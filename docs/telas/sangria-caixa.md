# Sangria de Caixa

> **Rota:** `/sangria-caixa` · **Chave RBAC:** `sangria-caixa` · **Menu:** Frente de Loja, ao lado
> do Fechamento de Caixa · **Migration:** `V094__sangria_de_caixa.sql` (2026-08-29)

## O problema que ela resolve

Não existia **nenhuma** forma de o operador tirar dinheiro do caixa. A loja vendia R$ 5.000 em
dinheiro, depositava no banco de tarde, e o sistema continuava afirmando que os R$ 5.000 estavam na
gaveta.

⚠️ O valor `DEBITO_CAIXA` está no enum `tipo_operacao_caixa` desde a V025, e isso enganou a leitura
do código por meses: o único que o emite é a **baixa de Contas a Pagar** — que paga uma dívida ao
fornecedor, não move dinheiro para o banco. E `CREDITO_CAIXA` ("Suprimento de caixa") só aparece
como rótulo no Fluxo de Caixa; **ninguém o emite**.

## ⭐ A regra que define o desenho: sangria é TRANSFERÊNCIA, não saída

> *"esta sangria tem que ter um destino: sempre será depositada numa conta bancária, ou vai pro
> caixa central que tb está definido como uma conta bancária"* — dono do produto, 2026-08-29.

Toda sangria escreve **três linhas na mesma transação**:

| Tabela | O que grava |
|---|---|
| `caixa_sangria` | o mestre — quem, quando, quanto, de qual caixa, para qual conta |
| `caixa_detalhe` | o **débito** no caixa (`DEBITO_CAIXA`, `credito_debito = 'D'`) |
| `conta_corrente_movimento` | o **crédito** na conta de destino |

As duas últimas apontam de volta por `id_sangria`, **com FK de verdade** — ao contrário de
`id_conta_pagar` nas mesmas duas tabelas, que nasceu sem FK "de propósito" e foi assim que um
`excluir()` passou meses deixando movimento órfão sem o banco reclamar.

⛔ Dinheiro que "sai" sem destino desaparece do fluxo. Por isso não existe sangria sem conta.

## Regras

### O caixa vem do servidor, nunca do corpo da requisição
`SangriaRequest` **não tem** `idCaixa`: é sempre o caixa aberto do próprio usuário. Aceitá-lo pelo
corpo deixaria um operador tirar dinheiro do caixa de outra pessoa por chamada direta à API — e a
tela nunca teria como oferecer isso.

### Só o dinheiro que está na gaveta
O disponível é da **carteira de abertura**, não do total do caixa. Um caixa que vendeu R$ 3.000 no
cartão e R$ 200 em dinheiro tem **R$ 200** para sangrar: somar o total deixaria o operador
"depositar" dinheiro que o adquirente ainda nem repassou.

### Trava o caixa antes de ler o saldo
`FOR UPDATE` na linha do `caixa_mestre`. Sem ela, dois cliques leem o mesmo disponível e sangram
duas vezes o que só cabia uma — a mesma corrida das quatro rotinas de dinheiro corrigidas no mesmo
dia (pendência 58).

### O depósito não se edita pelo extrato
`ContaCorrenteMovimentoService.exigirLancamentoManual` recusa alterar ou excluir a linha com
`id_sangria`, com 409. É a lição de 2026-08-15 aplicada **no mesmo dia** em que a rotina nasceu:
tabela de CRUD manual que passa a receber lançamento automático precisa recusar a edição já — senão
o extrato descasa do caixa **em silêncio**, e o banco não impede porque é uma linha comum.

### Sangria não se cancela (ainda)
`cfg_tela` declara **só incluir**, e o `GRANT` não tem `DELETE` — um DELETE acidental falha no banco
em vez de sumir com o rastro. Desfazer exigiria o mesmo guard de "caixa fechado" das outras rotinas,
e essa decisão não foi tomada. ⏭️ Se for pedida, o caminho é `VinculoCaixa.SANGRIA` +
`exigirCaixaAbertoParaDesfazer`, como o estorno de crediário.

## Contrato de API

| Verbo | Rota | O que faz |
|---|---|---|
| `GET` | `/api/v1/caixa/sangrias/contexto` | há caixa aberto? qual carteira? quanto há para sangrar? |
| `GET` | `/api/v1/caixa/sangrias` | as sangrias do caixa aberto |
| `POST` | `/api/v1/caixa/sangrias` | registra (201) |

**Recusas:** sem caixa aberto → 409 · valor acima do disponível → 409 · conta inexistente ou
inativa → 404.

## O efeito no Fluxo de Caixa

⭐ A sangria é a **metade que faltava** para o fundo de troco fazer sentido. Antes dela,
`FluxoCaixaService.saldoAte` somava `saldo_inicial` de **todas** as aberturas até a data, e nada
retirava esse dinheiro: uma loja que abre todo dia com R$ 200 e não vende nada mostrava, após 20
dias úteis, **"saldo real: R$ 4.000"** — com R$ 200 na gaveta.

Decisão do dono do produto: *"Fundo de caixa compõe o saldo final"* — ele conta. Juntando com a
sangria, a conta que fecha é **o fundo do último caixa de cada operador** (`fundoDeCaixaAte`): é o
dinheiro que ele tem na mão agora; o de ontem é a mesma cédula, e o que saiu virou sangria e está
no banco.

⚠️ E a contrapartida no período é a **variação** do fundo, não a soma das aberturas — senão a
igualdade que o relatório promete (`saldo inicial + movimentos = saldo final`) quebraria em toda
loja que abre o caixa mais de uma vez no período. No primeiro dia de uso a variação é o próprio
fundo, que é exatamente o que o teste de 2026-08-14 exigia.

## Testes

`SangriaCaixaCrudTest` — quatro casos, e o principal confere **os três lados no banco**, não o 201:
um teste que olhasse só o status passaria com metade do mecanismo, e metade é exatamente o defeito.
Sabotado (removendo o crédito no banco), dois dos quatro reprovam.
