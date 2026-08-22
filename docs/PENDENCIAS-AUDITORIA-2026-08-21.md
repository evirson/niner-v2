# Pendências da auditoria de 2026-08-21 — aguardando decisão do dono do produto

Dois agentes varreram backend e frontend em **quatro passadas** cada. **24 defeitos foram corrigidos
e commitados** no mesmo dia (ver `docs/PROGRESSO.md`). Este arquivo lista o que **não** foi corrigido
porque exige uma decisão de negócio, não um conserto técnico — e serve para ser cobrado na próxima
sessão.

Ordem: os sete primeiros são os que precisam de decisão; depois vêm as pendências menores (8–18); e
no fim, as que as rodadas 3 e 4 acrescentaram (19–25).

**As quatro que eu levaria primeiro**, por gravidade e por quanto custam se ficarem:

| # | O quê | Por que primeiro |
|---|---|---|
| 19 | Devolução ao fornecedor emite CST do fornecedor numa nota de Simples/MEI | Documento fiscal errado saindo hoje, e **nenhum teste cobre esse caminho** |
| 1 | Cancelar entrada com devolução debita estoque duas vezes | Estoque negativo silencioso + NF-e 55 órfã |
| 5 | Nota presa em `TRANSMITINDO` some da fila do dreno | Prazo legal de 24 h correndo, sem alarme (ver 25) |
| 22 | Parcela legada vira receita com CMV zero no DRE caixa | Lucro que nunca existiu, num relatório de decisão |

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

---

# Acrescentado pelas rodadas 3 e 4 da auditoria

## 19. ⛔ Devolução ao fornecedor copia o CST do FORNECEDOR para uma nota emitida por Simples/MEI

**Onde:** `DevolucaoCompraFiscalAssembler` (~:167) + `MontadorXmlNfeDevolucao` (~:295).

O assembler espelha `cst_icms`/`csosn` de `entrada_nfe_item` — a tributação que **o fornecedor**
declarou — e o montador emite `ICMS00`/`ICMS20` com `pICMS` e `vICMS` destacados. Mas o `<emit>` da
nota leva o **CRT da nossa loja**, que por DF37 é 1, 2 ou 4. O próprio motor tributário declara o
invariante: *"CRT 1/4 emite com CSOSN; só o CRT 2, com excesso de sublimite, pode usar CST"* — e
esta rotina não passa pelo motor, por desenho.

**Cenário:** loja Simples (CRT 1) compra de distribuidor do Lucro Real (CST 00, pICMS 18, vICMS
180) e devolve. Sai uma NF-e com `<CRT>1</CRT>` e `<ICMS00>` destacando ICMS próprio. Na melhor
hipótese a SEFAZ rejeita por incompatibilidade CRT×CST; na pior, autoriza — e a loja emitiu
documento destacando imposto que não apura, gerando crédito indevido ao fornecedor.

⚠️ **Nenhum teste cobre o caminho fiscal desta rotina** (a suíte roda com o fiscal desligado).

**Decisão necessária, com o contador:** converter para CSOSN (102, ou 500 quando a entrada veio com
ST) levando o ICMS original para `infAdic`, ou **recusar** a emissão quando `crt != 2` e o item
original trouxer `cst_icms`? A segunda é o mínimo seguro e é o padrão que o montador já usa para
CSOSN 202/900. **Não apliquei nenhuma das duas**: recusar pode parar uma operação que hoje passa, e
converter é regra tributária.

## 20. cStat 573 no cancelamento: a SEFAZ cancela e o ERP nunca reverte

**Onde:** `CancelamentoNfceService` (~:158).

Corrigido em parte (2026-08-21): o **155** ("cancelamento homologado fora de prazo") passou a
contar como sucesso, e o **573** ganhou mensagem específica mandando o operador usar "Consultar
SEFAZ". **Falta a correção de verdade**: no 573, consultar a nota (`NFeConsultaProtocolo4`, já usado
pelo reprocessamento) e, se ela estiver cancelada, seguir com a reversão.

Sem isso o beco continua: se a resposta do primeiro evento se perde (timeout), toda retentativa
devolve 573 — porque o evento é determinístico (mesmo `Id`, `nSeqEvento` 1) — e a venda nunca é
revertida no ERP com a NFC-e cancelada na SEFAZ.

## 21. FIFO da devolução ao fornecedor recomeça no primeiro `nItem` a cada devolução

**Onde:** `DevolucaoCompraFiscalAssembler` (~:145-151).

A alocação por `numero_item` está correta e tem teto — mas parte sempre da quantidade **original**
do item, sem descontar o que devoluções anteriores já consumiram daquele `nItem` (o saldo é
controlado por variação, nunca por item).

**Cenário:** entrada com a mesma variação em `nItem 1` (5 un a R$ 10) e `nItem 2` (5 un a R$ 12).
Devolução A de 5 un → aloca no `nItem 1`, nota de R$ 50 (correto). Devolução B de 5 un → aloca **no
`nItem 1` de novo**, outra nota de R$ 50. A loja devolveu R$ 110 e declarou R$ 100; o `nItem 2`
nunca é referenciado e a tributação dele nunca é espelhada.

**Decisão:** guardar o `numero_item` alocado por linha de devolução (hoje ele só existe dentro do
XML), ou recusar a segunda devolução parcial quando a variação ocupa mais de um `nItem`.

## 22. Parcela legada já recebida vira receita com CMV zero no DRE regime caixa

**Onde:** `ContasReceberImportador` (~:153-171) × `DreService` (~:281-313).

O importador cria uma `venda` sintética sem movimento de estoque e grava `data_recebimento` das
parcelas pagas **antes** da migração. O DRE em competência as ignora (JOIN INNER com o ledger), mas
o de **caixa** parte de `contas_receber` e as inclui: receita integral com `cmv` e `comissao`
zerados pelo `COALESCE` do LEFT JOIN.

**Cenário:** migração em março com 300 parcelas já recebidas em janeiro (R$ 45.000). O DRE de
janeiro em regime caixa mostra **R$ 45.000 de receita com CMV R$ 0,00** — margem 100%, lucro que
nunca existiu no Nainer.

⚠️ E o próprio importador avisa que *"nenhum lançamento de caixa é criado para parcelas já pagas"* —
então o dinheiro **não** entra no Fluxo de Caixa mas **entra** no DRE caixa. Dois relatórios
financeiros, o mesmo fato, respostas opostas.

**Decisão:** marcar a venda sintética (coluna de origem em `venda`) e excluí-la do DRE, ou não
importar `data_recebimento` de parcelas anteriores à migração? A primeira preserva o Relatório de
Contas a Receber, onde essas parcelas **devem** aparecer.

## 23. `CobrancaService.iniciarPagamento` chama o Mercado Pago dentro da transação

**Onde:** `CobrancaService` (~:48 e :105) — viola o F2 ("nenhuma chamada de rede dentro de transação
de banco") que o módulo fiscal inteiro respeita.

Se a transação der rollback **depois** de o PIX ser criado, ele existe no gateway apontando para uma
fatura inexistente: o cliente paga, o webhook não acha nada, os dois `UPDATE` casam zero linhas e
sobra um `log.warn`. **Dinheiro recebido, assinatura não promovida.** Probabilidade baixa (os
comandos seguintes são simples), custo alto.

## 24. `exigirSeObrigatorio` só existe para `String`: 7 campos configuráveis sem revalidação no servidor

**Onde:** `ConfiguracaoTelaService` + os `validar()` de Cliente/Funcionário/Produto.

As duas listas de `cfg_tela_campo` (front e servidor) **batem 100% em nomes** — o buraco é de tipo:
`exigirSeObrigatorio(Map, String, String)` só tem sobrecarga para texto, então todo campo
configurável que é número ou data fica de fora por construção: `limiteCredito`, `percComissao`,
`pesoBruto`, `pesoLiquido`, `precoOferta`, `dataInicioOferta`, `dataFinalOferta`.

Para 6 deles o formulário cobre, então o usuário da tela está protegido — mas o `CLAUDE.md` afirma
que a bandeira é aplicada *"again on the server"*, e para estes não é: uma integração pela API grava
sem eles.

⚠️ **Ressalva de contrato:** para `limiteCredito` e `precoOferta` o front envia
`desmascararMoeda('')` = **0**, então o servidor não distingue "vazio" de "zero legítimo" — nesses
dois o contrato precisaria passar a mandar `null`, o que é decisão de produto. Os cinco restantes
são mecânicos.

## 25. Não há alarme para nota presa em transmissão

**Onde:** `ConformidadeFiscalDtos.CategoriaConformidade` (só `EMPRESA/PRODUTOS/PAGAMENTOS/CLIENTES`).

Corrigido em parte (2026-08-21): `TRANSMITINDO`/`ASSINADO` deixaram o cinza neutro e passaram a
`badge-perigo` na lista fiscal. **Falta o alarme**: o painel de Conformidade pode exibir
*"✓ pronto para emitir"* com notas paradas há dias, e para achar uma o operador precisa **já
suspeitar que ela existe** (abrir a lista, escolher empresa, acertar o período e filtrar a
situação). Precisa de um endpoint que conte documentos parados por empresa — a lista atual é
paginada por período e não serve de contador. Casa com a pendência 5 (dreno).
