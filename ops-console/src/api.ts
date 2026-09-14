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
      // Non-JSON error body (e.g. a 403 with no content) - the status code
      // alone is still useful to the caller.
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

// Client-side only, and not a security boundary - it exists purely to decide
// which action buttons to render. The server enforces the real boundary with
// @PreAuthorize on every one of those endpoints regardless of what this
// function returns, proven live in PaymentAuthorizationTest (an ANALYST
// token gets 403 from /clear and /escalate even if a client never checked
// the role at all).
export function roleFromToken(token: string): Role | null {
  try {
    const payload = JSON.parse(atob(token.split(".")[1]));
    return payload.role === "SUPERVISOR" ? "SUPERVISOR" : "ANALYST";
  } catch {
    return null;
  }
}
