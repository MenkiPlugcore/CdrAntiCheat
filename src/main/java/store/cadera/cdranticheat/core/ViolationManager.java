package store.cadera.cdranticheat.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.alert.AlertService;
import store.cadera.cdranticheat.compat.BedrockDetector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ViolationManager {

    private final CdrAntiCheat plugin;
    private final AlertService alertService;
    private final BedrockDetector bedrockDetector;
    private final Map<UUID, Map<String, ViolationState>> violations = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastAlerts = new HashMap<>();
    private BukkitTask decayTask;

    public ViolationManager(CdrAntiCheat plugin, AlertService alertService, BedrockDetector bedrockDetector) {
        this.plugin = plugin;
        this.alertService = alertService;
        this.bedrockDetector = bedrockDetector;
    }

    public void start() {
        if (decayTask != null) {
            decayTask.cancel();
        }
        decayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::decayAndCleanup, 20L, 20L);
    }

    public FlagResult flag(Player player, String checkId, double amount, String details) {
        String normalizedCheck = checkId.toLowerCase(Locale.ROOT);
        if (!isCheckEnabled(normalizedCheck) || player.hasPermission("cdranticheat.bypass")) {
            return FlagResult.ignored();
        }

        boolean bedrock = bedrockDetector.isBedrock(player);
        if (bedrock && isSkippedForBedrock(normalizedCheck)) {
            return new FlagResult(false, getViolation(player.getUniqueId(), normalizedCheck), true);
        }

        double appliedAmount = Math.max(0.0, amount);
        if (bedrock) {
            double multiplier = Math.max(1.0,
                    plugin.getConfig().getDouble("compatibility.bedrock-threshold-multiplier", 1.75));
            appliedAmount /= multiplier;
        }

        long now = System.currentTimeMillis();
        Map<String, ViolationState> playerStates = violations.computeIfAbsent(
                player.getUniqueId(), ignored -> new HashMap<>()
        );
        ViolationState state = playerStates.computeIfAbsent(normalizedCheck, ignored -> new ViolationState());

        double previousLevel = state.level();
        state.add(appliedAmount, now);
        double currentLevel = state.level();

        double alertLevel = plugin.getConfig().getDouble("checks." + normalizedCheck + ".alert-vl", 1.0);
        if (currentLevel >= alertLevel && canAlert(player.getUniqueId(), normalizedCheck, now)) {
            alertService.alert(player, normalizedCheck, currentLevel, details, bedrock);
        }

        double kickLevel = plugin.getConfig().getDouble("checks." + normalizedCheck + ".kick-vl", -1.0);
        if (plugin.getConfig().getBoolean("actions.kick.enabled", true)
                && kickLevel > 0.0
                && previousLevel < kickLevel
                && currentLevel >= kickLevel) {
            String message = plugin.getConfig().getString(
                    "actions.kick.message",
                    "Unusual client behavior was detected. Please reconnect without prohibited modifications."
            );
            player.kickPlayer(message == null ? "Unusual client behavior was detected." : message);
        }

        return new FlagResult(true, currentLevel, bedrock);
    }

    private boolean canAlert(UUID uuid, String checkId, long now) {
        long cooldown = Math.max(0L, plugin.getConfig().getLong("alerts.cooldown-ms", 1000L));
        Map<String, Long> playerAlerts = lastAlerts.computeIfAbsent(uuid, ignored -> new HashMap<>());
        long previous = playerAlerts.getOrDefault(checkId, 0L);
        if (now - previous < cooldown) {
            return false;
        }
        playerAlerts.put(checkId, now);
        return true;
    }

    private boolean isSkippedForBedrock(String checkId) {
        List<String> skipped = plugin.getConfig().getStringList("compatibility.bedrock-skip-checks");
        for (String value : skipped) {
            if (checkId.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    public boolean isCheckEnabled(String checkId) {
        return plugin.getConfig().getBoolean("checks." + checkId + ".enabled", true);
    }

    public double getViolation(UUID uuid, String checkId) {
        Map<String, ViolationState> playerStates = violations.get(uuid);
        if (playerStates == null) {
            return 0.0;
        }
        ViolationState state = playerStates.get(checkId.toLowerCase(Locale.ROOT));
        return state == null ? 0.0 : state.level();
    }

    public Map<String, Double> snapshot(UUID uuid) {
        Map<String, ViolationState> playerStates = violations.get(uuid);
        Map<String, Double> snapshot = new LinkedHashMap<>();
        if (playerStates == null) {
            return snapshot;
        }
        playerStates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue().level()));
        return snapshot;
    }

    private void decayAndCleanup() {
        long now = System.currentTimeMillis();
        long graceMillis = Math.max(0L,
                plugin.getConfig().getLong("violations.decay-grace-seconds", 8L)) * 1000L;
        long retentionMillis = Math.max(60L,
                plugin.getConfig().getLong("violations.offline-retention-seconds", 900L)) * 1000L;
        double decay = Math.max(0.0,
                plugin.getConfig().getDouble("violations.decay-per-second", 0.20));

        Iterator<Map.Entry<UUID, Map<String, ViolationState>>> players = violations.entrySet().iterator();
        while (players.hasNext()) {
            Map.Entry<UUID, Map<String, ViolationState>> playerEntry = players.next();
            UUID uuid = playerEntry.getKey();
            Map<String, ViolationState> playerStates = playerEntry.getValue();

            Iterator<Map.Entry<String, ViolationState>> states = playerStates.entrySet().iterator();
            long newestFlag = 0L;
            while (states.hasNext()) {
                ViolationState state = states.next().getValue();
                newestFlag = Math.max(newestFlag, state.lastFlagAt());
                if (now - state.lastFlagAt() >= graceMillis) {
                    state.decay(decay);
                }
                if (state.isEmpty()) {
                    states.remove();
                }
            }

            Player player = Bukkit.getPlayer(uuid);
            boolean offlineExpired = player == null && newestFlag > 0L && now - newestFlag >= retentionMillis;
            if (playerStates.isEmpty() || offlineExpired) {
                players.remove();
                lastAlerts.remove(uuid);
            }
        }
    }

    public void shutdown() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
        violations.clear();
        lastAlerts.clear();
    }
}
