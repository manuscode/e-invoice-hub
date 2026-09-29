# Build context is the repository root, the parent pom is needed for the build.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY erp-simulator/pom.xml erp-simulator/
COPY invoice-hub invoice-hub
RUN ./mvnw -B -q -pl invoice-hub package -DskipTests

FROM eclipse-temurin:25-jre
# For the health check in docker compose.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/invoice-hub/target/invoice-hub-*.jar /app/invoice-hub.jar
# The hub parses documents from outside, it must not run as root.
RUN useradd --system --no-create-home app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/invoice-hub.jar"]
