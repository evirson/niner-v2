# Spec: Tipo de Carteira (formas de pagamento)      Status: Aprovada (retroativa)
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-23 (fusão com `moeda` em 2026-07-28) ·
Spec escrita: 2026-08-31 · Módulo(s): `financeiro` · Rota: `/tipos-carteira`

> ⚠️ **Spec RETROATIVA.** A tela existe desde 2026-07-23; o arquivo nasceu em 2026-08-31 para
> fechar a pendência 24 de `docs/PENDENCIAS.md`. Foi **derivada do código**
> (`TipoCarteiraController/Service/Dtos`, `web/src/pages/tipocarteira/`), e escrevê-la revelou um
> defeito real, corrigido no mesmo dia — ver "O guard de exclusão" abaixo.

## O que é

O cadastro das **formas de pagamento** da loja: dinheiro, PIX, cada bandeira de cartão, o
crediário da casa e o vale-mercadoria. Cada linha carrega o que aquela forma faz com o dinheiro —
prazo, faixa de parcelas, taxa da administradora, desconto ou acréscimo — e é o que o PDV oferece
na hora de fechar a venda.

⚠️ **Absorveu o cadastro de `moeda` em 2026-07-28**, que era uma segunda tela com N:N
(`moeda_detalhe`). Não existe mais vínculo N:N: a mesma bandeira em duas categorias são **duas
linhas** (`HIPER` em CARTAO_DEBITO e `HIPER` em CARTAO_CREDITO), porque prazo, taxa e parcelas são
diferentes entre elas. `percDesconto`/`percAcrescimo` vieram de lá.

## Campos, na ordem da tela

| Seção | Campos |
|---|---|
| **Identificação** | Nome da carteira (obrigatório, ≤ 60, maiúsculo) · **Categoria** (obrigatória) |
| **Prazo, parcelas e taxa** | Prazo de pagamento (dias) · Nº mínimo e máximo de parcelas · Taxa da administradora (%) |
| **Desconto / Acréscimo** | % de desconto **ou** % de acréscimo |
| **Recebimento de Crediário** | "Permite receber crediário" |
| **Dados Fiscais (NFC-e)** | `codigoTpag` · `codigoBandeira` · CNPJ da credenciadora |

### A categoria é o que o sistema entende — o nome é só rótulo

`CategoriaCarteira` é um enum fixo: `AVISTA`, `CARTAO_DEBITO`, `CARTAO_CREDITO`, `CREDIARIO`,
`VALE_MERCADORIA`. ⭐ Ela existe porque **`nome_carteira` é texto livre e não serve para decidir
nada**: é a categoria que permite ao Histórico do Cliente separar parcelas de crediário das demais
formas, ao PDV saber que `VALE_MERCADORIA` resgata um vale, e aos relatórios agruparem cartão.
Renomear a carteira nunca muda comportamento; trocar a categoria muda.

### Desconto e acréscimo nunca coexistem — e a checagem é por VALOR

`Informe % de desconto OU % de acréscimo — nunca os dois.` ⚠️ A validação é
**`valor > 0`, não "preenchido"**: toda carteira semeada no signup nasce com
`perc_desconto = 0, perc_acrescimo = 0` ao mesmo tempo, que é o estado neutro. Recusar por
presença quebraria o caso mais comum do sistema (ver `feedback_exclusividade_mutua_por_valor`).

⚠️ **Teto de 100%, no banco e no serviço** — `numeric(5,2)` aceita até 999,99, e a auditoria de
segurança de 2026-08-27 mostrou o custo: 999,99% numa forma de pagamento fechava venda de R$ 1.000
com ~R$ 91. CHECK 0–100 entrou na **V083**, e o teste que prendia o comportamento antigo
(`percentualAcimaDeCemEhAceito`) foi **invertido**, não apagado.

### Os campos fiscais não bloqueiam o cadastro

`codigoTpag`/`codigoBandeira`/`cnpjCredenciadora` (o grupo `pag/detPag` da NFC-e) são **opcionais**
de propósito: quem cobra o preenchimento é a **Conformidade Fiscal**, que aponta a lacuna sem
impedir o cadastro (F11 — avisar antes do balcão, não travar o balcão). Quando preenchido, o CNPJ
é validado com dígito verificador. Sem preencher, os três voltam **`null`, nunca string vazia** —
a Conformidade checa `IS NULL`.

## O guard de exclusão — e o defeito que esta spec revelou

`tipo_carteira` **não tem coluna `ativo`**, então não existe o fallback "inativar em vez de
excluir" do padrão de cadastro: ou exclui, ou responde 409.

⚠️ **Até 2026-08-31 o guard conhecia 2 das 5 FKs.** Conferido no schema
(`SELECT conrelid::regclass FROM pg_constraint WHERE contype='f' AND confrelid='tipo_carteira'::regclass`):

| Tabela que aponta para `tipo_carteira` | Estava no guard? |
|---|---|
| `contas_receber` | ✅ |
| `caixa_detalhe` | ✅ |
| `caixa_mestre` (a carteira de **abertura** do caixa) | ❌ |
| `caixa_fechamento_conferencia` | ❌ |
| `documento_fiscal_pagamento` | ❌ |

**O efeito:** o `DELETE` chegava ao banco, violava a FK, e o `GlobalExceptionHandler` respondia o
409 genérico *"Registro em uso por outro cadastro"* — **sem dizer onde** e **sem caminho de
volta**, já que a tela não oferece inativar. ⛔ E o caso mais provável era o mais banal: a carteira
**DINHEIRO**, que é a carteira de abertura do caixa, presa mesmo num dia sem nenhum lançamento.

**Hoje** o guard cobre as cinco e a mensagem **nomeia o vínculo**: *"Tipo de carteira em uso em
abertura de caixa — não pode ser excluído."*

⚠️ **Por que o teste antigo não pegava:** `excluirCarteiraComLancamentoDeCaixaVinculadoRespondeConflito`
cria o caixa **com** detalhe, então o guard antigo já respondia 409 — o teste passava sem
distinguir guard de FK. Os dois testes novos usam **só** a abertura e **só** a conferência, e
foram verificados **sabotando a correção**: sem ela, reprovam com
*Expected: a string containing "abertura de caixa"*.

## Contrato de API

```
GET    /api/v1/tipos-carteira?busca=&pagina=&limite=&ordenarPor=&direcao=
GET    /api/v1/tipos-carteira/{id}
POST   /api/v1/tipos-carteira
PUT    /api/v1/tipos-carteira/{id}
DELETE /api/v1/tipos-carteira/{id}      → { acao: "excluido" } | 409 nomeando o vínculo
```

`@Tela("tipos-carteira")` na classe; as ações saem do método HTTP pela regra do interceptor.
Ordenação por **allowlist** (`COLUNAS_ORDENAVEIS`) — a coluna do cliente nunca entra concatenada
no SQL. Paginação por número de página, 20 por padrão e teto de 100.

**Unicidade:** nome **por categoria** (`Já existe um tipo de carteira com esse nome nesta
categoria.`), não por nome — é o que permite a mesma bandeira em débito e crédito.

## Quem consome este cadastro

- **PDV** — split-tender, desconto/acréscimo por forma de pagamento, resgate de vale.
- **Recebimento de Crediário** — só carteiras com `permiteReceberCrediario` aparecem (RN007).
- **Abertura de Caixa** — a carteira do fundo de troco; **Sangria** e **Fechamento** contam só a
  carteira de abertura, porque cartão e crediário não estão na gaveta.
- **NFC-e** — `pag/detPag` sai daqui.
- **Relatórios** (Contas a Receber, DRE, Lucratividade) — taxa de cartão e agrupamento por forma.

## Non-goals

- **Inativar** em vez de excluir: exigiria coluna `ativo` e uma decisão de produto sobre o que
  fazer com carteira inativa que ainda aparece no histórico.
- **Vínculo N:N com bandeira** — removido de propósito em 2026-07-28; não reintroduzir.
- **Taxa por faixa de parcelas** (1×, 2–6×, 7–12× com taxas diferentes): hoje a taxa é uma só por
  carteira.
