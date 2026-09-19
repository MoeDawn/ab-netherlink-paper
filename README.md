# NetherLink Paper 端

Minecraft(Paper) 服务端插件，与 [AstrBot 侧的 NetherLink 插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)建立 WebSocket 长连接，实现服务器与 QQ 群的双向消息互通。

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
   从 [Releases](https://github.com/MoeDawn/ab-netherlink-paper/releases) 下载 `netherlink-paper-0.0.2.jar`

2. **放入服务端**
   把 jar 放进服务器的 `plugins/` 目录，重启服务器

3. **填配置**
   首次启动会生成 `plugins/NetherLink/config.yml`：

   ```yaml
   host: "AstrBot机器的IP"
   port: 8765
   token: "与 AstrBot 侧 auth_token 完全一致"
   server-name: "mc"          # 本服务器的标识，仅用于握手
   wake-prefixes: "ai,助手"    # 唤醒词，须与 AstrBot 侧 mc_wake_prefixes 一致
   ```

   > `token` 必须与 AstrBot 插件配置里的 `auth_token` 一模一样，握手时校验，不匹配会被断开。
   > 服务器**显示名**不在这里控制——QQ 群前缀、`{server}` 占位符、AI 上下文里的服务器名统一由 AstrBot 侧 `mc_server_name` 决定。

4. **重启服务器**使配置生效

连接成功后，AstrBot 日志会显示握手成功，游戏内事件即开始推送到群。

## 配置项

| 配置项 | 说明 |
|---|---|
| `host` / `port` | AstrBot 侧监听的 WebSocket 地址与端口 |
| `token` | 握手鉴权密钥，两侧必须一致 |
| `server-name` | 本服务器的标识，握手时上报（不影响显示名） |
| `wake-prefixes` | 游戏内唤醒词（逗号分隔），**须与 AstrBot 侧 `mc_wake_prefixes` 一致**否则唤不醒 AI |

## 可靠性

- 断线**自动重连**，指数退避（3 秒起，最多 60 秒）
- **15 秒心跳**保活，同时清理超时未回执的指令
- **持续接收**：WebSocket 的接收是按需拉取的，每收到一条消息立刻补下一次请求，
  长连接不会自行停止接收
- 重连与心跳都在异步线程执行，**不阻塞服务器主线程**

## 环境要求

- Minecraft 服务端 **Paper 26.3**（或兼容的 fork）
- **Java 25**
- 已装好并运行 [AstrBot 侧插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)

## 从源码构建（可选）

需要 JDK 25 与 Gradle 9.x（低版本编译不了 Paper 26.3 API）：

```bash
cd paper-plugin
./build.cmd          # Windows
```

产物在 `build/libs/netherlink-paper-0.0.2.jar`。

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
