# Changelog

All notable changes to CdrAntiCheat are documented here.

## [0.3.1-SNAPSHOT] - 2026-09-25

### Added

- Confidence & Observation Layer with `NORMAL`, `WATCH`, `ABNORMAL`, `SUSPICIOUS`, and `HIGH RISK` states.
- Configurable evidence weights per check.
- Reduced contribution from rapid repeated flags of the same check.
- Correlation bonus when independent checks occur inside the observation window.
- Confidence decay after quiet periods so legitimate players return toward `NORMAL`.
- Default `observe` enforcement mode that collects evidence and alerts staff without kick/setback actions.
- `/cdrac inspect <player>` observation diagnostics.
- Incident evidence snapshots containing timestamp, world, coordinates, yaw/pitch, platform, ping, PacketEvents RTT/jitter, TPS, check, VL, status, confidence, and signal counts.
- Combat evidence now includes target identity and target distance where available.
- DiscordSRV observation messages now include status, confidence, incident time, world, XYZ, rotation, network state, TPS, signal counts, evidence, and action mode.
- Global observation alert cooldown with immediate delivery on observation status changes.

### Changed

- `WATCH` is silent by default. Staff/Discord alerts begin at `ABNORMAL` unless configured otherwise.
- Existing kick thresholds are ignored while `observation.enforcement-mode: observe` is active.
- Fly setbacks are ignored in observe mode.
- Impossible/non-finite movement can still be cancelled through a dedicated safety option even in observe mode.
- File violation logs now include observation confidence and incident context.
- DiscordSRV remains optional and continues to fall back to in-game staff alerts when delivery is unavailable.

### Safety / False-positive policy

- A single check flag is treated as evidence, not proof of cheating.
- Repeated evidence from one source is weighted less than multiple independent correlated signals.
- Default operation remains tracking-only until legitimate Vephilim traffic has been calibrated.

## [0.3.0-SNAPSHOT] - 2026-09-25

### Added

- Combat correlation telemetry on top of the v0.2 PacketEvents engine.
- Attack target entity ID tracking.
- Rolling attack interval samples with mean and standard deviation.
- Short-window distinct target counting.
- Target-switch interval tracking.
- Attack-to-attack rotation delta tracking.
- Teleport grace visibility in packet diagnostics.
- `aim-a` repeated snap-lock heuristic.
- `multitarget-a` rapid distinct-target heuristic.
- `attack-timing-a` low-variance attack cadence heuristic.
- `killaura-a` multi-signal correlation score combining aim, switching, multi-target, timing, and extreme interval evidence.
- Combat safety gates for TPS, RTT, jitter, teleport grace, recent velocity, packet age, and packet target correlation.
- Expanded `/cdrac packet <player>` combat diagnostics.

### Changed

- `reach-a` now correlates the Bukkit damage target with the latest attack packet when the packet engine is active.
- `reach-a` prefers PacketEvents keepalive RTT and jitter for conservative latency compensation, with Bukkit ping as fallback.
- Advanced combat correlation defaults to player targets during initial production calibration.
- New combat-correlation checks are skipped for detected Floodgate/Bedrock clients by default.

### Notes

- v0.3 combat checks intentionally use buffers and multiple weak/strong signals rather than single-hit punishment.
- Hitbox ray tracing and critical-hit validation remain planned follow-up work inside the v0.3.x line.

## [0.2.0-SNAPSHOT] - 2026-09-25

### Added

- PacketEvents 2.14.0 integration for Paper 1.21.11.
- Optional packet engine with safe no-op fallback when PacketEvents is unavailable or incompatible.
- Thread-safe per-player packet state and reusable telemetry snapshots.
- Movement packet cadence tracking.
- Inbound packet-rate tracking.
- Keepalive RTT and jitter sampling.
- Rotation yaw/pitch and delta tracking.
- Server velocity tracking for future velocity/combat correlation.
- Attack timing tracking from `INTERACT_ENTITY` packets on 1.21.11.
- Teleport grace windows that reset timing evidence after server position corrections.
- `timer-a` sustained movement packet timing check.
- `bad-packets-a` validation for non-finite movement/rotation values and impossible pitch.
- `packet-rate-a` evidence channel, disabled by default pending production calibration.
- `/cdrac packet <player>` packet telemetry diagnostics.
- Packet engine status in `/cdrac status`.
- Packet engine configuration reload through `/cdrac reload`.

### Compatibility

- `timer-a` and `packet-rate-a` are skipped for Floodgate/Bedrock players by default until Bedrock-specific packet timing baselines are available.
- Packet callbacks keep Bukkit player access and violation actions on the main server thread where required.

### Fixed

- PacketEvents listener lifecycle now retains the registered `PacketListenerCommon` handle so it can be unregistered cleanly.

## [0.1.0-SNAPSHOT] - 2026-09-25

### Added

- Initial Paper 1.21.11 / Java 21 project structure.
- MENKIESTES Software License v1.0.
- Central violation engine with decay and thresholds.
- File audit logger and staff/OP fallback alerts.
- Optional DiscordSRV alert delivery.
- Floodgate-aware compatibility handling.
- Initial checks: `speed-a`, `fly-a`, `bad-movement-a`, `reach-a`, and `autoclicker-a`.
- `/cdrac` administrative commands.
