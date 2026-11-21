# Apache Fineract (CBS simulator) built from the vendored source (owner 2026-11-21: vendor/fineract, 1.16.1).
# Context: repo root. The jar is architecture-neutral: built once on the build platform, both arches share it.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
# Maven Central rate-limits shared CI IPs (429): resolve through Google's public mirror first (maven-mirror.gradle).
COPY deploy/images/fineract/maven-mirror.gradle /root/.gradle/init.d/maven-mirror.gradle
COPY vendor/fineract /src
# Upstream derives the version from git tags; the vendored tree has none, so turn that off (the jar reports
# 0.0.0-SNAPSHOT; the real version, 1.16.1, is in vendor/VENDORED.json).
ENV VERSIONING_DISABLE=true
RUN ./gradlew --no-daemon -q :fineract-provider:bootJar -x test -x javadoc \
    && cp fineract-provider/build/libs/fineract-provider.jar /fineract.jar

FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 10001 --no-create-home fineract
COPY --from=build /fineract.jar /app/fineract.jar
USER 10001
EXPOSE 8080
# Plain HTTP inside the mesh (Istio ambient encrypts pod-to-pod); the upstream dev keystore isn't vendored.
ENV FINERACT_SERVER_SSL_ENABLED=false FINERACT_SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "/app/fineract.jar"]
