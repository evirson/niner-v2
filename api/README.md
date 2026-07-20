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

> **JDK 25 nativo instalado (2026-07-21, Windows):** Eclipse Temurin 25 instalado via
> `winget` neste host — `./mvnw test` roda **direto**, sem container Docker/Maven,
> bem mais rápido (~41s para 26 testes vs. o caminho abaixo, que ainda funciona como
> fallback em máquinas sem JDK/`winget`). Terminais novos detectam `JAVA_HOME`
> automaticamente; um terminal já aberto no momento da instalação precisa reexportar
> `JAVA_HOME`/`PATH` manualmente ou ser reaberto (o Windows só propaga variáveis de
> ambiente para processos novos).

> **Sem JDK no host (ex.: Windows sem Java instalado) — rodar via container:** use a
> mesma imagem do estágio de build do `Dockerfile` (`maven:3.9-eclipse-temurin-25`),
> montando o **repo inteiro** (não só `api/` — o Flyway de teste usa `../db/migration`,
> relativo, que precisa existir dentro do container) e o socket do Docker:
> ```bash
> docker run --rm \
>   -v /var/run/docker.sock:/var/run/docker.sock \
>   -v "<raiz-do-repo>:/workspace" \
>   -v niner_maven_repo:/root/.m2 \
>   -w /workspace/api \
>   -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
>   -e TESTCONTAINERS_RYUK_DISABLED=true \
>   -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
>   maven:3.9-eclipse-temurin-25 \
>   mvn -B test
> ```
> Três coisas que quebram sem isso (achado em 2026-07-16, Docker Desktop no Windows):
> 1. **Ryuk não conecta de volta** ao container do Maven (`--network host` não é
>    honrado do jeito esperado pelo Docker Desktop) → `TESTCONTAINERS_RYUK_DISABLED=true`
>    (sem reaper automático; containers de teste precisam de limpeza manual ocasional
>    com `docker ps -a --filter ancestor=postgres:18`).
> 2. **JDBC aponta para o gateway interno do Docker** (`172.17.0.1`), inalcançável de
>    dentro de outro container → `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`.
> 3. **`../db/migration` não existe** se só a pasta `api/` for montada → monta o repo
>    inteiro e roda com `-w /workspace/api`.

## Pendências (fases seguintes)

- Camada de domínio na API: repositórios/serviços/endpoints `/api/v1` de produto, estoque e
  pedido (schema V013–V024 já existe; falta o código Spring Data JDBC sobre ele). Fornecedor
  e funcionário também (schema V016 já existe).
- Backoffice `admin/` (staff, `/api/admin/**`).

## Já resolvido

- Emissão/validação de JWT (HS256, claim `tid`/`aud`) — login/signup emitem, `/api/v1` valida.
- Migrations de domínio V013–V026 + políticas RLS (`FORCE`, V024) — ver `db/migration/README.md`.
- **Gate P8** (isolamento cross-tenant) verde (`RlsIsolamentoTest`, Testcontainers).
- **Módulo `cadastros.cliente`** (2026-07-20/21) — CRUD completo de cliente + categoria de
  cliente (`docs/telas/cliente.md`): `/api/v1/clientes` e `/api/v1/categorias-cliente`,
  validação de CPF/CNPJ (dígito verificador + duplicidade), normalização de texto para
  maiúsculas, celular/WhatsApp (11 dígitos + 3º dígito = 9), data de nascimento opcional
  (só não pode ser hoje/futuro), exclusão com fallback para inativar (venda associada).
  **10 testes** (`ClienteCrudTest`).
- **Módulo `comum.telaconfig`** (novo, 2026-07-21, §3.7.2) — `GET/PUT
  /api/v1/config-tela/{chaveTela}`: configuração por tenant de quais campos aparecem e são
  obrigatórios em cada tela, reutilizável entre telas (`docs/telas/configuracao-tela.md`).
  Só `ADMIN` grava (403 para `OPERADOR`, checado via claim `roles` do JWT — primeira
  autorização por papel aplicada de fato num endpoint). **5 testes**
  (`ConfiguracaoTelaTest`).
- **26 testes verdes no total** (Testcontainers, Postgres 18 real).
