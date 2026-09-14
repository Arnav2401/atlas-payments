from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    redis_url: str = os.environ.get("FRAUD_REDIS_URL", "redis://localhost:6379/0")
    model_dir: str = os.environ.get("FRAUD_MODEL_DIR", "models")
    # Unset by default, not defaulted to "localhost:9092" — the M3 synchronous
    # /score endpoint must keep working with no Kafka running at all, and a
    # silently-wrong default bootstrap address would fail confusingly instead
    # of the async path simply not starting. See main.py's lifespan.
    kafka_bootstrap_servers: str | None = os.environ.get("ATLAS_KAFKA_BOOTSTRAP_SERVERS")


settings = Settings()
