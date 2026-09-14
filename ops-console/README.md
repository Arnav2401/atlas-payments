# atlas-payments ops console

Minimal React + TypeScript SPA against `payment-api`: payment list, fraud
score, top SHAP features, and the review/clear/escalate workflow, gated by
the same JWT the API itself issues.

See the main [repo README](../README.md#ops-console) for how to run it —
either as part of `docker compose up` (`localhost:3002`) or standalone via
`npm run dev` (`localhost:5173`) against a payment-api on the host.

No routing, no state library, no UI kit: one `App.tsx`, a thin `fetch`
wrapper in `api.ts`, and plain CSS. The `PaymentDetail` component's
Clear/Escalate buttons are deliberately *not* hidden for a non-`SUPERVISOR`
role — see the comment there for why: the point is proving the server's
`@PreAuthorize` actually enforces the boundary, not building a UI that
merely looks like it does.
