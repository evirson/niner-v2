# Tela — CSRT por UF (backoffice)

**App:** `admin/` (backoffice da plataforma) · **Rota:** `/csrt` · **Arquivo:**
`admin/src/paginas/CsrtPorUf.tsx` · **Criada em:** 2026-08-20

> Segunda tela do backoffice que mexe em segredo (a primeira é Configuração). Vale a mesma regra:
> **o segredo entra e não sai.**

## Para que serve

O **CSRT** (Código de Segurança do Responsável Técnico, NT 2018.005) identifica quem **desenvolve
o software emissor** — a **MITRYUSCASH**, não o lojista e não a Vetor — e é obtido no portal da
SEFAZ de **cada** UF, para o CNPJ do responsável técnico. Ele entra no grupo `infRespTec` do XML
como `idCSRT` + `hashCSRT`.

Enquanto o produto emitia só no Paraná, o código cabia em duas variáveis de ambiente. Como o
Nainer atende as **27 unidades da federação**, virou cadastro por **UF × ambiente** — e cadastro
que precisa mudar **sem deploy**, porque um CSRT novo chega no dia em que entra um lojista de um
estado novo.

## Onde isso aparece para o lojista

Em lugar nenhum — e é de propósito. O lojista nunca vê nem configura o CSRT: ele é da casa de
software. O que ele vê é a consequência, quando falta: a emissão da NF-e modelo 55 é recusada
**antes de assinar** (F11), com a UF citada na mensagem.

## Regras

| # | Regra |
|---|---|
| RN-01 | **Papel:** todo staff **lê**; só **SUPER_ADMIN** grava e remove. |
| RN-02 | **O código nunca volta pela API** — nem para SUPER_ADMIN. A listagem devolve UF, ambiente, `idCSRT` (público: vai no XML em claro), `definido`, observação e data. |
| RN-03 | **Em branco mantém.** Salvar uma UF já cadastrada com o campo CSRT vazio conserva o código gravado — permite corrigir só o `idCSRT` ou a observação. |
| RN-04 | **Ambiente faz parte da chave.** Homologação (2) e produção (1) são cadastros separados no portal da SEFAZ; usar o de homologação em produção só aparece na primeira venda real. |
| RN-05 | **Remover confirma.** Sem CSRT, a UF que o exige para de autorizar na hora — a tela avisa isso no `confirm`. |
| RN-06 | `idCSRT` tem **exatamente 2 dígitos** (`pattern` do XSD). A tela filtra não-dígitos na digitação e o backend valida de novo. |
| RN-07 | UF fora das 27 é recusada com 400. |

## Contrato de API

| Método | Rota | Papel | Resposta |
|---|---|---|---|
| `GET` | `/api/admin/fiscal/csrt` | staff | `[{uf, ambiente, idCsrt, definido, observacao, atualizadoEm}]` |
| `PUT` | `/api/admin/fiscal/csrt/{uf}/{ambiente}` | SUPER_ADMIN | corpo `{idCsrt, csrt, observacao}` → a linha salva |
| `DELETE` | `/api/admin/fiscal/csrt/{uf}/{ambiente}` | SUPER_ADMIN | 204 · 404 se não existir |

## Modelo de dados

`cfg_csrt_resptec` (**V046**) — `uf` + `ambiente` (PK), `id_csrt`, `csrt_cifrado`, `observacao`,
`atualizado_em`. **Global, sem `id_tenant` e sem RLS**, igual a `cfg_uf_autorizador`: é dado da
casa de software, o mesmo para todos os lojistas. `csrt_cifrado` é AES-256-GCM pela chave mestra
(`SegredoCifrador`), que vive fora do banco.

⚠️ É o **único** `cfg_*` que `niner_app` escreve — os outros são carga por script do dono. Coberto
por `PrivilegiosNinerAppTest.csrtPorUfEhEscritoPelaAplicacaoDiferenteDasOutrasTabelasDeReferencia`,
porque o teste de integração conecta como superusuário e não enxergaria a falta do `GRANT`.

`cfg_uf_autorizador.exige_csrt` (mesma migration) diz se a UF exige o par **naquele modelo**: o PR
cobra na NF-e 55 e não cobra na NFC-e 65.

## Fallback de ambiente

`NINER_FISCAL_RESPTEC_ID_CSRT`/`_CSRT` continuam valendo para dev/CI e para a primeira subida, e
**só para a UF declarada em `NINER_FISCAL_RESPTEC_UF`** (default `PR`). Sem essa amarra o fallback
seria curinga e assinaria nota de qualquer estado com o código de um só — `cStat 974`, com um
diagnóstico que aponta para o lugar errado.

## Testes

`CsrtPorUfTest` (10): código de um estado não vale para outro, fallback restrito à UF que o
declara, ambiente como parte da chave, segredo que não volta pela API nem fica em claro no banco,
"em branco mantém", SUPORTE lê mas não grava, UF inválida, remoção inexistente, exigência vinda da
tabela da UF e a mensagem de F11 citando a UF.

## Ver também

- `docs/MODULOFISCAL.md` §9.9 — o CSRT, o cálculo do hash e as três formas de errar.
- `docs/telas/fiscal-configuracao.md` — configuração fiscal **do lojista** (outra coisa: certificado,
  série, ambiente, CSC).
