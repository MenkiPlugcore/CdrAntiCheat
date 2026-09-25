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
import store.cadera.cdranticheat.packet.PacketSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AntiCheatCommand implements TabExecutor {

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
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.WHITE + "v" + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.GRAY + "Paper target: " + ChatColor.WHITE + "1.21.11 / Java 21");
        sender.sendMessage(ChatColor.GRAY + "Packet engine: "
                + (plugin.getPacketEngine().isAvailable()
                ? ChatColor.GREEN + plugin.getPacketEngine().providerName()
                : ChatColor.YELLOW + plugin.getPacketEngine().providerName()));
        sender.sendMessage(ChatColor.GRAY + "DiscordSRV: "
                + (plugin.getAlertService().isDiscordAvailable() ? ChatColor.GREEN + "available" : ChatColor.YELLOW + "fallback mode"));
        sender.sendMessage(ChatColor.GRAY + "Floodgate API: "
                + (plugin.getBedrockDetector().isAvailable() ? ChatColor.GREEN + "available" : ChatColor.YELLOW + "not detected"));
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getPacketEngine().reload();
        sender.sendMessage(ChatColor.GREEN + "CdrAntiCheat config dan packet engine berhasil direload.");
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

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player harus sedang online.");
            return;
        }

        Map<String, Double> snapshot = plugin.getViolationManager().snapshot(target.getUniqueId());
        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.YELLOW + target.getName() + ChatColor.GRAY + " violation snapshot:");
        if (snapshot.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Tidak ada violation aktif.");
            return;
        }

        snapshot.forEach((check, level) -> sender.sendMessage(
                ChatColor.GRAY + "- " + ChatColor.RED + check + ChatColor.DARK_GRAY + ": "
                        + ChatColor.WHITE + String.format(Locale.US, "%.2f", level)
        ));
    }

    private void showPacket(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Gunakan: /cdrac packet <player>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player harus sedang online.");
            return;
        }

        PacketSnapshot snapshot = plugin.getPacketEngine().snapshot(target.getUniqueId());
        if (!snapshot.available()) {
            sender.sendMessage(ChatColor.YELLOW + "Packet telemetry tidak tersedia untuk " + target.getName() + ".");
            return;
        }

        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.YELLOW + target.getName() + ChatColor.GRAY + " packet telemetry:");
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
        sender.sendMessage(ChatColor.GRAY + "Buffers: " + ChatColor.WHITE
                + "timer=" + snapshot.timerBuffer()
                + " badPacket=" + snapshot.badPacketBuffer()
                + " packetRate=" + snapshot.packetRateBuffer());
        sender.sendMessage(ChatColor.GRAY + "Velocity: " + ChatColor.WHITE
                + format(snapshot.velocityX()) + ", " + format(snapshot.velocityY()) + ", " + format(snapshot.velocityZ())
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "last=" + ChatColor.WHITE
                + millis(snapshot.lastVelocityAgoMillis()));
        sender.sendMessage(ChatColor.GRAY + "Last attack: " + ChatColor.WHITE
                + millis(snapshot.lastAttackAgoMillis())
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "tracked=" + ChatColor.WHITE
                + snapshot.trackedForMillis() + "ms");
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.WHITE + "Commands");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " status");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " reload");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " alerts");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " violations <player>");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " packet <player>");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "reload", "alerts", "violations", "packet"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("violations")
                || args[0].equalsIgnoreCase("vl")
                || args[0].equalsIgnoreCase("packet")
                || args[0].equalsIgnoreCase("packets"))) {
            List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return filter(players, args[1]);
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

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String millis(long value) {
        return value < 0L ? "n/a" : value + "ms";
    }
}
