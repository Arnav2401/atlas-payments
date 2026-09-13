package com.atlas.payments.api;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handles failures that never reach the rule set.
 *
 * <p>This is the other half of "Jackson owns shape, the ten rules own semantics":
 * a body that is not valid JSON, or a settlement date that is not a date, fails
 * during deserialisation and none of the ten rules ever run. Those responses need
 * a shape too, and it should be distinguishable from a rule rejection — a client
 * that cannot tell "your JSON is broken" from "your currency is not supported"
 * cannot act on either.
 *
 * <p>TODO(M1): decide whether a shape failure reuses the rejection envelope with a
 * reserved code, or gets its own. Then make sure the answer is in the README
 * alongside the ten rules, because the ten-rule table will otherwise imply these
 * are the only ways a request can fail.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleUnreadableBody(HttpMessageNotReadableException exception) {
        throw new UnsupportedOperationException(
                "TODO(M1): map a malformed body to your error envelope. "
                        + "Do not leak the Jackson message to the caller - it contains "
                        + "internal class names and, for a bad value, the value itself.");
    }
}
