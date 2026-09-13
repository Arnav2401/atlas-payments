package com.atlas.payments.api;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.api.dto.PaymentSubmissionResponse;
import com.atlas.payments.persistence.PaymentStore;
import com.atlas.payments.validation.PaymentValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP boundary. Spring web types stop here.
 *
 * <p>This class should stay thin enough to read in one screen: validate, record,
 * map to the wire type. Any logic that grows here belongs in the validation or
 * domain package, where it can be tested without an application context.
 *
 * <h2>DECISION 1 — 200-with-status, or 4xx?</h2>
 *
 * This is on the brief's "will be asked" list and it is yours to settle. The
 * axis: is a rejected payment a <em>failed HTTP request</em>, or a
 * <em>successful request reporting a business outcome</em>? Both are defended in
 * production systems. What it changes here: on 200, the rejection path returns a
 * {@link PaymentSubmissionResponse} with {@code status=REJECTED} from this
 * method; on 4xx, the rejection becomes a thrown exception handled in
 * {@link ApiExceptionHandler}, {@link PaymentSubmissionResponse} loses its status
 * field, and {@code ValidationOutcome} arguably stops needing to be sealed.
 *
 * <p>Whichever you pick, the follow-up is: what does a monitoring dashboard in M5
 * show for a rejected payment, and is that the signal you want?
 *
 * <h2>DECISION 2 — the idempotency key</h2>
 *
 * Header or body field? Required or optional? If optional, what happens on a
 * retry without one? M2 makes this load-bearing, so it is cheaper to decide now.
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

    /**
     * TODO(M1): implement.
     *
     * <p>Sketch, once DECISION 1 is settled: run the validator, then
     * {@code switch} over the sealed {@code ValidationOutcome}. The exhaustive
     * switch is the point — if a third outcome is ever added, this method stops
     * compiling instead of silently falling through.
     */
    @PostMapping
    public ResponseEntity<PaymentSubmissionResponse> submit(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PaymentInstructionRequest request) {

        throw new UnsupportedOperationException("TODO(M1): validate -> record -> map");
    }
}
