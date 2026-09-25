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
import store.cadera.cdranticheat.licensing.LicenseManager;
import store.cadera.cdranticheat.observation.EvidenceSessionManager;
import store.cadera.cdranticheat.observation.ObservationManager;
import store.cadera.cdranticheat.packet.PacketEngine;
import store.cadera.cdranticheat.packet.PacketEngineFactory;

public final class CdrAntiCheat extends JavaPlugin {

    private LicenseManager licenseManager;
    private AlertService alertService;
    private BedrockDetector bedrockDetector;
    private ObservationManager observationManager;
    private EvidenceSessionManager evidenceSessionManager;
    private ViolationManager violationManager;
    private PacketEngine packetEngine;

    @Override
    public void onEnable() {
        licenseManager = new LicenseManager(this);
        if (!licenseManager.initialize()) {
            getLogger().severe("CdrAntiCheat cannot start without a valid MENKIESTES runtime license.");
            getLogger().severe("Restore the original plugins/CdrAntiCheat/LICENSE.txt and restart the server.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        licenseManager.startMonitor();

        saveDefaultConfig();

        bedrockDetector = new BedrockDetector(this);
        alertService = new AlertService(this);
        observationManager = new ObservationManager(this);
        observationManager.start();
        evidenceSessionManager = new EvidenceSessionManager(this);
        evidenceSessionManager.start();
        violationManager = new ViolationManager(
                this,
                alertService,
                bedrockDetector,
                observationManager,
                evidenceSessionManager
        );
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
        getLogger().info("License: VALID (SHA-256 " + licenseManager.expectedHash().substring(0, 12) + "...).");
        getLogger().info("Checks: bad-movement-a, speed-a, fly-a, reach-a, autoclicker-a, timer-a, bad-packets-a, aim-a, multitarget-a, attack-timing-a, killaura-a");
        getLogger().info("Observation engine: " + (violationManager.isEnforcementEnabled() ? "ENFORCE" : "OBSERVE") + " mode.");
        getLogger().info("Evidence sessions: "
                + (getConfig().getBoolean("evidence.enabled", true) ? "enabled" : "disabled") + ".");
        if (packetEngineStarted) {
            getLogger().info("Packet engine active via " + packetEngine.providerName() + ".");
        } else {
            getLogger().warning("Packet engine is unavailable (" + packetEngine.providerName()
                    + "). Event-level checks remain active, but packet and combat-correlation checks are disabled.");
        }

        if (alertService.isDiscordAvailable()) {
            getLogger().info("DiscordSRV detected. Observation alerts can be delivered to Discord.");
        } else {
            getLogger().info("DiscordSRV unavailable. Staff alerts use configured fallback behavior.");
        }
    }

    @Override
    public void onDisable() {
        if (licenseManager != null) {
            licenseManager.stopMonitor();
        }
        if (packetEngine != null) {
            packetEngine.stop();
        }
        if (violationManager != null) {
            violationManager.shutdown();
        }
        if (observationManager != null) {
            observationManager.shutdown();
        }
        if (evidenceSessionManager != null) {
            evidenceSessionManager.close();
        }
        if (alertService != null) {
            alertService.close();
        }
    }

    public LicenseManager getLicenseManager() {
        return licenseManager;
    }

    public AlertService getAlertService() {
        return alertService;
    }

    public BedrockDetector getBedrockDetector() {
        return bedrockDetector;
    }

    public ObservationManager getObservationManager() {
        return observationManager;
    }

    public EvidenceSessionManager getEvidenceSessionManager() {
        return evidenceSessionManager;
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }

    public PacketEngine getPacketEngine() {
        return packetEngine;
    }
}
