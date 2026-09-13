# ---------- build ----------
# Pinned to a JDK 8 toolchain because the service targets Java 8 bytecode. Dependencies
# are resolved in their own layer so that a source-only change does not re-download the
# world on every rebuild.
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are skipped here on purpose: they require a Docker daemon (Testcontainers), which
# is not available inside a build container. They run in CI and locally via `mvn verify`.
RUN mvn -B -q clean package -DskipTests

# ---------- runtime ----------
# Jammy rather than Alpine: the Temurin 8 Alpine images are published for amd64 only, and
# this needs to build on arm64 developer machines as well as amd64 hosts.
FROM eclipse-temurin:8-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root. A wallet service has no reason to be able to write to its own filesystem.
RUN groupadd --system --gid 1001 wallet \
    && useradd --system --uid 1001 --gid wallet --shell /usr/sbin/nologin wallet

WORKDIR /app
COPY --from=build --chown=wallet:wallet /build/target/wallet-*.jar /app/wallet.jar
USER wallet

EXPOSE 8080

# Reports unhealthy only once the app can actually serve traffic, which for this service
# means Flyway has finished and the connection pool is live — /actuator/health covers both.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:${PORT:-8080}/actuator/health || exit 1

# Container memory is far smaller than the host's, and Render's free tier is 512MB. JDK 8
# from 8u191 onwards is container-aware by default, so MaxRAMPercentage sizes the heap
# from the cgroup limit. The older UnlockExperimentalVMOptions/UseCGroupMemoryLimitForHeap
# pair is deprecated on this JDK and only produces a warning.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/wallet.jar"]
