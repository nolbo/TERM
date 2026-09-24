package com.termux.view;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;

/** A combination of {@link GestureDetector} and {@link ScaleGestureDetector}. */
final class GestureAndScaleRecognizer {

    public interface Listener {
        boolean onSingleTapUp(MotionEvent e);

        boolean onDoubleTap(MotionEvent e);

        void onTwoFingerSwipe(boolean downward);

        boolean onScroll(MotionEvent e2, float dx, float dy);

        boolean onFling(MotionEvent e, float velocityX, float velocityY);

        boolean onScale(float focusX, float focusY, float scale);

        boolean onDown(float x, float y);

        boolean onUp(MotionEvent e);

        void onLongPress(MotionEvent e);
    }

    private final GestureDetector mGestureDetector;
    private final ScaleGestureDetector mScaleDetector;
    final Listener mListener;
    boolean isAfterLongPress;
    private final float mSwipeThreshold;
    private boolean mMultiTouch, mSwipeCandidate, mSwipeHandled;
    private float mStartX0, mStartX1, mStartY0, mStartY1, mStartSpan;

    public GestureAndScaleRecognizer(Context context, Listener listener) {
        mListener = listener;
        mSwipeThreshold = Math.max(ViewConfiguration.get(context).getScaledTouchSlop() * 2f,
            32f * context.getResources().getDisplayMetrics().density);

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                return mListener.onScroll(e2, dx, dy);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                return mListener.onFling(e2, velocityX, velocityY);
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return mListener.onDown(e.getX(), e.getY());
            }

            @Override
            public void onLongPress(MotionEvent e) {
                mListener.onLongPress(e);
                isAfterLongPress = true;
            }
        }, null, true /* ignoreMultitouch */);

        mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return mListener.onSingleTapUp(e);
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                return mListener.onDoubleTap(e);
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                return true;
            }
        });

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (mSwipeCandidate || mSwipeHandled) return true;
                return mListener.onScale(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
            }
        });
        mScaleDetector.setQuickScaleEnabled(false);
    }

    public void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mMultiTouch = mSwipeCandidate = mSwipeHandled = false;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (!mMultiTouch && event.getPointerCount() == 2) {
                mSwipeCandidate = true;
                mStartX0 = event.getX(0); mStartX1 = event.getX(1);
                mStartY0 = event.getY(0); mStartY1 = event.getY(1);
                mStartSpan = (float) Math.hypot(mStartX1 - mStartX0, mStartY1 - mStartY0);
            } else {
                mSwipeCandidate = false;
            }
            mMultiTouch = true;
            // Cancel pending tap/long-press/fling and swallow the remaining one-finger tail.
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            mGestureDetector.onTouchEvent(cancel);
            cancel.recycle();
        }
        if (mMultiTouch) {
            if (action == MotionEvent.ACTION_MOVE && mSwipeCandidate && event.getPointerCount() == 2) {
                float dy0 = event.getY(0) - mStartY0, dy1 = event.getY(1) - mStartY1;
                float dx0 = event.getX(0) - mStartX0, dx1 = event.getX(1) - mStartX1;
                float span = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
                if (Math.abs(span - mStartSpan) > mSwipeThreshold / 2f) {
                    mSwipeCandidate = false; // Pinch wins for the rest of this gesture.
                } else if (dy0 * dy1 > 0 && Math.min(Math.abs(dy0), Math.abs(dy1)) > mSwipeThreshold
                        && Math.abs(dy0) > 1.5f * Math.abs(dx0) && Math.abs(dy1) > 1.5f * Math.abs(dx1)) {
                    mSwipeCandidate = false;
                    mSwipeHandled = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    mScaleDetector.onTouchEvent(cancel);
                    cancel.recycle();
                    mListener.onTwoFingerSwipe(dy0 > 0);
                }
            }
            if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) mSwipeCandidate = false;
            if (!mSwipeHandled) mScaleDetector.onTouchEvent(event);
            return;
        }
        mGestureDetector.onTouchEvent(event);
        mScaleDetector.onTouchEvent(event);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isAfterLongPress = false;
                break;
            case MotionEvent.ACTION_UP:
                if (!isAfterLongPress) {
                    // This behaviour is desired when in e.g. vim with mouse events, where we do not
                    // want to move the cursor when lifting finger after a long press.
                    mListener.onUp(event);
                }
                break;
        }
    }

    public boolean isInProgress() {
        return mScaleDetector.isInProgress();
    }

}
