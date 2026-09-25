package store.cadera.cdranticheat.packet;

import java.util.UUID;

public interface PacketEngine {

    boolean start();

    void stop();

    void reload();

    boolean isAvailable();

    String providerName();

    PacketSnapshot snapshot(UUID uuid);
}
