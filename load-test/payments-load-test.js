// k6 load test against the live docker-compose stack (payment-api on :8080).
//
// Run: docker compose up -d, then from the repo root:
//   docker run --rm -i --network atlas-payments_default \
//     -e BASE_URL=http://payment-api:8080 \
//     -v "$(pwd)/load-test:/scripts" grafana/k6 run /scripts/payments-load-test.js
// or, from the host (no --network needed):
//   docker run --rm -i -v "$(pwd)/load-test:/scripts" grafana/k6 run /scripts/payments-load-test.js
//
// The numbers this produces (p50/p95/p99, req/s, error rate) are the ones
// quoted in the README - regenerate them with this exact command rather
// than re-typing old numbers by hand.
import http from "k6/http";
import { check } from "k6";
import { Counter, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

// A real bearer token, not the login endpoint, is on the hot path here on
// purpose: /auth/token is a separate, cheap BCrypt-verify-then-sign
// operation with its own cost profile, and mixing it into the payments
// benchmark would blur what "payments p95" actually measures. Auth happens
// once per VU in setup(), matching how a real client would cache a token
// for its ~15-minute TTL rather than re-authenticating per request.
const DEBTOR_POOL_SIZE = 20;
const FUND_AMOUNT = 1_000_000;

const acceptedRate = new Rate("atlas_accepted_rate");
const rejectedLedgerCount = new Counter("atlas_rejected_ledger_total");

function authenticate(username, password) {
  const res = http.post(
    `${BASE_URL}/auth/token`,
    JSON.stringify({ username, password }),
    { headers: { "Content-Type": "application/json" } },
  );
  if (res.status !== 200) {
    throw new Error(`auth failed for ${username}: ${res.status} ${res.body}`);
  }
  return res.json("accessToken");
}

// Local UUID v4 generator - avoids a runtime dependency on an external CDN
// (jslib.k6.io) so this script's results stay reproducible offline.
function uuidv4() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function setup() {
  const supervisorToken = authenticate("supervisor1", "supervisor-demo-password");
  const debtorAccounts = [];
  for (let i = 0; i < DEBTOR_POOL_SIZE; i++) {
    const accountNumber = `LOAD-TEST-DEBTOR-${i}`;
    const res = http.post(
      `${BASE_URL}/ops/funding`,
      JSON.stringify({ accountNumber, amount: FUND_AMOUNT, currency: "USD" }),
      {
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${supervisorToken}`,
        },
      },
    );
    if (res.status !== 200) {
      throw new Error(`funding ${accountNumber} failed: ${res.status} ${res.body}`);
    }
    debtorAccounts.push(accountNumber);
  }

  const analystToken = authenticate("analyst1", "analyst-demo-password");
  return { analystToken, debtorAccounts };
}

export const options = {
  scenarios: {
    payments: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "20s", target: 20 },
        { duration: "40s", target: 20 },
        { duration: "10s", target: 0 },
      ],
    },
  },
  thresholds: {
    // The README's load-test table is generated from a run that passed
    // these; a run that doesn't pass them shouldn't be the one quoted.
    http_req_duration: ["p(95)<500", "p(99)<1000"],
    http_req_failed: ["rate<0.01"],
  },
};

export default function (data) {
  // VU-sharded, not random, so debtor accounts see genuine concurrent
  // writes from more than one VU (exercising the OPTIMISTIC_FORCE_INCREMENT
  // path on Account) without every VU contending for the same one row.
  const debtorAccount = data.debtorAccounts[__VU % data.debtorAccounts.length];
  const body = JSON.stringify({
    // Max 35 chars - ISO 20022's EndToEndIdentification35Text, enforced by
    // EndToEndIdRule (R04). A full UUID plus a "load-<vu>-<iter>-" prefix
    // blows past that, which is how this script's first run discovered the
    // rule the hard way: every request came back REJECTED_VALIDATION.
    endToEndId: `LT${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`,
    instructedAmount: 10.0,
    instructedCurrency: "USD",
    debtorAgent: "BANKUS33",
    creditorAgent: "BANKGB2L",
    debtorAccount,
    creditorAccount: "LOAD-TEST-CREDITOR",
    debtorCountry: "US",
    chargeBearer: "SHAR",
    settlementDate: "2026-09-14",
  });

  const res = http.post(`${BASE_URL}/payments`, body, {
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${data.analystToken}`,
      "Idempotency-Key": uuidv4(),
    },
  });

  const isHttp200 = check(res, {
    "status is 200": (r) => r.status === 200,
  });

  if (isHttp200) {
    const decision = res.json("status");
    acceptedRate.add(decision === "ACCEPTED");
    if (decision === "REJECTED") {
      const rejections = res.json("rejections");
      if (rejections && rejections.some((r) => r.code && r.code.startsWith("ATLAS-L"))) {
        rejectedLedgerCount.add(1);
      }
    }
  } else {
    acceptedRate.add(false);
  }
}
