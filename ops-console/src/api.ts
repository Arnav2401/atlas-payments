import type { ApiError, AuthResponse, PaymentSummary, RingsResponse, Role } from "./types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export class ApiRequestError extends Error {
  status: number;
  code: string;

  constructor(status: number, code: string) {
    super(`${status} ${code}`);
    this.status = status;
    this.code = code;
  }
}

async function request<T>(path: string, token: string | null, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });
  if (!res.ok) {
    let code = `HTTP_${res.status}`;
    try {
      const body = (await res.json()) as ApiError;
      code = body.code ?? code;
    } catch {
      // Not every error carries a JSON body (a 403 has none). Status is enough.
    }
    throw new ApiRequestError(res.status, code);
  }
  if (res.status === 204 || res.headers.get("content-length") === "0") {
    return undefined as T;
  }
  return (await res.json()) as T;
}

export async function login(username: string, password: string): Promise<AuthResponse> {
  return request<AuthResponse>("/auth/token", null, {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
}

export async function listPayments(token: string): Promise<PaymentSummary[]> {
  return request<PaymentSummary[]>("/payments?page=0&size=50", token);
}

export async function getPayment(token: string, paymentId: string): Promise<PaymentSummary> {
  return request<PaymentSummary>(`/payments/${paymentId}`, token);
}

export type DecisionAction = "review" | "clear" | "escalate";

export async function postDecisionAction(
  token: string,
  paymentId: string,
  action: DecisionAction,
): Promise<void> {
  return request<void>(`/payments/${paymentId}/${action}`, token, { method: "POST" });
}

export async function getRings(token: string, topK = 15): Promise<RingsResponse> {
  return request<RingsResponse>(`/rings?topK=${topK}`, token);
}

// Only decides which buttons to render. The server re-checks the role on every
// endpoint, so a wrong answer here is a cosmetic bug, not a privilege one.
export function roleFromToken(token: string): Role | null {
  try {
    // JWT segments are base64url: atob() needs the URL-safe chars swapped back
    // and the stripped '=' padding restored, or it throws on some tokens.
    const segment = token.split(".")[1];
    const base64 = segment.replace(/-/g, "+").replace(/_/g, "/").padEnd(
      segment.length + ((4 - (segment.length % 4)) % 4),
      "=",
    );
    const payload = JSON.parse(atob(base64));
    return payload.role === "SUPERVISOR" ? "SUPERVISOR" : "ANALYST";
  } catch {
    return null;
  }
}
