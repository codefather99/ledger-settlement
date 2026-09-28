package com.ledger.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Proves FeeScheduleProvider's eager, constructor-time initialization is
 * safe under concurrent first use: many virtual threads calling
 * overrideBasisPointsFor(...) at once, immediately after construction,
 * must all see the fully-initialized map. There is no lazy path left to
 * race on, unlike the class-initializer version this replaces.
 */
class FeeScheduleProviderConcurrencyTest {

    @Test
    void concurrentFirstReadsAllSeeFullyInitializedOverrides() throws InterruptedException {
        FeeScheduleClient stubClient = new FeeScheduleClient() {
            @Override
            public Map<String, Long> fetchOverridesBlocking() {
                return Map.of("MR-VIP-1", 150L);
            }
        };
        FeeScheduleProvider provider = new FeeScheduleProvider(stubClient);

        int threads = 200;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger mismatches = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            boolean vip = i % 2 == 0;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Long override = provider.overrideBasisPointsFor(vip ? "MR-VIP-1" : "MR-OTHER");
                boolean correct = vip ? Long.valueOf(150L).equals(override) : override == null;
                if (!correct) {
                    mismatches.incrementAndGet();
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(mismatches.get()).isZero();
    }
}
