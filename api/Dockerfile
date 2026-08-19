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
# pg_dump: o backup agendado (configurado no backoffice) roda dentro deste container.
# postgresql-client-18 para casar com a major do servidor — pg_dump mais antigo que o servidor
# recusa o dump, e essa falha só apareceria na primeira madrugada.
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client-18 \
 || apt-get install -y --no-install-recommends postgresql-client \
 && rm -rf /var/lib/apt/lists/*
# usuário não-root
RUN useradd -r -u 1001 niner
COPY --from=build /app/target/*.jar app.jar
USER niner
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
