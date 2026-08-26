-- V065 — o `state` do OAuth de canal (bloco M1, docs/MODULOMARKETPLACE.md §2.1).
--
-- ⭐ Esta tabela existe por causa de UMA frase do estudo: "errar o `state` conecta a conta ML de
-- um lojista no tenant de outro — falha de isolamento (P8) logo na porta de entrada".
--
-- O problema concreto: a URI de redirect do Mercado Livre **não aceita parte variável** e tem de
-- bater caractere por caractere. Então o vínculo "esta autorização é da loja X, canal Y" não pode
-- viajar na URL — viaja no `state`, que é o mesmo lugar do token anti-CSRF. Quando o navegador do
-- lojista volta do ML, ele chega **sem JWT**: é lendo esta linha que a API descobre de quem é a
-- autorização que acabou de chegar.
--
-- ⚠️ POR QUE EM `plataforma` E NÃO NO PLANO DO TENANT: o endpoint de retorno é público e roda
-- **sem TenantContext** — é justamente ele que vai *descobrir* o tenant. Uma tabela sob RLS
-- devolveria zero linha em silêncio (o mesmo defeito que deixou os jobs do fiscal inertes até
-- 2026-08-19, e o que faz `pg_dump` sem BYPASSRLS levar estrutura completa e nenhuma linha).
-- Precedente idêntico no projeto: `plataforma.recuperacao_senha` (V042).
--
-- ⚠️ GUARDA O HASH, NUNCA O `state`: mesma regra da recuperação de senha. Quem lê o banco não
-- consegue reconstruir um `state` válido e sequestrar uma autorização em curso.

CREATE TABLE plataforma.oauth_estado_canal (
  id_estado    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  estado_hash  text        NOT NULL,
  id_tenant    smallint    NOT NULL REFERENCES plataforma.tenant (id_tenant),
  id_canal     integer     NOT NULL,
  id_usuario   integer     NOT NULL,
  expira_em    timestamptz NOT NULL,
  usado_em     timestamptz,
  criado_em    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT oauth_estado_canal_uk UNIQUE (estado_hash)
);

CREATE INDEX oauth_estado_canal_tenant_ix
  ON plataforma.oauth_estado_canal (id_tenant, id_canal, criado_em);

COMMENT ON TABLE plataforma.oauth_estado_canal IS
  'O parametro `state` do OAuth de canal de venda: anti-CSRF + o vinculo tenant/canal que a URI '
  'de redirect nao pode carregar. Uso unico, validade curta, guarda o HASH e nunca o valor. '
  'Mora em `plataforma` porque o endpoint de retorno roda SEM TenantContext — e o tenant e '
  'justamente o que ele descobre aqui. Ver docs/MODULOMARKETPLACE.md §2.1.';

COMMENT ON COLUMN plataforma.oauth_estado_canal.usado_em IS
  'Marcado ANTES da troca do code por token, de proposito: um `state` que sobrevive a uma troca '
  'malsucedida poderia ser reapresentado com outro `code`. Falha na troca custa recomecar a '
  'autorizacao, que e barato; `state` reutilizavel nao e.';

GRANT SELECT, INSERT, UPDATE, DELETE ON plataforma.oauth_estado_canal TO niner_app;
