package com.atlas.payments.api;

import com.atlas.payments.ledger.FundingService;
import org.springframework.http.ResponseEntity;
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
 * <p><b>Explicitly unauthenticated, and that is a known gap, not an
 * oversight.</b> As it stands this endpoint creates money from the bank's own
 * position on request from anyone who can reach it. M5's RBAC work must put
 * this behind the supervisor role before this system is anything but a demo;
 * the README says so plainly, and this class should not quietly become load
 * -bearing before that happens.
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
    public ResponseEntity<Void> fund(@RequestBody FundingRequest request) {
        fundingService.fund(request.accountNumber(), request.amount(), Currency.getInstance(request.currency()));
        return ResponseEntity.ok().build();
    }
}
