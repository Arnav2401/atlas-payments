export type Role = "ANALYST" | "SUPERVISOR";

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface TopFeature {
  feature: string;
  value: number | null;
  shapContribution: number;
}

export type ReviewStatus = "NONE" | "UNDER_REVIEW" | "CLEARED" | "ESCALATED";

// Mirrors PaymentDecisionController.PaymentSummaryResponse exactly - the
// four fraud fields (flagged/probability/source/topFeatures) are null/empty
// for a payment that predates a decision consumer, or whose decision hasn't
// arrived yet (the outbox -> Kafka -> consumer path is asynchronous; the
// ledger write that produced this row is not the same transaction as the
// decision that scored it).
export interface PaymentSummary {
  paymentId: string;
  endToEndId: string;
  bookedAt: string;
  amount: string;
  currency: string;
  debtorAccount: string;
  creditorAccount: string;
  flagged: boolean | null;
  probability: number | null;
  source: string | null;
  reviewStatus: ReviewStatus;
  topFeatures: TopFeature[];
}

export interface ApiError {
  code: string;
  message: string;
}
