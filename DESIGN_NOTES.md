# Design notes

Decision log. Newest first. Every non-obvious choice gets an entry, written
when the decision is made — not reconstructed later.

Format:
  ## YYYY-MM-DD — <decision>
  **Chose:** ...
  **Over:** ...
  **Because:** ...
  **What broke:** ...

---

## TODO — write this entry yourself (2026-09-14 scope change)

The pacs.008 decision below was superseded today: real ISO 20022 / SWIFT
compliance is now out of scope, and the API takes ISO-20022-flavoured JSON
instead. The code and docs already reflect that.

This entry is deliberately blank. Claude scaffolded the code; it did not make
this call and should not write the reasoning. Fill in chose / over / because /
what broke in your own words — "what broke" is the unfakeable field and it is
exactly what gets asked.

---

## TODO — write this entry yourself (2026-09-14, JWT auth + RBAC)

M5 added self-issued JWTs (HMAC-SHA256, two roles) instead of delegating to a
real identity provider (Okta/Cognito/Keycloak), and two hardcoded demo users
instead of a real user directory. Both are real, defensible trade-offs for a
project at this scale, not oversights — but they are also exactly the kind of
simplification an interviewer will probe ("why not OAuth2 against a real
IdP?", "how would this change with real users?"). Fill in chose / over /
because / what broke yourself — see JwtService's and DemoUserStore's own
javadoc for the reasoning already written into the code, and decide in your
own words whether you'd defend it the same way.

---

## TODO — write this entry yourself (2026-09-14, Neo4j + GDS for M6)

Chose Neo4j Community Edition + the free Graph Data Science plugin
(Louvain, degree centrality, PageRank) over doing the same analysis
in-process with a Python graph library (networkx / igraph / graph-tool),
which would have needed no new service in docker-compose at all. What made
this worth a fourth datastore, and what you'd say if asked "why not just
pandas and networkx" — write it in your own words.

---

## TODO — write this entry yourself (2026-09-14, planted vs. mined fraud rings)

graph/plant_rings.py plants synthetic fraud rings rather than trying to mine
real ones out of PaySim — the dataset's own fraud mechanism (see
docs/sources.md) has no naturally occurring, *labelled* multi-account ring to
find. Chose to measure detection against a known ground truth instead. What
this claim does and does not prove about the detector's real-world
recall — write it yourself; it is a fair question and the honest answer is
more nuanced than "it works."

---

## TODO — write this entry yourself (2026-09-14, a second training script)

training/train_graph_uplift.py is a separate script from training/train.py,
not a --graph-features flag on it — the M3 baseline (models/metrics.json,
the number in the README's main metrics table) stays reproducible by that
exact, unmodified command regardless of what M6 does. Whether that was the
right call, and what the alternative (one script, one flag) would have cost
or saved — write it yourself.

---

## 2026-08-20 — Core message format
**Chose:** ISO 20022 `pacs.008` (FIToFICustomerCreditTransfer) as the system's
core inbound message.
**Over:** SWIFT MT103, the legacy equivalent.
**Because:** MT payment instructions were retired from the SWIFT network in
November 2025, and from 14 November 2026 SR2026 requires banks to natively
originate and process ISO 20022 under tighter validation rules. Building
against MT103 would model a system that no longer exists.
**What broke:** n/a — no code yet.
**Superseded:** 2026-09-14, see the entry above.
