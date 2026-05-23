# ResidenceBridge

中文: [README_CN.md](README_CN.md)

> Cross-server residence bridge plugin for sharing [Residence](https://www.spigotmc.org/resources/residence.11480/) data seamlessly across a Velocity / BungeeCord network

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/TypeThe0ry0902/ResidenceBridge/releases)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://adoptium.net)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.16%2B-brightgreen.svg)](https://papermc.io)

> **This plugin (sub-server side) must be used together with the proxy plugin: [ResidenceBridge-Velocity](https://github.com/TypeThe0ry0902/ResidenceBridge-Velocity). Cross-server features will not work correctly without it.**

## Overview

ResidenceBridge is a Bukkit/Paper plugin designed for Minecraft server networks that run multiple sub-servers behind **Velocity / BungeeCord**.

<img width="881" height="955" alt="image" src="https://github.com/user-attachments/assets/42127086-6d5e-419d-bdde-388a1b785ecf" />
<img width="426" height="240" alt="p2" src="https://github.com/user-attachments/assets/53f6ef61-0498-41da-9939-7a4ecfa35a9e" />
<img width="426" height="240" alt="p1" src="https://github.com/user-attachments/assets/1c9d2e6f-5ddb-4600-abd2-cedfa57e0f17" />

The solution has two parts and **both must be deployed**:

| Component | Deploy To |
|------|---------|
| **ResidenceBridge** (this repository) | Every Bukkit/Paper sub-server |
| **[ResidenceBridge-Velocity](https://github.com/TypeThe0ry0902/ResidenceBridge-Velocity)** | Velocity proxy |

Both parts work together through a shared **MySQL database** to provide:

- **Globally unique residence names** so duplicated names are rejected across the entire network
- **Cross-server residence teleportation** so `/res tp <name>` can move a player to the correct server and teleport them automatically
- **Real-time data sync** so each sub-server pushes its residence snapshot to MySQL on a configurable interval

---

## Features

| Feature | Description |
|------|------|
| Global unique residence names | Checks the whole network before creating or renaming a residence |
| Cross-server residence teleport | `/res tp` can target residences on any connected sub-server |
| Scheduled data synchronization | Periodically writes local residence snapshots to MySQL and removes deleted entries |
| Rename conflict detection | Prevents renaming a residence to a name already used elsewhere |
| Automatic deletion sync | Removes deleted residences from the global index automatically |
| Velocity and BungeeCord support | Supports both Velocity plugin messaging and BungeeCord-compatible channel messages |

---

## Requirements

| Component | Deploy To | Version Requirement |
|---------|---------|----------|
| Minecraft server | Each sub-server | Paper / Spigot **1.16+** (recommended: 1.20.x) |
| [Residence](https://www.spigotmc.org/resources/residence.11480/) | Each sub-server | Latest stable release |
| **ResidenceBridge** | Each sub-server | Same version as this repository release |
| **[ResidenceBridge-Velocity](https://github.com/TypeThe0ry0902/ResidenceBridge-Velocity)** | **Velocity proxy** | Matching version for this plugin |
| Database | Separate server | MySQL **5.7+** or MariaDB **10.4+** |
| Java | — | **8+** |

---

## Installation

### Step 1: Install the proxy plugin (required)

1. Download the latest jar from [ResidenceBridge-Velocity](https://github.com/TypeThe0ry0902/ResidenceBridge-Velocity).
2. Put it into the `plugins/` directory of your Velocity proxy.
3. Start Velocity and configure the MySQL connection and channel name according to that repository.

### Step 2: Install the plugin on each sub-server

4. Download the latest `ResidenceBridge-x.x.x.jar` and place it in the `plugins/` directory of every sub-server.
5. Make sure **Residence** is already installed on every sub-server.
6. Start each sub-server once so the plugin can generate `plugins/ResidenceBridge/config.yml`.
7. Edit `config.yml`, fill in the **same** MySQL settings used by the Velocity plugin, and assign a unique `server-id` for each sub-server.
8. Make sure `velocity.channel` is exactly the same as the channel configured on the proxy side.
9. Restart the server, or run `/rb reload` to reload the configuration.

---

## Configuration

Path: `plugins/ResidenceBridge/config.yml`

```yaml
# Unique identifier of the current sub-server.
# Every sub-server must use a different value.
server-id: "survival-1"

mysql:
  host: "127.0.0.1"
  port: 3306
  database: "minecraft"
  username: "root"
  password: "password"
  # Maximum number of connections in the HikariCP pool
  maximum-pool-size: 10

sync:
  # Delay before the first sync after server startup (20 ticks = 1 second)
  initial-delay-ticks: 40
  # Periodic sync interval in seconds
  interval-seconds: 60
  # Whether to print a log message after each successful sync
  log-success: false

teleport:
  # Lifetime of a pending cross-server teleport request in seconds
  pending-expire-seconds: 30
  # Delay before teleporting after the player joins the target server
  join-delay-ticks: 40

velocity:
  # Velocity plugin messaging channel name
  channel: "residencebridge:main"
  # Enable BungeeCord-compatible plugin messages when using BungeeCord
  fallback-bungee-channel: true

messages:
  duplicate: "&cA residence with this name already exists globally: &f%name%"
  not-found: "&cResidence not found: &f%name%"
  switching: "&aConnecting to the server that owns this residence: &f%server%"
  local-teleport-failed: "&cFailed to teleport to the local residence. Please contact an administrator."
  connect-request-failed: "&cFailed to create a cross-server teleport request. Please try again later."
```

---

## Commands and Permissions

| Command | Permission | Default | Description |
|------|---------|------|------|
| `/rb reload` | `residencebridge.command.reload` | OP | Reloads the plugin configuration without restarting |
| `/residencebridge reload` | `residencebridge.command.reload` | OP | Same as above (alias) |

All permission nodes default to OP only. Use a permission plugin such as LuckPerms if you want to grant access to specific groups.

---

## How It Works

### Residence creation flow

```text
Player runs /res create <name>
        |
        v
BridgePlugin intercepts the command
        |
        +-- tries to reserve the name in the MySQL global index
        |       |
        |       +-- reservation fails (name already exists) -> notify player and cancel
        |       |
        |       +-- reservation succeeds -> allow the original command
        |                                     |
        |                            Residence creates the residence
        |                                     |
        +------------------------- after success, write the full snapshot
```

### Cross-server teleport flow

```text
Player runs /res tp <name>
        |
        v
Query the MySQL global index
        |
        +-- local residence -> teleport directly
        |
        +-- remote residence -> write a pending_tp record
                              |
                    sub-server sends a plugin message to Velocity
                              |
                 ResidenceBridge-Velocity receives the message
                    and moves the player to the target server
                              |
                when the player joins, pending_tp is loaded
                              |
                     local server performs the final teleport
```

---

## Database Tables

| Table | Purpose |
|------|------|
| `residence_bridge_index` | Global residence index written by periodic sync |
| `residence_bridge_pending_tp` | One-time cross-server teleport tasks waiting to be consumed |

The plugin creates the required tables automatically on first startup.

---

## Build

**Release build for server usage (without bundling the TabooLib runtime):**

```bash
./gradlew build
```

Artifact path: `build/libs/ResidenceBridge-x.x.x.jar`

**Development API build (for developers only, includes TabooLib and removes logic code):**

```bash
./gradlew taboolibBuildApi -PDeleteCode
```

`-PDeleteCode` removes runtime logic code to reduce the artifact size significantly.
