# Changelog

All notable changes to CdrAntiCheat are documented here.

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
