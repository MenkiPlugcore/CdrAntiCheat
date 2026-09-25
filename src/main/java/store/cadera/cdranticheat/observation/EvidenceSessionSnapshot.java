package store.cadera.cdranticheat.observation;

import java.util.List;

public record EvidenceSessionSnapshot(
        String sessionId,
        long startedAtMillis,
        long lastUpdatedAtMillis,
        ObservationStatus peakStatus,
        double peakConfidence,
        int evidenceCount,
        int distinctChecks,
        String lastCheck,
        EvidenceSnapshot lastEvidence,
        List<EvidenceRecord> records
) {
    public EvidenceSessionSnapshot {
        records = List.copyOf(records);
    }
}
