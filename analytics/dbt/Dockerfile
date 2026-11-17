# dbt (Apache-2.0) + Trino adapter, with the DIFME project baked in. Context: analytics/dbt.
FROM python:3.12-slim
RUN pip install --no-cache-dir dbt-core==1.12.7 dbt-trino==1.12.0 && useradd -u 1000 dbt
COPY --chown=1000 . /dbt
USER 1000
WORKDIR /dbt
ENTRYPOINT ["dbt"]
