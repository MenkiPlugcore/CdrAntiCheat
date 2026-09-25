# CdrAntiCheat

CdrAntiCheat is a modular, packet-aware anti-cheat project for Paper servers by CADERA.

> Current development line: **v0.3.x Combat Correlation Engine**

## Target

- Paper 1.21.11
- Java 21
- Standalone Paper server architecture
- PacketEvents 2.14.0 for packet-level checks and telemetry
- Java clients with conservative Geyser/Floodgate compatibility rules
- Optional DiscordSRV alert delivery

## Design goals

CdrAntiCheat is designed around low false-positive detection, evidence-based violations, modular checks, configurable punishments, and auditable staff alerts.

Detection is intentionally separated from punishment. Suspicious behavior accumulates violation levels, decays over time, and only reaches alerts, setbacks, or kicks after configured evidence thresholds.

## Current checks

### Event-level foundation

- `speed-a`
- `fly-a`
- `bad-movement-a`
- `reach-a`
  - v0.3 correlates accepted damage with the recent attack packet target and PacketEvents RTT/jitter telemetry before evaluating reach
- `autoclicker-a`

### Packet & timing engine

- `timer-a`
  - analyzes sustained client movement packet cadence
  - uses a rolling timing window, startup grace, teleport grace, and evidence buffer
- `bad-packets-a`
  - validates non-finite movement/rotation data and impossible pitch values at packet level
- `packet-rate-a`
  - generic inbound packet-rate evidence channel
  - **disabled by default** until a real server baseline is collected

### Combat correlation engine

- `aim-a`
  - looks for repeated large rotation snaps that terminate extremely close to target center
  - requires multiple buffered samples rather than one flick
- `multitarget-a`
  - correlates rapid attack packets across three or more distinct entity IDs inside a short combat window
- `attack-timing-a`
  - looks for unusually low variance in attack intervals across a sufficiently long sample
- `killaura-a`
  - multi-signal correlation check
  - combines aim snap, fast target switching, multi-target evidence, low-variance timing, and extreme attack intervals
  - no single weak signal is intended to trigger this check alone

The packet engine records reusable telemetry for combat and future movement checks:

- inbound packets per second
- movement packets per second
- keepalive RTT and jitter
- yaw/pitch and rotation deltas
- on-ground state
- attack target entity ID
- attack interval mean and standard deviation
- short-window distinct target count
- target-switch interval
- attack-to-attack rotation delta
- teleport grace state
- last server velocity vector
- packet-check evidence buffers

## Combat safety gates

Combat correlation checks are intentionally conservative. By default they require:

- an attack packet that matches the entity receiving Bukkit damage
- healthy server TPS
- acceptable keepalive RTT and jitter
- no active teleport/setback grace
- no very recent server velocity grace
- repeated evidence buffers before a violation is added

The default v0.3 profile also limits advanced combat correlation to player targets while production calibration is still in progress.

## PacketEvents behavior

Packet-level features use PacketEvents as a provided dependency.

When PacketEvents is installed and enabled, CdrAntiCheat starts its packet engine and enables packet telemetry/checks.

When PacketEvents is missing or cannot be linked safely, CdrAntiCheat falls back to a no-op packet engine. Event-level checks can still start, while packet and combat-correlation checks remain unavailable.

This fallback is intentional so an optional integration failure does not take the entire anti-cheat or Paper server down.

## Bedrock / Geyser policy

Packet cadence and rotation behavior from Floodgate/Geyser clients are not assumed to be identical to native Java clients. Timing-sensitive and combat-correlation checks are skipped for detected Bedrock players by default until Bedrock-specific baselines are calibrated.

## Commands

- `/cdrac status`
- `/cdrac reload`
- `/cdrac alerts`
- `/cdrac violations <player>`
- `/cdrac packet <player>`

`/cdrac packet <player>` shows packet and combat telemetry including attack timing statistics, target switching, rotation delta, latency, velocity, and grace state. It is intended for development, calibration, and staff diagnostics.

Permission root: `cdranticheat.admin`

Staff alert permission: `cdranticheat.alerts`

Bypass permission: `cdranticheat.bypass`

## Installation

For the full packet/combat engine:

1. Run Paper 1.21.11 on Java 21.
2. Install PacketEvents 2.14.0 in the server `plugins` directory.
3. Install the CdrAntiCheat jar.
4. Start the server and confirm `/cdrac status` reports an active PacketEvents engine and available combat correlation.
5. Use `/cdrac packet <player>` during legitimate PvP before tightening combat thresholds.

DiscordSRV and Floodgate remain optional integrations.

## Build

```bash
mvn clean package
```

The resulting jar is written to `target/CdrAntiCheat-<version>.jar`.

## Calibration note

`v0.3.0-SNAPSHOT` is development software. A successful compile does not replace live server calibration. Before aggressive punishments are enabled, collect legitimate Vephilim traffic across normal PvP, high ping, lag spikes, teleportation, custom skills, knockback/velocity sources, high-CPS clicking, Geyser/Floodgate, and unusual target-switch scenarios.

## Roadmap

See [ROADMAP.md](ROADMAP.md).

## License

CdrAntiCheat is source-available proprietary software under the **MENKIESTES SOFTWARE LICENSE v1.0**. See [LICENSE](LICENSE).
