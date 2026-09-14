import { useCallback, useEffect, useState } from "react";
import {
  ApiRequestError,
  getPayment,
  getRings,
  listPayments,
  login,
  postDecisionAction,
  roleFromToken,
  type DecisionAction,
} from "./api";
import type { PaymentSummary, RingCandidate, Role } from "./types";
import "./index.css";

interface Session {
  token: string;
  username: string;
  role: Role | null;
}

function LoginForm({ onLogin }: { onLogin: (session: Session) => void }) {
  const [username, setUsername] = useState("analyst1");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const { accessToken } = await login(username, password);
      onLogin({ token: accessToken, username, role: roleFromToken(accessToken) });
    } catch {
      setError("Invalid username or password.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="login-card" onSubmit={submit}>
        <h1>atlas-payments</h1>
        <p className="subtitle">Ops console</p>
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? "Signing in…" : "Sign in"}
        </button>
        <p className="hint">
          Demo users: <code>analyst1</code> / <code>supervisor1</code>
        </p>
      </form>
    </div>
  );
}

function ReviewBadge({ status }: { status: PaymentSummary["reviewStatus"] }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{status.replace("_", " ")}</span>;
}

function FraudBadge({ payment }: { payment: PaymentSummary }) {
  if (payment.flagged === null) {
    return <span className="badge badge-pending">scoring…</span>;
  }
  return payment.flagged ? (
    <span className="badge badge-flagged">flagged</span>
  ) : (
    <span className="badge badge-clean">clean</span>
  );
}

function PaymentDetail({
  payment,
  role,
  onAction,
  actionPending,
}: {
  payment: PaymentSummary;
  role: Role | null;
  onAction: (action: DecisionAction) => void;
  actionPending: boolean;
}) {
  const canReview = payment.reviewStatus === "NONE" || payment.reviewStatus === "UNDER_REVIEW";
  const canResolve = payment.reviewStatus === "UNDER_REVIEW";

  return (
    <div className="detail">
      <h2>{payment.endToEndId}</h2>
      <dl className="detail-grid">
        <dt>Amount</dt>
        <dd>
          {payment.amount} {payment.currency}
        </dd>
        <dt>Debtor</dt>
        <dd>{payment.debtorAccount}</dd>
        <dt>Creditor</dt>
        <dd>{payment.creditorAccount}</dd>
        <dt>Booked</dt>
        <dd>{new Date(payment.bookedAt).toLocaleString()}</dd>
        <dt>Fraud score</dt>
        <dd>
          <FraudBadge payment={payment} />{" "}
          {payment.probability !== null && (
            <span className="probability">{(payment.probability * 100).toFixed(3)}%</span>
          )}{" "}
          {payment.source && <span className="source">via {payment.source}</span>}
        </dd>
        <dt>Review status</dt>
        <dd>
          <ReviewBadge status={payment.reviewStatus} />
        </dd>
      </dl>

      {payment.topFeatures.length > 0 && (
        <>
          <h3>Top SHAP features</h3>
          <p className="hint">Negative contribution pushes the score toward fraud.</p>
          <table className="shap-table">
            <thead>
              <tr>
                <th>Feature</th>
                <th>Value</th>
                <th>SHAP contribution</th>
              </tr>
            </thead>
            <tbody>
              {payment.topFeatures.map((f) => (
                <tr key={f.feature}>
                  <td>{f.feature}</td>
                  <td>{f.value ?? "—"}</td>
                  <td className={f.shapContribution < 0 ? "negative" : "positive"}>
                    {f.shapContribution.toFixed(4)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      <div className="actions">
        {canReview && (
          <button disabled={actionPending} onClick={() => onAction("review")}>
            Request review
          </button>
        )}
        {/* Enabled for both roles on purpose: an analyst clicking these should see
            the server's 403, not a button that was never clickable. */}
        <button
          disabled={actionPending || !canResolve}
          onClick={() => onAction("clear")}
          title={role !== "SUPERVISOR" ? "Requires SUPERVISOR — will be rejected server-side" : undefined}
        >
          Clear
        </button>
        <button
          disabled={actionPending || !canResolve}
          onClick={() => onAction("escalate")}
          title={role !== "SUPERVISOR" ? "Requires SUPERVISOR — will be rejected server-side" : undefined}
        >
          Escalate
        </button>
      </div>
    </div>
  );
}

function FraudRingsView({ session }: { session: Session }) {
  const [rings, setRings] = useState<RingCandidate[] | null>(null);
  const [enabled, setEnabled] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await getRings(session.token);
      setEnabled(response.enabled);
      setRings(response.candidates);
    } catch (e) {
      setError(e instanceof ApiRequestError ? `Failed to load fraud rings: ${e.code}` : "Failed to load fraud rings");
    } finally {
      setLoading(false);
    }
  }, [session.token]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return (
    <div className="rings-pane">
      <div className="list-header">
        <h2>M6 — suspected fraud rings</h2>
        <button className="link-button" onClick={refresh} disabled={loading}>
          {loading ? "Refreshing…" : "Refresh"}
        </button>
      </div>
      <p className="hint">
        Accounts ranked by in-degree concentrated into a short time window — Louvain community + degree
        centrality, computed offline over the counterparty graph (see the README's M6 section). A row marked
        PLANTED is one of the synthetic rings deliberately seeded to prove this ranking actually finds them,
        not a real one.
      </p>

      {error && <p className="error">{error}</p>}

      {!loading && !enabled && (
        <p className="hint">
          Graph features are not enabled on the fraud service (no Neo4j configured) — this is optional M6
          scope, off by default. See the README for how to turn it on.
        </p>
      )}

      {!loading && enabled && rings && rings.length === 0 && <p className="hint">No suspicious accounts found.</p>}

      {!loading && enabled && rings && rings.length > 0 && (
        <table className="shap-table">
          <thead>
            <tr>
              <th>Account</th>
              <th>Community</th>
              <th>In-degree</th>
              <th>Spread (h)</th>
              <th>Score</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rings.map((r) => (
              <tr key={r.accountId}>
                <td>{r.accountId}</td>
                <td>{r.community}</td>
                <td>{r.inDegree}</td>
                <td>{r.temporalSpreadHours}</td>
                <td>{r.suspicionScore.toFixed(2)}</td>
                <td>{r.planted && <span className="badge badge-flagged">PLANTED</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function Console({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const [tab, setTab] = useState<"payments" | "rings">("payments");
  const [payments, setPayments] = useState<PaymentSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionPending, setActionPending] = useState(false);
  const [banner, setBanner] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const list = await listPayments(session.token);
      setPayments(list);
    } catch (e) {
      setBanner(e instanceof ApiRequestError ? `Failed to load payments: ${e.code}` : "Failed to load payments");
    } finally {
      setLoading(false);
    }
  }, [session.token]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const selected = payments.find((p) => p.paymentId === selectedId) ?? null;

  async function handleAction(action: DecisionAction) {
    if (!selected) return;
    setActionPending(true);
    setBanner(null);
    try {
      await postDecisionAction(session.token, selected.paymentId, action);
      const updated = await getPayment(session.token, selected.paymentId);
      setPayments((prev) => prev.map((p) => (p.paymentId === updated.paymentId ? updated : p)));
    } catch (e) {
      setBanner(
        e instanceof ApiRequestError
          ? e.status === 403
            ? `Denied by the server: ${session.role ?? "this role"} cannot ${action} a payment.`
            : `Action failed: ${e.code}`
          : "Action failed",
      );
    } finally {
      setActionPending(false);
    }
  }

  return (
    <div className="console">
      <header>
        <h1>atlas-payments ops console</h1>
        <div className="session-info">
          <span>
            {session.username} <span className="role-chip">{session.role ?? "?"}</span>
          </span>
          <button className="link-button" onClick={onLogout}>
            Sign out
          </button>
        </div>
      </header>

      <nav className="tabs">
        <button className={tab === "payments" ? "tab-active" : ""} onClick={() => setTab("payments")}>
          Payments
        </button>
        <button className={tab === "rings" ? "tab-active" : ""} onClick={() => setTab("rings")}>
          Fraud rings
        </button>
      </nav>

      {banner && (
        <div className="banner" onClick={() => setBanner(null)}>
          {banner}
        </div>
      )}

      {tab === "rings" && <FraudRingsView session={session} />}

      {tab === "payments" && (
      <div className="layout">
        <div className="list-pane">
          <div className="list-header">
            <h2>Payments</h2>
            <button className="link-button" onClick={refresh} disabled={loading}>
              {loading ? "Refreshing…" : "Refresh"}
            </button>
          </div>
          <ul className="payment-list">
            {payments.map((p) => (
              <li
                key={p.paymentId}
                className={p.paymentId === selectedId ? "selected" : ""}
                onClick={() => setSelectedId(p.paymentId)}
              >
                <div className="row-main">
                  <span className="e2e-id">{p.endToEndId}</span>
                  <FraudBadge payment={p} />
                </div>
                <div className="row-sub">
                  <span>
                    {p.amount} {p.currency}
                  </span>
                  <ReviewBadge status={p.reviewStatus} />
                </div>
              </li>
            ))}
            {!loading && payments.length === 0 && <li className="empty">No payments yet.</li>}
          </ul>
        </div>

        <div className="detail-pane">
          {selected ? (
            <PaymentDetail
              payment={selected}
              role={session.role}
              onAction={handleAction}
              actionPending={actionPending}
            />
          ) : (
            <p className="hint">Select a payment to see its fraud score and SHAP explanation.</p>
          )}
        </div>
      </div>
      )}
    </div>
  );
}

export default function App() {
  const [session, setSession] = useState<Session | null>(null);

  if (!session) {
    return <LoginForm onLogin={setSession} />;
  }
  return <Console session={session} onLogout={() => setSession(null)} />;
}
