-- =============================================================================
-- BOOTSTRAP (NÃO é migration Flyway) — objetos GLOBAIS do cluster PostgreSQL.
-- Roda UMA vez na inicialização, como SUPERUSUÁRIO, já conectado ao banco
-- niner_db (ex.: montado em /docker-entrypoint-initdb.d, com POSTGRES_DB=niner_db).
--
-- Cria as duas roles do modelo de isolamento (P8):
--   niner_owner — dona dos objetos; roda as migrations Flyway (V001+).
--   niner_app   — role da aplicação; SEM BYPASSRLS e SEM ser dona de tabela,
--                 para que o Row-Level Security seja realmente aplicado a ela.
--
-- 🔴 As senhas abaixo são de DESENVOLVIMENTO. Em produção, provisionar via
--    secret manager / variáveis de ambiente e NÃO versionar segredos.
-- =============================================================================

CREATE ROLE niner_owner LOGIN PASSWORD 'dev_owner'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

CREATE ROLE niner_app LOGIN PASSWORD 'dev_app'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- niner_backup — usado SÓ pelo pg_dump do backup automático (2026-08-19).
--
-- Precisa de BYPASSRLS, e isso não é preferência: com FORCE ROW LEVEL SECURITY (V024) nem o DONO
-- das tabelas escapa da política. Medido neste banco: `SELECT count(*) FROM empresa` devolve 0
-- para niner_owner e 3 para o superusuário, sem `app.id_tenant` no contexto. Um dump feito por
-- niner_app ou niner_owner sairia com a estrutura completa e ZERO linha de cliente — o pior tipo
-- de backup, o que só se revela vazio no dia do restore.
--
-- Só leitura: BYPASSRLS não implica escrita, e este papel não recebe INSERT/UPDATE/DELETE.
CREATE ROLE niner_backup LOGIN PASSWORD 'dev_backup'
  NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;

GRANT CONNECT ON DATABASE niner_db TO niner_backup;

-- O banco niner_db é criado pela imagem (POSTGRES_DB=niner_db); passa a ser do owner.
ALTER DATABASE niner_db OWNER TO niner_owner;

GRANT CONNECT ON DATABASE niner_db TO niner_app;

-- Ninguém cria objetos soltos no schema public; só o dono organiza os schemas.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- ALTER DATABASE ... OWNER não torna niner_owner dono do schema public (ele já existia,
-- criado pelo superusuário de bootstrap da imagem). As migrations de domínio (V013+) criam
-- tipos/tabelas sem prefixo de schema (portanto em public) rodando como niner_owner — sem
-- este GRANT, o Flyway falharia com "permission denied for schema public" a partir de V013.
GRANT CREATE ON SCHEMA public TO niner_owner;

-- Leitura para o backup em tudo o que as migrations criarem (incluindo o que vier depois).
GRANT USAGE ON SCHEMA public TO niner_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE niner_owner IN SCHEMA public GRANT SELECT ON TABLES TO niner_backup;

-- Flyway conecta como niner_owner e aplica as migrations em db/migration/.
