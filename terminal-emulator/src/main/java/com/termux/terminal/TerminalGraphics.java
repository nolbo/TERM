package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Per-screen kitty/SIXEL state. No process, path, descriptor or Android lifecycle ownership. */
public final class TerminalGraphics {
    public static final int MAX_TRANSFER_BYTES = 6 * 1024 * 1024;
    private static final int MAX_STORED_PIXELS = 8 * TerminalImage.MAX_PIXELS;
    private static final int MAX_IMAGES = 64, MAX_PLACEMENTS = 128, MAX_PARENT_DEPTH = 32;
    private static final Comparator<Placement> LAYERS = Comparator.comparingInt((Placement p) -> p.z)
        .thenComparingLong(p -> p.imageId);
    private final Map<Long, KittyAnimation> images = new LinkedHashMap<>();
    private final Map<Long, Long> imageNumbers = new HashMap<>();
    private final List<Placement> placements = new ArrayList<>();
    private Map<String, String> pending;
    private ByteArrayOutputStream transfer;
    private long nextAnonymousId = -1, nextImageId = 1;
    private int screenRows = 24, screenColumns = 80;
    private TerminalBuffer textScreen;
    private long animationDelay = -1;

    public static final class Placement {
        public TerminalImage image;
        public final long imageId, placementId;
        public final int columns, rows, z;
        public final float widthCells, heightCells, offsetXCells, offsetYCells;
        public final int sourceX, sourceY, sourceWidth, sourceHeight;
        public final boolean virtual;
        public int column, row;
        // Local cell clipping survives scroll-region clipping and is also used for placeholder tiles.
        public float clipLeft, clipTop, clipRight, clipBottom;
        Placement parent;
        final int horizontal, vertical;
        boolean positioned = true, sixel;

        Placement(TerminalImage image, long id, long pid, int column, int row, float width, float height,
                  int z, int sx, int sy, int sw, int sh, float ox, float oy, boolean virtual,
                  Placement parent, int horizontal, int vertical) {
            this.image = image; imageId = id; placementId = pid; this.column = column; this.row = row;
            widthCells = width; heightCells = height;
            columns = (int) Math.ceil(width + ox); rows = (int) Math.ceil(height + oy);
            this.z = z; sourceX = sx; sourceY = sy; sourceWidth = sw; sourceHeight = sh;
            offsetXCells = ox; offsetYCells = oy; this.virtual = virtual; this.parent = parent;
            this.horizontal = horizontal; this.vertical = vertical;
            clipRight = width + ox; clipBottom = height + oy;
        }

        Placement tile(int column, int row, int imageColumn, int imageRow) {
            Placement p = new Placement(image, imageId, placementId, column - imageColumn, row - imageRow,
                widthCells, heightCells, z, sourceX, sourceY, sourceWidth, sourceHeight,
                offsetXCells, offsetYCells, false, null, 0, 0);
            p.clipLeft = imageColumn; p.clipTop = imageRow;
            p.clipRight = imageColumn + 1; p.clipBottom = imageRow + 1;
            return p;
        }

        boolean intersects(float x1, float y1, float x2, float y2) {
            return positioned && column + clipLeft < x2 && column + clipRight > x1 &&
                row + clipTop < y2 && row + clipBottom > y1;
        }
    }

    public List<Placement> getPlacements() { return Collections.unmodifiableList(placements); }
    public long getAnimationDelayMillis() { return animationDelay; }
    public void reset() {
        images.clear(); imageNumbers.clear(); placements.clear(); cancelTransfer(); animationDelay = -1;
    }
    void cancelTransfer() { pending = null; transfer = null; }
    void setTextScreen(TerminalBuffer screen, int rows, int columns) {
        textScreen = screen; screenRows = rows; screenColumns = columns;
    }
    int screenRows() { return screenRows; }
    boolean hasRelativeVirtualParent() {
        for (Placement p : placements) if (p.parent != null && p.parent.virtual) return true;
        return false;
    }
    void eraseText(int x, int y, int width, int height) {
        removePlacements(p -> p.sixel && p.intersects(x, y, (float) x + width, (float) y + height));
    }

    /** Only ED/DECSED screen clearing calls this; ordinary text erasure does not erase kitty images. */
    void erase(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (textScreen != null && placements.stream().anyMatch(p -> p.parent != null))
            getRenderPlacements(textScreen, 0, screenRows, screenColumns, System.nanoTime() / 1_000_000);
        removePlacements(p -> !p.virtual && p.intersects(x, y, (float) x + width, (float) y + height));
    }

    void scroll(int top, int bottom, int left, int right, int delta, int history) {
        Set<Placement> expired = new HashSet<>();
        boolean fullScreen = top == 0 && bottom == screenRows && left == 0 && right == screenColumns;
        for (Placement p : placements) {
            if (p.virtual || p.parent != null) continue;
            if (top == 0 && history > 0) {
                if (p.row < bottom) p.row += delta;
            } else if (fullScreen || (p.column + p.clipLeft >= left && p.column + p.clipRight <= right &&
                       p.row + p.clipTop >= top && p.row + p.clipBottom <= bottom)) {
                p.row += delta;
                p.clipTop = Math.max(p.clipTop, top - p.row);
                p.clipBottom = Math.min(p.clipBottom, bottom - p.row);
            }
            if (p.clipTop >= p.clipBottom || (history > 0 && p.row + p.clipBottom <= -history)) expired.add(p);
        }
        removePlacements(expired::contains);
    }

    void resize(int rowDelta, int columns, int rows, int history) {
        screenRows = rows;
        for (Placement p : placements) if (!p.virtual && p.parent == null) p.row += rowDelta;
        removePlacements(p -> !p.virtual && p.parent == null && p.row + p.clipBottom <= -history);
    }

    private void removePlacements(Predicate<Placement> predicate) {
        placements.removeIf(predicate);
        Set<Long> orphaned = new HashSet<>();
        boolean removed;
        do {
            removed = placements.removeIf(p -> {
                if (p.parent != null && !placements.contains(p.parent)) { orphaned.add(p.imageId); return true; }
                return false;
            });
        } while (removed);
        images.keySet().removeIf(id -> (id < 0 || orphaned.contains(id)) && !hasPlacements(id));
        imageNumbers.keySet().retainAll(images.keySet());
    }

    private boolean hasPlacements(long id) { return placements.stream().anyMatch(p -> p.imageId == id); }

    Placement sixel(String data, int background, int col, int row, int cellWidth, int cellHeight) {
        TerminalImage image = SixelDecoder.decode(data, background);
        long id = nextAnonymousId--;
        store(id, image, 0);
        Placement p = new Placement(image, id, 0, col, row, (float) image.width / Math.max(1, cellWidth),
            (float) image.height / Math.max(1, cellHeight), 0, 0, 0, image.width, image.height,
            0, 0, false, null, 0, 0);
        p.sixel = true;
        addPlacement(p);
        return p;
    }

    static final class Result {
        String response;
        Placement placement;
        boolean moveCursor;
    }

    Result kitty(String command, int col, int row, int cellWidth, int cellHeight) {
        return kitty(command, col, row, cellWidth, cellHeight, System.nanoTime() / 1_000_000);
    }

    Result kitty(String command, int col, int row, int cellWidth, int cellHeight, long now) {
        Result result = new Result();
        Map<String, String> keys = new LinkedHashMap<>();
        try {
            int separator = command.indexOf(';');
            String header = separator < 0 ? command : command.substring(0, separator);
            String payload = separator < 0 ? "" : command.substring(separator + 1);
            if (header.length() > 1024) throw new IllegalArgumentException("E2BIG: header");
            if (!header.isEmpty()) for (String item : header.split(",", -1)) {
                if (item.length() < 3 || item.charAt(1) != '=' || keys.put(item.substring(0, 1), item.substring(2)) != null)
                    throw new IllegalArgumentException("EINVAL: header");
            }
            if (keys.getOrDefault("a", "t").equals("d")) cancelTransfer();
            else if (pending != null) {
                Map<String, String> continuation = keys;
                keys = new LinkedHashMap<>(pending);
                for (String key : continuation.keySet()) {
                    if (!key.equals("m") && !key.equals("q") &&
                        !(key.equals("a") && "f".equals(continuation.get(key)) && "f".equals(keys.get(key))))
                        throw new IllegalArgumentException("EINVAL: chunk metadata");
                }
                keys.put("m", continuation.getOrDefault("m", "0"));
                if (continuation.containsKey("q")) keys.put("q", continuation.get("q"));
            }
            validate(keys);
            String action = keys.getOrDefault("a", "t");
            long requestedId = number(keys, "i", 0), imageNumber = number(keys, "I", 0);
            if (keys.containsKey("i") && keys.containsKey("I")) throw new IllegalArgumentException("EINVAL: id and number");
            long id = requestedId;
            if (imageNumber != 0 && !action.equals("t") && !action.equals("T") && !action.equals("q"))
                id = newestImage(imageNumber);
            if (textScreen != null && (action.equals("d") || keys.containsKey("P")))
                getRenderPlacements(textScreen, 0, screenRows, screenColumns, now);
            if (action.equals("d")) {
                delete(keys, id, col, row, now);
                return result; // Kitty delete commands do not acknowledge success.
            }
            if (action.equals("t") || action.equals("T") || action.equals("q") || action.equals("f")) {
                if (!keys.getOrDefault("t", "d").equals("d"))
                    throw new IllegalArgumentException("ENOTSUP: use direct transmission");
                if (payload.length() > 4096 || (number(keys, "m", 0) == 1 && payload.length() % 4 != 0))
                    throw new IllegalArgumentException("EINVAL: chunk size");
                byte[] bytes = Base64.getDecoder().decode(payload);
                if (transfer == null) transfer = new ByteArrayOutputStream();
                if (transfer.size() + bytes.length > MAX_TRANSFER_BYTES) throw new IllegalArgumentException("E2BIG: transfer");
                transfer.write(bytes, 0, bytes.length);
                if (number(keys, "m", 0) == 1) { pending = keys; return result; }
                byte[] data = transfer.toByteArray();
                cancelTransfer();
                if (keys.containsKey("o")) data = inflate(data);
                TerminalImage image = decode(data, keys);
                if (action.equals("f")) {
                    KittyAnimation animation = requireImage(id);
                    if (number(keys, "r", 0) == 0 && storedPixels() + animation.frames.get(0).pixels.length > MAX_STORED_PIXELS)
                        throw new IllegalArgumentException("ENOSPC: animation pixels");
                    long rgba = number(keys, "Y", 0);
                    animation.addOrEdit(image, integer(keys, "x", 0), integer(keys, "y", 0),
                        number(keys, "r", 0), number(keys, "c", 0), (int) ((rgba >>> 8) | (rgba << 24)),
                        number(keys, "X", 0) == 1, signed(keys, "z", 0), now);
                    keys.put("r", Long.toString(number(keys, "r", 0) == 0 ? animation.frames.size() : number(keys, "r", 0)));
                } else if (!action.equals("q")) {
                    if (imageNumber != 0) id = allocateImageId();
                    else if (id == 0) id = nextAnonymousId--;
                    store(id, image, imageNumber);
                }
            } else if (action.equals("a")) {
                requireImage(id).control(number(keys, "c", 0), number(keys, "r", 0), signed(keys, "z", 0),
                    integer(keys, "s", 0), number(keys, "v", 0), now);
            } else if (action.equals("c")) {
                KittyAnimation animation = requireImage(id);
                TerminalImage root = animation.frames.get(0);
                animation.compose(number(keys, "r", 0), number(keys, "c", 0), integer(keys, "X", 0),
                    integer(keys, "Y", 0), integer(keys, "x", 0), integer(keys, "y", 0),
                    nonzero(keys, "w", root.width), nonzero(keys, "h", root.height), number(keys, "C", 0) == 1);
            } else if (!action.equals("p")) throw new IllegalArgumentException("EINVAL: action");
            if (action.equals("T") || action.equals("p")) {
                result.placement = placement(keys, id, col, row, Math.max(1, cellWidth), Math.max(1, cellHeight));
                addPlacement(result.placement);
                result.moveCursor = !result.placement.virtual && result.placement.parent == null && number(keys, "C", 0) == 0;
            }
            if (id > 0) keys.put("i", Long.toString(id));
            refreshFrames(now);
            if (!action.equals("a")) reply(result, keys, "OK");
        } catch (IllegalArgumentException error) {
            cancelTransfer();
            String message = error.getMessage();
            reply(result, keys, message != null && message.matches("E[A-Z0-9]+: [a-z ]+") ? message : "EINVAL: graphics command");
        }
        return result;
    }

    private Placement placement(Map<String, String> keys, long id, int col, int row, int cw, int ch) {
        TerminalImage image = requireImage(id).image();
        int sx = Math.min(image.width, integer(keys, "x", 0)), sy = Math.min(image.height, integer(keys, "y", 0));
        int sw = Math.min(image.width - sx, nonzero(keys, "w", image.width));
        int sh = Math.min(image.height - sy, nonzero(keys, "h", image.height));
        int columns = integer(keys, "c", 0), rows = integer(keys, "r", 0);
        if (columns > 4096 || rows > 4096) throw new IllegalArgumentException("E2BIG: placement");
        int ox = integer(keys, "X", 0), oy = integer(keys, "Y", 0);
        if (ox >= cw || oy >= ch) throw new IllegalArgumentException("EINVAL: cell offset");
        // X/Y consume space inside an explicitly sized rectangle; they do not enlarge c/r.
        float width = columns > 0 ? columns - (float) ox / cw : (float) sw / cw;
        float height = rows > 0 ? rows - (float) oy / ch : (float) sh / ch;
        if (columns > 0 && rows == 0 && sw > 0) height = width * cw * sh / sw / ch;
        if (rows > 0 && columns == 0 && sh > 0) width = height * ch * sw / sh / cw;
        if (width > 4096 || height > 4096) throw new IllegalArgumentException("E2BIG: placement");
        boolean virtual = number(keys, "U", 0) == 1;
        long parentImage = number(keys, "P", 0), parentId = number(keys, "Q", 0);
        if (virtual && parentImage != 0) throw new IllegalArgumentException("EINVAL: virtual parent");
        if (parentImage == 0 && parentId != 0) throw new IllegalArgumentException("ENOPARENT: placement");
        long pid = id < 0 ? 0 : number(keys, "p", 0);
        Placement parent = parentImage == 0 ? null : findPlacement(parentImage, parentId, false);
        if (parentImage != 0 && parent == null) throw new IllegalArgumentException("ENOPARENT: placement");
        int depth = 0;
        for (Placement p = parent; p != null; p = p.parent) {
            if (p.imageId == id && pid != 0 && p.placementId == pid) throw new IllegalArgumentException("ECYCLE: placement");
            if (++depth > MAX_PARENT_DEPTH) throw new IllegalArgumentException("ETOODEEP: placement");
        }
        Placement replaced = pid == 0 ? null : findPlacement(id, pid, false);
        if (replaced != null) for (Placement child : placements) {
            int descendants = 0;
            for (Placement ancestor = child.parent; ancestor != null; ancestor = ancestor.parent) {
                descendants++;
                if (ancestor == replaced && descendants + depth > MAX_PARENT_DEPTH)
                    throw new IllegalArgumentException("ETOODEEP: placement");
            }
        }
        return new Placement(image, id, pid, col, row, width, height, signed(keys, "z", 0), sx, sy, sw, sh,
            (float) ox / cw, (float) oy / ch, virtual, parent, signed(keys, "H", 0), signed(keys, "V", 0));
    }

    Placement findPlacement(long id, long pid, boolean virtualOnly) {
        Placement result = null;
        for (Placement p : placements) if (p.imageId == id && (pid == 0 || p.placementId == pid) && (!virtualOnly || p.virtual)) result = p;
        return result;
    }

    private void addPlacement(Placement placement) {
        Placement old = placement.placementId == 0 ? null : findPlacement(placement.imageId, placement.placementId, false);
        if (old != null) {
            for (Placement child : placements) if (child.parent == old) child.parent = placement;
            placements.remove(old);
        }
        placements.add(placement);
        if (placements.size() > MAX_PLACEMENTS) {
            Placement oldest = placements.get(0);
            removePlacements(p -> p == oldest);
        }
        placements.sort(LAYERS);
    }

    private void delete(Map<String, String> keys, long id, int col, int row, long now) {
        char selector = keys.getOrDefault("d", "a").charAt(0);
        char kind = Character.toLowerCase(selector);
        long pid = number(keys, "p", 0), x = number(keys, "x", 0), y = number(keys, "y", 0);
        int z = signed(keys, "z", 0);
        if (kind == 'f') {
            KittyAnimation animation = images.get(id);
            if (animation != null) {
                if (animation.frames.size() == 1 && selector == 'F') removeImage(id);
                else animation.delete(Math.max(1, Math.min(animation.frames.size(), number(keys, "r", 1))), now);
            }
            refreshFrames(now); return;
        }
        boolean virtualAllowed = kind == 'i' || kind == 'n' || kind == 'r';
        Set<Long> candidates = new HashSet<>();
        if (kind == 'i' || kind == 'n') candidates.add(id);
        if (kind == 'r') for (long candidate : images.keySet()) if (candidate >= x && candidate <= y) candidates.add(candidate);
        removePlacements(p -> {
            if (p.virtual && !virtualAllowed) return false;
            boolean match;
            switch (kind) {
                case 'a': match = p.intersects(0, 0, Float.MAX_VALUE, screenRows); break;
                case 'i': case 'n': match = p.imageId == id && (pid == 0 || p.placementId == pid); break;
                case 'r': match = p.imageId >= x && p.imageId <= y; break;
                case 'c': match = p.intersects(col, row, col + 1f, row + 1f); break;
                case 'p': case 'q': match = p.intersects(x - 1, y - 1, x, y) && (kind != 'q' || p.z == z); break;
                case 'x': match = p.intersects(x - 1, -Float.MAX_VALUE, x, Float.MAX_VALUE); break;
                case 'y': match = p.intersects(-Float.MAX_VALUE, y - 1, Float.MAX_VALUE, y); break;
                case 'z': match = p.z == z; break;
                default: throw new IllegalArgumentException("EINVAL: delete selector");
            }
            if (match) candidates.add(p.imageId);
            return match;
        });
        if (Character.isUpperCase(selector)) for (long candidate : candidates) if (!hasPlacements(candidate)) {
            images.remove(candidate); imageNumbers.remove(candidate);
        }
    }

    private long newestImage(long number) {
        long id = 0;
        for (long candidate : images.keySet()) if (imageNumbers.getOrDefault(candidate, 0L) == number) id = candidate;
        return id;
    }

    private long allocateImageId() {
        do {
            long id = nextImageId++;
            if (nextImageId > 0xffffffffL) nextImageId = 1;
            if (!images.containsKey(id)) return id;
        } while (true);
    }

    private KittyAnimation requireImage(long id) {
        KittyAnimation image = images.get(id);
        if (image == null) throw new IllegalArgumentException("ENOENT: image");
        return image;
    }

    private int storedPixels() { int pixels = 0; for (KittyAnimation image : images.values()) pixels += image.pixels(); return pixels; }
    private void removeImage(long id) {
        images.remove(id); imageNumbers.remove(id); removePlacements(p -> p.imageId == id);
    }
    private void store(long id, TerminalImage image, long number) {
        removeImage(id);
        while (!images.isEmpty() && (images.size() >= MAX_IMAGES || storedPixels() + image.pixels.length > MAX_STORED_PIXELS)) {
            long oldest = images.keySet().iterator().next();
            for (long candidate : images.keySet()) if (!hasPlacements(candidate)) { oldest = candidate; break; }
            removeImage(oldest);
        }
        images.put(id, new KittyAnimation(image));
        if (number != 0) imageNumbers.put(id, number);
    }

    private void refreshFrames(long now) {
        animationDelay = -1;
        for (Map.Entry<Long, KittyAnimation> entry : images.entrySet()) {
            long delay = entry.getValue().tick(now);
            if (delay >= 0 && hasPlacements(entry.getKey())) animationDelay = animationDelay < 0 ? delay : Math.min(animationDelay, delay);
        }
        for (Placement p : placements) p.image = requireImage(p.imageId).image();
    }

    /** Resolves Unicode tiles and relative anchors from the current text, including after reflow. */
    public List<Placement> getRenderPlacements(TerminalBuffer screen, int topRow, int endRow, int columns, long now) {
        refreshFrames(now);
        for (Placement p : placements) if (p.virtual) { p.positioned = false; p.column = p.row = Integer.MAX_VALUE; }
        List<Placement> result = KittyPlaceholders.resolve(this, screen, topRow, endRow, columns);
        for (Placement p : placements) {
            if (p.virtual) continue;
            resolveParent(p, 0);
            if (p.positioned) result.add(p);
        }
        result.sort(LAYERS);
        animationDelay = -1;
        for (Placement p : result) if (p.intersects(0, topRow, columns, endRow)) {
            long delay = requireImage(p.imageId).tick(now);
            if (delay >= 0) animationDelay = animationDelay < 0 ? delay : Math.min(animationDelay, delay);
        }
        return result;
    }

    private void resolveParent(Placement p, int depth) {
        if (p.parent == null) { p.positioned = true; return; }
        if (depth > MAX_PARENT_DEPTH) { p.positioned = false; return; }
        if (!p.parent.virtual) resolveParent(p.parent, depth + 1);
        p.positioned = p.parent.positioned;
        if (p.positioned) {
            p.column = boundedCoordinate((long) p.parent.column + p.horizontal);
            p.row = boundedCoordinate((long) p.parent.row + p.vertical);
        }
    }
    private static int boundedCoordinate(long n) { return (int) Math.max(-1_000_000, Math.min(1_000_000, n)); }

    static void validate(Map<String, String> keys) {
        for (String key : keys.keySet()) {
            String value = keys.get(key);
            if ("atod".contains(key)) {
                String allowed = key.equals("a") ? "atTfpcqd" : key.equals("t") ? "dfts" : key.equals("o") ? "z" : "aAiInNcCfFpPqQrRxXyYzZ";
                if (value.length() != 1 || !allowed.contains(value)) throw new IllegalArgumentException("EINVAL: control value");
            } else if ("zHV".contains(key)) signed(keys, key, 0);
            else if ("fsvSOiIpqmNxywhXYcrCUPQ".contains(key)) number(keys, key, 0);
            else throw new IllegalArgumentException("EINVAL: unknown key");
        }
        if ("f".equals(keys.get("a")) && number(keys, "X", 0) > 1) throw new IllegalArgumentException("EINVAL: blend mode");
        if (number(keys, "q", 0) > 2 || number(keys, "m", 0) > 1 || number(keys, "C", 0) > 1 || number(keys, "U", 0) > 1)
            throw new IllegalArgumentException("EINVAL: control value");
    }

    static void reply(Result result, Map<String, String> keys, String message) {
        String quiet = keys.getOrDefault("q", "0");
        if (quiet.equals("2") || (quiet.equals("1") && message.equals("OK"))) return;
        String id = responseNumber(keys.get("i")), number = responseNumber(keys.get("I"));
        if (id == null && number == null) return;
        String pid = responseNumber(keys.get("p"));
        String frame = ("f".equals(keys.get("a")) || "a".equals(keys.get("a"))) ? responseNumber(keys.get("r")) : null;
        result.response = "\033_G" + (id != null ? "i=" + id : "i=0") +
            (number == null ? "" : ",I=" + number) + (pid == null ? "" : ",p=" + pid) + (frame == null ? "" : ",r=" + frame) + ";" + message + "\033\\";
    }
    private static String responseNumber(String value) {
        if (value == null || !value.matches("[0-9]{1,10}")) return null;
        long n = Long.parseLong(value); return n > 0 && n <= 0xffffffffL ? Long.toString(n) : null;
    }
    private static int integer(Map<String, String> keys, String key, int fallback) {
        long n = number(keys, key, fallback);
        if (n > Integer.MAX_VALUE) throw new IllegalArgumentException("E2BIG: number");
        return (int) n;
    }
    private static int nonzero(Map<String, String> keys, String key, int fallback) {
        int n = integer(keys, key, fallback); return n == 0 ? fallback : n;
    }
    private static int signed(Map<String, String> keys, String key, int fallback) {
        if (!keys.containsKey(key)) return fallback;
        String value = keys.get(key);
        if (!value.matches("-?[0-9]{1,10}")) throw new IllegalArgumentException("EINVAL: number");
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { throw new IllegalArgumentException("EINVAL: number"); }
    }
    private static long number(Map<String, String> keys, String key, long fallback) {
        if (!keys.containsKey(key)) return fallback;
        String value = keys.get(key);
        if (!value.matches("[0-9]{1,10}")) throw new IllegalArgumentException("EINVAL: number");
        long n = Long.parseLong(value);
        if (n > 0xffffffffL) throw new IllegalArgumentException("EINVAL: number");
        return n;
    }
    private static byte[] inflate(byte[] bytes) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytes);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n == 0 || output.size() + n > MAX_TRANSFER_BYTES) throw new IllegalArgumentException("E2BIG: compressed data");
                output.write(chunk, 0, n);
            }
            return output.toByteArray();
        } catch (DataFormatException error) { throw new IllegalArgumentException("EINVAL: compression"); }
        finally { inflater.end(); }
    }

    private static TerminalImage decode(byte[] data, Map<String, String> keys) {
        long format = number(keys, "f", 32);
        if (format == 100) {
            if (data.length < 8 || data[0] != (byte) 137 || data[1] != 'P' || data[2] != 'N' || data[3] != 'G')
                throw new IllegalArgumentException("EINVAL: png");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            TerminalImage.checkSize(options.outWidth, options.outHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null) throw new IllegalArgumentException("EINVAL: png");
            try {
                TerminalImage.checkSize(bitmap.getWidth(), bitmap.getHeight());
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                return new TerminalImage(bitmap.getWidth(), bitmap.getHeight(), pixels);
            } finally { bitmap.recycle(); }
        }
        if (format != 24 && format != 32) throw new IllegalArgumentException("ENOTSUP: pixel format");
        long w = number(keys, "s", 0), h = number(keys, "v", 0);
        if (w > TerminalImage.MAX_DIMENSION || h > TerminalImage.MAX_DIMENSION) throw new IllegalArgumentException("E2BIG: dimensions");
        int width = (int) w, height = (int) h;
        TerminalImage.checkSize(width, height);
        int channels = (int) format / 8;
        if (data.length != width * height * channels) throw new IllegalArgumentException("EINVAL: pixel count");
        int[] pixels = new int[width * height];
        for (int i = 0, offset = 0; i < pixels.length; i++, offset += channels) {
            int alpha = channels == 4 ? data[offset + 3] & 255 : 255;
            pixels[i] = alpha << 24 | (data[offset] & 255) << 16 | (data[offset + 1] & 255) << 8 | data[offset + 2] & 255;
        }
        return new TerminalImage(width, height, pixels);
    }
}
