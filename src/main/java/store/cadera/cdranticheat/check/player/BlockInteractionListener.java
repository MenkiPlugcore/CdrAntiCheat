package store.cadera.cdranticheat.check.player;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.core.ViolationManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class BlockInteractionListener implements Listener {

    private final CdrAntiCheat plugin;
    private final ViolationManager violations;
    private final Map<UUID, BreakStart> breakStarts = new HashMap<>();
    private final Map<UUID, Long> lastBreakAt = new HashMap<>();
    private final Map<UUID, Integer> fastBreakBuffer = new HashMap<>();
    private final Map<UUID, Integer> breakCadenceBuffer = new HashMap<>();
    private final Map<UUID, Long> lastPlaceAt = new HashMap<>();
    private final Map<UUID, Integer> fastPlaceBuffer = new HashMap<>();

    public BlockInteractionListener(CdrAntiCheat plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (!eligible(player)) {
            return;
        }

        Block block = event.getBlock();
        breakStarts.put(player.getUniqueId(), new BreakStart(
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ(),
                block.getType().name(),
                System.currentTimeMillis()
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (!eligible(player)) {
            clearBreakState(uuid);
            return;
        }

        Block block = event.getBlock();
        double hardness = hardness(block.getType());
        int efficiency = efficiencyLevel(player);
        boolean acceleratedLegitimately = hasLegitimateMiningAcceleration(player, efficiency);

        if (violations.isCheckEnabled("fastbreak-a")) {
            handleBreakDuration(player, block, hardness, efficiency, acceleratedLegitimately, now);
        }
        if (violations.isCheckEnabled("fastbreak-b")) {
            handleBreakCadence(player, block, hardness, efficiency, acceleratedLegitimately, now);
        }

        lastBreakAt.put(uuid, now);
        breakStarts.remove(uuid);
    }

    private void handleBreakDuration(Player player,
                                     Block block,
                                     double hardness,
                                     int efficiency,
                                     boolean acceleratedLegitimately,
                                     long now) {
        UUID uuid = player.getUniqueId();
        BreakStart start = breakStarts.get(uuid);
        double minimumHardness = Math.max(0.0,
                plugin.getConfig().getDouble("checks.fastbreak-a.minimum-hardness", 1.0));
        long minimumBreakMs = Math.max(1L,
                plugin.getConfig().getLong("checks.fastbreak-a.minimum-break-ms", 110L));
        int required = Math.max(1,
                plugin.getConfig().getInt("checks.fastbreak-a.required-buffer", 2));

        int buffer = fastBreakBuffer.getOrDefault(uuid, 0);
        if (start == null
                || !start.matches(block)
                || hardness < minimumHardness
                || acceleratedLegitimately) {
            fastBreakBuffer.put(uuid, Math.max(0, buffer - 1));
            return;
        }

        long elapsed = Math.max(0L, now - start.startedAtMillis());
        if (elapsed < minimumBreakMs) {
            buffer++;
            if (buffer >= required) {
                violations.flag(
                        player,
                        "fastbreak-a",
                        1.0,
                        String.format(Locale.US,
                                "block=%s hardness=%.2f elapsed=%dms min=%dms efficiency=%d ping=%d",
                                block.getType().name(), hardness, elapsed, minimumBreakMs, efficiency, player.getPing())
                );
                buffer = Math.max(1, required - 1);
            }
        } else {
            buffer = Math.max(0, buffer - 1);
        }
        fastBreakBuffer.put(uuid, buffer);
    }

    private void handleBreakCadence(Player player,
                                    Block block,
                                    double hardness,
                                    int efficiency,
                                    boolean acceleratedLegitimately,
                                    long now) {
        UUID uuid = player.getUniqueId();
        long previous = lastBreakAt.getOrDefault(uuid, 0L);
        double minimumHardness = Math.max(0.0,
                plugin.getConfig().getDouble("checks.fastbreak-b.minimum-hardness", 0.5));
        long minimumInterval = Math.max(1L,
                plugin.getConfig().getLong("checks.fastbreak-b.minimum-interval-ms", 45L));
        int required = Math.max(1,
                plugin.getConfig().getInt("checks.fastbreak-b.required-buffer", 4));

        int buffer = breakCadenceBuffer.getOrDefault(uuid, 0);
        if (previous <= 0L || hardness < minimumHardness || acceleratedLegitimately) {
            breakCadenceBuffer.put(uuid, Math.max(0, buffer - 1));
            return;
        }

        long interval = Math.max(0L, now - previous);
        if (interval < minimumInterval) {
            buffer++;
            if (buffer >= required) {
                violations.flag(
                        player,
                        "fastbreak-b",
                        1.0,
                        String.format(Locale.US,
                                "block=%s hardness=%.2f interval=%dms min=%dms efficiency=%d ping=%d",
                                block.getType().name(), hardness, interval, minimumInterval, efficiency, player.getPing())
                );
                buffer = Math.max(1, required - 1);
            }
        } else {
            buffer = Math.max(0, buffer - 1);
        }
        breakCadenceBuffer.put(uuid, buffer);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!violations.isCheckEnabled("fastplace-a")) {
            return;
        }

        Player player = event.getPlayer();
        if (!eligible(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long previous = lastPlaceAt.getOrDefault(uuid, 0L);
        lastPlaceAt.put(uuid, now);

        if (previous <= 0L) {
            return;
        }

        long minimumInterval = Math.max(1L,
                plugin.getConfig().getLong("checks.fastplace-a.minimum-interval-ms", 40L));
        int required = Math.max(1,
                plugin.getConfig().getInt("checks.fastplace-a.required-buffer", 5));
        long interval = Math.max(0L, now - previous);
        int buffer = fastPlaceBuffer.getOrDefault(uuid, 0);

        if (interval < minimumInterval) {
            buffer++;
            if (buffer >= required) {
                violations.flag(
                        player,
                        "fastplace-a",
                        1.0,
                        "block=" + event.getBlockPlaced().getType().name()
                                + " interval=" + interval + "ms"
                                + " min=" + minimumInterval + "ms"
                                + " ping=" + player.getPing()
                );
                buffer = Math.max(1, required - 1);
            }
        } else {
            buffer = Math.max(0, buffer - 1);
        }
        fastPlaceBuffer.put(uuid, buffer);
    }

    private boolean eligible(Player player) {
        if (player.hasPermission("cdranticheat.bypass")) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private boolean hasLegitimateMiningAcceleration(Player player, int efficiency) {
        int efficiencyExemptLevel = Math.max(1,
                plugin.getConfig().getInt("checks.fastbreak-a.efficiency-exempt-level", 4));
        return efficiency >= efficiencyExemptLevel
                || player.getPotionEffect(PotionEffectType.HASTE) != null
                || player.getPotionEffect(PotionEffectType.CONDUIT_POWER) != null;
    }

    private int efficiencyLevel(Player player) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        return tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
    }

    private double hardness(Material material) {
        BlockType blockType = material.asBlockType();
        if (blockType == null) {
            return -1.0;
        }
        return blockType.getHardness();
    }

    private void clearBreakState(UUID uuid) {
        breakStarts.remove(uuid);
        lastBreakAt.remove(uuid);
        fastBreakBuffer.remove(uuid);
        breakCadenceBuffer.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clearBreakState(uuid);
        lastPlaceAt.remove(uuid);
        fastPlaceBuffer.remove(uuid);
    }

    private record BreakStart(
            UUID worldId,
            int x,
            int y,
            int z,
            String material,
            long startedAtMillis
    ) {
        boolean matches(Block block) {
            return worldId.equals(block.getWorld().getUID())
                    && x == block.getX()
                    && y == block.getY()
                    && z == block.getZ()
                    && material.equals(block.getType().name());
        }
    }
}
