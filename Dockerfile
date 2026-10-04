# ============================
# Stage 1: Build Spring Boot JAR
# ============================
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy Maven wrapper và pom.xml trước để cache dependencies
COPY pom.xml ./    
# Copy file pom.xml từ máy host vào /app/pom.xml trong stage builder.
COPY .mvn .mvn
# Copy thư mục .mvn từ máy host vào /app/.mvn trong stage builder.
COPY mvnw ./
# Copy file mvnw từ máy host vào /app/mvnw trong stage builder và cấp quyền thực thi.
RUN chmod +x mvnw
# Cấp quyền thực thi cho file mvnw trong stage builder.

# Download dependencies (cache layer)
RUN ./mvnw dependency:go-offline -B

# Copy source code và build
COPY src ./src
RUN ./mvnw package -DskipTests -B

# ============================
# Stage 2: Runtime
# ============================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy JAR từ build stage
COPY --from=builder /app/target/*.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/health || exit 1

# Run
ENTRYPOINT ["java", "-jar", "app.jar"]
