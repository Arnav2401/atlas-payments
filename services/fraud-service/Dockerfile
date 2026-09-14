# Expects models/model.json and models/metrics.json to already exist in the
# build context — see README.md. The 494MB PaySim CSV they are trained from is
# not distributed with this repo (see docs/sources.md), so this image cannot
# train itself at build time the way `flyway migrate` self-provisions the
# ledger schema in payment-api's image. Run `uv run python -m training.train
# --data <path>` once, locally, before `docker compose up` — the image will
# fail fast at startup (FraudPredictor's constructor) rather than silently
# serving with no model if that step was skipped.
FROM python:3.12-slim AS runtime

# libgomp1 is XGBoost's OpenMP runtime on Linux - the same dependency that
# needed a manual libomp.dylib workaround on macOS during local development
# (see README), except here it is one apt-get away instead of a workaround,
# because the base image has a real package manager.
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir uv

WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY src/ src/
COPY training/ training/
COPY models/ models/

ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8001

CMD ["uvicorn", "fraud_service.main:app", "--host", "0.0.0.0", "--port", "8001"]
