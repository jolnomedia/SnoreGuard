package com.local.snoreguard;

/** Exact-step volume limits; no percentage conversion or rounding. */
public final class VolumePolicy {
    private VolumePolicy() {}

    public static int raiseTarget(int current, int increase, int cap, int systemMax) {
        int max = Math.max(0, systemMax);
        int safeCurrent = Math.max(0, Math.min(max, current));
        int safeCap = Math.max(0, Math.min(max, cap));
        int safeIncrease = Math.max(0, increase);
        if (safeCurrent >= safeCap) return safeCurrent;
        return Math.min(safeCap, safeCurrent + safeIncrease);
    }
}
