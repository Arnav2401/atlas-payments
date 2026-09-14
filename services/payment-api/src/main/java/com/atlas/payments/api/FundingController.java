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
