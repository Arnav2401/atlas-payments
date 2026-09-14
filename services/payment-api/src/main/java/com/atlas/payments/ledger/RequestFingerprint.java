package com.atlas.payments.ledger;

import com.atlas.payments.domain.PaymentInstruction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
