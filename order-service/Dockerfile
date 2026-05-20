# ─────────────────────────────────────────────────────────────
# Stage 1: BUILD
# ─────────────────────────────────────────────────────────────
FROM maven:3.9.5-eclipse-temurin-21 AS build

WORKDIR /app

# Download dependencies first (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build the JAR
COPY src ./src
RUN mvn clean package -DskipTests

# ─────────────────────────────────────────────────────────────
# Stage 2: RUN
# Lightweight JRE image
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# Order Service runs on port 8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
