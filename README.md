# NetherLink Paper 端

Minecraft(Paper) 服务器侧的 NetherLink 插件，负责与 AstrBot 建立 WebSocket 长连接，
实现服务器与 QQ 群的双向消息互通。

AstrBot 侧插件见：[astrbot_plugin_netherlink](https://github.com/MoeDawn/astrbot_plugin_netherlink)

## 架构

```
QQ群 ←→ NapCat(snowluma) ←→ AstrBot插件(WS服务端) ←WebSocket/JSON行→ 本插件(WS客户端)
```

本插件是 **WS 客户端**，主动连入 AstrBot；AstrBot 换机器只需改本插件的 `config.yml`。

## 功能

| 方向 | 说明 |
|---|---|
| MC → QQ | 聊天/进服/退服/死亡上报；唤醒词开头的聊天作为 `bot_chat` 上报（交由 AstrBot 的 LLM 处理） |
| 成就上报 | 玩家获得成就时上报 `advancement`（**已过滤配方解锁与根成就**，否则 AI 会被配方刷屏） |
| QQ → MC | `chat` / `bot_reply` 下行整行文本，用 `LegacyComponentSerializer` 渲染 `§` 染色码后广播到公屏 |
| 指令执行 | `command` 下行走 `dispatchCommand` 以控制台身份执行；Proxy 代理捕获 `sendMessage`，把**真实输出**回传给 AstrBot |

## 通信协议（JSON 行，每行一个对象）

**上行（MC → AstrBot）**

```jsonc
{"type": "hello", "token": "...", "server_name": "survival"}
{"type": "chat",  "player": "Steve", "text": "大家好"}
{"type": "join",  "player": "Steve"}
{"type": "leave", "player": "Steve"}
{"type": "death", "player": "Steve", "message": "Steve 掉出了世界"}
{"type": "bot_chat", "player": "Steve", "text": "ai 你好"}   // 唤醒词开头，走 LLM
{"type": "advancement", "player": "Steve",
 "advancement": "钻石！", "advancement_key": "story/mine_diamond"}
   // 获得成就（已过滤配方解锁与根成就），AstrBot 侧交 AI 处理好感与回复
{"type": "heartbeat"}
{"type": "command_result", "id": "uuid", "ok": true, "output": "..."}
```

**下行（AstrBot → MC）**——`line` 均为 AstrBot 侧渲染完成的整行文本，可含 `§` 染色码：

```jsonc
{"type": "chat",      "line": "⌜§a水群§f⌟ <§b张三§f> §5你好§f"}
{"type": "bot_reply", "line": "⌜§cai§f⌟ : §d你好呀§f"}
{"type": "command",   "id": "uuid", "cmd": "gamemode creative Steve"}
```

## 可靠性

- 断线**指数退避重连**（3s 起，上限 60s；连上后重置）
- **15 秒心跳**，同时清理超过 15 秒未回执的指令登记
- 心跳与重连均在异步线程执行，不阻塞服务器主线程

## 构建

**要求**：JDK 25 + Gradle 9.x（JDK 22 及以下编译不了 Paper 26.3 API）。

```bash
cd paper-plugin
./build.cmd        # Windows
```

产物：`build/libs/netherlink-paper-0.0.1.jar`

> 若路径含 `&` 等特殊字符，`./build.cmd` 可能解析失败，改用 `cmd //c ".\build.cmd"`。
> 本机已验证环境：JDK 25 (`C:\jdk25\jdk-25.0.4.1+1`)、Gradle 9.1.0 (`C:\gradle\gradle-9.1.0`)。
> Gradle 8.14 内置的 Kotlin 解析不了 JDK 25 的四段版本号，会崩。

## 安装

1. 把 jar 放入服务器 `plugins/`
2. 首次启动会生成 `plugins/NetherLink/config.yml`，编辑：

```yaml
host: "AstrBot机器IP"
port: 8765
token: "与AstrBot侧一致"
server-name: "mc"
wake-prefixes: "ai,助手"   # 游戏内唤醒词，须与 AstrBot 侧 mc_wake_prefixes 一致
```

3. 重启服务器

> `token` 必须与 AstrBot 插件配置里的 `auth_token` 完全相同，握手时校验，不匹配会被断开。

## 许可

见仓库根目录。
