-- V109 — Log de acesso ao ERP (docs/MODULOLOGACESSO.md).
--
-- Registra QUEM ENTROU no ERP, para auditoria da VETOR — não do lojista. Sucessos e falhas.
--
-- ⛔ NÃO registra saída: não existe hora exata de logoff sem inventá-la (o navegador não avisa
-- quando a pessoa vai embora), e inferência numa trilha de auditoria vira "fato" na cabeça de quem
-- lê. Decisão do dono do produto em 2026-09-01, junto com a de NÃO deslogar por inatividade.
--
-- ⚠️ Mora em `plataforma` por DUAS razões independentes:
--   (a) P9 — é dado do plano de controle: quem lê é a Vetor. Numa tabela de tenant a RLS DARIA
--       acesso ao administrador do lojista;
--   (b) técnica — no login ainda NÃO existe TenantContext (ele nasce do JWT, emitido no fim), e
--       tabela com RLS não seria gravável ali. Mesma razão de codigo_login e recuperacao_senha.

CREATE TABLE plataforma.acesso_login (
    id_acesso       bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ocorrido_em     timestamptz NOT NULL DEFAULT now(),

    -- ⚠️ Sem FK e NULLABLE de propósito: a tentativa pode ser de um e-mail que não existe em conta
    -- nenhuma, e o usuário pode ser EXCLUÍDO depois sem levar a trilha junto.
    id_tenant       smallint,
    id_usuario      integer,
    id_empresa      integer,

    -- ⭐ CÓPIA do que valia na hora, não referência. Auditoria precisa do que estava lá: o cadastro
    -- muda, o usuário some, e a linha não pode virar um id órfão. Mesmo princípio do percentual de
    -- comissão congelado na venda (V088).
    email_informado text        NOT NULL,

    -- SUCESSO | CREDENCIAL_INVALIDA | FORA_DO_HORARIO | SEM_EMPRESA | EMPRESA_INVALIDA
    -- | CODIGO_2FA_INVALIDO
    --
    -- ⚠️ CREDENCIAL_INVALIDA não distingue e-mail inexistente de senha errada de conta inativa —
    -- porque o LOGIN não distingue, de propósito (distinguir vira oráculo para quem adivinha
    -- e-mails). O log não pode ser mais específico que a autenticação: seria o mesmo oráculo pela
    -- porta dos fundos, para quem tiver acesso ao backoffice.
    resultado       text        NOT NULL,

    ip              inet,
    -- ⚠️ Sem esta coluna, daqui a um ano ninguém sabe se o IP é do cliente ou do nginx. Fora de
    -- produção `confiar-proxy` é false e o valor é o getRemoteAddr() mesmo.
    ip_confiavel    boolean     NOT NULL DEFAULT false,

    -- ⭐ BRUTO: é a prova. Os três abaixo são INTERPRETAÇÃO nossa, de um parser que erra e muda com
    -- o tempo — guardar só o derivado jogaria fora a evidência.
    user_agent      text,
    so              text,
    navegador       text,
    dispositivo     text,        -- COMPUTADOR | CELULAR | TABLET | DESCONHECIDO

    CONSTRAINT acesso_login_resultado_ck CHECK (resultado IN (
        'SUCESSO', 'CREDENCIAL_INVALIDA', 'FORA_DO_HORARIO', 'SEM_EMPRESA',
        'EMPRESA_INVALIDA', 'CODIGO_2FA_INVALIDO')),
    CONSTRAINT acesso_login_dispositivo_ck CHECK (dispositivo IS NULL OR dispositivo IN (
        'COMPUTADOR', 'CELULAR', 'TABLET', 'DESCONHECIDO'))
);

COMMENT ON TABLE plataforma.acesso_login IS
  'Quem entrou no ERP — sucessos E falhas. Auditoria da VETOR; o administrador do tenant NÃO vê '
  '(nenhum endpoint sob /api/v1 lê esta tabela, e há teste prendendo isso). Não registra saída: '
  'ver docs/MODULOLOGACESSO.md §1.';

-- A tela lista por período, sempre do mais recente para o mais antigo.
CREATE INDEX acesso_login_ocorrido_idx ON plataforma.acesso_login (ocorrido_em DESC);
-- Filtro por conta, e a pergunta "quem anda entrando neste tenant?".
CREATE INDEX acesso_login_tenant_idx ON plataforma.acesso_login (id_tenant, ocorrido_em DESC);
-- ⭐ Índice PARCIAL só das falhas: é a pergunta que a auditoria faz de verdade ("mostre o que não
-- deu certo"), e as falhas são a minoria das linhas — indexar tudo custaria mais e ajudaria menos.
CREATE INDEX acesso_login_falhas_idx ON plataforma.acesso_login (ocorrido_em DESC)
    WHERE resultado <> 'SUCESSO';
-- Filtro por e-mail: o mesmo e-mail pode aparecer em contas diferentes.
CREATE INDEX acesso_login_email_idx ON plataforma.acesso_login (lower(email_informado), ocorrido_em DESC);

-- ⛔ INSERT e SELECT, sem UPDATE nem DELETE para `niner_app`: trilha de auditoria não se corrige,
-- e o expurgo roda por outro caminho (a função abaixo, SECURITY DEFINER). Isso não protege de quem
-- tem o banco — protege de um `UPDATE` distraído vindo da aplicação.
GRANT SELECT, INSERT ON plataforma.acesso_login TO niner_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON plataforma.acesso_login TO niner_owner;

-- ---------------------------------------------------------------------------- expurgo
--
-- ⛔ Entregue JUNTO com a tabela, não depois. `plataforma.codigo_login` foi criada sem nenhum
-- expurgo, CRESCE PARA SEMPRE e hoje guarda hash e IP de contas já excluídas — e esta tabela ganha
-- uma linha por login de TODOS os tenants, então o mesmo descuido custaria mais.
--
-- Retenção: 2 anos (decisão do dono do produto). ⚠️ O piso legal é 6 meses — Marco Civil da
-- Internet, art. 15, para provedor de aplicações.
CREATE OR REPLACE FUNCTION plataforma.expurgar_acesso_login(meses_retencao int DEFAULT 24)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = plataforma, public
AS $$
DECLARE
    apagados integer;
BEGIN
    DELETE FROM plataforma.acesso_login
     WHERE ocorrido_em < now() - make_interval(months => meses_retencao);
    GET DIAGNOSTICS apagados = ROW_COUNT;
    RETURN apagados;
END;
$$;

COMMENT ON FUNCTION plataforma.expurgar_acesso_login(int) IS
  'Apaga acessos mais velhos que a retenção (padrão 24 meses). SECURITY DEFINER porque niner_app '
  'não tem DELETE na tabela — a aplicação agenda, o dono executa.';

GRANT EXECUTE ON FUNCTION plataforma.expurgar_acesso_login(int) TO niner_app;
