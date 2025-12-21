# ============================================
# Stage 1: Build with Maven dependency caching
# ============================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Install Maven
RUN apk add --no-cache maven

# Step 1: Copy POM first for dependency caching (Docker layer cache)
COPY apps/core/pom.xml ./apps/core/
WORKDIR /app/apps/core

# Step 2: Download dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Step 3: Copy proto files (needed for gRPC code generation)
COPY api/proto /app/api/proto

# Step 4: Copy source code and build
COPY apps/core/src ./src
RUN mvn package -DskipTests -B

# ============================================
# Stage 2: Runtime
# ============================================
FROM eclipse-temurin:21-jre-alpine

# Add non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app

# Copy the built JAR
COPY --from=builder /app/apps/core/target/*.jar app.jar

# Expose HTTP (actuator) and gRPC ports
EXPOSE 8080 9098

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
