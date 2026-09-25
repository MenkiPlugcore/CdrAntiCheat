package store.cadera.cdranticheat.observation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.packet.PacketSnapshot;

public record EvidenceSnapshot(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        int pingMillis,
        long keepAliveRttMillis,
        long keepAliveJitterMillis,
        double tps,
        long capturedAtMillis
) {
    public static EvidenceSnapshot capture(CdrAntiCheat plugin, Player player) {
        Location location = player.getLocation();
        PacketSnapshot packet = plugin.getPacketEngine() == null
                ? PacketSnapshot.unavailable()
                : plugin.getPacketEngine().snapshot(player.getUniqueId());

        return new EvidenceSnapshot(
                location.getWorld() == null ? "unknown" : location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                Math.max(0, player.getPing()),
                packet.available() ? packet.keepAliveRttMillis() : -1L,
                packet.available() ? packet.keepAliveJitterMillis() : -1L,
                Bukkit.getTPS()[0],
                System.currentTimeMillis()
        );
    }
}
