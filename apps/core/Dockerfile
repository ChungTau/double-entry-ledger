# ============================================
# Stage 1: Build with Maven dependency caching
# ============================================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Step 1: Copy Maven Wrapper and POM first for dependency caching
# 我們複製 mvnw 和 .mvn 目錄，這樣就不需要安裝系統 Maven
COPY apps/core/.mvn ./apps/core/.mvn
COPY apps/core/mvnw ./apps/core/mvnw
COPY apps/core/pom.xml ./apps/core/pom.xml

WORKDIR /app/apps/core

# 確保 mvnw 有執行權限
RUN chmod +x mvnw
# Ensure LF line endings (safeguard for Windows developers)
RUN sed -i 's/\r$//' mvnw

# Step 2: Download dependencies using the Wrapper
# 使用 ./mvnw 而不是 mvn
RUN ./mvnw dependency:go-offline -B

# Step 3: Copy proto files (needed for gRPC code generation)
COPY api/proto /app/api/proto

# Step 4: Copy source code and build
COPY apps/core/src ./src
# 使用 ./mvnw 進行打包
RUN ./mvnw package -DskipTests -B

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