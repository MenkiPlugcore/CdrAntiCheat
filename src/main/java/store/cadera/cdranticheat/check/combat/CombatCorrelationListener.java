package store.cadera.cdranticheat.check.combat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.core.ViolationManager;
import store.cadera.cdranticheat.packet.PacketSnapshot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CombatCorrelationListener implements Listener {

    private final CdrAntiCheat plugin;
    private final ViolationManager violations;
    private final Map<UUID, CombatState> states = new HashMap<>();

    public CombatCorrelationListener(CdrAntiCheat plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        Entity rawTarget = event.getEntity();
        if (!(rawTarget instanceof LivingEntity target) || rawTarget.equals(attacker)) {
            return;
        }

        if (plugin.getConfig().getBoolean("combat-engine.players-only", true) && !(target instanceof Player)) {
            return;
        }

        GameMode mode = attacker.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }
        if (!attacker.getWorld().equals(target.getWorld()) || attacker.isInsideVehicle()) {
            return;
        }

        if (!anyCombatCheckEnabled()) {
            return;
        }

        PacketSnapshot packet = plugin.getPacketEngine().snapshot(attacker.getUniqueId());
        if (!packet.available()) {
            return;
        }

        long maxPacketAge = Math.max(50L,
                plugin.getConfig().getLong("combat-engine.max-packet-age-ms", 220L));
        if (packet.lastAttackAgoMillis() < 0L
                || packet.lastAttackAgoMillis() > maxPacketAge
                || packet.lastTargetEntityId() != target.getEntityId()) {
            return;
        }

        if (packet.teleportGraceRemainingMillis() > 0L) {
            return;
        }

        long velocityGrace = Math.max(0L,
                plugin.getConfig().getLong("combat-engine.velocity-grace-ms", 150L));
        if (packet.lastVelocityAgoMillis() >= 0L && packet.lastVelocityAgoMillis() <= velocityGrace) {
            return;
        }

        double minTps = Math.max(10.0,
                plugin.getConfig().getDouble("combat-engine.min-tps", 18.5));
        double tps = Bukkit.getTPS()[0];
        if (tps < minTps) {
            return;
        }

        long maxRtt = Math.max(0L,
                plugin.getConfig().getLong("combat-engine.max-rtt-ms", 350L));
        long maxJitter = Math.max(0L,
                plugin.getConfig().getLong("combat-engine.max-jitter-ms", 120L));
        if ((packet.keepAliveRttMillis() >= 0L && maxRtt > 0L && packet.keepAliveRttMillis() > maxRtt)
                || (packet.keepAliveJitterMillis() >= 0L && maxJitter > 0L && packet.keepAliveJitterMillis() > maxJitter)) {
            return;
        }

        CombatState state = states.computeIfAbsent(attacker.getUniqueId(), ignored -> new CombatState());
        double aimError = aimErrorDegrees(attacker, target, packet.yaw(), packet.pitch());
        double targetDistance = closestDistance(attacker, target);
        String targetName = target instanceof Player targetPlayer ? targetPlayer.getName() : target.getType().name();

        boolean aimSuspicious = evaluateAim(packet, aimError);
        boolean multiTargetSuspicious = evaluateMultiTarget(packet);
        boolean timingSuspicious = evaluateAttackTiming(packet);
        boolean fastSwitchSuspicious = evaluateFastSwitch(packet);

        int aimRequired = Math.max(2,
                plugin.getConfig().getInt("combat-engine.aim.required-buffer", 4));
        if (state.updateAim(aimSuspicious, aimRequired)
                && violations.isCheckEnabled("aim-a")) {
            violations.flag(attacker, "aim-a", 1.0,
                    details(packet, aimError, tps, targetName, targetDistance, "snap-lock"));
        }

        int multiRequired = Math.max(1,
                plugin.getConfig().getInt("combat-engine.multitarget.required-buffer", 2));
        if (state.updateMultiTarget(multiTargetSuspicious, multiRequired)
                && violations.isCheckEnabled("multitarget-a")) {
            violations.flag(attacker, "multitarget-a", 1.0,
                    details(packet, aimError, tps, targetName, targetDistance, "rapid-target-chain"));
        }

        int timingRequired = Math.max(2,
                plugin.getConfig().getInt("combat-engine.attack-timing.required-buffer", 3));
        if (state.updateTiming(timingSuspicious, timingRequired)
                && violations.isCheckEnabled("attack-timing-a")) {
            violations.flag(attacker, "attack-timing-a", 1.0,
                    details(packet, aimError, tps, targetName, targetDistance, "low-variance-attack-cadence"));
        }

        int auraScore = 0;
        if (aimSuspicious) {
            auraScore += 2;
        }
        if (multiTargetSuspicious) {
            auraScore += 2;
        }
        if (fastSwitchSuspicious) {
            auraScore += 2;
        }
        if (timingSuspicious) {
            auraScore += 1;
        }
        if (packet.lastAttackIntervalMillis() >= 0L
                && packet.lastAttackIntervalMillis() <= Math.max(1L,
                plugin.getConfig().getLong("combat-engine.killaura.extreme-interval-ms", 20L))) {
            auraScore += 1;
        }

        int requiredScore = Math.max(2,
                plugin.getConfig().getInt("combat-engine.killaura.required-score", 4));
        int auraRequiredBuffer = Math.max(2,
                plugin.getConfig().getInt("combat-engine.killaura.required-buffer", 3));
        boolean auraSuspicious = auraScore >= requiredScore;

        if (state.updateKillAura(auraSuspicious, auraRequiredBuffer)
                && violations.isCheckEnabled("killaura-a")) {
            violations.flag(attacker, "killaura-a", 1.0,
                    details(packet, aimError, tps, targetName, targetDistance, "score=" + auraScore));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    private boolean anyCombatCheckEnabled() {
        return violations.isCheckEnabled("aim-a")
                || violations.isCheckEnabled("multitarget-a")
                || violations.isCheckEnabled("attack-timing-a")
                || violations.isCheckEnabled("killaura-a");
    }

    private boolean evaluateAim(PacketSnapshot packet, double aimError) {
        double minSnap = Math.max(5.0,
                plugin.getConfig().getDouble("combat-engine.aim.min-snap-degrees", 42.0));
        double maxError = Math.max(0.01,
                plugin.getConfig().getDouble("combat-engine.aim.max-error-degrees", 0.20));
        long maxInterval = Math.max(30L,
                plugin.getConfig().getLong("combat-engine.aim.max-attack-interval-ms", 250L));

        return packet.attackRotationDeltaDegrees() >= minSnap
                && aimError <= maxError
                && packet.lastAttackIntervalMillis() >= 0L
                && packet.lastAttackIntervalMillis() <= maxInterval;
    }

    private boolean evaluateMultiTarget(PacketSnapshot packet) {
        int minTargets = Math.max(3,
                plugin.getConfig().getInt("combat-engine.multitarget.min-distinct-targets", 3));
        long maxSwitchInterval = Math.max(20L,
                plugin.getConfig().getLong("combat-engine.multitarget.max-switch-interval-ms", 110L));

        return packet.recentDistinctTargets() >= minTargets
                && packet.targetSwitchIntervalMillis() >= 0L
                && packet.targetSwitchIntervalMillis() <= maxSwitchInterval;
    }

    private boolean evaluateAttackTiming(PacketSnapshot packet) {
        int minSamples = Math.max(8,
                plugin.getConfig().getInt("combat-engine.attack-timing.min-samples", 14));
        double minMean = Math.max(1.0,
                plugin.getConfig().getDouble("combat-engine.attack-timing.min-mean-ms", 35.0));
        double maxMean = Math.max(minMean,
                plugin.getConfig().getDouble("combat-engine.attack-timing.max-mean-ms", 130.0));
        double maxStdDev = Math.max(0.1,
                plugin.getConfig().getDouble("combat-engine.attack-timing.max-stddev-ms", 1.25));

        return packet.attackSamples() >= minSamples
                && packet.attackIntervalMeanMillis() >= minMean
                && packet.attackIntervalMeanMillis() <= maxMean
                && packet.attackIntervalStdDevMillis() <= maxStdDev;
    }

    private boolean evaluateFastSwitch(PacketSnapshot packet) {
        long maxSwitchInterval = Math.max(20L,
                plugin.getConfig().getLong("combat-engine.killaura.max-switch-interval-ms", 90L));
        long maxSwitchAge = Math.max(maxSwitchInterval,
                plugin.getConfig().getLong("combat-engine.killaura.max-switch-age-ms", 140L));
        double minRotation = Math.max(10.0,
                plugin.getConfig().getDouble("combat-engine.killaura.min-switch-rotation-degrees", 35.0));

        return packet.targetSwitchIntervalMillis() >= 0L
                && packet.targetSwitchIntervalMillis() <= maxSwitchInterval
                && packet.lastTargetSwitchAgoMillis() >= 0L
                && packet.lastTargetSwitchAgoMillis() <= maxSwitchAge
                && packet.attackRotationDeltaDegrees() >= minRotation;
    }

    private String details(PacketSnapshot packet,
                           double aimError,
                           double tps,
                           String target,
                           double targetDistance,
                           String reason) {
        return String.format(Locale.US,
                "%s target=%s distance=%.3f aimErr=%.3f rotDelta=%.2f targets=%d switch=%dms switchAge=%dms attack=%dms mean=%.2f std=%.2f samples=%d rtt=%d jitter=%d tps=%.2f",
                reason,
                target,
                targetDistance,
                aimError,
                packet.attackRotationDeltaDegrees(),
                packet.recentDistinctTargets(),
                packet.targetSwitchIntervalMillis(),
                packet.lastTargetSwitchAgoMillis(),
                packet.lastAttackIntervalMillis(),
                packet.attackIntervalMeanMillis(),
                packet.attackIntervalStdDevMillis(),
                packet.attackSamples(),
                packet.keepAliveRttMillis(),
                packet.keepAliveJitterMillis(),
                tps);
    }

    private double closestDistance(Player attacker, LivingEntity target) {
        Vector eye = attacker.getEyeLocation().toVector();
        BoundingBox box = target.getBoundingBox();
        double closestX = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double closestY = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double closestZ = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        return eye.distance(new Vector(closestX, closestY, closestZ));
    }

    private double aimErrorDegrees(Player attacker, LivingEntity target, float yaw, float pitch) {
        Vector eye = attacker.getEyeLocation().toVector();
        BoundingBox box = target.getBoundingBox();
        Vector center = new Vector(
                (box.getMinX() + box.getMaxX()) * 0.5,
                (box.getMinY() + box.getMaxY()) * 0.5,
                (box.getMinZ() + box.getMaxZ()) * 0.5
        );
        Vector toTarget = center.subtract(eye);
        if (toTarget.lengthSquared() < 1.0E-8) {
            return 0.0;
        }

        Vector look = directionFromRotation(yaw, pitch);
        double dot = clamp(look.normalize().dot(toTarget.normalize()), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    private Vector directionFromRotation(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRadians);
        return new Vector(
                -cosPitch * Math.sin(yawRadians),
                -Math.sin(pitchRadians),
                cosPitch * Math.cos(yawRadians)
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class CombatState {
        private int aimBuffer;
        private int multiTargetBuffer;
        private int timingBuffer;
        private int killAuraBuffer;

        boolean updateAim(boolean suspicious, int required) {
            aimBuffer = update(aimBuffer, suspicious, required);
            if (aimBuffer >= required) {
                aimBuffer = Math.max(0, required / 2);
                return true;
            }
            return false;
        }

        boolean updateMultiTarget(boolean suspicious, int required) {
            multiTargetBuffer = update(multiTargetBuffer, suspicious, required);
            if (multiTargetBuffer >= required) {
                multiTargetBuffer = Math.max(0, required / 2);
                return true;
            }
            return false;
        }

        boolean updateTiming(boolean suspicious, int required) {
            timingBuffer = update(timingBuffer, suspicious, required);
            if (timingBuffer >= required) {
                timingBuffer = Math.max(0, required / 2);
                return true;
            }
            return false;
        }

        boolean updateKillAura(boolean suspicious, int required) {
            killAuraBuffer = update(killAuraBuffer, suspicious, required);
            if (killAuraBuffer >= required) {
                killAuraBuffer = Math.max(0, required / 2);
                return true;
            }
            return false;
        }

        private int update(int current, boolean suspicious, int required) {
            return suspicious
                    ? Math.min(required + 2, current + 1)
                    : Math.max(0, current - 1);
        }
    }
}
