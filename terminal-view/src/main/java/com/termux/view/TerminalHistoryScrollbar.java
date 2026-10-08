package com.termux.view;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import com.termux.terminal.CommandMarker;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalHistory;
import java.util.Collections;
import java.util.List;

/** Presentation and gestures only. All retained command state belongs to the terminal buffer. */
final class TerminalHistoryScrollbar {
    private static final long IDLE_MILLIS = 1600;
    private final TerminalView view;
    private final float dp;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF capsule = new RectF(), railTouch = new RectF(), thumb = new RectF(), previous = new RectF(), next = new RectF(), latest = new RectF();
    private final Drawable completed, running, failed, chevron;
    private final Runnable hide = () -> animateTo(0);
    private ValueAnimator animator;
    private float alpha;
    private float animationTarget;
    private float trackTop, trackBottom, thumbHeight, downY, lastY, grabOffset;
    private long lastMove;
    private float releaseSpeed;
    private boolean captured, dragging, cancelled, railPressed;
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
        dp = view.getResources().getDisplayMetrics().density;
        completed = symbol(R.drawable.ic_history_check_circle);
        running = symbol(R.drawable.ic_history_play_arrow);
        failed = symbol(R.drawable.ic_history_block);
        chevron = symbol(R.drawable.ic_history_expand_more);
    }

    boolean eligible() {
        TerminalEmulator emulator = view.mEmulator;
        return emulator != null && !emulator.isHistoryViewportOwnedByApplication()
            && emulator.getScreen().getActiveTranscriptRows() > 0 && !view.isSelectingText();
    }

    boolean isVisible() { return eligible() && alpha > 0.05f; }
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
        view.removeCallbacks(hide);
        if (animator != null) animator.cancel();
        alpha = 0;
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
            view.removeCallbacks(hide);
            if (animator != null) animator.cancel();
            alpha = 0;
            if (captured) cancelled = true;
            dragging = false;
        }
    }

    void awaken() {
        screenUpdated();
        if (!eligible()) return;
        view.removeCallbacks(hide);
        animateTo(1);
        if (!captured) view.postDelayed(hide, IDLE_MILLIS);
    }

    void onViewportScrolled() {
        screenUpdated();
        // Ordinary scrolling keeps an open capsule alive, but only a leftward swipe reveals it.
        if (alpha > 0 || animator != null && animator.isRunning() && animationTarget == 1) awaken();
    }

    private void animateTo(float target) {
        if (alpha == target || animator != null && animator.isRunning() && animationTarget == target) return;
        if (animator != null) animator.cancel();
        animationTarget = target;
        animator = ValueAnimator.ofFloat(alpha, target);
        animator.setDuration(target == 1 ? 120 : 220);
        animator.addUpdateListener(value -> { alpha = (float) value.getAnimatedValue(); view.invalidate(); });
        animator.start();
    }

    private List<CommandMarker> markers() { return view.mEmulator.getHistory().getMarkers(); }
    private int historyRows() { return view.mEmulator.getScreen().getActiveTranscriptRows(); }

    private void layout() {
        float width = view.getWidth(), height = view.getHeight();
        float top = Math.max(0, view.getTerminalContentTopInset());
        boolean navigation = !markers().isEmpty();
        capsule.set(width - 32 * dp, top + 8 * dp, width - 8 * dp, height - 8 * dp);
        railTouch.set(width - 48 * dp, capsule.top, width, capsule.bottom);
        previous.set(railTouch.left, capsule.top, railTouch.right, capsule.top + 48 * dp);
        next.set(railTouch.left, capsule.bottom - 48 * dp, railTouch.right, capsule.bottom);
        trackTop = capsule.top + (navigation ? 52 : 4) * dp;
        trackBottom = Math.max(trackTop + 1, capsule.bottom - (navigation ? 52 : 4) * dp);
        thumbHeight = HistoryScrollbarModel.thumbHeight(trackBottom - trackTop, view.mEmulator.mRows, historyRows(), 48 * dp);
        float y = trackTop + (trackBottom - trackTop - thumbHeight)
            * HistoryScrollbarModel.fraction(view.getTopRow(), historyRows());
        thumb.set(capsule.left + 2 * dp, y, capsule.right - 2 * dp, y + thumbHeight);
        paint.setTextSize(12 * view.getResources().getDisplayMetrics().scaledDensity);
        String label = latestLabel();
        float labelRight = capsule.left - 8 * dp;
        float labelWidth = Math.min(labelRight, paint.measureText(label) + 24 * dp);
        latest.set(Math.max(0, labelRight - labelWidth), capsule.bottom - 48 * dp, labelRight, capsule.bottom);
        updateDensity();
    }

    private String latestLabel() {
        return pendingOutput > 0 ? view.getResources().getString(R.string.history_new_output, pendingOutput)
            : view.getResources().getString(R.string.history_latest);
    }

    void draw(Canvas canvas) {
        if (!eligible()) return;
        layout();
        int foreground = view.getHistoryScrollbarForeground();
        int surface = view.getHistoryScrollbarSurface();
        if (view.getTopRow() < 0) label(canvas, latest, latestLabel(), surface, foreground, 1);
        if (alpha <= 0) return;
        color(surface, alpha);
        canvas.drawRoundRect(capsule, 12 * dp, 12 * dp, paint);
        color(foreground, 0.22f * alpha);
        paint.setStrokeWidth(dp);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRoundRect(capsule, 12 * dp, 12 * dp, paint);
        paint.setStyle(Paint.Style.FILL);
        color(foreground, alpha * 0.2f);
        canvas.drawRoundRect(thumb, 10 * dp, 10 * dp, paint);
        for (HistoryScrollbarModel.Bucket bucket : density) {
            float x = capsule.centerX(), y = bucketY(bucket);
            // Status is the only glyph; mixed clusters keep both states within the narrow capsule.
            if (bucket.running) drawSymbol(canvas, running, x, y - (bucket.failed ? 10 * dp : 0), 18, foreground, alpha);
            if (bucket.failed) drawSymbol(canvas, failed, x, y + (bucket.running ? 10 * dp : 0), 18, foreground, alpha);
            if (!bucket.running && !bucket.failed) drawSymbol(canvas, completed, x, y, 18, foreground, alpha);
        }
        if (!markers().isEmpty()) {
            arrow(canvas, previous, true, foreground);
            arrow(canvas, next, false, foreground);
        }
        if (dragging) tooltip(canvas, view.getResources().getString(R.string.history_lines_ago, -view.getTopRow()), surface, foreground);
        else if (selected != null && SystemClock.uptimeMillis() < tooltipUntil) tooltip(canvas, markerLabel(selected), surface, foreground);
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
        CommandMarker marker = HistoryScrollbarModel.adjacent(markers(), view.getTopRow(), !up, selected);
        int saved = canvas.save();
        if (up) canvas.rotate(180, capsule.centerX(), bounds.centerY());
        drawSymbol(canvas, chevron, capsule.centerX(), bounds.centerY(), 18, foreground, alpha * (marker == null ? 0.38f : 1));
        canvas.restoreToCount(saved);
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
        label(canvas, bounds, text, surface, foreground, alpha);
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
            boolean latestHit = view.getTopRow() < 0 && latest.contains(event.getX(), event.getY());
            boolean railHit = alpha > 0.05f && railTouch.contains(event.getX(), event.getY());
            if (!latestHit && !railHit) return false;
            captured = true;
            cancelled = dragging = false;
            pointerId = event.getPointerId(0);
            downY = lastY = event.getY();
            lastMove = event.getEventTime();
            releaseSpeed = 0;
            railPressed = railHit;
            boolean thumbPressed = railHit && thumb.top <= event.getY() && event.getY() <= thumb.bottom;
            grabOffset = thumbPressed ? event.getY() - thumb.top : thumbHeight / 2;
            view.stopHistoryScrollAnimation();
            if (railHit) awaken();
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (!captured) return false;
        if (action == MotionEvent.ACTION_POINTER_DOWN) cancelled = true;
        int index = event.findPointerIndex(pointerId);
        if (index < 0) cancelled = true;
        if (action == MotionEvent.ACTION_MOVE && !cancelled) {
            float y = event.getY(index);
            if (railPressed && Math.abs(y - downY) > ViewConfiguration.get(view.getContext()).getScaledTouchSlop()) dragging = true;
            if (dragging) {
                layout();
                long elapsed = event.getEventTime() - lastMove;
                if (elapsed > 0) releaseSpeed = Math.abs(y - lastY) * 1000 / elapsed;
                lastY = y; lastMove = event.getEventTime();
                int row = HistoryScrollbarModel.rowAt((y - grabOffset - trackTop)
                    / Math.max(1, trackBottom - trackTop - thumbHeight), historyRows());
                selected = null;
                view.setHistoryTopRow(row);
                awaken();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!cancelled && action == MotionEvent.ACTION_UP) {
                if (dragging) {
                    if (snapEnabled && releaseSpeed < 600 * dp && view.getTopRow() < 0 && view.getTopRow() > -historyRows()) {
                        CommandMarker marker = HistoryScrollbarModel.nearest(markers(), view.getTopRow(), 2);
                        if (marker != null) jump(marker);
                    }
                } else if (view.getTopRow() < 0 && latest.contains(event.getX(), event.getY())) view.scrollToLatest();
                else if (!markers().isEmpty() && previous.contains(event.getX(), event.getY())) navigate(false);
                else if (!markers().isEmpty() && next.contains(event.getX(), event.getY())) navigate(true);
                else if (railPressed && railTouch.contains(event.getX(), event.getY())) {
                    layout();
                    CommandMarker marker = hitMarker(event.getY());
                    if (marker != null) jump(marker);
                }
            }
            captured = dragging = false;
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
            if (railPressed) awaken();
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
        tooltipUntil = SystemClock.uptimeMillis() + IDLE_MILLIS;
        awaken();
        view.announceForAccessibility(markerLabel(marker));
        return true;
    }
}
