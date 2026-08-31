#!/bin/bash
# Le a aliquota efetiva do Simples (pTotTribSN) de uma NFS-e ja emitida, direto do SEFIN.
#
# POR QUE ESTE SCRIPT EXISTE: o SEFIN exige pTotTribSN de optante do Simples — sem ele a DPS
# volta E0712, e omitir o bloco totTrib volta E1235 (docs/MODULONFSE.md §2.6, disparos 9 e 10).
# O numero e' do contador e nao esta em nenhum arquivo deste repositorio. Quem ja o tem gravado
# e' o finance-v, e ele aparece no XML de qualquer nota que aquele sistema emitiu.
#
# USO:
#   bash api/scripts/ler-aliquota-simples.sh "<caminho do .pfx>" "<senha>" [nDPS]
#
# ⚠️ A senha entra por ARGUMENTO, nunca no arquivo. Nada aqui e' segredo.
# Somente leitura: nao emite, nao cancela, nao altera nada.
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Uso: bash api/scripts/ler-aliquota-simples.sh \"<.pfx>\" \"<senha>\" [nDPS]"
  echo "     nDPS default = 2000878 (a ultima NFS-e emitida pelo finance-v em 2026-08-29)"
  exit 1
fi

PFX="$1"
SENHA="$2"
NDPS="${3:-2000878}"
BASE="https://sefin.nfse.gov.br/SefinNacional"
CNPJ="22120254000186"
CMUN="4106902"
SERIE=1

ID=$(printf "DPS%s2%s%05d%015d" "$CMUN" "$CNPJ" "$SERIE" "$NDPS")
echo "DPS ..: $ID"

CHAVE=$(curl -sS --cert-type P12 --cert "$PFX:$SENHA" "$BASE/dps/$ID" | tr -d '"')
if [ -z "$CHAVE" ]; then
  echo "Nenhuma NFS-e para essa DPS. Tente outro nDPS (ex.: 2000877, 2000876)."
  exit 1
fi
echo "chave : $CHAVE"

curl -sS --cert-type P12 --cert "$PFX:$SENHA" "$BASE/nfse/$CHAVE" | python3 -c '
import sys, json, base64, gzip, re
d = json.load(sys.stdin)
campo = [k for k in d if "gzip" in k.lower()]
if not campo:
    print("Resposta sem o XML da nota. Campos recebidos:", list(d.keys()))
    raise SystemExit(1)
xml = gzip.decompress(base64.b64decode(d[campo[0]])).decode("utf-8")
print()
for tag in ("pTotTribSN", "opSimpNac", "regApTribSN", "regEspTrib", "cTribNac", "pAliqAplic"):
    achado = re.findall(r"<%s>(.*?)</%s>" % (tag, tag), xml)
    print("%-12s = %s" % (tag, achado if achado else "(ausente)"))
'
