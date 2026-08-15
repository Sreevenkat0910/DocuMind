# Plan.md Phase 10: containerizes the Java app so `docker compose up` brings up the whole
# system (Java + Python + Postgres [+ Redis]), not just the infra pieces Phase 0/3 added.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
# Cache dependency resolution in its own layer: only reruns when pom.xml changes, not on every
# source edit (same reasoning as the gateway Dockerfile baking its models in ahead of time).
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
