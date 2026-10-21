import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for the downstream fee-schedule service that Ledger calls over HTTP.
 * Each response takes LATENCY_MS, like a remote call. The delay lives here, in the
 * downstream, so Ledger blocks on a real network read.
 * Run: java perf/FeeScheduleStub.java [port] [latencyMs]
 */
public class FeeScheduleStub {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args.length > 0 ? args[0] : "9090");
        long latencyMs = Long.parseLong(args.length > 1 ? args[1] : "120");
        AtomicInteger requests = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/fees", exchange -> {
            int n = requests.incrementAndGet();
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "MR-VIP-1=150\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            System.out.println("served fee schedule, request #" + n);
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("FeeScheduleStub listening on :" + port + ", latency " + latencyMs + " ms");
    }
}