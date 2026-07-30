# Spec: Conta Corrente                          Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-30 · Módulo(s): `financeiro` (contacorrente) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

`conta_corrente`/`conta_corrente_movimento` eram o único módulo do `financeiro` legado ainda
fora do v1 (§3.3.7) — o lojista não tinha onde cadastrar as contas bancárias da empresa nem
lançar manualmente um extrato (depósitos, saques, tarifas, transferências entre contas).

## Solução proposta

Duas telas no padrão de cadastro consolidado: **Conta Corrente** (cadastro da conta bancária
em si) e **Movimentação de Conta Corrente** (`docs/telas/conta-corrente-movimento.md`, lançamento
manual do extrato). Esta spec cobre só a primeira.

## Decisões (confirmadas com o dono do produto antes de implementar)

1. **PK de negócio, não surrogate** — `id_conta_corrente` é o próprio número/código da conta
   bancária (ex.: "001-12345-6"), digitado pelo usuário na criação e **imutável depois** — mesmo
   padrão de `cadastros.planocontas` (`docs/telas/plano-contas.md`). O legado
   (`db/051_CONTA_CORRENTE.txt`, já removido após a migração) tinha `ID_CONTA_CORRENTE
   VARCHAR(20)` como PK, sem gerador nenhum — confirma que é uma chave de negócio, não um id
   sequencial.
2. **`id_banco` é uma FK de verdade pra `cfg_banco`** (tabela de referência global, código
   FEBRABAN + nome, seed de 34 bancos comuns direto na migration) — o formulário mostra o nome
   do banco automaticamente ao digitar o código (pedido separado, 2026-07-30, mesmo padrão do
   NCM em `docs/telas/produto.md`). `id_agencia` continua texto livre — não existe uma lista
   global finita de agências.
3. **Tem `ativo`, diferente de Plano de Contas** — exclusão com vínculo em
   `conta_corrente_movimento` inativa em vez de excluir (mesmo padrão de `cadastros.fornecedor`),
   não responde só 409 como Plano de Contas (que não tem essa coluna).

## Regras de negócio

### Campos

`id_conta_corrente` (PK, texto, imutável), `id_empresa` (FK, obrigatório — qual empresa é dona
da conta), `id_banco` (FK pra `cfg_banco`), `id_agencia` (texto livre), `descricao` (texto,
obrigatório — como o operador reconhece a conta nas telas de lançamento),
`ativo` (boolean, default true), `data_abertura` (date, opcional).

### Exclusão com fallback de inativar

Único vínculo possível: `conta_corrente_movimento.id_conta_corrente`. Checagem antes do DELETE
(mesmo motivo das demais telas — FK violada aborta a transação inteira no Postgres).

## Contrato de API

```
GET    /api/v1/contas-corrente                        listagem paginada (busca, status)
GET    /api/v1/contas-corrente/opcoes                  lista enxuta (só ativas) p/ selects de outras telas
GET    /api/v1/contas-corrente/{idContaCorrente}       busca por código
POST   /api/v1/contas-corrente                         cria
PUT    /api/v1/contas-corrente/{idContaCorrente}       atualiza (código do corpo é ignorado)
DELETE /api/v1/contas-corrente/{idContaCorrente}        exclui ou inativa

GET    /api/v1/bancos/{codigo}                         nome do banco (autopreenchimento, 404 se não existir)
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), sem restrição de papel — mesma decisão
de produto dos demais cadastros.

## Critérios de aceitação (viram testes)

- Dado dados completos, quando cria, então grava com sucesso e devolve o registro (nome da
  empresa e do banco resolvidos por JOIN).
- Dado um código já existente, quando tenta criar de novo, então 409.
- Dado um banco inexistente, quando tenta criar/atualizar, então 400.
- Dado uma empresa inexistente, quando tenta criar/atualizar, então 400.
- Dado uma conta existente, quando atualiza tentando trocar o código, então o código do path
  prevalece — o registro continua com o código original.
- Dado uma conta sem vínculo, quando exclui, então apaga de verdade.
- Dado uma conta com movimentação vinculada, quando exclui, então inativa (`ativo = false`) em
  vez de apagar.
- Dado contas ativas e inativas, quando lista com `status=INATIVOS`/`ATIVOS`/`TODOS`, então
  filtra corretamente (padrão é `ATIVOS`).
- Dado uma busca por código ou descrição, então encontra a conta certa.
- Dado `/opcoes`, então só traz contas ativas.
- Dado uma conta de outro tenant, então não aparece na busca nem pode ser encontrada (RLS).

Cobertos por `ContaCorrenteCrudTest` (10 testes) + `BancoCrudTest` (2 testes).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `financeiro.contacorrente.lista`** e **`financeiro.contacorrente.form`** — ver
  `web/src/components/AjudaDaTela.tsx`. `url_video`: `NULL` por ora.

## Impacto no banco

`db/migration/V028__financeiro_conta_corrente.sql` — tabelas `conta_corrente` e
`cfg_banco` (referência global de bancos, sem RLS, seed de 34 códigos FEBRABAN comuns dentro da
própria migration — diferente de `cfg_produto_ncm`, que é carregada por script separado, porque
aqui a lista é pequena/estável e `id_banco` é `NOT NULL`, então os dados precisam existir em
qualquer ambiente que rode as migrations, inclusive testes/CI). RLS em `conta_corrente`.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Conciliação bancária automática (importar OFX/CSV do banco)** — só lançamento manual.
- **Transferência entre contas correntes** — cada lançamento é isolado; não há um "par" de
  débito numa conta + crédito noutra numa operação só.
- **Saldo corrente calculado/exibido na tela de Conta Corrente** — fica pra
  `docs/telas/conta-corrente-movimento.md` (RN de totais) ou para o Fechamento de Caixa.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Cadastro de uma conta corrente em menos de 30 segundos.
