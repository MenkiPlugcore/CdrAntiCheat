package store.cadera.cdranticheat.alert;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.observation.EvidenceSnapshot;
import store.cadera.cdranticheat.observation.ObservationSnapshot;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AlertService implements AutoCloseable {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final CdrAntiCheat plugin;
    private final Path logFile;
    private final Set<UUID> mutedStaff = ConcurrentHashMap.newKeySet();
    private final ExecutorService logExecutor;

    public AlertService(CdrAntiCheat plugin) {
        this.plugin = plugin;
        this.logFile = plugin.getDataFolder().toPath().resolve("logs").resolve("violations.log");
        this.logExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CdrAntiCheat-LogWriter");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void alert(Player player,
                      String checkId,
                      double violationLevel,
                      String details,
                      boolean bedrock,
                      ObservationSnapshot observation,
                      EvidenceSnapshot evidence,
                      String sessionId,
                      boolean enforcementEnabled) {
        String safeDetails = sanitize(details);
        String safeSession = sessionId == null || sessionId.isBlank() ? "none" : sanitize(sessionId);
        String platform = bedrock ? "BEDROCK" : "JAVA";
        String action = enforcementEnabled ? "ENFORCEMENT ENABLED" : "TRACKING ONLY";

        String plain = String.format(
                Locale.US,
                "[CdrAntiCheat] %s | session=%s status=%s confidence=%.1f%% check=%s VL=%.2f world=%s x=%.1f y=%.1f z=%.1f yaw=%.1f pitch=%.1f ping=%d rtt=%d jitter=%d tps=%.2f platform=%s action=%s evidence=%s",
                player.getName(),
                safeSession,
                observation.status().displayName(),
                observation.confidence(),
                checkId,
                violationLevel,
                evidence.world(),
                evidence.x(),
                evidence.y(),
                evidence.z(),
                evidence.yaw(),
                evidence.pitch(),
                evidence.pingMillis(),
                evidence.keepAliveRttMillis(),
                evidence.keepAliveJitterMillis(),
                evidence.tps(),
                platform,
                action,
                safeDetails
        );

        if (plugin.getConfig().getBoolean("alerts.log-to-file", true)) {
            writeFileLog(plain);
        }

        boolean discordDelivered = sendDiscord(buildDiscordMessage(
                player,
                checkId,
                violationLevel,
                safeDetails,
                platform,
                observation,
                evidence,
                safeSession,
                action
        ));

        String mode = plugin.getConfig().getString("alerts.in-game-mode", "fallback");
        mode = mode == null ? "fallback" : mode.toLowerCase(Locale.ROOT);

        if ("always".equals(mode) || ("fallback".equals(mode) && !discordDelivered)) {
            broadcastStaff(player, checkId, violationLevel, platform, observation, evidence, safeSession, action);
        }
    }

    private String buildDiscordMessage(Player player,
                                       String checkId,
                                       double violationLevel,
                                       String details,
                                       String platform,
                                       ObservationSnapshot observation,
                                       EvidenceSnapshot evidence,
                                       String sessionId,
                                       String action) {
        String occurred = LOG_TIME.format(Instant.ofEpochMilli(evidence.capturedAtMillis()));
        return String.format(
                Locale.US,
                "**CdrAntiCheat Observation**\n"
                        + "**Player:** %s\n"
                        + "**Session:** `%s`\n"
                        + "**Occurred:** `%s`\n"
                        + "**Status:** %s\n"
                        + "**Confidence:** %.1f%%\n"
                        + "**Check:** `%s` | VL %.2f\n"
                        + "**World:** `%s`\n"
                        + "**Location:** `X %.1f | Y %.1f | Z %.1f`\n"
                        + "**Rotation:** `Yaw %.1f | Pitch %.1f`\n"
                        + "**Platform:** %s\n"
                        + "**Network:** `Ping %dms | RTT %s | Jitter %s`\n"
                        + "**TPS:** `%.2f`\n"
                        + "**Signals:** `%d flags | %d checks`\n"
                        + "**Evidence:** %s\n"
                        + "**Action:** %s",
                escapeDiscord(player.getName()),
                escapeDiscord(sessionId),
                occurred,
                observation.status().displayName(),
                observation.confidence(),
                checkId,
                violationLevel,
                escapeDiscord(evidence.world()),
                evidence.x(),
                evidence.y(),
                evidence.z(),
                evidence.yaw(),
                evidence.pitch(),
                platform,
                evidence.pingMillis(),
                millis(evidence.keepAliveRttMillis()),
                millis(evidence.keepAliveJitterMillis()),
                evidence.tps(),
                observation.recentFlags(),
                observation.distinctChecks(),
                escapeDiscord(details),
                action
        );
    }

    private void broadcastStaff(Player flagged,
                                String checkId,
                                double violationLevel,
                                String platform,
                                ObservationSnapshot observation,
                                EvidenceSnapshot evidence,
                                String sessionId,
                                String action) {
        String message = ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + statusColor(observation.status().name()) + observation.status().displayName() + ChatColor.GRAY + " "
                + ChatColor.YELLOW + flagged.getName()
                + ChatColor.GRAY + " " + String.format(Locale.US, "%.0f%%", observation.confidence())
                + ChatColor.DARK_GRAY + " | " + ChatColor.RED + checkId
                + ChatColor.GRAY + " VL=" + ChatColor.WHITE + String.format(Locale.US, "%.2f", violationLevel)
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + sessionId
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + evidence.world()
                + ChatColor.WHITE + String.format(Locale.US, " %.1f %.1f %.1f", evidence.x(), evidence.y(), evidence.z())
                + ChatColor.DARK_GRAY + " [" + platform + "] "
                + ChatColor.GRAY + action;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if ((online.isOp() || online.hasPermission("cdranticheat.alerts"))
                    && !mutedStaff.contains(online.getUniqueId())) {
                online.sendMessage(message);
            }
        }
    }

    private ChatColor statusColor(String status) {
        return switch (status) {
            case "HIGH_RISK" -> ChatColor.DARK_RED;
            case "SUSPICIOUS" -> ChatColor.RED;
            case "ABNORMAL" -> ChatColor.GOLD;
            case "WATCH" -> ChatColor.YELLOW;
            default -> ChatColor.GRAY;
        };
    }

    private boolean sendDiscord(String message) {
        if (!plugin.getConfig().getBoolean("integrations.discordsrv.enabled", true)) {
            return false;
        }

        Plugin discordSrv = Bukkit.getPluginManager().getPlugin("DiscordSRV");
        if (discordSrv == null || !discordSrv.isEnabled()) {
            return false;
        }

        try {
            Class<?> discordSrvClass = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            Object discordSrvInstance = discordSrvClass.getMethod("getPlugin").invoke(null);
            if (discordSrvInstance == null) {
                return false;
            }

            String channelId = plugin.getConfig().getString("integrations.discordsrv.channel-id", "");
            Object channel;

            if (channelId != null && !channelId.isBlank()) {
                Object jda = discordSrvInstance.getClass().getMethod("getJda").invoke(discordSrvInstance);
                if (jda == null) {
                    return false;
                }
                Method getTextChannelById = jda.getClass().getMethod("getTextChannelById", String.class);
                channel = getTextChannelById.invoke(jda, channelId.trim());
            } else {
                channel = discordSrvInstance.getClass().getMethod("getMainTextChannel").invoke(discordSrvInstance);
            }

            if (channel == null) {
                return false;
            }

            Method sendMessage = channel.getClass().getMethod("sendMessage", CharSequence.class);
            Object action = sendMessage.invoke(channel, message);
            if (action == null) {
                return false;
            }

            action.getClass().getMethod("queue").invoke(action);
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().fine("DiscordSRV alert delivery unavailable: " + exception.getMessage());
            return false;
        }
    }

    private void writeFileLog(String message) {
        String line = "[" + LOG_TIME.format(Instant.now()) + "] " + message + System.lineSeparator();
        logExecutor.execute(() -> {
            try {
                Files.createDirectories(logFile.getParent());
                Files.writeString(
                        logFile,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not write anti-cheat violation log: " + exception.getMessage());
            }
        });
    }

    public boolean toggleAlerts(Player player) {
        UUID uuid = player.getUniqueId();
        if (mutedStaff.remove(uuid)) {
            return true;
        }
        mutedStaff.add(uuid);
        return false;
    }

    public boolean areAlertsEnabled(Player player) {
        return !mutedStaff.contains(player.getUniqueId());
    }

    public boolean isDiscordAvailable() {
        Plugin discordSrv = Bukkit.getPluginManager().getPlugin("DiscordSRV");
        return discordSrv != null && discordSrv.isEnabled()
                && plugin.getConfig().getBoolean("integrations.discordsrv.enabled", true);
    }

    private String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "no-details";
        }
        String sanitized = input.replace('\n', ' ').replace('\r', ' ').trim();
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500) + "...";
        }
        return sanitized;
    }

    private String escapeDiscord(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("`", "'").replace("@", "@​");
    }

    private String millis(long value) {
        return value < 0L ? "n/a" : value + "ms";
    }

    @Override
    public void close() {
        logExecutor.shutdown();
    }
}
