# Contributing

感谢你对 ResidenceBridge-Velocity 的关注。

## 开发环境

- JDK 17
- Git
- Gradle Wrapper

## 本地开发流程

1. Fork 并克隆仓库。
2. 创建功能分支 (建议命名: `feat/<name>` 或 `fix/<name>`)。
3. 编写或修改代码。
4. 执行构建校验:
   - Windows: `.\gradlew.bat clean build`
   - Linux/macOS: `./gradlew clean build`
5. 提交并发起 Pull Request。

## 代码规范

- 保持 Kotlin 代码简洁可读。
- 不提交 `build/`、IDE 临时文件、个人本地配置。
- 提交信息建议使用清晰语义, 例如:
  - `feat: add server fallback logic`
  - `fix: handle empty plugin message`
  - `docs: improve readme`

## Pull Request 要求

- 描述改动背景和目的。
- 说明主要修改点。
- 附上必要的验证步骤。

## 发布流程

- 仅在 `main` 分支发布。
- 使用语义化版本标签, 建议 `vX.Y.Z`。
- 推送标签后会自动触发 GitHub Release 并上传 jar 产物。

## Issue 建议

提交 Issue 时请尽量提供:

- Velocity 版本
- Java 版本
- 复现步骤
- 日志片段
