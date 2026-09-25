package store.cadera.cdranticheat.alert;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import store.cadera.cdranticheat.CdrAntiCheat;

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

    public void alert(Player player, String checkId, double violationLevel, String details, boolean bedrock) {
        String safeDetails = sanitize(details);
        String platform = bedrock ? "BEDROCK" : "JAVA";
        String plain = String.format(
                Locale.US,
                "[CdrAntiCheat] %s failed %s | VL %.2f | %s | %s",
                player.getName(), checkId, violationLevel, safeDetails, platform
        );

        if (plugin.getConfig().getBoolean("alerts.log-to-file", true)) {
            writeFileLog(plain);
        }

        boolean discordDelivered = sendDiscord(plain);
        String mode = plugin.getConfig().getString("alerts.in-game-mode", "fallback");
        mode = mode == null ? "fallback" : mode.toLowerCase(Locale.ROOT);

        if ("always".equals(mode) || ("fallback".equals(mode) && !discordDelivered)) {
            broadcastStaff(player, checkId, violationLevel, safeDetails, platform);
        }
    }

    private void broadcastStaff(Player flagged, String checkId, double violationLevel, String details, String platform) {
        String message = ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.YELLOW + flagged.getName() + ChatColor.GRAY + " failed "
                + ChatColor.RED + checkId + ChatColor.GRAY + " VL="
                + ChatColor.WHITE + String.format(Locale.US, "%.2f", violationLevel)
                + ChatColor.DARK_GRAY + " [" + platform + "] "
                + ChatColor.GRAY + details;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if ((online.isOp() || online.hasPermission("cdranticheat.alerts"))
                    && !mutedStaff.contains(online.getUniqueId())) {
                online.sendMessage(message);
            }
        }
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
        if (sanitized.length() > 400) {
            sanitized = sanitized.substring(0, 400) + "...";
        }
        return sanitized;
    }

    @Override
    public void close() {
        logExecutor.shutdown();
    }
}
