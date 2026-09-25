package store.cadera.cdranticheat.observation;

public record ObservationSnapshot(
        ObservationStatus status,
        double confidence,
        double score,
        int recentFlags,
        int distinctChecks,
        String lastCheck,
        long lastUpdatedAtMillis
) {
    public static ObservationSnapshot normal() {
        return new ObservationSnapshot(
                ObservationStatus.NORMAL,
                0.0,
                0.0,
                0,
                0,
                "none",
                0L
        );
    }
}
