package com.atlas.payments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

public record PaymentInstruction(
        String endToEndId,
        BigDecimal instructedAmount,
        Currency instructedCurrency,
        String debtorAgent,
        String creditorAgent,
        String debtorAccount,
        String creditorAccount,
        String debtorCountry,
        ChargeBearer chargeBearer,
        LocalDate settlementDate
) {
    public enum ChargeBearer { DEBT, CRED, SHAR, SLEV }

    public PaymentInstruction {
        Objects.requireNonNull(endToEndId, "endToEndId");
        Objects.requireNonNull(instructedAmount, "instructedAmount");
        Objects.requireNonNull(instructedCurrency, "instructedCurrency");
        Objects.requireNonNull(debtorAgent, "debtorAgent");
        Objects.requireNonNull(creditorAgent, "creditorAgent");
        Objects.requireNonNull(debtorAccount, "debtorAccount");
        Objects.requireNonNull(creditorAccount, "creditorAccount");
        Objects.requireNonNull(debtorCountry, "debtorCountry");
        Objects.requireNonNull(chargeBearer, "chargeBearer");
        Objects.requireNonNull(settlementDate, "settlementDate");
    }

    public static PaymentInstruction of(
            String endToEndId,
            BigDecimal instructedAmount,
            String instructedCurrency,
            String debtorAgent,
            String creditorAgent,
            String debtorAccount,
            String creditorAccount,
            String debtorCountry,
            String chargeBearer,
            LocalDate settlementDate) {
        return new PaymentInstruction(
                endToEndId,
                instructedAmount,
                Currency.getInstance(instructedCurrency),
                debtorAgent,
                creditorAgent,
                debtorAccount,
                creditorAccount,
                debtorCountry,
                ChargeBearer.valueOf(chargeBearer),
                settlementDate);
    }
}
