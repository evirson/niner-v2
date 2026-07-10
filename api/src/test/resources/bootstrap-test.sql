-- Bootstrap SÓ para testes (Testcontainers). Cria as roles que as migrations
-- assumem existir (V001 usa AUTHORIZATION niner_owner; V011 dá grants a niner_app).
-- Aqui não há o ALTER DATABASE/OWNER do bootstrap de produção (db/bootstrap/00_roles.sql):
-- nos testes o Flyway roda como o superusuário do container.
CREATE ROLE niner_owner LOGIN PASSWORD 'dev_owner'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

CREATE ROLE niner_app LOGIN PASSWORD 'dev_app'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
