import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 极简的假 Yggdrasil 服务端，只实现 /session/minecraft/hasJoined。
 * <p>用法: java FakeYggdrasil [port] [undashedUuid] [playerName]</p>
 */
public final class FakeYggdrasil {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
        String uuid = args.length > 1 ? args[1] : "1111111122223333444455555555aaaa";
        String name = args.length > 2 ? args[2] : "TestPlayer";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/session/minecraft/hasJoined", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            System.out.println("[FakeYggdrasil] " + exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getPath() + "?" + rawQuery);
            System.out.flush();
            respond(exchange, 200, "{\"id\":\"" + uuid + "\",\"name\":\"" + name
                    + "\",\"properties\":[]}");
        });
        // 任何其它路径都返回 204，用来模拟“查无此人”
        server.createContext("/", exchange -> {
            System.out.println("[FakeYggdrasil] (no-session) " + exchange.getRequestMethod() + " "
                    + exchange.getRequestURI());
            System.out.flush();
            respond(exchange, 204, "");
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("[FakeYggdrasil] listening on 127.0.0.1:" + port
                + " -> " + name + " / " + uuid);
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
