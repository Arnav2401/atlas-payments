package com.atlas.payments.ledger;

import com.atlas.payments.domain.PaymentInstruction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A stable digest of a payment's economically meaningful fields, used to detect
 * an idempotency key reused for different content.
 *
 * <p>SHA-256 rather than {@code hashCode()}: this value is persisted and
 * compared across processes and JVM restarts, and {@code hashCode()} guarantees
 * neither stability across runs nor collision resistance. A collision here would
 * let a genuinely different payment be mistaken for a repeat of an earlier one.
 *
 * <p>The fields are joined with ASCII Unit Separator, a character R04's
 * permitted set forbids in any input, so {@code ("AB","C")} and
 * {@code ("A","BC")} cannot digest alike.
 */
final class RequestFingerprint {

    private static final String DELIMITER = "\u001F";

    private RequestFingerprint() {
    }

    static String of(PaymentInstruction instruction) {
        String canonical = String.join(DELIMITER,
                instruction.endToEndId(),
                instruction.instructedAmount().stripTrailingZeros().toPlainString(),
                instruction.instructedCurrency().getCurrencyCode(),
                instruction.debtorAgent(),
                instruction.creditorAgent(),
                instruction.debtorAccount(),
                instruction.creditorAccount(),
                instruction.debtorCountry(),
                instruction.chargeBearer().name(),
                instruction.settlementDate().toString());

        return HexFormat.of().formatHex(sha256(canonical));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
