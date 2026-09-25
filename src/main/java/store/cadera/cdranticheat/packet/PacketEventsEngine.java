package store.cadera.cdranticheat.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import store.cadera.cdranticheat.CdrAntiCheat;
import store.cadera.cdranticheat.core.ViolationManager;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class PacketEventsEngine implements PacketEngine, Listener {

    private final CdrAntiCheat plugin;
    private final ViolationManager violations;
    private final ConcurrentMap<UUID, PacketPlayerData> players = new ConcurrentHashMap<>();
    private final AtomicLong lastErrorLogNanos = new AtomicLong();
    private final String providerVersion;

    private volatile Settings settings;
    private volatile boolean active;
    private PacketListenerCommon packetListener;

    public PacketEventsEngine(CdrAntiCheat plugin, ViolationManager violations) {
        this.plugin = plugin;
        this.violations = violations;
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("packetevents");
        this.providerVersion = dependency == null ? "unknown" : dependency.getDescription().getVersion();
        reload();
    }

    @Override
    public boolean start() {
        if (active) {
            return true;
        }

        try {
            packetListener = PacketEvents.getAPI().getEventManager().registerListener(
                    new CorePacketListener(), PacketListenerPriority.NORMAL
            );
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            active = true;
            return true;
        } catch (Exception | LinkageError exception) {
            plugin.getLogger().warning("Failed to initialize PacketEvents engine: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            packetListener = null;
            active = false;
            return false;
        }
    }

    @Override
    public void stop() {
        if (packetListener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            } catch (Exception | LinkageError ignored) {
            }
            packetListener = null;
        }
        HandlerList.unregisterAll(this);
        players.clear();
        active = false;
    }

    @Override
    public void reload() {
        settings = Settings.from(plugin);
    }

    @Override
    public boolean isAvailable() {
        return active;
    }

    @Override
    public String providerName() {
        return active ? "PacketEvents " + providerVersion : "PacketEvents inactive";
    }

    @Override
    public PacketSnapshot snapshot(UUID uuid) {
        PacketPlayerData data = players.get(uuid);
        return data == null ? PacketSnapshot.unavailable() : data.snapshot(System.nanoTime());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
    }

    private PacketPlayerData data(UUID uuid) {
        return players.computeIfAbsent(uuid, PacketPlayerData::new);
    }

    private void handleReceive(PacketReceiveEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid == null) {
            return;
        }

        Settings current = settings;
        long now = System.nanoTime();
        PacketPlayerData data = data(uuid);
        data.recordInbound(now);

        if (current.packetRateEnabled()
                && data.shouldSamplePacketRate(now, current.packetRateSampleIntervalNanos())) {
            double pps = data.inboundPps();
            if (data.updatePacketRateBuffer(pps > current.packetRateMaxPps(), current.packetRateRequiredBuffer())) {
                flag(uuid, "packet-rate-a", 1.0,
                        "inboundPps=" + format(pps) + " max=" + format(current.packetRateMaxPps()));
            }
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            handleFlying(event, uuid, data, current, now);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.KEEP_ALIVE) {
            WrapperPlayClientKeepAlive keepAlive = new WrapperPlayClientKeepAlive(event);
            data.recordKeepAliveResponse(keepAlive.getId(), now);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                data.recordAttack(now);
            }
        }
    }

    private void handleFlying(PacketReceiveEvent event,
                              UUID uuid,
                              PacketPlayerData data,
                              Settings current,
                              long now) {
        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
        boolean invalid = false;
        String invalidReason = null;

        if (flying.hasPositionChanged()) {
            Vector3d position = flying.getLocation().getPosition();
            if (!Double.isFinite(position.getX())
                    || !Double.isFinite(position.getY())
                    || !Double.isFinite(position.getZ())) {
                invalid = true;
                invalidReason = "non-finite-position";
            }
        }

        if (flying.hasRotationChanged()) {
            float yaw = flying.getLocation().getYaw();
            float pitch = flying.getLocation().getPitch();
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                invalid = true;
                invalidReason = "non-finite-rotation";
            } else if (Math.abs(pitch) > current.maxAbsolutePitch()) {
                invalid = true;
                invalidReason = "pitch=" + format(pitch);
            } else {
                data.recordRotation(yaw, pitch);
            }
        }

        if (current.badPacketsEnabled()) {
            if (data.updateBadPacketBuffer(invalid, current.badPacketRequiredBuffer())) {
                flag(uuid, "bad-packets-a", 1.0,
                        invalidReason == null ? "invalid movement packet" : invalidReason);
            }
        }

        if (invalid) {
            return;
        }

        data.recordMovement(now, current.timerWindowNanos(), flying.isOnGround());

        if (!current.timerEnabled()
                || data.ageMillis(now) < current.timerStartupGraceMillis()
                || data.isInTeleportGrace(now)
                || !data.shouldSampleTimer(now, current.timerSampleIntervalNanos())) {
            return;
        }

        double pps = data.movementPps();
        boolean suspicious = pps > current.timerMaxMovementPps();
        if (data.updateTimerBuffer(suspicious, current.timerRequiredBuffer())) {
            flag(uuid, "timer-a", 1.0,
                    "movementPps=" + format(pps)
                            + " max=" + format(current.timerMaxMovementPps())
                            + " rtt=" + data.keepAliveRttMillis()
                            + "ms jitter=" + data.keepAliveJitterMillis() + "ms");
        }
    }

    private void handleSend(PacketSendEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid == null) {
            return;
        }

        long now = System.nanoTime();
        PacketPlayerData data = data(uuid);

        if (event.getPacketType() == PacketType.Play.Server.KEEP_ALIVE) {
            WrapperPlayServerKeepAlive keepAlive = new WrapperPlayServerKeepAlive(event);
            data.recordKeepAliveSent(keepAlive.getId(), now);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            data.markTeleportGrace(now + settings.teleportGraceNanos());
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity velocityPacket = new WrapperPlayServerEntityVelocity(event);
            int entityId = velocityPacket.getEntityId();
            Vector3d velocity = velocityPacket.getVelocity();
            double x = velocity.getX();
            double y = velocity.getY();
            double z = velocity.getZ();

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.getEntityId() == entityId) {
                    PacketPlayerData currentData = players.get(uuid);
                    if (currentData != null) {
                        currentData.recordVelocity(System.nanoTime(), x, y, z);
                    }
                }
            });
        }
    }

    private void flag(UUID uuid, String checkId, double amount, String details) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                violations.flag(player, checkId, amount, details);
            }
        });
    }

    private void reportListenerError(Exception exception) {
        long now = System.nanoTime();
        long previous = lastErrorLogNanos.get();
        if (now - previous < 5_000_000_000L || !lastErrorLogNanos.compareAndSet(previous, now)) {
            return;
        }
        plugin.getLogger().warning("Packet engine listener error: " + exception.getClass().getSimpleName()
                + ": " + exception.getMessage());
    }

    private static String format(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private final class CorePacketListener implements PacketListener {
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            try {
                handleReceive(event);
            } catch (Exception exception) {
                reportListenerError(exception);
            }
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            try {
                handleSend(event);
            } catch (Exception exception) {
                reportListenerError(exception);
            }
        }
    }

    private record Settings(
            boolean timerEnabled,
            long timerWindowNanos,
            double timerMaxMovementPps,
            int timerRequiredBuffer,
            long timerSampleIntervalNanos,
            long timerStartupGraceMillis,
            boolean badPacketsEnabled,
            float maxAbsolutePitch,
            int badPacketRequiredBuffer,
            boolean packetRateEnabled,
            double packetRateMaxPps,
            int packetRateRequiredBuffer,
            long packetRateSampleIntervalNanos,
            long teleportGraceNanos
    ) {
        static Settings from(CdrAntiCheat plugin) {
            long timerWindowMs = Math.max(1000L,
                    plugin.getConfig().getLong("packet-engine.timer.window-ms", 3000L));
            long timerSampleMs = Math.max(100L,
                    plugin.getConfig().getLong("packet-engine.timer.sample-interval-ms", 250L));
            long packetRateSampleMs = Math.max(250L,
                    plugin.getConfig().getLong("packet-engine.packet-rate.sample-interval-ms", 1000L));
            long teleportGraceMs = Math.max(0L,
                    plugin.getConfig().getLong("packet-engine.teleport-grace-ms", 1500L));

            return new Settings(
                    plugin.getConfig().getBoolean("checks.timer-a.enabled", true),
                    timerWindowMs * 1_000_000L,
                    Math.max(20.0, plugin.getConfig().getDouble("packet-engine.timer.max-movement-pps", 22.75)),
                    Math.max(1, plugin.getConfig().getInt("packet-engine.timer.required-buffer", 6)),
                    timerSampleMs * 1_000_000L,
                    Math.max(0L, plugin.getConfig().getLong("packet-engine.timer.startup-grace-ms", 5000L)),
                    plugin.getConfig().getBoolean("checks.bad-packets-a.enabled", true),
                    (float) Math.max(90.0, plugin.getConfig().getDouble("packet-engine.bad-packets.max-absolute-pitch", 90.01)),
                    Math.max(1, plugin.getConfig().getInt("packet-engine.bad-packets.required-buffer", 2)),
                    plugin.getConfig().getBoolean("checks.packet-rate-a.enabled", false),
                    Math.max(50.0, plugin.getConfig().getDouble("packet-engine.packet-rate.max-inbound-pps", 250.0)),
                    Math.max(1, plugin.getConfig().getInt("packet-engine.packet-rate.required-buffer", 4)),
                    packetRateSampleMs * 1_000_000L,
                    teleportGraceMs * 1_000_000L
            );
        }
    }
}
