"""Measures feature-computation latency against a REAL Redis, not fakeredis.

fakeredis is in-process Python with no network round trip at all — using it
here would report a number with no relationship to what the 20ms p99 budget
actually has to survive (a real socket, real serialisation, a real container
boundary in docker-compose). This test is skipped, not failed, when no Redis
is reachable, so the rest of the suite stays runnable without Docker; see
README for how to run it for real.
"""

from __future__ import annotations

import time

import pytest
import redis

from fraud_service.features.engineering import FeatureEngineer, PaymentEvent
from fraud_service.features.history import AccountHistory
from fraud_service.features.velocity import VelocityFeatures

N_SAMPLES = 500


@pytest.fixture
def real_redis():
    client = redis.from_url("redis://localhost:6379/15", decode_responses=True)  # db 15: scratch, not app data
    try:
        client.ping()
    except redis.ConnectionError:
        pytest.skip("no local Redis reachable on localhost:6379 - run `docker compose up -d redis` first")
    yield client
    client.flushdb()


def test_feature_computation_p99_under_20ms(real_redis) -> None:
    engineer = FeatureEngineer(velocity=VelocityFeatures(real_redis), history=AccountHistory(real_redis))

    latencies_ms: list[float] = []
    for i in range(N_SAMPLES):
        event = PaymentEvent(
            amount=float(100 + i),
            hour_of_day=i % 24,
            is_cash_out=(i % 2 == 0),
            # A mix of brand-new and repeat accounts, so the benchmark
            # exercises both the cheap cold-start path and the path that
            # actually reads accumulated Redis state - not just whichever one
            # is faster.
            debtor_account=f"BENCH-ORIG-{i % 50}",
            creditor_account=f"BENCH-DEST-{i % 10}",
            debtor_balance_before=10_000.0,
            creditor_balance_before=5_000.0,
        )

        start = time.perf_counter()
        engineer.compute(event)
        latencies_ms.append((time.perf_counter() - start) * 1000)
        engineer.record(event)

    latencies_ms.sort()
    p50 = latencies_ms[len(latencies_ms) // 2]
    p95 = latencies_ms[int(len(latencies_ms) * 0.95)]
    p99 = latencies_ms[int(len(latencies_ms) * 0.99)]

    print(f"\nfeature computation latency over {N_SAMPLES} calls (real Redis):")
    print(f"  p50={p50:.3f}ms  p95={p95:.3f}ms  p99={p99:.3f}ms")

    assert p99 < 20.0, f"p99 feature latency {p99:.3f}ms exceeds the 20ms budget"
