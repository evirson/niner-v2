# Spec: Abertura de Caixa                          Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-30 · Módulo(s): `financeiro` (caixa) · Fase: 2 — Crediário/Caixa (Q5/ADR-010)

## Problema

`caixa_mestre` existe desde V025 (2026-07-16), mas até 2026-07-30 nunca era aberto de forma
explícita: o Recebimento de Crediário abria um sozinho, em silêncio, sempre com `saldo_inicial =
0`, na primeira vez que o usuário recebia uma parcela no dia (`docs/telas/recebimento-
crediario.md`, seção "Caixa"). O PDV nem checava se havia caixa aberto. Não existia tela para o
operador informar o valor físico com que o caixa começa o dia.

## Solução proposta

Tela dedicada de Abertura de Caixa + um popup obrigatório reaproveitado no PDV e no Recebimento
de Crediário. Um `caixa_mestre` por usuário/empresa/dia:

- **Tela dedicada** (`/abertura-caixa`, no subgrupo **Caixa** dentro de *Frente de Loja* —
  `web/src/lib/menu.ts:103-120`, junto do Fechamento de Caixa): se já há caixa aberto hoje para o
  usuário logado nesta empresa, mostra só a informação (hora de abertura, moeda, saldo inicial) —
  sem formulário, sem reabrir. Se não há, mostra o formulário (moeda + saldo inicial) e o botão
  "Abrir Caixa".
- **Popup obrigatório** (`AberturaCaixaModal.tsx`) — ao entrar no PDV ou no Recebimento de
  Crediário, a tela checa `GET /api/v1/caixa/status`; se `aberto = false`, um popup com o mesmo
  formulário cobre a tela inteira **sem opção de pular** — só "Voltar" (sai pra `/`) ou "Abrir
  Caixa". Nenhuma ação da tela por trás é possível até o caixa abrir.
- **Rede de segurança no backend** — `PdvVendaService.efetivarVenda` e
  `RecebimentoCrediarioService.efetivarRecebimento` chamam
  `CaixaService.idCaixaAbertoObrigatorio()` antes de gravar qualquer coisa; sem caixa aberto,
  400 (`IllegalStateException`) e nada é gravado. Cobre o caso de alguém chamar a API direto,
  sem passar pela tela.

## Decisões (confirmadas com o dono do produto via `AskUserQuestion` antes de implementar)

1. **Saldo inicial: uma linha só (moeda + valor), não split-tender** (recomendado, escolhido). Um
   campo de valor + a moeda do saldo inicial. Diferente do split-tender de pagamento do PDV/
   Recebimento — aqui é só uma referência de onde o saldo inicial "mora", não uma composição de
   várias formas. **Revisado em 2026-07-31** (pedido direto do dono do produto): deixou de ser um
   select — o saldo inicial só pode ser aberto na carteira "Dinheiro" (cartão/PIX/crediário não
   têm saldo inicial de verdade, só recebem movimento durante o dia). Ver seção "Revisão
   2026-07-31" abaixo.
2. **Gatilho do popup no PDV/Recebimento: ao entrar na tela, não só ao efetivar** (recomendado,
   escolhido). Assim que a tela carrega, se não há caixa aberto, o popup já aparece — o operador
   nunca chega a montar uma venda/recebimento pra só depois descobrir que precisa abrir o caixa.

## User stories

- Como operador, quero abrir o caixa do dia informando quanto dinheiro físico ele já tem, antes
  de começar a vender ou receber crediário.
- Como operador, se eu esquecer de abrir o caixa e for direto pro PDV ou pro Recebimento de
  Crediário, quero que o sistema me peça a abertura ali mesmo, sem precisar navegar pra outra tela
  e voltar.
- Como operador, se eu já abri o caixa hoje, quero que a tela de Abertura de Caixa só me informe
  isso, sem me deixar abrir um segundo.

## Regras de negócio

### Um caixa por usuário/empresa/dia

`caixa_mestre` é filtrado por `id_tenant + id_empresa + id_usuario + data_abertura::date =
CURRENT_DATE + caixa_fechado = false`. Dois usuários na mesma empresa têm caixas independentes;
o mesmo usuário em duas empresas (ver `docs/telas/login-empresa.md`) também — a claim `eid` do
JWT decide a empresa corrente. Tentar abrir um segundo caixa no mesmo dia responde 409
(`ConflitoDadosException`).

### Saldo inicial e moeda ficam no cabeçalho, não em `caixa_detalhe`

`caixa_mestre` ganhou de volta (2026-07-30) as colunas `id_carteira` (FK `tipo_carteira`,
`NOT NULL`) e `saldo_inicial numeric(12,2) NOT NULL DEFAULT 0` — tinham sido removidas mais cedo
no mesmo dia (pedido direto, sem essa feature ainda desenhada) e voltaram já com a FK pra moeda,
que a versão anterior não tinha. Não é um lançamento de `caixa_detalhe`: é um dado fixo da sessão
de caixa, gravado uma vez na abertura.

### PDV e Recebimento de Crediário passam a exigir caixa aberto

Antes de 2026-07-30: o PDV não olhava pra `caixa_mestre` de jeito nenhum (a venda nunca grava em
`caixa_detalhe`, ainda fora de escopo); o Recebimento de Crediário abria um caixa sozinho, com
saldo zero, na hora de gravar o primeiro lançamento do dia (comportamento antigo, ver histórico
em `docs/telas/recebimento-crediario.md`). A partir de agora, os dois **exigem** um caixa já
aberto — nenhum dos dois abre nada sozinho.

> **Terceira rotina que exige caixa aberto (2026-08-14): a baixa de Contas a Pagar em dinheiro.**
> Pagar uma conta com `origemPagamento = CAIXA` grava um `DEBITO_CAIXA` em `caixa_detalhe` no
> caixa aberto do usuário e, sem caixa aberto, devolve **400** ("Não há caixa aberto para
> registrar o pagamento em dinheiro. Abra o caixa ou pague pela conta corrente.") —
> `api/src/main/java/com/vetor/niner/financeiro/contaspagar/ContaPagarService.java:193-206`.
> ✅ **Resolvido em 2026-08-15.** A tela de Contas a Pagar passou a abrir o mesmo
> `AberturaCaixaModal` — mas com um gatilho diferente, de propósito: no PDV/Recebimento o popup
> bloqueia a tela inteira **na entrada** (nada se faz sem caixa); aqui ele só aparece quando o
> operador escolhe **"Caixa da loja"** como origem do pagamento, porque pagar pela conta corrente
> — ou só editar a conta — não precisa de caixa nenhum. Por isso o `aoVoltar` daquela tela
> **limpa a origem e devolve o operador ao formulário**, em vez de navegar pra fora
> (`ContasPagarForm.tsx`; o componente não decide isso, quem passa o `aoVoltar` decide).
> ~~Inconsistência de UX conhecida: a tela de Contas a Pagar não abre o `AberturaCaixaModal` — só
> mostra o erro e deixa o operador se virar.~~

### Fechamento de caixa

`caixa_mestre.caixa_fechado`/`data_fechamento` já existiam no schema (V025) mas ficaram sem
nenhuma tela ou rotina até 2026-07-30, quando a Abertura ganhou uma tela irmã — ver
`docs/telas/fechamento-caixa.md`.

**Fechar deixou de ser definitivo em 2026-08-14:** existe **reabertura ADMIN-only** com motivo
obrigatório (`POST /api/v1/caixa/fechamento/{id}/reabrir`, `CaixaService.reabrir`,
`api/src/main/java/com/vetor/niner/financeiro/caixa/CaixaService.java:251-300`), criada porque
estorno de recebimento de crediário, exclusão e reabertura de conta a pagar apagam linhas de
`caixa_detalhe` e, em caixa já fechado, descasariam a conferência gravada.

Isso **não afrouxa a regra desta tela** — "um caixa aberto por usuário/empresa/dia" vale também na
volta: reabrir um caixa antigo enquanto o do dia está aberto criaria dois caixas abertos para a
mesma pessoa (e o PDV não saberia em qual lançar), então a reabertura checa o mesmo invariante e
responde **409** com "Este operador já tem outro caixa aberto. Feche o caixa aberto antes de
reabrir este." (`CaixaService.java:262-276`). Detalhes em `docs/telas/fechamento-caixa.md`.

## Contrato de API

```
GET  /api/v1/caixa/status     status do caixa de hoje (aberto/fechado, dados se aberto)
GET  /api/v1/caixa/carteiras  só a carteira "Dinheiro" do tenant (categoria AVISTA) — 2026-07-31
POST /api/v1/caixa/abrir      { idCarteira, saldoInicial } → abre; 409 se já houver um aberto
```

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), abertos a ADMIN e OPERADOR (mesma decisão
de PDV/Recebimento de Crediário — operação de caixa do dia a dia). Erros em Problem Details (RFC
9457): 400 (`idCarteira` inexistente, diferente de "Dinheiro" — 2026-07-31 —, `saldoInicial`
negativo ou ausente), 409 (caixa já aberto hoje para este usuário/empresa).

## Critérios de aceitação (viram testes)

- Dado nenhum caixa aberto hoje, quando consulta o status, então `aberto = false` e os demais
  campos vêm nulos.
- Dado uma abertura válida (carteira existente + saldo ≥ 0), quando confirma, então
  `caixa_mestre` grava `id_carteira`/`saldo_inicial` e o status seguinte reflete `aberto = true`
  com os mesmos dados.
- Dado um caixa já aberto hoje pro usuário/empresa, quando tenta abrir de novo, então 409 e nada
  muda.
- Dado um `idCarteira` que não existe (ou é de outro tenant), quando tenta abrir, então 400.
- Dado o caixa de um tenant aberto, então não aparece no status de outro tenant (RLS).
- Dado o PDV sem caixa aberto, quando tenta efetivar uma venda direto pela API, então 400 e nada
  é gravado (rede de segurança do backend — a tela em si já bloqueia antes via popup).
- Dado o Recebimento de Crediário sem caixa aberto, quando tenta efetivar direto pela API, então
  400 e nada é gravado (mesma rede de segurança).

Cobertos por `CaixaCrudTest` (6 testes) + 1 teste novo em cada de `PdvCrudTest`/
`RecebimentoCrediarioCrudTest` (as demais 17/8 pré-existentes ganharam uma chamada de abertura de
caixa no setup, pra continuarem passando). Suíte completa do projeto: **500/500 verdes
(2026-08-14)** — eram 211 quando esta tela nasceu, em 2026-07-30.

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `financeiro.aberturacaixa.tela`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

`caixa_mestre` ganha `id_carteira integer NOT NULL` (FK composta pra `tipo_carteira`) e
`saldo_inicial numeric(12,2) NOT NULL DEFAULT 0` — editado dentro de
`V025__financeiro_caixa_crediario.sql` (banco ainda em construção, sem nova migration numerada).
Nenhuma tabela nova.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Fechamento de caixa** — construído em seguida, mesmo dia, ver `docs/telas/fechamento-
  caixa.md`.
- **Sangria/suprimento durante o dia** — fora de escopo.
- **Múltiplas moedas no saldo inicial** — uma linha só (moeda + valor), ver decisão 1 acima.
- **Histórico/relatório de aberturas** — a tela dedicada só mostra a abertura de hoje.

## Revisão 2026-07-31 — saldo inicial só em "Dinheiro"

Pedido direto do dono do produto, em teste manual: cartão/PIX/crediário não têm "saldo inicial"
de verdade — só recebem movimento (crédito/débito em `caixa_detalhe`) durante o dia. Deixar
qualquer carteira disponível na abertura não fazia sentido de negócio e ainda abria brecha pra
abrir o caixa com a carteira errada por engano.

- **Backend** (`CaixaService`): `listarCarteirasParaAbertura()` filtra
  `categoria_carteira = 'AVISTA' AND nome_carteira = 'DINHEIRO'` em vez de listar todo
  `tipo_carteira` do tenant. `validarCarteira()` (chamada em `abrir()`) reforça a mesma regra —
  P4, nunca só o front barra; um `POST /caixa/abrir` direto com outra carteira responde 400
  ("O saldo inicial do caixa só pode ser aberto com a carteira \"Dinheiro\".").
- **Frontend** (`CamposAberturaCaixa.tsx`, reaproveitado pela tela dedicada e pelo popup
  obrigatório do PDV/Recebimento): o select de moeda virou um campo fixo, somente leitura,
  mostrando "DINHEIRO" (ou uma mensagem de erro se a carteira não existir — cadastro apagado ou
  renomeado). Sem escolha nenhuma nesta tela a partir de agora.
- **Testes:** +2 em `CaixaCrudTest` — a lista de carteiras só traz "Dinheiro" (não traz PIX,
  também `AVISTA`, semeada no signup); abrir com outra carteira (PIX) responde 400. Suíte
  completa do projeto: **500/500 verdes (2026-08-14)** (eram 266 na data desta revisão).

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Abertura de caixa em menos de 10 segundos, sem impedir o operador de entender por que o PDV/
Recebimento de Crediário está bloqueado.
