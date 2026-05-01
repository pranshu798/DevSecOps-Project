# ─────────────────────────────────────────────────────────────
#  Multi-stage Dockerfile – Spring Boot
#  Stage 1: Build the JAR with Maven
#  Stage 2: Lean runtime image (Distroless / Eclipse Temurin)
# ─────────────────────────────────────────────────────────────

# ── Stage 1: Build ───────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# Cache dependencies first (layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline --no-transfer-progress -q

# Copy source & build
COPY src ./src
RUN mvn clean package -DskipTests --no-transfer-progress

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

# OCI Labels for traceability
ARG BUILD_DATE
ARG VCS_REF
ARG VERSION
LABEL org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${VCS_REF}"   \
      org.opencontainers.image.version="${VERSION}"    \
      org.opencontainers.image.title="pranshu-springboot-app"

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy only the fat JAR
COPY --from=builder --chown=appuser:appgroup /build/target/*.jar app.jar

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=45s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
