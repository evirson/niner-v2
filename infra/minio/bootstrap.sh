#!/bin/sh
# Bootstrap do object storage PRIVADO (ADR-014, docs/infra/armazenamento-privado-minio.md).
#
# Roda como job do docker-compose (serviço `minio-init`) usando a conta ROOT do MinIO, e deixa o
# servidor no estado que a API espera. É IDEMPOTENTE: rodar de novo não recria nem estraga nada.
#
# O mesmo script serve para provisionar o VPS no dia da migração — só muda MINIO_ENDPOINT e as
# credenciais. É por isso que ele não depende de nada do compose além das variáveis de ambiente.
set -eu

: "${MINIO_ENDPOINT:?}" "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}"
: "${BUCKET_FISCAL:?}" "${BUCKET_PRIVADO:?}" "${RETENCAO_FISCAL_DIAS:?}" "${VERSAO_ANTIGA_DIAS:?}"
: "${APP_ACCESS_KEY:?}" "${APP_SECRET_KEY:?}"

mc alias set niner "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > /dev/null

# ---------------------------------------------------------------------------------------------
# 1) Bucket FISCAL — WORM. O XML autorizado é imutável (F6) e tem guarda legal de 5 anos.
#    `--with-lock` só funciona na CRIAÇÃO do bucket (Object Lock não pode ser ligado depois),
#    e já liga versionamento junto — os dois andam sempre em par no S3.
#    GOVERNANCE (e não COMPLIANCE): a versão do objeto não pode ser apagada nem sobrescrita, mas
#    um administrador com permissão explícita de bypass ainda consegue intervir. COMPLIANCE não
#    perdoa nem o root, nem por engano nosso — cedo demais para isso.
# ---------------------------------------------------------------------------------------------
if mc ls "niner/$BUCKET_FISCAL" > /dev/null 2>&1; then
  echo "[minio-init] bucket $BUCKET_FISCAL ja existe"
else
  mc mb --with-lock "niner/$BUCKET_FISCAL"
  echo "[minio-init] bucket $BUCKET_FISCAL criado (object lock + versionamento)"
fi
# Retenção padrão: aplicada a todo objeto novo, sem o gravador precisar pedir.
mc retention set --default GOVERNANCE "${RETENCAO_FISCAL_DIAS}d" "niner/$BUCKET_FISCAL"

# ---------------------------------------------------------------------------------------------
# 2) Bucket PRIVADO — foto de cliente e demais anexos pessoais. Versionado (recupera exclusão
#    acidental), mas SEM lock: a LGPD dá ao titular o direito de exclusão, então apagar de
#    verdade precisa ser possível.
# ---------------------------------------------------------------------------------------------
if mc ls "niner/$BUCKET_PRIVADO" > /dev/null 2>&1; then
  echo "[minio-init] bucket $BUCKET_PRIVADO ja existe"
else
  mc mb "niner/$BUCKET_PRIVADO"
  echo "[minio-init] bucket $BUCKET_PRIVADO criado"
fi
mc version enable "niner/$BUCKET_PRIVADO" > /dev/null

# ⚠️ Em bucket versionado, apagar NÃO apaga: cria um "delete marker" e a versão antiga continua
# guardada. Para foto de cliente isso é o oposto do que a LGPD pede — o titular pediu exclusão e o
# dado continuaria lá para sempre. A regra abaixo fecha isso: versão antiga e delete marker órfão
# somem em VERSAO_ANTIGA_DIAS. A janela existe de propósito, para dar tempo de desfazer engano.
if mc ilm rule ls "niner/$BUCKET_PRIVADO" > /dev/null 2>&1; then
  echo "[minio-init] regra de expiracao ja existe em $BUCKET_PRIVADO"
else
  mc ilm rule add --noncurrent-expire-days "$VERSAO_ANTIGA_DIAS" --expire-delete-marker \
    "niner/$BUCKET_PRIVADO" > /dev/null
  echo "[minio-init] $BUCKET_PRIVADO: versao antiga expira em ${VERSAO_ANTIGA_DIAS}d (exclusao de verdade — LGPD)"
fi

# Nenhum dos dois é público. O default do MinIO já é privado; a linha abaixo é explícita de
# propósito — é a diferença que separa este storage do bucket de fotos de produto.
mc anonymous set none "niner/$BUCKET_FISCAL"  > /dev/null
mc anonymous set none "niner/$BUCKET_PRIVADO" > /dev/null

# ---------------------------------------------------------------------------------------------
# 3) Credencial da API — menor privilégio. A API NUNCA usa a conta root.
#    No bucket fiscal ela pode gravar e ler, mas NÃO apagar: a imutabilidade fica garantida duas
#    vezes (política aqui + guarda no código, AreaPrivada.FISCAL_XML). E em lugar nenhum tem
#    s3:BypassGovernanceRetention — vazamento da chave da API não apaga XML fiscal.
# ---------------------------------------------------------------------------------------------
cat > /tmp/niner-app-policy.json <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket", "s3:GetBucketLocation", "s3:ListBucketVersions"],
      "Resource": ["arn:aws:s3:::$BUCKET_FISCAL", "arn:aws:s3:::$BUCKET_PRIVADO"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": ["arn:aws:s3:::$BUCKET_FISCAL/*"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::$BUCKET_PRIVADO/*"]
    }
  ]
}
POLICY

# `user add` em usuário existente atualiza a senha; `policy create` sobrescreve a política.
mc admin user add niner "$APP_ACCESS_KEY" "$APP_SECRET_KEY" > /dev/null
mc admin policy create niner niner-app /tmp/niner-app-policy.json > /dev/null
# `attach` reclama (e sai != 0) se a política já estiver anexada — daí o `|| true`.
mc admin policy attach niner niner-app --user "$APP_ACCESS_KEY" > /dev/null 2>&1 || true

echo "[minio-init] pronto: $BUCKET_FISCAL (WORM ${RETENCAO_FISCAL_DIAS}d) + $BUCKET_PRIVADO + usuario $APP_ACCESS_KEY"
