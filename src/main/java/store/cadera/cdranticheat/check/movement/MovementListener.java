package store.cadera.cdranticheat.check.movement;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.core.FlagResult;
import store.cadera.cdranticheat.core.ViolationManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MovementListener implements Listener {

    private final CdrAntiCheat plugin;
    private final ViolationManager violations;
    private final Map<UUID, Long> lastMoveAt = new HashMap<>();
    private final Map<UUID, Long> recentVelocityAt = new HashMap<>();
    private final Map<UUID, Integer> speedBuffer = new HashMap<>();
    private final Map<UUID, Integer> airEvents = new HashMap<>();
    private final Map<UUID, Integer> hoverBuffer = new HashMap<>();
    private final Map<UUID, Location> lastSafe = new HashMap<>();
    private final Map<UUID, Long> lastSetbackAt = new HashMap<>();

    public MovementListener(CdrAntiCheat plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        recentVelocityAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        resetMovementState(event.getPlayer());
        if (event.getTo() != null) {
            lastSafe.put(event.getPlayer().getUniqueId(), event.getTo().clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        Player player = event.getPlayer();
        Location from = event.getFrom();
        UUID uuid = player.getUniqueId();

        if (!isFinite(to) || Math.abs(to.getPitch()) > 90.01F) {
            violations.flag(player, "bad-movement-a", 1.0,
                    "non-finite movement or invalid pitch=" + to.getPitch());
            boolean safetyCancel = plugin.getConfig().getBoolean(
                    "observation.safety-cancel-impossible-movement", true
            );
            if (violations.isEnforcementEnabled() || safetyCancel) {
                event.setCancelled(true);
            }
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return;
        }

        long now = System.currentTimeMillis();
        long previousMove = lastMoveAt.getOrDefault(uuid, now - 50L);
        lastMoveAt.put(uuid, now);
        double tickFactor = Math.max(1.0, Math.min(5.0, (now - previousMove) / 50.0));

        if (isGeneralMovementExempt(player, to, now)) {
            speedBuffer.put(uuid, 0);
            airEvents.put(uuid, 0);
            hoverBuffer.put(uuid, 0);
            if (player.isOnGround()) {
                lastSafe.put(uuid, to.clone());
            }
            return;
        }

        handleSpeed(player, to, dx, dz, tickFactor);
        handleFly(player, to, dy, now);
    }

    private void handleSpeed(Player player, Location to, double dx, double dz, double tickFactor) {
        UUID uuid = player.getUniqueId();
        if (!violations.isCheckEnabled("speed-a")) {
            return;
        }

        if (!player.isOnGround() || isSpeedSurfaceExempt(to)) {
            speedBuffer.put(uuid, Math.max(0, speedBuffer.getOrDefault(uuid, 0) - 1));
            return;
        }

        double horizontal = Math.hypot(dx, dz);
        double configured = plugin.getConfig().getDouble("checks.speed-a.max-horizontal-per-tick", 0.78);
        double walkScale = Math.max(1.0, player.getWalkSpeed() / 0.2F);
        double potionBonus = speedPotionBonus(player);
        double maximum = (configured * walkScale + potionBonus) * tickFactor;

        int buffer = speedBuffer.getOrDefault(uuid, 0);
        if (horizontal > maximum) {
            buffer++;
            int required = Math.max(1, plugin.getConfig().getInt("checks.speed-a.required-buffer", 3));
            if (buffer >= required) {
                violations.flag(
                        player,
                        "speed-a",
                        1.0,
                        String.format(Locale.US, "horizontal=%.3f max=%.3f ping=%d", horizontal, maximum, player.getPing())
                );
                buffer = Math.max(1, required - 1);
            }
        } else {
            buffer = Math.max(0, buffer - 1);
        }
        speedBuffer.put(uuid, buffer);
    }

    private void handleFly(Player player, Location to, double deltaY, long now) {
        UUID uuid = player.getUniqueId();
        if (!violations.isCheckEnabled("fly-a")) {
            return;
        }

        if (player.isOnGround()) {
            airEvents.put(uuid, 0);
            hoverBuffer.put(uuid, 0);
            lastSafe.put(uuid, to.clone());
            return;
        }

        int air = airEvents.getOrDefault(uuid, 0) + 1;
        airEvents.put(uuid, air);

        int maxAir = Math.max(1, plugin.getConfig().getInt("checks.fly-a.max-air-events", 26));
        double hoverAbsoluteMax = Math.max(0.001,
                plugin.getConfig().getDouble("checks.fly-a.hover-vertical-absolute-max", 0.025));

        int hover = hoverBuffer.getOrDefault(uuid, 0);
        if (air > maxAir && Math.abs(deltaY) <= hoverAbsoluteMax) {
            hover++;
        } else {
            hover = Math.max(0, hover - 1);
        }

        int requiredHover = Math.max(1,
                plugin.getConfig().getInt("checks.fly-a.required-hover-buffer", 6));
        if (hover >= requiredHover) {
            FlagResult result = violations.flag(
                    player,
                    "fly-a",
                    1.0,
                    String.format(Locale.US, "air-events=%d dy=%.4f ping=%d", air, deltaY, player.getPing())
            );

            double setbackLevel = plugin.getConfig().getDouble("checks.fly-a.setback-vl", 5.0);
            if (violations.isEnforcementEnabled()
                    && result.accepted()
                    && result.violationLevel() >= setbackLevel) {
                attemptSetback(player, now);
            }
            hover = Math.max(1, requiredHover / 2);
        }
        hoverBuffer.put(uuid, hover);
    }

    private void attemptSetback(Player player, long now) {
        UUID uuid = player.getUniqueId();
        long previous = lastSetbackAt.getOrDefault(uuid, 0L);
        if (now - previous < 1500L) {
            return;
        }

        Location safe = lastSafe.get(uuid);
        if (safe == null || safe.getWorld() == null || !safe.getWorld().equals(player.getWorld())) {
            return;
        }

        lastSetbackAt.put(uuid, now);
        Location destination = safe.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.teleport(destination);
            }
        });
    }

    private boolean isGeneralMovementExempt(Player player, Location to, long now) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return true;
        }
        if (player.getAllowFlight() || player.isFlying() || player.isGliding()
                || player.isRiptiding() || player.isInsideVehicle() || player.isSwimming()) {
            return true;
        }
        if (to.getBlock().isLiquid() || to.clone().subtract(0.0, 0.2, 0.0).getBlock().isLiquid()) {
            return true;
        }
        if (hasEffect(player, PotionEffectType.LEVITATION) || hasEffect(player, PotionEffectType.SLOW_FALLING)) {
            return true;
        }
        if (isClimbableLike(to.getBlock().getType()) || isClimbableLike(to.clone().subtract(0.0, 1.0, 0.0).getBlock().getType())) {
            return true;
        }

        long velocityAt = recentVelocityAt.getOrDefault(player.getUniqueId(), 0L);
        return now - velocityAt < 1500L;
    }

    private boolean isSpeedSurfaceExempt(Location location) {
        Material current = location.getBlock().getType();
        Material below = location.clone().subtract(0.0, 1.0, 0.0).getBlock().getType();
        return isSpecialMovementSurface(current) || isSpecialMovementSurface(below);
    }

    private boolean isSpecialMovementSurface(Material material) {
        String name = material.name();
        return name.contains("ICE")
                || name.equals("SLIME_BLOCK")
                || name.equals("HONEY_BLOCK")
                || name.equals("SOUL_SAND")
                || name.equals("SOUL_SOIL")
                || name.equals("POWDER_SNOW")
                || name.equals("BUBBLE_COLUMN");
    }

    private boolean isClimbableLike(Material material) {
        String name = material.name();
        return name.equals("LADDER")
                || name.equals("VINE")
                || name.equals("SCAFFOLDING")
                || name.equals("WEEPING_VINES")
                || name.equals("WEEPING_VINES_PLANT")
                || name.equals("TWISTING_VINES")
                || name.equals("TWISTING_VINES_PLANT");
    }

    private double speedPotionBonus(Player player) {
        PotionEffect effect = player.getPotionEffect(PotionEffectType.SPEED);
        if (effect == null) {
            return 0.0;
        }
        return (effect.getAmplifier() + 1) * 0.18;
    }

    private boolean hasEffect(Player player, PotionEffectType type) {
        return player.getPotionEffect(type) != null;
    }

    private boolean isFinite(Location location) {
        return Double.isFinite(location.getX())
                && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ())
                && Float.isFinite(location.getYaw())
                && Float.isFinite(location.getPitch());
    }

    private void resetMovementState(Player player) {
        UUID uuid = player.getUniqueId();
        lastMoveAt.remove(uuid);
        speedBuffer.remove(uuid);
        airEvents.remove(uuid);
        hoverBuffer.remove(uuid);
        lastSetbackAt.remove(uuid);
        recentVelocityAt.put(uuid, System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastMoveAt.remove(uuid);
        recentVelocityAt.remove(uuid);
        speedBuffer.remove(uuid);
        airEvents.remove(uuid);
        hoverBuffer.remove(uuid);
        lastSafe.remove(uuid);
        lastSetbackAt.remove(uuid);
    }
}
