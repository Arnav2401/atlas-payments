package com.atlas.payments.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciliation: does {@code sum(debits) = sum(credits)} across the whole ledger,
 * and does every individual entry balance?
 *
 * <p>Both are checked, and they are not the same question. The global sum can be
 * zero while two entries are individually wrong in equal and opposite directions
 * — the classic way a broken ledger looks healthy from a distance.
 */
@Service
public class ReconciliationService {

    private final PostingRepository postings;
    private final JournalEntryRepository journalEntries;

    public ReconciliationService(PostingRepository postings, JournalEntryRepository journalEntries) {
        this.postings = postings;
        this.journalEntries = journalEntries;
    }

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        return ReconciliationReport.of(
                postings.countPostings(),
                postings.totalDebitsMinorUnits(),
                postings.totalCreditsMinorUnits(),
                journalEntries.findUnbalancedEntryIds());
    }
}
