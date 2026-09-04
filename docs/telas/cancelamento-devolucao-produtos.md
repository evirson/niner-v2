# Spec: Cancelamento de Devolução de Produtos              Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-10 · Módulo(s): `vendas` (cancelamentodevolucao) · Fase: 2 — Vendas/Financeiro

> ⚠️ **Desde 2026-08-20, este cancelamento pode ser recusado.** Ele **debita** estoque (a
> mercadoria que a devolução tinha devolvido sai de novo), então passa pela mesma regra das demais
> rotinas: se o lojista **desligar** "Permite quantidade de estoque negativo" (Parâmetros do
> Sistema → Estoque), nenhum débito pode deixar o saldo abaixo de zero. Com o parâmetro no padrão
> (ligado), nada muda. Ver `docs/telas/configuracao-geral.md`.

## Problema

Depois que a Devolução de Produtos passou a emitir um vale-mercadoria por devolução
(`docs/telas/devolucao-produtos.md`), não existia nenhuma forma de desfazer uma devolução
lançada por engano — nem a tabela `venda_devolucao` tinha um conceito de situação
(Ativo/Cancelado), nem existia rotina que retirasse do estoque a quantidade que a devolução tinha
colocado de volta. Pedido direto do dono do produto, fecha o mesmo tipo de lacuna que o
Cancelamento de Venda já fecha para a venda — mas para a devolução.

## Solução implementada

Tela nova (`/cancelamento-devolucao-produtos`, menu Frente de Loja → Cancelamentos, ADMIN e
OPERADOR) que, diferente do Cancelamento de Venda, abre com um **popup obrigatório de filtros**
ao entrar: número do vale de devolução (que também é o `id_devolucao`) OU um intervalo de datas
da devolução. Confirmado o popup, a grid mostra os vales ainda canceláveis; o ícone de visualizar
abre um modal de confirmação com os itens que vão sair do estoque, pede um motivo e efetiva o
cancelamento numa única transação.

**Correção 2026-08-11 (dia seguinte):** o popup de filtros cobre visualmente o ✕ da topbar
(`BotaoFecharTela`), e por ser a entrada obrigatória da tela não havia como sair sem confirmar os
filtros. Ganhou um botão próprio **"Fechar"** (ghost, ao lado de "Localizar Devoluções") que chama
`navigate(-1)` — mesmo padrão de fechamento do resto do sistema
([[project_botao_fechar_tela]]).

## Decisões de escopo (fechadas via chat com o dono do produto, sem `AskUserQuestion` prévio —
respondidas diretamente)

**Chave de pesquisa = ID da devolução, não um campo de busca livre.** A primeira formulação do
pedido citava "chave de pesquisa, Nº vale devolução" como dois campos; esclarecido que são o
mesmo campo (`venda_devolucao.id_devolucao`, que também é o número impresso no vale) — os
filtros reais são só **ID do vale OU data inicial/final**, mesmo padrão binário do Cancelamento
de Venda (nº da venda OU período).

**Só é possível cancelar um vale ainda não usado.** Regra central do pedido, ao pé da letra:
"só pode existir um cancelamento se o vale que foi gerado nesta devolução ainda não foi usado".
Diferente do Cancelamento de Venda (que sempre pode reabrir um vale que a própria venda cancelada
tinha consumido), aqui não existe reversão de resgate — se o vale já foi gasto numa venda, a
única forma de desfazer é cancelar essa venda primeiro (`Cancelamento de Venda`, que reabre o
vale) e só depois cancelar a devolução.

**Permissão: ADMIN e OPERADOR, com OPERADOR restrito à empresa logada.** Diferente do
Cancelamento de Venda (ADMIN-only — reverte caixa/contas a receber, superfície maior), esta
feature só mexe em estoque, mesmo nível de acesso de Devolução de Produtos/Transferência de
Estoque. Mas, ao contrário dessas duas (sem restrição de empresa dentro do papel OPERADOR), o
dono do produto pediu explicitamente escopo por empresa: "se for usuário admin pode cancelar
qualquer devolução, se não for admin, só pode cancelar as devoluções da empresa que está
logado" — ADMIN cancela de qualquer empresa do tenant, OPERADOR só da empresa ativa da sessão
(`eid` do JWT).

## User stories

- Como ADMIN ou OPERADOR, quero localizar um vale de devolução pelo número, ou por um intervalo
  de datas, e ver só os que ainda podem ser cancelados.
- Como OPERADOR, só quero enxergar e cancelar devoluções da empresa em que estou logado.
- Como ADMIN, quero cancelar a devolução de qualquer empresa do tenant.
- Como ADMIN ou OPERADOR, quero ver os itens que vão sair do estoque antes de confirmar, informar
  um motivo e confirmar explicitamente.
- Como ADMIN ou OPERADOR, se o vale já foi usado numa venda, quero ser impedido de cancelar e
  entender por quê.

## Regras de negócio

### RN-01 — ADMIN e OPERADOR, OPERADOR escopado por empresa

`listar`/`buscarDetalhe`/`cancelar` filtram `id_empresa = eid` (claim do JWT) sempre que o
usuário não é ADMIN — `CancelamentoDevolucaoService.exigirAcessoAEmpresa`, 403 se um OPERADOR
tentar acessar/cancelar uma devolução de outra empresa. ADMIN não tem essa restrição.

### RN-02 — Só vale ainda não usado é cancelável

Fora da busca direta por ID, a listagem por período já filtra `vale_usado = false AND cancelada =
false` — só mostra o que é de fato acionável. Buscar pelo ID mostra o vale mesmo que já usado ou
já cancelado (para o operador entender o porquê do bloqueio, mesmo padrão do Cancelamento de
Venda). No `cancelar()`: `vale_usado = true` → 409 definitivo; `cancelada = true` → 409 com data/
usuário/motivo do cancelamento anterior.

### RN-03 — Confirmação explícita com motivo

O modal sempre mostra o resumo da devolução + itens que sairão do estoque antes de qualquer ação;
exige motivo não vazio; botão de ação é "Sim, Cancelar Devolução".

## O que o cancelamento reverte

Tudo numa única transação (`CancelamentoDevolucaoService.cancelar`):

- **Estoque:** um novo `produto_movimento_mestre` (`tipo_movimento = 'CANCELAMENTO_DEVOLUCAO'`,
  novo valor de enum dedicado — não reaproveita `CANCELAMENTO` da venda, para o Kardex/relatórios
  distinguirem qual operação foi revertida) + um `produto_movimento_detalhe`
  (`credito_debito = 'D'`) por item, inverso do `'C'` gravado pela devolução original — a trigger
  `fn_atualiza_estoque_movimento` (já existente) tira a quantidade de `produto_estoque` sozinha.
  O `produto_movimento_mestre` original (`tipo_movimento = 'DEVOLUCAO'`) nunca é apagado nem
  alterado (P3, ledger imutável).
- **Nada de caixa/contas a receber:** a devolução nunca gerou lançamento em `caixa_detalhe` nem
  `contas_receber` — só o vale-mercadoria (`venda_devolucao`) e o ledger de estoque.

`venda_devolucao` grava `cancelada = true`, `data_cancelamento`, `id_usuario_cancelamento`,
`motivo_cancelamento` — mesmo padrão de `venda.cancelada`, funciona como o próprio registro de
auditoria (P3).

## Consistência: um vale cancelado nunca pode ser resgatado

Duas verificações passaram a checar `cancelada`, além de `vale_usado`, para o vale nunca ser
resgatável depois de cancelado (sem essa checagem, o estoque já retirado pelo cancelamento seria
"dado" de novo sem lastro):

- **`PdvVendaService.resolverVale`** (resgate real no split-tender) — `ValeInfo` ganhou o campo
  `cancelado`; 409 "O vale-mercadoria nº X foi cancelado." antes mesmo de checar `usado`.
- **`DevolucaoProdutoService.buscarVale`** (consulta usada tanto pela reimpressão quanto pelo
  PDV para *mostrar* o vale antes de tentar pagar) — `ValeMercadoriaResponse` ganhou `cancelada`;
  `FormaPagamentoModal.tsx` mostra "O vale nº X foi cancelado." na hora de buscar, sem precisar
  tentar enviar o pagamento pra descobrir.

## Contrato de API

```
GET  /api/v1/vendas/cancelamento-devolucao?idDevolucao=&dataInicial=&dataFinal=&pagina=&limite=&ordenarPor=&direcao=
GET  /api/v1/vendas/cancelamento-devolucao/{idDevolucao}     detalhe (itens que sairão do estoque)
POST /api/v1/vendas/cancelamento-devolucao/{idDevolucao}     { motivo } → executa o cancelamento
```

`idDevolucao`, se informado, ignora `dataInicial`/`dataFinal`. Sem ele, ambas as datas são
obrigatórias (400 se ausentes, invertidas, ou intervalo > 365 dias). Todos sob `/api/v1/**` (JWT
de tenant, RLS — P8). Erros em Problem Details: 400 (filtros inválidos, motivo vazio), 403
(OPERADOR tentando outra empresa), 404 (devolução inexistente/de outro tenant), 409 (vale já
usado, ou já cancelada).

## Critérios de aceitação (viram testes)

- Dado um OPERADOR, quando lista ou tenta cancelar uma devolução de outra empresa, então 403.
- Dado um OPERADOR, quando lista ou cancela uma devolução da própria empresa, então funciona.
- Dado um ADMIN, quando lista ou cancela uma devolução de qualquer empresa do tenant, então
  funciona.
- Dado um vale ainda não usado, quando cancelado com sucesso, então o estoque volta à quantidade
  anterior à devolução, e `venda_devolucao` grava `cancelada/data_cancelamento/
  id_usuario_cancelamento/motivo_cancelamento`.
- Dado um vale já usado numa venda, quando tenta cancelar, então 409 e nada é revertido.
- Dado uma devolução já cancelada, quando tenta cancelar de novo, então 409.
- Dado um vale cancelado, quando usado como pagamento `VALE_MERCADORIA` numa venda (PDV), então
  409 — tanto na busca (`GET /vendas/devolucao/vale/{id}`) quanto na efetivação
  (`POST /pdv/vendas`).
- Dado o ID de uma devolução, quando buscado, então ignora o filtro de data (mesmo já usada/
  cancelada, para mostrar o porquê do bloqueio).
- Dado uma devolução de um tenant, então não aparece nem pode ser cancelada por outro tenant
  (RLS).

Verificado ao vivo (API via curl + tela via `claude-in-chrome`): vale de R$379,90/759,80 gerado,
estoque creditado, cancelado — estoque debitado de volta a zero, segunda tentativa de cancelar
409, tentativa de resgate no PDV 409 (backend e busca do vale no frontend). Escopo por empresa
testado criando um usuário OPERADOR restrito a uma filial: só via/cancelava a própria devolução,
403 na de outra empresa; ADMIN via as duas. **Sem testes JUnit automatizados ainda** — ver
"Questões abertas".

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `vendas.cancelamentodevolucao.lista`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

- `venda_devolucao` ganha `cancelada boolean NOT NULL DEFAULT false`, `data_cancelamento
  timestamptz`, `id_usuario_cancelamento integer` (FK composta pra `usuario`,
  `venda_devolucao_usuario_cancelamento_fk`), `motivo_cancelamento text` — editado dentro de
  `V018__vendas.sql` (banco em construção, sem nova migration numerada).
- `tipo_movimento` (ENUM, `V013__dominio_tipos_enum.sql`) ganha o valor
  `CANCELAMENTO_DEVOLUCAO`.
- Nenhuma tabela nova — reaproveita `produto_movimento_mestre/detalhe` e `venda_devolucao` já
  existentes.

## Impacto em código já existente (fora do pacote novo)

- `PdvVendaService.resolverVale`/`resolverPagamentos` — bloqueia resgate de vale cancelado.
- `DevolucaoProdutoService.buscarVale` / `DevolucaoProdutoDtos.ValeMercadoriaResponse` — ganhou
  `cancelada`; `web/src/lib/devolucaoProduto.ts` e `FormaPagamentoModal.tsx` (PDV) refletem isso
  na busca do vale.
- `RelatorioMovimentacaoProdutosDtos`/`Service` (Kardex) e os equivalentes no frontend
  (`relatorioMovimentacaoProdutos.ts`, `FiltrosMovimentacaoProdutosModal.tsx`) — reconhecem o
  novo valor de enum (rótulo "Cancelamento de Devolução", origem mapeada como `Devolução #N`
  igual `DEVOLUCAO`), senão o Kardex ficaria cego pro novo tipo de movimento.
- `web/src/lib/menu.ts` — item movido de `implementacoes-futuras` pro subgrupo `cancelamentos`
  (Frente de Loja), sem `adminOnly`.
- `web/src/App.tsx` — rota trocou de `<EmBreve>` pro componente real, **fora** do bloco
  `RequireAdmin` (ADMIN+OPERADOR).

## Non-goals desta feature

- **Reabertura de vale consumido por uma venda cancelada** — já existe, mas do lado do
  Cancelamento de Venda (`docs/telas/cancelamento-venda.md`), não duplicado aqui.
- **Cancelar a venda que gerou o vendedor/venda de crédito da devolução** — sem vínculo, mesmo
  non-goal já documentado em `docs/telas/devolucao-produtos.md`.
- **Motivo pré-preenchido ou sugestões de motivo** — campo livre, sem lista.

## Questões abertas

- **Sem testes JUnit automatizados** (`CancelamentoDevolucaoCrudTest` ainda não existe) — todas
  as demais rotinas de cancelamento/devolução do sistema têm suíte própria
  (`CancelamentoVendaCrudTest`, `DevolucaoProdutoCrudTest`, `ValeMercadoriaCrudTest`); esta
  feature foi verificada só manualmente (curl + navegador) até aqui. P5 da constituição pede
  teste automatizado por critério de aceitação antes do merge — pendência a fechar antes de
  considerar a feature 100% pronta pro padrão do projeto.

## Métrica de sucesso

Cancelamento de um vale simples (popup de filtro → localizar → confirmar) em menos de 20
segundos, com o operador sempre vendo o que será retirado do estoque antes de confirmar.

---

## Revisão 2026-08-22 — erro do servidor virou popup, e o campo ganhou foco (auditoria, 10 e 13)

**Item 10.** O erro de negócio devolvido pelo servidor saía em banner inline (`erro-campo`), contra a
convenção do projeto — toda mensagem de erro vira popup —, e a mensagem multilinha **colapsava numa
linha só**, escondendo a instrução do que fazer. Agora vai para `AvisoModal`, que ganhou
`white-space: pre-line`.

⚠️ **A validação LOCAL continua inline**, junto ao campo: "informe o motivo" é resposta ao que o
operador acabou de digitar, e ali o texto curto ao lado do campo é o certo. O que mudou foi só a
recusa vinda do servidor.

**Item 13.** O campo principal da tela (Nº do Vale de Devolução) não tinha `autoFocus` — convenção
de toda tela de lista/localização desde 2026-08-22.

---

**Revisão 2026-09-04 — a grid navega por ↑/↓.** As setas percorrem as linhas e a linha corrente
fica realçada (`.linha-focada`: fundo translúcido + faixa à esquerda), sem tirar o foco do campo de
busca. O mecanismo é comum às 18 telas de lista e está descrito no arquivo-padrão
`docs/telas/cliente.md`; a implementação é `web/src/lib/useNavegacaoDeGrid.ts`.

⚠️ **Esta tela não foi aberta no navegador na entrega de 09-04** — a mudança foi
aplicada por script e conferida (o import resolve, o spread caiu na `<tr>` da grid principal e não
de um modal, não há `return` antecipado antes do hook) mais `tsc -b`, mas só sete das dezoito
telas foram exercitadas de verdade.
