# CdrAntiCheat

CdrAntiCheat is a modular, packet-aware anti-cheat project for Paper servers by CADERA.

> Current development line: **v0.2.x Packet & Timing Engine**

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

The packet engine also records telemetry that future combat and movement checks can consume without registering separate packet listeners:

- inbound packets per second
- movement packets per second
- keepalive RTT and jitter
- yaw/pitch and rotation deltas
- on-ground state
- last attack timestamp
- last server velocity vector
- packet-check evidence buffers

## PacketEvents behavior

Packet-level features use PacketEvents as a provided dependency.

When PacketEvents is installed and enabled, CdrAntiCheat starts its packet engine and enables packet telemetry/checks.

When PacketEvents is missing or cannot be linked safely, CdrAntiCheat falls back to a no-op packet engine. The plugin can still start and the event-level v0.1 checks remain available. Packet-level checks will not function until PacketEvents is installed.

This fallback is intentional so an optional integration failure does not take the entire anti-cheat or Paper server down.

## Bedrock / Geyser policy

Packet cadence from Floodgate/Geyser clients is not assumed to be identical to native Java clients. Timing-sensitive checks such as `timer-a` and `packet-rate-a` are skipped for detected Bedrock players by default until Bedrock-specific baselines are calibrated.

## Commands

- `/cdrac status`
- `/cdrac reload`
- `/cdrac alerts`
- `/cdrac violations <player>`
- `/cdrac packet <player>`

`/cdrac packet <player>` shows the current packet telemetry snapshot and is intended for development, calibration, and staff diagnostics.

Permission root: `cdranticheat.admin`

Staff alert permission: `cdranticheat.alerts`

Bypass permission: `cdranticheat.bypass`

## Installation

For the full v0.2 packet engine:

1. Run Paper 1.21.11 on Java 21.
2. Install PacketEvents 2.14.0 in the server `plugins` directory.
3. Install the CdrAntiCheat jar.
4. Start the server and confirm `/cdrac status` reports an active PacketEvents engine.
5. Use `/cdrac packet <player>` while testing legitimate gameplay before tightening timing thresholds.

DiscordSRV and Floodgate remain optional integrations.

## Build

```bash
mvn clean package
```

The resulting jar is written to `target/CdrAntiCheat-<version>.jar`.

## Calibration note

`v0.2.0-SNAPSHOT` is development software. A successful compile does not replace live server calibration. Before aggressive punishments are enabled, collect legitimate Vephilim traffic across normal movement, combat, teleportation, high ping, lag spikes, custom skills, Geyser/Floodgate, and server-side velocity sources.

## Roadmap

See [ROADMAP.md](ROADMAP.md).

## License

CdrAntiCheat is source-available proprietary software under the **MENKIESTES SOFTWARE LICENSE v1.0**. See [LICENSE](LICENSE).
