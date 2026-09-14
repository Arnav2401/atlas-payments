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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
 *
 * <h2>DECISION 3 — a fraud flag rides alongside acceptance, it does not block it</h2>
 *
 * <p>M3 adds a fraud assessment to every newly-written payment, but a flagged
 * payment is still returned {@code ACCEPTED} with the assessment attached, not
 * rejected outright. The alternative — auto-rejecting anything the model
 * flags — needs a review workflow behind it (who clears a false positive, and
 * how) that does not exist yet; that is M5's ops-console "decision action".
 * Until it does, auto-rejecting on a probabilistic score would make a false
 * positive unrecoverable rather than merely inconvenient. Scoring and
 * surfacing the result now, blocking on it later once there is somewhere for
 * a blocked payment to go, is the narrower and more honest claim for this
 * module to make.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentValidator validator;
    private final PaymentStore paymentStore;
    private final FraudClient fraudClient;
    private final Clock clock;

    public PaymentController(PaymentValidator validator, PaymentStore paymentStore,
                             FraudClient fraudClient, Clock clock) {
        this.validator = validator;
        this.paymentStore = paymentStore;
        this.fraudClient = fraudClient;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<PaymentSubmissionResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentInstructionRequest request) {

        // Exhaustive over a sealed type: adding a third outcome stops this
        // compiling rather than silently falling through.
        return switch (validator.validate(request)) {
            case ValidationOutcome.Accepted accepted -> {
                try {
                    StoredPayment stored = paymentStore.record(accepted.instruction(), idempotencyKey);
                    FraudAssessment assessment = assessFraud(accepted.instruction(), stored);
                    yield ResponseEntity.ok(PaymentSubmissionResponse.accepted(
                            accepted.instruction().endToEndId(), stored.paymentId(), assessment));
                } catch (InsufficientFundsException insufficient) {
                    // A payment can pass all ten rules and still be refused by
                    // the ledger, because the rules cannot see account state.
                    // Same envelope as a validation rejection: the service
                    // produced a decision, and the decision is the payload.
                    yield ResponseEntity.ok(PaymentSubmissionResponse.rejectedByLedger(
                            accepted.instruction().endToEndId(),
                            InsufficientFundsException.CODE,
                            "debtorAccount",
                            insufficient.getMessage()));
                }
            }
            case ValidationOutcome.Rejected rejected -> ResponseEntity.ok(
                    PaymentSubmissionResponse.rejected(request.endToEndId(), rejected.reasons()));
        };
    }

    /**
     * Scores a genuinely new payment, and skips scoring a replay entirely.
     *
     * <p>A replay is the same payment already decided once — see the javadoc
     * on {@code StoredPayment}. {@code stored.debtorBalanceBeforeMinor()} is
     * {@code null} exactly on that path, which is what this check is really
     * testing; it is not a null-safety formality.
     *
     * <p>{@code hourOfDay} is the wall-clock hour at submission time (UTC, for
     * the same reason R08 fixes its clock to UTC — see
     * SettlementDateWindowRule), not the simulated hour PaySim's `step`
     * encoded during training. Same feature, same meaning ("what time of day
     * did this happen"), different clock behind it — training never had
     * access to a real submission timestamp because PaySim does not have one.
     *
     * <p>{@code isCashOut} is always {@code false} — see the field's javadoc
     * on {@link FraudAssessmentRequest} for the scoping limitation that follows
     * from it.
     */
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
