-- V041 — Configuração da plataforma, editável pelo backoffice (2026-08-19).
--
-- Decisão do dono do produto: SMTP, agenda de backup e credencial de gateway saem do arquivo de
-- configuração e passam a ser editáveis pelo admin, sem deploy. A divisão por camada é o que
-- torna isso seguro:
--
--   · NUNCA aqui  → senha do Postgres, segredo do JWT e a CHAVE MESTRA (niner.seguranca.
--                   chave-segredos). É ela que cifra as colunas *_cifrado abaixo; guardá-la no
--                   banco faria um dump valer tudo (mesmo princípio de fiscal_certificado, ADR-005).
--   · Aqui CIFRADO → segredo de terceiro que o admin precisa trocar: senha do SMTP, access token
--                   e segredo de webhook do Mercado Pago.
--   · Aqui EM CLARO → operacional: host/porta/remetente do SMTP, agenda e retenção do backup.
--
-- Tabela GLOBAL (P9), singleton — é configuração da Vetor, não de um tenant.

CREATE TABLE plataforma.configuracao_plataforma (
  id                     smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),

  -- ---- SMTP (avisos de cota, recuperação de senha, envio de NF-e ao cliente) -----------------
  smtp_habilitado        boolean     NOT NULL DEFAULT false,
  smtp_host              text,
  smtp_porta             integer     CHECK (smtp_porta IS NULL OR smtp_porta BETWEEN 1 AND 65535),
  smtp_usuario           text,
  smtp_senha_cifrada     text,                    -- AES-GCM (SegredoCifrador); nunca em claro
  smtp_starttls          boolean     NOT NULL DEFAULT true,
  smtp_remetente_email   text,
  smtp_remetente_nome    text        NOT NULL DEFAULT 'Niner',

  -- ---- Backup automático --------------------------------------------------------------------
  backup_habilitado      boolean     NOT NULL DEFAULT false,
  backup_hora            time        NOT NULL DEFAULT '03:00',   -- horário local do servidor
  backup_retencao_dias   smallint    NOT NULL DEFAULT 30 CHECK (backup_retencao_dias > 0),
  backup_ultimo_em       timestamptz,
  backup_ultimo_status   text,                    -- 'OK' | 'ERRO'
  backup_ultimo_detalhe  text,                    -- tamanho/chave no OK; mensagem no ERRO

  -- ---- Gateway de cobrança (ADR-016) ---------------------------------------------------------
  -- Quando preenchidos, vencem as variáveis de ambiente; vazio = usa o ambiente (dev/CI).
  mp_access_token_cifrado text,
  mp_webhook_secret_cifrado text,
  mp_notification_url    text,

  atualizado_em          timestamptz NOT NULL DEFAULT now(),
  atualizado_por         integer     REFERENCES plataforma.staff (id_staff)
);

COMMENT ON TABLE plataforma.configuracao_plataforma IS
  'Configuracao da Vetor editavel pelo backoffice (SMTP, backup, gateway). Colunas *_cifrado usam a chave mestra, que NAO fica no banco.';
COMMENT ON COLUMN plataforma.configuracao_plataforma.smtp_senha_cifrada IS
  'Cifrada com AES-GCM (comum.seguranca.SegredoCifrador). A API nunca devolve este valor em claro, nem para SUPER_ADMIN.';
COMMENT ON COLUMN plataforma.configuracao_plataforma.backup_hora IS
  'Horario diario do backup (hora local do servidor). O agendador confere de minuto em minuto se a janela chegou.';

INSERT INTO plataforma.configuracao_plataforma (id) VALUES (1);

GRANT SELECT, INSERT, UPDATE ON plataforma.configuracao_plataforma TO niner_app;
