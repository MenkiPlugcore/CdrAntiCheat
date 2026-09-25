package store.cadera.cdranticheat.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.observation.EvidenceHistorySnapshot;
import store.cadera.cdranticheat.observation.EvidenceRecord;
import store.cadera.cdranticheat.observation.EvidenceSessionSnapshot;
import store.cadera.cdranticheat.observation.EvidenceSnapshot;
import store.cadera.cdranticheat.observation.ObservationSnapshot;
import store.cadera.cdranticheat.observation.ObservationStatus;
import store.cadera.cdranticheat.packet.PacketSnapshot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

public final class AntiCheatCommand implements TabExecutor {

    private static final DateTimeFormatter EVIDENCE_TIME = DateTimeFormatter
            .ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final CdrAntiCheat plugin;

    public AntiCheatCommand(CdrAntiCheat plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!sender.hasPermission("cdranticheat.admin")) {
            sender.sendMessage(ChatColor.RED + "Kamu tidak punya permission untuk command ini.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> sendStatus(sender);
            case "reload" -> reload(sender);
            case "alerts" -> toggleAlerts(sender);
            case "violations", "vl" -> showViolations(sender, args);
            case "packet", "packets" -> showPacket(sender, args);
            case "inspect", "observe" -> showObservation(sender, args);
            case "evidence", "history" -> showEvidence(sender, args);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.WHITE + "v" + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.GRAY + "Paper target: " + ChatColor.WHITE + "1.21.11 / Java 21");
        sender.sendMessage(ChatColor.GRAY + "Mode: "
                + (plugin.getViolationManager().isEnforcementEnabled()
                ? ChatColor.RED + "ENFORCE"
                : ChatColor.GREEN + "OBSERVE (tracking only)"));
        sender.sendMessage(ChatColor.GRAY + "Evidence sessions: "
                + (plugin.getConfig().getBoolean("evidence.enabled", true)
                ? ChatColor.GREEN + "enabled"
                : ChatColor.RED + "disabled"));
        sender.sendMessage(ChatColor.GRAY + "Packet engine: "
                + (plugin.getPacketEngine().isAvailable()
                ? ChatColor.GREEN + plugin.getPacketEngine().providerName()
                : ChatColor.YELLOW + plugin.getPacketEngine().providerName()));
        sender.sendMessage(ChatColor.GRAY + "Combat correlation: "
                + (plugin.getPacketEngine().isAvailable()
                ? ChatColor.GREEN + "available"
                : ChatColor.YELLOW + "disabled (packet engine unavailable)"));
        sender.sendMessage(ChatColor.GRAY + "DiscordSRV: "
                + (plugin.getAlertService().isDiscordAvailable() ? ChatColor.GREEN + "available" : ChatColor.YELLOW + "fallback mode"));
        sender.sendMessage(ChatColor.GRAY + "Floodgate API: "
                + (plugin.getBedrockDetector().isAvailable() ? ChatColor.GREEN + "available" : ChatColor.YELLOW + "not detected"));
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getPacketEngine().reload();
        sender.sendMessage(ChatColor.GREEN + "CdrAntiCheat config, packet engine, combat profile, observation profile, dan evidence profile berhasil direload.");
    }

    private void toggleAlerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Command alerts hanya bisa dipakai player.");
            return;
        }

        boolean enabled = plugin.getAlertService().toggleAlerts(player);
        player.sendMessage(ChatColor.GRAY + "CdrAntiCheat alerts: "
                + (enabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
    }

    private void showViolations(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Gunakan: /cdrac violations <player>");
            return;
        }

        TrackedTarget target = resolveTarget(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player tidak online dan belum punya evidence runtime yang terlacak.");
            return;
        }

        Map<String, Double> snapshot = plugin.getViolationManager().snapshot(target.uuid());
        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.YELLOW + target.name() + ChatColor.GRAY + " violation snapshot:");
        if (snapshot.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Tidak ada violation aktif.");
            return;
        }

        snapshot.forEach((check, level) -> sender.sendMessage(
                ChatColor.GRAY + "- " + ChatColor.RED + check + ChatColor.DARK_GRAY + ": "
                        + ChatColor.WHITE + String.format(Locale.US, "%.2f", level)
        ));
    }

    private void showObservation(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Gunakan: /cdrac inspect <player>");
            return;
        }

        TrackedTarget target = resolveTarget(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player tidak online dan belum punya evidence runtime yang terlacak.");
            return;
        }

        ObservationSnapshot observation = plugin.getObservationManager().snapshot(target.uuid());
        EvidenceSessionSnapshot session = plugin.getEvidenceSessionManager().latestSession(target.uuid());

        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.YELLOW + target.name() + ChatColor.GRAY + " observation:");
        sender.sendMessage(ChatColor.GRAY + "Status: " + statusColor(observation.status())
                + observation.status().displayName()
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Confidence: " + ChatColor.WHITE
                + String.format(Locale.US, "%.1f%%", observation.confidence()));
        sender.sendMessage(ChatColor.GRAY + "Score: " + ChatColor.WHITE + format(observation.score())
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Recent flags: " + ChatColor.WHITE
                + observation.recentFlags()
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Distinct checks: " + ChatColor.WHITE
                + observation.distinctChecks());
        sender.sendMessage(ChatColor.GRAY + "Last signal: " + ChatColor.WHITE + observation.lastCheck());
        sender.sendMessage(ChatColor.GRAY + "Action mode: "
                + (plugin.getViolationManager().isEnforcementEnabled()
                ? ChatColor.RED + "ENFORCE"
                : ChatColor.GREEN + "TRACKING ONLY"));

        if (session == null) {
            sender.sendMessage(ChatColor.GRAY + "Evidence session: " + ChatColor.DARK_GRAY + "none");
            return;
        }

        sender.sendMessage(ChatColor.GRAY + "Session: " + ChatColor.WHITE + session.sessionId()
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Evidence: " + ChatColor.WHITE + session.evidenceCount()
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Checks: " + ChatColor.WHITE + session.distinctChecks());
        sender.sendMessage(ChatColor.GRAY + "Peak: " + statusColor(session.peakStatus()) + session.peakStatus().displayName()
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Peak confidence: " + ChatColor.WHITE
                + String.format(Locale.US, "%.1f%%", session.peakConfidence()));

        EvidenceSnapshot last = session.lastEvidence();
        if (last != null) {
            sender.sendMessage(ChatColor.GRAY + "Last location: " + ChatColor.WHITE + last.world()
                    + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE
                    + String.format(Locale.US, "X %.1f Y %.1f Z %.1f", last.x(), last.y(), last.z()));
        }
        sender.sendMessage(ChatColor.DARK_GRAY + "Gunakan /cdrac evidence " + target.name()
                + " untuk riwayat evidence sesi.");
    }

    private void showEvidence(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Gunakan: /cdrac evidence <player> [page]");
            return;
        }

        TrackedTarget target = resolveTarget(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player tidak online dan belum punya evidence runtime yang terlacak.");
            return;
        }

        EvidenceHistorySnapshot history = plugin.getEvidenceSessionManager().history(target.uuid());
        if (history.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Belum ada evidence session untuk " + target.name() + ".");
            return;
        }

        List<EvidenceView> entries = new ArrayList<>();
        for (EvidenceSessionSnapshot session : history.sessions()) {
            for (EvidenceRecord record : session.records()) {
                entries.add(new EvidenceView(session.sessionId(), record));
            }
        }

        int perPage = Math.max(3, Math.min(10,
                plugin.getConfig().getInt("evidence.entries-per-page", 5)));
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) perPage));
        int page = parsePage(args.length >= 3 ? args[2] : null, totalPages);
        int start = (page - 1) * perPage;
        int end = Math.min(entries.size(), start + perPage);

        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.YELLOW + target.name() + ChatColor.GRAY + " evidence history "
                + ChatColor.WHITE + "(" + page + "/" + totalPages + ")");
        sender.sendMessage(ChatColor.GRAY + "Sessions: " + ChatColor.WHITE + history.sessions().size()
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Total evidence: " + ChatColor.WHITE
                + history.totalEvidence()
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Retained detail: " + ChatColor.WHITE
                + entries.size());

        for (int index = start; index < end; index++) {
            EvidenceView view = entries.get(index);
            EvidenceRecord record = view.record();
            EvidenceSnapshot evidence = record.evidence();
            String time = EVIDENCE_TIME.format(Instant.ofEpochMilli(record.occurredAtMillis()));

            sender.sendMessage(ChatColor.DARK_GRAY + "#" + (index + 1) + " "
                    + ChatColor.GRAY + time + " "
                    + ChatColor.DARK_GRAY + "[" + view.sessionId() + "] "
                    + statusColor(record.status()) + record.status().displayName()
                    + ChatColor.GRAY + " " + record.checkId()
                    + ChatColor.DARK_GRAY + " VL=" + ChatColor.WHITE + format(record.violationLevel())
                    + ChatColor.DARK_GRAY + " conf=" + ChatColor.WHITE
                    + String.format(Locale.US, "%.0f%%", record.confidence()));
            sender.sendMessage(ChatColor.DARK_GRAY + "   " + ChatColor.GRAY + evidence.world()
                    + ChatColor.WHITE + String.format(Locale.US, " X %.1f Y %.1f Z %.1f", evidence.x(), evidence.y(), evidence.z())
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY
                    + (record.bedrock() ? "BEDROCK" : "JAVA")
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + record.actionLabel());
            sender.sendMessage(ChatColor.DARK_GRAY + "   " + ChatColor.GRAY + shorten(record.details(), 150));
        }

        if (page < totalPages) {
            sender.sendMessage(ChatColor.GRAY + "Next: " + ChatColor.WHITE + "/cdrac evidence "
                    + target.name() + " " + (page + 1));
        }
    }

    private void showPacket(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Gunakan: /cdrac packet <player>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player harus sedang online untuk packet telemetry.");
            return;
        }

        PacketSnapshot snapshot = plugin.getPacketEngine().snapshot(target.getUniqueId());
        if (!snapshot.available()) {
            sender.sendMessage(ChatColor.YELLOW + "Packet telemetry tidak tersedia untuk " + target.getName() + ".");
            return;
        }

        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.YELLOW + target.getName() + ChatColor.GRAY + " packet/combat telemetry:");
        sender.sendMessage(ChatColor.GRAY + "Inbound: " + ChatColor.WHITE
                + format(snapshot.inboundPacketsPerSecond()) + " pps"
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Movement: " + ChatColor.WHITE
                + format(snapshot.movementPacketsPerSecond()) + " pps");
        sender.sendMessage(ChatColor.GRAY + "KeepAlive RTT: " + ChatColor.WHITE
                + millis(snapshot.keepAliveRttMillis())
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Jitter: " + ChatColor.WHITE
                + millis(snapshot.keepAliveJitterMillis()));
        sender.sendMessage(ChatColor.GRAY + "Rotation: " + ChatColor.WHITE
                + "yaw=" + format(snapshot.yaw()) + " pitch=" + format(snapshot.pitch())
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "delta=" + ChatColor.WHITE
                + format(snapshot.deltaYaw()) + "/" + format(snapshot.deltaPitch()));
        sender.sendMessage(ChatColor.GRAY + "Attack: " + ChatColor.WHITE
                + "target=" + snapshot.lastTargetEntityId()
                + " age=" + millis(snapshot.lastAttackAgoMillis())
                + " interval=" + millis(snapshot.lastAttackIntervalMillis()));
        sender.sendMessage(ChatColor.GRAY + "Attack timing: " + ChatColor.WHITE
                + "samples=" + snapshot.attackSamples()
                + " mean=" + format(snapshot.attackIntervalMeanMillis()) + "ms"
                + " std=" + format(snapshot.attackIntervalStdDevMillis()) + "ms");
        sender.sendMessage(ChatColor.GRAY + "Target correlation: " + ChatColor.WHITE
                + "distinct=" + snapshot.recentDistinctTargets()
                + " switch=" + millis(snapshot.targetSwitchIntervalMillis())
                + " switchAge=" + millis(snapshot.lastTargetSwitchAgoMillis())
                + " rotDelta=" + format(snapshot.attackRotationDeltaDegrees()) + "deg");
        sender.sendMessage(ChatColor.GRAY + "Buffers: " + ChatColor.WHITE
                + "timer=" + snapshot.timerBuffer()
                + " badPacket=" + snapshot.badPacketBuffer()
                + " packetRate=" + snapshot.packetRateBuffer());
        sender.sendMessage(ChatColor.GRAY + "Velocity: " + ChatColor.WHITE
                + format(snapshot.velocityX()) + ", " + format(snapshot.velocityY()) + ", " + format(snapshot.velocityZ())
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "last=" + ChatColor.WHITE
                + millis(snapshot.lastVelocityAgoMillis()));
        sender.sendMessage(ChatColor.GRAY + "Grace: " + ChatColor.WHITE
                + "teleport=" + millis(snapshot.teleportGraceRemainingMillis())
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "tracked=" + ChatColor.WHITE
                + snapshot.trackedForMillis() + "ms");
    }

    private TrackedTarget resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new TrackedTarget(online.getUniqueId(), online.getName());
        }

        UUID uuid = plugin.getEvidenceSessionManager().resolveTrackedName(name);
        if (uuid == null) {
            return null;
        }
        return new TrackedTarget(uuid, plugin.getEvidenceSessionManager().lastKnownName(uuid));
    }

    private int parsePage(String raw, int totalPages) {
        if (raw == null) {
            return 1;
        }
        try {
            return Math.max(1, Math.min(totalPages, Integer.parseInt(raw)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private ChatColor statusColor(ObservationStatus status) {
        return switch (status) {
            case HIGH_RISK -> ChatColor.DARK_RED;
            case SUSPICIOUS -> ChatColor.RED;
            case ABNORMAL -> ChatColor.GOLD;
            case WATCH -> ChatColor.YELLOW;
            case NORMAL -> ChatColor.GRAY;
        };
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.WHITE + "Commands");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " status");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " reload");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " alerts");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " violations <player>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " inspect <player>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " evidence <player> [page]");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " packet <player>");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "reload", "alerts", "violations", "inspect", "evidence", "packet"), args[0]);
        }
        if (args.length == 2) {
            boolean packetOnly = args[0].equalsIgnoreCase("packet") || args[0].equalsIgnoreCase("packets");
            boolean playerArgument = packetOnly
                    || args[0].equalsIgnoreCase("violations")
                    || args[0].equalsIgnoreCase("vl")
                    || args[0].equalsIgnoreCase("inspect")
                    || args[0].equalsIgnoreCase("observe")
                    || args[0].equalsIgnoreCase("evidence")
                    || args[0].equalsIgnoreCase("history");
            if (playerArgument) {
                TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(names::add);
                if (!packetOnly) {
                    names.addAll(plugin.getEvidenceSessionManager().trackedNames());
                }
                return filter(new ArrayList<>(names), args[1]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private String shorten(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value == null ? "no-details" : value;
        }
        return value.substring(0, maximum) + "...";
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String millis(long value) {
        return value < 0L ? "n/a" : value + "ms";
    }

    private record TrackedTarget(UUID uuid, String name) {
    }

    private record EvidenceView(String sessionId, EvidenceRecord record) {
    }
}
