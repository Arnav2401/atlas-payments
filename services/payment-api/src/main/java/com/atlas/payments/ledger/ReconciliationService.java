package com.atlas.payments.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
