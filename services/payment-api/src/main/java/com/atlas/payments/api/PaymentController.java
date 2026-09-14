package com.atlas.payments.api;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.api.dto.PaymentSubmissionResponse;
import com.atlas.payments.domain.PaymentInstruction;
import com.atlas.payments.fraud.FraudAssessment;
import com.atlas.payments.fraud.FraudAssessmentRequest;
import com.atlas.payments.fraud.FraudClient;
import com.atlas.payments.ledger.InsufficientFundsException;
import com.atlas.payments.ledger.Money;
import com.atlas.payments.persistence.PaymentStore;
import com.atlas.payments.persistence.StoredPayment;
import com.atlas.payments.validation.PaymentValidator;
import com.atlas.payments.validation.ValidationOutcome;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final PaymentValidator validator;
    private final PaymentStore paymentStore;
    private final FraudClient fraudClient;
    private final PaymentMetrics metrics;
    private final Clock clock;

    public PaymentController(PaymentValidator validator, PaymentStore paymentStore,
                             FraudClient fraudClient, PaymentMetrics metrics, Clock clock) {
        this.validator = validator;
        this.paymentStore = paymentStore;
        this.fraudClient = fraudClient;
        this.metrics = metrics;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<PaymentSubmissionResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentInstructionRequest request) {
        MDC.put("endToEndId", request.endToEndId());
        try {
            return switch (validator.validate(request)) {
                case ValidationOutcome.Accepted accepted -> {
                    try {
                        StoredPayment stored = paymentStore.record(accepted.instruction(), idempotencyKey);
                        FraudAssessment assessment = assessFraud(accepted.instruction(), stored);
                        metrics.recordAccepted();
                        if (assessment != null) {
                            metrics.recordFraudAssessment(assessment.source().name(), assessment.flagged());
                        }
                        yield ResponseEntity.ok(PaymentSubmissionResponse.accepted(
                                accepted.instruction().endToEndId(), stored.paymentId(), assessment));
                    } catch (InsufficientFundsException insufficient) {
                        metrics.recordRejectedByLedger();
                        yield ResponseEntity.ok(PaymentSubmissionResponse.rejectedByLedger(
                                accepted.instruction().endToEndId(),
                                InsufficientFundsException.CODE,
                                "debtorAccount",
                                insufficient.getMessage()));
                    }
                }
                case ValidationOutcome.Rejected rejected -> {
                    metrics.recordRejectedByValidation();
                    yield ResponseEntity.ok(
                            PaymentSubmissionResponse.rejected(request.endToEndId(), rejected.reasons()));
                }
            };
        } finally {
            MDC.remove("endToEndId");
        }
    }

    private FraudAssessment assessFraud(PaymentInstruction instruction, StoredPayment stored) {
        if (stored.debtorBalanceBeforeMinor() == null) {
            return null;
        }

        var currency = instruction.instructedCurrency();
        var request = new FraudAssessmentRequest(
                instruction.endToEndId(),
                instruction.instructedAmount(),
                false,
                instruction.debtorAccount(),
                instruction.creditorAccount(),
                Money.fromMinorUnits(stored.debtorBalanceBeforeMinor(), currency),
                Money.fromMinorUnits(stored.creditorBalanceBeforeMinor(), currency),
                LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).getHour());

        return fraudClient.assess(request);
    }
}
