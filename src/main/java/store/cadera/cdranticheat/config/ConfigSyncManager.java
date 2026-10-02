package store.cadera.cdranticheat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import store.cadera.cdranticheat.CdrAntiCheat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ConfigSyncManager {

    public static final int CURRENT_SCHEMA = 4;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> LEGACY_BEDROCK_SKIPS = List.of(
            "reach-a",
            "autoclicker-a",
            "timer-a",
            "packet-rate-a",
            "aim-a",
            "multitarget-a",
            "attack-timing-a",
            "killaura-a"
    );
    private static final String LEGACY_KICK_MESSAGE =
            "Unusual client behavior was detected. Please reconnect without prohibited modifications.";

    private final CdrAntiCheat plugin;
    private volatile ConfigSyncReport lastReport = new ConfigSyncReport(
            CURRENT_SCHEMA, CURRENT_SCHEMA, 0, 0, 0, false, null
    );

    public ConfigSyncManager(CdrAntiCheat plugin) {
        this.plugin = plugin;
    }

    public synchronized ConfigSyncReport synchronize() {
        File configFile = configFile();
        if (!configFile.isFile()) {
            plugin.saveDefaultConfig();
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
        YamlConfiguration defaults = loadDefaults();
        int detectedVersion = current.getInt("config-version", 0);

        int migrated = applyMigrations(current, detectedVersion, defaults);
        int added = 0;
        int repaired = 0;

        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }

            Object defaultValue = defaults.get(key);
            if (!current.isSet(key)) {
                current.set(key, defaultValue);
                added++;
                continue;
            }

            Object existing = current.get(key);
            if (!compatibleType(existing, defaultValue)) {
                plugin.getLogger().warning("Config key '" + key + "' has invalid type. Restoring safe default.");
                current.set(key, defaultValue);
                repaired++;
            }
        }

        boolean versionChanged = current.getInt("config-version", 0) != CURRENT_SCHEMA;
        if (versionChanged) {
            current.set("config-version", CURRENT_SCHEMA);
        }

        boolean changed = migrated > 0 || added > 0 || repaired > 0 || versionChanged;
        String backupName = null;
        if (changed) {
            try {
                File backup = backup(configFile);
                backupName = backup == null ? null : backup.getName();
                current.save(configFile);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to synchronize config.yml", exception);
            }
        }

        plugin.reloadConfig();
        lastReport = new ConfigSyncReport(
                detectedVersion,
                CURRENT_SCHEMA,
                added,
                migrated,
                repaired,
                changed,
                backupName
        );
        return lastReport;
    }

    public synchronized ConfigSyncReport inspect() {
        File configFile = configFile();
        if (!configFile.isFile()) {
            return new ConfigSyncReport(0, CURRENT_SCHEMA, countLeafKeys(loadDefaults()), 0, 0, true, null);
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
        YamlConfiguration defaults = loadDefaults();
        int missing = 0;
        int invalid = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!current.isSet(key)) {
                missing++;
            } else if (!compatibleType(current.get(key), defaults.get(key))) {
                invalid++;
            }
        }

        int detected = current.getInt("config-version", 0);
        boolean changed = detected != CURRENT_SCHEMA || missing > 0 || invalid > 0;
        return new ConfigSyncReport(detected, CURRENT_SCHEMA, missing, 0, invalid, changed, null);
    }

    public synchronized File backupNow() throws IOException {
        File file = configFile();
        if (!file.isFile()) {
            plugin.saveDefaultConfig();
        }
        return backup(file);
    }

    public ConfigSyncReport lastReport() {
        return lastReport;
    }

    private int applyMigrations(YamlConfiguration current, int detectedVersion, YamlConfiguration defaults) {
        if (detectedVersion >= CURRENT_SCHEMA) {
            return 0;
        }

        int changed = 0;

        if (equalsIgnoreCase(current.getString("observation.enforcement-mode"), "observe")) {
            current.set("observation.enforcement-mode", "enforce");
            changed++;
        }
        if (approximately(current.getDouble("compatibility.bedrock-threshold-multiplier", 1.75), 1.75)) {
            current.set("compatibility.bedrock-threshold-multiplier", 1.0);
            changed++;
        }
        if (current.getStringList("compatibility.bedrock-skip-checks").equals(LEGACY_BEDROCK_SKIPS)) {
            current.set("compatibility.bedrock-skip-checks", List.of());
            changed++;
        }
        if (!current.isSet("compatibility.equal-enforcement")) {
            current.set("compatibility.equal-enforcement", true);
            changed++;
        }

        changed += migrateDouble(current, "checks.speed-a.max-horizontal-per-tick", 0.78, 0.46);
        changed += migrateInt(current, "checks.speed-a.required-buffer", 3, 4);
        changed += migrateDouble(current, "checks.speed-a.kick-vl", 12.0, 8.0);
        changed += migrateInt(current, "checks.fly-a.max-air-events", 26, 20);
        changed += migrateInt(current, "checks.fly-a.required-hover-buffer", 6, 5);
        changed += migrateDouble(current, "checks.fly-a.kick-vl", 12.0, 7.0);

        String kickMessage = current.getString("actions.kick.message");
        if (Objects.equals(kickMessage, LEGACY_KICK_MESSAGE)) {
            current.set("actions.kick.message", defaults.getString("actions.kick.message"));
            changed++;
        }
        if (!current.isSet("actions.kick.cooldown-ms")) {
            current.set("actions.kick.cooldown-ms", defaults.getLong("actions.kick.cooldown-ms", 5000L));
            changed++;
        }

        return changed;
    }

    private int migrateDouble(YamlConfiguration config, String path, double oldValue, double newValue) {
        if (config.isSet(path) && approximately(config.getDouble(path), oldValue)) {
            config.set(path, newValue);
            return 1;
        }
        return 0;
    }

    private int migrateInt(YamlConfiguration config, String path, int oldValue, int newValue) {
        if (config.isSet(path) && config.getInt(path) == oldValue) {
            config.set(path, newValue);
            return 1;
        }
        return 0;
    }

    private boolean compatibleType(Object existing, Object defaultValue) {
        if (existing == null || defaultValue == null) {
            return true;
        }
        if (existing instanceof Number && defaultValue instanceof Number) {
            return true;
        }
        if (existing instanceof List<?> && defaultValue instanceof List<?>) {
            return true;
        }
        return defaultValue.getClass().isInstance(existing);
    }

    private int countLeafKeys(YamlConfiguration configuration) {
        int count = 0;
        for (String key : configuration.getKeys(true)) {
            if (!configuration.isConfigurationSection(key)) {
                count++;
            }
        }
        return count;
    }

    private YamlConfiguration loadDefaults() {
        InputStream stream = plugin.getResource("config.yml");
        if (stream == null) {
            throw new IllegalStateException("Bundled config.yml is missing from plugin jar");
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read bundled config.yml", exception);
        }
    }

    private File backup(File source) throws IOException {
        if (!source.isFile()) {
            return null;
        }
        File directory = new File(plugin.getDataFolder(), "backups");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create backup directory " + directory);
        }

        String pluginVersion = plugin.getDescription().getVersion().replaceAll("[^A-Za-z0-9._-]", "_");
        String timestamp = BACKUP_TIME.format(LocalDateTime.now());
        File destination = new File(directory, "config-" + pluginVersion + "-" + timestamp + ".yml");
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return destination;
    }

    private File configFile() {
        return new File(plugin.getDataFolder(), "config.yml");
    }

    private static boolean approximately(double left, double right) {
        return Math.abs(left - right) < 0.000001;
    }

    private static boolean equalsIgnoreCase(String value, String expected) {
        return value != null && value.toLowerCase(Locale.ROOT).equals(expected.toLowerCase(Locale.ROOT));
    }
}
