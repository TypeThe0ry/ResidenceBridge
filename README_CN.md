# ResidenceBridge

English version: [README.md](README.md)

> 跨服领地桥接插件，让 [Residence](https://www.spigotmc.org/resources/residence.11480/) 在 Velocity / BungeeCord 多服网络中共享领地索引、跨服传送和全区限制。

[![Version](https://img.shields.io/badge/version-1.2.0-blue.svg)](https://github.com/TypeThe0ry0902/ResidenceBridge/releases)
[![Server Java](https://img.shields.io/badge/Server-Java%208%2B-orange.svg)](https://adoptium.net)
[![Velocity Java](https://img.shields.io/badge/Velocity-Java%2017%2B-orange.svg)](https://adoptium.net)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.16%2B-brightgreen.svg)](https://papermc.io)

## 仓库结构

本仓库从 1.2.0 起合并了子服端和代理端代码，发布时会同时产出两个 jar，两个都需要部署。

| 目录 | 产物 | 部署位置 | Java |
|------|------|----------|------|
| [Server](Server) | `ResidenceBridge-1.2.0.jar` | 每台 Bukkit/Paper/Folia 子服 | 8+ |
| [Velocity](Velocity) | `ResidenceBridge-Velocity-1.2.0.jar` | Velocity 代理端 | 17+ |

> 只安装子服端或只安装 Velocity 端都不能完整使用跨服功能。

## 功能

- 全服唯一领地名：`/res create`、`/res rename` 会检查 MySQL 全局索引，避免跨服重名。
- 跨服领地传送：`/res tp <领地>` 会自动切换到领地所在子服，并在目标服执行 Residence 传送。
- 跨服补全：`/res tp`、`/res remove`、`/res rename` 等领地名参数会补全全服索引里的领地。
- 全区领地列表：`/res list` 显示玩家在所有子服拥有的领地，管理员可用 `/res list <玩家名>` 查看他人全服领地。
- 全区数量限制：创建领地时按玩家在所有子服的领地总数限制，可按权限配置不同上限，兼容 LuckPerms 权限组。
- 传送等待时间：可按权限配置 `/res tp` 等待秒数，支持移动/受伤取消，管理员或指定权限可跳过等待。
- 跨服常用操作：`/res rename`、`/res give`、`/res remove`、`/res delete` 对外服领地会切到目标服后自动执行原指令。
- 自动同步：各子服定时把本服 Residence 领地快照写入 MySQL，适合已开服后直接安装初始化。
- PlaceholderAPI：支持 `%reslink_ressize%`、`%reslink_reslist_1%`、`%reslink_reslist_2%` 等变量。
- RGB 消息：游戏内提示支持 `&` 颜色和 `&#RRGGBB` RGB。
- Folia 兼容调度：玩家相关任务优先使用玩家调度器，减少原生 Residence 传送卡顿和线程问题。

## 环境要求

| 组件 | 要求 |
|------|------|
| 子服 | Paper / Spigot / Folia 1.16+ |
| Residence | 各子服安装，建议使用稳定新版 |
| Velocity | Velocity 3.x |
| 数据库 | MySQL 5.7+ 或 MariaDB 10.4+ |
| PlaceholderAPI | 可选，仅使用 PAPI 变量时需要 |

## 安装

1. 下载同一版本的两个 jar。
2. 将 `ResidenceBridge-1.2.0.jar` 放入每台子服的 `plugins/`。
3. 将 `ResidenceBridge-Velocity-1.2.0.jar` 放入 Velocity 的 `plugins/`。
4. 确认每台子服已安装 Residence。
5. 启动一次子服生成 `plugins/ResidenceBridge/config.yml`。
6. 给每台子服配置唯一的 `server-id`，并填写相同的 MySQL 信息。
7. 确认 `server-id` 与 Velocity 代理端 `velocity.toml` 里的服务器名一致，例如 Velocity 配的是 `s2 = "127.0.0.1:25567"`，子服就填 `server-id: "s2"`。
8. 确认所有子服 `velocity.channel` 保持默认值 `residencebridge:main`。当前 Velocity 端固定监听这个频道。
9. 重启 Velocity 和所有子服，或在子服执行 `/rb reload`。
10. 在每台子服控制台执行 `rb sync`，把已有 Residence 领地立即写入 MySQL。

## 配置摘要

完整默认配置在 [Server/src/main/resources/config.yml](Server/src/main/resources/config.yml)。常用配置如下：

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
  header: "&#66ccff你的全区领地列表 &7(&f%count%&7) &8- &7第 &f%page%&7/&f%max_page% &7页"
  other-header: "&#66ccff%target% 的全区领地列表 &7(&f%count%&7) &8- &7第 &f%page%&7/&f%max_page% &7页"
  line: "&7- &a%name% &8[&f%server%&8] &7%world%"
  empty: "&e你还没有任何领地。"
```

如果你从旧版本升级，旧的 `config.yml` 不会自动合并新节点，需要手动补充 `teleport.wait.enabled`、`teleport.wait.bypass-permission`、`limits`、`list.others-permission`、`list.other-header`、`remote-action-commands`、`placeholder` 和新增 `messages`。

## 配置步骤

### 1. 子服 server-id

每台子服的 `server-id` 必须唯一，并且必须能被 Velocity 找到。推荐直接使用 Velocity 的服务器名：

```toml
# velocity.toml
[servers]
s1 = "127.0.0.1:25566"
s2 = "127.0.0.1:25567"
```

对应子服配置：

```yaml
# s1/plugins/ResidenceBridge/config.yml
server-id: "s1"

# s2/plugins/ResidenceBridge/config.yml
server-id: "s2"
```

如果 `server-id` 和 Velocity 服务器名不同，数据库能同步，但跨服切换会失败。

### 2. MySQL

所有子服必须填写同一个数据库。插件启动时会自动创建三张表，不需要手动导入 SQL。

```yaml
mysql:
  host: "127.0.0.1"
  port: 3306
  database: "minecraft"
  username: "root"
  password: "password"
```

数据库账号需要有 `CREATE TABLE`、`ALTER TABLE`、`SELECT`、`INSERT`、`UPDATE`、`DELETE` 权限。

### 3. 代理端与消息频道

Velocity 端只需要安装 `ResidenceBridge-Velocity-1.2.0.jar`，当前没有单独配置文件。子服端保持：

```yaml
velocity:
  channel: "residencebridge:main"
  fallback-bungee-channel: true
```

纯 Velocity 网络也可以把 `fallback-bungee-channel` 改成 `false`；如果不确定，保持默认即可。

### 4. 已有领地首次同步

安装到已有服务器后，ResidenceBridge 会定时同步本服 Residence 领地。也可以手动执行：

```text
rb sync
```

如果使用玩家身份执行，则是：

```text
/rb sync
```

确认同步结果：

```sql
SELECT display_name, server_id, world_name, owner_name, status
FROM residence_bridge_index
ORDER BY server_id, display_name;
```

### 5. 诊断命令

如果出现同步数量为 0、传送失败或列表不对，先执行：

```text
rb debug
```

关键字段说明：

| 字段 | 含义 |
|------|------|
| `Residence instance` | 是否拿到 Residence 插件实例 |
| `Residence names` / `Residence values` | 是否通过 Residence API 读到内存领地 |
| `Residence file snapshots` | 是否通过 Residence 保存文件读到已有领地 |
| `Snapshots` | 最终会同步到 MySQL 的领地快照 |

只要 `Snapshots` 不是 0，执行 `rb sync` 后就应该写入 MySQL。

## 指令与权限

| 指令 | 权限 | 默认 | 说明 |
|------|------|------|------|
| `/rb reload` | `residencebridge.command.reload` | OP | 重载子服端配置 |
| `/rb sync` | `residencebridge.command.sync` | OP | 立即同步本服 Residence 领地到 MySQL |
| `/rb debug` | `residencebridge.command.debug` | OP | 输出 Residence 读取和同步诊断信息 |
| `/residencebridge reload` | `residencebridge.command.reload` | OP | 同上 |
| `/res list <玩家名>` | `residencebridge.list.others` | OP | 查看其他玩家的全服领地列表 |

功能权限由配置决定，适合直接用 LuckPerms 赋给组或玩家，例如 `lp group vip permission set reslink.count.vip true`。默认示例：

| 权限 | 默认用途 |
|------|----------|
| `ResLinkDefault` | 传送等待 3 秒 |
| `ResLinkDefaultCount` | 全区领地上限 3 个 |
| `reslink.tp.vip` | 传送等待 1 秒 |
| `reslink.count.vip` | 全区领地上限 10 个 |
| `residencebridge.list.others` | 查看其他玩家全服领地 |
| `residencebridge.teleport.bypass` | 传送无需等待 |
| `residencebridge.limit.bypass` | 不限制全区领地数量 |

## PlaceholderAPI

| 变量 | 说明 |
|------|------|
| `%reslink_ressize%` | 玩家全区领地数量 |
| `%reslink_reslist_1%` | 玩家第 1 个领地名 |
| `%reslink_reslist_2%` | 玩家第 2 个领地名 |

## 数据库表

| 表名 | 用途 |
|------|------|
| `residence_bridge_index` | 全局领地索引，包含领地名、所在服务器、世界、所有者和状态 |
| `residence_bridge_pending_tp` | 待消费的跨服传送请求 |
| `residence_bridge_pending_action` | 待消费的跨服指令动作 |

表结构会在插件启动时自动创建或补齐。

## 构建

服务端插件：

```bash
cd Server
./gradlew build
```

Velocity 插件：

```bash
cd Velocity
./gradlew build
```

构建产物：

```text
Server/build/libs/ResidenceBridge-1.2.0.jar
Velocity/build/libs/ResidenceBridge-Velocity-1.2.0.jar
```

GitHub Actions 会在推送 `main` 或 `v*` 标签时自动构建并上传两个 jar。
