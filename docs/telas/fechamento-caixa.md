# Spec: Fechamento de Caixa                        Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-30 · Módulo(s): `financeiro` (caixa) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

`caixa_mestre.caixa_fechado`/`data_fechamento` existiam no schema desde V025, mas nenhuma tela ou
rotina os usava — a Abertura de Caixa (`docs/telas/abertura-caixa.md`) deixou o fechamento
explicitamente como non-goal. Sem fechamento, um caixa aberto continua "aberto" indefinidamente
(nunca vira `caixa_fechado = true`), e não existe conferência entre o dinheiro físico contado e o
total que o sistema registrou ao longo do dia.

## Solução proposta

Tela dedicada (`/fechamento-caixa`, menu ao lado de Abertura de Caixa) que busca o caixa de um
usuário/data, mostra os totais por tipo de carteira (crédito/débito recalculados de
`caixa_detalhe`), pede a conferência de dinheiro contado e fecha o caixa. Imprime/exporta um
relatório em folha A4.

- **ADMIN** vê um select de usuário (todos os ativos do tenant) + campo de data; pode consultar e
  fechar o caixa de **qualquer** usuário.
- **OPERADOR** só vê o campo de data (sem select) — sempre o próprio caixa; tentar informar
  `idUsuario` de outra pessoa responde 403.
- Totais **recalculados na hora** a partir de `caixa_detalhe` (nunca um campo gravado) — uma
  linha por tipo de carteira usado no caixa, mais a carteira da abertura mesmo sem nenhum
  lançamento (mostra o `saldoInicial` sozinho).
- **Conferência de dinheiro contado**: o operador digita quanto contou fisicamente em dinheiro; a
  tela mostra a diferença contra o `valorEsperado` da carteira "DINHEIRO" (`saldoInicial +
  totalCredito − totalDebito`). Comparação é sempre contra a carteira chamada exatamente
  "DINHEIRO" (case-insensitive/trim) — não existe hoje um flag "é dinheiro físico" em
  `tipo_carteira` para generalizar isso a outras carteiras.
- **Impressão**: botão "Visualizar Impressão" abre um popup de pré-visualização (disponível tanto
  antes quanto depois de fechar) com "Salvar PDF"/"Imprimir", folha A4 — diferente do Comprovante
  de Pagamento de Crediário, que é 80mm térmico (`docs/telas/comprovante-recebimento-
  crediario.md`).

## Decisões (confirmadas com o dono do produto via `AskUserQuestion` antes de implementar)

1. **ADMIN escolhe o usuário via select + campo de data** (recomendado, escolhido) — não uma
   lista clicável de caixas abertos. OPERADOR não vê o select, só o campo de data.
2. **Com conferência de dinheiro contado** (escolhido, não a opção mais simples de só mostrar os
   totais calculados) — o operador digita o valor físico contado, a tela mostra a diferença
   contra o total do sistema antes de confirmar o fechamento.

## User stories

- Como ADMIN, quero fechar o caixa de qualquer usuário informando a data do movimento.
- Como OPERADOR, quero fechar só o meu próprio caixa, sem ver dados de outros usuários.
- Como operador (ADMIN ou OPERADOR), quero ver os totais de crédito/débito por forma de pagamento
  antes de fechar, pra saber se bate com o que foi vendido/recebido no dia.
- Como operador, quero informar quanto contei fisicamente em dinheiro e ver a diferença contra o
  esperado, antes de confirmar o fechamento.
- Como operador, quero visualizar o relatório de fechamento antes de imprimir ou salvar em PDF.

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

### Conferência de dinheiro — sempre contra a carteira "DINHEIRO"

Não existe hoje uma forma de marcar qual(is) tipo(s) de carteira representam dinheiro físico —
por convenção, o dono do produto usa sempre uma carteira chamada "DINHEIRO" (seed do signup). A
tela busca a linha cujo `nomeCarteira` bate com "DINHEIRO" (trim/uppercase) e compara o valor
contado contra o `valorEsperado` dela. Se essa carteira não tiver movimento nenhum (nem abertura),
a diferença não é calculável (`—` na tela).

### Fechamento é definitivo, mas revisitável

`POST /api/v1/caixa/fechamento` grava `caixa_fechado = true`, `data_fechamento = now()`,
`valor_contado_dinheiro` e `id_usuario_fechamento` (pode ser diferente de `id_usuario` quando
ADMIN fecha o caixa de outra pessoa). Tentar fechar de novo responde 409
(`ConflitoDadosException`). `GET /api/v1/caixa/fechamento` continua funcionando depois de fechado
(mostra os mesmos totais + os campos de fechamento gravados), pra permitir reabrir a tela e
reimprimir o relatório mais tarde — só não permite editar o valor contado (campo fica desabilitado
na tela quando `fechado = true`).

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
GET  /api/v1/caixa/fechamento?idUsuario=&data=   busca o caixa (aberto ou fechado) de um usuário/data
POST /api/v1/caixa/fechamento                    { idCaixa, valorContadoDinheiro } → fecha
```

`idUsuario` é opcional — omitido busca o próprio caixa do usuário logado; informado exige ADMIN se
for diferente do próprio. `data` é obrigatório (`yyyy-MM-dd`). Ambos sob `/api/v1/**` (JWT de
tenant, RLS ativo — P8). Erros em Problem Details (RFC 9457): 400 (`valorContadoDinheiro`
ausente/negativo), 403 (não-ADMIN informando outro usuário, ou fechando o caixa de outro usuário),
404 (nenhum caixa para aquele usuário/data, ou `idCaixa` inexistente), 409 (caixa já fechado).

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
- Dado um fechamento válido, quando confirmado, então `caixa_mestre` grava `caixa_fechado = true`,
  `data_fechamento`, `valor_contado_dinheiro` e `id_usuario_fechamento`.
- Dado um caixa já fechado, quando tenta fechar de novo, então 409.
- Dado um `idCaixa` inexistente (ou de outro tenant), então 404.
- Dado o caixa de um tenant, então não aparece na busca de outro tenant (RLS).

Cobertos por `FechamentoCaixaCrudTest` (11 testes) + 2 testes novos em `PdvCrudTest` (venda com
várias formas de pagamento lança `caixa_detalhe` em todas menos crediário; débito fica em aberto
em `contas_receber` mesmo já tendo entrado no caixa). Suíte completa do projeto: 251/251 verdes
(2026-07-30).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `financeiro.fechamentocaixa.tela`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

`caixa_mestre` ganha `valor_contado_dinheiro numeric(12,2)` e `id_usuario_fechamento integer` (FK
composta pra `usuario`) — editado dentro de `V025__financeiro_caixa_crediario.sql` (banco ainda em
construção, sem nova migration numerada). Nenhuma tabela nova. `caixa_fechado`/`data_fechamento`
já existiam desde V025 e passam a ser usados pela primeira vez.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Sangria/suprimento durante o dia** — fora de escopo, só abertura + fechamento.
- **Conferência de outras carteiras além de "DINHEIRO"** — só dinheiro físico é conferido; cartão/
  Pix/crediário não têm contagem manual.
- **Reabrir um caixa fechado** — não existe rota pra isso; um fechamento é definitivo (só a
  consulta/reimpressão continuam disponíveis depois).
- **Conciliação de cartões** (baixar as parcelas de débito/crédito em `contas_receber` quando a
  operadora efetivamente pagar) — mencionada como próximo passo pelo dono do produto, mas fora
  desta feature.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Fechamento de caixa em menos de 30 segundos, com a diferença de dinheiro contado visível antes de
confirmar.
