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
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.WHITE + "v" + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.GRAY + "Paper target: " + ChatColor.WHITE + "1.21.11 / Java 21");
        sender.sendMessage(ChatColor.GRAY + "DiscordSRV: "
                + (plugin.getAlertService().isDiscordAvailable() ? ChatColor.GREEN + "available" : ChatColor.YELLOW + "fallback mode"));
        sender.sendMessage(ChatColor.GRAY + "Floodgate API: "
                + (plugin.getBedrockDetector().isAvailable() ? ChatColor.GREEN + "available" : ChatColor.YELLOW + "not detected"));
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "CdrAntiCheat config berhasil direload.");
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

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.DARK_GRAY + "[" + ChatColor.RED + "CdrAC" + ChatColor.DARK_GRAY + "] "
                + ChatColor.WHITE + "Commands");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " status");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " reload");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " alerts");
        sender.sendMessage(ChatColor.GRAY + "/" + label + " violations <player>");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "reload", "alerts", "violations"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("violations") || args[0].equalsIgnoreCase("vl"))) {
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
}
