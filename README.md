# ResidenceBridge

中文: [README_CN.md](README_CN.md)

> Cross-server Residence bridge for sharing [Residence](https://www.spigotmc.org/resources/residence.11480/) data, teleport requests, and global limits across a Velocity / BungeeCord network.

[![Version](https://img.shields.io/badge/version-1.2.0-blue.svg)](https://github.com/TypeThe0ry0902/ResidenceBridge/releases)
[![Java](https://img.shields.io/badge/Server-Java%208%2B-orange.svg)](https://adoptium.net)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.16%2B-brightgreen.svg)](https://papermc.io)

## Repository Layout

Since 1.2.0, the sub-server plugin and Velocity proxy plugin live in the same repository. Releases build both jars, and both must be deployed.

| Directory | Artifact | Deploy To |
|-----------|----------|-----------|
| [Server](Server) | `ResidenceBridge-1.2.0.jar` | Every Bukkit/Paper/Folia sub-server |
| [Velocity](Velocity) | `ResidenceBridge-Velocity-1.2.0.jar` | Velocity proxy |

Installing only one side is not enough for cross-server features.

## Features

- Globally unique residence names for `/res create` and `/res rename`.
- Cross-server `/res tp <name>` with automatic server switching and final Residence teleport on the target server.
- Cross-server tab completion for global residence names in `/res tp`, `/res remove`, `/res rename`, and similar commands.
- Global `/res list` showing residences owned by the player on every sub-server, plus `/res list <player>` for admins.
- Global residence count limits, configurable by permission group and compatible with LuckPerms permission assignments.
- Permission-based teleport wait time, with movement and damage cancellation; admins or a configured permission can skip the wait.
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
7. Make each `server-id` match the server name in Velocity's `velocity.toml`. For example, if Velocity has `s2 = "127.0.0.1:25567"`, set `server-id: "s2"` on that sub-server.
8. Keep `velocity.channel` at the default `residencebridge:main`. The Velocity-side plugin listens on this channel.
9. Restart Velocity and all sub-servers, or run `/rb reload` on sub-servers.
10. Run `rb sync` from each sub-server console to immediately index existing Residence data.

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
  join-delay-ticks: 0
  wait:
    enabled: true
    default-seconds: 3
    bypass-permission: "residencebridge.teleport.bypass"
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

list:
  page-size: 8
  others-permission: "residencebridge.list.others"
  header: "&#66ccffYour global residences &7(&f%count%&7) &8- &7Page &f%page%&7/&f%max_page%"
  other-header: "&#66ccff%target%'s global residences &7(&f%count%&7) &8- &7Page &f%page%&7/&f%max_page%"
  line: "&7- &a%name% &8[&f%server%&8] &7%world%"
  empty: "&eNo residences found."
```

When upgrading from an older version, Bukkit will not merge new defaults into an existing `config.yml`. Add the new `teleport.wait.enabled`, `teleport.wait.bypass-permission`, `limits`, `list.others-permission`, `list.other-header`, `remote-action-commands`, `placeholder`, and extra `messages` nodes manually.

## Configuration Checklist

### 1. Sub-server IDs

Use the same names as Velocity servers:

```toml
# velocity.toml
[servers]
s1 = "127.0.0.1:25566"
s2 = "127.0.0.1:25567"
```

```yaml
# s1/plugins/ResidenceBridge/config.yml
server-id: "s1"

# s2/plugins/ResidenceBridge/config.yml
server-id: "s2"
```

If `server-id` does not match a Velocity server name, database sync can still work, but cross-server switching will fail.

### 2. MySQL

All sub-servers must use the same database. Tables are created automatically.

```yaml
mysql:
  host: "127.0.0.1"
  port: 3306
  database: "minecraft"
  username: "root"
  password: "password"
```

The database user needs `CREATE TABLE`, `ALTER TABLE`, `SELECT`, `INSERT`, `UPDATE`, and `DELETE` permissions.

### 3. Proxy Channel

The Velocity-side plugin currently has no separate config file. Keep this on the sub-server side:

```yaml
velocity:
  channel: "residencebridge:main"
  fallback-bungee-channel: true
```

For pure Velocity networks, `fallback-bungee-channel` may be set to `false`; keeping the default is fine when unsure.

### 4. First Sync and Debugging

After installing on an existing network, run this from every sub-server console:

```text
rb sync
```

Check what ResidenceBridge can read:

```text
rb debug
```

Important debug fields:

| Field | Meaning |
|-------|---------|
| `Residence instance` | Whether the Residence plugin instance is available |
| `Residence names` / `Residence values` | Whether Residence API data is readable |
| `Residence file snapshots` | Whether saved Residence files were found |
| `Snapshots` | Final data that will be synced to MySQL |

If `Snapshots` is not 0, `rb sync` should write data to MySQL.

## Commands and Permissions

| Command | Permission | Default | Description |
|---------|------------|---------|-------------|
| `/rb reload` | `residencebridge.command.reload` | OP | Reloads the sub-server config |
| `/rb sync` | `residencebridge.command.sync` | OP | Syncs local Residence data to MySQL immediately |
| `/rb debug` | `residencebridge.command.debug` | OP | Prints Residence read and sync diagnostics |
| `/residencebridge reload` | `residencebridge.command.reload` | OP | Alias |
| `/res list <player>` | `residencebridge.list.others` | OP | Lists another player's global residences |

Permission-driven behavior is configured in `config.yml` and works well with LuckPerms, for example `lp group vip permission set reslink.count.vip true`. Default examples:

| Permission | Purpose |
|------------|---------|
| `ResLinkDefault` | 3-second teleport wait |
| `ResLinkDefaultCount` | 3 global residences |
| `reslink.tp.vip` | 1-second teleport wait |
| `reslink.count.vip` | 10 global residences |
| `residencebridge.list.others` | List another player's global residences |
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
