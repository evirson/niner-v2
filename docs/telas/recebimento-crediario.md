# Spec: Recebimento de Crediário       Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-29 · Módulo(s): `financeiro` (recebimentocrediario) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

O cliente com parcelas de crediário em aberto (`contas_receber`, categoria `CREDIARIO` de
`tipo_carteira`) precisa poder quitar uma ou várias delas numa única operação de caixa, com
multa/juros calculados automaticamente pelo atraso, gerando o movimento de caixa e a baixa
financeira corretos. Até 2026-07-29 não existia nenhuma rotina de recebimento — as parcelas
nasciam no PDV (`vendas.PdvVendaService`) e ficavam em aberto pra sempre, sem forma de baixar.
`caixa_mestre`/`caixa_detalhe` (V025, 2026-07-16) e os campos de configuração de juros/multa em
`cfg_geral` (V023) já existiam prontos, esperando por esta feature ("Fase 2" nos comentários do
schema desde a criação).

## Solução proposta

Nova tela de domínio no módulo `financeiro`, item próprio no menu lateral (mesmo nível de
PDV/Estoque). Fluxo: busca o cliente → lista as parcelas de crediário em aberto dele com
multa/juros já calculados → seleção múltipla com totais no rodapé → uma ou mais formas de
pagamento cobrindo o valor exato do que foi selecionado → confirma. Tudo dentro de uma única
transação (RN013): parcela(s) baixada(s), lançamento(s) de caixa gravado(s), caixa aberto
automaticamente se não houver um pra esse usuário/empresa/dia.

Spec original (RN001–RN013) fornecida pelo dono do produto por escrito; as decisões abaixo
fecham os pontos que a spec original deixava implícitos, confirmados em conversa antes de
implementar.

## User stories

- Como operador de caixa, quero buscar um cliente por nome/CPF/celular e ver todas as parcelas
  de crediário dele em aberto, com o valor atualizado (incluindo multa/juros de atraso).
- Como operador, quero selecionar uma ou várias parcelas e ver o total a receber somar
  automaticamente.
- Como operador, quero poder combinar mais de uma forma de pagamento pra fechar o valor de uma
  vez (ex.: parte em dinheiro, parte no débito).
- Como operador, não quero que a exclusividade de desconto/acréscimo da forma de pagamento
  (usada na venda) se aplique aqui — o crediário é quitado pelo valor exato.

## Modelo de dados

Sem tabela nova de "parcela" — reaproveita `contas_receber`/`contas_receber_detalhe`/
`caixa_mestre`/`caixa_detalhe`, todas já existentes desde V025 (2026-07-16), nunca usadas antes
desta feature. Duas mudanças de schema, ambas dentro de `V025__financeiro_caixa_crediario.sql`
(banco em construção, edita a migration existente em vez de criar uma nova):

1. **`tipo_carteira.permite_receber_crediario`** (`boolean NOT NULL DEFAULT false`, RN007) — só
   carteiras marcadas aqui aparecem como opção de pagamento nesta tela. Controlado no cadastro
   de Tipo de Carteira (checkbox "Permite receber parcelas de crediário"). Tenants novos nascem
   com DINHEIRO/PIX/CARTAO DEBITO/CARTAO CREDITO já marcados `true` (categorias explicitamente
   permitidas, ver RN007 abaixo) — `CREDIARIO`/vales seguem `false` (crediário não paga
   crediário). Tenants existentes antes desta mudança nasceram com tudo `false` — precisa
   habilitar manualmente em Tipo de Carteira.
2. **`contas_receber_lote`** (tabela nova) — cabeçalho de uma operação de "Receber": `id_lote_
   recebimento` (gerador real, `GENERATED ALWAYS AS IDENTITY`), `id_cliente`, `id_empresa`,
   `id_usuario`, `data_recebimento`, `valor_total`. Antes desta feature, `contas_receber.
   id_lote_recebimento`/`caixa_detalhe.id_lote_recebimento` eram só "número de gerador externo"
   sem tabela — mesmo padrão que `produto_movimento_mestre.id_transferencia` era antes de
   `produto_transferencia` existir (V019). **Sem FK** de `contas_receber`/`caixa_detalhe` pra
   `contas_receber_lote` — proposital, mesma decisão de não acoplar o ledger genérico a uma
   tabela de negócio específica.

## Campos da tela

### Filtros (busca de cliente)

| Campo | Tipo | Obrigatório |
|---|---|---|
| Nome | texto | Não |
| CPF | texto (só dígitos) | Não |
| Celular | texto (só dígitos) | Não |

RN001: pelo menos um dos três é obrigatório (400 se todos vazios). Clique numa linha do
resultado seleciona o cliente e abre a grid de parcelas.

### Grid de parcelas em aberto

Layout revisado em 2026-07-29 (mockup do dono do produto) — grid à esquerda + barra lateral
fixa à direita, dentro de um container de altura travada; **só a grid rola por dentro**
(`.table-wrap`), o resto da tela (busca de cliente, barra lateral, forma de pagamento) não tem
scroll próprio, pedido explícito "scroll apenas na grid de parcelas a receber".

| Coluna | Origem |
|---|---|
| Nº Venda | `contas_receber.id_venda` |
| Empresa | `empresa.codigo_empresa` (2 dígitos, via `venda.id_empresa`) |
| Data Venda | `venda.data_venda` |
| Nº PC | `numeroParcela/totalParcelas`, ex. "02/06" — `totalParcelas` é o total do **mesmo plano de pagamento** (mesma venda + mesma carteira), não da venda inteira, já que uma venda pode ter mais de um plano de pagamento independente |
| Vencimento | `contas_receber.data_vencimento` |
| Vlr. Original | `contas_receber.valor_receber` |
| Multa + Juros | soma das duas calculadas (ver fórmula abaixo) — colunas fundidas numa só |
| Total a Pagar | Valor Original + Multa + Juros |

RN002: só parcelas com `data_recebimento IS NULL`. Escopo desta tela (pelo nome e pelo non-goal
"não recebe cartão aqui" abaixo): só `tipo_carteira.categoria_carteira = 'CREDIARIO'` — parcela
de cartão de crédito (que também nasce em aberto no PDV) não aparece aqui, não tem tela própria
ainda. Seleção múltipla (RN005), grid navegável por teclado (▲/▼ move o foco entre linhas,
Enter/Espaço alterna a seleção da linha focada) e selecionável (clique na linha ou no checkbox).
Trocar a seleção zera qualquer forma de pagamento já lançada (o total mudou).

**Barra lateral** (era rodapé de 5 caixas, virou coluna fixa ao lado da grid em 2026-07-29) — 3
caixas de totais (RN003/RN004, recalculadas a cada mudança de seleção): Selecionadas, Valor
Selecionado, Valor Recebido (fica verde quando o saldo fecha); abaixo, o botão **"Receber"**
(desabilitado sem parcela selecionada ou com saldo já fechado).

### Forma de pagamento

Só aparece o botão "Receber" (na barra lateral) depois de pelo menos uma parcela selecionada.
Ao clicar, abre um popup (`EscolherFormaPagamentoModal.tsx`, revisado em 2026-07-29) com as
categorias permitidas: À Vista, Cartão Débito, Cartão Crédito (RN007) — nunca Crediário. Dentro
de cada categoria, só carteiras com `permite_receber_crediario = true`. O popup já resolve
categoria **e** Tipo de Carteira/Valor Pago numa tela só (revisão sobre a primeira versão, que
mandava pra um formulário à parte na página principal) — "Voltar" retorna à lista de categorias
sem fechar o popup; "Adicionar Pagamento" lança e fecha. Mesmo mecanismo de split-tender do PDV,
mas **sem** a lógica de desconto/acréscimo da carteira (RN008) — o valor pago cobre exatamente o
que foi digitado, sem bônus/penalidade. Lançamentos já feitos aparecem na página principal como
**cards numa grade de 4 colunas** (não tabela), cada um removível. Botão "Efetivar Recebimento"
(no topo da tela, era "Receber" — renomeado em 2026-07-29 pra não confundir com o botão da barra
lateral que só abre o popup) só habilita quando a soma dos pagamentos fecha exatamente o Valor
Total do Recebimento (tolerância de 1 centavo, absorvida na última linha).

## Fórmula de multa e juros (RN008, confirmada com o dono do produto em 2026-07-29)

Usa `cfg_geral.multa_crediario`/`multa_crediario_dias`/`juros_crediario`/`juros_crediario_dias`
(Parâmetros do Sistema, campos "Multa após (dias)"/"Multa (%)"/"Juros após (dias)"/"Juros (%)"
— já existiam na tela antes desta feature, confirmando a leitura de carência abaixo). `diasAtraso
= GREATEST(0, hoje − data_vencimento)`.

- **Multa** — percentual único sobre o valor original, só se `diasAtraso > multa_crediario_dias`
  (carência); não cresce com o tempo. `multa = valorOriginal × multa_crediario%` ou `0`.
- **Juros** — percentual **ao dia** sobre o valor original, por cada dia de atraso além da
  carência (`juros_crediario_dias`); cresce a cada dia. `juros = valorOriginal × juros_crediario%
  × max(0, diasAtraso − juros_crediario_dias)`.

Exemplo verificado ao vivo (parcela de R$50, atraso de 45 dias, `multa_crediario=2%`/carência 3
dias, `juros_crediario=1,5%`/carência 10 dias): `multa = 50 × 2% = R$1,00`; `juros = 50 × 1,5% ×
(45−10) = R$26,25`; total = R$77,25. Sempre recalculado no servidor no momento do recebimento
(RN009) — a listagem pode estar minutos desatualizada, nunca confia no valor mostrado no front.

## Alocação de pagamentos entre parcelas (RN012)

Como uma operação pode cobrir várias parcelas (de vendas diferentes) com várias formas de
pagamento, `RecebimentoCrediarioService.alocarPagamentos()` faz uma alocação FIFO: parcelas
ordenadas por vencimento (mais antiga primeiro), linhas de pagamento processadas na ordem
informada, cada linha consome o valor da parcela corrente até esgotar antes de passar pra
próxima — uma única parcela pode acabar coberta por mais de uma forma de pagamento se cair na
fronteira. Cada `(parcela, linha)` da alocação vira exatamente um lançamento em `caixa_detalhe`
(garante `id_venda` correto por lançamento). Pra `contas_receber_detalhe` (RN010, "quando
aplicável"): a carteira que mais contribuiu pro total daquela parcela decide se grava detalhe de
cartão (`categoria = CARTAO_DEBITO`/`CARTAO_CREDITO`) — `valor_bruto` = total da parcela,
`taxa_administradora` da carteira, `valor_liquido` = bruto menos a taxa. Sem captura de número
de autorização nesta tela (fica `NULL`).

## Caixa (RN011/RN012 + nota técnica do dono do produto)

Todo recebimento grava em `caixa_detalhe` (`tipo_operacao = 'RECEBIMENTO_PARCELA_CREDIARIO'`,
`credito_debito = 'C'`). **Revisado em 2026-07-30** (`docs/telas/abertura-caixa.md`): a API não
abre mais o caixa sozinha. Se não existir `caixa_mestre` aberto (`caixa_fechado = false`) pra
esse usuário, nessa empresa, hoje, `efetivarRecebimento` responde 400 e nada é gravado
(`CaixaService.idCaixaAbertoObrigatorio`) — a tela é responsável por pedir a abertura (popup
obrigatório) antes de deixar o operador chegar até aqui. Comportamento antigo (até 2026-07-30):
abria um caixa automaticamente com `saldo_inicial = 0`, sem pedir nada ao operador.

## Critérios de aceitação (viram testes)

- Dado um cliente com filtro de nome/CPF/celular, quando buscado, então aparece na lista (400
  se nenhum filtro foi informado).
- Dado um cliente com parcelas de crediário em aberto e outras já recebidas/de outra categoria,
  quando a grid carrega, então só as em aberto de categoria CREDIARIO aparecem.
- Dado `multa_crediario`/`juros_crediario`/carências configurados e uma parcela vencida há N
  dias, então multa/juros/total batem com a fórmula acima.
- Dado uma parcela ainda dentro da carência, então multa e juros são zero.
- Dado tipos de carteira com/sem `permite_receber_crediario` e em categorias diferentes, quando
  lista as disponíveis, então só aparecem as marcadas nas categorias À Vista/Débito/Crédito.
- Dado um pagamento numa carteira sem `permite_receber_crediario`, quando confirma, então 400 e
  nada é gravado.
- Dado pagamentos cuja soma não fecha o total das parcelas selecionadas, quando confirma, então
  400 e nada é gravado.
- Dado parcelas de vendas diferentes pagas com formas diferentes (split-tender), quando
  confirma, então cada lançamento de caixa fica com o `id_venda`/`id_carteira` certos.
- Dado um pagamento numa carteira de cartão, então grava `contas_receber_detalhe`.
- Dado uma parcela já recebida (corrida entre duas telas), quando tenta receber de novo, então
  409 e nada é gravado.
- Dado uma parcela de outro tenant, então não aparece na listagem nem pode ser recebida (RLS).

Cobertos por `RecebimentoCrediarioCrudTest` (24 testes). Suíte completa do projeto: 500/500
verdes (2026-08-14).

## Impacto no contrato de API

```
GET  /api/v1/recebimento-crediario/clientes?nome=&cpf=&celular=   busca (1 filtro obrigatório)
GET  /api/v1/recebimento-crediario/parcelas?idCliente=              parcelas em aberto + multa/juros
GET  /api/v1/recebimento-crediario/carteiras                        formas de pagamento permitidas
POST /api/v1/recebimento-crediario                                  efetiva o recebimento (transação única)
GET  /api/v1/recebimento-crediario/{idLoteRecebimento}/comprovante  comprovante p/ impressão 80mm (2026-07-30)
```

**Comprovante de pagamento (2026-07-30):** popup automático após efetivar, pronto pra
impressão térmica 80mm ou PDF — spec própria em `docs/telas/comprovante-recebimento-
crediario.md`.

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), abertos a ADMIN e OPERADOR (mesma
decisão de PDV/Transferência — operação de caixa do dia a dia). Erros em Problem Details (RFC
9457): 400 (sem filtro de busca, carteira não permitida, soma não fecha, parcela de outro
cliente), 409 (parcela já recebida).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `financeiro.recebimentocrediario.tela`** — ver
  `web/src/components/AjudaDaTela.tsx`. `url_video`: `NULL` por ora.

## Impacto no banco

`tipo_carteira.permite_receber_crediario` (coluna nova) + `contas_receber_lote` (tabela nova),
ambas dentro de `V025__financeiro_caixa_crediario.sql` + RLS próprio no mesmo arquivo. Sem
mudança em `contas_receber`/`caixa_mestre`/`caixa_detalhe`/`contas_receber_detalhe` — só uso do
que já existia desde 2026-07-16. **O redesenho de layout de 2026-07-29 não mexeu em schema
nenhum** — só frontend (`RecebimentoCrediario.tsx`/`EscolherFormaPagamentoModal.tsx`/CSS).

**Nota operacional (reversão manual de um recebimento de teste no banco de dev):** um
recebimento efetivado grava em **4** tabelas, não 3 — `contas_receber_lote` (cabeçalho),
`caixa_mestre` (se abriu um caixa novo), `caixa_detalhe` (um lançamento por alocação) e
`contas_receber` (a própria parcela, `data_recebimento`/`valor_recebido`/
`id_empresa_pagamento`/`id_lote_recebimento`) — **e, quando a carteira dominante de alguma
parcela for de cartão (RN010), também `contas_receber_detalhe`**, cuja chave primária é
`(id_tenant, id_conta_receber)`. Esquecer essa última tabela numa reversão manual deixa uma
linha órfã que quebra qualquer recebimento futuro da mesma parcela com cartão, com um 409
genérico ("Registro em uso por outro cadastro") que não deixa a causa óbvia — achado real em
2026-07-29, ver `docs/PROGRESSO.md` linha do tempo do dia.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Recebimento de parcela de cartão de crédito** — parcela de categoria `CARTAO_CREDITO`
  também nasce em aberto no PDV, mas esta tela só mostra `CREDIARIO`. Precisaria de tela própria
  (ou extensão desta) se vier a ser pedido.
- **Parcelamento do recebimento** — pagar com cartão de crédito aqui não gera novas parcelas;
  é sempre um valor à vista naquela forma de pagamento.
- ~~**Edição/estorno de um recebimento já efetivado** — sem tela pra desfazer; `contas_receber`
  fica com `data_recebimento` preenchida permanentemente (diferente da Transferência, que ganhou
  exclusão em 2026-07-29 — aqui não foi pedido).~~ — **superado**: existe a tela irmã **Estorno de
  Recebimento de Crediário** (`web/src/pages/recebimentocrediario/EstornoRecebimentoCrediario.tsx`,
  `RecebimentoCrediarioController.java:77`, spec `docs/telas/estorno-recebimento-crediario.md`),
  que reverte o **lote inteiro** do recebimento — não a edição parcial, que segue fora de escopo.
- **Captura de número de autorização de cartão** — `contas_receber_detalhe.numero_autorizacao`
  fica `NULL`; a tela não tem campo pra isso.
- **Sangria/suprimento** — seguem sem fluxo. ~~**Fechamento de caixa**~~ — abertura e
  **fechamento** ganharam tela própria em 2026-07-30 (`docs/telas/abertura-caixa.md`,
  `docs/telas/fechamento-caixa.md`; `web/src/pages/caixa/FechamentoCaixa.tsx`,
  `CaixaController.java:56`).

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Tempo de recebimento de 1–3 parcelas de crediário em menos de 30 segundos.

---

## ⚠️ 2026-08-29 (auditoria, 2ª leva) — o 403 chegava com o dinheiro já contado

"Efetivar Recebimento" é POST em `recebimento-crediario` (⇒ INCLUIR) e a tela não consultava
`usePermissaoDaTela`. O cenário é o pior da família: o operador com só *"Acessar"* localiza o
crediário **com o cliente no balcão**, monta o split-tender (dinheiro + cartão), fecha o saldo — e o
**403 chega no botão final**, com o dinheiro contado na mão.

⭐ É o mesmo defeito que as rodadas anteriores fecharam em ~15 telas de cadastro. A varredura tinha
coberto os **cadastros** e não as **rotinas operacionais**, que são justamente onde o 403 tardio
custa mais caro.
