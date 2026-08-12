# Spec: Contas a Pagar / Pagas             Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-19 · Módulo(s): `financeiro` (contaspagar) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

A tabela `contas_pagar` existe desde V026 (Q5/ADR-012, 2026-07-16), mas até esta feature só
era escrita internamente pela Entrada de Produtos por Compra (parcelas geradas na confirmação
de uma entrada, via `com.vetor.niner.estoque.entrada.ContasPagarService`, um helper de INSERT
sem tela própria). Não existia CRUD nem tela para: consultar todas as duplicatas de
fornecedor a pagar (independente de terem vindo de uma entrada ou não), corrigir um lançamento,
excluir um lançamento incorreto, ou registrar que uma conta foi paga.

## Solução proposta

Tela de cadastro completo (lista + form) no módulo Financeiro, no mesmo padrão de
`docs/telas/conta-corrente-movimento.md` (PK surrogate, editável, sem `ativo` — exclusão é
sempre definitiva), combinada com o **popup de filtros obrigatório ao entrar na tela** (mesmo
padrão do CRM/Cancelamento de Devolução/Entrada de Produtos), pedido explícito do dono do
produto.

Dois `ContaPagarService` coexistem, de propósito, sem se misturar: o novo
`com.vetor.niner.financeiro.contaspagar.ContaPagarService` (singular) é o CRUD completo desta
tela; `com.vetor.niner.estoque.entrada.ContasPagarService` (plural, pré-existente) continua
sendo o helper interno de INSERT usado só pela Entrada de Produtos — não foi tocado.

**Não existe uma ação/tela de "Pagar" separada.** O pedido do dono do produto listou só três
ações na grid (Visualizar/Editar/Excluir); dar baixa é editar o registro preenchendo Data de
Pagamento, Valor Pago e marcando "Documento Pago" — o próprio formulário tem uma nota explicativa
visível sobre isso.

## Regras de negócio

### Campos

`idContaPagar` (PK surrogate), `idFornecedor` (FK, obrigatório), `idEmpresa` (FK, obrigatório),
`idPlanoContas` (FK, obrigatório — classificação gerencial da despesa), `notaFiscal` (integer,
opcional), `numeroDuplicata` (texto, opcional, até 40 caracteres), `dataLancamento`
(`OffsetDateTime`, obrigatório), `dataVencimento` (`OffsetDateTime`, obrigatório),
`dataPagamento` (`OffsetDateTime`, opcional — preenchida na baixa), `valorPagar`
(`numeric(12,2)`, obrigatório, > 0), `valorPago` (`numeric(12,2)`, default `0`),
`documentoPago` (boolean, default `false`), `observacoes` (texto, opcional, até 1000
caracteres), `idMovimento` (FK opcional para `produto_movimento_mestre` — preenchido só quando
a conta nasceu de uma Entrada de Produtos; usado pelo Cancelamento de Entrada para localizar e
apagar as duplicatas da entrada cancelada).

### CRUD completo, sem fallback de inativar

`contas_pagar` não tem coluna `ativo` e nada referencia esta tabela por FK — exclusão é sempre
um `DELETE` definitivo, mesmo em linhas com `idMovimento` preenchido (geradas por uma Entrada):
se a entrada de origem for cancelada depois, o `DELETE ... WHERE id_movimento = ?` do
Cancelamento de Entrada simplesmente não encontra mais nada para apagar.

### Popup de filtros obrigatório

Ao abrir a tela, `filtrosAberto` inicia `true` e a grid só aparece depois de "Localizar" (todos
os filtros são opcionais — em branco lista tudo). Filtros: Fornecedor (typeahead, reaproveita
`buscarFornecedoresEmissao`), Empresa (select), Nota Fiscal, Duplicata, Início/Final Vencimento,
Início/Final Pagamento. Segundo botão no popup, "＋ Nova Conta a Pagar", pula a busca e vai
direto para o cadastro.

### Filtros de data usam fuso de Brasília, não UTC

A sessão do Postgres roda em UTC, mas a tela mostra datas em horário local do navegador —
`dataVencimentoInicial/Final` e `dataPagamentoInicial/Final` comparam com
`(coluna AT TIME ZONE 'America/Sao_Paulo')::date`, não a coluna crua (mesmo padrão adotado em
Entrada de Produtos e Filtros de Entrada, 2026-08-19). Gravação de data usa meio-dia UTC
(`T12:00:00Z`), não meia-noite, para não sofrer o mesmo efeito de fuso ao exibir de volta.

## Tela

- **Grid**: Fornecedor, Empresa, Nota Fiscal, Duplicata, Vencimento, Pagamento, Valor a Pagar,
  Valor Pago — nessa ordem. Colunas ordenáveis (allowlist no backend): `dataVencimento`
  (padrão, `DESC`), `dataPagamento`, `fornecedor`, `empresa`, `notaFiscal`, `valorPagar`,
  `valorPago`. `numeroDuplicata` **não é ordenável** (não existe caso de uso claro para ordenar
  por ela e o backend não expõe essa coluna em `COLUNAS_ORDENAVEIS`).
- **Ações de linha**: visualizar (verde, somente leitura via `<fieldset disabled>` no mesmo
  componente de formulário), editar (azul), excluir (vermelho, com popup de confirmação
  mostrando fornecedor/vencimento/valor antes de confirmar).
- **Formulário**: Fornecedor (typeahead + "Trocar", mesmo padrão da Entrada de Produtos),
  Empresa (select), Plano de Contas (select, `tamanho: 500` — evita o bug de dropdown truncado
  quando o tenant tem centenas de contas), Nº Nota Fiscal, Duplicata, Data de
  Lançamento/Vencimento (obrigatórias, mascaradas `dd/mm/aaaa`), Valor a Pagar (obrigatório,
  máscara de moeda), seção "Pagamento" (Data de Pagamento, Valor Pago, "Documento Pago" — com o
  aviso de que não existe tela de baixa separada), Observações, `InfoRegistro` (auditoria).

## Contrato de API

```
GET    /api/v1/contas-pagar     listagem paginada
  ?idFornecedor=&idEmpresa=&notaFiscal=&numeroDuplicata=
  &dataVencimentoInicial=&dataVencimentoFinal=&dataPagamentoInicial=&dataPagamentoFinal=
  &pagina=&limite=&ordenarPor=&direcao=
GET    /api/v1/contas-pagar/{id}
POST   /api/v1/contas-pagar
PUT    /api/v1/contas-pagar/{id}
DELETE /api/v1/contas-pagar/{id}
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), sem restrição de papel (mesma decisão
de produto do resto do módulo Financeiro — Conta Corrente/Movimentação).

## Critérios de aceitação (viram testes)

- Dado dados completos, quando cria, então grava com sucesso (nome do fornecedor, da empresa e
  descrição do plano de contas resolvidos por JOIN).
- Dado `valorPagar` zero ou negativo, então 400.
- Dado um fornecedor, empresa ou plano de contas inexistente, então 400.
- Dado uma conta existente, quando atualiza preenchendo `dataPagamento`/`valorPago`/
  `documentoPago`, então grava a baixa (não existe endpoint separado para isso).
- Dado uma conta existente, quando exclui, então apaga de verdade, mesmo se tiver
  `idMovimento` preenchido.
- Dado filtro por fornecedor e por empresa, então filtra corretamente.
- Dado filtro por nota fiscal e por duplicata (busca parcial), então filtra corretamente.
- Dado filtro por intervalo de vencimento, então só traz contas com vencimento dentro do
  intervalo (bucket por dia local, não UTC).
- Dado filtro por intervalo de pagamento, então só traz contas pagas dentro do intervalo.
- Dado uma conta de outro tenant, então não aparece na listagem nem pode ser buscada (RLS).

Cobertos por `ContaPagarCrudTest` (11 testes).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `financeiro.contaspagar.lista`** e **`financeiro.contaspagar.form`** — ver
  `web/src/components/AjudaDaTela.tsx`. `url_video`: `NULL` por ora.

## Impacto no banco

`contas_pagar` já existia (V026, 2026-07-16) sem `criado_em`/`atualizado_em` — as duas colunas
foram adicionadas nesta feature (edição in-place de `V026__financeiro_contas_pagar.sql`, banco
ainda em construção) para satisfazer a seção de auditoria (`InfoRegistro.tsx`) obrigatória em
toda tela de cadastro. Sem `ativo` — segue o mesmo precedente de `conta_corrente_movimento`.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Tela/endpoint de "Pagar" (baixa em lote ou dedicada)** — fora de escopo por pedido
  explícito; a baixa é feita editando o registro. Se pedirem uma tela de baixa em lote no
  futuro, é uma extensão nova sobre esta base, não parte desta spec.
- **Rateio/parcelamento automático** — já resolvido na origem (Entrada de Produtos gera 1
  linha por parcela); esta tela só cria/edita uma conta por vez.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Localizar e dar baixa numa conta a pagar em menos de 30 segundos.
