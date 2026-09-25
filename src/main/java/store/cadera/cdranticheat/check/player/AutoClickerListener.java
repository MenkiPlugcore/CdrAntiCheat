package store.cadera.cdranticheat.check.player;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.core.ViolationManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AutoClickerListener implements Listener {

    private final CdrAntiCheat plugin;
    private final ViolationManager violations;
    private final Map<UUID, Deque<Long>> clicks = new HashMap<>();
    private final Map<UUID, Long> lastSampleAt = new HashMap<>();
    private final Map<UUID, Integer> buffers = new HashMap<>();

    public AutoClickerListener(CdrAntiCheat plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!violations.isCheckEnabled("autoclicker-a")) {
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Deque<Long> samples = clicks.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        samples.addLast(now);

        long cutoff = now - 1000L;
        while (!samples.isEmpty() && samples.peekFirst() < cutoff) {
            samples.removeFirst();
        }

        long interval = Math.max(100L,
                plugin.getConfig().getLong("checks.autoclicker-a.sample-interval-ms", 250L));
        long previousSample = lastSampleAt.getOrDefault(uuid, 0L);
        if (now - previousSample < interval) {
            return;
        }
        lastSampleAt.put(uuid, now);

        int cps = samples.size();
        int maxCps = Math.max(1, plugin.getConfig().getInt("checks.autoclicker-a.max-cps", 24));
        int buffer = buffers.getOrDefault(uuid, 0);

        if (cps > maxCps) {
            buffer++;
            int required = Math.max(1,
                    plugin.getConfig().getInt("checks.autoclicker-a.required-buffer", 4));
            if (buffer >= required) {
                violations.flag(player, "autoclicker-a", 1.0,
                        "cps=" + cps + " max=" + maxCps + " ping=" + player.getPing());
                buffer = Math.max(1, required - 1);
            }
        } else {
            buffer = Math.max(0, buffer - 1);
        }

        buffers.put(uuid, buffer);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clicks.remove(uuid);
        lastSampleAt.remove(uuid);
        buffers.remove(uuid);
    }
}
