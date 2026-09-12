package com.termux.view;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class StableResizeSchedulerTest {

    @Test
    public void layoutBurstAppliesOnlyOnceAfterSettleDelay() {
        FakeCallbackQueue queue = new FakeCallbackQueue();
        int[] applyCount = {0};
        StableResizeScheduler scheduler = new StableResizeScheduler(queue, () -> applyCount[0]++, 120);

        scheduler.scheduleAfterLayoutSettles();
        scheduler.scheduleAfterLayoutSettles();
        scheduler.scheduleAfterLayoutSettles();

        assertEquals(0, applyCount[0]);
        assertEquals(1, queue.callbacks.size());
        assertEquals(120, queue.lastDelayMillis);

        queue.runPendingCallbacks();

        assertEquals(1, applyCount[0]);
    }

    @Test
    public void immediateResizeCancelsPendingLayoutResize() {
        FakeCallbackQueue queue = new FakeCallbackQueue();
        int[] applyCount = {0};
        StableResizeScheduler scheduler = new StableResizeScheduler(queue, () -> applyCount[0]++, 120);

        scheduler.scheduleAfterLayoutSettles();
        scheduler.applyImmediately();
        queue.runPendingCallbacks();

        assertEquals(1, applyCount[0]);
    }

    @Test
    public void cancelPreventsDetachedViewFromApplyingResize() {
        FakeCallbackQueue queue = new FakeCallbackQueue();
        int[] applyCount = {0};
        StableResizeScheduler scheduler = new StableResizeScheduler(queue, () -> applyCount[0]++, 120);

        scheduler.scheduleAfterLayoutSettles();
        scheduler.cancel();
        queue.runPendingCallbacks();

        assertEquals(0, applyCount[0]);
    }

    private static final class FakeCallbackQueue implements StableResizeScheduler.CallbackQueue {
        final List<Runnable> callbacks = new ArrayList<>();
        long lastDelayMillis;

        @Override
        public void removeCallbacks(Runnable callback) {
            callbacks.removeIf(pending -> pending == callback);
        }

        @Override
        public boolean postDelayed(Runnable callback, long delayMillis) {
            callbacks.add(callback);
            lastDelayMillis = delayMillis;
            return true;
        }

        void runPendingCallbacks() {
            List<Runnable> pending = new ArrayList<>(callbacks);
            callbacks.clear();
            pending.forEach(Runnable::run);
        }
    }
}
