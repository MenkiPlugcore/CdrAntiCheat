package store.cadera.cdranticheat.core;

public final class ViolationState {

    private double level;
    private int totalFlags;
    private long lastFlagAt;

    public double level() {
        return level;
    }

    public int totalFlags() {
        return totalFlags;
    }

    public long lastFlagAt() {
        return lastFlagAt;
    }

    public void add(double amount, long timestamp) {
        level += Math.max(0.0, amount);
        totalFlags++;
        lastFlagAt = timestamp;
    }

    public void decay(double amount) {
        level = Math.max(0.0, level - Math.max(0.0, amount));
    }

    public boolean isEmpty() {
        return level <= 0.0001;
    }
}
