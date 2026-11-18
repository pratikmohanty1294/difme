# Runtime image for every DIFME Flink job (PDF §5.5). Context: repo root (after mvn package).
FROM flink:2.2.1-java21
USER root
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
USER flink
# S3 filesystem for checkpoints/savepoints (s3://flink/...). Flink 2.x images no longer bundle it under /opt/flink/opt.
ADD --chown=flink:flink https://repo1.maven.org/maven2/org/apache/flink/flink-s3-fs-presto/2.2.1/flink-s3-fs-presto-2.2.1.jar /opt/flink/plugins/s3-fs-presto/
COPY --chown=flink:flink analytics/flink-jobs/target/difme-flink-jobs.jar /opt/flink/usrlib/difme-flink-jobs.jar
COPY --chown=flink:flink processes/dmn/payment-limits.dmn /opt/flink/usrlib/payment-limits.dmn
