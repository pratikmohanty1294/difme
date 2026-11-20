# DIFME Feast feature server (owner decision 2026-11-20: minimal Feast). Context: repo root.
# Registry in YugabyteDB (SQL registry), online store on Valkey, offline store on Iceberg through Trino.
FROM python:3.12-slim
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1 PYTHONDONTWRITEBYTECODE=1
RUN pip install "feast[redis,postgres,trino]==0.47.0" "psycopg[binary]==3.2.9" \
    && useradd -u 10001 -m feast
COPY ml/feast /feature_repo
WORKDIR /feature_repo
USER 10001
EXPOSE 6566
CMD ["feast", "serve", "--host", "0.0.0.0", "--port", "6566"]
