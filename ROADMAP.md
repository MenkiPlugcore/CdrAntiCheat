# CdrAntiCheat Roadmap

The roadmap is intentionally incremental. Anti-cheat systems become unreliable when aggressive detections are added before their data model, exemptions, and evidence pipeline are stable.

## v0.1.x - Foundation

- [x] Project bootstrap for Paper 1.21.11 / Java 21
- [x] Central violation engine
- [x] Violation decay and threshold handling
- [x] File audit logger
- [x] OP/staff fallback alerts
- [x] DiscordSRV optional alert bridge
- [x] Floodgate-aware Bedrock compatibility
- [x] Administrative command
- [x] `speed-a`
- [x] `fly-a`
- [x] `bad-movement-a`
- [x] `reach-a`
- [x] `autoclicker-a`

## v0.2.x - Packet & Timing Engine

- [x] PacketEvents integration
- [x] safe fallback when PacketEvents is missing/incompatible
- [x] thread-safe per-player packet state
- [x] packet movement timeline / cadence sampling
- [x] `timer-a`
- [x] basic invalid movement/rotation packet validation (`bad-packets-a`)
- [x] keepalive RTT and jitter samples
- [x] rotation delta telemetry
- [x] attack timing telemetry
- [x] server velocity telemetry
- [x] teleport/setback timing grace
- [x] generic packet-rate telemetry
- [x] optional `packet-rate-a` check (disabled by default)
- [x] `/cdrac packet <player>` diagnostics
- [ ] deeper packet-order/state-machine validation
- [ ] transaction-confirmation timeline for checks that require acknowledgement correlation
- [ ] production calibration from legitimate Vephilim traffic

## v0.3.x - Combat Correlation & Observation

### v0.3.0 Combat Correlation Engine

- [x] packet attack + target entity correlation
- [x] reach correlation improvements using packet RTT/jitter and target match
- [x] rotation/aim correlation telemetry
- [x] `aim-a` repeated snap-lock heuristic
- [x] multi-target / improbable target switching telemetry
- [x] `multitarget-a`
- [x] rolling attack interval statistics
- [x] `attack-timing-a`
- [x] velocity-aware combat grace
- [x] TPS / RTT / jitter combat safety gates
- [x] `killaura-a` multi-signal evidence score
- [x] extended staff combat telemetry in `/cdrac packet <player>`

### v0.3.1 Confidence & Observation Layer

- [x] `NORMAL` / `WATCH` / `ABNORMAL` / `SUSPICIOUS` / `HIGH RISK` statuses
- [x] confidence scoring above raw VL
- [x] per-check evidence weights
- [x] same-check repeat discounting
- [x] independent-check correlation bonus
- [x] confidence decay after quiet periods
- [x] default tracking-only `observe` mode
- [x] enforcement hard gate for kick and fly setback
- [x] minimum alert status and observation alert cooldown
- [x] `/cdrac inspect <player>`
- [x] timestamp + world + XYZ + yaw/pitch evidence snapshot
- [x] ping + RTT + jitter + TPS evidence snapshot
- [x] combat target + distance context
- [x] rich DiscordSRV observation reports
- [x] file logs enriched with incident context

### Remaining v0.3.x

- [ ] hitbox ray tracing
- [ ] critical-hit validation
- [ ] deeper line-of-sight / occlusion correlation
- [ ] recent evidence history / per-player evidence sessions
- [ ] production calibration and threshold tuning from legitimate Vephilim PvP

## v0.4.x - Movement Simulation

- movement prediction model
- friction / acceleration model
- jump validation
- velocity validation
- knockback checks
- elytra checks
- water movement checks
- vehicle exemptions
- environment sampling cache

## v0.5.x - World Interaction

- scaffold heuristics
- fast place
- fast break
- nuker heuristics
- impossible interaction distance
- inventory move
- chest/inventory action timing

## v0.6.x - Evidence & Staff Tools

- verbose staff mode
- player inspection GUI
- persistent/recent evidence snapshots
- recent flags history
- per-check statistics
- false-positive tuning metrics
- exportable diagnostics

## v0.7.x - Punishment Profiles

- configurable action ladders
- setback profiles
- kick/command actions
- temporary mitigation modes
- repeated-offender escalation
- per-check action overrides

Automatic permanent bans should remain opt-in and should only be enabled after sufficient production data demonstrates acceptable false-positive behavior.

## v0.8.x - Compatibility Layer

- deeper Floodgate/Geyser rules
- ViaVersion awareness
- common movement/plugin compatibility hooks
- custom item/skill exemptions API
- external velocity registration API

## v0.9.x - Hardening

- profiler and hot-path optimization
- memory leak audit
- concurrency audit
- malformed input hardening
- configuration migration
- diagnostics bundle

## v1.0.0 - Production Stable

Target criteria:

- stable Paper 1.21.11 support
- documented check behavior
- configurable and explainable detections
- no known critical false-positive class in default profile
- production-safe logging and alerting
- documented API for integrations and exemptions
