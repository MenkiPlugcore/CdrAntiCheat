package store.cadera.cdranticheat.observation;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import store.cadera.cdranticheat.CdrAntiCheat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ObservationManager {

    private final CdrAntiCheat plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private BukkitTask decayTask;

    public ObservationManager(CdrAntiCheat plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (decayTask != null) {
            decayTask.cancel();
        }
        decayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::decayAndCleanup, 20L, 20L);
    }

    public ObservationUpdate record(UUID uuid, String checkId, double amount, long now) {
        if (!plugin.getConfig().getBoolean("observation.enabled", true)) {
            return new ObservationUpdate(ObservationSnapshot.normal(), false);
        }

        String normalizedCheck = checkId.toLowerCase(Locale.ROOT);
        State state = states.computeIfAbsent(uuid, ignored -> new State());
        prune(state, now);

        ObservationStatus previousStatus = state.status;
        long repeatWindow = Math.max(0L,
                plugin.getConfig().getLong("observation.same-check-repeat-window-ms", 2500L));
        double repeatMultiplier = clamp(
                plugin.getConfig().getDouble("observation.same-check-repeat-multiplier", 0.40),
                0.05,
                1.0
        );
        double defaultWeight = Math.max(0.0,
                plugin.getConfig().getDouble("observation.default-weight", 8.0));
        double weight = Math.max(0.0,
                plugin.getConfig().getDouble("observation.weights." + normalizedCheck, defaultWeight));
        double maxScore = Math.max(1.0,
                plugin.getConfig().getDouble("observation.max-score", 100.0));

        Long lastSameCheck = state.lastByCheck.get(normalizedCheck);
        boolean repeatedQuickly = lastSameCheck != null && now - lastSameCheck <= repeatWindow;
        boolean newCorrelatedCheck = !state.events.isEmpty()
                && state.events.stream().noneMatch(event -> event.checkId().equals(normalizedCheck));

        double appliedWeight = weight * Math.max(0.0, amount);
        if (repeatedQuickly) {
            appliedWeight *= repeatMultiplier;
        }
        if (newCorrelatedCheck) {
            appliedWeight += Math.max(0.0,
                    plugin.getConfig().getDouble("observation.correlation-bonus", 4.0));
        }

        state.score = Math.min(maxScore, state.score + appliedWeight);
        state.lastFlagAt = now;
        state.lastCheck = normalizedCheck;
        state.lastByCheck.put(normalizedCheck, now);
        state.events.addLast(new EvidenceEvent(now, normalizedCheck));
        state.status = statusFor(state.score);

        ObservationSnapshot snapshot = snapshot(state, now);
        return new ObservationUpdate(snapshot, previousStatus != state.status);
    }

    public ObservationSnapshot snapshot(UUID uuid) {
        State state = states.get(uuid);
        if (state == null) {
            return ObservationSnapshot.normal();
        }
        long now = System.currentTimeMillis();
        prune(state, now);
        state.status = statusFor(state.score);
        return snapshot(state, now);
    }

    public ObservationStatus minimumAlertStatus() {
        return ObservationStatus.parse(
                plugin.getConfig().getString("alerts.minimum-observation-status", "ABNORMAL"),
                ObservationStatus.ABNORMAL
        );
    }

    private void decayAndCleanup() {
        long now = System.currentTimeMillis();
        long graceMillis = Math.max(0L,
                plugin.getConfig().getLong("observation.decay-grace-seconds", 10L)) * 1000L;
        long sessionTimeoutMillis = Math.max(10L,
                plugin.getConfig().getLong("observation.session-timeout-seconds", 60L)) * 1000L;
        double decayPerSecond = Math.max(0.0,
                plugin.getConfig().getDouble("observation.score-decay-per-second", 1.25));

        Iterator<Map.Entry<UUID, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next().getValue();
            prune(state, now);

            if (state.lastFlagAt > 0L && now - state.lastFlagAt >= graceMillis) {
                state.score = Math.max(0.0, state.score - decayPerSecond);
                state.status = statusFor(state.score);
            }

            if (state.score <= 0.0
                    && state.events.isEmpty()
                    && state.lastFlagAt > 0L
                    && now - state.lastFlagAt >= sessionTimeoutMillis) {
                iterator.remove();
            }
        }
    }

    private void prune(State state, long now) {
        long windowMillis = Math.max(1000L,
                plugin.getConfig().getLong("observation.correlation-window-ms", 15000L));
        long cutoff = now - windowMillis;
        while (!state.events.isEmpty() && state.events.peekFirst().timestamp() < cutoff) {
            state.events.removeFirst();
        }

        state.lastByCheck.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private ObservationSnapshot snapshot(State state, long now) {
        Set<String> distinct = new HashSet<>();
        for (EvidenceEvent event : state.events) {
            distinct.add(event.checkId());
        }

        double maxScore = Math.max(1.0,
                plugin.getConfig().getDouble("observation.max-score", 100.0));
        double confidence = clamp((state.score / maxScore) * 100.0, 0.0, 100.0);

        return new ObservationSnapshot(
                state.status,
                confidence,
                state.score,
                state.events.size(),
                distinct.size(),
                state.lastCheck == null ? "none" : state.lastCheck,
                state.lastFlagAt == 0L ? now : state.lastFlagAt
        );
    }

    private ObservationStatus statusFor(double score) {
        double watch = Math.max(0.0,
                plugin.getConfig().getDouble("observation.thresholds.watch", 12.0));
        double abnormal = Math.max(watch,
                plugin.getConfig().getDouble("observation.thresholds.abnormal", 35.0));
        double suspicious = Math.max(abnormal,
                plugin.getConfig().getDouble("observation.thresholds.suspicious", 60.0));
        double highRisk = Math.max(suspicious,
                plugin.getConfig().getDouble("observation.thresholds.high-risk", 85.0));

        if (score >= highRisk) {
            return ObservationStatus.HIGH_RISK;
        }
        if (score >= suspicious) {
            return ObservationStatus.SUSPICIOUS;
        }
        if (score >= abnormal) {
            return ObservationStatus.ABNORMAL;
        }
        if (score >= watch) {
            return ObservationStatus.WATCH;
        }
        return ObservationStatus.NORMAL;
    }

    public void shutdown() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
        states.clear();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class State {
        private final Deque<EvidenceEvent> events = new ArrayDeque<>();
        private final Map<String, Long> lastByCheck = new HashMap<>();
        private double score;
        private long lastFlagAt;
        private String lastCheck;
        private ObservationStatus status = ObservationStatus.NORMAL;
    }

    private record EvidenceEvent(long timestamp, String checkId) {
    }
}
