package com.atlas.payments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

/**
 * A payment instruction that has passed all ten rules.
 *
 * <p>Parse, don't validate. Downstream code takes this type, never
 * {@code PaymentInstructionRequest}, so it cannot receive unvalidated data — the
 * type does not exist until validation succeeded. The fields have narrowed:
 * {@code instructedCurrency} is a real {@link Currency} and {@code chargeBearer}
 * a real enum, because by this point R03 and R07 have proved they parse.
 *
 * <p><b>Honest limitation.</b> Construct only via {@link #of}, and only from
 * {@code PaymentValidator}. Java cannot enforce that across packages — the
 * guarantee is convention plus code review. If you want the compiler to enforce
 * it, move this type into the validation package and expose a read-only
 * interface, or use JPMS. Worth settling before M2 depends on it.
 */
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

    /** Who pays the transfer charges. */
    public enum ChargeBearer { DEBT, CRED, SHAR, SLEV }

    /**
     * Every field is required. These checks can only fire if a rule was removed
     * or the type was constructed outside the validator — they are an assertion
     * that the invariant holds, not input validation.
     */
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

    /**
     * The narrowing step: takes the request's permissive types and produces the
     * strict ones. Call only after every rule has passed.
     *
     * @throws IllegalArgumentException if narrowing fails, which means a rule
     *         that should have caught it did not — a bug in the rule set, not
     *         bad input, and it should surface as a 500 rather than be mapped to
     *         a rejection.
     */
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
