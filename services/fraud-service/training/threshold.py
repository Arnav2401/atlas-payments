"""Cost-based decision threshold.

The brief's instruction is specific: not the 0.5 default, and justified in
cost terms, not accuracy terms. This module implements that literally —
sweep candidate thresholds, compute the actual expected dollar cost of each,
and pick the minimum. The two costs and where they come from:

  - A missed fraud (false negative) costs the fraud amount itself. This is
    read directly from each test-set transaction's own `amount` — not a
    single average applied uniformly — so a threshold sweep genuinely
    reflects "how much money got through", not "how many events got through".

  - A false alarm (false positive) costs a fixed analyst review, because that
    is the actual operational cost a bank incurs: a human looks at a payment
    that turns out to be legitimate. This number IS an assumption, not a
    measurement — the dataset has no review-cost field, so REVIEW_COST_USD
    below is asserted and must be revisited before this number means anything
    outside a resume project. It is a policy parameter, not a constant, for
    exactly that reason.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

# Assumption, not a measurement — see module docstring. A single analyst
# review of a flagged payment. Revisit before this threshold means anything
# in a real deployment; it belongs in configuration, not a Python constant,
# the same way R08's settlement window does on the Java side.
REVIEW_COST_USD = 25.0


@dataclass(frozen=True)
class ThresholdChoice:
    threshold: float
    expected_cost_usd: float
    false_negative_amount_usd: float
    false_positive_review_cost_usd: float
    precision: float
    recall: float
    flagged_count: int
    missed_fraud_count: int


def select_cost_minimising_threshold(
    y_true: np.ndarray, y_score: np.ndarray, amounts: np.ndarray, review_cost_usd: float = REVIEW_COST_USD
) -> tuple[ThresholdChoice, pd.DataFrame]:
    """Sweeps 200 thresholds in (0, 1) and returns the one with the lowest
    total expected cost on the given (labelled) set, plus the full sweep for
    plotting/reporting.

    `amounts` must be the raw transaction amount for every row in `y_true` —
    this is what makes the cost real money, not an event count.
    """
    candidates = np.linspace(0.01, 0.99, 200)
    rows = []

    for t in candidates:
        flagged = y_score >= t
        false_negative = (~flagged) & (y_true == 1)
        false_positive = flagged & (y_true == 0)
        true_positive = flagged & (y_true == 1)

        fn_cost = amounts[false_negative].sum()
        fp_cost = false_positive.sum() * review_cost_usd
        total_cost = fn_cost + fp_cost

        precision = true_positive.sum() / flagged.sum() if flagged.sum() > 0 else float("nan")
        recall = true_positive.sum() / (y_true == 1).sum()

        rows.append(
            {
                "threshold": t,
                "expected_cost_usd": total_cost,
                "false_negative_amount_usd": fn_cost,
                "false_positive_review_cost_usd": fp_cost,
                "precision": precision,
                "recall": recall,
                "flagged_count": int(flagged.sum()),
                "missed_fraud_count": int(false_negative.sum()),
            }
        )

    sweep = pd.DataFrame(rows)
    best = sweep.loc[sweep["expected_cost_usd"].idxmin()]

    choice = ThresholdChoice(
        threshold=float(best["threshold"]),
        expected_cost_usd=float(best["expected_cost_usd"]),
        false_negative_amount_usd=float(best["false_negative_amount_usd"]),
        false_positive_review_cost_usd=float(best["false_positive_review_cost_usd"]),
        precision=float(best["precision"]),
        recall=float(best["recall"]),
        flagged_count=int(best["flagged_count"]),
        missed_fraud_count=int(best["missed_fraud_count"]),
    )
    return choice, sweep
