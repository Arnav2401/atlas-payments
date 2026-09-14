package com.atlas.payments.api;

import com.atlas.payments.ledger.FundingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * {@code POST /ops/funding} — puts money into a customer account.
 *
 * <p><b>An operations affordance, not a product feature — see
 * {@link FundingService}'s own javadoc.</b> Real money enters through a
 * settlement rail (an incoming wire, a card capture), each with its own
 * reconciliation against an external statement; this exists because M3's
 * available-funds check is otherwise untestable and undemonstrable without
 * SOME way to get money into the ledger at all, and none existed before this.
 *
 * <p><b>Gated behind {@code SUPERVISOR} — closing the gap M3 flagged and left
 * open on purpose, until the role existed to gate it with.</b> This endpoint
 * creates money from the bank's own position on request; it is the highest-
 * authority action in this API, one register above the "clear/escalate"
 * actions {@code PaymentDecisionController} gates the same way, and the
 * natural proof for the brief's "an analyst token cannot reach a supervisor
 * endpoint" — see {@code FundingControllerSecurityTest}.
 */
@RestController
@RequestMapping("/ops")
public class FundingController {

    private final FundingService fundingService;

    public FundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    public record FundingRequest(String accountNumber, BigDecimal amount, String currency) {}

    @PostMapping("/funding")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<Void> fund(@RequestBody FundingRequest request) {
        fundingService.fund(request.accountNumber(), request.amount(), Currency.getInstance(request.currency()));
        return ResponseEntity.ok().build();
    }
}
