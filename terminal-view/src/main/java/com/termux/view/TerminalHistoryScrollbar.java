package com.termux.view;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import com.termux.terminal.CommandMarker;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalHistory;
import java.util.Collections;
import java.util.List;

/** Presentation and gestures only. All retained command state belongs to the terminal buffer. */
final class TerminalHistoryScrollbar {
    private static final long TOOLTIP_MILLIS = 1600;
    private final TerminalView view;
    private final float dp;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF capsule = new RectF(), railTouch = new RectF(), thumb = new RectF(), previous = new RectF(), next = new RectF();
    private final Drawable completed, running, failed, chevron, chevronUp, edgeArrow, edgeArrowUp;
    private final Runnable clearTooltip;
    private ValueAnimator animator;
    private float reveal;
    private float animationTarget;
    private float trackTop, trackBottom, thumbHeight, downX, downY, lastX, lastY, grabOffset;
    private float swipeDistance;
    private boolean shown, swipeResolved;
    private enum TouchAxis { UNDECIDED, HORIZONTAL, VERTICAL }
    private TouchAxis touchAxis = TouchAxis.UNDECIDED;
    private long lastMove;
    private float releaseSpeed;
    private boolean captured, dragging, cancelled;
    private int pointerId;
    private CommandMarker selected;
    private long tooltipUntil;
    private long lastOutput = -1, pendingOutput;
    private boolean wasBrowsing;
    private boolean snapEnabled = true;
    private long densityRevision = -1, densityScroll = -1;
    private TerminalHistory densityHistory;
    private int densityRows = -1, densityBins = -1;
    private List<HistoryScrollbarModel.Bucket> density = Collections.emptyList();

    TerminalHistoryScrollbar(TerminalView view) {
        this.view = view;
        clearTooltip = () -> { tooltipUntil = 0; view.invalidate(); };
        dp = view.getResources().getDisplayMetrics().density;
        completed = symbol(R.drawable.ic_history_check_circle);
        running = symbol(R.drawable.ic_history_play_arrow);
        failed = symbol(R.drawable.ic_history_block);
        chevron = symbol(R.drawable.ic_history_expand_more);
        chevronUp = symbol(R.drawable.ic_history_expand_more);
        edgeArrow = symbol(R.drawable.ic_history_vertical_align_bottom);
        edgeArrowUp = symbol(R.drawable.ic_history_vertical_align_bottom);
    }

    boolean eligible() {
        TerminalEmulator emulator = view.mEmulator;
        return emulator != null && !emulator.isHistoryViewportOwnedByApplication()
            && emulator.getScreen().getActiveTranscriptRows() > 0 && !view.isSelectingText();
    }

    boolean isVisible() { return eligible() && reveal > 0.05f; }
    boolean isDragging() { return dragging; }
    long getPendingOutput() { return pendingOutput; }
    void setSnapEnabled(boolean enabled) { snapEnabled = enabled; }

    void reset() {
        dispose();
        selected = null;
        densityRevision = -1;
        densityHistory = null;
        lastOutput = -1;
        pendingOutput = 0;
        wasBrowsing = false;
    }

    void dispose() {
        view.removeCallbacks(clearTooltip);
        if (animator != null) animator.cancel();
        reveal = 0;
        shown = false;
        tooltipUntil = 0;
        resetSwipe();
        captured = dragging = false;
        if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
    }

    void screenUpdated() {
        if (view.mEmulator == null) return;
        long output = view.mEmulator.getTranscriptScrollCounter();
        boolean browsing = !view.mEmulator.isHistoryViewportOwnedByApplication() && view.getTopRow() < 0;
        if (browsing && wasBrowsing && lastOutput >= 0) pendingOutput += Math.max(0, output - lastOutput);
        if (!browsing) pendingOutput = 0;
        lastOutput = output;
        wasBrowsing = browsing;
        if (!eligible()) {
            view.removeCallbacks(clearTooltip);
            if (animator != null) animator.cancel();
            reveal = 0;
            shown = false;
            tooltipUntil = 0;
            if (captured) cancelled = true;
            dragging = false;
        }
    }

    private void show() {
        screenUpdated();
        if (!eligible()) return;
        shown = true;
        animateTo(1);
    }

    private void dismiss() {
        shown = false;
        tooltipUntil = 0;
        view.removeCallbacks(clearTooltip);
        animateTo(0);
    }

    void resetSwipe() { swipeDistance = 0; swipeResolved = false; }

    /** GestureDetector distance is positive when a finger travels left. One action per gesture. */
    void onHorizontalScroll(float distance) {
        if (!eligible() || swipeResolved) return;
        if (distance * swipeDistance < 0) swipeDistance = 0;
        swipeDistance += distance;
        if (Math.abs(swipeDistance) < 48 * dp) return;
        swipeResolved = true;
        if (swipeDistance > 0) show(); else dismiss();
    }

    void onViewportScrolled() { screenUpdated(); }

    private float slideOffset() { return (1 - reveal) * 48 * dp; }

    private void animateTo(float target) {
        if (animator != null && animator.isRunning() && animationTarget == target) return;
        if (animator != null) animator.cancel();
        animationTarget = target;
        if (reveal == target) { view.invalidate(); return; }
        animator = ValueAnimator.ofFloat(reveal, target);
        animator.setDuration(target == 1 ? 200 : 180);
        animator.setInterpolator(target == 1 ? new DecelerateInterpolator() : new AccelerateInterpolator());
        animator.addUpdateListener(value -> { reveal = (float) value.getAnimatedValue(); view.invalidate(); });
        animator.start();
    }

    private List<CommandMarker> markers() { return view.mEmulator.getHistory().getMarkers(); }
    private int historyRows() { return view.mEmulator.getScreen().getActiveTranscriptRows(); }

    private void layout() {
        float width = view.getWidth(), height = view.getHeight();
        float top = Math.max(0, view.getTerminalContentTopInset());
        capsule.set(width - 32 * dp, top + 8 * dp, width - 8 * dp, height - 8 * dp);
        railTouch.set(width - 48 * dp, capsule.top, width, capsule.bottom);
        float navigationHeight = Math.min(32 * dp, capsule.height() / 2);
        previous.set(railTouch.left, capsule.top, railTouch.right, capsule.top + navigationHeight);
        next.set(railTouch.left, capsule.bottom - navigationHeight, railTouch.right, capsule.bottom);
        trackTop = previous.bottom + 4 * dp;
        trackBottom = Math.max(trackTop + 1, next.top - 4 * dp);
        thumbHeight = HistoryScrollbarModel.thumbHeight(trackBottom - trackTop, view.mEmulator.mRows, historyRows(), 48 * dp);
        float y = trackTop + (trackBottom - trackTop - thumbHeight)
            * HistoryScrollbarModel.fraction(view.getTopRow(), historyRows());
        thumb.set(capsule.left + 2 * dp, y, capsule.right - 2 * dp, y + thumbHeight);
        paint.setTextSize(12 * view.getResources().getDisplayMetrics().scaledDensity);
        updateDensity();
    }

    void draw(Canvas canvas) {
        if (!eligible()) return;
        layout();
        int foreground = view.getHistoryScrollbarForeground();
        int surface = view.getHistoryScrollbarSurface();
        if (reveal <= 0) return;
        int saved = canvas.save();
        canvas.clipRect(0, 0, view.getWidth(), view.getHeight());
        canvas.translate(slideOffset(), 0);
        color(surface, 1);
        canvas.drawRoundRect(capsule, 12 * dp, 12 * dp, paint);
        color(foreground, 0.22f);
        paint.setStrokeWidth(dp);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRoundRect(capsule, 12 * dp, 12 * dp, paint);
        paint.setStyle(Paint.Style.FILL);
        color(foreground, 0.2f);
        canvas.drawRoundRect(thumb, 10 * dp, 10 * dp, paint);
        for (HistoryScrollbarModel.Bucket bucket : density) {
            float x = capsule.centerX(), y = bucketY(bucket);
            // Status is the only glyph; mixed clusters keep both states within the narrow capsule.
            if (bucket.running) drawSymbol(canvas, running, x, y - (bucket.failed ? 10 * dp : 0), 18, foreground, 1);
            if (bucket.failed) drawSymbol(canvas, failed, x, y + (bucket.running ? 10 * dp : 0), 18, foreground, 1);
            if (!bucket.running && !bucket.failed) drawSymbol(canvas, completed, x, y, 18, foreground, 1);
        }
        arrow(canvas, previous, true, foreground);
        arrow(canvas, next, false, foreground);
        canvas.restoreToCount(saved);
        if (shown && reveal == 1) {
            if (dragging) tooltip(canvas, view.getResources().getString(R.string.history_lines_ago, -view.getTopRow()), surface, foreground);
            else if (selected != null && SystemClock.uptimeMillis() < tooltipUntil) tooltip(canvas, markerLabel(selected), surface, foreground);
        }
    }

    private void updateDensity() {
        TerminalHistory history = view.mEmulator.getHistory();
        int bins = Math.max(1, (int) ((trackBottom - trackTop - thumbHeight) / (48 * dp)) + 1);
        long scroll = view.mEmulator.getTranscriptRowShiftCounter();
        if (densityHistory != history || densityRevision != history.getRevision() || densityRows != historyRows() || densityBins != bins || densityScroll != scroll) {
            density = HistoryScrollbarModel.aggregate(markers(), historyRows(), bins);
            densityHistory = history;
            densityRevision = history.getRevision(); densityRows = historyRows(); densityBins = bins; densityScroll = scroll;
        }
    }

    private float bucketY(HistoryScrollbarModel.Bucket bucket) {
        if (densityBins == 1) return (trackTop + trackBottom) / 2;
        return trackTop + thumbHeight / 2 + bucket.index * (trackBottom - trackTop - thumbHeight) / Math.max(1, densityBins - 1);
    }

    private CommandMarker hitMarker(float y) {
        for (HistoryScrollbarModel.Bucket bucket : density) {
            float center = bucketY(bucket);
            if (Math.abs(y - center) > 24 * dp) continue;
            if (bucket.running && (!bucket.failed || y < center)) return bucket.runningMarker;
            if (bucket.failed) return bucket.failedMarker;
            return bucket.marker;
        }
        return null;
    }

    private Drawable symbol(int resource) { return view.getContext().getDrawable(resource).mutate(); }

    private void drawSymbol(Canvas canvas, Drawable symbol, float x, float y, float size, int tint, float opacity) {
        float half = size * dp / 2;
        symbol.setBounds(Math.round(x - half), Math.round(y - half), Math.round(x + half), Math.round(y + half));
        symbol.setTint(tint);
        symbol.setAlpha(Math.round(255 * opacity));
        symbol.draw(canvas);
    }

    private void arrow(Canvas canvas, RectF bounds, boolean up, int foreground) {
        CommandMarker marker = navigationMarker(!up);
        boolean enabled = marker != null || view.getTopRow() != (up ? -historyRows() : 0);
        boolean showCount = !up && pendingOutput > 0;
        float y = bounds.centerY() - (showCount ? 5 * dp : 0);
        int saved = canvas.save();
        if (up) canvas.rotate(180, capsule.centerX(), y);
        // VectorDrawable keeps native render state: each direction needs its own alpha/bounds.
        Drawable glyph = marker == null ? (up ? edgeArrowUp : edgeArrow) : (up ? chevronUp : chevron);
        drawSymbol(canvas, glyph, capsule.centerX(), y, 18, foreground, enabled ? 1 : 0.38f);
        canvas.restoreToCount(saved);
        if (showCount) {
            String count = pendingOutput > 999 ? "999+" : Long.toString(pendingOutput);
            float textSize = paint.getTextSize();
            paint.setTextSize(8 * view.getResources().getDisplayMetrics().scaledDensity);
            float available = capsule.width() - 8 * dp;
            if (paint.measureText(count) > available) paint.setTextSize(paint.getTextSize() * available / paint.measureText(count));
            color(foreground, 1);
            canvas.drawText(count, capsule.centerX() - paint.measureText(count) / 2, bounds.bottom - 4 * dp, paint);
            paint.setTextSize(textSize);
        }
    }

    private CommandMarker navigationMarker(boolean forward) {
        if (HistoryScrollbarModel.atCommandBoundary(markers(), view.getTopRow(), forward)) return null;
        return HistoryScrollbarModel.adjacent(markers(), view.getTopRow(), forward, selected);
    }

    private void navigateViewport(boolean forward) {
        CommandMarker marker = navigationMarker(forward);
        if (marker == null) scrollToEdge(forward); else jump(marker);
    }

    boolean scrollToEdge(boolean forward) {
        if (!eligible()) return false;
        selected = null;
        tooltipUntil = 0;
        view.removeCallbacks(clearTooltip);
        if (forward) view.scrollToLatest(); else view.setHistoryTopRow(-historyRows());
        view.announceForAccessibility(view.getResources().getString(forward ? R.string.history_latest : R.string.history_top));
        return true;
    }

    private void color(int color, float opacity) { paint.setColor(color); paint.setAlpha(Math.round(255 * opacity)); }

    private void label(Canvas canvas, RectF bounds, String text, int surface, int foreground, float opacity) {
        color(surface, opacity);
        canvas.drawRoundRect(bounds, 16 * dp, 16 * dp, paint);
        color(foreground, opacity);
        text = TextUtils.ellipsize(text, paint, Math.max(0, bounds.width() - 16 * dp), TextUtils.TruncateAt.END).toString();
        int saved = canvas.save();
        canvas.clipRect(bounds.left + 8 * dp, bounds.top, bounds.right - 8 * dp, bounds.bottom);
        canvas.drawText(text, bounds.centerX() - paint.measureText(text) / 2,
            bounds.centerY() - (paint.ascent() + paint.descent()) / 2, paint);
        canvas.restoreToCount(saved);
    }

    private void tooltip(Canvas canvas, String text, int surface, int foreground) {
        float right = capsule.left - 8 * dp;
        float width = Math.min(right, paint.measureText(text) + 24 * dp);
        float center = Math.max(24 * dp, Math.min(view.getHeight() - 76 * dp, thumb.centerY()));
        RectF bounds = new RectF(right - width, center - 24 * dp, right, center + 24 * dp);
        label(canvas, bounds, text, surface, foreground, 1);
    }

    private String markerLabel(CommandMarker marker) {
        String name = marker.getCommandName();
        return name != null ? name : view.getResources().getString(R.string.history_command);
    }

    boolean touch(MotionEvent event) {
        if (!eligible()) {
            // Consume the remainder of an already captured drag if an application takes over.
            if (!captured) return false;
            cancelled = true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!eligible() || event.getPointerCount() != 1) return false;
            layout();
            if (!shown || !isVisible() || !railTouch.contains(event.getX() - slideOffset(), event.getY())) return false;
            captured = true;
            cancelled = dragging = false;
            pointerId = event.getPointerId(0);
            downX = lastX = event.getX();
            downY = lastY = event.getY();
            touchAxis = TouchAxis.UNDECIDED;
            resetSwipe();
            lastMove = event.getEventTime();
            releaseSpeed = 0;
            boolean thumbPressed = thumb.top <= event.getY() && event.getY() <= thumb.bottom;
            grabOffset = thumbPressed ? event.getY() - thumb.top : thumbHeight / 2;
            view.stopHistoryScrollAnimation();
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (!captured) return false;
        if (action == MotionEvent.ACTION_POINTER_DOWN) cancelled = true;
        int index = event.findPointerIndex(pointerId);
        if (index < 0) cancelled = true;
        if (action == MotionEvent.ACTION_MOVE && !cancelled) {
            float x = event.getX(index);
            float y = event.getY(index);
            float dx = x - downX, dy = y - downY;
            if (touchAxis == TouchAxis.UNDECIDED && Math.max(Math.abs(dx), Math.abs(dy)) > ViewConfiguration.get(view.getContext()).getScaledTouchSlop())
                touchAxis = Math.abs(dx) > Math.abs(dy) ? TouchAxis.HORIZONTAL : TouchAxis.VERTICAL;
            if (touchAxis == TouchAxis.HORIZONTAL) {
                if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                    // A rail swipe can reach the right edge before travelling 48dp. Axis lock
                    // already crossed touch slop, so allow it to dismiss within the hit target.
                    if (dx > 0 && !swipeResolved) { dismiss(); swipeResolved = true; }
                    else onHorizontalScroll(lastX - x);
                }
                lastX = x;
                return true;
            }
            if (touchAxis == TouchAxis.VERTICAL) dragging = true;
            if (dragging) {
                layout();
                long elapsed = event.getEventTime() - lastMove;
                if (elapsed > 0) releaseSpeed = Math.abs(y - lastY) * 1000 / elapsed;
                lastY = y; lastMove = event.getEventTime();
                int row = HistoryScrollbarModel.rowAt((y - grabOffset - trackTop)
                    / Math.max(1, trackBottom - trackTop - thumbHeight), historyRows());
                selected = null;
                view.setHistoryTopRow(row);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!cancelled && action == MotionEvent.ACTION_UP && touchAxis != TouchAxis.HORIZONTAL) {
                if (dragging) {
                    if (snapEnabled && releaseSpeed < 600 * dp && view.getTopRow() < 0 && view.getTopRow() > -historyRows()) {
                        CommandMarker marker = HistoryScrollbarModel.nearest(markers(), view.getTopRow(), 2);
                        if (marker != null) jump(marker);
                    }
                } else if (previous.contains(event.getX() - slideOffset(), event.getY())) navigateViewport(false);
                else if (next.contains(event.getX() - slideOffset(), event.getY())) navigateViewport(true);
                else if (railTouch.contains(event.getX() - slideOffset(), event.getY())) {
                    layout();
                    CommandMarker marker = hitMarker(event.getY());
                    if (marker != null) jump(marker);
                }
            }
            captured = dragging = false;
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
            resetSwipe();
            return true;
        }
        return true;
    }

    boolean navigate(boolean forward) {
        if (!eligible()) return false;
        return jump(HistoryScrollbarModel.adjacent(markers(), view.getTopRow(), forward, selected));
    }

    boolean jump(CommandMarker marker) {
        if (!eligible() || marker == null || !markers().contains(marker)) return false;
        Integer row = marker.anchor.getRow();
        if (row == null) return false;
        view.setHistoryTopRow(row);
        selected = marker;
        tooltipUntil = SystemClock.uptimeMillis() + TOOLTIP_MILLIS;
        view.removeCallbacks(clearTooltip);
        view.postDelayed(clearTooltip, TOOLTIP_MILLIS);
        show();
        view.announceForAccessibility(markerLabel(marker));
        return true;
    }
}
