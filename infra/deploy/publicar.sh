#!/usr/bin/env bash
# Publica o Nainer no VPS: build dos três fronts + migrations + API.
# Idempotente — pode rodar a cada atualização.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DESTINO_ESTATICO="${DESTINO_ESTATICO:-/var/www/nainer}"
COMPOSE="docker compose -f $RAIZ/docker-compose.prod.yml --env-file $RAIZ/.env"

cd "$RAIZ"
[ -f .env ] || { echo "ERRO: .env não existe. Rode infra/deploy/gerar-segredos.sh primeiro." >&2; exit 1; }
source .env

echo "==> 1/5 Banco e object storage"
$COMPOSE up -d db minio
$COMPOSE up minio-init

echo "==> 2/5 Migrations (como niner_owner — a app nunca roda migration, P8)"
$COMPOSE --profile migrate run --rm flyway

echo "==> 3/5 API"
$COMPOSE up -d --build api

echo "==> 4/5 Fronts (build estático — dev server não vai para produção)"
for app in site web admin; do
  echo "    · $app"
  docker run --rm -v "$RAIZ/$app:/app" -w /app node:26-alpine sh -c "npm ci --silent || npm install --silent; npm run build"
  sudo mkdir -p "$DESTINO_ESTATICO/$app"
  sudo rsync -a --delete "$RAIZ/$app/dist/" "$DESTINO_ESTATICO/$app/"
done

echo "==> 5/5 Apontando os fronts para a API deste domínio (config de runtime, sem rebuild)"
for app in site web admin; do
  printf "window.NINER_API_BASE = 'https://api.%s';\n" "$NINER_DOMINIO" | sudo tee "$DESTINO_ESTATICO/$app/config.js" >/dev/null
done
printf "window.NINER_WEB_BASE = 'https://app.%s';\n" "$NINER_DOMINIO" | sudo tee -a "$DESTINO_ESTATICO/site/config.js" >/dev/null
# O app do lojista precisa saber o endereço do site (link "criar conta" no login).
printf "window.NINER_SITE_BASE = 'https://%s';\n" "$NINER_DOMINIO" | sudo tee -a "$DESTINO_ESTATICO/web/config.js" >/dev/null

# Corpo do 429 gerado pelo PRÓPRIO nginx (limit_req). Sem este arquivo a recusa sai como página
# HTML, o front faz response.json() e estoura no meio do tratamento do erro — some justamente a
# mensagem "aguarde um instante" que existe pra ser lida. Ver `error_page 429` em infra/nginx.
sudo mkdir -p "$DESTINO_ESTATICO/erro"
printf '%s\n' '{"type":"urn:nainer:erro:limite-de-requisicoes","title":"Muitas requisições","status":429,"detail":"Você fez muitas tentativas seguidas. Aguarde um instante e tente de novo."}' \
  | sudo tee "$DESTINO_ESTATICO/erro/429.json" >/dev/null

echo
echo "✅ Publicado. Confira:"
echo "   curl -sf https://api.$NINER_DOMINIO/actuator/health"
echo "   https://$NINER_DOMINIO   https://app.$NINER_DOMINIO   https://admin.$NINER_DOMINIO"
echo
echo "Depois do primeiro deploy, no backoffice: preencher SMTP, ligar o backup e RODAR UM BACKUP"
echo "manual — descobrir que a credencial está errada na primeira madrugada é caro."
