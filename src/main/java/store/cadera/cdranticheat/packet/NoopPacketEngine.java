package store.cadera.cdranticheat.packet;

import java.util.UUID;

public final class NoopPacketEngine implements PacketEngine {

    private final String reason;

    public NoopPacketEngine(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public void stop() {
    }

    @Override
    public void reload() {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String providerName() {
        return reason;
    }

    @Override
    public PacketSnapshot snapshot(UUID uuid) {
        return PacketSnapshot.unavailable();
    }
}
