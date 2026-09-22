# NetherLink MC 端

Minecraft 服务端插件（Paper / Purpur / Folia），与 [AstrBot 侧的 NetherLink 插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)建立 WebSocket 长连接，实现服务器与 QQ 群的双向消息互通。

**必须先装好 AstrBot 侧插件**，本插件才能工作（它是客户端，主动连入 AstrBot）。

## 功能

| 方向 | 说明 |
|---|---|
| 游戏 → QQ | 聊天/进服/退服/死亡推送到群；唤醒词开头的话作为对话交给 AI |
| 成就上报 | 玩家获得成就时通知 AI（已过滤配方解锁与根成就） |
| QQ → 游戏 | 群消息渲染 `§` 染色码后广播到公屏 |
| 指令执行 | 以控制台身份执行 AI 下发的指令，并把服务器**真实输出**回传给 AI |

## 安装

1. **下载 Release**
   从 [Releases](https://github.com/MoeDawn/netherlink-server/releases) 下载 `netherlink-server-0.1.0.jar`

2. **放入服务端**
   把 jar 放进服务器的 `plugins/` 目录，重启服务器

3. **填配置**
   首次启动会生成 `plugins/NetherLink/config.yml`：

   ```yaml
   host: "AstrBot机器的IP"
   port: 8765
   token: "与 AstrBot 侧 auth_token 完全一致"
   server-name: "survival"    # 本服务器的标识：AstrBot 侧用它区分不同服务器
   wake-prefixes: "ai,助手"    # 唤醒词，须与 AstrBot 侧 mc_wake_prefixes 一致
   ```

   > `token` 必须与 AstrBot 插件配置里的 `auth_token` 一模一样，握手时校验，不匹配会被断开。
   >
   > **`server-name` 是身份**：AstrBot 侧的 `ws_ports` 与 `server_display_names` 里写的
   > `server-name` 指的就是它（若 `ws_ports` 只写了端口、没写名字，则直接采用这里上报的值）。
   > 而**显示名不在这里控制**——QQ 群前缀、`{server}` 占位符、AI 上下文里的服务器名统一由
   > AstrBot 侧的 `server_display_names` 决定（没配则显示 `MC`）。

4. **重启服务器**使配置生效

连接成功后，AstrBot 日志会显示握手成功，游戏内事件即开始推送到群。

## 配置项

| 配置项 | 说明 |
|---|---|
| `host` / `port` | AstrBot 侧监听的 WebSocket 地址与端口 |
| `token` | 握手鉴权密钥，两侧必须一致 |
| `server-name` | 本服务器的**身份**，握手时上报。AstrBot 侧的 `ws_ports` / `server_display_names` 用它区分与命名各台服务器（不影响显示名） |
| `wake-prefixes` | 游戏内唤醒词（逗号分隔），**须与 AstrBot 侧 `mc_wake_prefixes` 一致**否则唤不醒 AI |

## 可靠性

- 断线**自动重连**，指数退避（3 秒起，最多 60 秒）
- **15 秒心跳**保活，同时清理超时未回执的指令
- **持续接收**：WebSocket 的接收是按需拉取的，每收到一条消息立刻补下一次请求，
  长连接不会自行停止接收
- 重连与心跳都在异步线程执行，**不阻塞服务器主线程**

## 环境要求

- Minecraft 服务端：**Paper 26.3 / Purpur 26.3 / Folia**（见下表）
- **Java 25**
- 已装好并运行 [AstrBot 侧插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)

> **支持的服务端核心（Minecraft 26.3）**

| 核心 | 状态 | 说明 |
|---|---|---|
| **Paper** | ✅ 已验证 | 当前实机运行的就是它 |
| **Purpur** | ✅ 可运行 | Paper 的分支，API 与事件完全一致 |
| **Folia** | ⚠️ 已适配，未实机验证 | 调度器已全部换成 Paper/Folia 共用的那一套（`GlobalRegionScheduler` / `AsyncScheduler`），并声明了 `folia-supported: true`。⚠️ 但没有在真 Folia 上跑过 |
| Spigot | ❌ 不支持 | 依赖 `Bukkit.createCommandSender`（用于捕获指令输出），这是 **Paper 专有扩展**——Spigot 26.3 的 Bukkit 里没有它 |
| Fabric / NeoForge | ❌ 不支持 | 它们是模组加载器，不是 Bukkit 实现，得单独移植 |

> ⚠️ **版本限定**：只支持 **Minecraft 26.3**。降级/升级到别的 MC 版本需要重新编译、
> 并可能改动代码——本插件依赖两个 **Paper 专属**的接口：
>
> - `io.papermc.paper.event.player.AsyncChatEvent`（Paper 的异步聊天事件）
> - `Bukkit.createCommandSender`（Paper 对 Bukkit 的扩展，用来捕获指令输出）
>
> 后者的实现类 `FeedbackForwardingSender` 在 paper-server 侧。
> 换 MC 版本时这两处都得先确认。
>
> **Folia 说明**：`folia-supported: true` 只是声明，真正让它能跑的是「用对了调度器」。
> 本插件已改用 `GlobalRegionScheduler`（主线程操作）与 `AsyncScheduler`（网络 / 心跳），
> 这两个在普通 Paper 上行为一致，所以**同一份 jar 两种服务端都能用**。

## 从源码构建（可选）

需要 JDK 25 与 Gradle 9.x（低版本编译不了 Paper 26.3 API）：

```bash
cd paper-plugin
./build.cmd          # Windows
```

产物在 `build/libs/netherlink-server-0.1.0.jar`。

> 若项目路径含 `&` 等特殊字符导致 `./build.cmd` 解析失败，改用 `cmd //c ".\build.cmd"`。

## 目录结构

```text
paper-plugin/
├── src/main/java/dev/eyf/netherlink/
│   ├── NetherLinkPlugin.java    # 插件入口：事件监听、配置、指令执行
│   └── AstrBotWsClient.java     # WebSocket 客户端：连接、重连、心跳
├── src/main/resources/
│   └── paper-plugin.yml         # 插件元数据
├── build.gradle.kts
└── README.md
```
