# Runtime image for every DIFME Flink job (PDF §5.5). Context: repo root (after mvn package).
FROM flink:2.2.1-java21
USER root
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
USER flink
COPY --chown=flink:flink analytics/flink-jobs/target/difme-flink-jobs.jar /opt/flink/usrlib/difme-flink-jobs.jar
COPY --chown=flink:flink processes/dmn/payment-limits.dmn /opt/flink/usrlib/payment-limits.dmn
