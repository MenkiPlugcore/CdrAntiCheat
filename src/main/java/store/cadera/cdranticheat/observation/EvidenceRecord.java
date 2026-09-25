package store.cadera.cdranticheat.observation;

public record EvidenceRecord(
        long occurredAtMillis,
        String checkId,
        double violationLevel,
        ObservationStatus status,
        double confidence,
        double score,
        String details,
        boolean bedrock,
        boolean enforcementEnabled,
        EvidenceSnapshot evidence
) {
    public String actionLabel() {
        return enforcementEnabled ? "ENFORCEMENT ENABLED" : "TRACKING ONLY";
    }
}
