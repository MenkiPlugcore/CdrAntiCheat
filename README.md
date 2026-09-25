# CdrAntiCheat

CdrAntiCheat is a modular, packet-aware anti-cheat project for Paper servers by CADERA.

> Current development line: **v0.3.2 Evidence Session & Staff Tracking**

## Target

- Paper 1.21.11
- Java 21
- Standalone Paper server architecture
- PacketEvents 2.14.0 for packet-level checks and telemetry
- Java clients with conservative Geyser/Floodgate compatibility rules
- Optional DiscordSRV observation alert delivery

## Design goals

CdrAntiCheat is designed around low false-positive detection, evidence-based violations, modular checks, configurable punishments, and auditable staff tooling.

Detection is intentionally separated from punishment. In the default `observe` profile, checks continue collecting evidence but do not kick or setback players. Player state is classified through a confidence layer instead of treating one flag as proof of cheating.

## Observation statuses

The observation engine uses five internal states:

- `NORMAL`
- `WATCH`
- `ABNORMAL`
- `SUSPICIOUS`
- `HIGH RISK`

`WATCH` is silent by default. Staff/Discord alerts begin at `ABNORMAL` unless `alerts.minimum-observation-status` is changed.

Repeated evidence from the same check inside a short window is intentionally discounted. Independent checks inside the correlation window receive additional evidence weight. Observation confidence decays after quiet periods so legitimate players naturally return toward `NORMAL`.

Default action mode:

```yaml
observation:
  enforcement-mode: observe
```

`observe` collects evidence and warns staff without enabling kick/setback actions. `enforce` enables the configured punishment thresholds.

## Evidence sessions

v0.3.2 records every accepted flag into a bounded per-player evidence history, including evidence that remains at silent `WATCH` status and never reaches Discord.

A quiet gap automatically starts a new evidence session. Each session tracks:

- unique session ID
- start and last-update time
- total evidence count
- distinct checks involved
- peak observation status
- peak confidence
- last check
- last incident location
- retained chronological evidence records

Each evidence record contains:

- timestamp
- check and current VL
- observation status, score, and confidence
- world and X/Y/Z
- yaw/pitch
- Java/Bedrock platform
- Bukkit ping
- PacketEvents RTT/jitter when available
- server TPS
- enforcement/tracking mode
- check-specific detail such as combat target, distance, timing, rotation, or movement measurement when available

Runtime history is intentionally bounded by `evidence.max-sessions-per-player`, `evidence.max-records-per-session`, and `evidence.runtime-retention-minutes`.

Runtime history powers staff commands and can include recently tracked players after they disconnect. Runtime history is not reloaded after a full server restart.

For persistent review, every accepted evidence item can also be appended to daily files under:

```text
plugins/CdrAntiCheat/evidence/evidence-YYYY-MM-DD.log
```

The evidence log is separate from `logs/violations.log`: the daily evidence log records silent tracking evidence too, while the violation log follows the staff/Discord alert pipeline.

## Current checks

### Event-level foundation

- `speed-a`
- `fly-a`
- `bad-movement-a`
- `reach-a`
  - correlates accepted damage with the recent attack packet target and PacketEvents RTT/jitter telemetry
- `autoclicker-a`

### Packet & timing engine

- `timer-a`
  - analyzes sustained client movement packet cadence
  - uses a rolling timing window, startup grace, teleport grace, and evidence buffer
- `bad-packets-a`
  - validates non-finite movement/rotation data and impossible pitch values at packet level
- `packet-rate-a`
  - generic inbound packet-rate evidence channel
  - disabled by default until a real server baseline is collected

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

## DiscordSRV behavior

DiscordSRV remains an optional soft dependency.

When enabled, CdrAntiCheat sends observation reports containing status, confidence, timestamp, check/VL, world, XYZ, rotation, platform, network state, TPS, correlated signal counts, evidence detail, and action mode.

Set `integrations.discordsrv.channel-id` to a channel ID, or leave it blank to use DiscordSRV's main text channel.

If Discord delivery is unavailable and `alerts.in-game-mode` is `fallback`, the alert is sent to OPs/staff with `cdranticheat.alerts` instead.

## Combat safety gates

Combat correlation checks are intentionally conservative. By default they require:

- an attack packet that matches the entity receiving Bukkit damage
- healthy server TPS
- acceptable keepalive RTT and jitter
- no active teleport/setback grace
- no very recent server velocity grace
- repeated evidence buffers before a violation is added

The default profile also limits advanced combat correlation to player targets while production calibration is still in progress.

## PacketEvents behavior

Packet-level features use PacketEvents as a provided dependency.

When PacketEvents is installed and enabled, CdrAntiCheat starts its packet engine and enables packet telemetry/checks.

When PacketEvents is missing or cannot be linked safely, CdrAntiCheat falls back to a no-op packet engine. Event-level checks can still start, while packet and combat-correlation checks remain unavailable.

## Bedrock / Geyser policy

Packet cadence and rotation behavior from Floodgate/Geyser clients are not assumed to be identical to native Java clients. Timing-sensitive and combat-correlation checks are skipped for detected Bedrock players by default until Bedrock-specific baselines are calibrated.

## Commands

- `/cdrac status`
- `/cdrac reload`
- `/cdrac alerts`
- `/cdrac violations <player>`
- `/cdrac inspect <player>`
- `/cdrac evidence <player> [page]`
- `/cdrac packet <player>`

`/cdrac inspect <player>` shows current observation state plus the latest evidence-session summary, including session ID, evidence count, distinct checks, peak status/confidence, and last incident location.

`/cdrac evidence <player> [page]` shows paginated recent evidence with session ID, time, status/confidence, check/VL, world/coordinates, platform, action mode, and check-specific details. Recently tracked offline players remain inspectable while runtime history is retained.

`/cdrac packet <player>` requires the player to be online and shows live packet/combat telemetry including attack timing statistics, target switching, rotation delta, latency, velocity, and grace state.

Permission root: `cdranticheat.admin`

Staff alert permission: `cdranticheat.alerts`

Bypass permission: `cdranticheat.bypass`

## Installation

For the full packet/combat engine:

1. Run Paper 1.21.11 on Java 21.
2. Install PacketEvents 2.14.0 in the server `plugins` directory.
3. Install the CdrAntiCheat jar.
4. Start the server and confirm `/cdrac status` reports an active PacketEvents engine, enabled evidence sessions, and `OBSERVE (tracking only)` mode.
5. If DiscordSRV is installed, set `integrations.discordsrv.channel-id` or leave it blank to use the main DiscordSRV text channel.
6. Use `/cdrac inspect <player>`, `/cdrac evidence <player>`, and `/cdrac packet <player>` during legitimate gameplay before considering `enforce` mode.

DiscordSRV and Floodgate remain optional integrations.

## Build

```bash
mvn clean package
```

The resulting jar is written to `target/CdrAntiCheat-<version>.jar`.

## Calibration note

`v0.3.2-SNAPSHOT` is development software. A successful compile does not replace live server calibration. Keep the default `observe` mode while collecting legitimate Vephilim traffic across normal PvP, high ping, lag spikes, teleportation, custom skills, knockback/velocity sources, high-CPS clicking, Geyser/Floodgate, and unusual target-switch scenarios.

## Roadmap

See [ROADMAP.md](ROADMAP.md).

## License

CdrAntiCheat is source-available proprietary software under the **MENKIESTES SOFTWARE LICENSE v1.0**. See [LICENSE](LICENSE).
