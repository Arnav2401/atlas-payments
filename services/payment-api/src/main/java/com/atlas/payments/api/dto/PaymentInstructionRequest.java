package com.atlas.payments.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Raw inbound payload.
 *
 * <p>ISO-20022-<em>flavoured</em> field names. This is not ISO 20022 pacs.008 XML
 * and nothing here claims schema compliance — the README must say so plainly.
 *
 * <p>The field types are permissive on purpose, and the line is: <b>Jackson owns
 * shape, the ten rules own semantics.</b> {@code instructedCurrency} is a String
 * rather than an enum or {@link java.util.Currency} so that "currency must be a
 * live ISO 4217 code" stays a rule that emits your reason code, instead of a
 * deserialisation failure that emits a 400 you do not control. {@code "XYZ"} is
 * well-formed but wrong, so it is a rule. {@code settlementDate} stays a
 * {@link LocalDate} because {@code "not-a-date"} is not well-formed at all —
 * that is genuinely a shape error and Jackson should own it.
 *
 * <p>If you disagree with where that line sits, move it — but move it
 * deliberately and write the entry in DESIGN_NOTES.md, because "why is currency
 * a String here" is an obvious interview question.
 */
public record PaymentInstructionRequest(
        String endToEndId,
        BigDecimal instructedAmount,
        String instructedCurrency,
        String debtorAgent,
        String creditorAgent,
        String debtorAccount,
        String creditorAccount,
        String debtorCountry,
        String chargeBearer,
        LocalDate settlementDate
) {}
