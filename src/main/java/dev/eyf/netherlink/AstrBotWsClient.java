package dev.eyf.netherlink;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;

/**
 * WebSocket 客户端：作为客户端主动连入 AstrBot 插件的 WS 服务端。
 * 负责连接生命周期（握手鉴权、指数退避重连、心跳），
 * 收到的消息通过主线程调度器回调 NetherLinkPlugin 处理。
 */
public final class AstrBotWsClient implements WebSocket.Listener {

    private final NetherLinkPlugin plugin;
    private final URI uri;
    private final String token;
    private final HttpClient http;

    private volatile WebSocket socket;
    private volatile boolean shuttingDown = false;
    private int retryDelay = 3; // 秒，指数退避：3 -> 6 -> 12 -> 24 -> 48（上限）

    /** 指令 id -> 发出时间，用于超时兜底与防重复回执 */
    private final Map<String, Long> pendingCommands = new ConcurrentHashMap<>();

    public AstrBotWsClient(NetherLinkPlugin plugin, String host, int port, String token) {
        this.plugin = plugin;
        this.token = token;
        this.uri = URI.create("ws://" + host + ":" + port + "/ws");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isConnected() {
        WebSocket s = socket;
        return s != null && !s.isOutputClosed();
    }

    /** 发送一行 JSON 到 AstrBot；未连接时返回 false。 */
    public boolean send(String json) {
        WebSocket s = socket;
        if (s == null || s.isOutputClosed()) {
            return false;
        }
        s.sendText(json, true);
        return true;
    }

    /** 建立连接并开始后台重连循环（异步）。 */
    public void connect() {
        shuttingDown = false;
        attemptConnect();
    }

    private void attemptConnect() {
        if (shuttingDown) {
            return;
        }
        plugin.getLogger().info("正在连接 AstrBot: " + uri);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        plugin.getLogger().warning("连接 AstrBot 失败: " + err.getMessage());
                        scheduleReconnect();
                        return;
                    }
                    if (shuttingDown) {
                        // 卸载期间才完成的连接：绝不能存进字段——shutdown() 早就跑完了，
                        // 那个 socket 会永远没人关（send/isConnected 也都读不到它）。
                        // 就地关掉，让对端立刻知道这条连接不该存在。
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
                        return;
                    }
                    // 必须存下来：send()/isConnected()/shutdown() 读的都是这个字段，
                    // 漏赋值时它们全都静默失效（send 恒 false），MC→QQ 整条方向静默死掉。
                    socket = ws;
                    retryDelay = 3; // 连上后重置退避
                    // 握手：token 校验由 AstrBot 侧完成，失败会被对方关闭
                    ws.sendText("{\"type\":\"hello\",\"token\":\"" + token + "\",\"server_name\":\""
                            + plugin.serverName() + "\"}", true);
                    plugin.getLogger().info("已连接 AstrBot，握手已发送");
                    startHeartbeat();
                });
    }

    private void scheduleReconnect() {
        if (shuttingDown) {
            return;
        }
        long delay = retryDelay;
        retryDelay = Math.min(retryDelay * 2, 60);
        // 重连延迟：旧 API 的单位是 tick，新 API 是时间单位，故 秒 -> 毫秒。
        // Folia 上传统调度器不可用；新 API 在普通 Paper 上行为一致（见类注释）。
        Bukkit.getAsyncScheduler().runDelayed(
                plugin, t -> attemptConnect(), delay * 50L, TimeUnit.MILLISECONDS);
    }

    /** 每 15 秒异步发送心跳。 */
    private void startHeartbeat() {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> {
            if (!shuttingDown && isConnected()) {
                send("{\"type\":\"heartbeat\"}");
                checkCommandTimeouts();
            }
        }, 15 * 50L, 15 * 50L, TimeUnit.MILLISECONDS);
    }

    /** 清理超过 15 秒未回执的指令（AstrBot 侧已超时兜底，这里仅释放内存）。 */
    private void checkCommandTimeouts() {
        long now = System.currentTimeMillis();
        pendingCommands.entrySet().removeIf(e -> now - e.getValue() > 15_000);
    }

    public void shutdown() {
        shuttingDown = true;
        WebSocket s = socket;
        if (s != null) {
            s.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }

    // ------------------------------------------------------------------
    // WebSocket.Listener 回调（在 HttpClient 的线程上触发）
    // ------------------------------------------------------------------
    @Override
    public void onOpen(WebSocket ws) {
        // 建连后的第一次「拉取」。JDK 的默认实现就是 webSocket.request(1)，
        // 显式写出来是为了让「接收是按需拉取」这个契约在代码里可见。
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        String msg = data.toString();
        // 切回异步线程解析，避免阻塞 IO 线程
        Bukkit.getAsyncScheduler().runNow(plugin, t -> plugin.onWsMessage(msg));
        // 必须补回这一次 request(1)：WebSocket 的接收是「按需拉取」的——
        // 每次派发前预扣一次额度，回调里不再 request 就永久停止派发。
        // JDK 的默认实现正是 webSocket.request(1); return null;，
        // 覆写 onText 却不 request，等于读完第一条消息就把读侧关死：
        // 表现为握手成功、首条下行能收到，之后永久静默且不报任何错。
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        plugin.getLogger().warning("与 AstrBot 的连接关闭: " + statusCode + " " + reason);
        if (socket == ws) {
            socket = null;
        }
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        plugin.getLogger().warning("WS 错误: " + error.getMessage());
    }
}
