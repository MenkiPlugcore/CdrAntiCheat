package store.cadera.cdranticheat.observation;

import java.util.Locale;

public enum ObservationStatus {
    NORMAL,
    WATCH,
    ABNORMAL,
    SUSPICIOUS,
    HIGH_RISK;

    public boolean atLeast(ObservationStatus other) {
        return ordinal() >= other.ordinal();
    }

    public static ObservationStatus parse(String value, ObservationStatus fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public String displayName() {
        return name().replace('_', ' ');
    }
}
