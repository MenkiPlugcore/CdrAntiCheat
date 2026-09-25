package store.cadera.cdranticheat.packet;

import org.bukkit.plugin.Plugin;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.core.ViolationManager;

import java.lang.reflect.Constructor;

public final class PacketEngineFactory {

    private static final String IMPLEMENTATION = "store.cadera.cdranticheat.packet.PacketEventsEngine";

    private PacketEngineFactory() {
    }

    public static PacketEngine create(CdrAntiCheat plugin, ViolationManager violations) {
        Plugin packetEvents = plugin.getServer().getPluginManager().getPlugin("packetevents");
        if (packetEvents == null || !packetEvents.isEnabled()) {
            return new NoopPacketEngine("PacketEvents not installed");
        }

        try {
            Class<?> implementation = Class.forName(IMPLEMENTATION, true, plugin.getClass().getClassLoader());
            Constructor<?> constructor = implementation.getConstructor(CdrAntiCheat.class, ViolationManager.class);
            Object engine = constructor.newInstance(plugin, violations);
            return (PacketEngine) engine;
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning("PacketEvents engine could not be loaded: " + exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
            return new NoopPacketEngine("PacketEvents incompatible");
        }
    }
}
