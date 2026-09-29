# syntax=docker/dockerfile:1

# Stage 1: Build the React + Vite frontend
FROM node:20-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm install
COPY frontend/ ./
RUN npm run build

# Stage 2: Build the Spring Boot executable JAR
FROM eclipse-temurin:21-jdk-alpine AS backend-builder
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
# Pre-fetch dependencies
RUN ./mvnw dependency:go-offline -B 2>/dev/null || true
COPY src ./src
# Copy built static frontend into Spring Boot's static folder
COPY --from=frontend-builder /app/frontend/dist ./src/main/resources/static
RUN ./mvnw clean package -DskipTests -B

# Stage 3: Production JRE 21 Container
FROM eclipse-temurin:21-jre-alpine
LABEL maintainer="Ishant Garg"
WORKDIR /app

# Run as non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8085
COPY --from=backend-builder --chown=appuser:appgroup /app/target/semantic-search-*.jar app.jar

ENV PORT=8085 \
    SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8085/actuator/health || exit 1

ENTRYPOINT ["java", "-Xmx1024m", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
