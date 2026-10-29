package com.local.snoreguard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VolumePolicyTest {
    @Test public void exactCapIsNeverRoundedUp() {
        assertEquals(10, VolumePolicy.raiseTarget(8, 5, 10, 15));
    }

    @Test public void partialIncreaseStopsAtCap() {
        assertEquals(10, VolumePolicy.raiseTarget(9, 2, 10, 15));
    }

    @Test public void currentVolumeAboveCapIsNotForcedDown() {
        assertEquals(12, VolumePolicy.raiseTarget(12, 2, 10, 15));
    }

    @Test public void capCannotExceedSystemMaximum() {
        assertEquals(15, VolumePolicy.raiseTarget(12, 5, 30, 15));
    }

    @Test public void zeroCapPreventsAutomaticRaise() {
        assertEquals(0, VolumePolicy.raiseTarget(0, 2, 0, 15));
    }
}
