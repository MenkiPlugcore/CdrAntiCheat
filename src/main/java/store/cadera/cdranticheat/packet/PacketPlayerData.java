package store.cadera.cdranticheat.packet;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PacketPlayerData {

    private static final long COMBAT_HISTORY_NANOS = 1_000_000_000L;
    private static final long DISTINCT_TARGET_WINDOW_NANOS = 250_000_000L;
    private static final long COMBAT_SESSION_GAP_NANOS = 2_000_000_000L;
    private static final int MAX_ATTACK_INTERVAL_SAMPLES = 24;

    private final UUID uuid;
    private final long createdAtNanos = System.nanoTime();
    private final Deque<Long> movementTimes = new ArrayDeque<>();
    private final Map<Long, Long> keepAliveSentAt = new HashMap<>();
    private final Deque<AttackSample> attacks = new ArrayDeque<>();
    private final Deque<Long> attackIntervalsNanos = new ArrayDeque<>();

    private long inboundWindowStartNanos = createdAtNanos;
    private int inboundWindowCount;
    private double inboundPps;
    private double movementPps;

    private long lastTimerSampleNanos;
    private long lastPacketRateSampleNanos;
    private int timerBuffer;
    private int badPacketBuffer;
    private int packetRateBuffer;

    private boolean hasRotation;
    private float yaw;
    private float pitch;
    private float deltaYaw;
    private float deltaPitch;
    private boolean onGround;

    private long keepAliveRttMillis = -1L;
    private long keepAliveJitterMillis = -1L;
    private long teleportGraceUntilNanos;

    private long lastAttackNanos;
    private int lastTargetEntityId = -1;
    private long lastTargetSwitchNanos;
    private long targetSwitchIntervalNanos = -1L;
    private boolean hasAttackRotation;
    private float lastAttackYaw;
    private float lastAttackPitch;
    private double attackRotationDeltaDegrees;

    private long lastVelocityNanos;
    private double velocityX;
    private double velocityY;
    private double velocityZ;

    PacketPlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    UUID uuid() {
        return uuid;
    }

    synchronized void recordInbound(long nowNanos) {
        inboundWindowCount++;
        long elapsed = nowNanos - inboundWindowStartNanos;
        if (elapsed >= 1_000_000_000L) {
            inboundPps = inboundWindowCount / (elapsed / 1_000_000_000.0);
            inboundWindowCount = 0;
            inboundWindowStartNanos = nowNanos;
        }
    }

    synchronized void recordMovement(long nowNanos, long windowNanos, boolean ground) {
        movementTimes.addLast(nowNanos);
        long cutoff = nowNanos - windowNanos;
        while (!movementTimes.isEmpty() && movementTimes.peekFirst() < cutoff) {
            movementTimes.removeFirst();
        }
        movementPps = movementTimes.size() / Math.max(0.001, windowNanos / 1_000_000_000.0);
        onGround = ground;
    }

    synchronized void recordRotation(float newYaw, float newPitch) {
        if (hasRotation) {
            deltaYaw = normalizeYaw(newYaw - yaw);
            deltaPitch = newPitch - pitch;
        }
        yaw = newYaw;
        pitch = newPitch;
        hasRotation = true;
    }

    synchronized boolean shouldSampleTimer(long nowNanos, long intervalNanos) {
        if (nowNanos - lastTimerSampleNanos < intervalNanos) {
            return false;
        }
        lastTimerSampleNanos = nowNanos;
        return true;
    }

    synchronized boolean shouldSamplePacketRate(long nowNanos, long intervalNanos) {
        if (nowNanos - lastPacketRateSampleNanos < intervalNanos) {
            return false;
        }
        lastPacketRateSampleNanos = nowNanos;
        return true;
    }

    synchronized boolean updateTimerBuffer(boolean suspicious, int required) {
        timerBuffer = suspicious ? Math.min(required + 2, timerBuffer + 1) : Math.max(0, timerBuffer - 1);
        if (timerBuffer >= required) {
            timerBuffer = Math.max(1, required - 1);
            return true;
        }
        return false;
    }

    synchronized boolean updateBadPacketBuffer(boolean suspicious, int required) {
        badPacketBuffer = suspicious ? Math.min(required + 2, badPacketBuffer + 1) : Math.max(0, badPacketBuffer - 1);
        if (badPacketBuffer >= required) {
            badPacketBuffer = Math.max(1, required - 1);
            return true;
        }
        return false;
    }

    synchronized boolean updatePacketRateBuffer(boolean suspicious, int required) {
        packetRateBuffer = suspicious ? Math.min(required + 2, packetRateBuffer + 1) : Math.max(0, packetRateBuffer - 1);
        if (packetRateBuffer >= required) {
            packetRateBuffer = Math.max(1, required - 1);
            return true;
        }
        return false;
    }

    synchronized void recordKeepAliveSent(long id, long nowNanos) {
        keepAliveSentAt.put(id, nowNanos);
        keepAliveSentAt.entrySet().removeIf(entry -> nowNanos - entry.getValue() > 60_000_000_000L);
    }

    synchronized boolean recordKeepAliveResponse(long id, long nowNanos) {
        Long sent = keepAliveSentAt.remove(id);
        if (sent == null) {
            return false;
        }
        long newRtt = Math.max(0L, (nowNanos - sent) / 1_000_000L);
        if (keepAliveRttMillis >= 0L) {
            keepAliveJitterMillis = Math.abs(newRtt - keepAliveRttMillis);
        }
        keepAliveRttMillis = newRtt;
        return true;
    }

    synchronized void markTeleportGrace(long untilNanos) {
        teleportGraceUntilNanos = Math.max(teleportGraceUntilNanos, untilNanos);
        movementTimes.clear();
        movementPps = 0.0;
        timerBuffer = 0;
        resetCombatHistory();
    }

    synchronized boolean isInTeleportGrace(long nowNanos) {
        return nowNanos < teleportGraceUntilNanos;
    }

    synchronized long ageMillis(long nowNanos) {
        return Math.max(0L, (nowNanos - createdAtNanos) / 1_000_000L);
    }

    synchronized double movementPps() {
        return movementPps;
    }

    synchronized double inboundPps() {
        return inboundPps;
    }

    synchronized long keepAliveRttMillis() {
        return keepAliveRttMillis;
    }

    synchronized long keepAliveJitterMillis() {
        return keepAliveJitterMillis;
    }

    synchronized void recordAttack(long nowNanos, int targetEntityId) {
        if (lastAttackNanos > 0L) {
            long interval = nowNanos - lastAttackNanos;
            if (interval > COMBAT_SESSION_GAP_NANOS) {
                resetCombatHistory();
            } else if (interval > 0L) {
                attackIntervalsNanos.addLast(interval);
                while (attackIntervalsNanos.size() > MAX_ATTACK_INTERVAL_SAMPLES) {
                    attackIntervalsNanos.removeFirst();
                }
            }
        }

        if (lastTargetEntityId >= 0 && targetEntityId != lastTargetEntityId) {
            targetSwitchIntervalNanos = lastAttackNanos <= 0L ? -1L : Math.max(0L, nowNanos - lastAttackNanos);
            lastTargetSwitchNanos = nowNanos;
        }

        if (hasAttackRotation && hasRotation) {
            double yawDelta = normalizeYaw(yaw - lastAttackYaw);
            double pitchDelta = pitch - lastAttackPitch;
            attackRotationDeltaDegrees = Math.hypot(yawDelta, pitchDelta);
        } else {
            attackRotationDeltaDegrees = 0.0;
        }

        if (hasRotation) {
            lastAttackYaw = yaw;
            lastAttackPitch = pitch;
            hasAttackRotation = true;
        }

        lastAttackNanos = nowNanos;
        lastTargetEntityId = targetEntityId;
        attacks.addLast(new AttackSample(nowNanos, targetEntityId));
        pruneAttacks(nowNanos);
    }

    synchronized void recordVelocity(long nowNanos, double x, double y, double z) {
        lastVelocityNanos = nowNanos;
        velocityX = x;
        velocityY = y;
        velocityZ = z;
    }

    synchronized PacketSnapshot snapshot(long nowNanos) {
        pruneAttacks(nowNanos);

        long lastAttackAgo = lastAttackNanos == 0L
                ? -1L
                : Math.max(0L, (nowNanos - lastAttackNanos) / 1_000_000L);
        long lastAttackInterval = attackIntervalsNanos.isEmpty()
                ? -1L
                : Math.max(0L, attackIntervalsNanos.peekLast() / 1_000_000L);

        double meanMillis = intervalMeanMillis();
        double stdDevMillis = intervalStdDevMillis(meanMillis);
        int distinctTargets = recentDistinctTargets(nowNanos);
        long switchAgoMillis = lastTargetSwitchNanos == 0L
                ? -1L
                : Math.max(0L, (nowNanos - lastTargetSwitchNanos) / 1_000_000L);
        long switchIntervalMillis = targetSwitchIntervalNanos < 0L
                ? -1L
                : targetSwitchIntervalNanos / 1_000_000L;
        long teleportGraceRemaining = nowNanos >= teleportGraceUntilNanos
                ? 0L
                : Math.max(0L, (teleportGraceUntilNanos - nowNanos) / 1_000_000L);

        return new PacketSnapshot(
                true,
                ageMillis(nowNanos),
                inboundPps,
                movementPps,
                keepAliveRttMillis,
                keepAliveJitterMillis,
                yaw,
                pitch,
                deltaYaw,
                deltaPitch,
                onGround,
                timerBuffer,
                badPacketBuffer,
                packetRateBuffer,
                lastAttackAgo,
                lastTargetEntityId,
                lastAttackInterval,
                attackIntervalsNanos.size(),
                meanMillis,
                stdDevMillis,
                distinctTargets,
                switchAgoMillis,
                switchIntervalMillis,
                attackRotationDeltaDegrees,
                teleportGraceRemaining,
                lastVelocityNanos == 0L ? -1L : Math.max(0L, (nowNanos - lastVelocityNanos) / 1_000_000L),
                velocityX,
                velocityY,
                velocityZ
        );
    }

    private void resetCombatHistory() {
        attacks.clear();
        attackIntervalsNanos.clear();
        lastAttackNanos = 0L;
        lastTargetEntityId = -1;
        lastTargetSwitchNanos = 0L;
        targetSwitchIntervalNanos = -1L;
        hasAttackRotation = false;
        attackRotationDeltaDegrees = 0.0;
    }

    private void pruneAttacks(long nowNanos) {
        long cutoff = nowNanos - COMBAT_HISTORY_NANOS;
        while (!attacks.isEmpty() && attacks.peekFirst().timeNanos() < cutoff) {
            attacks.removeFirst();
        }
    }

    private int recentDistinctTargets(long nowNanos) {
        long cutoff = nowNanos - DISTINCT_TARGET_WINDOW_NANOS;
        Set<Integer> targets = new HashSet<>();
        for (AttackSample sample : attacks) {
            if (sample.timeNanos() >= cutoff) {
                targets.add(sample.targetEntityId());
            }
        }
        return targets.size();
    }

    private double intervalMeanMillis() {
        if (attackIntervalsNanos.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (long interval : attackIntervalsNanos) {
            sum += interval / 1_000_000.0;
        }
        return sum / attackIntervalsNanos.size();
    }

    private double intervalStdDevMillis(double meanMillis) {
        if (attackIntervalsNanos.size() < 2) {
            return 0.0;
        }
        double sum = 0.0;
        for (long interval : attackIntervalsNanos) {
            double value = interval / 1_000_000.0;
            double delta = value - meanMillis;
            sum += delta * delta;
        }
        return Math.sqrt(sum / attackIntervalsNanos.size());
    }

    private static float normalizeYaw(float value) {
        float result = value % 360.0f;
        if (result > 180.0f) {
            result -= 360.0f;
        } else if (result < -180.0f) {
            result += 360.0f;
        }
        return result;
    }

    private record AttackSample(long timeNanos, int targetEntityId) {
    }
}
