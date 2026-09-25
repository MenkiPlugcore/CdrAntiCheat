package store.cadera.cdranticheat.core;

public record FlagResult(boolean accepted, double violationLevel, boolean bedrock) {

    public static FlagResult ignored() {
        return new FlagResult(false, 0.0, false);
    }
}
