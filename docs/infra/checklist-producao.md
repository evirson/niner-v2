# Checklist de produção — subir o Niner no VPS

Resposta à pergunta "o que foi entregue já aceita a parte grátis?" (2026-08-18).

**Funcionalmente, sim.** O caminho do cliente gratuito está fechado e testado: landing → cadastro
(conta ATIVA no plano Gratuito, sem prazo) → ERP inteiro liberado → cota de 100 vendas/mês com
aviso, tolerância e bloqueio → painel *Minha Conta* → inclusão de CNPJ. 774 testes verdes.

**Operacionalmente, não ainda.** Cinco itens abaixo são **bloqueadores** — não por perfeccionismo,
mas porque cada um tem consequência concreta com cliente real dentro.

## 🔴 Bloqueadores (antes do primeiro cadastro de verdade)

> **Situação em 2026-08-19:** ✅ **1 e 4 resolvidos**. 2, 3 e 5 têm desenho decidido pelo dono do
> produto — configuração no banco, editável pelo backoffice (ver "Decisão sobre configuração").

1. ✅ **RESOLVIDO (2026-08-19).** ~~**`/api/admin/**` está `permitAll`**~~ (TODO(jwt) em `SegurancaConfig`). Publicar `api.` sem
   travar isso expõe o gerenciador de marketing — **nome, e-mail e WhatsApp de leads** — para
   qualquer um na internet. É vazamento de dado pessoal (LGPD), não "detalhe de segurança".
   *Feito:* JWT de staff (`aud=plataforma`, ADR-009) com **decoder próprio por superfície** —
   `/api/v1` só aceita `aud=tenant`, `/api/admin` só aceita `aud=plataforma`, e o token trocado de
   lugar é recusado na porta. Login em `POST /api/admin/sessao`; primeiro `SUPER_ADMIN` criado por
   `NINER_STAFF_INICIAL_EMAIL`/`_SENHA` **apenas se a tabela estiver vazia** (`StaffBootstrap`).
   Coberto por `StaffAdminSegurancaTest` (5 casos).
2. **Segredos com valor de desenvolvimento.** `niner.jwt.secret` tem default
   `niner-dev-secret-…` no `application.yml`: quem conhece esse valor **forja token de qualquer
   tenant** e entra em qualquer loja. Idem `niner.seguranca.chave-segredos` (cifra a senha do
   certificado fiscal) e as senhas do Postgres (`dev_app`/`dev_owner`). Todos precisam vir de
   variável de ambiente no VPS, gerados na hora.
3. **Backup do Postgres e do MinIO.** Hoje não existe nenhum. Com cliente real há dado fiscal com
   **guarda legal de 5 anos** e dado pessoal sob LGPD; perder o disco do VPS é perder o negócio do
   lojista. `pg_dump` diário + cópia fora do VPS + **um restore testado** (spec §3.6: RPO 24h).
4. ✅ **RESOLVIDO (2026-08-19).** ~~Sem rate limit no `/api/publico/**`~~ — `LimiteRequisicaoFilter`
   com dois baldes por IP (escrita 10/min, beacon 120/min, ajustáveis por env), 429 em Problem
   Details com `Retry-After`. Leitura de catálogo e **webhook de gateway ficam de fora** (recusar
   notificação é perder confirmação de pagamento). Em produção, manter também `limit_req` no nginx
   e ligar `NINER_LIMITE_CONFIAR_PROXY=true` — sem isso o filtro conta o IP do proxy, não o do
   visitante. Coberto por `LimiteRequisicaoTest` (4 casos).
5. **Não existe recuperação de senha nem e-mail transacional.** Lojista que esquece a senha fica
   trancado para fora, e nenhum aviso de cota chega por e-mail. Decisão do dono do produto:
   (a) aceitar reset manual pelo suporte no começo, ou (b) implementar antes de abrir cadastro.

## Decisão sobre configuração (2026-08-19, dono do produto)

Os itens 2, 3 e 5 passam a ser **configuráveis pelo backoffice**, com a divisão que a segurança
exige:

| Camada | Onde vive | Exemplos |
|---|---|---|
| **Nunca no banco** | variável de ambiente no VPS | senha do Postgres, `niner.jwt.secret`, chave mestra `chave-segredos` |
| **No banco, cifrado** com a chave mestra | `plataforma.configuracao_plataforma` | senha do SMTP, access token do Mercado Pago, futuras credenciais de marketplace |
| **No banco, em claro** | idem | host/porta/remetente do SMTP, agenda e retenção do backup, parâmetros comerciais (já em `parametro_comercial`) |

O motivo de a chave mestra ficar fora: é ela que cifra o resto: guardá-la dentro do que ela
protege faz um dump de banco valer tudo. Mesmo princípio já aplicado em
`fiscal_certificado.senha_cifrada` (ADR-005).

## ⚠️ VPS compartilhado com outros projetos (2026-08-19)

O Docker do VPS **já roda outros projetos**. Antes de fixar qualquer porta:
`ss -ltnp` (ou `docker ps --format '{{.Names}}: {{.Ports}}'`) e escolher faixa livre — o
`.env` do compose já parametriza todas as portas (`NINER_DB_PORT`, `NINER_API_PORT`,
`NINER_MINIO_PORT`…). É a mesma armadilha que apareceu na máquina de desenvolvimento, onde a
8080 estava tomada por outro sistema. Só 80/443 são fixas, no proxy.

## 🟡 Importantes (podem entrar logo depois, mas com data)

- **Credencial real do GCS** (ADR-013): sem ela, upload/exclusão de **foto de produto** falha em
  runtime — a API sobe normal e quebra só no uso.
- **MinIO de produção** com credencial própria e ciclo de vida (XML fiscal WORM 5 anos, ADR-014).
- **`noindex` em `app.` e `admin.`** + HSTS; sitemap só no apex. App indexado dilui o SEO da
  landing, que é o canal de aquisição.
- **Compose de produção + rotina de deploy**: migrations rodam como `niner_owner` (P8), a app
  como `niner_app`, e o Flyway **não** roda pela aplicação.
- **Logs persistentes e alerta mínimo** (a API caiu? a fila de webhook parou?).
- **`admin/` não existe** como app React — o host fica reservado. **A API do backoffice existe**
  (funil, leads, contas perto do limite) e agora está autenticada; falta só a interface, que
  passou a ser a próxima tela grande porque é onde a configuração de SMTP/backup vai morar.

## 🟢 Já pronto

Modelo comercial e cota (ADR-015) · painel do assinante · multi-CNPJ · landing com SEO
(JSON-LD/OG/sitemap/robots) · medição de aquisição first-party + funil no control-plane (ADR-017)
· cobrança Mercado Pago ponta a ponta contra gateway falso, com webhook idempotente e worker que
reconsulta o gateway (ADR-016) · RLS + filtro explícito de tenant em toda query (P8).

## Sobre testar a assinatura com token de produção

1. **Cobra de verdade.** Use uma faixa temporária de valor mínimo (`UPDATE plataforma.plano SET
   preco_mensal = 1.00 WHERE …`) ou aceite o débito real da primeira cobrança.
2. **O webhook precisa de URL pública** — `https://api.niner.com.br/api/publico/webhooks/mercadopago`
   em `NINER_MP_NOTIFICATION_URL`, e o mesmo endereço cadastrado no painel do Mercado Pago, com o
   **segredo do webhook** em `NINER_MP_WEBHOOK_SECRET` (sem ele a assinatura não é validada).
3. **Antes disso, se possível, feche o sandbox**: falta só habilitar chave PIX na conta de teste
   (hoje dá 401 `Unauthorized use of live credentials`). Provar o fluxo sem dinheiro real é mais
   barato que descobrir um detalhe de payload cobrando de você mesmo.
4. Conferir na primeira cobrança real: fatura vira `PAGA`, assinatura muda de faixa **sozinha**
   (quem promove é o worker, não a tela) e o evento fica em `plataforma.webhook_gateway` com
   `processado_em` preenchido.

## Ordem sugerida para amanhã

DNS propagado → nginx/Caddy com os 4 hosts + TLS + `limit_req` + bloqueio de `/api/admin` →
segredos gerados no VPS → compose de produção (db/minio/api) + migrations → fronts publicados com
`config.js` apontando para `api.` → backup diário com restore testado → só então abrir cadastro.
