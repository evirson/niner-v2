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

-- O banco niner_db é criado pela imagem (POSTGRES_DB=niner_db); passa a ser do owner.
ALTER DATABASE niner_db OWNER TO niner_owner;

GRANT CONNECT ON DATABASE niner_db TO niner_app;

-- Ninguém cria objetos soltos no schema public; só o dono organiza os schemas.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Flyway conecta como niner_owner e aplica as migrations em db/migration/.
