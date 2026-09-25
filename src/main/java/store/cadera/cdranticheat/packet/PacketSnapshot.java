package store.cadera.cdranticheat.packet;

public record PacketSnapshot(
        boolean available,
        long trackedForMillis,
        double inboundPacketsPerSecond,
        double movementPacketsPerSecond,
        long keepAliveRttMillis,
        long keepAliveJitterMillis,
        float yaw,
        float pitch,
        float deltaYaw,
        float deltaPitch,
        boolean onGround,
        int timerBuffer,
        int badPacketBuffer,
        int packetRateBuffer,
        long lastAttackAgoMillis,
        int lastTargetEntityId,
        long lastAttackIntervalMillis,
        int attackSamples,
        double attackIntervalMeanMillis,
        double attackIntervalStdDevMillis,
        int recentDistinctTargets,
        long lastTargetSwitchAgoMillis,
        long targetSwitchIntervalMillis,
        double attackRotationDeltaDegrees,
        long teleportGraceRemainingMillis,
        long lastVelocityAgoMillis,
        double velocityX,
        double velocityY,
        double velocityZ
) {
    public static PacketSnapshot unavailable() {
        return new PacketSnapshot(
                false, 0L, 0.0, 0.0, -1L, -1L,
                0.0f, 0.0f, 0.0f, 0.0f, false,
                0, 0, 0,
                -1L, -1, -1L, 0, 0.0, 0.0, 0,
                -1L, -1L, 0.0, 0L,
                -1L, 0.0, 0.0, 0.0
        );
    }
}
