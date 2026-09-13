package com.atlas.payments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

/**
 * A payment instruction that has passed all ten rules.
 *
 * <p>Parse, don't validate. Downstream code takes this type, never
 * {@code PaymentInstructionRequest}, so it cannot receive unvalidated data —
 * the type does not exist until validation succeeded. Note the fields have
 * narrowed: {@code instructedCurrency} is a real {@link Currency}, not a String,
 * because by this point R03 has proved it parses.
 *
 * <p>This is the decision that pays off in M2 and M4. The ledger write and the
 * outbox row both take this type, and neither needs a defensive re-check.
 *
 * <p><b>Honest limitation.</b> Construct only via {@link #of}, and only from
 * {@code PaymentValidator}. Java cannot enforce that across packages — the
 * guarantee is convention plus code review. If you want the compiler to enforce
 * it, either move this type into the validation package and expose a read-only
 * interface to the rest of the app, or put the two packages in a JPMS module and
 * export only the interface. Worth settling before M2 depends on it.
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
     * TODO(M1): the only construction path. Called by PaymentValidator once every
     * rule has passed; performs the narrowing (String -> Currency, String ->
     * ChargeBearer) that the rules have already proved is safe.
     */
    public static PaymentInstruction of(/* TODO(M1): parameters */) {
        throw new UnsupportedOperationException("TODO(M1): narrow a validated request into this type");
    }
}
