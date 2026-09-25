package store.cadera.cdranticheat.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import store.cadera.cdranticheat.CdrAntiCheat;

import java.lang.reflect.Method;
import java.util.UUID;

public final class BedrockDetector {

    private final CdrAntiCheat plugin;
    private Object floodgateApi;
    private Method isFloodgatePlayerMethod;

    public BedrockDetector(CdrAntiCheat plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        if (!plugin.getConfig().getBoolean("compatibility.floodgate-detection", true)) {
            return;
        }

        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) {
            return;
        }

        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            floodgateApi = getInstance.invoke(null);
            isFloodgatePlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            plugin.getLogger().info("Floodgate detected. Bedrock compatibility mode is active.");
        } catch (ReflectiveOperationException exception) {
            floodgateApi = null;
            isFloodgatePlayerMethod = null;
            plugin.getLogger().warning("Floodgate is installed, but its API could not be initialized: " + exception.getMessage());
        }
    }

    public boolean isBedrock(Player player) {
        if (floodgateApi == null || isFloodgatePlayerMethod == null) {
            return false;
        }

        try {
            Object result = isFloodgatePlayerMethod.invoke(floodgateApi, player.getUniqueId());
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    public boolean isAvailable() {
        return floodgateApi != null && isFloodgatePlayerMethod != null;
    }
}
