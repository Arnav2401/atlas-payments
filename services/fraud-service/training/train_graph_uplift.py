"""M6's measured claim: does adding graph features to the M3 model change PR-AUC.

    # 1. Build the training-period graph (graph/build_graph.py) and compute
    #    its Louvain/degree/PageRank properties (this script does the latter
    #    itself, via graph.graph_features.compute_and_write_algorithms).
    # 2. uv run python -m training.train_graph_uplift --data /path/to/paysim.csv

Deliberately a separate script from training/train.py, not a --graph-features
flag bolted onto it: train.py's own output (models/model.json,
models/metrics.json) is what the main README's model metrics table and the
production fraud-service actually serve, and that baseline must stay
reproducible by that exact, unmodified command. This script re-derives its
own baseline internally (below) as a same-run sanity check that the
comparison is apples-to-apples, rather than diffing against a committed
metrics.json that could drift out of sync with a code change elsewhere.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import xgboost as xgb
from sklearn.metrics import average_precision_score

from fraud_service.feature_spec import FEATURE_NAMES, TARGET_COLUMN
from graph.graph_features import GRAPH_FEATURE_NAMES, compute_and_write_algorithms, fetch_account_features, join_graph_features
from graph.neo4j_client import add_connection_args, connect
from training.data import drop_identifiers, load_raw, scope_to_fraud_eligible_types, temporal_split
from training.features import add_causal_features
from training.train import SEED, precision_at_recall


def fit_and_score(x_train, y_train, x_test, y_test) -> tuple[xgb.XGBClassifier, float]:
    scale_pos_weight = (y_train == 0).sum() / (y_train == 1).sum()
    model = xgb.XGBClassifier(
        n_estimators=300,
        max_depth=6,
        learning_rate=0.1,
        scale_pos_weight=scale_pos_weight,
        eval_metric="aucpr",
        random_state=SEED,
        n_jobs=-1,
    )
    model.fit(x_train, y_train)
    y_score = model.predict_proba(x_test)[:, 1]
    return model, float(average_precision_score(y_test, y_score)), y_score


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, help="Path to the raw PaySim CSV")
    parser.add_argument("--out-dir", default="models", help="Where to write graph_uplift_metrics.json")
    parser.add_argument("--skip-gds", action="store_true", help="Reuse community/inDegree/pagerank already written to Neo4j")
    add_connection_args(parser)
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    t0 = time.time()
    raw = load_raw(args.data)
    scoped = scope_to_fraud_eligible_types(raw)
    featured = add_causal_features(scoped)
    train_df, test_df = temporal_split(featured, train_fraction=0.8)
    print(f"temporal split: {len(train_df):,} train rows, {len(test_df):,} test rows ({time.time() - t0:.1f}s)")

    driver = connect(args.neo4j_uri, args.neo4j_user, args.neo4j_password)
    try:
        if not args.skip_gds:
            print("running Louvain / degree / PageRank on the training-period graph...")
            from graphdatascience import GraphDataScience

            gds = GraphDataScience(driver)
            compute_and_write_algorithms(gds)
        account_features = fetch_account_features(driver)
    finally:
        driver.close()
    print(f"graph features available for {len(account_features):,} accounts")

    train_df = join_graph_features(train_df, account_features)
    test_df = join_graph_features(test_df, account_features)
    graph_coverage_train = train_df["dest_graph_in_degree"].notna().mean()
    graph_coverage_test = test_df["dest_graph_in_degree"].notna().mean()
    print(f"graph feature coverage: {graph_coverage_train:.1%} of train rows, {graph_coverage_test:.1%} of test rows")

    train_df, test_df = drop_identifiers(train_df), drop_identifiers(test_df)

    x_train_base, y_train = train_df[FEATURE_NAMES], train_df[TARGET_COLUMN]
    x_test_base, y_test = test_df[FEATURE_NAMES], test_df[TARGET_COLUMN]

    augmented_names = FEATURE_NAMES + GRAPH_FEATURE_NAMES
    x_train_aug = train_df[augmented_names]
    x_test_aug = test_df[augmented_names]

    print("\ntraining baseline (M3 feature set, re-derived for a same-run comparison)...")
    _, pr_auc_baseline, y_score_base = fit_and_score(x_train_base, y_train, x_test_base, y_test)
    precision_base = precision_at_recall(y_test.to_numpy(), y_score_base, 0.80)
    print(f"baseline PR-AUC: {pr_auc_baseline:.4f}  (committed models/metrics.json: 0.9964)")

    print("\ntraining graph-augmented (M3 feature set + graph features)...")
    model_aug, pr_auc_augmented, y_score_aug = fit_and_score(x_train_aug, y_train, x_test_aug, y_test)
    precision_aug = precision_at_recall(y_test.to_numpy(), y_score_aug, 0.80)
    print(f"augmented PR-AUC: {pr_auc_augmented:.4f}")

    delta = pr_auc_augmented - pr_auc_baseline
    print(f"\nPR-AUC delta: {delta:+.4f} ({'improvement' if delta > 0 else 'no improvement' if delta == 0 else 'regression'})")

    import shap

    explainer = shap.TreeExplainer(model_aug)
    sample = x_test_aug.sample(n=min(5_000, len(x_test_aug)), random_state=SEED)
    shap_values = explainer.shap_values(sample)
    import pandas as pd

    mean_abs_shap = pd.Series(np.abs(shap_values).mean(axis=0), index=augmented_names).sort_values(ascending=False)
    print("\ntop 8 features by mean |SHAP|, augmented model:")
    print(mean_abs_shap.head(8).to_string())

    metrics = {
        "seed": SEED,
        "train_rows": len(train_df),
        "test_rows": len(test_df),
        "graph_feature_coverage": {"train": float(graph_coverage_train), "test": float(graph_coverage_test)},
        "baseline": {
            "feature_names": FEATURE_NAMES,
            "pr_auc": pr_auc_baseline,
            "precision_at_80pct_recall": precision_base,
        },
        "graph_augmented": {
            "feature_names": augmented_names,
            "pr_auc": pr_auc_augmented,
            "precision_at_80pct_recall": precision_aug,
        },
        "pr_auc_delta": delta,
        "top_8_features_by_mean_abs_shap_augmented": mean_abs_shap.head(8).to_dict(),
    }
    metrics_path = out_dir / "graph_uplift_metrics.json"
    metrics_path.write_text(json.dumps(metrics, indent=2))
    print(f"\nwrote {metrics_path}")


if __name__ == "__main__":
    main()
