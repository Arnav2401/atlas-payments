package com.atlas.payments.api;

import com.atlas.payments.api.dto.ApiError;
import com.atlas.payments.fraud.PaymentNotFoundException;
import com.atlas.payments.fraud.ReviewConflictException;
import com.atlas.payments.ledger.IdempotencyConflictException;
import com.atlas.payments.ledger.LedgerConflictException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handles failures that occur before any rule runs.
 *
 * <p>This is the other half of "Jackson owns shape, the ten rules own
 * semantics": a body that is not valid JSON, or a settlement date that is not a
 * date, fails during deserialisation and none of the ten rules ever execute.
 * Those get {@link ApiError} and a 400 — deliberately a different shape and a
 * different status from a rule rejection, because a client that cannot
 * distinguish "your JSON is broken" from "your currency is not supported"
 * cannot act on either.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * The exception message is deliberately discarded. Jackson's text names
     * internal classes and field paths, and for a bad value it quotes the value
     * back — which on this endpoint could be part of an account identifier.
     * Neither belongs in a response to an unauthenticated caller.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                ApiError.MALFORMED_BODY,
                "Request body could not be read as a payment instruction"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                ApiError.MISSING_HEADER,
                "Missing required header: " + exception.getHeaderName()));
    }

    /**
     * An idempotency key reused for a different payment: 409, not 400 and not a
     * rejection. The request is well-formed and the payment is valid — the
     * conflict is with state the caller created earlier, which is exactly what
     * 409 means.
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                ApiError.IDEMPOTENCY_CONFLICT, exception.getMessage()));
    }

    /**
     * The brief's requirement that a ledger conflict is handled explicitly
     * rather than surfacing as a 500.
     */
    @ExceptionHandler(LedgerConflictException.class)
    public ResponseEntity<ApiError> handleLedgerConflict(LedgerConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                ApiError.LEDGER_CONFLICT, exception.getMessage()));
    }

    /**
     * Two payments raced on the same account and this one lost the version check.
     *
     * <p>409, not 500 — the brief's requirement that the conflict is handled
     * explicitly. It is also honest advice: the caller may safely resubmit with
     * the same Idempotency-Key, and will either win the race or be told the
     * payment already exists. A transient conflict is a different thing from a
     * business rejection, which is why this is not a 200 REJECTED.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentModification(OptimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                ApiError.CONCURRENT_MODIFICATION,
                "A concurrent payment modified this account. Resubmit with the same Idempotency-Key."));
    }

    /**
     * {@code POST /auth/token} with a wrong username or password. Handled here
     * (application code throws it explicitly) rather than by Spring Security's
     * own filter-chain exception translation, because this exception comes
     * from inside {@code AuthController}, after the request has already
     * reached application code — the filter chain's own 401 handling is for
     * requests that never carried a valid bearer token at all.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(
                ApiError.INVALID_CREDENTIALS, "Invalid username or password"));
    }

    /**
     * A review-workflow transition attempted on an already-resolved payment.
     * A business conflict, not a server fault — 409, the same status M2 uses
     * for every other "the state you assumed has moved on" case in this API.
     * Its own exception type, not the generic {@code IllegalStateException} —
     * see {@link ReviewConflictException}'s javadoc for why that distinction
     * matters here specifically.
     */
    @ExceptionHandler(ReviewConflictException.class)
    public ResponseEntity<ApiError> handleReviewConflict(ReviewConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                ApiError.REVIEW_CONFLICT, exception.getMessage()));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiError> handlePaymentNotFound(PaymentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                ApiError.PAYMENT_NOT_FOUND, exception.getMessage()));
    }

    /**
     * A narrowing failure in {@code PaymentInstruction.of} means a rule that
     * should have caught something did not. That is a bug in the rule set, not
     * bad input, so it must not be dressed up as a rejection — it surfaces as a
     * 500 and should page someone.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleNarrowingFailure(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                "ATLAS-E500",
                "Internal error processing the payment instruction"));
    }
}
