# Expects models/ to already hold a trained model. The PaySim CSV it comes from
# is not in the repo (docs/sources.md), so the image cannot train itself: run
# `uv run python -m training.train --data <path>` once before building.
FROM python:3.12-slim AS runtime

# XGBoost's OpenMP runtime.
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir uv

WORKDIR /app
COPY pyproject.toml uv.lock ./
# Dependencies only, so they cache in their own layer. `uv sync` also builds
# the local package, which needs src/ - hence the second sync after the COPYs.
RUN uv sync --frozen --no-dev --no-install-project

COPY src/ src/
COPY training/ training/
COPY graph/ graph/
COPY models/ models/
RUN uv sync --frozen --no-dev

ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8001

CMD ["uvicorn", "fraud_service.main:app", "--host", "0.0.0.0", "--port", "8001"]
