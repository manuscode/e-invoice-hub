# Build context is the repository root, the parent pom is needed for the build.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY invoice-hub/pom.xml invoice-hub/
COPY erp-simulator erp-simulator
RUN ./mvnw -B -q -pl erp-simulator package -DskipTests

FROM eclipse-temurin:25-jre
COPY --from=build /workspace/erp-simulator/target/erp-simulator-*.jar /app/erp-simulator.jar
RUN useradd --system --no-create-home app
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/erp-simulator.jar"]
