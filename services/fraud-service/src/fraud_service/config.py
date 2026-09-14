from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    redis_url: str = os.environ.get("FRAUD_REDIS_URL", "redis://localhost:6379/0")
    model_dir: str = os.environ.get("FRAUD_MODEL_DIR", "models")


settings = Settings()
