package store.cadera.cdranticheat.check.combat;

import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.core.ViolationManager;

import java.util.Locale;

public final class CombatListener implements Listener {

    private final CdrAntiCheat plugin;
    private final ViolationManager violations;

    public CombatListener(CdrAntiCheat plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        Entity damaged = event.getEntity();
        if (!(damaged instanceof LivingEntity target) || damaged.equals(player)) {
            return;
        }
        if (!violations.isCheckEnabled("reach-a")) {
            return;
        }

        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }
        if (!player.getWorld().equals(target.getWorld()) || player.isInsideVehicle()) {
            return;
        }

        BoundingBox box = target.getBoundingBox().expand(0.10);
        Vector eye = player.getEyeLocation().toVector();
        double closestX = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double closestY = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double closestZ = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double distance = eye.distance(new Vector(closestX, closestY, closestZ));

        int ping = Math.max(0, player.getPing());
        double base = plugin.getConfig().getDouble("checks.reach-a.base-max-distance", 3.75);
        int maxPingComp = Math.max(0,
                plugin.getConfig().getInt("checks.reach-a.max-ping-compensation-ms", 200));
        double perMs = Math.max(0.0,
                plugin.getConfig().getDouble("checks.reach-a.compensation-per-ms", 0.0015));
        double allowed = base + Math.min(ping, maxPingComp) * perMs;

        if (distance > allowed) {
            violations.flag(
                    player,
                    "reach-a",
                    1.0,
                    String.format(Locale.US, "distance=%.3f max=%.3f ping=%d target=%s",
                            distance, allowed, ping, target.getType().name())
            );
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
