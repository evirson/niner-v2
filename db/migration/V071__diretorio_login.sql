-- V071 — Diretório de login: índice global de e-mail → conta (2026-08-27)
--
-- PARA QUE SERVE. A tela de login deixou de pedir o "identificador da loja" (decisão do dono do
-- produto, 2026-08-27): o usuário digita só e-mail e senha. Como `usuario.email` é único apenas
-- DENTRO do tenant (V015: `UNIQUE (id_tenant, lower(email))`), o mesmo e-mail pode existir em
-- várias contas — o caso real que ele descreveu é o dono que hoje vende cosméticos e amanhã abre
-- outra empresa, de sapatos, num tenant diferente. Sem um índice global, não há por onde começar
-- a busca: o RLS exige saber o tenant ANTES de ler `usuario`, e é justamente o tenant que o
-- login precisa descobrir.
--
-- POR QUE UMA TABELA, E NÃO UM SELECT DIRETO EM `usuario`. `usuario` está sob FORCE ROW LEVEL
-- SECURITY (V024) — sem `app.id_tenant` no contexto ela devolve ZERO linhas, inclusive para o
-- dono. Uma varredura "por todos os tenants" não existe sem BYPASSRLS, que `niner_app` nunca
-- terá (P8). Esta tabela mora em `plataforma`, que é global e sem RLS pela mesma exceção
-- documentada de `plataforma.recuperacao_senha` (V042) e `plataforma.oauth_estado_canal` (V065):
-- ela é consultada por quem AINDA NÃO SABE de que tenant é a requisição.
--
-- É UM ÍNDICE, NÃO A VERDADE. A conta de verdade continua sendo a linha em `usuario`, dentro da
-- célula. Esta tabela é reconstruível a partir dela (ver o backfill no fim do arquivo) — é o que
-- dispensa transação distribuída no dia em que o catálogo sair para fora da célula.
--
-- id_celula. Hoje existe UMA célula (parque > célula > tenant > empresa — vocabulário aprovado em
-- 2026-08-27, ver docs/infra/parque-de-celulas.md) e a coluna é sempre 1. Ela nasce aqui porque
-- é exatamente a informação que o login vai precisar quando houver várias: qual célula abrir para
-- conferir a senha daquele candidato.

CREATE TABLE plataforma.diretorio_login (
  -- Já normalizado (lower + trim) pela trigger — nunca gravar o e-mail cru aqui.
  email         text        NOT NULL,
  id_celula     smallint    NOT NULL DEFAULT 1,
  id_tenant     smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant) ON DELETE CASCADE,
  id_usuario    integer     NOT NULL,
  atualizado_em timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (email, id_celula, id_tenant)
);

COMMENT ON TABLE plataforma.diretorio_login IS
  'Índice global e-mail → (célula, tenant, usuário) para o login sem identificador. Reconstruível a partir de usuario; mantido pela trigger usuario_diretorio_login_tg.';

-- ⚠️ SEM FK para `usuario`, de propósito: no parque, o usuário mora em OUTRA célula e a
-- integridade referencial não atravessa. Quem mantém a coerência é a trigger abaixo — inclusive
-- no DELETE, que é onde vínculo sem FK costuma deixar órfão neste projeto.

-- niner_app LÊ (é o login), mas não escreve: toda escrita passa pela trigger, que roda como
-- niner_owner (SECURITY DEFINER). Mesmo desenho de gerar_ean13_interno() em V017.
--
-- ⚠️ O GRANT abaixo é redundante e o REVOKE é que importa: a V011 declarou
-- `ALTER DEFAULT PRIVILEGES ... GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO niner_app`
-- para o schema plataforma, então toda tabela NOVA daqui nasce com escrita liberada para a
-- aplicação — sem que nada neste arquivo diga isso. Quem quiser uma tabela de `plataforma`
-- somente-leitura para a app precisa revogar explicitamente, como a própria V011 faz com
-- `impersonacao_log`. Sem isto, "só a trigger escreve" seria uma afirmação falsa do comentário.
GRANT SELECT ON plataforma.diretorio_login TO niner_app;
REVOKE INSERT, UPDATE, DELETE ON plataforma.diretorio_login FROM niner_app;

-- Qual célula é esta instância. Hoje responde 1 sempre; quando o parque existir, passa a ler a
-- configuração da célula. Fica como função (e não literal) porque o valor é o mesmo que
-- cfg_ean_gerador.id_banco já usa desde V017 — e um literal espalhado pelo schema é a bomba-
-- relógio clássica: correta enquanto só existe um valor possível.
CREATE OR REPLACE FUNCTION plataforma.celula_atual() RETURNS smallint
LANGUAGE sql STABLE AS $$ SELECT 1::smallint $$;

CREATE OR REPLACE FUNCTION plataforma.fn_sincroniza_diretorio_login()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
  -- "Avise o índice quando o usuário mudar" mora na TRIGGER, não nos serviços — mesma decisão
  -- (e mesmo motivo) do gatilho de sincronização de estoque em V067: mexem em `usuario` o
  -- signup, a tela de Usuários (criar, editar, excluir e o caminho que apenas INATIVA quando há
  -- caixa vinculado) e o que vier depois. Espalhar a manutenção por serviço garante,
  -- matematicamente, que um caminho fique de fora — e o que ficar de fora vira um login que não
  -- encontra a conta, ou pior, um e-mail antigo que continua abrindo a porta.
  IF (TG_OP = 'DELETE') THEN
    DELETE FROM plataforma.diretorio_login
     WHERE id_tenant = OLD.id_tenant AND id_usuario = OLD.id_usuario;
    RETURN OLD;
  END IF;

  IF (TG_OP = 'UPDATE' AND lower(btrim(NEW.email)) <> lower(btrim(OLD.email))) THEN
    DELETE FROM plataforma.diretorio_login
     WHERE id_tenant = OLD.id_tenant AND id_usuario = OLD.id_usuario;
  END IF;

  INSERT INTO plataforma.diretorio_login (email, id_celula, id_tenant, id_usuario)
  VALUES (lower(btrim(NEW.email)), plataforma.celula_atual(), NEW.id_tenant, NEW.id_usuario)
  ON CONFLICT (email, id_celula, id_tenant)
  DO UPDATE SET id_usuario = EXCLUDED.id_usuario, atualizado_em = now();

  RETURN NEW;
END;
$$;

CREATE TRIGGER usuario_diretorio_login_tg
AFTER INSERT OR UPDATE OF email OR DELETE ON usuario
FOR EACH ROW EXECUTE FUNCTION plataforma.fn_sincroniza_diretorio_login();

-- Backfill do que já existe.
-- ⚠️ Migration roda como niner_owner e `usuario` tem FORCE ROW LEVEL SECURITY: sem soltar a
-- política, este INSERT ... SELECT copiaria ZERO linhas e o Flyway anunciaria sucesso (foi o que
-- mordeu na V057). NO FORCE libera só o dono — a política continua valendo para niner_app.
ALTER TABLE usuario NO FORCE ROW LEVEL SECURITY;

INSERT INTO plataforma.diretorio_login (email, id_celula, id_tenant, id_usuario)
SELECT lower(btrim(u.email)), plataforma.celula_atual(), u.id_tenant, u.id_usuario
  FROM usuario u
ON CONFLICT (email, id_celula, id_tenant) DO NOTHING;

ALTER TABLE usuario FORCE ROW LEVEL SECURITY;
