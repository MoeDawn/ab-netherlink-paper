package dev.eyf.netherlink;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * NetherLink MC 端（Paper 插件）。
 *
 * 职责：
 *   1. 维护与 AstrBot 的 WebSocket 连接（见 AstrBotWsClient）
 *   2. MC -> QQ：聊天/进服/退服/死亡事件上报；
 *      唤醒词开头的聊天作为 bot_chat 上报（由 AstrBot 侧 LLM 处理）
 *   3. QQ -> MC：收到 chat/bot_reply 下行消息（AstrBot 已按模板渲染好含
 *      § 染色码的整行文本），用 LegacyComponentSerializer 渲染后广播到公屏；
 *      收到 command 以控制台身份执行并回传输出
 *   4. 防循环由 AstrBot 侧通过 OneBot self_id 识别机器人自身消息，
 *      MC 侧不做任何文本匹配判断
 */
public final class NetherLinkPlugin extends JavaPlugin implements Listener {

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer PLAIN =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();

    private AstrBotWsClient wsClient;
    private final Gson gson = new Gson();
    private String serverName = "mc";

    // 游戏内机器人唤醒词（与 AstrBot 侧配置一致，逗号分隔）
    private final java.util.List<String> wakePrefixes = new java.util.ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        String host = getConfig().getString("host", "127.0.0.1");
        int port = getConfig().getInt("port", 8765);
        String token = getConfig().getString("token", "change-me");

        wsClient = new AstrBotWsClient(this, host, port, token);
        wsClient.connect();

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("NetherLink MC 端已启用，目标 AstrBot: " + host + ":" + port);
    }

    @Override
    public void onDisable() {
        if (wsClient != null) {
            wsClient.shutdown();
        }
        getLogger().info("NetherLink MC 端已卸载");
    }

    private void loadSettings() {
        serverName = getConfig().getString("server-name", "mc");
        wakePrefixes.clear();
        for (String p : getConfig().getString("wake-prefixes", "ai,助手").split(",")) {
            if (!p.isBlank()) {
                wakePrefixes.add(p.strip());
            }
        }
    }

    @Override
    public void saveDefaultConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            getDataFolder().mkdirs();
            try {
                Files.writeString(file.toPath(), """
                        # NetherLink MC 端配置
                        # AstrBot 插件监听的 WebSocket 地址
                        host: "127.0.0.1"
                        port: 8765
                        # 必须与 AstrBot 插件配置的 auth_token 一致
                        token: "change-me"
                        # 服务器名（显示用）
                        server-name: "mc"
                        # 游戏内机器人唤醒词（逗号分隔，与 AstrBot 侧 mc_wake_prefixes 一致）
                        wake-prefixes: "ai,助手"
                        """);
            } catch (Exception e) {
                getLogger().warning("写入默认配置失败: " + e.getMessage());
            }
        }
    }

    public String serverName() {
        return serverName;
    }

    // ------------------------------------------------------------------
    // MC -> QQ 事件上报（异步线程执行网络发送）
    // ------------------------------------------------------------------
    private void report(JsonObject payload) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> wsClient.send(payload.toString()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String plain = PLAIN.serialize(event.message());
        // 唤醒词开头 → 机器人对话（bot_chat），AstrBot 侧走 LLM
        for (String prefix : wakePrefixes) {
            if (plain.startsWith(prefix)) {
                JsonObject bot = new JsonObject();
                bot.addProperty("type", "bot_chat");
                bot.addProperty("player", event.getPlayer().getName());
                bot.addProperty("text", plain);
                report(bot);
                return;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("player", event.getPlayer().getName());
        o.addProperty("text", plain);
        report(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "join");
        o.addProperty("player", event.getPlayer().getName());
        report(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "leave");
        o.addProperty("player", event.getPlayer().getName());
        report(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "death");
        o.addProperty("player", event.getPlayer().getName());
        String deathMessage = event.deathMessage() == null ? ""
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(event.deathMessage());
        o.addProperty("message", deathMessage);
        report(o);
    }

    /**
     * 玩家获得成就 → 上报给 AstrBot，由那边组装提示词交给 AI 处理好感与回复。
     *
     * 只上报「有展示信息的」成就：配方解锁（minecraft:recipes/*）与根成就
     * （display 为 null）也会触发本事件，但那些不是玩家眼里的"获得成就"，
     * 报上去会让 AI 频繁无意义地加好感。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        var advancement = event.getAdvancement();
        var display = advancement.getDisplay();
        if (display == null) {
            return;  // 根成就：没有展示名，不是玩家感知的成就
        }
        String key = advancement.getKey().getKey();   // 如 "story/mine_diamond"
        if (key.startsWith("recipes/")) {
            return;  // 配方解锁，不算成就
        }
        net.kyori.adventure.text.Component titleComp = display.title();
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(titleComp);

        JsonObject o = new JsonObject();
        o.addProperty("type", "advancement");
        o.addProperty("player", event.getPlayer().getName());
        o.addProperty("advancement", title);
        o.addProperty("advancement_key", key);
        report(o);
    }

    // ------------------------------------------------------------------
    // QQ -> MC（来自 AstrBot 的下行消息，异步线程调用）
    // ------------------------------------------------------------------
    public void onWsMessage(String raw) {
        try {
            JsonObject data = gson.fromJson(raw, JsonObject.class);
            String type = data.has("type") ? data.get("type").getAsString() : "";
            switch (type) {
                case "chat", "bot_reply" -> handleLineDown(data);
                case "command" -> handleCommandDown(data);
                default -> {
                    // hello 应答/未知类型忽略
                }
            }
        } catch (Exception e) {
            getLogger().warning("处理 WS 消息失败: " + e.getMessage());
        }
    }

    /**
     * AstrBot 下行的 chat / bot_reply：line 字段已是模板渲染完成的整行文本，
     * 可含 § 染色码。渲染成 Adventure 组件后广播到公屏。
     * 防循环完全由 AstrBot 侧负责（OneBot self_id 识别机器人自身消息），
     * MC 侧不做文本匹配——避免误伤复读机器人消息的真人玩家。
     */
    private void handleLineDown(JsonObject data) {
        String line = data.has("line") ? data.get("line").getAsString() : "";
        if (line.isEmpty()) {
            return;
        }
        net.kyori.adventure.text.Component rendered = LEGACY.deserialize(line);
        Bukkit.getScheduler().runTask(this, () -> Bukkit.broadcast(rendered));
    }

    private void handleCommandDown(JsonObject data) {
        String id = data.has("id") ? data.get("id").getAsString() : UUID.randomUUID().toString();
        String rawCmd = data.has("cmd") ? data.get("cmd").getAsString() : "";
        final String cmd = rawCmd.startsWith("/") ? rawCmd.substring(1) : rawCmd;

        // 控制台身份执行：权限等同 OP，切主线程
        Bukkit.getScheduler().runTask(this, () -> {
            StringBuilder output = new StringBuilder();
            CommandSender console = capturingSender(Bukkit.getConsoleSender(), output);
            try {
                boolean dispatched = Bukkit.dispatchCommand(console, cmd);
                if (output.length() == 0) {
                    output.append(dispatched ? "（指令已执行，无返回输出）" : "指令执行失败（服务端返回 false）");
                }
                sendCommandResult(id, output.toString());
            } catch (Exception e) {
                sendCommandResult(id, "执行异常: " + e.getMessage());
            }
        });
    }

    /**
     * 包装控制台发送器：拦截所有 sendMessage* 调用收集输出文本，
     * 其余方法委托给真实控制台（保持 OP 权限、名称等语义）。
     * 部分 Paper 指令用 MiniMessage/Adventure 输出，反射兜底处理 component 参数。
     */
    private CommandSender capturingSender(CommandSender delegate, StringBuilder sink) {
        Class<?> iface;
        try {
            iface = Class.forName("io.papermc.paper.command.CommandSender");

        } catch (ClassNotFoundException e) {
            // 老 API 结构下退回 Bukkit CommandSender
            iface = CommandSender.class;
        }
        final Class<?> senderIface = iface;
        return (CommandSender) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{senderIface},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("sendMessage") || name.equals("sendRichMessage")
                            || name.equals("sendPlainMessage")) {
                        if (args != null) {
                            for (Object arg : args) {
                                sink.append(describe(arg)).append('\n');
                            }
                        }
                        return null;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException ite) {
                        throw ite.getCause() != null ? ite.getCause() : ite;
                    }
                });
    }

    /** 把输出参数转成可读文本：字符串直接用，Adventure Component 序列化，其余 toString。 */
    private String describe(Object arg) {
        if (arg instanceof String s) {
            return s;
        }
        try {
            // net.kyori.adventure.text.Component → 纯文本
            Class<?> component = Class.forName("net.kyori.adventure.text.Component");
            if (component.isInstance(arg)) {
                return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize((net.kyori.adventure.text.Component) arg);
            }
        } catch (ClassNotFoundException ignored) {
            // 无 Adventure 环境，走 toString
        }
        return String.valueOf(arg);
    }

    private void sendCommandResult(String id, String output) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "command_result");
        o.addProperty("id", id);
        o.addProperty("ok", true);
        o.addProperty("output", output);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> wsClient.send(o.toString()));
    }
}
