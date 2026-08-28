# Checklist de produção — subir o Nainer no VPS

Resposta à pergunta "o que foi entregue já aceita a parte grátis?" (2026-08-18).

**Funcionalmente, sim.** O caminho do cliente gratuito está fechado e testado: landing → cadastro
(conta ATIVA no plano Gratuito, sem prazo) → ERP inteiro liberado → cota de 100 vendas/mês com
aviso, tolerância e bloqueio → painel *Minha Conta* → inclusão de CNPJ. 774 testes verdes.

**Operacionalmente, não ainda.** Cinco itens abaixo são **bloqueadores** — não por perfeccionismo,
mas porque cada um tem consequência concreta com cliente real dentro.

## 🔴 Bloqueadores (antes do primeiro cadastro de verdade)

> **Situação em 2026-08-19:** ✅ **os cinco resolvidos** (795 testes, 0 falhas). O que resta para
> abrir cadastro real não é mais código: é **configurar** (segredos no VPS, SMTP e backup pelo
> backoffice) e **fazer um restore de teste**.

1. ✅ **RESOLVIDO (2026-08-19).** ~~**`/api/admin/**` está `permitAll`**~~ (TODO(jwt) em `SegurancaConfig`). Publicar `api.` sem
   travar isso expõe o gerenciador de marketing — **nome, e-mail e WhatsApp de leads** — para
   qualquer um na internet. É vazamento de dado pessoal (LGPD), não "detalhe de segurança".
   *Feito:* JWT de staff (`aud=plataforma`, ADR-009) com **decoder próprio por superfície** —
   `/api/v1` só aceita `aud=tenant`, `/api/admin` só aceita `aud=plataforma`, e o token trocado de
   lugar é recusado na porta. Login em `POST /api/admin/sessao`; primeiro `SUPER_ADMIN` criado por
   `NINER_STAFF_INICIAL_EMAIL`/`_SENHA` **apenas se a tabela estiver vazia** (`StaffBootstrap`).
   Coberto por `StaffAdminSegurancaTest` (5 casos).
2. ⚠️ **CONTINUA VALENDO — é configuração, não código.** **Segredos com valor de desenvolvimento.** `niner.jwt.secret` tem default
   `niner-dev-secret-…` no `application.yml`: quem conhece esse valor **forja token de qualquer
   tenant** e entra em qualquer loja. Idem `niner.seguranca.chave-segredos` (cifra a senha do
   certificado fiscal) e as senhas do Postgres (`dev_app`/`dev_owner`). Todos precisam vir de
   variável de ambiente no VPS, gerados na hora.
3. ✅ **RESOLVIDO (2026-08-19).** ~~**Backup do Postgres e do MinIO.** Hoje não existe nenhum.~~
   *Feito:* `BackupService` roda `pg_dump --format=custom`, envia para o MinIO em
   `plataforma/backup/`, aplica a **retenção configurada** e grava o resultado (inclusive o erro)
   na tela de configuração — backup que falha em silêncio é indistinguível de backup que funciona.
   Agenda (horário + retenção) é editável no backoffice e o `BackupJob` confere a janela a cada
   minuto, então mudar o horário vale no mesmo dia. Disparo manual em
   `POST /api/admin/backup/executar` (SUPER_ADMIN) — use no dia do deploy, para não descobrir na
   primeira madrugada que a credencial estava errada.
   🔴 **Pré-requisito de infra:** o dump precisa do papel **`niner_backup` (BYPASSRLS)** criado no
   `db/bootstrap/00_roles.sql`, e `NINER_BACKUP_USUARIO`/`NINER_BACKUP_SENHA` apontando para ele.
   Com `FORCE ROW LEVEL SECURITY`, `niner_app` **e `niner_owner`** enxergam zero linha (medido:
   `count(*) FROM empresa` = 0 para o owner, 3 para o superusuário) — o serviço recusa rodar
   nessa condição. Falta ainda **cópia fora do VPS** e um **restore testado**: isso é operação, e
   nenhum código substitui.
   ~~Texto original:~~ Com cliente real há dado fiscal com
   **guarda legal de 5 anos** e dado pessoal sob LGPD; perder o disco do VPS é perder o negócio do
   lojista. `pg_dump` diário + cópia fora do VPS + **um restore testado** (spec §3.6: RPO 24h).
4. ✅ **RESOLVIDO (2026-08-19).** ~~Sem rate limit no `/api/publico/**`~~ — `LimiteRequisicaoFilter`
   com dois baldes por IP (escrita 10/min, beacon 120/min, ajustáveis por env), 429 em Problem
   Details com `Retry-After`. Leitura de catálogo e **webhook de gateway ficam de fora** (recusar
   notificação é perder confirmação de pagamento). Em produção, manter também `limit_req` no nginx
   e ligar `NINER_LIMITE_CONFIAR_PROXY=true` — sem isso o filtro conta o IP do proxy, não o do
   visitante. Coberto por `LimiteRequisicaoTest` (6 casos).

   ⛔ **Correção de 2026-08-27 (auditoria de segurança): o filtro lia o cabeçalho ERRADO.** Ele
   pegava o **primeiro** elemento de `X-Forwarded-For` — que é exatamente o que o **cliente**
   manda. O nginx usa `proxy_add_x_forwarded_for`, que **acrescenta** o IP real ao **fim** da lista
   e preserva o começo. Bastava `curl -H "X-Forwarded-For: 10.0.0.$RANDOM"` em laço para criar um
   balde novo a cada requisição e **o limite deixar de existir** — justamente em produção, onde
   `confiar-proxy` está ligado. Sobrava só o `limit_req` do nginx (60 r/m por
   `$binary_remote_addr`), **6× o teto pretendido**, e a segunda camada do desenho deixava de
   existir. O alvo pior era o código de 4 dígitos do login em duas etapas.

   Hoje o filtro lê **`X-Real-IP`**, que o nginx **sobrescreve** com `$remote_addr`
   (`infra/nginx/nainer.conf:230`) e o cliente não tem como forjar. O último elemento do
   `X-Forwarded-For` é a reserva, para um proxy que não mande `X-Real-IP`: é o salto mais próximo, o
   único que o cliente não escolhe.

   ⚠️ **Se trocar de proxy, confira isto primeiro.** A regra que torna o valor confiável não está no
   Java — está na configuração do proxy que sobrescreve o cabeçalho. Um proxy que só *acrescenta*
   reabre o buraco, e nada no código acusa.
5. ✅ **RESOLVIDO (2026-08-19).** ~~**Não existe recuperação de senha nem e-mail transacional.**~~
   *Feito:* `POST /api/publico/recuperar-senha` (responde 204 sempre, para não revelar quem é
   cliente) e `POST /api/publico/redefinir-senha` com token de **uso único**, validade de 2h e
   **hash** no banco. O SMTP é configurado no backoffice (senha cifrada) e serve também para
   avisos e, adiante, envio de NF-e ao cliente do lojista. Falta a tela no `web/`
   ("Esqueci minha senha" + página de redefinição) e **preencher o SMTP**.
   ~~Texto original:~~ Lojista que esquece a senha fica
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
- ✅ **`noindex` em `app.` e `admin.`** + **HSTS** (ligado em 2026-08-19, depois que os 4 hosts
  passaram a servir TLS de verdade); sitemap só no apex. App indexado dilui o SEO da landing,
  que é o canal de aquisição.
- **Compose de produção + rotina de deploy**: migrations rodam como `niner_owner` (P8), a app
  como `niner_app`, e o Flyway **não** roda pela aplicação.
- **Logs persistentes e alerta mínimo** (a API caiu? a fila de webhook parou?).
- ✅ **`admin/` construído e publicado** em `https://admin.nainer.com.br` (painel/funil, contas,
  leads, configuração de SMTP/backup/Mercado Pago). Falta o dono do produto **preencher o SMTP**
  — sem ele a recuperação de senha responde 204 e não manda e-mail nenhum.

## ⚠️ Backup "configurado" não é backup (2026-08-19)

O backup automático passou o primeiro dia no ar gravando `backup_ultimo_status = ERRO` sem
produzir arquivo nenhum: a role tinha `BYPASSRLS` e `SELECT` nas tabelas, mas **nenhum `SELECT`
em sequência**, e o `pg_dump` aborta com código 1 por causa disso (corrigido na `V044`). A lição
vale além do caso: **rodar um backup manual pelo backoffice logo depois de configurar**, e
conferir o arquivo no MinIO — a única prova de que a rotina funciona é o arquivo existir. Antes
do primeiro cliente pagante, testar também um **restore**.

## 🟢 Já pronto

Modelo comercial e cota (ADR-015) · painel do assinante · multi-CNPJ · landing com SEO
(JSON-LD/OG/sitemap/robots) · medição de aquisição first-party + funil no control-plane (ADR-017)
· cobrança Mercado Pago ponta a ponta contra gateway falso, com webhook idempotente e worker que
reconsulta o gateway (ADR-016) · RLS + filtro explícito de tenant em toda query (P8).

## Sobre testar a assinatura com token de produção

1. **Cobra de verdade.** Use uma faixa temporária de valor mínimo (`UPDATE plataforma.plano SET
   preco_mensal = 1.00 WHERE …`) ou aceite o débito real da primeira cobrança.
2. **O webhook precisa de URL pública** — `https://api.nainer.com.br/api/publico/webhooks/mercadopago`
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
