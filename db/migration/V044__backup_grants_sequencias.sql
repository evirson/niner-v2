-- =============================================================================================
-- V044 — leitura de SEQUÊNCIAS para a role de backup.
--
-- Defeito encontrado na validação de produção (2026-08-19): o backup automático NUNCA rodou.
-- `plataforma.configuracao_plataforma.backup_ultimo_detalhe` guardava:
--
--     pg_dump: error: failed to get data for sequence "assinatura_id_assinatura_seq";
--              user may lack SELECT privilege on the sequence
--
-- A role `niner_backup` tinha BYPASSRLS e SELECT nas TABELAS (bootstrap 00_roles.sh), mas
-- SELECT em 0 de 59 sequências — e o pg_dump lê `last_value` de cada uma para restaurar o
-- ponto de contagem. Sem isso o dump aborta com código 1 e não sobra backup nenhum.
--
-- Por que aqui e não só no bootstrap: o bootstrap roda uma vez, na criação do cluster, ANTES
-- de existir o schema `plataforma` e qualquer sequência — ele só consegue deixar
-- ALTER DEFAULT PRIVILEGES preparado para `public`. Esta migration fecha o que já existe (os
-- dois schemas) e deixa o padrão valendo para o que vier depois.
--
-- O bloco é condicional porque `niner_backup` é objeto de CLUSTER, não de banco: um ambiente
-- que restaure só o schema (ou um Testcontainers sem o bootstrap) não tem a role, e a
-- migration não pode falhar por causa disso.
-- =============================================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'niner_backup') THEN
        RAISE NOTICE 'role niner_backup nao existe neste cluster; grants de backup ignorados.';
        RETURN;
    END IF;

    EXECUTE 'GRANT USAGE ON SCHEMA public, plataforma TO niner_backup';

    -- O que já existe (V001–V043).
    EXECUTE 'GRANT SELECT ON ALL TABLES    IN SCHEMA public     TO niner_backup';
    EXECUTE 'GRANT SELECT ON ALL TABLES    IN SCHEMA plataforma TO niner_backup';
    EXECUTE 'GRANT SELECT ON ALL SEQUENCES IN SCHEMA public     TO niner_backup';
    EXECUTE 'GRANT SELECT ON ALL SEQUENCES IN SCHEMA plataforma TO niner_backup';

    -- O que as próximas migrations criarem. Sem FOR ROLE: vale para o dono da sessão, que é
    -- justamente quem roda o Flyway (niner_owner) e portanto cria as tabelas.
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public     GRANT SELECT ON TABLES    TO niner_backup';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public     GRANT SELECT ON SEQUENCES TO niner_backup';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA plataforma GRANT SELECT ON TABLES    TO niner_backup';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA plataforma GRANT SELECT ON SEQUENCES TO niner_backup';
END $$;
