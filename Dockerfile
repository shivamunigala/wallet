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

# Memory is the binding constraint here, not CPU: Render's free instance is hard-capped at
# 512Mi and the platform kills the container the moment total RSS crosses it - heap,
# metaspace, code cache, thread stacks and JVM native overhead all counted together.
#
# MaxRAMPercentage was the wrong instrument. It sizes only the *heap*, as a share of the
# container limit, and says nothing about the rest. At 70% it claimed ~358Mi of the 512Mi
# for heap alone, leaving ~154Mi for a Spring Boot 2.7 + Hibernate startup that needs
# roughly 90Mi of metaspace before it has served a single request. The JVM never saw an
# OutOfMemoryError - it was still happily growing a heap it had been told it could grow,
# and the platform killed it mid-Hibernate-bootstrap, before Tomcat bound the port.
#
# So every region is now bounded explicitly, and the total is kept under the cap with
# headroom rather than pressed against it:
#
#   heap         224Mi   -Xmx
#   metaspace    112Mi   -XX:MaxMetaspaceSize
#   code cache    48Mi   -XX:ReservedCodeCacheSize
#   thread stacks ~20Mi  -Xss512k, Tomcat's pool being the bulk of it
#   JVM native    ~35Mi
#   ------------------
#   ceiling      ~439Mi against a 512Mi limit
#
# SerialGC because the free instance is a fraction of a core: G1's concurrent threads and
# per-region bookkeeping cost both CPU and footprint that a heap this small cannot repay.
#
# ExitOnOutOfMemoryError is kept deliberately. A wallet that has lost its heap must not
# linger in a half-working state serving some transfers and failing others - it should die
# and let the platform restart it.
ENV JAVA_OPTS="-Xmx224m -XX:MaxMetaspaceSize=112m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/wallet.jar"]
