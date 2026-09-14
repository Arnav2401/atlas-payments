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

@RestControllerAdvice
public class ApiExceptionHandler {
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

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                ApiError.IDEMPOTENCY_CONFLICT, exception.getMessage()));
    }

    @ExceptionHandler(LedgerConflictException.class)
    public ResponseEntity<ApiError> handleLedgerConflict(LedgerConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                ApiError.LEDGER_CONFLICT, exception.getMessage()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentModification(OptimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                ApiError.CONCURRENT_MODIFICATION,
                "A concurrent payment modified this account. Resubmit with the same Idempotency-Key."));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(
                ApiError.INVALID_CREDENTIALS, "Invalid username or password"));
    }

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleNarrowingFailure(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                "ATLAS-E500",
                "Internal error processing the payment instruction"));
    }
}
