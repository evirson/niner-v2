# Pendências da auditoria de 2026-08-21 — aguardando decisão do dono do produto

Dois agentes varreram backend e frontend em duas passadas. **13 defeitos foram corrigidos e
commitados** no mesmo dia (ver `docs/PROGRESSO.md`). Este arquivo lista o que **não** foi corrigido
porque exige uma decisão de negócio, não um conserto técnico — e serve para ser cobrado na próxima
sessão.

Ordem: os sete primeiros são os que precisam de decisão; depois vêm as pendências menores.

---

## 1. ⛔ Cancelar entrada que já teve devolução ao fornecedor debita o estoque DUAS vezes

**Onde:** `EntradaMercadoriaService.cancelar` (~:499-530).

`cancelar()` tem dois guards (já cancelada; conta a pagar já baixada) e **não** checa se existe um
movimento `DEVOLUCAO_COMPRA` apontando para aquela entrada. O estorno lança um `'D'` da quantidade
**cheia** da compra, ignorando o que já saiu pela devolução.

**Cenário:** entrada de 10 un → devolução ao fornecedor de 4 (com **NF-e 55 autorizada**) → ADMIN
cancela a entrada → estorno de 10 → estoque **−4**. As mesmas 4 unidades saíram duas vezes. Fica
ainda uma NF-e 55 válida na SEFAZ referenciando uma entrada que não existe mais, e a entrada some
da lista elegível, então nem dá para desfazer pela tela de devolução. Com
`cfg_permite_estoque_negativo` ligado (o padrão), a trigger não barra: passa em silêncio.

**Decisão necessária:** recusar o cancelamento enquanto houver devolução não cancelada (o `EXISTS`
já existe pronto em `DevolucaoCompraService`), ou cancelar em cascata? A primeira é o padrão da casa
(é o que o guard da conta paga já faz).

---

## 2. Vale-mercadoria sai pelo preço MÉDIO quando a mesma variação tem duas linhas na venda

**Onde:** `DevolucaoProdutoService` (~:206-262 e :299-320).

A devolução agrupa por `id_variacao` e deriva o unitário como média ponderada
(`valor_total_vendido / qtd_vendida`). O comentário do próprio código chama isso de "raro, mas
possível" — **e a mudança de 2026-08-21 tornou esse caso o caminho normal**: o mesmo produto agora
pode aparecer duas vezes na venda, com o preço congelado do orçamento e com o preço de hoje.

**Cenário:** orçamento de 1 un a R$ 80; no dia da venda o cadastro está a R$ 120 e o cliente leva 2.
A venda grava 1×80 e 1×120. Semanas depois ele devolve **uma** peça: o vale sai por **R$ 100** — um
valor que a venda nunca praticou. Se ele devolveu a peça orçada, a loja pagou R$ 20 a mais; se
devolveu a outra, o cliente perdeu R$ 20. O mesmo valor médio vai para a **NF-e 55 de devolução**,
que passa a declarar um unitário que não bate com nenhum item da NFC-e original.

**Decisão necessária:** qual linha a devolução consome — FIFO pela linha, ou o operador escolhe? O
mínimo é a grid passar a listar **por linha da venda** quando houver mais de um preço para a mesma
variação.

---

## 3. Orçamento de uma empresa pode ser consumido por outra do mesmo tenant

**Onde:** `PdvVendaService` (~:118) — `abrirParaVenda` devolve o `idEmpresa` do orçamento e ninguém
compara com o claim `eid` da sessão.

Não é vazamento de tenant (P8 está intacto) e o preço congelado não é por empresa, então o impacto
prático hoje é baixo. Mas o documento impresso diz "empresa A", a venda cai na B, e o estoque baixa
numa empresa diferente da que a grid do orçamento mostrou.

**Decisão necessária:** é intencional para o caso 1 tenant : 1 empresa da v1, ou deve recusar?

---

## 4. Documentos A4 não paginam — orçamento com muitos itens perde as páginas seguintes

**Onde:** `styles.css` — `.orcamento-imprimir-a4`, `.danfe-imprimir` (DANFE da devolução) e
`.guia-transferencia-imprimir`, todos em `position: absolute`.

O próprio arquivo já registra o diagnóstico, escrito em 2026-08-21 ao consertar a etiqueta:
*"aquela posiciona o bloco em `position: absolute`, o que é certo para um documento de UMA página …
e é justamente o que impede paginar"*. Os três documentos A4 continuam absolutos.

**Cenário:** orçamento com ~30 itens passa de 297 mm → imprime a primeira página e **o resto some
sem aviso** (total e assinatura ficam de fora). Mesmo raciocínio para um DANFE de devolução longo.

**Decisão necessária:** aplicar o mesmo remédio das etiquetas (portal para o `<body>` +
`display: none` no resto) nos três. Só se confirma imprimindo — vale testar com um orçamento longo
antes de um cliente encontrar.

---

## 5. Nota em contingência que sofre falha de rede sai da fila do dreno para sempre

**Onde:** `FiscalContingenciaDrenoJob` (~:149) + `DocumentoFiscalRepositorio` (~:479 e :513).

`transmitir()` marca a nota como `TRANSMITINDO` **antes** de enviar, mas as duas consultas que
alimentam o dreno filtram `situacao IN ('CONTINGENCIA','ASSINADO')`. Uma vez em `TRANSMITINDO`, a
nota **nunca mais** é encontrada pelo job — apesar de o comentário do `catch` prometer *"volta na
próxima"*. O painel de contingência conta os três estados, então ela aparece como pendente **para
sempre**.

**Cenário:** SEFAZ cai, 12 NFC-e saem em contingência com cupom na mão dos consumidores (prazo legal
de 24 h). O dreno começa a transmitir, a rede oscila na 3ª nota: as notas 1-2 autorizam, as 4-12
voltam na próxima rodada, e **a 3 fica órfã** até o prazo expirar. Nada alerta: o alarme cobre só
"rejeitada em contingência", não "presa".

**Decisão necessária:** incluir `TRANSMITINDO` na fila do dreno — mas aí a retomada **precisa
consultar a chave antes** (F5, nunca retransmitir cego), no mesmo padrão do
`DocumentoFiscalReprocessamentoService`. ⚠️ Complemento no front: a tela de Documentos Fiscais
precisa destacar `TRANSMITINDO` + `tipo_emissao = 9` com o mesmo peso de uma rejeitada.

---

## 6. O KPI "Devoluções" do Relatório de Vendas mede um período diferente do DRE e das Comissões

**Onde:** `RelatorioVendasService` (~:248-261).

Três problemas na mesma query:
1. O javadoc afirma *"sempre 0 hoje — nenhuma tela cria movimento de devolução ainda"*. **Falso
   desde 2026-08-19.** O KPI já traz número há dias com a documentação dizendo que é zero.
2. Filtra pelo período da **venda**, não da devolução. DRE e Comissões usam `data_movimento`. Uma
   devolução de abril, de uma venda de janeiro, entra no relatório de **janeiro** aqui e no de
   **abril** lá — dois relatórios do mesmo sistema discordando do mesmo fato.
3. Falta o filtro de devolução cancelada (corrigido no DRE e nas Comissões em 2026-08-21; aqui não,
   porque depende da decisão do item 2 acima).

**Cenário:** R$ 100.000 vendidos em janeiro; em abril devolvem R$ 8.000 de janeiro. O relatório de
abril mostra devoluções R$ 0; o de janeiro — fechado há três meses e já usado para decidir compra —
passa a mostrar 8%.

**Decisão necessária:** qual base é a certa? (As duas coexistindo não pode estar certo.)

---

## 7. Webhook de cobrança: o lock é solto antes do processamento

**Onde:** `CobrancaWebhookProcessador` (~:48-65).

O javadoc promete que a separação em dois beans impede que *"dois workers peguem o mesmo evento"* —
mas a transação de `pegarLote` **commita ao retornar**, soltando os locks; `processar` roda depois em
`REQUIRES_NEW`, sem lock. `aplicar()` é quase todo idempotente (o `UPDATE` do pagamento é, e a fatura
tem guard `AND status <> 'PAGA'`), **exceto o `UPDATE` da assinatura**, que empurra
`proxima_cobranca` mais um ciclo a cada reaplicação — um mês de graça por notificação duplicada.

**Reachability honesta:** com uma instância de API e `@Scheduled`, não acontece hoje. Vira real no
dia em que existirem duas instâncias — cenário que o `CLAUDE.md` prevê.

**Decisão necessária:** guard de idempotência no `UPDATE` da assinatura
(`AND proxima_cobranca < novo_valor`) ou marcar as linhas com `claimed_by` dentro da transação de
`pegarLote`.

---

# Pendências menores (não precisam de decisão; ficaram de fora por prioridade)

| # | Onde | O quê |
|---|---|---|
| 8 | `ContagemEstoque` (~:188) | Ícone de lixeira remove a linha **sem confirmação** — em todo o resto do sistema exclusão confirma. Apaga trabalho de contagem já gravado. |
| 9 | `CancelamentoVendaModal` | Não antecipa a recusa "caixa de hoje fechado": o ADMIN escreve 15+ caracteres de justificativa para levar o erro. Antecipa as outras três. ⚠️ Precisa de um `boolean caixaAbertoHoje` no DTO — o front não deduz, porque a venda pode ser de outra empresa. |
| 10 | `CancelamentoVendaModal` (~:264), `CancelamentoDevolucaoModal` (~:164) | Erro de negócio do servidor sai em banner inline (`erro-campo`) em vez de Toast/`AvisoModal`, contra a convenção — e a mensagem multilinha **colapsa numa linha só** (falta `white-space: pre-line`). |
| 11 | `RelatorioVendas` (~:385, :386, :506) | Quantidade formatada com `formatarMoeda`: o KPI "Itens Vendidos" mostra **"1.234,00"** e o rodapé da grade mostra "12,00" sob uma coluna de "3", "5", "4". |
| 12 | `InutilizacaoNumeracao` (~:201) | A ação mais irreversível do sistema (queima faixa perante o fisco, sem desfazer) é a única sem modal de confirmação. |
| 13 | `EstornoRecebimentoCrediario` (~:94), `CancelamentoDevolucao` (~:268), `OrcamentoLista` | Falta `autoFocus` no campo principal — convenção de tela de lista/localização. |
| 14 | `ContagemEstoque` / `ZerarContagemEstoque` | Mutações invalidam só `['balanco-contagem']`; `['balanco-diferencas']` (lida por duas telas) nunca é invalidada. `staleTime: 0` limita o dano, mas o modal do `EfetivarBalanco` pode afirmar "N produtos" com o N antigo. |
| 15 | `TipoCarteiraForm`/`Lista` | Invalidam `['tipos-carteira']`, mas o PDV lê `['tipos-carteira-pdv']` — chave de array não casa por prefixo. Janela pequena por causa do `staleTime: 0`. |
| 16 | `FiscalContingenciaPainel` (~:164), `InutilizacaoNumeracao` (~:195) | Justificativa sem `maiusculas()` — a da inutilização vai no XML da SEFAZ. |
| 17 | 5 relatórios | `navigate('/')` no fechar do popup obrigatório, em vez de `navigate(-1)` (que `RelatorioDre` e `FluxoCaixa` usam). Empilha histórico. |
| 18 | `OrcamentoImpressaoModal` (~:79) | `@page orcamento-bobina` é injetado e **nada o referencia** — funciona hoje por acaso (cai no `@page` global de 80mm). |

---

# Áreas que os agentes declararam SEM cobertura

**Backend:** motor tributário (`fiscal/motor/`) — nada visto; `MontadorXmlNfce` (733 linhas) e
`MontadorXmlNfeDevolucao` (499) — campo a campo; assinatura/XSD; `BackupService`; assinatura e
faturamento fora do webhook; fórmulas do Relatório de Estoque e de Contas a Receber; regras de
negócio dos importadores de Cliente/Fornecedor/Produto.

**Frontend:** galeria de imagens do produto (upload/exclusão — onde um erro deixa arquivo órfão no
bucket público); `PesquisaVendas` (hub de reimpressão e cancelamento); `TransferenciaDetalhe`;
`DiferencasEstoque`; `ReimpressaoRecebimentoCrediario`; telas fiscais de configuração; `crm/`;
cadastros em profundidade.
