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

## Revisão 2026-08-24 — o campo do código passou a ter tamanho mínimo

**O que aconteceu.** O credenciamento da casa de software na SEFAZ/PR entrega **duas coisas com
cara de código**: o **número do credenciamento** (5 dígitos, identifica a empresa no portal) e o
**CSRT** (36 caracteres, o segredo da NT 2018.005). O primeiro foi cadastrado no campo do segundo.
A tela aceitou — o campo só tinha `@Size(max = 200)` — e o defeito só apareceu na transmissão, como
**`cStat 974`: "CNPJ do responsável técnico diverge do cadastrado"**, uma mensagem que fala em CNPJ
e manda o diagnóstico inteiro para o lado errado.

**A correção.** `@Pattern` de **20 a 200 caracteres** em `SalvarCsrtRequest.csrt`, com
`Flag.DOTALL`, e mensagem que nomeia a confusão: *"O CSRT tem 36 caracteres — confira se você não
copiou o número do credenciamento por engano."*

Três detalhes que a anotação precisa respeitar:

- **Vazio continua válido.** Em branco significa **manter o código gravado** (a convenção do projeto
  para segredo, a mesma da senha de SMTP). Por isso é um `@Pattern` com alternativa vazia
  (`"|.{20,200}"`) e **não** um `@Size(min = 20)`, que barraria justamente quem só quer corrigir a
  observação ou o `idCSRT`.
- **20, e não 36 exatos.** O formato vem de NT nacional, mas quem gera o código é **cada UF**. Piso
  generoso barra a confusão sem engessar o produto nas outras 26 unidades.
- **`@Pattern` usa `matches()`**, que já ancora nas duas pontas — âncoras explícitas (`^`, `\z`) são
  redundantes e, se o escape se perder na edição, viram caractere literal e o regex passa a recusar
  tudo. Foi o que aconteceu na primeira tentativa desta correção.

**O número do credenciamento não vai em campo nenhum.** O grupo `infRespTec` leva `CNPJ`,
`xContato`, `email`, `fone`, `idCSRT` e `hashCSRT` — não existe tag para ele. O lugar dele é a
**Observação**, que é texto livre.

**Teste:** `CsrtPorUfTest.recusaCodigoCurtoDemaisComoCsrt` — manda `79413` no campo do código e
espera **400**.

## 🔴 Revisão 2026-08-24 (parte 2) — o CSRT correto **não** resolveu o `cStat 974`

Fechando o ciclo da revisão acima, para que ninguém repita o diagnóstico: **o piso de tamanho era
uma correção real e necessária, e ainda assim não era a causa do 974.**

Com o código de 36 caracteres gravado — conferido **sem revelar o segredo**, pela matemática do
cifrado: `octet_length(decode(csrt_cifrado,'base64'))` = **64** = 12 (nonce) + **36** + 16 (tag) —
a retransmissão da mesma venda voltou **974 outra vez**.

### Como a SEFAZ chega ao 974, e por que a mensagem engana

Ela **não** compara o `<infRespTec><CNPJ>` com o CNPJ do emitente. O caminho é:

1. busca o CSRT cadastrado **pelo `idCSRT`**;
2. lê para qual CNPJ aquele código foi emitido;
3. compara com o CNPJ declarado no `infRespTec`.

Divergiu no passo 3 → **974**. A consequência prática é contraintuitiva: **um `idCSRT` errado
produz uma mensagem sobre CNPJ**, e o identificador não é citado em lugar nenhum.

### ⚠️ O `idCSRT` vem pré-preenchido com `01` — e isso é a mesma armadilha

O formulário inicializa `idCsrt` em `'01'`. É **chute do formulário, não valor conferido**: quem
cadastra vê um campo preenchido e passa direto. Se o portal emitiu o CSRT com identificador `02`
(ou qualquer outro), o cadastro fica silenciosamente errado e o erro só aparece na SEFAZ, com o
texto acima.

É a **mesma família de defeito** do campo do CSRT sem piso: *um default com cara de valor
conferido*. ⏭️ **Pendente de confirmação com o portal:** se for esse o caso, o default sai e o campo
passa a nascer vazio e obrigatório — um campo em branco é honesto sobre o que ele não sabe.

### O que conferir no portal da SEFAZ, nesta ordem

| # | Verificar | Onde corrigir |
|---|---|---|
| 1 | O **`idCSRT`** (2 dígitos) que veio junto com o código de 36 | `/csrt` |
| 2 | O **CNPJ** para o qual o CSRT foi emitido | `NINER_FISCAL_RESPTEC_CNPJ` (hoje cai no default `37829453000135` do `application.yml`) |
| 3 | Propagação do credenciamento (saiu em 24/08) | só depois de descartar 1 e 2 |

**Estado:** 9 documentos `REJEITADO` com 974, **nenhum autorizado**. Nada preso, nada a estornar.
