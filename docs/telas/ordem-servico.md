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

## Critérios de aceitação (`OrdemServicoTest`, 20 testes)

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

---

## Fora de escopo desta versão

- **NFS-e.** O produto vai oferecer a emissão (decisão dele), mas ela espera as credenciais
  municipais da empresa de homologação. Hoje a venda de serviço sai na **papeleta**, e a nota é
  decisão do lojista — o mesmo desenho da venda de mercadoria.
- **Agenda / hora marcada.** `produto_servico.duracao_minutos` já é gravada, mas nada a consome.
- **Comissão por item.** `ordem_servico_item.id_funcionario` (quem executou) é gravado, mas o
  relatório de comissões continua olhando o vendedor da venda.
- **Papeleta com serviço e peça separados.** Hoje o comprovante lista tudo junto.
