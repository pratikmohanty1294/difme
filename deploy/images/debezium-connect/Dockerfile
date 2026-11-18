# Kafka Connect for Debezium CDC (PDF §5.5), built in CI instead of by Strimzi's in-cluster build: the cluster
# has no push credentials and no push registry, and a prebuilt, scanned, signed image is what production runs anyway.
# Base: the Strimzi Kafka image matching the operator (0.51.0) and Kafka (4.2.0) versions.
FROM quay.io/strimzi/kafka:0.51.0-kafka-4.2.0
ARG DEBEZIUM=3.7.1.Final
ARG APICURIO=3.3.4
USER root:root
# Cruise Control (bundled Netty) and kafka_exporter (Go stdlib) are never used by Connect and carry CRITICAL CVEs
# the gate blocks; removing them also shrinks the image. ca-certificates gets the distro fix.
RUN set -eux; rm -rf /opt/cruise-control /opt/kafka-exporter; \
    microdnf -y update ca-certificates && microdnf clean all
RUN set -eux; m=https://repo1.maven.org/maven2; \
    mkdir -p /opt/kafka/plugins/debezium-postgres /opt/kafka/plugins/apicurio-converters; \
    curl -fsSL "$m/io/debezium/debezium-connector-postgres/$DEBEZIUM/debezium-connector-postgres-$DEBEZIUM-plugin.tar.gz" \
      | tar -xz -C /opt/kafka/plugins/debezium-postgres; \
    curl -fsSL "$m/io/apicurio/apicurio-registry-distro-connect-converter/$APICURIO/apicurio-registry-distro-connect-converter-$APICURIO.tar.gz" \
      | tar -xz -C /opt/kafka/plugins/apicurio-converters
USER 1001
