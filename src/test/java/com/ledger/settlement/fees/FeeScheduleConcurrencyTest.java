package com.ledger.settlement.fees;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeeScheduleConcurrencyTest {

    private static final Map<String, Long> TABLE = Map.of("MR-VIP-1", 150L, "MR-VIP-2", 90L);

    @Test
    @DisplayName("64 virtual threads released at once all see the same complete table, loaded exactly once")
    void concurrentFirstUseSeesCompleteTableLoadedOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        FeeSchedule schedule = new FeeSchedule(() -> {
            loads.incrementAndGet();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TABLE;
        });

        int threads = 64;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Optional<Long>>>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return List.of(
                            schedule.overrideBasisPoints("MR-VIP-1"),
                            schedule.overrideBasisPoints("MR-VIP-2"),
                            schedule.overrideBasisPoints("MR-4471"));
                }));
            }
            ready.await();
            go.countDown();
            for (Future<List<Optional<Long>>> result : results) {
                assertThat(result.get()).containsExactly(Optional.of(150L), Optional.of(90L), Optional.empty());
            }
        }
        assertThat(loads.get()).isEqualTo(1);
    }
}