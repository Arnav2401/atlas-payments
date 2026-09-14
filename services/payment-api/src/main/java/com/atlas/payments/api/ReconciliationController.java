package com.atlas.payments.api;

import com.atlas.payments.ledger.ReconciliationReport;
import com.atlas.payments.ledger.ReconciliationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ledger")
public class ReconciliationController {
    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<ReconciliationReport> reconcile() {
        ReconciliationReport report = reconciliation.reconcile();

        return report.balanced()
                ? ResponseEntity.ok(report)
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(report);
    }
}
