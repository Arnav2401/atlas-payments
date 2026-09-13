package com.atlas.payments.api;

import com.atlas.payments.ledger.ReconciliationReport;
import com.atlas.payments.ledger.ReconciliationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /ledger/reconciliation}.
 *
 * <p>Returns 200 when the ledger balances and <b>500 when it does not</b>. That
 * is deliberate: an unbalanced ledger is not a client error and not a normal
 * outcome to be reported politely — it means money has been created or
 * destroyed, and the endpoint should read as broken to every monitor watching
 * it. This is the one place in the service where a non-2xx is the point.
 *
 * <p>M5 will need this as a scraped health signal, not just a manual curl.
 */
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
