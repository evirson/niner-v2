# Spec: Devolução de Produtos                              Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-03 · Módulo(s): `vendas` (devolução) · Fase: 2 — Vendas/Financeiro

## Problema

Não existia nenhuma forma de dar entrada de volta no estoque de produtos que o cliente devolve
(sem ser uma troca completa nem o cancelamento da venda inteira). O PDV reservava o atalho **F5
"Devolver Produto"** desde 2026-07-28 sem nenhuma lógica; o schema já antecipava a feature: a
tabela `venda_devolucao` existe desde a migration original de vendas (`V018__vendas.sql`) e o
enum `tipo_movimento` já tinha o valor `DEVOLUCAO` (`V013__dominio_tipos_enum.sql`) — nenhum dos
dois era usado por código Java. O Relatório de Vendas já calculava "Valor Devolução"/"%
Devolução" esperando esse dado (`RelatorioVendasService.buscarValorDevolucao`).

## Solução implementada

Tela nova (`/devolucao-produto`, menu "Frente de Loja", ADMIN e OPERADOR) que:

1. Pede o **número da venda** (opcional ou obrigatório, conforme
   `cfg_geral.cfg_exige_numero_venda_devolucao` — ver "Restrição a produtos vendidos" abaixo) —
   ao sair do campo, busca automaticamente o vendedor daquela venda (RN-01) **e**, desde
   2026-08-11, os itens que ela vendeu, restringindo o que pode ser devolvido.
2. Deixa digitar/ler **código de barras** repetidamente, empilhando uma grid de itens (mesmo
   padrão de leitura do PDV/Transferência de Estoque — `lib/pdv.ts:interpretarCodigoBarras` +
   `buscarProdutoPorCodigo`, mesmo `PesquisaProdutoModal`), cada linha com produto + quantidade
   editável.
3. O botão **"Gravar Devolução"** efetiva tudo numa transação: devolve a quantidade de cada item
   ao estoque **e emite um vale-mercadoria**, exibido num comprovante para impressão imediata.

## Decisões de escopo (fechadas)

**Vínculo com a venda original: persistido desde o início (`venda_devolucao.id_venda_credito`,
ver "Vale-mercadoria" abaixo), mas só passou a *restringir* a devolução em 2026-08-11.** Até
então o número era só um dado auxiliar pra resolver o vendedor (RN-01) — o operador podia
devolver qualquer código de barras, de qualquer venda ou nenhuma, em qualquer quantidade
(equivalente a uma entrada de estoque manual rotulada "devolução"). Pedido do dono do produto:
"só pode devolver produtos que foram vendidos" — ver "Restrição a produtos vendidos" abaixo pro
desenho completo (o que mudou, o que continuou igual, e a configuração que liga/desliga a
obrigatoriedade do campo).

**Comissão: só o dado fica pronto, nenhum cálculo existe ainda.** `funcionario.perc_comissao` é
puramente cadastral em todo o sistema — nenhuma venda "credita" comissão de verdade hoje. A
devolução grava o vendedor resolvido em `produto_movimento_detalhe.id_funcionario` de cada linha
(mesmo campo que a venda já usa), para que um futuro relatório de comissão calcule `perc_comissao
× (Σ vendas do vendedor − Σ devoluções do vendedor no período)` sem precisar de tabela nova.
Nenhum cálculo de comissão é feito por esta feature.

**Toda devolução gera um vale-mercadoria** (revisão de escopo, 2026-08-03 — a primeira versão
deste documento previa "sem efeito financeiro"; o dono do produto pediu geração de vale como
parte central da feature). Ver "Vale-mercadoria" abaixo. Não é opcional por devolução — sempre
acontece.

**Permissão: ADMIN e OPERADOR.** Diferente do Cancelamento de Venda (ADMIN-only, reverte uma
venda inteira com caixa/parcelas), a Devolução de Produtos mexe só em estoque + emite um vale —
mesmo nível de acesso de Transferência de Estoque.

## Vale-mercadoria

Reaproveita a tabela `venda_devolucao` (existente desde `V018`, nunca usada até esta feature):

- `id_devolucao` (a própria PK) **é o número do vale**, impresso no comprovante.
- `id_venda_credito` grava o número da venda opcional informado na tela (redefinição desta
  coluna — o design original de `V018` previa um cenário de troca, não usado aqui).
- **O valor do vale nunca é gravado como coluna** — é sempre derivado somando os itens do
  movimento `DEVOLUCAO` vinculado (`produto_movimento_mestre.id_devolucao`, FK que já existia
  em `V019`, até então nunca preenchida).
- `vale_usado`/`id_venda_debito` (colunas que já existiam) são preenchidos quando o vale é
  resgatado numa venda futura (ver "Resgate no PDV" abaixo) — `false`/`NULL` enquanto não usado.

Ao gravar a devolução, um popup automático (`ComprovanteValeModal.tsx`) mostra o número e o
valor do vale, com "Salvar PDF", "Imprimir" e "Enviar por WhatsApp". Até 2026-08-07 usava um
layout próprio de 42 colunas (fonte Courier, `.comprovante-preview`), mesmo padrão do
Comprovante de Recebimento de Crediário; **desde 2026-08-07** foi padronizado com o layout da
Papeleta de Venda (`.papeleta-preview`/`.papeleta-imprimir`, `ComprovantePapeletaModal.tsx`) —
pedido do dono do produto pra uniformizar a impressão dos itens entre os dois comprovantes que
saem na mesma bobina térmica física. Esse layout compartilhado **mudou em 2026-08-14**: deixou de
ser 64 colunas numa linha por item (fonte Lucida Console) e passou a **42 colunas com o item em 2
linhas** (Consolas em negrito), porque a versão anterior saía ilegível na bobina real. O vale
acompanhou automaticamente **no texto e na impressão** (usa as mesmas funções de montagem de
linhas), e foi conferido impresso na mesma data, aprovado sem ajuste próprio — o que valida na
prática a decisão de 2026-08-07 de padronizar os dois comprovantes: consertar a papeleta
consertou o vale junto. Ver `docs/telas/papeleta-venda.md`.

✅ **Pendência resolvida em 2026-08-14 — o PDF do vale voltou à mesma calibragem.** A padronização
de 2026-08-14 tinha valido só para as linhas de texto, **não** para o documento jsPDF:
`montarDocumentoComprovanteVale` ficou para trás em **5pt / `alturaLinha` 2,6mm** enquanto a
papeleta e o comprovante de crediário subiram para **8pt / 3,6mm**, e o docstring da função ainda
afirmava "mesma largura/fonte" — os números diziam outra coisa. Efeito: o vale salvo em PDF (e o
enviado por WhatsApp, que usa o mesmo Blob) saía visivelmente menor que os outros dois
comprovantes; a impressão térmica não era afetada (passa pelo CSS, não pelo jsPDF), e por isso o
problema não apareceu no teste impresso. Corrigido: as duas constantes foram para **8pt / 3,6mm**
(`web/src/lib/comprovante.ts:419-420`), idênticas às da papeleta (`:334-335`) e às do comprovante
de crediário (`:128-129`) — os três comprovantes térmicos do sistema estão agora de fato iguais,
PDF incluído. O docstring da função (`:408-416`) registra o histórico.
"Enviar por WhatsApp" reaproveita o mesmo mecanismo da Papeleta de Venda/Comprovante de
Crediário (`comum.arquivocompartilhado`, ver `docs/infra/compartilhamento-arquivo-temporario.md`)
— como `venda_devolucao` não tem vínculo com cliente (devolução é anônima), não há telefone pra
pré-preencher, o operador digita na hora. Backend: `ItemDevolucaoResponse` ganhou `sku` e
`valorTotal` (mesmas colunas de `ItemComprovanteVenda`, PDV) pra reaproveitar a mesma tabela de
itens da papeleta em vez de duplicar a montagem do layout.

**Bug corrigido (2026-08-11):** o "Salvar PDF" do vale saía com a largura errada (colunas da
direita cortadas) sempre que a devolução tinha poucos itens — causa raiz e correção (piso de 80mm
na altura calculada, jsPDF inverte largura/altura em orientação retrato quando `largura > altura`)
documentados em `docs/telas/papeleta-venda.md` (seção "Bug corrigido"), já que a mesma função
(`comprovante.ts`) gera os três comprovantes térmicos do sistema.

### Resgate no PDV

Em vez de um mecanismo de pagamento novo, o resgate usa o tipo de carteira **"VALE MERCADORIA"**,
que já era seedado em todo tenant desde o signup. Só a **categoria** dela mudou: de `AVISTA`
para um novo valor do enum `categoria_carteira`, **`VALE_MERCADORIA`** (`V025`, editado direto —
banco em construção). Consequência: "VALE PRESENTE" (outro rótulo seedado desde sempre, também
`AVISTA`, sem nenhuma lógica) foi **removido do seed** — conviver ao lado do vale de verdade
ficaria confuso.

No split-tender do PDV, a categoria "Vale-Mercadoria" pede o **número do vale**, não um valor
digitado — o servidor busca o valor de verdade e ignora qualquer valor mandado pelo cliente
(mesmo princípio de todo o PDV: preço nunca vem do front). Paga na hora, como À Vista. Bloqueios:
vale já usado → 409; vale maior que o saldo a pagar → 400 (decisão do dono do produto: vale é
sempre consumido por inteiro — "sem troco em vale" — por isso é bloqueado em vez de aceitar e
perder a diferença; o schema só guarda usado/não usado, nunca saldo remanescente). Ao efetivar a
venda, marca `vale_usado = true` + `id_venda_debito` **atomicamente** (`UPDATE ... WHERE
vale_usado = false`, trava otimista contra resgate concorrente do mesmo vale em duas vendas
simultâneas). Como "VALE MERCADORIA" já é uma carteira normal, o **Fechamento de Caixa já
totaliza sozinho** — nenhuma mudança lá.

### Cancelamento de Venda reabre o vale

Se a venda que resgatou um vale for cancelada (`CancelamentoVendaService`), o vale volta a valer
(`vale_usado = false`, `id_venda_debito = NULL`), dentro da mesma transação do cancelamento —
senão o cliente perderia o crédito de um vale cuja venda de resgate foi desfeita. Sem rastro de
que já tinha sido usado uma vez, mesma filosofia da exclusão física já usada ali para
`caixa_detalhe`/`contas_receber`.

### Cancelamento da própria devolução (2026-08-11)

Ver `docs/telas/cancelamento-devolucao-produtos.md` — desfaz a devolução em si (não a venda que a
resgatou), só permitido enquanto o vale ainda não foi usado (`vale_usado = false`). `venda_devolucao`
ganhou `cancelada/data_cancelamento/id_usuario_cancelamento/motivo_cancelamento`; `buscarVale()`
(usada aqui e pelo PDV) e `PdvVendaService.resolverVale()` passaram a checar `cancelada` também,
não só `vale_usado` — um vale cancelado nunca é resgatável.

## Restrição a produtos vendidos (2026-08-11)

Pedido direto do dono do produto: "se entrar com o número da venda, já pega o vendedor
automaticamente, e só pode devolver produtos que foram vendidos". Duas mudanças de comportamento
distintas, implementadas juntas:

### Busca automática do vendedor (sem botão)

O botão "Buscar Vendedor" foi removido — a busca dispara sozinha ao sair do campo (`onBlur`) ou
no Enter (`aoDigitarNumeroVenda`, já existia). `GET /vendas/devolucao/vendedor` passou a
responder também a lista de itens vendidos naquela venda (`itens: ItemVendaOrigemResponse[]`,
com `qtdVendida` e `qtdDisponivelDevolucao` por item), reaproveitando a mesma chamada que já
buscava o vendedor — um único request alimenta as duas mudanças.

### Restrição de itens (`RN-06`)

Quando o número da venda é informado (e resolve com sucesso), a tela só aceita lançar produtos
presentes nos itens daquela venda, até `qtdDisponivelDevolucao` (= quantidade vendida menos o
que já foi devolvido em devoluções **não canceladas** da mesma venda — uma devolução cancelada
não conta contra o limite, já que o cancelamento reverteu o estoque, ver
`docs/telas/cancelamento-devolucao-produtos.md`). Mecânica escolhida (via `AskUserQuestion`):
**código de barras + bloqueio**, não uma lista de itens pra escolher — mantém a leitura livre já
existente, só rejeita com uma mensagem clara (toast) o que não pertence à venda ou excede o
disponível, tanto na leitura por código de barras quanto na Pesquisa de Produto. Um item já
lançado na grade que deixar de ser válido (ex.: o operador trocou o número da venda depois de já
ter lançado itens) mostra o erro na própria linha e bloqueia "Gravar Devolução" até ser
corrigido/removido — não é apagado silenciosamente.

**Validado no servidor, não só na tela (P4).** `DevolucaoProdutoService.efetivar()` recalcula a
mesma disponibilidade e rejeita com 400 se algum item não pertence à venda ou excede o
disponível — a tela é só uma conveniência de UX, a regra de verdade vive no backend. Sem o
número da venda informado, nenhuma restrição se aplica (comportamento livre de sempre).

### Número da venda obrigatório ou opcional, por configuração

Novo parâmetro em Parâmetros do Sistema (`configuracao.geral`,
`cfg_geral.cfg_exige_numero_venda_devolucao`, default `false` — ver
`docs/telas/configuracao-geral.md`):
quando ligado, o campo "Número da Venda" vira obrigatório (rótulo `*`, mensagem de erro inline,
"Gravar Devolução" bloqueado sem ele) — decisão do dono do produto (via `AskUserQuestion`) de
manter o campo **opcional por padrão**, restringindo só quando preenchido; o parâmetro existe
pra quem quiser forçar a obrigatoriedade em todo o tenant. Validado também no servidor
(`efetivar()` rejeita com 400 se a flag está ligada e `numeroVenda` não veio).

### Foco inicial do campo depende da configuração

Ao abrir a tela (ou depois de gravar uma devolução, quando o formulário volta ao estado
inicial): se `cfg_exige_numero_venda_devolucao` está ligada, o foco vai direto pro campo "Número
da Venda" (o operador precisa preenchê-lo antes de tudo); desligada, o foco continua indo pro
campo "Código de Barras", como sempre foi. **Bug corrigido no mesmo dia:** o efeito de foco
inicial rodava assim que a query da configuração retornava *qualquer* valor, inclusive um valor
em cache desatualizado (React Query, `staleTime` padrão 0 — uma tela já visitada nesta sessão do
navegador serve o cache na hora enquanto busca uma versão fresca por trás, e a navegação do
sistema é toda client-side, sem recarregar a página). Corrigido esperando `isFetching` virar
`false` antes de decidir o foco, e invalidando o cache das 4 flags leves de `cfg_geral`
(`ConfiguracaoGeralForm.tsx`, `onSuccess` do salvar) pra qualquer tela já aberta reagir na hora.
Detalhe técnico completo: [[feedback_react_query_cache_entre_telas]] (memória).

## Revisão 2026-08-19 — grid de seleção por venda + NF-e de devolução

Fecha a pergunta **DF20** (`docs/MODULOFISCAL.md` §10.2, "a pergunta mais importante do v1, e
ainda sem resposta") e o item **B9** do roteiro fiscal (NF-e de devolução), que dependiam dela.

> **Estado (2026-08-19):** a **grid de seleção por venda está implementada e testada ao vivo**; a
> **NF-e de devolução emite ponta a ponta** (assembler → montador → assinatura → transporte →
> persistência), coberta por teste de integração com certificado real. Falta o **DANFE A4** (o
> documento impresso) e o teste ao vivo contra a SEFAZ de homologação.

### Dois modos de operação, decididos pelo mesmo parâmetro que já existe

`cfg_geral.cfg_exige_numero_venda_devolucao` passa a decidir **o mecanismo de lançamento inteiro**,
não só se o campo é obrigatório:

**Ligado — grid de seleção (substitui a leitura por código de barras neste modo):**
1. Ao entrar na tela, popup obrigatório pede o **Número da Venda** (mesmo padrão "popup
   obrigatório ao entrar" já usado em Pesquisa de Vendas/Cancelamento de Devolução).
2. Resolvida a venda, o popup mostra uma grid com os itens vendidos: **Código de Barras |
   Descrição do Produto + Cor + Tamanho | Qtd. Vendida | Preço Unitário | Preço Total**, e um
   botão **"Selecionar"** por linha + **"Selecionar Todos"** no topo. Dado real: o endpoint
   `GET /vendas/devolucao/vendedor` já devolve `qtdVendida`/`qtdDisponivelDevolucao` por item
   (RN-06, 2026-08-11) — só faltam `precoUnitario`/`valorTotal` (já existem em
   `produto_movimento_detalhe`, é só expor).
3. **Decidido:** clicar "Selecionar" adiciona a linha na grid final (a mesma "Itens a Devolver"
   de sempre) com a **quantidade total ainda disponível** (`qtdDisponivelDevolucao`) — devolução
   parcial de um item se ajusta **depois**, na grid final, com o campo de quantidade que já existe
   hoje (não um campo novo dentro do popup). "Selecionar Todos" faz isso pra cada linha de uma vez.
4. Popup fecha ("Confirmar"), tela segue com a grid final de sempre (revisar quantidade, remover
   linha, "Gravar Devolução") — RN-02/RN-06 continuam valendo, só a **forma de lançar** mudou.

**Desligado — leitura livre de código de barras (comportamento atual, sem mudança):** campo
"Número da Venda" opcional e inline (não popup), leitura de código de barras sempre disponível.
Se o operador preencher o número mesmo assim, a resolução de vendedor/itens e a restrição RN-06
continuam se aplicando exatamente como hoje — só não aparece a grid de seleção.

### Integração fiscal — NF-e de devolução (modelo 55, entrada)

Regra decidida, independente do parâmetro acima (depende só de **ter ou não um número de venda
resolvido com NFC-e autorizada**):

| Situação | Nota fiscal? |
|---|---|
| Número da venda informado, e ela tem NFC-e `AUTORIZADO`/`CONTINGENCIA` | **Sim** — NF-e modelo 55, tipo entrada, referenciando a NFC-e original |
| Número da venda informado, mas sem NFC-e (F12 desligado, ou nota rejeitada/não emitida) | **Não** — só vale-mercadoria, como hoje |
| **Sem número de venda nenhum** | **Não** — mesmo tratamento do caso acima. Decidido via `AskUserQuestion`: sem nenhum documento de origem pra referenciar, uma "nota de devolução" deixa de ter base fiscal (seria juridicamente outra coisa — aquisição de terceiro não-contribuinte, fora de escopo). Revisitar só se/quando confirmado com o contador que há uma base segura. |

**⚠️ Achado de pesquisa que precisa de confirmação do contador antes de ir pra produção:** existe
uma mudança regulatória recente (**Ajuste SINIEF nº 8/2026**, em vigor desde 2026-05/08) que
reestrutura como devolução deve ser referenciada — distinguindo devolução **total** (`refNFe`,
referência simples pela chave) de **parcial** (grupo `DFeReferenciado`, item a item). As fontes
consultadas não deixam claro se isso já vale pro consumidor final de balcão (NFC-e) ou só entre
contribuintes (B2B) — não é algo pra eu decidir sozinho. Enquanto não confirmado, o padrão
histórico documentado (CFOP 1.202 interno/2.202 interestadual, `dest` = próprio CNPJ da loja
quando o consumidor original não foi identificado) é o ponto de partida, mas a estrutura exata de
referência por item precisa ser revisada à luz dessa mudança antes de codar o montador do XML.

### O que o schema já tem pronto (achado bom — B9 é mais barato do que parecia)

Toda a base de dados pro modelo 55 **já existe**, semeada desde o B4/B6 sem nunca ter sido usada:
- `cfg_cfop` já tem **1202**/**2202** ("Devolucao de venda de mercadoria adquirida ou recebida de
  terceiros", interno/interestadual) semeados desde a V034.
- `fiscal_numeracao` já aceita `modelo IN (55, 65)` — numeração do modelo 55 não precisa de
  schema novo.
- `fiscal_config_empresa` já tem `serie_nfe`/`emite_nfe` (só nunca usados) — falta só a tela de
  Configuração Fiscal ligar `emite_nfe` (hoje só emitir NFC-e é operável).
- `documento_fiscal_referencia` já existe **exatamente** pra isso: "a devolução referencia a
  NFC-e original" está no comentário da tabela desde que foi criada (V035).
- O transporte (`SefazTransporte`), a assinatura (`AssinadorXmlNfe`) e os 243 XSD oficiais já são
  agnósticos de modelo — o que falta é só o **montador do XML** (um `MontadorXmlNfeDevolucao`,
  espelhando os valores tributários já gravados em `documento_fiscal_item` da nota original, sem
  reprocessar pelo motor tributário) e o **DANFE A4** (hoje só existe o DANFCE térmico, 80mm).

### Preço da devolução passou a ser o da VENDA, não o do cadastro (2026-08-19)

Achado ao expor `precoUnitario` na grid: `DevolucaoProdutoService` usava sempre
`produto.preco_venda` (o preço de **hoje**) para gravar o movimento e derivar o valor do vale — se
o preço mudou desde a venda, o cliente recebia um vale de valor diferente do que pagou. Corrigido:
quando há venda de origem, o preço (e o **custo**, que alimenta o CMV do DRE) vêm da média
ponderada de `produto_movimento_detalhe` daquela venda; sem venda de origem, cai no cadastro, como
sempre. Mesmo princípio que `CancelamentoVendaService.estornarEstoque` já documentava para o custo.

É pré-requisito da NF-e de devolução: a nota de entrada tem que espelhar os valores da nota de
saída que ela referencia. Coberto por dois testes que formam controle negativo entre si —
`devolucaoUsaOPrecoDaVendaOriginalMesmoDepoisDeOPrecoDoCadastroMudar` (espera o preço da venda) e
`devolucaoSemNumeroDeVendaUsaOPrecoAtualDoCadastro` (espera o do cadastro), ambos com o cadastro
alterado depois da venda.

### Três armadilhas do XSD que só a validação contra o schema revelou (2026-08-19)

O montador (`MontadorXmlNfeDevolucao`) valida contra o XSD oficial em teste, e o schema recusou
três coisas que a leitura do MOC não anteciparia:

| O que parecia | O que o schema exige |
|---|---|
| `NFref` logo após `cMunFG` (ordem "lógica" do cabeçalho) | `NFref` é o **último** elemento do `ide`, depois de `verProc`/`dhCont`/`xJust` |
| `DFeReferenciado` dentro de `prod` (o nome sugere "referência do produto") | É filho de **`det`**, depois de `imposto` — descreve o **item**, não o produto |
| `vPag` omitido quando `tPag=90`, como a **anotação** do XSD diz textualmente | O **schema** declara `vPag` obrigatório; quem valida é o schema. Vai `0.00` |

A terceira é a mais traiçoeira: a documentação do próprio XSD contradiz o schema do próprio XSD.
Está fixada como asserção de teste para o par não voltar a divergir em silêncio.

### 🔴 Gap achado e corrigido: `documento_fiscal_item` nunca era gravada (2026-08-19)

Construir a devolução expôs um buraco pré-existente do módulo fiscal: a tabela
`documento_fiscal_item` existe desde a V035 e **nunca havia recebido um único `INSERT`** — o
ambiente de dev tinha **43 documentos fiscais, 27 autorizados e zero itens**. O dado nunca esteve
perdido (o `xml_assinado` sempre guardou a nota inteira), mas viver só dentro do XML o torna
inacessível a qualquer consulta — e a NF-e de devolução precisa **espelhar a tributação item a
item** da nota original, o que exige justamente essa tabela.

Corrigido na origem: `DocumentoFiscalRepositorio.gravarAssinado` passou a gravar um item por
linha, decompondo o que o motor calculou (ICMS/PIS/COFINS/IBS-CBS, CFOP, NCM, origem, valores).
A FK `id_variacao` é resolvida por subquery a partir do SKU — `ItemNota` é o contrato do montador
de XML e não carrega chave interna, e poluí-lo só por causa dessa coluna não se justificava.

⚠️ **Notas autorizadas antes desta data seguem sem itens** — não há como reconstruí-las sem
parsear o XML. Na prática são todas de homologação/teste; em produção o efeito é nulo, porque o
fiscal ainda não foi ligado para nenhum cliente real. A devolução fiscal dessas notas é recusada
explicitamente pelo assembler, com mensagem que explica o motivo, em vez de gerar nota incompleta.

### Endpoints de NF-e 55 do Paraná — pendência da F0 fechada (2026-08-19)

`cfg_uf_autorizador` tinha as linhas do modelo 55 do PR com **URL nula de propósito** ("falhar
explicitamente em vez de chutar domínio", V034). Sem elas o B9 não teria para onde transmitir.
Confirmado no portal oficial Sped-PR (a mesma fonte que validou as da NFC-e): o PR tem autorizador
**próprio** também para NF-e 55, em `nfe.sefa.pr.gov.br/nfe/` — **não usa SVRS**, ao contrário do
que a primeira pesquisa sugeria. Preenchidas na V034 e aplicadas no banco de dev.
`SefazAutorizadorCrudTest` deixou de documentar a pendência e passou a documentar a resolução,
inclusive verificando que o host é `nfe.sefa` e não `nfce.sefa` (troca fácil, que só apareceria na
primeira transmissão real).

### Falha na nota NÃO desfaz a devolução (F3) — diferente do Cancelamento de Venda

Decisão de desenho que vale registrar porque contraria a analogia mais óbvia. No **Cancelamento de
Venda**, a nota tem que ser cancelada na SEFAZ **antes** de reverter qualquer coisa — se a
reversão acontecesse primeiro, a nota ficaria válida sem a venda que a originou ("estoque/caixa e
fiscal nunca divergem", §10.1).

Na **devolução é o contrário**: a mercadoria já voltou fisicamente ao balcão e o vale-mercadoria
já é do cliente. Bloquear a devolução porque a SEFAZ está fora travaria a loja por um motivo que
não é da loja — exatamente o que o **F3** ("fiscal nunca bloqueia a operação de balcão") existe
para evitar, e o mesmo tratamento que a NFC-e da venda já recebe. Uma rejeição registra o
documento com a situação real em Documentos Fiscais, para reprocessar, e a resposta da API traz
`notaFiscal.situacao` para a tela avisar o operador.

A orquestração fica no **controller**, não no serviço: `efetivar` é `@Transactional` e a emissão
faz I/O de rede (até 10 s) — chamá-la de dentro prenderia conexão e travas pelo tempo da SEFAZ,
violando o **F2**. O controller não é transacional, então a devolução já está commitada quando a
emissão começa.

### Ainda em aberto

- Se a venda referenciada ainda está dentro dos 30 min de cancelamento da NFC-e, vale sugerir
  "cancele a venda direto" em vez de devolução+nota nova? (Cancelamento desfaz tudo; devolução
  pode ser parcial — talvez os dois caminhos devam conviver, sem um substituir o outro.)
- DANFE A4 — layout de referência recebido do dono do produto (`c:\fix\danfe_55.pdf`, NF-e real de
  outro emissor): canhoto destacável, cabeçalho em 3 colunas (emitente / DANFE+tipo / chave de
  acesso), natureza da operação + protocolo, destinatário, fatura/duplicata, cálculo do imposto em
  duas faixas de caixas, transportador, tabela de produtos (código, descrição, NCM, O/CST, CFOP,
  un, qtd, unitário, total, BC ICMS, valor ICMS, alíquotas) e dados adicionais. Ainda não
  implementado — hoje só existe o DANFCE térmico de 80mm.

## User stories

- Como operador de caixa, quero opcionalmente informar o número de uma venda pra que o sistema
  já saiba qual vendedor atender, sem precisar procurar isso manualmente.
- Como operador de caixa, quero ler o código de barras de cada produto devolvido e ver uma grid
  se formando, igual já faço no PDV, até terminar de separar tudo que o cliente está devolvendo.
- Como operador de caixa, quero gravar a devolução e já sair com um vale-mercadoria impresso
  pra entregar ao cliente.
- Como operador de caixa, quero que o cliente possa usar esse vale numa compra futura,
  escolhendo "Vale-Mercadoria" como forma de pagamento e digitando o número do vale.
- Como ADMIN, se eu cancelar uma venda que usou um vale, quero que esse vale volte a valer, sem
  o cliente perder o crédito.

## Regras de negócio

### RN-01 — Identificação automática do vendedor via número da venda

Se informado (obrigatório ou não, ver RN-06), resolve o vendedor buscando
`produto_movimento_detalhe.id_funcionario` dos itens de `produto_movimento_mestre` com
`tipo_movimento = 'VENDA'` e `id_venda` = informado (mesmo dado que o PDV grava — um vendedor
por venda, não por item). Dispara sozinho ao sair do campo ou no Enter, sem botão (2026-08-11).
Se a venda não existir (ou for de outro tenant — RLS), retorna 404 no lookup (`GET /vendedor`);
no `POST` de efetivação, se não resolver, a devolução segue sem vendedor (não bloqueia).

### RN-02 — Grid de leitura de código de barras

Reaproveita `web/src/lib/pdv.ts` (`interpretarCodigoBarras` — inclusive a sintaxe `qtd*código` —
e `buscarProdutoPorCodigo`) e `PesquisaProdutoModal.tsx`. Grid em tabela simples (`table
table-compacta`, estilo `TransferenciaForm.tsx`).

### RN-03 — Efetivação gera estoque + vale

Dentro de uma única transação: `venda_devolucao` (vale) → `produto_movimento_mestre`
(`tipo_movimento = 'DEVOLUCAO'`, `id_devolucao` apontando pro vale) → um
`produto_movimento_detalhe` (`credito_debito = 'C'`) por linha, com `id_funcionario` = vendedor
resolvido em RN-01. A trigger `fn_atualiza_estoque_movimento` soma a quantidade de volta em
`produto_estoque` sozinha.

### RN-04 — Resgate do vale (`categoria_carteira = VALE_MERCADORIA`)

Ver "Resgate no PDV" acima.

### RN-05 — Reabertura do vale no cancelamento

Ver "Cancelamento de Venda reabre o vale" acima.

### RN-06 — Restrição a produtos vendidos (2026-08-11)

Ver "Restrição a produtos vendidos" acima. Resumo: com o número da venda informado, só é
permitido lançar itens presentes nela, até a quantidade ainda não devolvida
(`qtdVendida − qtdDevolvidaEmDevoluçõesNãoCanceladas`); validado no servidor
(`DevolucaoProdutoService.efetivar`, 400) e replicado na tela pra feedback imediato. Sem número
de venda, sem restrição. `cfg_geral.cfg_exige_numero_venda_devolucao` controla se o campo é
obrigatório.

## Contrato de API

```
GET  /api/v1/vendas/devolucao/vendedor?numeroVenda=123    → { numeroVenda, idFuncionario, nomeFuncionario, itens: [{ idVariacao, sku, descricaoProduto, variacaoCor, variacaoTamanho, qtdVendida, qtdDisponivelDevolucao }] } | 404
GET  /api/v1/vendas/devolucao/vale/{idDevolucao}           → { idDevolucao, valorVale, valeUsado, cancelada, dataDevolucao, idVendaCredito, idVendaDebito }
GET  /api/v1/pdv/produtos/codigo/{codigo}                  → reaproveita o endpoint já existente do PDV
GET  /api/v1/config-geral/exige-numero-venda-devolucao     → { cfgExigeNumeroVendaDevolucao } (qualquer papel)
POST /api/v1/vendas/devolucao
     { numeroVenda?: number, itens: [{ idVariacao, qtd }] }
     → { idMovimento, idDevolucao, valorVale, dataMovimento, idFuncionario, nomeFuncionario, itens }
```

`ValeMercadoriaResponse` devolve o próprio `idDevolucao` (é o número do vale, o que o operador
digita no PDV) e o par de flags que decide se o vale ainda vale: **`valeUsado`** (já resgatado
numa venda) e **`cancelada`** — este último é o campo que barra um vale já cancelado pelo
Cancelamento de Devolução de Produtos (`docs/telas/cancelamento-devolucao-produtos.md`).

`POST /api/v1/pdv/vendas` (`PdvVendaService`) ganhou o campo opcional `idDevolucao` em cada linha
de `pagamentos[]` — obrigatório só quando `idCarteira` aponta pra uma carteira de categoria
`VALE_MERCADORIA`. Erros em Problem Details: 400 (grid vazia, quantidade inválida, número do
vale ausente numa linha `VALE_MERCADORIA`, vale maior que o saldo a pagar, **produto fora da
venda informada, quantidade maior que a disponível na venda, ou número da venda ausente quando
`cfg_exige_numero_venda_devolucao` está ligada** — 2026-08-11), 404 (venda/variação/vale
inexistente ou de outro tenant), 409 (vale já usado).

## Critérios de aceitação

- Dado um número de venda existente, quando informado, então o vendedor daquela venda é
  resolvido e exibido automaticamente (sem clique em botão).
- Dado nenhum número de venda informado (quando o campo não é obrigatório, ver RN-06), quando a
  devolução é gravada, então segue sem vendedor associado e sem restrição de itens.
- Dado um código de barras lido N vezes (ou `N*código`), quando a grid é montada, então soma a
  quantidade na mesma linha em vez de duplicar.
- Dado uma grid com itens, quando "Gravar Devolução" é confirmado, então cada item volta ao
  estoque na empresa correta, um `produto_movimento_mestre` com `tipo_movimento='DEVOLUCAO'`
  fica registrado, e um vale-mercadoria é emitido com o valor correto.
- Dado um vale emitido, quando usado como pagamento `VALE_MERCADORIA` numa venda cujo saldo a
  pagar é maior ou igual ao valor do vale, então a venda é efetivada, o vale é marcado como
  usado e vinculado à venda, e o Fechamento de Caixa totaliza esse valor na carteira.
- Dado um vale já usado, quando usado novamente, então 409.
- Dado um vale maior que o saldo a pagar de uma venda, quando usado, então 400.
- Dado um pagamento `VALE_MERCADORIA` sem número de vale informado, então 400.
- Dado uma venda que usou um vale, quando cancelada (Cancelamento de Venda), então o vale volta
  a `vale_usado = false`/`id_venda_debito = NULL` e pode ser usado numa venda futura.
- Dado um tenant, então nunca enxerga nem afeta estoque/venda/vale de outro tenant (RLS, P8).
- Dado um número de venda informado, quando o campo perde o foco (ou Enter), então o vendedor e
  os itens vendidos são buscados automaticamente, sem clique em nenhum botão.
- Dado um número de venda informado e resolvido, quando um produto que não faz parte dela é lido
  ou pesquisado, então é rejeitado (tela) e, se enviado direto pro `POST`, rejeitado com 400.
- Dado um número de venda informado, quando a quantidade lançada de um item excede o que ainda
  não foi devolvido dela, então é rejeitada (tela e servidor).
- Dado um item já devolvido (não cancelado) de uma venda, quando a mesma venda é consultada de
  novo, então `qtdDisponivelDevolucao` reflete o desconto; uma devolução cancelada não desconta.
- Dado `cfg_exige_numero_venda_devolucao` ligada, quando a devolução é gravada sem número de
  venda, então 400.

Cobertos por `DevolucaoProdutoCrudTest` (10 testes) e `ValeMercadoriaCrudTest` (6 testes,
incluindo o cancelamento reabrindo o vale). Suíte completa do projeto: **500/500 verdes
(2026-08-14)**.

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `vendas.devolucaoproduto.form`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

- `categoria_carteira` (ENUM, `V025`) ganhou o valor `VALE_MERCADORIA`.
- `SignupService` (seed por tenant): a carteira "VALE MERCADORIA" nasce com categoria
  `VALE_MERCADORIA` em vez de `AVISTA`; "VALE PRESENTE" foi removida do seed.
- Nenhuma tabela nova — reaproveita `produto_movimento_mestre/detalhe` (`tipo_movimento =
  'DEVOLUCAO'`, já existia desde `V013`) e `venda_devolucao` (já existia desde `V018`, agora
  finalmente usada).
- `cfg_geral.cfg_exige_numero_venda_devolucao boolean NOT NULL DEFAULT false` (coluna nova,
  2026-08-11, dentro de `V023__cfg_geral.sql` — banco em construção, editada em vez de nova
  migration). Ver `docs/telas/configuracao-geral.md`.

## Impacto nas integrações

Nenhum ainda — comissão e TEF ficam fora do v1. **Documento fiscal deixou de ser non-goal em
2026-08-19** (planejamento, não implementado) — ver "Revisão 2026-08-19" acima; até esse trabalho
ser codado, toda devolução continua sem nota fiscal, igual a hoje.

## Non-goals

- Cálculo de comissão de fato — só o dado (`id_funcionario`) fica pronto.
- Uso do fluxo de troca de `venda_devolucao` (`id_venda_credito` como geradora de uma nova
  venda) — a coluna existe e é preenchida, mas não dispara nenhuma lógica de troca.
- Saldo parcial de vale (usar parte do valor e manter o resto disponível) — um vale é sempre
  consumido por inteiro, numa única venda.
- **Emissão de nota fiscal sem nenhum número de venda informado** — decisão de escopo (ver
  "Revisão 2026-08-19"), não limitação técnica.

## Métrica de sucesso

Devolução de um produto simples (leitura → gravar → vale impresso) em menos de 20 segundos; o
vale gerado pode ser resgatado numa venda futura sem nenhuma etapa manual além de digitar o
número.
