package store.cadera.cdranticheat.observation;

import java.util.List;
import java.util.UUID;

public record EvidenceHistorySnapshot(
        UUID uuid,
        String playerName,
        long lastUpdatedAtMillis,
        List<EvidenceSessionSnapshot> sessions
) {
    public EvidenceHistorySnapshot {
        sessions = List.copyOf(sessions);
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    public int totalEvidence() {
        return sessions.stream().mapToInt(EvidenceSessionSnapshot::evidenceCount).sum();
    }
}
