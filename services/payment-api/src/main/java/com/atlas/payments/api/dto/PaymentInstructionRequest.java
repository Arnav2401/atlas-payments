package com.atlas.payments.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

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
