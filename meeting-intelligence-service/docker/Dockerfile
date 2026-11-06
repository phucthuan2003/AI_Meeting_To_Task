FROM nvidia/cuda:12.3.2-cudnn9-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/app/models_cache \
    MPLCONFIGDIR=/tmp/matplotlib

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip ca-certificates libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 app \
    && mkdir -p /app/models_cache /app/benchmarks/results \
    && chown -R app:app /app
COPY requirements.txt .
RUN python3 -m pip install --no-cache-dir -r requirements.txt
COPY src src
COPY benchmarks benchmarks

USER app
EXPOSE 8000
CMD ["python3", "-m", "uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8000", "--ws", "websockets", "--ws-max-size", "64000", "--ws-max-queue", "8", "--limit-concurrency", "32"]
