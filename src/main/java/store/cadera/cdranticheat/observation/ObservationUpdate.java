package store.cadera.cdranticheat.observation;

public record ObservationUpdate(
        ObservationSnapshot snapshot,
        boolean statusChanged
) {
}
