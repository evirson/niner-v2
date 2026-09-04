# Spec: Ordem de Serviço                                      Status: Implementada
Autor: Claudio Calixto (dono do produto) + Claude · Data: 2026-08-28 · Módulo(s): `vendas` (ordemservico) · Fase: S — Serviços

## Problema

O ERP cobre a **peça** e não cobre o **trabalho**. A oficina mecânica vende o filtro de óleo pelo
PDV e não tem onde registrar a mão de obra; o petshop vende a ração e não tem onde registrar o
banho e tosa. Pior que a lacuna do cadastro é a lacuna do **tempo**: o carro fica dois dias na
oficina, e nesse intervalo alguém precisa saber que aquele amortecedor já tem dono.

Vender serviço direto no PDV não resolve, porque o PDV é instantâneo — o cliente chega, paga, sai.
O trabalho de serviço tem um começo (o carro entra), um meio (descobre-se o que ele realmente
precisa) e um fim (o cliente vem pagar), e é esse intervalo que o sistema não tinha onde guardar.

## Solução proposta

Uma **Ordem de Serviço** em Frente de Loja: o balcão abre a OS com o que já se sabe (o objeto, os
serviços previstos, as peças previstas), acompanha a execução por estados, e — concluída — puxa a
OS inteira no **PDV pelo F5**, onde ela vira venda de verdade.

⛔ **OS não é orçamento** (reforço explícito do dono do produto, 2026-08-28). São entidades
separadas, com regras opostas no ponto que mais importa: o **orçamento é imutável** (proposta
comercial que a loja se comprometeu a honrar) e a **OS é mutável** (trabalho em andamento — o
mecânico abre o motor e acha mais serviço). O que as duas compartilham é o **mecanismo** de virar
venda, não a entidade nem a regra.

---

## Regras de negócio (fechadas com o dono do produto em 2026-08-28)

### R1 — A OS abre com serviço E peça juntos

Pedido literal dele: *"quando abrir uma OS, já vai o serviço e os produtos deste serviço também,
aí puxa tudo no PDV, e lá no PDV pode incluir mais serviços e mais produtos"*.

Uma só lista no banco (`ordem_servico_item`), duas grades na tela — Serviços e Peças —, separadas
por `produto_barra.tipo_item`. A separação visual não é enfeite: é ela que responde "quanto foi mão
de obra e quanto foi material", que é a pergunta que o dono da oficina faz, e é a divisão que a
NFS-e vai precisar quando o módulo fiscal de serviço entrar.

### R2 — A OS é editável até virar venda

Estados `ABERTA`, `APROVADA`, `EM_EXECUCAO` e `CONCLUIDA` aceitam alteração. `FATURADA` (virou
venda) e `CANCELADA` (morreu) não aceitam mais nada.

⚠️ É o oposto do orçamento — de propósito. Acrescentar peça no meio do caminho **é o trabalho
normal** da oficina, não uma exceção a ser punida com "cancele e abra outra".

### R3 — A situação só anda para frente, um passo por vez

`ABERTA → APROVADA → EM_EXECUCAO → CONCLUIDA`. Não existe voltar: um estado que anda para trás faz
a reserva de estoque e o histórico contarem histórias diferentes.

⛔ `FATURADA` **não se marca pela mão** — o servidor recusa com uma mensagem explícita. Ela é
consequência de existir uma venda; marcá-la à mão criaria uma OS "cobrada" sem dinheiro por trás.
`CANCELADA` também não: tem endpoint próprio, porque cancelar **devolve estoque** (R5).

### R4 — A peça reserva estoque ao ser lançada; o serviço nunca

Decisão dele, **contra** a recomendação inicial de não reservar — e com uma razão melhor: *a peça
foi separada fisicamente para aquele carro*. Ela está na bancada, não na prateleira. Continuar
oferecendo-a no balcão é prometer o que já tem dono.

- `produto_estoque.reservado` sobe ao lançar a peça, **antes mesmo da aprovação** — o momento em
  que ela sai da prateleira é o lançamento, não o aceite do cliente.
- Alterar a OS ajusta a reserva **por delta** (`GREATEST(reservado + delta, 0)`), nunca reescrevendo
  o total: outra OS pode ter reserva na mesma variação.
- **Serviço não reserva nada** — não tem saldo. A V086 garante isso na trigger, não no serviço.
- ⚠️ Reserva **não é baixa**. O estoque só sai de verdade quando a OS virar venda no PDV.

### R5 — O caminho da OS parada é CANCELAR

Decisão dele quando perguntei o que fazer com a OS que o cliente abandonou: *"se acontecer isso,
alguém vai ter a possibilidade de cancelar a OS, aí com ela cancelada volta a peça pro estoque"*.

Cancelamento pede **motivo obrigatório**, devolve a reserva das peças e é classificado como ação
**EXCLUIR** no RBAC ("desfazer é excluir") — quem pode abrir OS não deveria poder cancelar a de
ontem.

⛔ **Não há worker de expiração.** Uma OS que expira sozinha devolveria peça enquanto o carro ainda
está no elevador. Quem sabe que o trabalho parou é a pessoa.

### R6 — Quem cobra é o PDV, pelo F5

⛔ **Não existe endpoint nem botão de "faturar" na tela da OS.** Uma segunda porta de faturamento
teria de reimplementar caixa aberto, split-tender, desconto máximo, limite de crédito, cota do
plano, papeleta e emissão fiscal — sete regras que já existem e que divergiriam na primeira
correção feita em só um dos lados.

- Só OS **CONCLUÍDA** aparece no F5 (filtro do servidor, não da tela): faturar antes de concluir
  cobraria por um trabalho que ainda pode mudar de tamanho.
- A OS entra **inteira** — não há campo de quantidade no popup. O serviço já foi prestado e a peça
  já foi aplicada; "levar metade da mão de obra" não existe. (Aqui a OS **diverge** do orçamento,
  onde o cliente pode levar menos do que orçou.)
- No PDV ainda dá para **acrescentar** serviços e peças, pelo preço de hoje — é o pedido dele.
- Cliente e vendedor vêm da OS e ficam **fixos**, pela mesma razão do orçamento.
- Ao efetivar: a OS passa a `FATURADA` com `id_venda`, e a **reserva é liberada** no mesmo passo em
  que o estoque baixa de verdade. Se a reserva não fosse liberada, a peça ficaria contada duas
  vezes — baixada e ainda reservada.

⛔ **Orçamento e OS nunca na mesma venda.** O servidor recusa: cada um traria o próprio preço
fechado para a mesma linha e não haveria como desempatar. A guarda roda **antes de qualquer
consulta** — colocada depois, um 404 de orçamento inexistente mascarava a mensagem certa (achado
pelo teste, não pela leitura).

### R7 — "Objeto do serviço" é texto livre

Placa, nome do animal, número de série do notebook. Campo obrigatório, e é **por ele que se busca a
OS** no balcão — quem chega dizendo "vim buscar o Rex" não sabe o número da OS.

⛔ Texto livre de propósito: uma tabela de veículos serviria à oficina e atrapalharia o petshop, e
o produto atende os dois. Quando (e se) o ramo de atividade justificar um cadastro estruturado, ele
entra **ao lado**, não no lugar.

### R8 — O módulo é opt-in

A OS só aparece com `cfg_geral.cfg_usa_servicos` ligado (Parâmetros do Sistema), desligado por
padrão. Decisão dele: *"por padrão o módulo de serviço vai precisar ligar ele pra funcionar, pois
as empresas de serviço são menos que as de comércio"*.

Com o módulo desligado, o F5 do PDV vai **direto** ao orçamento, exatamente como antes — quem nunca
vai abrir uma OS não paga um clique por ela; o rótulo da tecla também volta a "Buscar Orçamento".

⚠️ O **menu** respeita o módulo por um filtro próprio, `filtrarPorModulo` (`NavItem.moduloServicos`),
aplicado na lateral, na página-hub e na busca do topo. Ele é diferente de `filtrarPorPapel` e de
`filtrarPorPermissao`: ali a tela está *proibida*, aqui ela **não faz sentido** para o tenant —
mostrar "Ordens de Serviço" para uma loja de calçados é prometer função que ela nunca vai usar.
Enquanto a configuração não carregou (`undefined`), o item **fica**: um item que aparece e some
meio segundo depois é pior que um que demora a aparecer. ⛔ E esconder no menu nunca foi proteção
(P4) — quem recusa cadastrar serviço é o `ProdutoService`, e quem recusa abrir OS é o
`OrdemServicoService`.

---

## Modelo de dados (V087)

`situacao_ordem_servico` — enum `ABERTA | APROVADA | EM_EXECUCAO | CONCLUIDA | FATURADA | CANCELADA`.

**`ordem_servico`** — `id_ordem_servico`, `id_tenant`, `id_empresa`, `id_cliente`, `id_funcionario`
(quem atendeu), `objeto_servico` (NOT NULL), `observacao`, `situacao`, `data_abertura`,
`data_conclusao`, `valor_desconto`, `id_venda`, `data_faturamento`, `data_cancelamento`,
`motivo_cancelamento`, auditoria. Dois CHECK amarram o estado ao fato:

- `faturada_ck` — `FATURADA` exige `id_venda` **e** `data_faturamento`; nenhum outro estado os tem.
- `cancelada_ck` — `CANCELADA` exige `motivo_cancelamento` e `data_cancelamento`.

**`ordem_servico_item`** — `id_variacao`, `qtd_produto`, `preco_venda`, `id_funcionario` (quem
executa **este** item, nulo é normal) e **`qtd_reservada`**, que é quanto a linha segura em
estoque. Guardar a reserva na linha (e não só somada em `produto_estoque`) é o que permite liberar
exatamente o que esta OS pegou, sem depender de recalcular a partir da quantidade — que pode ter
mudado no meio do caminho.

RLS `FORCE` nas duas, com `id_tenant` explícito em toda query (P8).

---

## Contrato de API — `/api/v1/ordens-servico` (`@Tela("ordens-servico")`)

| Verbo | Caminho | Ação RBAC | O que faz |
|---|---|---|---|
| GET | `/` | ACESSAR | Lista paginada; filtros `busca` (nº, cliente, objeto), `situacao`, `dataInicial`, `dataFinal` |
| GET | `/faturaveis?idCliente=` | ACESSAR | As OS **concluídas** do cliente — a consulta do F5 |
| GET | `/{id}` | ACESSAR | A OS inteira, com itens |
| POST | `/` | INCLUIR | Abre a OS (reserva as peças) |
| PUT | `/{id}` | ALTERAR | Altera a OS (ajusta a reserva por delta) |
| PUT | `/{id}/situacao?para=` | ALTERAR | Avança um passo |
| POST | `/{id}/cancelar` | **EXCLUIR** | Cancela com motivo, devolve a reserva |

⚠️ `precoVenda` no item é **opcional**: em branco, o servidor resolve pelo cadastro. Aceitá-lo sem
conferir deixaria quem chama a API escolher quanto custa o serviço — o mesmo cuidado que a Devolução
de Produtos precisou tomar com o preço do vale-mercadoria.

⚠️ A resposta do item traz `variacaoCor` e `variacaoTamanho`. Sem eles, duas peças do **mesmo**
produto em cores diferentes saem idênticas na tela; a sentinela PADRÃO (`id_cor = 1`) vem **nula**,
nunca a palavra "PADRÃO" ao lado de todo item da oficina. Achado abrindo a tela: a linha nascia com
a variação (vinha da pesquisa de produto) e a perdia ao recarregar.

---

## Telas

- **`/ordens-servico`** — lista com busca por objeto/cliente/número, filtro de situação e período.
  Três ações: ver (verde), alterar (azul, some em FATURADA/CANCELADA) e cancelar (vermelho, idem).
- **`/ordens-servico/nova`**, **`/ordens-servico/:id`**, **`/ordens-servico/:id/visualizar`** —
  cabeçalho + duas grades (Serviços, Peças) + rodapé com desconto e os três totais.
- **PDV, F5** — com o módulo ligado, um passo pergunta "Orçamento ou Ordem de Serviço?"; com ele
  desligado, vai direto ao orçamento. O selo do topo e o selo de cliente/vendedor nomeiam o
  documento certo ("da OS", não "do orçamento").

Ajuda da tela (R22): `vendas.ordemservico.lista` e `vendas.ordemservico.form`; a de `pdv.tela`
ganhou o parágrafo do F5 com OS.

---

## Critérios de aceitação (`OrdemServicoTest`, 22 testes + 2 em `RelatorioComissoesCrudTest`)

| Dado | Quando | Então |
|---|---|---|
| Módulo ligado, um serviço e uma peça | Abro a OS | Totais saem separados (serviços × peças) |
| Uma peça com saldo | Lanço na OS | `reservado` sobe pela quantidade lançada |
| Um serviço | Lanço na OS | Nada é reservado (serviço não tem saldo) |
| OS com peça reservada | Cancelo | A reserva volta ao estoque |
| OS já cancelada | Cancelo de novo | Recusa — a reserva não é liberada em dobro |
| OS com 3 peças | Altero para 1 | A reserva vira 1, sem resto |
| OS ABERTA | Peço `EM_EXECUCAO` | Recusa — a situação anda um passo por vez |
| OS EM_EXECUCAO | Peço `APROVADA` | Recusa — não volta atrás |
| Qualquer OS | Peço `FATURADA` pela mão | Recusa com a mensagem que aponta o PDV |
| OS não concluída | Puxo no PDV | Recusa |
| OS concluída | Efetivo a venda | OS `FATURADA` com `id_venda`, reserva 0, estoque baixado |
| A mesma OS | Efetivo duas vezes | A segunda é recusada |
| OS + nenhuma linha marcada | Efetivo | Recusa (a OS seria consumida sem ser honrada) |
| OS de 2 peças | Peço 3 na venda | Recusa |
| Orçamento **e** OS na mesma venda | Efetivo | Recusa antes de qualquer consulta |
| Peça com cor/tamanho e serviço sem grade | Leio a OS | Cor/tamanho vêm preenchidos na peça e **nulos** no serviço |
| OS de outro tenant | Leio/altero | 404 (P8) |

-
---

## O que entrou depois da primeira entrega (2026-08-28, mesma data)

A OS nasceu operável e com seis lacunas, todas fechadas no mesmo dia a pedido do dono do produto
(*"faça tudo o que pode ser feito, menos a emissão da NFS-e"*).

### R9 — A via impressa (o que mais faltava)

A OS não tinha **nenhuma** forma de imprimir, e isso a deixava inutilizável no balcão que ela existe
para atender: o cliente deixa o carro e não leva nada na mão. Toda oficina trabalha com a via do
cliente; no petshop ela é o comprovante de entrega do animal.

`OrdemServicoImpressaoModal` — **bobina** (80mm, 42 colunas) e **A4**, mais envio por WhatsApp, no
mesmo desenho do orçamento. ⭐ Diferente do orçamento, os itens saem em **dois blocos** com subtotal
cada um, e o **executor** aparece ao lado do serviço: numa oficina ele é a referência de quem
procurar quando o serviço volta.

⚠️ O botão só existe depois que a OS foi salva, e imprime **o que está gravado**, nunca o que está
na tela. Alteração não salva não sai no papel — é a garantia, não a limitação.

### R10 — Quem executa cada serviço

Coluna **"Executado por"** na grade de Serviços (não na de Peças: em peça, quem vende leva). O
campo já existia no banco e na API desde a V087; faltava a tela oferecê-lo.

⛔ O seletor é um **botão que abre a pesquisa**, não um `<select>`: a lista de funcionários é
paginada, e um select só acharia quem está na primeira página.

### R11 — A comissão vai para quem executou, com o percentual do serviço (DS5)

Duas partes, as duas na V088:

1. **`produto_movimento_detalhe.id_funcionario` passa a ser quem EXECUTOU aquela linha.** Antes o
   PDV gravava o vendedor da venda em todas as linhas, e o mecânico nunca via a comissão do próprio
   trabalho.
2. **`perc_comissao` é congelado na linha**, resolvido como
   `COALESCE(produto_servico.perc_comissao, funcionario.perc_comissao)` — a comissão do **serviço**
   vence a da pessoa (o tosador ganha 20% do banho e 10% da tosa).

⭐ O congelamento conserta um defeito antigo que ninguém tinha notado: o relatório calculava
`líquido × perc_comissao` **na consulta**, então **promover um vendedor reescrevia a comissão de
todos os meses passados** — inclusive os já pagos, que deixavam de bater com a folha. Medido: com a
sabotagem, R$ 20,00 de março viravam R$ 50,00.

⚠️ O relatório agora **soma por linha** e mostra o percentual **efetivo** (comissão ÷ líquido).
Quando todas as linhas têm o mesmo percentual, o número é idêntico ao de antes.

### ⛔ R12 — A regressão que a V088 causou, e a coluna que faltava (V089/V090)

A venda **nunca teve vendedor próprio**: cinco serviços o derivavam do ledger com
`MAX(pmd.id_funcionario)` ou `LIMIT 1`, e isso funcionava porque todas as linhas tinham o mesmo
funcionário — suposição correta, e **escrita em lugar nenhum** (só num javadoc da Devolução, que
dizia "gravado igual em toda linha").

Com a R11 a suposição morreu, e a **Pesquisa de Vendas passou a mostrar "Vendedor: MECANICO JOAO"**
numa venda fechada por outra pessoa. ⚠️ **Nenhum teste pegou; quem pegou foi abrir a tela.**

A correção não é reverter (a comissão é a feature), é dar à venda o próprio vendedor:

| Coluna | Significa |
|---|---|
| `venda.id_funcionario` (V089) | quem **vendeu** — o atendimento, o caixa |
| `produto_movimento_detalhe.id_funcionario` (V088) | quem **executou aquela linha** |

Os cinco leitores passaram a ler de `venda`: Pesquisa de Vendas (grade e detalhe), Cancelamento
(grade e detalhe), Devolução, Relatório de Vendas e a **papeleta** — esta última era a mais grave,
porque um cupom de oficina sairia na mão do consumidor com o nome do mecânico no campo "Vendedor".

⚠️ **O filtro "Vendedor" da Pesquisa ficou abrangente de propósito** (`v.id_funcionario` **OU**
participação no ledger): o mecânico que procura pelo próprio nome quer achar a venda em que
trabalhou. Filtro que esconde é pior que filtro amplo demais.

⛔ **E a V089 saiu com o backfill VAZIO** — medido: 347 vendas, 0 preenchidas. O `NO FORCE ROW
LEVEL SECURITY` foi aplicado só na tabela **escrita**, e o UPDATE **lê** de outras duas, que
continuaram bloqueadas. A **V090** conserta (migration nova, nunca editando a aplicada) e é
idempotente. ⭐ A regra completa: `NO FORCE` vale **por tabela** e precisa cobrir tudo o que a
migration toca — lê e escreve.

### R13 — A OS na ficha do cliente

Faixa no **topo** do Histórico do Cliente, antes das compras — a pergunta que o balcão faz ao abrir
a ficha é "este cliente tem alguma coisa comigo agora?", e uma OS em execução é um carro no elevador.

⛔ Só as **em aberto**: a faturada já aparece como venda na aba de compras, e listar as duas
repetiria o mesmo dinheiro. Some por completo quando não há nenhuma.

### R14 — A papeleta separa serviço de produto

Dois blocos com subtotal, **só quando a venda tem os dois**. ⚠️ Venda só de mercadoria — a esmagadora
maioria — sai **exatamente** como antes: um comprovante de padaria não ganha a palavra "PRODUTOS" no
meio por causa de um módulo que aquela loja nem ligou.

### R15 — A duração vira estimativa, não agenda

`produto_servico.duracao_minutos` era gravada e nunca lida. Agora a tela soma a duração dos serviços
e mostra **"Tempo estimado dos serviços: 1h30"**.

⛔ **Não é agenda**: ninguém reserva horário, ninguém checa conflito, e a soma ignora que dois
mecânicos podem trabalhar em paralelo. Por isso o rótulo diz *estimado* e não promete um horário —
prometer "pronto às 15h30" com esse cálculo seria mentir com precisão.

---

## Fora de escopo desta versão

- **NFS-e.** O produto vai oferecer a emissão (decisão dele), mas ela espera as credenciais
  municipais da empresa de homologação. Hoje a venda de serviço sai na **papeleta** — já com os
  blocos separados que a nota vai precisar — e a emissão é decisão do lojista, o mesmo desenho da
  venda de mercadoria.
- **Agenda / hora marcada.** A duração já é somada e mostrada como estimativa (R15), mas ninguém
  reserva horário. Agenda é feature própria, com decisões de produto que ainda não foram tomadas
  (horário de funcionamento, disponibilidade por profissional, conflito).
- **Executor por LINHA quando a mesma variação se repete.** `executoresDaOrdemServico` mapeia por
  variação e fica com o primeiro executor — o javadoc registra o limite em vez de fingir que ele
  não existe. Resolver exige a chave de linha que o PDV ainda não carrega para a OS.

---

## ⛔ 2026-08-29 (auditoria, 2ª leva) — a mesma variação não entra duas vezes com preços diferentes

O PDV congela o preço da OS num `Map<idVariacao, preco>`, então de duas linhas da mesma variação
**só a última sobrevivia** — enquanto a validação de quantidade somava as duas. Uma OS com
`TROCA DE ÓLEO` 1 × R$ 100 (negociado) + 1 × R$ 150 virava uma venda de **2 × R$ 150 = R$ 300**
contra os R$ 250 que o documento impresso promete. O cliente reclama com o papel na mão e nada no
sistema explica os R$ 50.

⭐ **O orçamento NÃO tem esse problema**, e a diferença é o motivo de a trava morar na OS: lá o preço
vem sempre do cadastro (`OrcamentoService.resolverItens`), então duas linhas da mesma variação têm
forçosamente o mesmo preço. Na OS o preço **pode vir do cliente** (limitado ao teto do cadastro), e
é isso que abre a divergência.

⚠️ **A trava é na ORIGEM, não no PDV:** travar no fechamento da venda deixaria o operador com o
cliente na frente e uma OS impossível de faturar. Aqui ele ainda está montando a ordem.

⚠️ **E preço nulo não é "sem preço", é o preço de CADASTRO.** A primeira versão da trava fazia
`continue` na linha sem preço, e isso reabria o buraco pela porta ao lado: linha A a R$ 100 +
linha B da mesma variação com `precoVenda: null` (→ R$ 150) passava direto. `gravarItens` e
`exigirDescontoDentroDoTeto` já resolviam o nulo assim; a trava é que discordava das duas.

**Guarda:** `OrdemServicoTest.osNaoAceitaAMesmaVariacaoComDoisPrecos`, com o par que garante que
repetir o item **com o mesmo preço** continua valendo — a trava é sobre divergência, não sobre
repetição.

---

**Revisão 2026-09-04 — a grid navega por ↑/↓.** As setas percorrem as linhas e a linha corrente
fica realçada (`.linha-focada`: fundo translúcido + faixa à esquerda), sem tirar o foco do campo de
busca. O mecanismo é comum às 18 telas de lista e está descrito no arquivo-padrão
`docs/telas/cliente.md`; a implementação é `web/src/lib/useNavegacaoDeGrid.ts`.

⚠️ **Esta tela não foi aberta no navegador na entrega de 09-04** — a mudança foi
aplicada por script e conferida (o import resolve, o spread caiu na `<tr>` da grid principal e não
de um modal, não há `return` antecipado antes do hook) mais `tsc -b`, mas só sete das dezoito
telas foram exercitadas de verdade.
