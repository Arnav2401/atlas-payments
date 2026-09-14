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

export interface RingCandidate {
  accountId: string;
  community: number;
  inDegree: number;
  temporalSpreadHours: number;
  suspicionScore: number;
  planted: boolean;
}

export interface RingsResponse {
  enabled: boolean;
  candidates: RingCandidate[];
}
