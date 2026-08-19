#!/bin/bash
# =============================================================================================
# BOOTSTRAP (NÃO é migration Flyway) — objetos GLOBAIS do cluster PostgreSQL.
# Roda UMA vez na inicialização, como superusuário, já conectado ao banco niner_db.
#
# ⚠️ Era um .sql com as senhas ESCRITAS NO ARQUIVO até 2026-08-19. O deploy em produção mostrou
# o problema de duas formas ao mesmo tempo: (a) o Flyway não entrava, porque o .env do servidor
# tinha senha aleatória e o banco havia nascido com `dev_owner`; e (b) — o que importa de
# verdade — um banco novo subia em produção com **senhas de desenvolvimento, versionadas neste
# repositório**. Agora as senhas vêm do ambiente, e os valores de dev são apenas o padrão.
#
# As três roles do modelo de isolamento (P8):
#   niner_owner  — dona dos objetos; roda as migrations Flyway (V001+).
#   niner_app    — role da aplicação; SEM BYPASSRLS e sem ser dona de tabela, para que o RLS
#                  seja realmente aplicado a ela.
#   niner_backup — só leitura, COM BYPASSRLS: com FORCE ROW LEVEL SECURITY nem o dono enxerga
#                  as linhas, e um pg_dump feito por niner_app ou niner_owner sairia com a
#                  estrutura completa e ZERO dado de cliente (medido em 2026-08-19: o owner via
#                  0 empresas; o superusuário, 3).
# =============================================================================================
set -euo pipefail

SENHA_OWNER="${NINER_OWNER_PASSWORD:-dev_owner}"
SENHA_APP="${NINER_APP_PASSWORD:-dev_app}"
SENHA_BACKUP="${NINER_BACKUP_PASSWORD:-dev_backup}"

if [ "$SENHA_OWNER" = "dev_owner" ]; then
  echo "[bootstrap] ⚠️  usando senhas de DESENVOLVIMENTO (NINER_OWNER_PASSWORD não definida)."
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname niner_db <<SQL
CREATE ROLE niner_owner LOGIN PASSWORD '$SENHA_OWNER'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

CREATE ROLE niner_app LOGIN PASSWORD '$SENHA_APP'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

CREATE ROLE niner_backup LOGIN PASSWORD '$SENHA_BACKUP'
  NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;

-- O banco niner_db é criado pela imagem (POSTGRES_DB=niner_db); passa a ser do owner.
ALTER DATABASE niner_db OWNER TO niner_owner;

GRANT CONNECT ON DATABASE niner_db TO niner_app, niner_backup;

-- Ninguém cria objetos soltos no schema public; só o dono organiza os schemas.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- ALTER DATABASE ... OWNER não torna niner_owner dono do schema public (ele já existia, criado
-- pelo superusuário de bootstrap da imagem). As migrations de domínio (V013+) criam tipos e
-- tabelas sem prefixo de schema — portanto em public — rodando como niner_owner; sem este
-- GRANT, o Flyway falharia com "permission denied for schema public" a partir de V013.
GRANT CREATE ON SCHEMA public TO niner_owner;

-- Leitura para o backup em tudo o que as migrations criarem depois. SEQUÊNCIA também: o
-- pg_dump lê o last_value de cada uma, e sem SELECT nelas o dump aborta com código 1 — o
-- backup automático de produção passou o primeiro dia falhando exatamente assim (V044).
-- O schema `plataforma` ainda não existe aqui (nasce na V001), então o resto é lá.
GRANT USAGE ON SCHEMA public TO niner_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE niner_owner IN SCHEMA public GRANT SELECT ON TABLES    TO niner_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE niner_owner IN SCHEMA public GRANT SELECT ON SEQUENCES TO niner_backup;
SQL

echo "[bootstrap] roles niner_owner / niner_app / niner_backup criadas."
