// k6 load test against the running stack. The README's p50/p95/p99 and
// throughput numbers come from this script:
//   docker run --rm -i --network atlas-payments_default \
//     -e BASE_URL=http://payment-api:8080 \
//     -v "$(pwd)/load-test:/scripts" grafana/k6 run /scripts/payments-load-test.js
import http from "k6/http";
import { check } from "k6";
import { Counter, Rate } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

const DEBTOR_POOL_SIZE = 20;
const FUND_AMOUNT = 1_000_000;

// Computed, not pinned: R08 rejects a settlement date before today, so a
// hardcoded one silently turns every request into REJECTED_VALIDATION.
const SETTLEMENT_DATE = new Date().toISOString().slice(0, 10);

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

// Local, so the script doesn't depend on jslib.k6.io being reachable.
function uuidv4() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// Auth once here rather than per request: /auth/token has its own cost profile
// and mixing it in would blur what "payments p95" measures.
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
    // The README quotes a run that passed these.
    http_req_duration: ["p(95)<500", "p(99)<1000"],
    http_req_failed: ["rate<0.01"],
  },
};

export default function (data) {
  // Sharded by VU so accounts see real concurrent writes without every VU
  // piling onto one row.
  const debtorAccount = data.debtorAccounts[__VU % data.debtorAccounts.length];
  const body = JSON.stringify({
    // Max 35 chars (R04). A full UUID plus a prefix overflows it, and every
    // request comes back REJECTED_VALIDATION.
    endToEndId: `LT${__VU}-${__ITER}-${uuidv4().slice(0, 8)}`,
    instructedAmount: 10.0,
    instructedCurrency: "USD",
    debtorAgent: "BANKUS33",
    creditorAgent: "BANKGB2L",
    debtorAccount,
    creditorAccount: "LOAD-TEST-CREDITOR",
    debtorCountry: "US",
    chargeBearer: "SHAR",
    settlementDate: SETTLEMENT_DATE,
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
