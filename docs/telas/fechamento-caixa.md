# Spec: Fechamento de Caixa                        Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-30 · Módulo(s): `financeiro` (caixa) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

`caixa_mestre.caixa_fechado`/`data_fechamento` existiam no schema desde V025, mas nenhuma tela ou
rotina os usava — a Abertura de Caixa (`docs/telas/abertura-caixa.md`) deixou o fechamento
explicitamente como non-goal. Sem fechamento, um caixa aberto continua "aberto" indefinidamente
(nunca vira `caixa_fechado = true`), e não existe conferência entre o dinheiro físico contado e o
total que o sistema registrou ao longo do dia.

## Solução proposta

Tela dedicada (`/fechamento-caixa`, no subgrupo **Caixa** dentro de *Frente de Loja* —
`web/src/lib/menu.ts:103-120`, junto da Abertura de Caixa) que busca o caixa de um
usuário/data e conduz um fechamento **"às cegas"**: o operador informa quanto tem de cada tipo de
carteira que teve movimento no dia, sem ver o valor calculado pelo sistema antes de digitar. Só
fecha de fato quando **todas** as carteiras batem exatamente; se alguma não bater, a tela mostra a
divergência (esperado × contado × diferença) por carteira, com um drill-down analítico
lançamento-a-lançamento, e o caixa continua aberto. Imprime/exporta um relatório em folha A4 — só
depois do fechamento ter sido confirmado com sucesso.

- **ADMIN** vê um select de usuário (todos os ativos do tenant) + campo de data; pode consultar e
  fechar o caixa de **qualquer** usuário.
- **OPERADOR** só vê o campo de data (sem select) — sempre o próprio caixa; tentar informar
  `idUsuario` de outra pessoa responde 403.
- **Contagem às cegas**: a tela mostra o NOME de cada tipo de carteira com movimento no caixa
  (mesmas linhas de `GET /api/v1/caixa/fechamento`), cada uma com um campo pra digitar o valor
  contado — sem exibir saldo inicial, crédito, débito ou esperado nesse momento. O objetivo é o
  operador contar sem viés, sem já saber o que "precisa dar".
- **Fechamento condicional**: `POST /api/v1/caixa/fechamento` recebe um valor contado por
  carteira. Se todas baterem (diferença zero em cada uma), o caixa fecha de verdade e a
  conferência é gravada. Se qualquer uma não bater, **nada é gravado** — a resposta traz a
  divergência de cada carteira, e a tela troca a contagem por essa divergência.
- **Divergência com drill-down**: cada linha da divergência é clicável — abre um modal com os
  lançamentos daquela carteira dentro do caixa (data/hora, operação, origem — venda ou
  recebimento —, C/D, valor), inclusive uma linha sintética de "abertura de caixa" pro saldo
  inicial. Um botão "Contar de Novo" volta pra contagem, mantendo os valores já digitados pra
  corrigir só o que precisar.
- **Impressão**: botão "Visualizar Impressão" só aparece depois do caixa fechar com sucesso (agora
  ou numa visita anterior) — abre um popup de pré-visualização com "Salvar PDF"/"Imprimir", folha
  A4, mostrando os totais transacionais **e** a conferência (esperado/contado/diferença, todos
  zero). Diferente do Comprovante de Pagamento de Crediário, que é 80mm térmico
  (`docs/telas/comprovante-recebimento-crediario.md`).

## Decisões (confirmadas com o dono do produto via `AskUserQuestion` antes de implementar)

1. **ADMIN escolhe o usuário via select + campo de data** (recomendado, escolhido) — não uma
   lista clicável de caixas abertos. OPERADOR não vê o select, só o campo de data.
2. **Com conferência de dinheiro contado** (escolhido, não a opção mais simples de só mostrar os
   totais calculados) — o operador digita o valor físico contado, a tela mostra a diferença
   contra o total do sistema antes de confirmar o fechamento.
3. **Revisão no mesmo dia, pedido direto e detalhado do dono do produto — fechamento "às cegas"
   de TODAS as carteiras, não só dinheiro**: (1) mostrar todos os tipos de carteira com
   movimento no dia; (2) pedir o valor contado de cada um; (3) só liberar a impressão se tudo
   bater; (4) se não bater, tela separada com carteira/esperado/contado; (5) clicar na carteira
   mostra analiticamente os lançamentos, pra conferência. Substituiu por completo a decisão 2
   (um único campo "dinheiro contado" comparado só contra a carteira DINHEIRO) — ver "Regras de
   negócio" abaixo.

## User stories

- Como ADMIN, quero fechar o caixa de qualquer usuário informando a data do movimento.
- Como OPERADOR, quero fechar só o meu próprio caixa, sem ver dados de outros usuários.
- Como operador (ADMIN ou OPERADOR), quero ver os totais de crédito/débito por forma de pagamento
  antes de fechar, pra saber se bate com o que foi vendido/recebido no dia.
- Como operador, quero contar às cegas quanto tenho de cada tipo de carteira (dinheiro, cartões,
  Pix…) e só depois ver se bateu com o que o sistema esperava, pra a contagem não ser enviesada
  pelo valor calculado.
- Como operador, quando algo não bate, quero ver a diferença de cada carteira e poder clicar nela
  pra conferir lançamento a lançamento o que compõe o valor esperado.
- Como operador, quero visualizar o relatório de fechamento antes de imprimir ou salvar em PDF,
  disponível só depois que o caixa realmente fechou.

## Regras de negócio

### Permissão — dono do caixa ou ADMIN

`CaixaService.buscarParaFechamento`/`fechar`: se o `idCaixa`/`idUsuario` alvo não é o do usuário
logado, exige `roles` conter `ADMIN` (mesmo mecanismo de `ConfiguracaoGeralService`/
`UsuarioService`) — senão 403.

### Totais por tipo de carteira

Uma linha por `id_carteira` distinto em `caixa_detalhe` daquele `id_caixa`, agrupando
`credito_debito` em `totalCredito`/`totalDebito` (`SUM(valor) FILTER`/agregação em Java — ver
`CaixaService.montarFechamento`). A carteira da abertura (`caixa_mestre.id_carteira`) sempre
aparece na lista, mesmo com `totalCredito = totalDebito = 0` (só o `saldoInicial`). `valorEsperado
= saldoInicial + totalCredito − totalDebito` — `saldoInicial` só é diferente de zero na linha da
carteira de abertura; as demais entram com `saldoInicial = 0`.

### Carteiras com o mesmo nome em categorias diferentes (revisão 2026-07-31)

A mesma bandeira pode ter um cadastro em débito e outro em crédito (`tipo_carteira_uk` permite o
mesmo `nome_carteira` com `categoria_carteira` diferente — ex.: "HIPER" débito e "HIPER" crédito,
duas linhas com `id_carteira` distintos, cadastradas na tela de Tipo de Carteira). Até 2026-07-31 as
linhas de totais/conferência só traziam `nomeCarteira`, então duas carteiras homônimas apareciam
sem nenhuma forma de diferenciar uma da outra na tela (mesmo já sendo somadas corretamente por
`id_carteira` internamente). Corrigido acrescentando `categoriaCarteira` em toda linha
(`LinhaTotalCarteiraResponse`/`LinhaConferenciaResponse`) — a tela/impressão mostram
`"NOME — Categoria"` (ex.: `"HIPER — Cartão Débito"`, `"HIPER — Cartão Crédito"`), mesmo padrão já
usado em `FormaPagamentoModal.tsx` do PDV (`rotuloCarteira()`, novo em `web/src/lib/caixa.ts`).

### Conferência às cegas — TODA carteira com movimento, não só "DINHEIRO"

Revisão de 2026-07-30: a versão original comparava só uma carteira chamada "DINHEIRO"; o dono do
produto pediu que a conferência valha pra qualquer tipo de carteira que teve movimento no caixa
(cartão, Pix, crediário quitado no dia etc.), uma linha de contagem por carteira, todas exibidas
sem o valor esperado (contagem "às cegas", sem viés). `POST /api/v1/caixa/fechamento` exige um
`valorContado` pra **cada** carteira que aparece em `GET /api/v1/caixa/fechamento` — faltar
qualquer uma responde 400 (`"Informe o valor contado da carteira <nome>."`).

### Fechamento só é gravado se TODAS as carteiras baterem

Pra cada carteira, `diferenca = valorContado − valorEsperado`. Se **alguma** diferença for
diferente de zero, nada é gravado — nem `caixa_mestre` nem a conferência — e a resposta
(`fechado: false`) traz `linhas` com `idCarteira/nomeCarteira/valorEsperado/valorContado/
diferenca` de cada carteira, pra tela mostrar a divergência sem fechar o caixa. O usuário pode
corrigir os valores e tentar de novo quantas vezes precisar. Só quando todas as diferenças são
exatamente zero o backend grava: `caixa_mestre.caixa_fechado = true`, `data_fechamento = now()`,
`id_usuario_fechamento` (pode ser diferente de `id_usuario` quando ADMIN fecha o caixa de outra
pessoa) **e** uma linha por carteira em `caixa_fechamento_conferencia` (tabela nova, ver "Impacto
no banco"). Tentar fechar um caixa já fechado responde 409 (`ConflitoDadosException`).
`GET /api/v1/caixa/fechamento` continua funcionando depois de fechado (mostra os mesmos totais +
a conferência gravada), pra permitir reabrir a tela e reimprimir o relatório mais tarde.

### Drill-down analítico por carteira

`GET /api/v1/caixa/fechamento/{idCaixa}/carteiras/{idCarteira}/lancamentos` devolve, em ordem
cronológica, cada lançamento de `caixa_detalhe` daquela carteira dentro daquele caixa
(data/hora, tipo de operação, origem, crédito/débito, valor). Quando `idCarteira` é a carteira de
abertura do caixa, a lista começa com uma linha sintética `ABERTURA_CAIXA` (crédito, valor =
`saldoInicial`) que não existe fisicamente em `caixa_detalhe` — é montada em memória pra fechar a
soma com o `valorEsperado` mostrado na tela. `origem` prioriza `id_lote_recebimento` sobre
`id_venda` quando os dois estão preenchidos na mesma linha (recebimento de parcela de crediário
grava os dois; "Recebimento nº X" é o evento relevante daquele dia, não a venda original que
gerou a parcela).

> ⚠️ **Saída de dinheiro por Contas a Pagar (2026-08-23) — o drill-down não sabe nomear.** Desde
> o Fluxo de Caixa, a baixa de uma conta a pagar em dinheiro grava um `caixa_detalhe` com
> `tipo_operacao = 'DEBITO_CAIXA'`, `credito_debito = 'D'` e `id_conta_pagar` preenchido
> (`ContaPagarService.java:198-206`). Ele **entra normalmente na conferência e no valor esperado**
> da carteira do caixa (é um débito, reduz o esperado), mas o drill-down mostra `origem = "—"`:
> a função `CaixaService.origem` (`api/src/main/java/com/vetor/niner/financeiro/caixa/CaixaService.java:271-275`)
> só conhece `id_lote_recebimento` e `id_venda`, e o SELECT de `:251-257` nem lê a coluna
> `id_conta_pagar`. Pendência: ler `cd.id_conta_pagar` e devolver "Conta a pagar nº N".

### Dependência: PDV precisou passar a lançar em `caixa_detalhe`

Antes desta feature, só o Recebimento de Crediário gravava em `caixa_detalhe` — o PDV nunca
lançava nada lá (decisão explícita, "fora de escopo por ora", ver histórico em
`docs/telas/pdv.md`). Isso foi corrigido no mesmo dia, como pré-requisito prático descoberto ao
testar esta tela (o usuário reportou "fiz várias vendas e só aparece Dinheiro"): agora cada linha
de pagamento à vista (`AVISTA`/`CARTAO_DEBITO`/`CARTAO_CREDITO`) de uma venda do PDV também lança
um crédito em `caixa_detalhe`; `CREDIARIO` continua de fora (ver `docs/telas/pdv.md`, seção "Venda
lança em `caixa_detalhe`").

## Contrato de API

```
GET  /api/v1/caixa/fechamento?idUsuario=&data=
     busca o caixa (aberto ou fechado) de um usuário/data → { idCaixa, idUsuario, nomeUsuario,
     nomeEmpresa, dataAbertura, dataFechamento, fechado,
     linhas: [{ idCarteira, nomeCarteira, categoriaCarteira, saldoInicial, totalCredito, totalDebito, valorEsperado }],
     conferencia: [{ idCarteira, nomeCarteira, categoriaCarteira, valorEsperado, valorContado, diferenca }] }
     -- `conferencia` só vem preenchida quando `fechado = true`. `categoriaCarteira` desde 2026-07-31.

POST /api/v1/caixa/fechamento
     { idCaixa, valoresContados: [{ idCarteira, valorContado }] }
     → { idCaixa, fechado, linhas: [{ idCarteira, nomeCarteira, categoriaCarteira, valorEsperado, valorContado, diferenca }] }
     -- `fechado = true` e a conferência foi gravada quando toda `diferenca` é zero;
     -- `fechado = false` (nada gravado) quando alguma carteira não bateu — `linhas` mostra
     -- onde está a divergência.

GET  /api/v1/caixa/fechamento/{idCaixa}/carteiras/{idCarteira}/lancamentos
     → [{ dataHora, tipoOperacao, creditoDebito, valor, origem }]
     -- drill-down analítico de uma carteira dentro do caixa (inclui a linha sintética de abertura).
```

`idUsuario` é opcional — omitido busca o próprio caixa do usuário logado; informado exige ADMIN se
for diferente do próprio. `data` é obrigatório (`yyyy-MM-dd`). Todos sob `/api/v1/**` (JWT de
tenant, RLS ativo — P8). Erros em Problem Details (RFC 9457): 400 (`valorContado` ausente/negativo
em alguma carteira, ou faltando carteira na lista), 403 (não-ADMIN informando outro usuário, ou
fechando/consultando o caixa de outro usuário), 404 (nenhum caixa para aquele usuário/data,
`idCaixa` inexistente, ou drill-down de uma carteira fora do caixa), 409 (caixa já fechado).

## Critérios de aceitação (viram testes)

- Dado um caixa aberto hoje para o usuário logado, quando busca o fechamento sem informar
  `idUsuario`, então devolve os totais do próprio caixa.
- Dado um OPERADOR, quando informa `idUsuario` de outra pessoa na busca ou no fechamento, então
  403.
- Dado um ADMIN, quando informa `idUsuario` de outra pessoa, então consulta/fecha o caixa dela
  normalmente.
- Dado nenhum caixa para o usuário/data informados, então 404.
- Dado um caixa com lançamentos em `caixa_detalhe` em mais de uma carteira, então os totais de
  crédito/débito de cada linha batem com a soma dos lançamentos daquela carteira, e
  `valorEsperado` reflete a fórmula (saldo inicial só na carteira de abertura).
- Dado um fechamento em que o valor contado bate exatamente com o esperado em todas as carteiras,
  quando confirmado, então `caixa_mestre` grava `caixa_fechado = true`, `data_fechamento`,
  `id_usuario_fechamento`, e uma linha por carteira é gravada em `caixa_fechamento_conferencia`.
- Dado um fechamento em que alguma carteira não bate, quando confirmado, então nada é gravado
  (`caixa_mestre` continua aberto), e a resposta traz `fechado: false` com a diferença de cada
  carteira.
- Dado um fechamento sem informar o valor contado de alguma carteira que teve movimento, então
  400.
- Dado o drill-down de uma carteira, então devolve a linha sintética de abertura (quando aplicável)
  mais os lançamentos reais, na ordem cronológica.
- Dado um caixa já fechado, quando tenta fechar de novo, então 409.
- Dado um `idCaixa` inexistente (ou de outro tenant), então 404.
- Dado o caixa de um tenant, então não aparece na busca de outro tenant (RLS).
- Dado duas carteiras com o mesmo nome em categorias diferentes (ex.: "HIPER" débito e "HIPER"
  crédito), quando ambas têm movimento no mesmo caixa, então aparecem como duas linhas separadas,
  cada uma com sua própria `categoriaCarteira` e seus próprios totais (2026-07-31).

Cobertos por `FechamentoCaixaCrudTest` (15 testes) + 2 testes em `PdvCrudTest` (venda com várias
formas de pagamento lança `caixa_detalhe` em todas menos crediário; débito fica em aberto em
`contas_receber` mesmo já tendo entrado no caixa). Suíte completa do projeto: **492/492 verdes
(2026-08-24)** — eram 266 quando esta tela nasceu, em 2026-07-31.

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `financeiro.fechamentocaixa.tela`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

`caixa_mestre` ganhou `valor_contado_dinheiro numeric(12,2)` e `id_usuario_fechamento integer` (FK
composta pra `usuario`) na primeira versão desta feature (dentro de
`V025__financeiro_caixa_crediario.sql`, banco ainda em construção). A revisão "às cegas" trocou o
modelo de conferência de um único valor pra uma linha por carteira, então criou a tabela nova
`caixa_fechamento_conferencia` (`id_conferencia identity PK`, `id_tenant`, `id_caixa`,
`id_carteira`, `valor_esperado`, `valor_contado`, FKs compostas pra `caixa_mestre` e
`tipo_carteira`, RLS por tenant) — editada no mesmo arquivo de migration (banco ainda em
construção). `id_usuario_fechamento` continua sendo usado; **`valor_contado_dinheiro` ficou sem
uso a partir de agora — a coluna foi mantida (não apagada) pra não perder dado de caixas já
fechados com a versão anterior**, seguindo a regra de nunca excluir dado de tabela sem perguntar
antes. `caixa_fechado`/`data_fechamento` já existiam desde V025.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Sangria/suprimento durante o dia** — fora de escopo, só abertura + fechamento.
- **Reabrir um caixa fechado** — não existe rota pra isso; um fechamento é definitivo (só a
  consulta/reimpressão continuam disponíveis depois).
- **Conciliação de cartões** (baixar as parcelas de débito/crédito em `contas_receber` quando a
  operadora efetivamente pagar) — mencionada como próximo passo pelo dono do produto, mas fora
  desta feature.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Fechamento de caixa em menos de 30 segundos quando os valores contados batem; quando não batem, a
divergência por carteira e o drill-down analítico ficam visíveis antes de tentar de novo.
