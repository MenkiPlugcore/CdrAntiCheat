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
    private final Map<UUID, Integer> airSpeedBuffer = new HashMap<>();
    private final Map<UUID, Integer> airEvents = new HashMap<>();
    private final Map<UUID, Integer> hoverBuffer = new HashMap<>();
    private final Map<UUID, Integer> ascentBuffer = new HashMap<>();
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
        boolean bedrock = plugin.getBedrockDetector().isBedrock(player);

        if (!isFinitePosition(to)) {
            flagImpossibleMovement(event, player, "non-finite position");
            return;
        }

        if (!isFiniteRotation(to) || Math.abs(to.getPitch()) > 90.01F) {
            Location authoritative = player.getLocation();
            boolean authoritativeValid = isFiniteRotation(authoritative)
                    && Math.abs(authoritative.getPitch()) <= 90.01F;

            // Geyser/Floodgate can expose transient raw rotation values that are not the
            // authoritative Bukkit rotation. Treat this as transport normalization, not
            // a Bedrock exemption: all actual movement checks remain identical.
            if (!bedrock || !authoritativeValid) {
                flagImpossibleMovement(event, player,
                        "non-finite movement or invalid pitch=" + to.getPitch());
                return;
            }
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
        double tickFactor = Math.max(1.0, Math.min(4.0, (now - previousMove) / 50.0));

        if (isGeneralMovementExempt(player, to, now)) {
            clearActiveBuffers(uuid);
            if (player.isOnGround()) {
                lastSafe.put(uuid, to.clone());
            }
            return;
        }

        handleSpeed(player, to, dx, dz, tickFactor);
        handleFly(player, to, dy, now);
    }

    private void flagImpossibleMovement(PlayerMoveEvent event, Player player, String details) {
        violations.flag(player, "bad-movement-a", 1.0, details);
        boolean safetyCancel = plugin.getConfig().getBoolean(
                "observation.safety-cancel-impossible-movement", true
        );
        if (violations.isEnforcementEnabled() || safetyCancel) {
            event.setCancelled(true);
        }
    }

    private void handleSpeed(Player player, Location to, double dx, double dz, double tickFactor) {
        UUID uuid = player.getUniqueId();
        double horizontal = Math.hypot(dx, dz);
        double walkScale = Math.max(1.0, player.getWalkSpeed() / 0.2F);
        double potionBonus = speedPotionBonus(player);

        if (player.isOnGround()) {
            airSpeedBuffer.put(uuid, Math.max(0, airSpeedBuffer.getOrDefault(uuid, 0) - 1));
            if (!violations.isCheckEnabled("speed-a")) {
                return;
            }
            if (isSpeedSurfaceExempt(to)) {
                speedBuffer.put(uuid, Math.max(0, speedBuffer.getOrDefault(uuid, 0) - 1));
                return;
            }

            double configured = plugin.getConfig().getDouble("checks.speed-a.max-horizontal-per-tick", 0.46);
            double maximum = (configured * walkScale + potionBonus) * tickFactor;
            updateSpeedBuffer(player, "speed-a", speedBuffer, horizontal, maximum,
                    Math.max(1, plugin.getConfig().getInt("checks.speed-a.required-buffer", 4)));
            return;
        }

        speedBuffer.put(uuid, Math.max(0, speedBuffer.getOrDefault(uuid, 0) - 1));
        if (!violations.isCheckEnabled("speed-b")) {
            return;
        }

        double configured = plugin.getConfig().getDouble("checks.speed-b.max-horizontal-per-tick", 0.62);
        double maximum = (configured * walkScale + potionBonus) * tickFactor;
        updateSpeedBuffer(player, "speed-b", airSpeedBuffer, horizontal, maximum,
                Math.max(1, plugin.getConfig().getInt("checks.speed-b.required-buffer", 5)));
    }

    private void updateSpeedBuffer(Player player,
                                   String checkId,
                                   Map<UUID, Integer> bufferMap,
                                   double horizontal,
                                   double maximum,
                                   int required) {
        UUID uuid = player.getUniqueId();
        int buffer = bufferMap.getOrDefault(uuid, 0);
        if (horizontal > maximum) {
            buffer++;
            if (buffer >= required) {
                violations.flag(
                        player,
                        checkId,
                        1.0,
                        String.format(Locale.US, "horizontal=%.3f max=%.3f ping=%d", horizontal, maximum, player.getPing())
                );
                buffer = Math.max(1, required - 1);
            }
        } else {
            buffer = Math.max(0, buffer - 1);
        }
        bufferMap.put(uuid, buffer);
    }

    private void handleFly(Player player, Location to, double deltaY, long now) {
        UUID uuid = player.getUniqueId();
        if (!violations.isCheckEnabled("fly-a") && !violations.isCheckEnabled("fly-b")) {
            return;
        }

        if (player.isOnGround()) {
            airEvents.put(uuid, 0);
            hoverBuffer.put(uuid, 0);
            ascentBuffer.put(uuid, 0);
            lastSafe.put(uuid, to.clone());
            return;
        }

        int air = airEvents.getOrDefault(uuid, 0) + 1;
        airEvents.put(uuid, air);

        if (violations.isCheckEnabled("fly-a")) {
            handleHover(player, air, deltaY, now);
        }
        if (violations.isCheckEnabled("fly-b")) {
            handleAscent(player, air, deltaY, now);
        }
    }

    private void handleHover(Player player, int air, double deltaY, long now) {
        UUID uuid = player.getUniqueId();
        int maxAir = Math.max(1, plugin.getConfig().getInt("checks.fly-a.max-air-events", 20));
        double hoverAbsoluteMax = Math.max(0.001,
                plugin.getConfig().getDouble("checks.fly-a.hover-vertical-absolute-max", 0.030));

        int hover = hoverBuffer.getOrDefault(uuid, 0);
        if (air > maxAir && Math.abs(deltaY) <= hoverAbsoluteMax) {
            hover++;
        } else {
            hover = Math.max(0, hover - 1);
        }

        int requiredHover = Math.max(1,
                plugin.getConfig().getInt("checks.fly-a.required-hover-buffer", 5));
        if (hover >= requiredHover) {
            FlagResult result = violations.flag(
                    player,
                    "fly-a",
                    1.0,
                    String.format(Locale.US, "air-events=%d dy=%.4f ping=%d", air, deltaY, player.getPing())
            );

            maybeSetback(player, result, now, "checks.fly-a.setback-vl", 4.0);
            hover = Math.max(1, requiredHover / 2);
        }
        hoverBuffer.put(uuid, hover);
    }

    private void handleAscent(Player player, int air, double deltaY, long now) {
        UUID uuid = player.getUniqueId();
        int minimumAir = Math.max(1,
                plugin.getConfig().getInt("checks.fly-b.minimum-air-events", 8));
        double minimumDelta = Math.max(0.001,
                plugin.getConfig().getDouble("checks.fly-b.minimum-upward-delta", 0.060));
        double maximumDelta = Math.max(minimumDelta,
                plugin.getConfig().getDouble("checks.fly-b.maximum-upward-delta", 0.750));
        int required = Math.max(1,
                plugin.getConfig().getInt("checks.fly-b.required-buffer", 4));

        int buffer = ascentBuffer.getOrDefault(uuid, 0);
        boolean suspicious = air >= minimumAir && deltaY >= minimumDelta && deltaY <= maximumDelta;
        if (suspicious) {
            buffer++;
            if (buffer >= required) {
                FlagResult result = violations.flag(
                        player,
                        "fly-b",
                        1.0,
                        String.format(Locale.US, "air-events=%d upward-dy=%.4f ping=%d", air, deltaY, player.getPing())
                );
                maybeSetback(player, result, now, "checks.fly-b.setback-vl", 4.0);
                buffer = Math.max(1, required - 1);
            }
        } else {
            buffer = Math.max(0, buffer - 1);
        }
        ascentBuffer.put(uuid, buffer);
    }

    private void maybeSetback(Player player, FlagResult result, long now, String path, double fallback) {
        double setbackLevel = plugin.getConfig().getDouble(path, fallback);
        if (violations.isEnforcementEnabled()
                && result.accepted()
                && result.violationLevel() >= setbackLevel) {
            attemptSetback(player, now);
        }
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
        if (isClimbableLike(to.getBlock().getType())
                || isClimbableLike(to.clone().subtract(0.0, 1.0, 0.0).getBlock().getType())) {
            return true;
        }

        long velocityAt = recentVelocityAt.getOrDefault(player.getUniqueId(), 0L);
        long grace = Math.max(250L,
                plugin.getConfig().getLong("movement-engine.velocity-grace-ms", 1500L));
        return now - velocityAt < grace;
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
        return (effect.getAmplifier() + 1) * 0.075;
    }

    private boolean hasEffect(Player player, PotionEffectType type) {
        return player.getPotionEffect(type) != null;
    }

    private boolean isFinitePosition(Location location) {
        return Double.isFinite(location.getX())
                && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ());
    }

    private boolean isFiniteRotation(Location location) {
        return Float.isFinite(location.getYaw()) && Float.isFinite(location.getPitch());
    }

    private void clearActiveBuffers(UUID uuid) {
        speedBuffer.put(uuid, 0);
        airSpeedBuffer.put(uuid, 0);
        airEvents.put(uuid, 0);
        hoverBuffer.put(uuid, 0);
        ascentBuffer.put(uuid, 0);
    }

    private void resetMovementState(Player player) {
        UUID uuid = player.getUniqueId();
        lastMoveAt.remove(uuid);
        speedBuffer.remove(uuid);
        airSpeedBuffer.remove(uuid);
        airEvents.remove(uuid);
        hoverBuffer.remove(uuid);
        ascentBuffer.remove(uuid);
        lastSetbackAt.remove(uuid);
        recentVelocityAt.put(uuid, System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastMoveAt.remove(uuid);
        recentVelocityAt.remove(uuid);
        speedBuffer.remove(uuid);
        airSpeedBuffer.remove(uuid);
        airEvents.remove(uuid);
        hoverBuffer.remove(uuid);
        ascentBuffer.remove(uuid);
        lastSafe.remove(uuid);
        lastSetbackAt.remove(uuid);
    }
}
