package store.cadera.cdranticheat.observation;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import store.cadera.cdranticheat.CdrAntiCheat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EvidenceSessionManager implements AutoCloseable {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final CdrAntiCheat plugin;
    private final Map<UUID, PlayerHistory> histories = new HashMap<>();
    private final Map<String, UUID> nameIndex = new HashMap<>();
    private final ExecutorService logExecutor;
    private BukkitTask cleanupTask;

    public EvidenceSessionManager(CdrAntiCheat plugin) {
        this.plugin = plugin;
        this.logExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CdrAntiCheat-EvidenceWriter");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanup, 1200L, 1200L);
    }

    public synchronized EvidenceSessionSnapshot record(Player player,
                                                       String checkId,
                                                       double violationLevel,
                                                       String details,
                                                       boolean bedrock,
                                                       ObservationSnapshot observation,
                                                       EvidenceSnapshot evidence,
                                                       boolean enforcementEnabled) {
        if (!plugin.getConfig().getBoolean("evidence.enabled", true)) {
            return null;
        }

        long now = evidence.capturedAtMillis();
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        String normalizedCheck = checkId.toLowerCase(Locale.ROOT);
        String safeDetails = sanitize(details);

        PlayerHistory history = histories.computeIfAbsent(uuid, ignored -> new PlayerHistory(uuid));
        history.playerName = playerName;
        history.lastUpdatedAtMillis = now;
        nameIndex.put(playerName.toLowerCase(Locale.ROOT), uuid);

        long sessionGapMillis = Math.max(5L,
                plugin.getConfig().getLong("evidence.session-gap-seconds", 45L)) * 1000L;
        SessionState session = history.sessions.peekFirst();
        if (session == null || now - session.lastUpdatedAtMillis > sessionGapMillis) {
            session = new SessionState(createSessionId(uuid, now), now);
            history.sessions.addFirst(session);
        }

        EvidenceRecord record = new EvidenceRecord(
                now,
                normalizedCheck,
                violationLevel,
                observation.status(),
                observation.confidence(),
                observation.score(),
                safeDetails,
                bedrock,
                enforcementEnabled,
                evidence
        );

        session.add(record);
        trimRecords(session);
        trimSessions(history);

        if (plugin.getConfig().getBoolean("evidence.log-to-file", true)) {
            writeEvidenceLog(playerName, uuid, session.sessionId, record);
        }

        return snapshot(session);
    }

    public synchronized EvidenceHistorySnapshot history(UUID uuid) {
        PlayerHistory history = histories.get(uuid);
        if (history == null) {
            return new EvidenceHistorySnapshot(uuid, "unknown", 0L, List.of());
        }

        List<EvidenceSessionSnapshot> sessions = new ArrayList<>();
        for (SessionState session : history.sessions) {
            sessions.add(snapshot(session));
        }
        return new EvidenceHistorySnapshot(
                uuid,
                history.playerName == null ? "unknown" : history.playerName,
                history.lastUpdatedAtMillis,
                sessions
        );
    }

    public synchronized EvidenceSessionSnapshot latestSession(UUID uuid) {
        PlayerHistory history = histories.get(uuid);
        if (history == null || history.sessions.isEmpty()) {
            return null;
        }
        return snapshot(history.sessions.peekFirst());
    }

    public synchronized UUID resolveTrackedName(String playerName) {
        if (playerName == null) {
            return null;
        }
        return nameIndex.get(playerName.toLowerCase(Locale.ROOT));
    }

    public synchronized String lastKnownName(UUID uuid) {
        PlayerHistory history = histories.get(uuid);
        return history == null || history.playerName == null ? "unknown" : history.playerName;
    }

    public synchronized List<String> trackedNames() {
        return histories.values().stream()
                .map(history -> history.playerName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private void trimRecords(SessionState session) {
        int maximum = Math.max(5,
                plugin.getConfig().getInt("evidence.max-records-per-session", 40));
        while (session.records.size() > maximum) {
            session.records.removeFirst();
        }
    }

    private void trimSessions(PlayerHistory history) {
        int maximum = Math.max(1,
                plugin.getConfig().getInt("evidence.max-sessions-per-player", 5));
        while (history.sessions.size() > maximum) {
            history.sessions.removeLast();
        }
    }

    private EvidenceSessionSnapshot snapshot(SessionState state) {
        List<EvidenceRecord> records = new ArrayList<>(state.records);
        Collections.reverse(records);
        return new EvidenceSessionSnapshot(
                state.sessionId,
                state.startedAtMillis,
                state.lastUpdatedAtMillis,
                state.peakStatus,
                state.peakConfidence,
                state.totalEvidence,
                state.distinctChecks.size(),
                state.lastCheck == null ? "none" : state.lastCheck,
                state.lastEvidence,
                records
        );
    }

    private synchronized void cleanup() {
        long now = System.currentTimeMillis();
        long retentionMillis = Math.max(5L,
                plugin.getConfig().getLong("evidence.runtime-retention-minutes", 120L)) * 60_000L;

        List<UUID> remove = new ArrayList<>();
        for (Map.Entry<UUID, PlayerHistory> entry : histories.entrySet()) {
            PlayerHistory history = entry.getValue();
            if (history.lastUpdatedAtMillis > 0L && now - history.lastUpdatedAtMillis > retentionMillis) {
                remove.add(entry.getKey());
            }
        }

        for (UUID uuid : remove) {
            PlayerHistory removed = histories.remove(uuid);
            if (removed != null && removed.playerName != null) {
                nameIndex.remove(removed.playerName.toLowerCase(Locale.ROOT), uuid);
            }
        }
    }

    private void writeEvidenceLog(String playerName,
                                  UUID uuid,
                                  String sessionId,
                                  EvidenceRecord record) {
        String day = DAY_FORMAT.format(Instant.ofEpochMilli(record.occurredAtMillis()));
        String directory = plugin.getConfig().getString("evidence.log-directory", "evidence");
        directory = directory == null || directory.isBlank() ? "evidence" : directory.trim();
        Path path = plugin.getDataFolder().toPath().resolve(directory).resolve("evidence-" + day + ".log");

        EvidenceSnapshot evidence = record.evidence();
        String line = String.format(
                Locale.US,
                "[%s] session=%s player=%s uuid=%s check=%s VL=%.2f status=%s confidence=%.1f score=%.2f world=%s x=%.1f y=%.1f z=%.1f yaw=%.1f pitch=%.1f ping=%d rtt=%d jitter=%d tps=%.2f platform=%s action=%s details=%s%n",
                TIME_FORMAT.format(Instant.ofEpochMilli(record.occurredAtMillis())),
                sessionId,
                sanitize(playerName),
                uuid,
                record.checkId(),
                record.violationLevel(),
                record.status().displayName(),
                record.confidence(),
                record.score(),
                sanitize(evidence.world()),
                evidence.x(),
                evidence.y(),
                evidence.z(),
                evidence.yaw(),
                evidence.pitch(),
                evidence.pingMillis(),
                evidence.keepAliveRttMillis(),
                evidence.keepAliveJitterMillis(),
                evidence.tps(),
                record.bedrock() ? "BEDROCK" : "JAVA",
                record.actionLabel().replace(' ', '_'),
                sanitize(record.details())
        );

        logExecutor.execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(
                        path,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not write anti-cheat evidence log: " + exception.getMessage());
            }
        });
    }

    private String createSessionId(UUID uuid, long startedAtMillis) {
        String time = Long.toString(startedAtMillis, 36).toUpperCase(Locale.ROOT);
        String suffix = uuid.toString().substring(0, 4).toUpperCase(Locale.ROOT);
        return "S-" + time + "-" + suffix;
    }

    private String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "no-details";
        }
        String result = input.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
        if (result.length() > 600) {
            result = result.substring(0, 600) + "...";
        }
        return result;
    }

    @Override
    public synchronized void close() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        histories.clear();
        nameIndex.clear();
        logExecutor.shutdown();
    }

    private static final class PlayerHistory {
        private final UUID uuid;
        private final Deque<SessionState> sessions = new ArrayDeque<>();
        private String playerName;
        private long lastUpdatedAtMillis;

        private PlayerHistory(UUID uuid) {
            this.uuid = uuid;
        }
    }

    private static final class SessionState {
        private final String sessionId;
        private final long startedAtMillis;
        private final Deque<EvidenceRecord> records = new ArrayDeque<>();
        private final Set<String> distinctChecks = new HashSet<>();
        private long lastUpdatedAtMillis;
        private int totalEvidence;
        private ObservationStatus peakStatus = ObservationStatus.NORMAL;
        private double peakConfidence;
        private String lastCheck;
        private EvidenceSnapshot lastEvidence;

        private SessionState(String sessionId, long startedAtMillis) {
            this.sessionId = sessionId;
            this.startedAtMillis = startedAtMillis;
            this.lastUpdatedAtMillis = startedAtMillis;
        }

        private void add(EvidenceRecord record) {
            records.addLast(record);
            distinctChecks.add(record.checkId());
            totalEvidence++;
            lastUpdatedAtMillis = record.occurredAtMillis();
            lastCheck = record.checkId();
            lastEvidence = record.evidence();
            if (record.status().ordinal() > peakStatus.ordinal()) {
                peakStatus = record.status();
            }
            peakConfidence = Math.max(peakConfidence, record.confidence());
        }
    }
}
