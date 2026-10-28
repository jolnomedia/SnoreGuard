package com.local.snoreguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VolumeRampTest {
    @Test public void raisesAtMostOneStepPerInterval() {
        VolumeRamp ramp = new VolumeRamp();
        ramp.start(3, 7);
        assertEquals(4, ramp.next(3, 10000));
        assertEquals(4, ramp.next(4, 11000));
        assertEquals(5, ramp.next(4, 11500));
        assertEquals(6, ramp.next(5, 13000));
        assertEquals(7, ramp.next(6, 14500));
        assertFalse(ramp.isActive());
    }

    @Test public void restoresAtMostOneStepPerInterval() {
        VolumeRamp ramp = new VolumeRamp();
        ramp.start(8, 4);
        assertEquals(7, ramp.next(8, 0));
        assertEquals(6, ramp.next(7, 1500));
        assertEquals(5, ramp.next(6, 3000));
        assertEquals(4, ramp.next(5, 4500));
        assertFalse(ramp.isActive());
    }

    @Test public void equalCurrentAndTargetDoesNotStart() {
        VolumeRamp ramp = new VolumeRamp();
        ramp.start(5, 5);
        assertFalse(ramp.isActive());
        assertEquals(5, ramp.next(5, 0));
    }

    @Test public void cancelStopsFurtherChanges() {
        VolumeRamp ramp = new VolumeRamp();
        ramp.start(2, 5);
        assertEquals(3, ramp.next(2, 0));
        ramp.cancel();
        assertFalse(ramp.isActive());
        assertEquals(3, ramp.next(3, 1500));
    }
}
