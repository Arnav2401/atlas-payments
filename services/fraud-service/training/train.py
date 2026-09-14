from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import pandas as pd
import shap
import xgboost as xgb
from sklearn.metrics import average_precision_score, precision_recall_curve

from fraud_service.feature_spec import FEATURE_NAMES, TARGET_COLUMN
from training.data import drop_identifiers, load_raw, scope_to_fraud_eligible_types, temporal_split
from training.features import add_causal_features
from training.threshold import REVIEW_COST_USD, select_cost_minimising_threshold

SEED = 42
REPORTED_RECALL_OPERATING_POINT = 0.80  # for the precision-at-fixed-recall headline number
GLOBAL_SHAP_SAMPLE_SIZE = 5_000


def precision_at_recall(y_true: np.ndarray, y_score: np.ndarray, recall_target: float) -> float:
    precision, recall, _ = precision_recall_curve(y_true, y_score)
    eligible = recall >= recall_target
    if not eligible.any():
        return float("nan")
    return float(precision[eligible].max())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True, help="Path to the raw PaySim CSV")
    parser.add_argument("--out-dir", default="models", help="Where to write the model + metrics")
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    t0 = time.time()
    raw = load_raw(args.data)
    scoped = scope_to_fraud_eligible_types(raw)
    print(f"loaded {len(raw):,} rows, scoped to {len(scoped):,} (TRANSFER + CASH_OUT only)")

    featured = add_causal_features(scoped)
    train_df, test_df = temporal_split(featured, train_fraction=0.8)
    train_df, test_df = drop_identifiers(train_df), drop_identifiers(test_df)
    print(f"temporal split: {len(train_df):,} train rows, {len(test_df):,} test rows")
    print(f"feature engineering + split: {time.time() - t0:.1f}s")

    x_train, y_train = train_df[FEATURE_NAMES], train_df[TARGET_COLUMN]
    x_test, y_test = test_df[FEATURE_NAMES], test_df[TARGET_COLUMN]
    amounts_test = test_df["amount"].to_numpy()

    scale_pos_weight = (y_train == 0).sum() / (y_train == 1).sum()
    print(f"train fraud rate: {y_train.mean():.5%}  scale_pos_weight: {scale_pos_weight:.1f}")

    model = xgb.XGBClassifier(
        n_estimators=300,
        max_depth=6,
        learning_rate=0.1,
        scale_pos_weight=scale_pos_weight,
        eval_metric="aucpr",
        random_state=SEED,
        n_jobs=-1,
    )

    t0 = time.time()
    model.fit(x_train, y_train)
    print(f"training: {time.time() - t0:.1f}s")

    y_score = model.predict_proba(x_test)[:, 1]

    pr_auc = average_precision_score(y_test, y_score)
    precision_at_recall_value = precision_at_recall(y_test.to_numpy(), y_score, REPORTED_RECALL_OPERATING_POINT)
    print(f"PR-AUC: {pr_auc:.4f}")
    print(f"precision at {REPORTED_RECALL_OPERATING_POINT:.0%} recall: {precision_at_recall_value:.4f}")

    choice, sweep = select_cost_minimising_threshold(y_test.to_numpy(), y_score, amounts_test)
    print(f"cost-minimising threshold: {choice.threshold:.3f}  expected cost: ${choice.expected_cost_usd:,.0f}")
    print(
        f"  at that threshold: precision={choice.precision:.3f} recall={choice.recall:.3f} "
        f"flags {choice.flagged_count:,} payments, misses {choice.missed_fraud_count} of "
        f"{int(y_test.sum())} frauds"
    )

    cost_flag_nothing = amounts_test[y_test.to_numpy() == 1].sum()
    cost_flag_everything = (y_test == 0).sum() * REVIEW_COST_USD
    assert choice.expected_cost_usd <= cost_flag_nothing, "threshold search must beat flagging nothing"
    assert choice.expected_cost_usd <= cost_flag_everything, "threshold search must beat flagging everything"
    print(f"  sanity: flag-nothing cost=${cost_flag_nothing:,.0f}  flag-everything cost=${cost_flag_everything:,.0f}")

    explainer = shap.TreeExplainer(model)
    shap_sample = x_test.sample(n=min(GLOBAL_SHAP_SAMPLE_SIZE, len(x_test)), random_state=SEED)
    shap_values = explainer.shap_values(shap_sample)
    mean_abs_shap = pd.Series(np.abs(shap_values).mean(axis=0), index=FEATURE_NAMES).sort_values(ascending=False)
    print("\ntop 5 features by mean |SHAP|:")
    print(mean_abs_shap.head(5).to_string())

    model_path = out_dir / "model.json"
    model.save_model(model_path)

    metrics = {
        "seed": SEED,
        "train_rows": len(train_df),
        "test_rows": len(test_df),
        "train_fraud_rate": float(y_train.mean()),
        "test_fraud_rate": float(y_test.mean()),
        "pr_auc": float(pr_auc),
        "precision_at_recall": {"recall_target": REPORTED_RECALL_OPERATING_POINT, "precision": precision_at_recall_value},
        "cost_based_threshold": {
            "threshold": choice.threshold,
            "review_cost_usd": REVIEW_COST_USD,
            "expected_cost_usd": choice.expected_cost_usd,
            "precision": choice.precision,
            "recall": choice.recall,
            "flagged_count": choice.flagged_count,
            "missed_fraud_count": choice.missed_fraud_count,
            "total_frauds_in_test": int(y_test.sum()),
            "cost_flag_nothing_usd": float(cost_flag_nothing),
            "cost_flag_everything_usd": float(cost_flag_everything),
        },
        "top_5_features_by_mean_abs_shap": mean_abs_shap.head(5).to_dict(),
        "feature_names": FEATURE_NAMES,
    }
    (out_dir / "metrics.json").write_text(json.dumps(metrics, indent=2))
    sweep.to_csv(out_dir / "threshold_sweep.csv", index=False)

    print(f"\nwrote {model_path}, {out_dir / 'metrics.json'}, {out_dir / 'threshold_sweep.csv'}")


if __name__ == "__main__":
    main()
