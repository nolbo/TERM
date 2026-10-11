package dev.terminal.view;

/**
 * Coalesces a burst of layout-driven resize requests into one resize after the layout settles.
 *
 * <p>Android dispatches a size change for every frame of an IME animation. Applying each of those
 * transient sizes to a terminal would repeatedly reflow its scrollback and signal the process even
 * though only the final size is useful.</p>
 */
final class StableResizeScheduler {

    interface CallbackQueue {
        void removeCallbacks(Runnable callback);

        boolean postDelayed(Runnable callback, long delayMillis);
    }

    private final CallbackQueue mCallbackQueue;
    private final Runnable mApplyResize;
    private final long mSettleDelayMillis;

    StableResizeScheduler(CallbackQueue callbackQueue, Runnable applyResize, long settleDelayMillis) {
        mCallbackQueue = callbackQueue;
        mApplyResize = applyResize;
        mSettleDelayMillis = settleDelayMillis;
    }

    void scheduleAfterLayoutSettles() {
        mCallbackQueue.removeCallbacks(mApplyResize);
        mCallbackQueue.postDelayed(mApplyResize, mSettleDelayMillis);
    }

    void applyImmediately() {
        cancel();
        mApplyResize.run();
    }

    void cancel() {
        mCallbackQueue.removeCallbacks(mApplyResize);
    }
}
