# Build multi-stage da API Niner (Spring Boot 4 / Java 25).
# Estágio 1 — build com Maven + JDK 25 (offline-friendly: baixa deps antes do código).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Estágio 2 — runtime enxuto (JRE 25).
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
# usuário não-root
RUN useradd -r -u 1001 niner
COPY --from=build /app/target/*.jar app.jar
USER niner
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
