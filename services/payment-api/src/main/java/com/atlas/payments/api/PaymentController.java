package com.atlas.payments.api;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.api.dto.PaymentSubmissionResponse;
import com.atlas.payments.persistence.PaymentStore;
import com.atlas.payments.persistence.StoredPayment;
import com.atlas.payments.validation.PaymentValidator;
import com.atlas.payments.validation.ValidationOutcome;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP boundary. Spring web types stop here.
 *
 * <h2>DECISION 1 — rejections are returned on 200, not 4xx</h2>
 *
 * <p>The dividing line is <b>did the service produce a decision?</b> A payment
 * that fails validation was understood, evaluated against ten rules, and
 * decisioned — that is the service working, and the decision is the payload.
 * A body that is not readable as JSON produced no decision at all, so it is a
 * 400 and reports through {@link ApiExceptionHandler} in a different shape.
 *
 * <p>The honest counter-argument is 422 Unprocessable Entity, which exists for
 * exactly "well-formed but semantically invalid" and gets you HTTP-level
 * observability for free.
 *
 * <p><b>The trap this choice creates, which must be handled in M5:</b> generic
 * tooling counts non-2xx responses. With rejections on 200, a k6 load test will
 * report a 0% error rate while rejecting every payment, and a Grafana panel
 * built on status codes will show a perfectly healthy service. So the M5
 * decision-outcome counters are not a nice-to-have — they are the only place
 * rejection is visible, and the k6 thresholds must assert on them rather than on
 * status codes. The mirror-image trap for 422 is that a spike in customer error
 * alarms your error-rate panel as though the service were broken.
 *
 * <h2>DECISION 2 — the idempotency key is a required header</h2>
 *
 * <p>Required now rather than optional-then-required, because adding a required
 * header later is a breaking change and the cost today is one line per caller.
 * A header rather than a body field because it describes the <em>submission</em>,
 * not the payment: two submissions of the same payment under different keys are
 * two payments, the same key twice is one.
 *
 * <p>In M1 it reaches an in-memory map. M2 makes it load-bearing.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentValidator validator;
    private final PaymentStore paymentStore;

    public PaymentController(PaymentValidator validator, PaymentStore paymentStore) {
        this.validator = validator;
        this.paymentStore = paymentStore;
    }

    @PostMapping
    public ResponseEntity<PaymentSubmissionResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentInstructionRequest request) {

        // Exhaustive over a sealed type: adding a third outcome stops this
        // compiling rather than silently falling through.
        return switch (validator.validate(request)) {
            case ValidationOutcome.Accepted accepted -> {
                StoredPayment stored = paymentStore.record(accepted.instruction(), idempotencyKey);
                yield ResponseEntity.ok(PaymentSubmissionResponse.accepted(
                        accepted.instruction().endToEndId(), stored.paymentId()));
            }
            case ValidationOutcome.Rejected rejected -> ResponseEntity.ok(
                    PaymentSubmissionResponse.rejected(request.endToEndId(), rejected.reasons()));
        };
    }
}
