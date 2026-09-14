from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    redis_url: str = os.environ.get("FRAUD_REDIS_URL", "redis://localhost:6379/0")
    model_dir: str = os.environ.get("FRAUD_MODEL_DIR", "models")
    kafka_bootstrap_servers: str | None = os.environ.get("ATLAS_KAFKA_BOOTSTRAP_SERVERS")

    neo4j_uri: str | None = os.environ.get("ATLAS_NEO4J_URI")
    neo4j_user: str = os.environ.get("ATLAS_NEO4J_USER", "neo4j")
    neo4j_password: str = os.environ.get("ATLAS_NEO4J_PASSWORD", "atlas-demo-password")


settings = Settings()
