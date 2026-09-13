package com.atlas.payments.ledger;

import java.util.List;

/**
 * Proof that the ledger balances.
 *
 * <p>Amounts are in minor units and are only meaningful summed per currency —
 * they are reported across all currencies here because every entry is
 * single-currency and each one independently sums to zero, so the global total
 * is zero whatever the currency mix. That reasoning is why this is safe; it
 * would stop being safe the moment cross-currency entries are introduced.
 */
public record ReconciliationReport(
        long postingCount,
        long totalDebitsMinorUnits,
        long totalCreditsMinorUnits,
        long imbalanceMinorUnits,
        List<Long> unbalancedEntryIds,
        boolean balanced
) {
    public static ReconciliationReport of(long postingCount, long debits, long credits,
                                          List<Long> unbalancedEntryIds) {
        // Credits are stored negative, so a balanced ledger sums to exactly zero.
        long imbalance = debits + credits;
        return new ReconciliationReport(
                postingCount, debits, credits, imbalance,
                unbalancedEntryIds,
                imbalance == 0 && unbalancedEntryIds.isEmpty());
    }
}
