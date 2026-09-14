package com.atlas.payments.fraud;

import com.atlas.payments.ledger.JournalEntryEntity;
import com.atlas.payments.ledger.JournalEntryRepository;
import com.atlas.payments.ledger.Money;
import com.atlas.payments.ledger.PostingEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/payments")
public class PaymentDecisionController {
    private final JournalEntryRepository journalEntries;
    private final PaymentDecisionRepository decisions;
    private final ObjectMapper objectMapper;

    public PaymentDecisionController(JournalEntryRepository journalEntries, PaymentDecisionRepository decisions,
                                     ObjectMapper objectMapper) {
        this.journalEntries = journalEntries;
        this.decisions = decisions;
        this.objectMapper = objectMapper;
    }

    public record TopFeatureResponse(String feature, Double value, double shapContribution) {}

    public record PaymentSummaryResponse(
            String paymentId,
            String endToEndId,
            Instant bookedAt,
            BigDecimal amount,
            String currency,
            String debtorAccount,
            String creditorAccount,
            Boolean flagged,
            Double probability,
            String source,
            String reviewStatus,
            List<TopFeatureResponse> topFeatures) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ANALYST', 'SUPERVISOR')")
    @Transactional(readOnly = true)
    public ResponseEntity<List<PaymentSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<JournalEntryEntity> entries = journalEntries.findAllByOrderByBookedAtDesc(PageRequest.of(page, size));
        if (entries.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // Re-read the page with postings and accounts attached, otherwise
        // toSummary triggers a query per entry and per posting.
        List<Long> ids = entries.getContent().stream().map(JournalEntryEntity::getId).toList();
        Map<Long, JournalEntryEntity> hydrated = journalEntries.findWithPostingsByIdIn(ids).stream()
                .collect(Collectors.toMap(JournalEntryEntity::getId, e -> e));

        List<UUID> paymentIds = entries.getContent().stream().map(JournalEntryEntity::getExternalId).toList();
        Map<UUID, PaymentDecisionEntity> decisionsByPaymentId = decisions.findAllByPaymentIdIn(paymentIds).stream()
                .collect(Collectors.toMap(PaymentDecisionEntity::getPaymentId, d -> d));

        // Ordered by the paged query, which the IN lookup above does not preserve.
        List<PaymentSummaryResponse> summaries = entries.getContent().stream()
                .map(entry -> hydrated.getOrDefault(entry.getId(), entry))
                .map(entry -> toSummary(entry, decisionsByPaymentId.get(entry.getExternalId())))
                .toList();

        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('ANALYST', 'SUPERVISOR')")
    @Transactional(readOnly = true)
    public ResponseEntity<PaymentSummaryResponse> get(@PathVariable UUID paymentId) {
        JournalEntryEntity entry = journalEntries.findByExternalId(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        PaymentDecisionEntity decision = decisions.findByPaymentId(paymentId).orElse(null);
        return ResponseEntity.ok(toSummary(entry, decision));
    }

    @PostMapping("/{paymentId}/review")
    @PreAuthorize("hasAnyRole('ANALYST', 'SUPERVISOR')")
    @Transactional
    public ResponseEntity<Void> requestReview(@PathVariable UUID paymentId) {
        decisions.findByPaymentId(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId))
                .requestReview();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{paymentId}/clear")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional
    public ResponseEntity<Void> clear(@PathVariable UUID paymentId) {
        decisions.findByPaymentId(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId)).clear();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{paymentId}/escalate")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional
    public ResponseEntity<Void> escalate(@PathVariable UUID paymentId) {
        decisions.findByPaymentId(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId)).escalate();
        return ResponseEntity.ok().build();
    }

    private PaymentSummaryResponse toSummary(JournalEntryEntity entry, PaymentDecisionEntity decision) {
        List<PostingEntity> postings = entry.getPostings();
        PostingEntity debit = postings.stream().filter(PostingEntity::isDebit).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "journal entry " + entry.getExternalId() + " has no debit posting — the deferred "
                                + "balance trigger should have made this impossible to commit"));
        PostingEntity credit = postings.stream().filter(p -> !p.isDebit()).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "journal entry " + entry.getExternalId() + " has no credit posting — the deferred "
                                + "balance trigger should have made this impossible to commit"));

        var currency = java.util.Currency.getInstance(debit.getCurrency());
        BigDecimal amount = Money.fromMinorUnits(debit.getAmountMinor(), currency);

        List<TopFeatureResponse> topFeatures = decision == null ? List.of() : parseTopFeatures(decision.getRawPayload());

        return new PaymentSummaryResponse(
                entry.getExternalId().toString(),
                entry.getEndToEndId(),
                entry.getBookedAt(),
                amount,
                debit.getCurrency(),
                debit.getAccount().getAccountNumber(),
                credit.getAccount().getAccountNumber(),
                decision == null ? null : decision.isFlagged(),
                decision == null ? null : decision.getProbability(),
                decision == null ? null : decision.getSource(),
                decision == null ? ReviewStatus.NONE.name() : decision.getReviewStatus().name(),
                topFeatures);
    }

    private List<TopFeatureResponse> parseTopFeatures(String rawPayload) {
        try {
            PaymentDecisionedEvent event = objectMapper.readValue(rawPayload, PaymentDecisionedEvent.class);
            return event.topFeatures().stream()
                    .map(f -> new TopFeatureResponse(f.feature(), f.value(), f.shapContribution()))
                    .toList();
        } catch (Exception malformed) {
            return List.of();
        }
    }
}
