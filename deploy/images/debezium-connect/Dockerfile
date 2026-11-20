# Kafka Connect for Debezium CDC (PDF §5.5), built in CI instead of by Strimzi's in-cluster build: the cluster
# has no push credentials and no push registry, and a prebuilt, scanned, signed image is what production runs anyway.
# Base: the Strimzi Kafka image matching the operator (0.51.0) and Kafka (4.2.0) versions.
FROM quay.io/strimzi/kafka:0.51.0-kafka-4.2.0
ARG DEBEZIUM=3.7.1.Final
ARG APICURIO=3.3.4
USER root:root
# Cruise Control (bundled Netty) and kafka_exporter (Go stdlib) are never used by Connect and carry CRITICAL CVEs
# the gate blocks; removing them also shrinks the image. ca-certificates and the JDK get the distro fixes
# (2026-11-20: CVE-2026-114938 in java-21-openjdk-headless 21.0.10, fixed in 21.0.13).
RUN set -eux; rm -rf /opt/cruise-control /opt/kafka-exporter; \
    microdnf -y update ca-certificates 'java-21-openjdk*' && microdnf clean all
# A public Maven Central mirror: repo1 returned HTTP 429 to CI runners (2026-11-20).
RUN set -eux; m=https://maven-central.storage-download.googleapis.com/maven2; \
    mkdir -p /opt/kafka/plugins/debezium-postgres /opt/kafka/plugins/apicurio-converters; \
    curl -fsSL "$m/io/debezium/debezium-connector-postgres/$DEBEZIUM/debezium-connector-postgres-$DEBEZIUM-plugin.tar.gz" \
      | tar -xz -C /opt/kafka/plugins/debezium-postgres; \
    curl -fsSL "$m/io/apicurio/apicurio-registry-distro-connect-converter/$APICURIO/apicurio-registry-distro-connect-converter-$APICURIO.tar.gz" \
      | tar -xz -C /opt/kafka/plugins/apicurio-converters
USER 1001
