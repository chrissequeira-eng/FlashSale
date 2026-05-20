# ─────────────────────────────────────────────────────────────
# Stage 1: BUILD
# Use Maven image to compile and package the JAR
# ─────────────────────────────────────────────────────────────
FROM maven:3.9.5-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml first (Docker layer caching: dependencies won't
# re-download if only source code changed)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Now copy source and build
COPY src ./src
RUN mvn clean package -DskipTests

# ─────────────────────────────────────────────────────────────
# Stage 2: RUN
# Use a lightweight JRE image (much smaller than JDK)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy just the compiled JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Product Service runs on port 8081
EXPOSE 8081

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]
