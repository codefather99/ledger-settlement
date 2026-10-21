package com.ledger.settlement.fees;

import java.util.Map;
import java.util.Optional;

/**
 * Per-merchant fee overrides in basis points.
 *
 * DEFECT (planted for lab SJV-L1): the table is fetched from the downstream service inside
 * the class initializer. The first request that touches this class blocks in the static
 * block while the downstream call runs, and any other request that arrives meanwhile waits
 * for that initialization to finish.
 */
public final class FeeSchedule {

    private static final Map<String, Long> OVERRIDES;

    static {
        OVERRIDES = FeeScheduleClient.fetchOverrides();
    }

    private FeeSchedule() {
    }

    public static Optional<Long> overrideBasisPoints(String merchantId) {
        return Optional.ofNullable(OVERRIDES.get(merchantId));
    }
}