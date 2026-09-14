package com.atlas.payments.ledger;

import java.util.List;

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
        long imbalance = debits + credits;
        return new ReconciliationReport(
                postingCount, debits, credits, imbalance,
                unbalancedEntryIds,
                imbalance == 0 && unbalancedEntryIds.isEmpty());
    }
}
