package store.cadera.cdranticheat;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import store.cadera.cdranticheat.alert.AlertService;
import store.cadera.cdranticheat.check.combat.CombatCorrelationListener;
import store.cadera.cdranticheat.check.combat.CombatListener;
import store.cadera.cdranticheat.check.movement.MovementListener;
import store.cadera.cdranticheat.check.player.AutoClickerListener;
import store.cadera.cdranticheat.command.AntiCheatCommand;
import store.cadera.cdranticheat.compat.BedrockDetector;
import store.cadera.cdranticheat.core.ViolationManager;
import store.cadera.cdranticheat.packet.PacketEngine;
import store.cadera.cdranticheat.packet.PacketEngineFactory;

public final class CdrAntiCheat extends JavaPlugin {

    private AlertService alertService;
    private BedrockDetector bedrockDetector;
    private ViolationManager violationManager;
    private PacketEngine packetEngine;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        bedrockDetector = new BedrockDetector(this);
        alertService = new AlertService(this);
        violationManager = new ViolationManager(this, alertService, bedrockDetector);
        violationManager.start();

        packetEngine = PacketEngineFactory.create(this, violationManager);
        boolean packetEngineStarted = packetEngine.start();

        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new MovementListener(this, violationManager), this);
        pluginManager.registerEvents(new CombatListener(this, violationManager), this);
        pluginManager.registerEvents(new CombatCorrelationListener(this, violationManager), this);
        pluginManager.registerEvents(new AutoClickerListener(this, violationManager), this);

        AntiCheatCommand antiCheatCommand = new AntiCheatCommand(this);
        PluginCommand command = getCommand("cdrac");
        if (command == null) {
            throw new IllegalStateException("Command cdrac is missing from plugin.yml");
        }
        command.setExecutor(antiCheatCommand);
        command.setTabCompleter(antiCheatCommand);

        getLogger().info("CdrAntiCheat " + getDescription().getVersion() + " enabled.");
        getLogger().info("Checks: bad-movement-a, speed-a, fly-a, reach-a, autoclicker-a, timer-a, bad-packets-a, aim-a, multitarget-a, attack-timing-a, killaura-a");
        if (packetEngineStarted) {
            getLogger().info("Packet engine active via " + packetEngine.providerName() + ".");
        } else {
            getLogger().warning("Packet engine is unavailable (" + packetEngine.providerName()
                    + "). Event-level checks remain active, but packet and combat-correlation checks are disabled.");
        }

        if (alertService.isDiscordAvailable()) {
            getLogger().info("DiscordSRV detected. Violation alerts can be delivered to Discord.");
        } else {
            getLogger().info("DiscordSRV unavailable. Staff alerts use configured fallback behavior.");
        }
    }

    @Override
    public void onDisable() {
        if (packetEngine != null) {
            packetEngine.stop();
        }
        if (violationManager != null) {
            violationManager.shutdown();
        }
        if (alertService != null) {
            alertService.close();
        }
    }

    public AlertService getAlertService() {
        return alertService;
    }

    public BedrockDetector getBedrockDetector() {
        return bedrockDetector;
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }

    public PacketEngine getPacketEngine() {
        return packetEngine;
    }
}
