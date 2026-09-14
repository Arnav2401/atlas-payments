package com.atlas.payments.testing;

import com.atlas.payments.api.dto.PaymentInstructionRequest;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class PaymentInstructionRequests {
    private PaymentInstructionRequests() {}

    public static Builder valid() {
        return new Builder();
    }

    public static final class Builder {
        private String endToEndId = "E2E-0000000001";
        private BigDecimal instructedAmount = new BigDecimal("100.00");
        private String instructedCurrency = "USD";
        private String debtorAgent = "DEUTDEFF";
        private String creditorAgent = "CHASUS33XXX";
        private String debtorAccount = "DE89370400440532013000";
        private String creditorAccount = "GB29NWBK60161331926819";
        private String debtorCountry = "DE";
        private String chargeBearer = "SHAR";
        private LocalDate settlementDate = LocalDate.of(2026, 10, 1);

        public Builder endToEndId(String v) { this.endToEndId = v; return this; }
        public Builder instructedAmount(BigDecimal v) { this.instructedAmount = v; return this; }
        public Builder instructedAmount(String v) { this.instructedAmount = v == null ? null : new BigDecimal(v); return this; }
        public Builder instructedCurrency(String v) { this.instructedCurrency = v; return this; }
        public Builder debtorAgent(String v) { this.debtorAgent = v; return this; }
        public Builder creditorAgent(String v) { this.creditorAgent = v; return this; }
        public Builder debtorAccount(String v) { this.debtorAccount = v; return this; }
        public Builder creditorAccount(String v) { this.creditorAccount = v; return this; }
        public Builder debtorCountry(String v) { this.debtorCountry = v; return this; }
        public Builder chargeBearer(String v) { this.chargeBearer = v; return this; }
        public Builder settlementDate(LocalDate v) { this.settlementDate = v; return this; }

        public PaymentInstructionRequest build() {
            return new PaymentInstructionRequest(
                    endToEndId, instructedAmount, instructedCurrency,
                    debtorAgent, creditorAgent,
                    debtorAccount, creditorAccount,
                    debtorCountry, chargeBearer, settlementDate);
        }
    }
}
