# CdrAntiCheat Roadmap

The roadmap is intentionally incremental. Anti-cheat systems become unreliable when many aggressive detections are added before their data model, exemptions, and evidence pipeline are stable.

## v0.1.x - Foundation

- [x] Project bootstrap for Paper 1.21.11 / Java 21
- [ ] Central violation engine
- [ ] Violation decay and threshold handling
- [ ] File audit logger
- [ ] OP/staff fallback alerts
- [ ] DiscordSRV optional alert bridge
- [ ] Floodgate-aware Bedrock compatibility
- [ ] Administrative command
- [ ] `speed-a`
- [ ] `fly-a`
- [ ] `bad-movement-a`
- [ ] `reach-a`
- [ ] `autoclicker-a`

## v0.2.x - Packet & Timing Engine

- PacketEvents integration
- packet movement timeline
- timer detection
- packet-order validation
- duplicate/invalid packet checks
- transaction/latency samples
- keepalive sanity checks
- server lag compensation

## v0.3.x - Combat Engine

- reach correlation improvements
- rotation/aim analysis
- hitbox ray tracing
- multi-target / improbable target switching heuristics
- attack timing analysis
- velocity-aware combat exemptions
- critical-hit validation

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
- player inspection GUI or command view
- evidence snapshots
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
