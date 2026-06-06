# ResidenceBridge-Velocity

ResidenceBridge-Velocity 是一个运行在 Velocity 代理端的桥接插件。

English version: [README.md](README.md)

它本身不产生业务事件, 主要负责接收 Paper 端发送的插件消息并将玩家转发到目标子服。

## 重要说明

仅安装本插件无法单独工作。

必须在 Paper 端同时安装 ResidenceBridge, 否则不会有消息发送到 Velocity, 转服逻辑也不会触发。

Paper 端项目地址:

https://github.com/TypeThe0ry/ResidenceBridge

## 特性

- 轻量化: 无独立运行时配置文件, 开箱即用。
- 代理侧转发: 在 Velocity 侧解析目标服并发起连接。
- CI 就绪: 仓库自带 GitHub Actions 自动构建流程。

## 环境要求

- Java 17
- Velocity 3.x
- Paper 端已安装 ResidenceBridge
- Gradle Wrapper (仓库已内置)

## 快速开始

1. 构建插件。
2. 将 ResidenceBridge-Velocity 放入 Velocity 的 plugins 目录。
3. 在你的 Paper 子服安装 ResidenceBridge。
4. 启动 Velocity 与 Paper。
5. 确认 Velocity 控制台出现 ResidenceBridge-Velocity enabled.

## 构建

### 发行版本 (可运行)

Linux/macOS:

```bash
./gradlew clean build
```

Windows:

```powershell
.\gradlew.bat clean build
```

默认产物:

```text
build/libs/ResidenceBridge-Velocity-1.0.0.jar
```

### 开发版本 (不可运行)

```bash
./gradlew taboolibBuildApi -PDeleteCode
```

`-PDeleteCode` 用于移除逻辑代码以缩小体积, 该产物仅用于开发依赖场景。

## 部署与运行

1. Velocity 端:
	- 将本仓库构建出的 jar 放入 Velocity 根目录 plugins 文件夹。
2. Paper 端:
	- 安装 ResidenceBridge: https://github.com/TypeThe0ry/ResidenceBridge
3. 启动顺序:
	- 先启动 Velocity, 再启动各 Paper 子服。
4. 验证:
	- Velocity 日志出现 ResidenceBridge-Velocity enabled.
	- 触发 Paper 端逻辑后, 玩家可被转发到指定服务器。

## 设置说明

当前版本不提供独立配置文件, 行为由代码固定定义:

- 插件消息频道: `residencebridge:main`
- 消息载荷: 目标子服名 (例如 `lobby`)
- 目标服解析: 基于 Velocity 已注册服务器列表

若目标服不存在, 会记录 `Target server not found` 警告。

如果没有安装 Paper 端 ResidenceBridge, 此频道不会有有效消息输入。

## 项目结构

```text
.github/workflows/      GitHub Actions CI
src/main/kotlin/        插件源码
gradle/wrapper/         Gradle Wrapper
build.gradle.kts        构建脚本
```

## 开发与协作

- 主分支: `main`
- CI: Push/PR 自动构建
- 提交前建议执行: `./gradlew clean build`

## 自动发布

仓库已支持按标签自动发布 GitHub Release 并上传插件 jar。

发布步骤:

1. 确保 `main` 分支代码已就绪并已推送。
2. 创建并推送版本标签 (建议格式 `vX.Y.Z`)。

```bash
git tag v1.0.1
git push origin v1.0.1
```

3. GitHub Actions 会自动:
   - 执行构建
   - 创建 Release
   - 上传 `build/libs` 生成的 jar 文件

详情可见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

本项目使用 MIT License, 详见 [LICENSE](LICENSE)。
