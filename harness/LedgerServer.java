import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for the real com.ledger.settlement Spring Boot service, built
 * directly on com.sun.net.httpserver so it compiles/runs with plain
 * javac/java (this sandbox has no route to Maven Central, so the real
 * Spring Boot 4.1.1 + JPA + Postgres dependency graph can't be resolved
 * here). It mirrors, at the JVM level:
 *
 *   - the real PaymentEntity fields and SettlementService's long-arithmetic
 *     fee math (gross * feeRateBasisPoints / 10_000),
 *   - Tomcat's actual default request executor sizing (min-spare=10,
 *     max=200, accept-count=100) as the "current pooling",
 *   - the new FeeScheduleClient/FeeSchedule/FeeScheduleProvider classes
 *     this PR adds, including the planted <clinit> pinning defect and its
 *     fix.
 *
 * Usage: java LedgerServer.java <port> <pooled|virtual> <defect-on|defect-off>
 */
public class LedgerServer {

    // ---- mirrors com.ledger.settlement.domain.PaymentEntity (fields only) ----
    record PaymentEntity(String id, String merchantId, long amountMinor, String currency, Instant recordedAt) {}

    static final Map<String, PaymentEntity> STORE = new ConcurrentHashMap<>();
    static final AtomicLong SEQ = new AtomicLong();

    // ---- mirrors com.ledger.settlement.config.LedgerProperties: ledger.fee-rate: 0.031 ----
    static final long BASE_FEE_RATE_BASIS_POINTS = 310;

    // ---- NEW in this PR: the downstream fee-schedule lookup ----

    /** Mirrors the new FeeScheduleClient component. */
    static class FeeScheduleClient {
        // Simulates a blocking call to a downstream compliance/rates
        // service that returns per-merchant fee overrides (e.g. VIP
        // tiers). 120ms is a realistic synchronous downstream latency.
        static Map<String, Long> fetchOverridesBlocking() {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("MR-VIP-1", 150L); // VIP merchant gets 150bps instead of 310
        }
    }

    static volatile boolean DEFECT = false;

    /** DEFECTIVE version: mirrors the "before fix" FeeSchedule with the override table lazily loaded in <clinit>. */
    static class FeeSchedule {
        static final Map<String, Long> OVERRIDES;
        static {
            // Blocks inside the class initializer - first request that
            // touches FeeSchedule pays the downstream latency here,
            // pinning its carrier thread for the duration.
            OVERRIDES = FeeScheduleClient.fetchOverridesBlocking();
        }
        static Long overrideFor(String merchantId) {
            return OVERRIDES.get(merchantId);
        }
    }

    // FIXED version: eagerly loaded (mirrors a Spring bean constructed at context startup).
    static volatile Map<String, Long> eagerOverrides;

    static Long overrideFor(String merchantId) {
        if (DEFECT) {
            return FeeSchedule.overrideFor(merchantId); // triggers <clinit> on first hit
        } else {
            return eagerOverrides.get(merchantId);
        }
    }

    // ---- mirrors SettlementService.settlementFor(merchantId) ----
    static long settle(String merchantId) {
        Long override = overrideFor(merchantId);
        long bps = override != null ? override : BASE_FEE_RATE_BASIS_POINTS;
        long gross = STORE.values().stream()
                .filter(p -> p.merchantId().equals(merchantId))
                .mapToLong(PaymentEntity::amountMinor)
                .sum();
        long fee = gross * bps / 10_000;
        return gross - fee;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String mode = args.length > 1 ? args[1] : "pooled";       // pooled | virtual
        boolean defect = args.length > 2 && args[2].equals("defect-on");
        DEFECT = defect;

        for (int i = 0; i < 500; i++) {
            String merchant = "m-" + (i % 5);
            STORE.put("p-" + i, new PaymentEntity("p-" + i, merchant, 1000 + i, "NGN", Instant.now()));
        }
        STORE.put("p-vip", new PaymentEntity("p-vip", "MR-VIP-1", 500_000, "NGN", Instant.now()));

        if (!defect) {
            // FIX: load eagerly, off the request path - mirrors a Spring
            // singleton bean's constructor running at context startup.
            eagerOverrides = FeeScheduleClient.fetchOverridesBlocking();
        }

        // ---- Executor selection ----
        // "pooled" mirrors Tomcat's actual defaults for the embedded
        // connector: minSpareThreads=10 (core-equivalent), maxThreads=200,
        // acceptCount=100 (queue capacity).
        ExecutorService executor;
        String executorDescription;
        if (mode.equals("virtual")) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
            executorDescription = "Executors.newVirtualThreadPerTaskExecutor() (spring.threads.virtual.enabled=true)";
        } else {
            executor = new ThreadPoolExecutor(
                    10, 200, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100));
            executorDescription = "Tomcat defaults: minSpareThreads=10, maxThreads=200, acceptCount=100";
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);

        server.createContext("/payments/settlement", (HttpHandler) exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String merchantId = "m-0";
            if (query != null && query.startsWith("merchantId=")) {
                merchantId = query.substring("merchantId=".length());
            }
            long owed = settle(merchantId);
            byte[] body = ("{\"merchantId\":\"" + merchantId + "\",\"amountOwedMinor\":" + owed + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.createContext("/payments", (HttpHandler) exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String id = "PAY-" + SEQ.incrementAndGet();
            STORE.put(id, new PaymentEntity(id, "m-0", 10_00, "NGN", Instant.now()));
            byte[] body = ("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.start();
        System.out.println("LedgerServer up on port " + port
                + " | executor=" + executorDescription
                + " | defect=" + defect);
    }
}
