package store.cadera.cdranticheat.config;

public record ConfigSyncReport(
        int detectedVersion,
        int currentVersion,
        int addedKeys,
        int migratedValues,
        int repairedValues,
        boolean changed,
        String backupFile
) {
    public boolean synchronizedConfig() {
        return detectedVersion == currentVersion
                && addedKeys == 0
                && repairedValues == 0;
    }
}
