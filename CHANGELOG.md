# 更新日志

> 本文件用于 GitHub Release 的说明。格式参考 AstrBot 侧插件的 `CHANGELOG.md`。

## v0.1.0

与 AstrBot 侧插件版本号对齐（两侧分发渠道不同，版本号各自独立存储，本轮起取值一致）。

### 新增

- **支持 Folia**：改用 Paper/Folia 共用的调度器（`GlobalRegionScheduler` /
  `AsyncScheduler`），并在 `paper-plugin.yml` 声明 `folia-supported: true`。
  官方文档明确这两套调度器在普通 Paper 上会被内部接管、行为一致，
  所以**同一份 jar 三种服务端通用**，无需分支判断。
  - ⚠️ 延迟/周期的单位从 **tick** 变成**时间单位**，按 50ms/tick 换算。
- **支持 Purpur**：它是 Paper 的分支，API 与事件完全一致。

### 修复

- **多台服务器在线时执行指令会误报「超时」**：服务器端侧不受影响，但这是
  指令链路里最关键的一环——详见 AstrBot 侧 v0.1.0 的说明。

### 变更

- 项目名从 `netherlink-paper` 规范成 `netherlink-server`（仓库同名）。
  本插件现在支持 Paper / Purpur / Folia，名字里的 paper 已不准确。
  ⚠️ 插件标识 `name: netherlink` **不变**，已装服的配置无需改动。

### 环境要求

- Minecraft **26.3**，服务端为 **Paper / Purpur / Folia**
- **Java 25**
- 需要配套的 AstrBot 侧插件 `astrbot_plugin_netherlink` **0.1.0**

### 不支持

- **Spigot**：缺少 `Bukkit.createCommandSender`（Paper 专有扩展，用于捕获指令输出），
  这不是调度器层面能解决的。
- **Fabric / NeoForge**：它们是模组加载器而非 Bukkit 实现，需要单独移植。

## v0.0.2

- 指令执行改用官方 `Bukkit.createCommandSender`，修复原生指令（give/tp/list/time…）
  执行时抛 `Cannot make ... a vanilla command listener` 的问题。
- `command_result` 的 `ok` 如实上报，执行异常不再被伪装成成功。

## v0.0.1

- 首个版本：WebSocket 长连、聊天/进退服/死亡/成就上报、QQ→MC 消息广播、以控制台身份执行指令。
