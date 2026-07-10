# niner-api

API do SaaS Niner — **monólito modular** (Spring Boot 4 / Java 25), uma API com
**três superfícies** (ADR-007):

- `/api/publico/**` — site público (signup/checkout/trial). Anônimo.
- `/api/v1/**` — ERP do tenant. Estabelece o `TenantContext` (claim `tid` do JWT); RLS ativo no banco.
- `/api/admin/**` — backoffice da plataforma (staff), opera em `plataforma.*`.

Persistência: **Spring Data JDBC** (não JPA). Isolamento de tenant (P8): a app conecta
como `niner_app` (sem `BYPASSRLS`) e o `TenantAwareTransactionManager` aplica
`app.id_tenant` por transação, casando com as políticas RLS. **Migrations não rodam pela
app** (`spring.flyway.enabled=false`) — são aplicadas como `niner_owner` pelo serviço
`flyway` do `docker-compose` (raiz do repo).

## Rodar em dev

```bash
# 1) banco + migrations (na raiz do repo)
docker compose up -d db
docker compose run --rm flyway

# 2) a API (conecta como niner_app em localhost:5432/niner_db)
cd api && ./mvnw spring-boot:run        # ou: java -jar target/niner-api-*.jar

# 3) provar as superfícies
curl localhost:8080/actuator/health
curl localhost:8080/api/publico/ping
curl localhost:8080/api/v1/ping         # id_tenant nulo sem JWT
curl localhost:8080/api/admin/ping
```

Ou tudo via compose: `docker compose up -d db && docker compose run --rm flyway && docker compose up -d api`.

## Testes

```bash
cd api && ./mvnw test
```

Usam **Testcontainers** (Postgres 18 real): criam as roles via `bootstrap-test.sql`,
aplicam `db/migration` (V001–V012) com Flyway e exercem as 3 superfícies + a propagação
de tenant.

> **Colima + Testcontainers:** se o runtime de container é o Colima, exporte antes de
> rodar os testes:
> ```bash
> export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
> ```
> Sem isso o Ryuk (resource reaper) falha ao montar o `docker.sock`
> (`operation not supported`).

## Pendências (fases seguintes)

- Emissão/validação de JWT (`aud` tenant × plataforma, claim `tid`) — hoje `TODO(jwt)`.
- Migrations de domínio V013+ (dependem de Q2/Q5/Q7) e as políticas RLS de domínio.
- **Gate P8** (isolamento cross-tenant) só fecha com as tabelas de domínio.
