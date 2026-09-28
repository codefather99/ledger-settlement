import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives GET /payments/settlement at a fixed concurrency for a fixed
 * duration and reports throughput plus p50/p95/p99 latency, in place
 * of k6 (not installable in this sandbox's network-restricted
 * environment). Same load profile every run: 200 concurrent callers,
 * 60 seconds, so baseline/virtual/pinned/fixed numbers are comparable.
 *
 * Usage: java LoadGenerator.java <baseUrl> <concurrency> <durationSeconds>
 */
public class LoadGenerator {
    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        int durationSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 60;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        long endAt = System.nanoTime() + Duration.ofSeconds(durationSeconds).toNanos();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        // Bounded ring buffer of latencies (nanos), generous capacity.
        long[] latencies = new long[2_000_000];
        AtomicInteger latIdx = new AtomicInteger();

        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        Runnable worker = () -> {
            int i = 0;
            while (System.nanoTime() < endAt) {
                String merchant = "m-" + (i++ % 5);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/payments/settlement?merchantId=" + merchant))
                        .timeout(Duration.ofSeconds(5))
                        .GET().build();
                long start = System.nanoTime();
                try {
                    HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                    long elapsed = System.nanoTime() - start;
                    if (resp.statusCode() == 200) {
                        int idx = latIdx.getAndIncrement();
                        if (idx < latencies.length) latencies[idx] = elapsed;
                        completed.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
        };

        long wallStart = System.nanoTime();
        Thread[] threads = new Thread[concurrency];
        for (int t = 0; t < concurrency; t++) {
            threads[t] = Thread.ofPlatform().start(worker);
        }
        for (Thread t : threads) t.join();
        long wallElapsed = System.nanoTime() - wallStart;
        callers.shutdown();

        int n = Math.min(latIdx.get(), latencies.length);
        long[] sorted = Arrays.copyOf(latencies, n);
        Arrays.sort(sorted);

        double seconds = wallElapsed / 1_000_000_000.0;
        double throughput = completed.get() / seconds;

        System.out.println("=== Load test result ===");
        System.out.println("target           = " + baseUrl + "/payments/settlement");
        System.out.println("concurrency      = " + concurrency);
        System.out.println("duration_s       = " + String.format("%.1f", seconds));
        System.out.println("completed        = " + completed.get());
        System.out.println("errors           = " + errors.get());
        System.out.println("throughput_rps   = " + String.format("%.1f", throughput));
        System.out.println("p50_ms           = " + String.format("%.2f", pct(sorted, 0.50)));
        System.out.println("p95_ms           = " + String.format("%.2f", pct(sorted, 0.95)));
        System.out.println("p99_ms           = " + String.format("%.2f", pct(sorted, 0.99)));
    }

    static double pct(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) return Double.NaN;
        int idx = (int) Math.ceil(p * sortedNanos.length) - 1;
        idx = Math.max(0, Math.min(idx, sortedNanos.length - 1));
        return sortedNanos[idx] / 1_000_000.0;
    }
}
