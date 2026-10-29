package com.local.snoreguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VolumeRampTest {
    @Test public void raisesOnlyOneStepPerInterval() {
        VolumeRamp ramp = new VolumeRamp();
        ramp.start(5, 9);
        assertEquals(6, ramp.next(5, 0));
        assertEquals(6, ramp.next(6, 1000));
        assertEquals(7, ramp.next(6, 1500));
        assertEquals(8, ramp.next(7, 3000));
        assertEquals(9, ramp.next(8, 4500));
        assertFalse(ramp.isActive());
    }

    @Test public void restoresOnlyOneStepPerInterval() {
        VolumeRamp ramp = new VolumeRamp();
        ramp.start(10, 6);
        assertEquals(9, ramp.next(10, 0));
        assertEquals(8, ramp.next(9, 1500));
        assertEquals(7, ramp.next(8, 3000));
        assertEquals(6, ramp.next(7, 4500));
        assertFalse(ramp.isActive());
    }

    @Test public void equalVolumesDoNotStartRamp() {
        VolumeRamp ramp = new VolumeRamp();
        ramp.start(7, 7);
        assertFalse(ramp.isActive());
        assertEquals(7, ramp.next(7, 0));
    }

    @Test public void cancellationStopsFurtherChanges() {
        VolumeRamp ramp = new VolumeRamp();
        ramp.start(5, 9);
        assertTrue(ramp.isActive());
        ramp.cancel();
        assertEquals(5, ramp.next(5, 5000));
    }

    @Test public void lowerCapShrinksUnfinishedRaiseWithoutSkippingSteps() {
        VolumeRamp ramp = new VolumeRamp();
        ramp.start(5, 12);
        assertEquals(6, ramp.next(5, 0));
        ramp.limitTarget(8);
        assertEquals(8, ramp.getTarget());
        assertEquals(7, ramp.next(6, 1500));
        assertEquals(8, ramp.next(7, 3000));
        assertFalse(ramp.isActive());
    }
}
