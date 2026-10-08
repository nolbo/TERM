package com.termux.view;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import com.termux.terminal.CommandMarker;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalHistory;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Presentation and gestures only. All retained command state belongs to the terminal buffer. */
final class TerminalHistoryScrollbar {
    private static final long IDLE_MILLIS = 1600;
    private final TerminalView view;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF thumb = new RectF(), previous = new RectF(), next = new RectF(), latest = new RectF();
    private final Runnable hide = () -> animateTo(0);
    private ValueAnimator animator;
    private float alpha;
    private float animationTarget;
    private float trackTop, trackBottom, thumbHeight, downY, lastY, grabOffset;
    private long lastMove;
    private float releaseSpeed;
    private boolean captured, dragging, cancelled, thumbPressed;
    private int pointerId;
    private CommandMarker selected;
    private long tooltipUntil;
    private long lastOutput = -1, pendingOutput;
    private boolean wasBrowsing;
    private boolean snapEnabled = true;
    private long densityRevision = -1, densityScroll = -1;
    private int densityRows = -1, densityBins = -1;
    private List<HistoryScrollbarModel.Bucket> density = Collections.emptyList();

    TerminalHistoryScrollbar(TerminalView view) {
        this.view = view;
        dp = view.getResources().getDisplayMetrics().density;
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
        if (!dragging) view.postDelayed(hide, IDLE_MILLIS);
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
        previous.set(width - 48 * dp, top, width, top + 48 * dp);
        next.set(width - 48 * dp, height - 48 * dp, width, height);
        trackTop = top + (navigation ? 52 : 8) * dp;
        trackBottom = Math.max(trackTop + 1, height - (navigation ? 52 : 8) * dp);
        thumbHeight = HistoryScrollbarModel.thumbHeight(trackBottom - trackTop, view.mEmulator.mRows, historyRows(), 32 * dp);
        float y = trackTop + (trackBottom - trackTop - thumbHeight)
            * HistoryScrollbarModel.fraction(view.getTopRow(), historyRows());
        thumb.set(width - 13 * dp, y, width - 7 * dp, y + thumbHeight);
        paint.setTextSize(12 * view.getResources().getDisplayMetrics().scaledDensity);
        String label = latestLabel();
        float labelWidth = Math.min(width - 64 * dp, paint.measureText(label) + 24 * dp);
        latest.set(Math.max(0, width - 56 * dp - labelWidth), height - 52 * dp, width - 56 * dp, height - 4 * dp);
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
        color(foreground, 0.25f * alpha);
        paint.setStrokeWidth(2 * dp);
        float x = view.getWidth() - 10 * dp;
        canvas.drawLine(x, trackTop, x, trackBottom, paint);

        TerminalHistory history = view.mEmulator.getHistory();
        int bins = Math.max(1, (int) ((trackBottom - trackTop - thumbHeight) / (10 * dp)) + 1);
        long scroll = view.mEmulator.getTranscriptRowShiftCounter();
        if (densityRevision != history.getRevision() || densityRows != historyRows() || densityBins != bins || densityScroll != scroll) {
            density = HistoryScrollbarModel.aggregate(markers(), historyRows(), bins);
            densityRevision = history.getRevision(); densityRows = historyRows(); densityBins = bins; densityScroll = scroll;
        }
        for (HistoryScrollbarModel.Bucket bucket : density) {
            float y = trackTop + thumbHeight / 2 + bucket.index * (trackBottom - trackTop - thumbHeight) / Math.max(1, bins - 1);
            float length = (bucket.count > 1 ? 12 + Math.min(8, (float) Math.log1p(bucket.count) * 2) : 8) * dp;
            color(foreground, alpha);
            paint.setStrokeWidth((bucket.count > 1 ? 3 : 2) * dp);
            canvas.drawLine(x - length, y, x - 4 * dp, y, paint);
            // Shape conveys status even with a monochrome palette, including mixed clusters.
            if (bucket.running) {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(x - length - 4 * dp, y, 3 * dp, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if (bucket.failed) {
                float cross = x - length - (bucket.running ? 14 : 4) * dp;
                canvas.drawLine(cross - 3 * dp, y - 3 * dp, cross + 3 * dp, y + 3 * dp, paint);
                canvas.drawLine(cross - 3 * dp, y + 3 * dp, cross + 3 * dp, y - 3 * dp, paint);
            }
        }
        color(foreground, alpha * 0.9f);
        canvas.drawRoundRect(thumb, 3 * dp, 3 * dp, paint);
        if (!markers().isEmpty()) {
            arrow(canvas, previous, true, foreground);
            arrow(canvas, next, false, foreground);
        }
        if (dragging) tooltip(canvas, view.getResources().getString(R.string.history_lines_ago, -view.getTopRow()), surface, foreground);
        else if (selected != null && SystemClock.uptimeMillis() < tooltipUntil) tooltip(canvas, markerLabel(selected), surface, foreground);
    }

    private void arrow(Canvas canvas, RectF bounds, boolean up, int foreground) {
        CommandMarker marker = HistoryScrollbarModel.adjacent(markers(), view.getTopRow(), !up, selected);
        color(foreground, alpha * (marker == null ? 0.38f : 1));
        paint.setStrokeWidth(2 * dp);
        float x = bounds.centerX(), y = bounds.centerY(), direction = up ? -1 : 1;
        canvas.drawLine(x - 6 * dp, y - direction * 3 * dp, x, y + direction * 3 * dp, paint);
        canvas.drawLine(x, y + direction * 3 * dp, x + 6 * dp, y - direction * 3 * dp, paint);
    }

    private void color(int color, float opacity) { paint.setColor(color); paint.setAlpha(Math.round(255 * opacity)); }

    private void label(Canvas canvas, RectF bounds, String text, int surface, int foreground, float opacity) {
        color(surface, opacity);
        canvas.drawRoundRect(bounds, 16 * dp, 16 * dp, paint);
        color(foreground, opacity);
        int saved = canvas.save();
        canvas.clipRect(bounds.left + 8 * dp, bounds.top, bounds.right - 8 * dp, bounds.bottom);
        canvas.drawText(text, bounds.centerX() - paint.measureText(text) / 2,
            bounds.centerY() - (paint.ascent() + paint.descent()) / 2, paint);
        canvas.restoreToCount(saved);
    }

    private void tooltip(Canvas canvas, String text, int surface, int foreground) {
        float width = Math.min(view.getWidth() - 64 * dp, paint.measureText(text) + 24 * dp);
        float center = Math.max(24 * dp, Math.min(view.getHeight() - 76 * dp, thumb.centerY()));
        RectF bounds = new RectF(view.getWidth() - 56 * dp - width, center - 24 * dp, view.getWidth() - 56 * dp, center + 24 * dp);
        label(canvas, bounds, text, surface, foreground, alpha);
    }

    private String markerLabel(CommandMarker marker) {
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(marker.startedAt));
        String state = view.getResources().getString(marker.isRunning() ? R.string.history_running
            : marker.isFailed() ? R.string.history_failed : R.string.history_completed);
        return marker.getExitCode() == null ? time + " · " + state
            : view.getResources().getString(R.string.history_marker_exit, time, state, marker.getExitCode());
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
            boolean railHit = alpha > 0.05f && event.getX() >= view.getWidth() - 48 * dp;
            if (!latestHit && !railHit) return false;
            captured = true;
            cancelled = dragging = false;
            pointerId = event.getPointerId(0);
            downY = lastY = event.getY();
            lastMove = event.getEventTime();
            releaseSpeed = 0;
            thumbPressed = railHit && Math.abs(event.getY() - thumb.centerY()) <= Math.max(24 * dp, thumb.height() / 2);
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
            float y = event.getY(index);
            if (Math.abs(y - downY) > ViewConfiguration.get(view.getContext()).getScaledTouchSlop()
                && event.getX(index) >= view.getWidth() - 48 * dp) dragging = true;
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
                else if ((!thumbPressed || event.getX() < view.getWidth() - 16 * dp)
                    && event.getY() >= trackTop && event.getY() <= trackBottom) {
                    int row = HistoryScrollbarModel.rowAt((event.getY() - trackTop - thumbHeight / 2)
                        / Math.max(1, trackBottom - trackTop - thumbHeight), historyRows());
                    int radius = Math.max(1, (int) Math.ceil(historyRows() * 12 * dp / Math.max(1, trackBottom - trackTop - thumbHeight)));
                    CommandMarker marker = HistoryScrollbarModel.nearest(markers(), row, radius);
                    if (marker != null) jump(marker);
                }
            }
            captured = dragging = false;
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
            awaken();
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
