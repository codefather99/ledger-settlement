package com.ledger.settlement.service;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * TEMPORARY LAB DEFECT VERSION.
 *
 * The fee schedule is intentionally initialized through a nested class
 * initializer. The blocking downstream call therefore occurs during
 * class initialization when FeeSchedule is first referenced.
 *
 * This is intentionally defective for the JFR pinning experiment.
 */
@Component
public class FeeScheduleProvider {

    private final FeeScheduleClient feeScheduleClient;

    public FeeScheduleProvider(FeeScheduleClient feeScheduleClient) {
        this.feeScheduleClient = feeScheduleClient;
    }

    public Long overrideBasisPointsFor(String merchantId) {
        return FeeSchedule.getOverrides(feeScheduleClient).get(merchantId);
    }

    static class FeeSchedule {

        private static Map<String, Long> OVERRIDES;

        static {
            // INTENTIONAL DEFECT:
            // Blocking operation inside class initialization.
            //
            // This will be reached from the request path the first time
            // FeeSchedule is referenced.
            //
            // The 120 ms Thread.sleep() occurs inside
            // FeeSchedule.<clinit>.
            OVERRIDES = null;
        }

        static Map<String, Long> getOverrides(FeeScheduleClient client) {
            if (OVERRIDES == null) {
                OVERRIDES = client.fetchOverridesBlocking();
            }
            return OVERRIDES;
        }
    }
}