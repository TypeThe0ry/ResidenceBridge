# ResidenceBridge

中文: [README_CN.md](README_CN.md)

> Cross-server Residence bridge for sharing [Residence](https://www.spigotmc.org/resources/residence.11480/) data, teleport requests, and global limits across a Velocity / BungeeCord network.

[![Version](https://img.shields.io/badge/version-1.2.0-blue.svg)](https://github.com/TypeThe0ry0902/ResidenceBridge/releases)
[![Server Java](https://img.shields.io/badge/Server-Java%208%2B-orange.svg)](https://adoptium.net)
[![Velocity Java](https://img.shields.io/badge/Velocity-Java%2017%2B-orange.svg)](https://adoptium.net)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.16%2B-brightgreen.svg)](https://papermc.io)

## Repository Layout

Since 1.2.0, the sub-server plugin and Velocity proxy plugin live in the same repository. Releases build both jars, and both must be deployed.

| Directory | Artifact | Deploy To | Java |
|-----------|----------|-----------|------|
| [Server](Server) | `ResidenceBridge-1.2.0.jar` | Every Bukkit/Paper/Folia sub-server | 8+ |
| [Velocity](Velocity) | `ResidenceBridge-Velocity-1.2.0.jar` | Velocity proxy | 17+ |

Installing only one side is not enough for cross-server features.

## Features

- Globally unique residence names for `/res create` and `/res rename`.
- Cross-server `/res tp <name>` with automatic server switching and final Residence teleport on the target server.
- Global `/res list` showing residences owned by the player on every sub-server.
- Global residence count limits, configurable by permission group.
- Permission-based teleport wait time, with movement and damage cancellation.
- Cross-server common actions: `/res rename`, `/res give`, `/res remove`, and `/res delete` switch to the owning server and execute the original command there.
- Scheduled MySQL sync for all local Residence data, suitable for installing on an already running network.
- PlaceholderAPI placeholders: `%reslink_ressize%`, `%reslink_reslist_1%`, `%reslink_reslist_2%`, and so on.
- Custom messages with legacy `&` colors and `&#RRGGBB` RGB colors.
- Folia-aware scheduling for player tasks.

## Requirements

| Component | Requirement |
|-----------|-------------|
| Sub-server | Paper / Spigot / Folia 1.16+ |
| Residence | Installed on every sub-server |
| Velocity | Velocity 3.x |
| Database | MySQL 5.7+ or MariaDB 10.4+ |
| PlaceholderAPI | Optional, only required for placeholders |

## Installation

1. Download both jars from the same release.
2. Put `ResidenceBridge-1.2.0.jar` into `plugins/` on every sub-server.
3. Put `ResidenceBridge-Velocity-1.2.0.jar` into `plugins/` on Velocity.
4. Make sure Residence is installed on every sub-server.
5. Start each sub-server once to generate `plugins/ResidenceBridge/config.yml`.
6. Configure a unique `server-id` for every sub-server and the same MySQL connection on all of them.
7. Keep `velocity.channel` consistent everywhere. The default is `residencebridge:main`.
8. Restart Velocity and all sub-servers, or run `/rb reload` on sub-servers.

## Configuration

The full default config is in [Server/src/main/resources/config.yml](Server/src/main/resources/config.yml). Common options:

```yaml
server-id: "survival-1"

mysql:
  host: "127.0.0.1"
  port: 3306
  database: "minecraft"
  username: "root"
  password: "password"
  maximum-pool-size: 10

teleport:
  pending-expire-seconds: 30
  join-delay-ticks: 40
  wait:
    default-seconds: 3
    cancel-on-move: true
    cancel-on-damage: true
    groups:
      default:
        permission: "ResLinkDefault"
        seconds: 3
      vip:
        permission: "reslink.tp.vip"
        seconds: 1
      bypass:
        permission: "residencebridge.teleport.bypass"
        seconds: 0

limits:
  default-max-residences: 3
  bypass-permission: "residencebridge.limit.bypass"
  groups:
    default:
      permission: "ResLinkDefaultCount"
      max-residences: 3
    vip:
      permission: "reslink.count.vip"
      max-residences: 10
```

When upgrading from an older version, Bukkit will not merge new defaults into an existing `config.yml`. Add the new `teleport.wait`, `limits`, `list`, `remote-action-commands`, `placeholder`, and extra `messages` nodes manually.

## Commands and Permissions

| Command | Permission | Default | Description |
|---------|------------|---------|-------------|
| `/rb reload` | `residencebridge.command.reload` | OP | Reloads the sub-server config |
| `/residencebridge reload` | `residencebridge.command.reload` | OP | Alias |

Permission-driven behavior is configured in `config.yml`. Default examples:

| Permission | Purpose |
|------------|---------|
| `ResLinkDefault` | 3-second teleport wait |
| `ResLinkDefaultCount` | 3 global residences |
| `residencebridge.teleport.bypass` | No teleport wait |
| `residencebridge.limit.bypass` | Unlimited global residence count |

## PlaceholderAPI

| Placeholder | Description |
|-------------|-------------|
| `%reslink_ressize%` | Player's global residence count |
| `%reslink_reslist_1%` | Player's first residence name |
| `%reslink_reslist_2%` | Player's second residence name |

## Database Tables

| Table | Purpose |
|-------|---------|
| `residence_bridge_index` | Global residence index with name, server, world, owner, and status |
| `residence_bridge_pending_tp` | Pending cross-server teleport requests |
| `residence_bridge_pending_action` | Pending cross-server command actions |

Tables are created or migrated automatically when the plugin starts.

## Build

Server plugin:

```bash
cd Server
./gradlew build
```

Velocity plugin:

```bash
cd Velocity
./gradlew build
```

Artifacts:

```text
Server/build/libs/ResidenceBridge-1.2.0.jar
Velocity/build/libs/ResidenceBridge-Velocity-1.2.0.jar
```

GitHub Actions builds and uploads both jars on `main` pushes and `v*` tags.