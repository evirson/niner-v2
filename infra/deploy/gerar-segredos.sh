#!/usr/bin/env bash
# Gera o .env de produção com segredos aleatórios. Roda UMA vez, no VPS.
#
# Por que gerar no servidor: os defaults do application.yml são de desenvolvimento e estão no
# repositório — quem os conhece forja token de qualquer tenant (niner.jwt.secret) e decifra a
# senha do certificado fiscal (chave-segredos). Nenhum dos dois pode sair daqui.
set -euo pipefail

DESTINO="${1:-.env}"
if [ -e "$DESTINO" ]; then
  echo "ERRO: $DESTINO já existe. Não vou sobrescrever segredo em uso." >&2
  echo "      Se quiser rotacionar, guarde o atual antes: mv $DESTINO $DESTINO.bak" >&2
  exit 1
fi

read -rp "Domínio (ex.: nainer.com.br): " DOMINIO
read -rp "E-mail do primeiro SUPER_ADMIN do backoffice: " STAFF_EMAIL

senha() { openssl rand -base64 24 | tr -d '/+=' | cut -c1-24; }

cat > "$DESTINO" <<ENV
# Gerado por infra/deploy/gerar-segredos.sh em $(date -Iseconds)
# 🔴 NUNCA versionar este arquivo. Guarde uma cópia no gerenciador de senhas da Vetor.

NINER_DOMINIO=$DOMINIO

# ---- portas do host (o VPS tem OUTROS projetos: confira com \`ss -ltnp\` antes) ----
# Todas publicadas só em 127.0.0.1; quem fala com a internet é o nginx.
NINER_API_PORT=8080
NINER_DB_PORT=5432
NINER_MINIO_PORT=9000
NINER_MINIO_CONSOLE_PORT=9001

# ---- banco ----
DB_PASSWORD=$(senha)
FLYWAY_OWNER_PASSWORD=$(senha)
DB_APP_PASSWORD=$(senha)
NINER_BACKUP_USUARIO=niner_backup
NINER_BACKUP_SENHA=$(senha)

# ---- segredos da aplicação ----
# JWT: assinatura HS256. Trocar este valor invalida todas as sessões abertas.
NINER_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
# Chave mestra (AES-256, base64 de 32 bytes): cifra senha de certificado fiscal, SMTP e token do
# gateway. ⚠️ PERDER ESTA CHAVE = perder acesso a tudo que ela cifrou. Guarde fora do servidor.
NINER_CHAVE_SEGREDOS=$(openssl rand -base64 32 | tr -d '\n')

# ---- primeiro acesso ao backoffice (criado só se plataforma.staff estiver vazia) ----
NINER_STAFF_INICIAL_EMAIL=$STAFF_EMAIL
NINER_STAFF_INICIAL_SENHA=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-18)
NINER_STAFF_INICIAL_NOME=Administrador Vetor

# ---- MinIO (object storage privado: XML fiscal e dado pessoal) ----
MINIO_ROOT_USER=niner_root
MINIO_ROOT_PASSWORD=$(senha)
NINER_STORAGE_PRIVADO_ACCESS_KEY=niner_app
NINER_STORAGE_PRIVADO_SECRET_KEY=$(senha)
NINER_STORAGE_BUCKET_FISCAL=niner-fiscal
NINER_STORAGE_BUCKET_PRIVADO=niner-privado

# ---- fotos de produto (GCS, ADR-013) — coloque a chave em api/secrets/gcs.json ----
NINER_STORAGE_BUCKET=niner-erp

# ---- cobrança: preferir configurar pelo backoffice (fica cifrado no banco) ----
NINER_MP_ACCESS_TOKEN=
NINER_MP_WEBHOOK_SECRET=
ENV

chmod 600 "$DESTINO"
echo
echo "✅ $DESTINO criado (permissão 600)."
echo
echo "Anote AGORA, em local seguro — a senha inicial do backoffice não é recuperável pelo sistema:"
grep -E '^NINER_STAFF_INICIAL_(EMAIL|SENHA)=' "$DESTINO"
echo
echo "⚠️  Guarde NINER_CHAVE_SEGREDOS fora do servidor. Sem ela, o que foi cifrado não volta."
