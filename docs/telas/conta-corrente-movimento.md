# Spec: Movimentação de Conta Corrente             Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-30 · Módulo(s): `financeiro` (contacorrente) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

Ver `docs/telas/conta-corrente.md` — a conta em si já existe cadastrada, mas faltava onde
lançar os movimentos do extrato (depósitos, saques, tarifas, transferências) manualmente.

## Solução proposta

Tela de cadastro completo (lista + form) pro lançamento — **diferente** de `caixa_detalhe`
(ledger imutável, só leitura depois de gravado): aqui o operador pode editar ou excluir um
lançamento já gravado, porque é digitação manual sujeita a erro de captura, não um efeito
colateral automático de outra rotina.

> ⚠️ **Desde 2026-08-14 a premissa "só lançamento manual" deixou de ser verdadeira.** A **baixa
> de Contas a Pagar** com origem `CONTA_CORRENTE` grava aqui um débito automático
> (`ContaPagarService.sincronizarMovimentoDeDinheiro`,
> `api/src/main/java/com/vetor/niner/financeiro/contaspagar/ContaPagarService.java:177-188`),
> identificado por `id_conta_pagar` preenchido e observação "Pagamento da conta a pagar nº N".
> ✅ **Distinguidos e protegidos desde 2026-08-15.** Antes disso esses lançamentos apareciam
> iguais aos manuais e podiam ser **editados ou excluídos sem nenhum aviso** — a baixa continuava
> registrada em `contas_pagar` mas o dinheiro sumia (ou mudava de valor) no banco, e como
> `id_conta_pagar` foi criado **sem FK de propósito** o banco também não impedia nada. Agora:
> - a resposta da API traz `idContaPagar` (nulo em lançamento manual);
> - a grid mostra o badge **"Baixa automática"** no lugar dos ícones de editar/excluir, com o
>   número da conta a pagar no `title`;
> - `atualizar()`/`excluir()` recusam com **409** e apontam a saída — *"Este lançamento foi gerado
>   pela baixa da conta a pagar nº N … Altere ou desfaça o pagamento em Financeiro › Contas a
>   Pagar / Pagas — o movimento da conta corrente acompanha."* (`exigirLancamentoManual`, mesmo
>   espírito de `CaixaService.exigirCaixaAbertoParaDesfazer`).
>
> O guard é estreito e **não atrapalha a própria baixa**: `ContaPagarService` apaga e regrava o
> movimento por SQL direto, sem passar por este serviço. Lançamento digitado aqui continua
> editável e excluível como sempre (`ContaCorrenteMovimentoCrudTest`,
> `movimentoGeradoPelaBaixaDeContaPagarNaoPodeSerEditadoNemExcluido` e
> `movimentoManualContinuaSemVinculoDeContaPagar`).

## Regras de negócio

### Campos

`localizador` (PK surrogate), `id_conta_corrente` (FK, obrigatório), `id_plano_contas` (FK,
obrigatório), `data_movimento` (timestamptz, obrigatório), `numero_documento` (texto,
obrigatório), `credito_debito` (`C`/`D`, reaproveita o ENUM `credito_debito` já criado em V013 —
mesmo usado por `caixa_detalhe`), `compensado` (boolean, default false — marca se já foi
confirmado no extrato do banco), `valor` (`numeric(12,2)`, > 0), `observacao` (texto, opcional).

### CRUD completo, sem fallback de inativar

Nada referencia `conta_corrente_movimento` — exclusão é sempre definitiva, sem checagem de
vínculo (diferente de Conta Corrente/Plano de Contas, que têm FKs apontando pra elas).

### Filtros da listagem (revisados 2x depois do primeiro corte)

Ordem final na tela, da esquerda pra direita: **Data Inicial → Data Final → Empresa → Plano de
Contas → Conta Corrente → Documento (busca) → Compensado**. `idEmpresa` filtra via JOIN em
`conta_corrente` (a tabela de lançamento não tem `id_empresa` direto — vem da conta). Datas são
`LocalDate` (não `OffsetDateTime` — comparação por `data_movimento::date`), mesmo padrão de
`docs/telas/estorno-recebimento-crediario.md`. Filtro de Plano de Contas usa `SeletorPlanoContas`
(busca por código ou nome, 2026-08-13 — antes era `<select>` nativo `limite=100`; ver
`docs/telas/plano-contas.md`), mesmo componente do campo do formulário desta tela.

## Contrato de API

```
GET    /api/v1/contas-corrente-movimento   listagem paginada
  ?idContaCorrente=&idEmpresa=&idPlanoContas=&busca=&dataInicial=&dataFinal=&compensado=
GET    /api/v1/contas-corrente-movimento/{localizador}
POST   /api/v1/contas-corrente-movimento
PUT    /api/v1/contas-corrente-movimento/{localizador}
DELETE /api/v1/contas-corrente-movimento/{localizador}
```

`compensado`: `TODOS`/`COMPENSADOS`/`PENDENTES`. Todos sob `/api/v1/**` (JWT de tenant, RLS
ativo — P8), sem restrição de papel.

## Critérios de aceitação (viram testes)

- Dado dados completos, quando cria, então grava com sucesso (descrição da conta e do plano de
  contas resolvidas por JOIN).
- Dado `creditoDebito` fora de `C`/`D`, então 400.
- Dado `valor` zero ou negativo, então 400.
- Dado uma conta corrente ou plano de contas inexistente, então 400.
- Dado um lançamento existente, quando atualiza valor/compensado, então grava a mudança.
- Dado um lançamento existente, quando exclui, então apaga de verdade.
- Dado filtro por conta corrente e por compensado, então filtra corretamente.
- Dado filtro por intervalo de data, então só traz lançamentos dentro do intervalo.
- Dado filtro por empresa e por plano de contas, então filtra corretamente.
- Dado busca por número de documento, então encontra o lançamento certo.
- Dado um lançamento de outro tenant, então não aparece na listagem nem pode ser buscado (RLS).

Cobertos por `ContaCorrenteMovimentoCrudTest` (12 testes).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `financeiro.contacorrentemovimento.lista`** e
  **`financeiro.contacorrentemovimento.form`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

`db/migration/V028__financeiro_conta_corrente.sql` — tabela `conta_corrente_movimento`
(`localizador integer GENERATED ALWAYS AS IDENTITY`, FKs compostas pra `conta_corrente` e
`cfg_plano_contas`). RLS própria. `criado_em` **e** `atualizado_em` (diferente de
`caixa_detalhe`, que só tem `criado_em` — ali o lançamento é imutável, aqui não).

**Coluna acrescentada em 2026-08-14 (Fluxo de Caixa):** `id_conta_pagar integer` nullable e
**sem FK de propósito** (`V028__financeiro_conta_corrente.sql:90`) — marca os movimentos gerados
automaticamente pela baixa de Contas a Pagar e é o vínculo que permite apagá-los quando a baixa é
desfeita. Movimento lançado manualmente nesta tela sempre tem `id_conta_pagar` nulo.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Conciliação bancária automática** — `compensado` é marcado manualmente pelo operador.
- **Vínculo com Fechamento de Caixa** — são conceitos separados: conta corrente é extrato
  bancário; caixa é o dinheiro/formas de pagamento físicas da loja.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Lançamento de um movimento em menos de 20 segundos.
