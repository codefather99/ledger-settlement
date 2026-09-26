package com.ledger.settlement.service;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Fetches per-merchant fee overrides from the downstream compliance/rates
 * provider (e.g. a discounted basis-points rate for a negotiated VIP tier).
 *
 * Intentionally synchronous and blocking, standing in for a real HTTP call
 * to that provider - replace {@link #fetchOverridesBlocking()}'s body with
 * the real client call when that provider exists. Keep it blocking; how the
 * caller schedules this blocking call is exactly what this lab is about,
 * not how the call itself is made.
 */
@Component
public class FeeScheduleClient {

    public Map<String, Long> fetchOverridesBlocking() {
        try {
            // Simulated downstream latency.
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while fetching fee schedule", e);
        }
        return Map.of("MR-VIP-1", 150L); // 150 basis points, in place of the standard 310
    }
}
