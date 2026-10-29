package com.local.snoreguard;

/** Pure Java state machine: changes at most one system volume step per interval. */
public final class VolumeRamp {
    public static final long STEP_INTERVAL_MS = 1500;
    private int target;
    private boolean active;
    private long lastStepAt = Long.MIN_VALUE;

    public void start(int current, int target) {
        this.target = target;
        this.active = current != target;
        this.lastStepAt = Long.MIN_VALUE;
    }

    public int next(int current, long now) {
        if (!active || current == target) {
            active = false;
            return current;
        }
        if (lastStepAt != Long.MIN_VALUE && now - lastStepAt < STEP_INTERVAL_MS) return current;
        int next = current + Integer.compare(target, current);
        lastStepAt = now;
        if (next == target) active = false;
        return next;
    }

    /** Lowers an unfinished raise target without changing the step schedule. */
    public void limitTarget(int cap) {
        if (active && cap < target) target = cap;
    }

    public boolean isActive() { return active; }
    public int getTarget() { return target; }
    public void cancel() { active = false; }
}
