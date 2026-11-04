import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 极简的假 Yggdrasil 服务端，用于端到端测试。
 *
 * <p>用法: {@code java FakeYggdrasil [port] [undashedUuid] [playerName] [denyPathPrefixes] [forcedName]}</p>
 *
 * <ul>
 *   <li>请求路径以 {@code denyPathPrefixes}（逗号分隔）中任意一项开头 → 返回 204（模拟"查无此人"）</li>
 *   <li>其余 JSON 请求 → 返回 200 + 档案</li>
 *   <li>{@code forcedName} 非空时覆盖返回的 name（用来测试改名 / 名字冲突逻辑）</li>
 * </ul>
 *
 * <p>典型用途:</p>
 * <ul>
 *   <li>自定义 Yggdrasil: 路径 {@code /session/minecraft/hasJoined}</li>
 *   <li>OFFICIAL 服务: 把 sessionServer 指到 {@code http://127.0.0.1:port/official}，
 *       实际请求路径变成 {@code /official/session/minecraft/hasJoined}</li>
 * </ul>
 */
public final class FakeYggdrasil {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
        String uuid = args.length > 1 ? args[1] : "1111111122223333444455555555aaaa";
        String name = args.length > 2 ? args[2] : "TestPlayer";
        String denySpec = args.length > 3 ? args[3] : "";
        String forcedName = args.length > 4 ? args[4] : null;
        // PowerShell 的 Start-Process 不接受空字符串实参，调用方用 "-" 作为空占位符
        if ("-".equals(denySpec)) denySpec = "";
        if ("-".equals(forcedName)) forcedName = null;

        String[] denyPrefixes = denySpec.isEmpty() ? new String[0] : denySpec.split(",");
        String finalName = (forcedName == null || forcedName.isEmpty()) ? name : forcedName;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            System.out.println("[FakeYggdrasil] " + exchange.getRequestMethod() + " " + path
                    + (rawQuery == null ? "" : "?" + rawQuery));
            System.out.flush();

            for (String deny : denyPrefixes) {
                if (!deny.isEmpty() && path.startsWith(deny)) {
                    System.out.println("[FakeYggdrasil]   -> 204 (denied by prefix '" + deny + "')");
                    System.out.flush();
                    respond(exchange, 204, "");
                    return;
                }
            }
            respond(exchange, 200, "{\"id\":\"" + uuid + "\",\"name\":\"" + finalName
                    + "\",\"properties\":[]}");
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("[FakeYggdrasil] listening on 127.0.0.1:" + port
                + " -> " + finalName + " / " + uuid
                + (denyPrefixes.length == 0 ? "" : "  (deny: " + String.join(",", denyPrefixes) + ")"));
        System.out.flush();
        Thread.currentThread().join();
    }

    private static void respond(HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        }
        exchange.close();
    }
}
