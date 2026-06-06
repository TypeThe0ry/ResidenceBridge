# ResidenceBridge-Velocity

English version: [README.md](README.md)

这是 ResidenceBridge 合并仓库中的 Velocity 代理端子项目。完整项目说明请查看根目录 [../README_CN.md](../README_CN.md)。

## 作用

ResidenceBridge-Velocity 只负责代理端转发：接收子服端通过 `residencebridge:main` 发送的插件消息，解析目标服务器名，并把玩家连接到目标子服。

支持两种消息载荷：

- `connect|serverId`：1.2.0 起使用的新格式。
- `serverId`：兼容旧版本格式。

## 要求

- Java 17+
- Velocity 3.x
- 各子服安装并配置同版本 `ResidenceBridge`

## 构建

```bash
./gradlew build
```

产物：

```text
build/libs/ResidenceBridge-Velocity-1.2.0.jar
```

在合并仓库根目录推送 `main` 或 `v*` 标签时，GitHub Actions 会同时构建 [../Server](../Server) 和当前 Velocity 子项目。
