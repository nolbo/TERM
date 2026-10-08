package com.termux.view;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
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
    // Keep drag coordinates independent of the smaller, icon-aligned presentation.
    private final RectF visualThumb = new RectF();
    private final Drawable completed, running, failed, chevron, chevronUp, edgeArrow, edgeArrowUp;
    private final Drawable activeCompleted, activeRunning, activeFailed;
    private final Runnable clearTooltip;
    private ValueAnimator animator, pressAnimator, tooltipAnimator;
    private TerminalView.HistoryScrollbarAnimatorFactory spatialMotion, fastSpatialMotion, effectsMotion;
    private float reveal, revealVelocity, pressure, pressureVelocity, tooltipOpacity, tooltipVelocity;
    private float pressureTarget, tooltipTarget, pressedY;
    private int pressedTarget;
    private String tooltipText;
    private final RectF stateLayer = new RectF();
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
    private long lastOutput = -1, pendingOutput;
    private boolean wasBrowsing;
    private boolean snapEnabled = true;
    private long densityRevision = -1, densityScroll = -1;
    private TerminalHistory densityHistory;
    private int densityRows = -1, densityBins = -1;
    private List<HistoryScrollbarModel.Bucket> density = Collections.emptyList();

    TerminalHistoryScrollbar(TerminalView view) {
        this.view = view;
        clearTooltip = () -> animateTooltip(0);
        dp = view.getResources().getDisplayMetrics().density;
        completed = symbol(R.drawable.ic_history_check_circle);
        running = symbol(R.drawable.ic_history_play_arrow);
        failed = symbol(R.drawable.ic_history_block);
        // VectorDrawable's hardware render state must not share changing tints.
        activeCompleted = symbol(R.drawable.ic_history_check_circle);
        activeRunning = symbol(R.drawable.ic_history_play_arrow);
        activeFailed = symbol(R.drawable.ic_history_block);
        chevron = symbol(R.drawable.ic_history_expand_more);
        chevronUp = symbol(R.drawable.ic_history_expand_more);
        edgeArrow = symbol(R.drawable.ic_history_chevron_line_down);
        edgeArrowUp = symbol(R.drawable.ic_history_chevron_line_down);
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

    void setMotion(TerminalView.HistoryScrollbarAnimatorFactory spatial,
            TerminalView.HistoryScrollbarAnimatorFactory fastSpatial, TerminalView.HistoryScrollbarAnimatorFactory effects) {
        spatialMotion = spatial;
        fastSpatialMotion = fastSpatial;
        effectsMotion = effects;
    }

    void setTypeface(Typeface typeface) { paint.setTypeface(typeface); view.invalidate(); }

    private void stopAnimations() {
        if (animator != null) animator.cancel();
        if (pressAnimator != null) pressAnimator.cancel();
        if (tooltipAnimator != null) tooltipAnimator.cancel();
        revealVelocity = pressureVelocity = tooltipVelocity = 0;
        pressure = pressureTarget = tooltipOpacity = tooltipTarget = 0;
        tooltipText = null;
    }

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
        stopAnimations();
        reveal = 0;
        shown = false;
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
            stopAnimations();
            reveal = 0;
            shown = false;
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
        view.removeCallbacks(clearTooltip);
        animatePressure(0);
        animateTooltip(0);
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
        if (reveal == target || !ValueAnimator.areAnimatorsEnabled()) {
            reveal = target; revealVelocity = 0; view.invalidate(); return;
        }
        animator = animation(target == 1 ? spatialMotion : fastSpatialMotion, reveal, target, revealVelocity,
            target == 1 ? 200 : 180, target == 1 ? new DecelerateInterpolator() : new AccelerateInterpolator(),
            (value, velocity) -> { reveal = value; revealVelocity = velocity; view.invalidate(); });
        animator.start();
    }

    private ValueAnimator animation(TerminalView.HistoryScrollbarAnimatorFactory factory, float from, float to,
            float velocity, long duration, Interpolator fallback, TerminalView.HistoryScrollbarAnimatorFactory.Frame frame) {
        if (factory != null) return factory.create(from, to, velocity, frame);
        ValueAnimator result = ValueAnimator.ofFloat(from, to);
        result.setDuration(duration);
        result.setInterpolator(fallback);
        result.addUpdateListener(value -> frame.update((float) value.getAnimatedValue(), 0));
        return result;
    }

    private void animatePressure(float target) {
        if (pressAnimator != null && pressAnimator.isRunning() && pressureTarget == target) return;
        if (pressAnimator != null) pressAnimator.cancel();
        pressureTarget = target;
        if (pressure == target || !ValueAnimator.areAnimatorsEnabled()) {
            pressure = target; pressureVelocity = 0; view.invalidate(); return;
        }
        pressAnimator = animation(fastSpatialMotion, pressure, target, pressureVelocity, 160, new DecelerateInterpolator(),
            (value, velocity) -> { pressure = value; pressureVelocity = velocity; view.invalidate(); });
        pressAnimator.start();
    }

    private void animateTooltip(float target) {
        if (tooltipAnimator != null && tooltipAnimator.isRunning() && tooltipTarget == target) return;
        if (tooltipAnimator != null) tooltipAnimator.cancel();
        tooltipTarget = target;
        if (tooltipOpacity == target || !ValueAnimator.areAnimatorsEnabled()) {
            tooltipOpacity = target; tooltipVelocity = 0;
            if (target == 0) tooltipText = null;
            view.invalidate(); return;
        }
        tooltipAnimator = animation(effectsMotion, tooltipOpacity, target, tooltipVelocity, 120, new DecelerateInterpolator(),
            (value, velocity) -> {
                tooltipOpacity = value; tooltipVelocity = velocity;
                if (target == 0 && value == 0 && velocity == 0) tooltipText = null;
                view.invalidate();
            });
        tooltipAnimator.start();
    }

    private float pressed() { return Math.max(0, Math.min(1, pressure)); }

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
        paint.setTextSize(14 * view.getResources().getDisplayMetrics().scaledDensity);
        updateDensity();
        updateThumbShape();
    }

    private void updateThumbShape() {
        float radius = thumb.width() / 2;
        float closest = radius, center = thumb.centerY();
        Integer selectedRow = selected == null ? null : selected.anchor.getRow();
        boolean atSelected = selectedRow != null && Math.min(0, selectedRow) == view.getTopRow();
        int selectedBin = atSelected ? Math.round(HistoryScrollbarModel.fraction(selectedRow, historyRows()) * (densityBins - 1)) : -1;
        findMarker: for (HistoryScrollbarModel.Bucket bucket : density) {
            boolean mixed = bucket.running && bucket.failed;
            for (int i = 0; i < (mixed ? 2 : 1); i++) {
                CommandMarker marker = bucket.running && i == 0 ? bucket.runningMarker
                    : bucket.failed ? bucket.failedMarker : bucket.marker;
                float y = bucketY(bucket) + (mixed ? (i == 0 ? -10 : 10) * dp : 0);
                // A marker tap uses its aggregated glyph even when binning offsets its row.
                if (atSelected && bucket.index == selectedBin && marker.isRunning() == selected.isRunning()
                    && marker.isFailed() == selected.isFailed()) { closest = 0; center = y; break findMarker; }
                float distance = Math.abs(thumb.centerY() - y);
                if (distance < closest) { closest = distance; center = y; }
            }
        }
        // Morph within the icon's region, fully circular in its inner 6dp. Adjacent mixed
        // symbols are 20dp apart, so their transition regions meet without jumping targets.
        float morph = Math.max(0, Math.min(1, (radius - closest) / (4 * dp)));
        morph = morph * morph * (3 - 2 * morph);
        visualThumb.set(thumb.left, thumb.top + (center - radius - thumb.top) * morph,
            thumb.right, thumb.bottom + (center + radius - thumb.bottom) * morph);
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
        color(foreground, 0.12f);
        paint.setStrokeWidth(1.5f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (trackBottom - trackTop > 20 * dp)
            canvas.drawLine(capsule.centerX(), trackTop + 10 * dp, capsule.centerX(), trackBottom - 10 * dp, paint);
        int active = view.getHistoryScrollbarActive();
        color(mix(active, view.getHistoryScrollbarOnActive(), pressedTarget == 4 ? 0.12f * pressed() : 0), 1);
        canvas.drawRoundRect(visualThumb, 10 * dp, 10 * dp, paint);
        if (pressedTarget == 3 && pressed() > 0) {
            stateLayer.set(capsule.centerX() - 10 * dp, pressedY - 10 * dp,
                capsule.centerX() + 10 * dp, pressedY + 10 * dp);
            color(foreground, 0.12f * pressed());
            canvas.drawRoundRect(stateLayer, (10 - 3 * pressed()) * dp, (10 - 3 * pressed()) * dp, paint);
        }
        for (HistoryScrollbarModel.Bucket bucket : density) {
            float x = capsule.centerX(), y = bucketY(bucket);
            // Status is the only glyph; mixed clusters keep both states within the narrow capsule.
            if (bucket.running) commandSymbol(canvas, running, activeRunning, x, y - (bucket.failed ? 10 * dp : 0), foreground);
            if (bucket.failed) commandSymbol(canvas, failed, activeFailed, x, y + (bucket.running ? 10 * dp : 0), foreground);
            if (!bucket.running && !bucket.failed) commandSymbol(canvas, completed, activeCompleted, x, y, foreground);
        }
        arrow(canvas, previous, true, foreground);
        arrow(canvas, next, false, foreground);
        canvas.restoreToCount(saved);
        if (tooltipOpacity > 0) {
            if (dragging) tooltipText = view.getResources().getString(R.string.history_lines_ago, -view.getTopRow());
            if (tooltipText != null) {
                saved = canvas.save();
                canvas.translate(slideOffset(), 0);
                tooltip(canvas, tooltipText, surface, foreground);
                canvas.restoreToCount(saved);
            }
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

    private void commandSymbol(Canvas canvas, Drawable symbol, Drawable active, float x, float y, int foreground) {
        boolean onHandle = y - 9 * dp >= visualThumb.top && y + 9 * dp <= visualThumb.bottom;
        drawSymbol(canvas, onHandle ? active : symbol, x, y, 18, onHandle ? view.getHistoryScrollbarOnActive() : foreground, 1);
    }

    private void arrow(Canvas canvas, RectF bounds, boolean up, int foreground) {
        CommandMarker marker = navigationMarker(!up);
        boolean enabled = marker != null || view.getTopRow() != (up ? -historyRows() : 0);
        boolean showCount = !up && pendingOutput > 0;
        float state = pressedTarget == (up ? 1 : 2) ? pressed() : 0;
        stateLayer.set(capsule.left + dp, bounds.centerY() - 11 * dp, capsule.right - dp, bounds.centerY() + 11 * dp);
        color(mix(view.getHistoryScrollbarSurface(), foreground, 0.06f + 0.12f * state), 1);
        canvas.drawRoundRect(stateLayer, (11 - 4 * state) * dp, (11 - 4 * state) * dp, paint);
        float y = bounds.centerY() - (showCount ? 5 * dp : 0);
        int saved = canvas.save();
        if (up) canvas.rotate(180, capsule.centerX(), y);
        // VectorDrawable keeps native render state: each direction needs its own alpha/bounds.
        Drawable glyph = marker == null ? (up ? edgeArrowUp : edgeArrow) : (up ? chevronUp : chevron);
        drawSymbol(canvas, glyph, capsule.centerX(), y, 18 - state, foreground, enabled ? 1 : 0.38f);
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
        view.removeCallbacks(clearTooltip);
        animateTooltip(0);
        if (forward) view.scrollToLatest(); else view.setHistoryTopRow(-historyRows());
        view.announceForAccessibility(view.getResources().getString(forward ? R.string.history_latest : R.string.history_top));
        return true;
    }

    private void color(int color, float opacity) { paint.setColor(color); paint.setAlpha(Math.round(255 * opacity)); }

    private static int mix(int from, int to, float fraction) {
        return Color.rgb(Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * fraction),
            Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * fraction),
            Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction));
    }

    private void label(Canvas canvas, RectF bounds, String text, int surface, int foreground, float opacity) {
        color(surface, opacity);
        canvas.drawRoundRect(bounds, 24 * dp, 24 * dp, paint);
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
        float center = Math.max(capsule.top + 24 * dp, Math.min(capsule.bottom - 24 * dp, visualThumb.centerY()));
        RectF bounds = new RectF(right - width, center - 24 * dp, right, center + 24 * dp);
        label(canvas, bounds, text, surface, foreground, Math.max(0, Math.min(1, tooltipOpacity)));
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
            boolean thumbPressed = thumb.top <= event.getY() && event.getY() <= thumb.bottom
                || visualThumb.top <= event.getY() && event.getY() <= visualThumb.bottom;
            grabOffset = thumbPressed ? event.getY() - thumb.top : thumbHeight / 2;
            pressedTarget = previous.contains(event.getX() - slideOffset(), event.getY()) ? 1
                : next.contains(event.getX() - slideOffset(), event.getY()) ? 2 : 4;
            if (pressedTarget == 1 && navigationMarker(false) == null && view.getTopRow() == -historyRows()
                || pressedTarget == 2 && navigationMarker(true) == null && view.getTopRow() == 0) pressedTarget = 0;
            if (pressedTarget == 4) {
                for (HistoryScrollbarModel.Bucket bucket : density) {
                    float center = bucketY(bucket);
                    if (Math.abs(event.getY() - center) > 24 * dp) continue;
                    pressedTarget = 3;
                    pressedY = center + (bucket.running && bucket.failed ? (event.getY() < center ? -10 : 10) * dp : 0);
                    break;
                }
            }
            animatePressure(pressedTarget == 0 ? 0 : 1);
            view.stopHistoryScrollAnimation();
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (!captured) return false;
        if (action == MotionEvent.ACTION_POINTER_DOWN) { cancelled = true; animatePressure(0); animateTooltip(0); }
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
            if (touchAxis == TouchAxis.VERTICAL && !dragging) {
                dragging = true;
                pressedTarget = 4;
                view.removeCallbacks(clearTooltip);
                animateTooltip(1);
            }
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
            if (cancelled || action == MotionEvent.ACTION_CANCEL || dragging && selected == null) animateTooltip(0);
            animatePressure(0);
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
        tooltipText = markerLabel(marker);
        animateTooltip(1);
        view.removeCallbacks(clearTooltip);
        view.postDelayed(clearTooltip, TOOLTIP_MILLIS);
        show();
        view.announceForAccessibility(markerLabel(marker));
        return true;
    }
}
