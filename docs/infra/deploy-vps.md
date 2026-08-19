# Deploy no VPS — passo a passo

Publicação do Nainer num VPS que **já roda outros projetos**. Tudo aqui é parametrizado por isso.

Pré-requisitos que **não** estão no repositório e sem os quais nada acontece:

| O que | Onde consigo |
|---|---|
| **IP do VPS** e acesso SSH (usuário com `sudo`) | você |
| **DNS propagado** — 5 registros A em `nainer.com.br` + 2 em `niner.com.br` | `docs/infra/dns-producao.md` |
| Docker e Docker Compose no servidor | `docker --version` |
| Saber **qual proxy já roda na 80/443** (nginx? Traefik? nenhum?) | `ss -ltnp \| grep -E ':80\|:443'` |
| Chave de conta de serviço do GCS (fotos de produto) | console do Firebase/GCS |

## 0. Antes de qualquer coisa: varrer as portas

O VPS é compartilhado. Escolha faixas livres **antes** de subir:

```bash
ss -ltnp                                   # o que já escuta
docker ps --format '{{.Names}}: {{.Ports}}'  # o que outros projetos publicaram
```

Anote as escolhidas no `.env` (`NINER_API_PORT`, `NINER_DB_PORT`, `NINER_MINIO_PORT`,
`NINER_MINIO_CONSOLE_PORT`). Só 80/443 são fixas, e são do proxy.

## 1. Código e segredos

```bash
git clone git@github.com:evirson/niner-v2.git /opt/nainer && cd /opt/nainer
git checkout feat/modelo-comercial-assinatura     # até o merge na main

./infra/deploy/gerar-segredos.sh                  # cria .env (600) com tudo aleatório
```

⚠️ **Guarde `NINER_CHAVE_SEGREDOS` fora do servidor.** É ela que cifra senha de certificado
fiscal, SMTP e token do gateway — perdê-la é perder o acesso a tudo que ela cifrou.

Copie a chave do GCS para `api/secrets/gcs.json` (o diretório é gitignored).

## 2. Papel de backup no banco

O `db/bootstrap/00_roles.sql` cria `niner_backup` (com `BYPASSRLS`) no **primeiro** init do
Postgres. Se o banco já existir, crie à mão — e troque a senha de desenvolvimento pela do `.env`:

```sql
CREATE ROLE niner_backup LOGIN PASSWORD '<NINER_BACKUP_SENHA do .env>'
  NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;
GRANT CONNECT ON DATABASE niner_db TO niner_backup;
GRANT USAGE ON SCHEMA public, plataforma TO niner_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public, plataforma TO niner_backup;
```

Sem `BYPASSRLS` o `pg_dump` sai **sem uma linha** das tabelas dos lojistas — e o serviço recusa
executar justamente para isso não passar batido.

## 3. Subir banco, storage, migrations e API

```bash
./infra/deploy/publicar.sh
```

O script sobe `db` + `minio`, roda o `minio-init`, aplica as migrations como `niner_owner`
(a aplicação **nunca** roda migration — P8), sobe a API, faz o build dos três fronts e publica
os estáticos em `/var/www/nainer/{site,web,admin}`, já com o `config.js` apontando para
`https://api.nainer.com.br`.

## 4. Proxy e TLS

Se **não** houver proxy na 80/443:

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
sudo cp infra/nginx/nainer.conf /etc/nginx/sites-available/nainer
sudo ln -s /etc/nginx/sites-available/nainer /etc/nginx/sites-enabled/
sudo mkdir -p /var/www/certbot
sudo certbot certonly --webroot -w /var/www/certbot \
  -d nainer.com.br -d www.nainer.com.br -d app.nainer.com.br \
  -d admin.nainer.com.br -d api.nainer.com.br
sudo certbot certonly --webroot -w /var/www/certbot -d niner.com.br -d www.niner.com.br
sudo nginx -t && sudo systemctl reload nginx
```

Se **já houver** nginx de outro projeto: acrescente os `server` blocks do arquivo ao que existe —
**não instale um segundo**, dois na mesma porta não sobem. Se for Traefik, traduza as regras
(hosts, `limit_req`, cabeçalhos `X-Forwarded-*` e o `noindex` de app/admin).

## 5. Conferir antes de anunciar

```bash
curl -sf https://api.nainer.com.br/actuator/health          # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' https://nainer.com.br
curl -s -o /dev/null -w '%{http_code}\n' https://app.nainer.com.br
curl -s -I https://niner.com.br | head -1                    # 301 para nainer.com.br
curl -s -o /dev/null -w '%{http_code}\n' https://api.nainer.com.br/api/admin/marketing/funil  # 401
```

O último precisa dar **401**: se responder 200, o backoffice está aberto ao mundo com nome,
e-mail e WhatsApp de leads dentro.

## 6. Primeiro acesso e o que só se resolve depois de estar no ar

1. Entrar em `https://admin.nainer.com.br` com `NINER_STAFF_INICIAL_EMAIL`/`SENHA` do `.env`.
2. **Preencher o SMTP** e mandar um teste (a recuperação de senha depende dele).
3. **Ligar o backup** e clicar em *Executar backup agora* — descobrir credencial errada na
   primeira madrugada é caro.
4. **Levar uma cópia do backup para fora do VPS** (rclone/rsync) e **fazer um restore de teste**.
   Backup sem restore testado é esperança, não backup.
5. Preencher `site/src/dados/contato.ts` (WhatsApp, Instagram, e-mail, CNPJ) e republicar o site.
6. Configurar o webhook do Mercado Pago apontando para
   `https://api.nainer.com.br/api/publico/webhooks/mercadopago` e guardar o segredo no backoffice.

## Atualizações seguintes

```bash
cd /opt/nainer && git pull && ./infra/deploy/publicar.sh
```

O script é idempotente. Migration nova é aplicada antes de a API subir com o código novo.
