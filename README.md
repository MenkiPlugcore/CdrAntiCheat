# CdrAntiCheat

CdrAntiCheat is a modular anti-cheat project for Paper servers by CADERA.

> Current development line: **v0.1.x Foundation**

## Target

- Paper 1.21.11
- Java 21
- Standalone Paper server architecture
- Java clients and Geyser/Floodgate-aware compatibility
- Optional DiscordSRV alert delivery

## Design goals

CdrAntiCheat is designed around low false-positive detection, evidence-based violations, modular checks, configurable punishments, and auditable staff alerts.

The project deliberately separates detection from punishment. A single suspicious event should not immediately ban a player. Checks accumulate violation levels, decay over time, and may trigger alerts, setbacks, kicks, or configured commands only after thresholds are met.

## v0.1.0 scope

The first foundation release introduces:

- central violation engine with decay
- per-player violation tracking
- local file audit log
- staff/OP fallback alerts
- optional DiscordSRV alerts
- Floodgate-aware Bedrock detection
- Movement checks
  - `speed-a`
  - `fly-a`
  - `bad-movement-a`
- Combat check
  - `reach-a`
- Player input check
  - `autoclicker-a`
- configurable check thresholds and exclusions
- `/cdrac` administrative command

This release is a foundation, not a claim of feature parity with mature commercial anti-cheats. Packet-level checks, rotation analysis, combat correlation, simulation, inventory checks, scaffold detection, velocity validation, timer detection, and advanced client heuristics are planned incrementally.

## Commands

- `/cdrac status`
- `/cdrac reload`
- `/cdrac alerts`
- `/cdrac violations <player>`

Permission root: `cdranticheat.admin`

Staff alert permission: `cdranticheat.alerts`

Bypass permission: `cdranticheat.bypass`

## Build

```bash
mvn clean package
```

The resulting jar is written to `target/CdrAntiCheat-<version>.jar`.

## Optional integrations

### DiscordSRV

If DiscordSRV is installed and enabled in `config.yml`, CdrAntiCheat can send violation alerts to a configured Discord channel ID. If no channel ID is configured, it attempts to use DiscordSRV's main text channel.

### Floodgate

If Floodgate is present, CdrAntiCheat can identify Bedrock players and apply compatibility rules to checks that are unsafe to evaluate identically across Java and Bedrock.

## Roadmap

See [ROADMAP.md](ROADMAP.md).

## License

CdrAntiCheat is source-available proprietary software under the **MENKIESTES SOFTWARE LICENSE v1.0**. See [LICENSE](LICENSE).
