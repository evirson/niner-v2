# Spec: Entrada de Mercadorias (XML NF-e + lançamento manual)      Status: Implementada (falta só Fase 5 — atalho de etiqueta)
Autor: Evirson (dono do produto) + Claude · Data: 2026-07-23, implementação 2026-08-11/12 (Manual+Planilha), 2026-08-18/19 (XML, Cancelamento, Filtros), 2026-08-20 (respeito ao parâmetro "Usa Cor/Grade") · Módulo(s): `estoque` (entrada) · Fase: 1 — Núcleo do ERP

> **Estado de implementação (2026-08-20).** Todas as "Questões abertas" abaixo foram
> respondidas "sim" pelo dono do produto e a maioria dos **[COMPLEMENTAR]** ao longo do
> documento reflete decisões já tomadas durante a implementação (mantidos no corpo do texto
> como registro histórico da discussão, mesmo já resolvidos na prática) — implementado:
> - **Fluxo manual** completo: tela de conferência item a item, confirmação grava
>   `produto_movimento_mestre` (`COMPRA`) + N `produto_movimento_detalhe` (`C`) numa
>   transação, saldo sobe via trigger (V019), sem escrita manual de estoque.
> - **Fluxo Planilha** completo: modelo baixável (`GET .../planilha/modelo`), preview que casa
>   cada linha por EAN → descrição+marca+referência (+ cor/tamanho da grade quando o produto
>   usa grade) → cadastra cor/tamanho novos automaticamente quando a confiança é alta, linha
>   sem match fica pendente de resolução manual antes de confirmar.
> - **Rateio de frete/IPI/ICMS-ST no custo** e **reajuste automático de `preco_custo`/
>   `preco_venda`** — ambos configuráveis (`cfg_geral.cfg_rateia_frete_entrada` /
>   `cfg_reajusta_preco_entrada`, Parâmetros do Sistema), desligados por padrão.
> - **`contas_pagar`** gerado a partir de parcelas informadas no corpo da confirmação
>   (opcional — Manual/Planilha só geram se o operador preencher; o XML preencheria a partir
>   de `cobr/dup`, ainda não implementado). Conta contábil vem de
>   `cfg_geral.id_plano_contas_compra_mercadoria` (não mais de `fornecedor.id_plano_contas`,
>   que era a conta do fornecedor, errado para este uso — corrigido em V032).
> - **`produto_fornecedor`** (V031): vínculo código-do-fornecedor × variação, aprendido a cada
>   entrada — acelera o match de importações futuras do mesmo fornecedor. Fornecedor **não**
>   é obrigatório no cadastro do produto (decisão da questão 4).
> - **Cadastro rápido embutido** de produto/fornecedor/NCM direto na tela de conferência
>   (`ProdutoQuickCreateModal.tsx`, `FornecedorQuickCreateModal.tsx`, `PesquisaNcmModal.tsx`)
>   quando um item não casa com o cadastro.
> - **Multi-empresa**: `GET /api/v1/empresas/permitidas` (ADMIN vê todas do tenant, OPERADOR
>   só as ligadas a ele em `usuario_empresa`) — o formulário deixa escolher em qual empresa a
>   mercadoria está entrando.
> - **Correção pós-confirmação** (questão 7): edição direta do item (`PUT
>   .../itens/{idDetalhe}`), a trigger de estoque já desfaz o delta antigo e aplica o novo;
>   **sem** tabela de histórico de UPDATE/DELETE por ora (pendência registrada, não bloqueante).
> - Schema definido em `V031__estoque_entrada.sql` (`entrada_xml`, `produto_fornecedor`) e
>   `V032__entrada_planilha.sql` (`cfg_geral.id_plano_contas_compra_mercadoria` + seed do plano
>   de contas de compra) — ver "Impacto no banco" abaixo pelo desenho final (difere um pouco
>   do proposto no rascunho original).
> - **Fase 3 — Fluxo XML (2026-08-18/19)** — `NfeXmlParser.java` (DOM sem namespace, XXE-safe)
>   + `EntradaXmlService.java`. A aba "Dados Gerais" pede o **upload do XML primeiro**;
>   fornecedor/empresa/nota/data/parcelas só aparecem depois de processar. Fornecedor casado
>   pelo CNPJ do `emit` — sem match, `FornecedorQuickCreateModal` abre sozinho, pré-preenchido,
>   já atribuindo `cfg_geral.id_plano_contas_compra_mercadoria` por baixo (não é campo da tela
>   em nenhum dos 3 fluxos). Empresa casada pelo CNPJ do `dest` contra `empresa.cnpj` (hoje
>   normalmente vazio — não existe tela de cadastro de empresa ainda, só `GET
>   /api/v1/empresas`). Nota fiscal e data sempre do XML, somente-leitura. Parcelas: só pede
>   número manualmente se o XML não trouxer `cobr/dup` — se trouxer, vêm automáticas.
>   Matching de item: EAN (`produto_barra.ean`) → `produto_fornecedor` aprendido (`cProd`) →
>   heurística de texto (só sugestão) → pendência manual. Cor/tamanho **nunca** são cadastrados
>   sozinhos no XML (diferente da Planilha) — sempre exigem confirmação manual do operador;
>   quando o palpite bate com uma opção já existente no cadastro, vem **pré-selecionado** (não
>   auto-criado). Pendência sem produto: o `nomeProduto` sobra sem a cor/tamanho identificados
>   (removidos do texto), e cadastrar UM item da pendência propaga automaticamente
>   `idProdutoEncontrado`/`idGradeEncontrada` para as OUTRAS linhas com o mesmo nome
>   normalizado — evita recadastrar o mesmo produto em tamanhos diferentes. Testado ponta a
>   ponta com 2 NF-es reais (Dakota Calçados 36 itens; A. Grings S.A., `nfe-grings.xml`, no
>   `EntradaXmlCrudTest`, 8 testes).
> - **Cancelamento (2026-08-19)** — `POST /api/v1/estoque/entradas/{id}/cancelar`, ADMIN-only.
>   Mestre original nunca é apagado/editado — ganha `cancelado`/`data_cancelamento`/
>   `id_usuario_cancelamento`/`motivo_cancelamento` (mesmo padrão de `venda.cancelada`); o
>   estorno de estoque é um NOVO `produto_movimento_mestre` tipo `CANCELAMENTO` com
>   `credito_debito='D'` por item (a trigger de V019 reverte `produto_estoque` sozinha).
>   `contas_pagar` geradas pela entrada são deletadas. Bloqueios: já cancelada → 409; conta a
>   pagar já quitada → 409 (hoje inatingível na prática, sem tela de baixa ainda — mantido como
>   defesa em profundidade). Reimportar a mesma NF-e depois de cancelar funciona (o índice
>   único de idempotência ganhou `AND cancelado = false`). Precisou de `GRANT UPDATE` **de
>   coluna** (não de tabela) em `produto_movimento_mestre` pra `niner_app`, furando a
>   imutabilidade P3 só nas 4 colunas novas — os testes JUnit não pegaram essa lacuna porque
>   rodam com o superusuário do Testcontainers, não com `niner_app` de verdade. Ícone vermelho
>   ao lado do de visualizar na grid, só visível pra ADMIN e quando `!cancelada`; linha
>   cancelada ganha badge "Cancelada" e continua na grid.
> - **Filtros da listagem (2026-08-19)** — popup obrigatório ao entrar na tela (mesmo padrão do
>   CRM/Cancelamento de Devolução): Fornecedor (busca por texto), Empresa, Nº Nota Fiscal, Data
>   Início/Fim. Todos os campos opcionais (em branco = lista tudo). Dois botões no popup:
>   "Localizar" e "＋ Nova entrada" (pula a busca, vai direto pro formulário). Bug de fuso
>   horário achado e corrigido nesta rodada: a sessão do Postgres roda em UTC mas a tela mostra
>   data em horário local do navegador — filtro de data e gravação de `dataMovimento` agora
>   usam `(coluna AT TIME ZONE 'America/Sao_Paulo')` em vez de comparar/gravar em UTC puro.
> - **Parâmetro "Usa Cor/Grade" desligado (2026-08-20)** — nenhum dos 3 fluxos pede ou mostra
>   Cor/Tamanho, mesmo para um produto que já tenha `id_grade` gravado de uma sessão anterior
>   (a grade é ignorada por completo enquanto o parâmetro estiver desligado — decisão explícita
>   do dono do produto, não só um bloqueio para produto novo). `EntradaPlanilhaService` zera
>   `idGrade` internamente quando `cfg_geral.cfg_usa_cor_grade = false`;
>   `PesquisaProdutoEntradaModal` (fluxo Individual) passou a checar a flag global, não só o
>   `idGrade` do produto escolhido; `LinhaPendentePlanilha` (pendência do Planilha/XML) só exibe
>   os selects de Cor/Tamanho quando a linha resolvida realmente tem grade — antes disso havia
>   um bug de travamento (produto sem grade caindo nessa tela ficava com o select de Tamanho
>   sempre vazio e o "Confirmar" nunca habilitava).
> - **Grid "Localizados"/"Não Localizados" agrupada por nome (2026-08-21)** — com "Usa Cor/Grade"
>   desligado, a coluna Cor/Tamanho some das duas grids e linhas com o mesmo nome de produto
>   (`pendentesAgrupados`, `LinhaPendenteSemGrade`) somam em uma só (quantidade total); "＋
>   Cadastrar"/"Pesquisar"/"Ignorar" na linha agrupada resolvem/ignoram TODAS as linhas do grupo
>   de uma vez (`aoCriarProduto`/`aoSelecionarNaPesquisa`). Ordem dos itens localizados segue a
>   ordem do XML/planilha — não é reordenada por descrição/cor/tamanho (pedido revertido).
> - **NCM do XML sincroniza com o cadastro do produto** — `EntradaMercadoriaService.efetivar`
>   substitui `produto.codigo_ncm` pelo NCM do XML quando diferente; produto não localizado leva
>   o NCM do XML (não validado) pro cadastro rápido, mesmo quando esse código ainda não existe em
>   `cfg_produto_ncm`.
>
> **Pendente (não implementado ainda):**
> - **Fase 5 — Atalho de Emissão de Etiquetas**: ação rápida para imprimir etiquetas dos
>   produtos recém-recebidos direto a partir de uma entrada confirmada. Único item que falta
>   para esta feature ser considerada 100% completa.
> - Questão 8 (quem confirmou o movimento) resolvida **mais simples** do que o rascunho
>   original propunha: nenhuma tabela `usuario↔funcionário` nova — `id_usuario` foi direto
>   pra `produto_movimento_mestre` (FK pra `usuario`, nullable porque nenhum outro fluxo grava
>   ainda), não `id_funcionario` no detalhe como o rascunho cogitava.
>
> Seções abaixo preservadas como registro da discussão original; onde o texto conflitar com o
> resumo acima, o resumo acima reflete o que está implementado de fato.

> **⚠️ RASCUNHO original (2026-07-23)** — estrutura, tabelas e mapeamentos preenchidos a partir
> do schema existente (V019/V026, nada de tabela nova até segunda ordem). As seções marcadas
> com **[COMPLEMENTAR]** precisavam da explicação/decisão do Evirson antes de a spec ser
> aprovada — a maioria foi respondida durante a implementação, ver bloco acima.

> **Registro da discussão (2026-07-23) — estado ao pausar.** Decisões já fechadas pelo dono
> do produto (a incorporar no corpo da spec na retomada):
> - **Fluxo Planilha** adicionado como terceiro fluxo (modelo gerado pelo sistema, importação
>   com validação, cadastro de item/variações/dados fiscais quando não existe).
> - **Tipo de entrada:** XML com CFOP de compra → `COMPRA` automático; fora disso o usuário
>   seleciona o tipo (ajuste, devolução etc.).
> - Questões 1–2: rateio de frete/IPI/ST no custo e reajuste de `preco_custo`/`preco_venda`
>   — **sim, configuráveis** (onde mora a configuração ainda em aberto, ver abaixo).
> - Questão 3: gerar `contas_pagar` das duplicatas do XML — **sim**.
> - Questões 4–5: vínculo produto×fornecedor para match por `cProd` — **sim**, sem tornar
>   fornecedor obrigatório no produto; cadastro rápido de produto embutido — **sim**.
> - Questão 6: conversão de unidade de compra→venda — **sim**.
> - Questão 7 (correção de entrada confirmada): **edição direta E estorno, ambos com
>   rastreabilidade de UPDATE/DELETE** (auditoria de quem/quando/valor antigo→novo, P3).
> - Questão 8: **criar tabela de ligação usuário↔funcionário**.
> - Impacto no banco: **aprovados** `chave_nfe` + `serie` no mestre e a tabela `entrada_xml`.
>
> **Pendências para a retomada:**
> 1. Confirmar o desenho físico de `produto_fornecedor` (cProd + unidade_compra +
>    fator_conversao?) e `usuario_funcionario` — o "sim" foi de conceito, falta aprovar as
>    tabelas.
> 2. Onde mora a configuração de rateio/reajuste: `cfg_geral`, na tela por entrada, ou
>    padrão em `cfg_geral` com override na tela.
> 3. Divergência entre soma dos itens e `vNF`: bloqueia ou só avisa.
> 4. Detalhar o fluxo Planilha (formato do modelo, colunas, quais dados fiscais).
> 5. Desenhar a auditoria de UPDATE/DELETE do detalhe (tabela de histórico/trigger).

## Problema

O estoque é a fonte da verdade do sistema (P1), mas hoje não existe nenhuma forma de colocar
mercadoria *para dentro* dele: as tabelas do ledger (`produto_movimento_mestre` +
`produto_movimento_detalhe`, V019) e o saldo materializado (`produto_estoque`, mantido pela
trigger `trg_produto_movimento_detalhe_estoque`) existem desde V019, mas não há endpoint nem
tela. O lojista precisa registrar a compra de mercadoria de duas formas:

1. **Importando o XML da NF-e** (modelo 55) que o fornecedor emite — o caminho preferido,
   sem digitação;
2. **Lançamento manual** — para compra sem nota eletrônica, produtor rural, ajuste de carga
   inicial, etc.

História de usuário já prevista na spec-mãe (§2, história 4): *"Como operador, quero
registrar entrada de mercadoria (compra) e ajustes de inventário com motivo, para o estoque
refletir a realidade."* Requisito R2: toda movimentação registra origem, usuário e saldo.

**[COMPLEMENTAR]** — contexto de negócio: com que frequência o lojista típico recebe
mercadoria, quem opera essa tela (dono? estoquista?), volume médio de itens por nota.

## Solução proposta

Uma tela `estoque.entrada` com **dois fluxos que convergem para a mesma confirmação**:

- **Fluxo XML:** upload do arquivo `.xml` da NF-e → o backend faz o parse, casa fornecedor e
  itens com o cadastro, e devolve uma **pré-entrada** para conferência na tela (itens
  casados, itens sem correspondência, totais). O operador resolve as pendências (vincular ou
  cadastrar produto/fornecedor) e **confirma**. Nada entra no estoque antes da confirmação.
- **Fluxo manual:** o operador informa fornecedor, número da nota (opcional) e adiciona itens
  um a um (busca por SKU/descrição/EAN — leitor de código de barras funciona por ser input de
  texto), com quantidade e custo. Mesma tela de conferência, mesma confirmação.
- **Fluxo Planilha:** geramos uma planilha modelo ao usuário que vai seguir o preenchimento padrão 
que o sistema entende e importa através de um clique que seleciona a planilha a ser importada, valida os
itens se existe já linka com cadastro se nao existe abre a opção de cadastramento do item e variações e também dados fiscais,
coisa importante para dias atuais com nova legislação tributária.

A confirmação grava **1 linha em `produto_movimento_mestre`** (`tipo_movimento = 'COMPRA'`)
+ **N linhas em `produto_movimento_detalhe`** (`credito_debito = 'C'`), numa única transação.
A trigger de V019 materializa o saldo em `produto_estoque` — o serviço Java **não** atualiza
saldo na mão.

**[COMPLEMENTAR]** — se a entrada não for via XML com o CFOP de compra, entrão precisamos que usuário selecione o tipo de 
entrada, ajuste de estoque , devolução etc...

## Tabelas envolvidas (todas já existem — nenhuma migration nova prevista, ver "Impacto no banco")

### `produto_movimento_mestre` (V019) — cabeçalho da entrada (imutável, P3)

| Coluna | Tipo | Uso na entrada |
|---|---|---|
| `id_movimento` | identity PK | — |
| `id_tenant` | smallint | `TenantContext` (P8) |
| `id_empresa` | integer NOT NULL | Empresa que recebe a mercadoria (v1: a única do tenant) |
| `tipo_movimento` | enum | **`COMPRA`** neste fluxo |
| `data_movimento` | timestamptz | Data do lançamento (`now()`); a data de emissão da NF fica no XML bruto |
| `id_fornecedor` | integer FK | Obrigatório na entrada (nullable no schema — obrigar no serviço) |
| `id_venda` / `id_transferencia` / `id_devolucao` | integer | NULL neste fluxo |
| `nota_fiscal` | integer | Número da NF (tag `nNF` do XML; opcional no manual) |

### `produto_movimento_detalhe` (V019) — 1 linha por variação (corrigível, ver V019)

| Coluna | Tipo | Uso na entrada |
|---|---|---|
| `id_movimento` | FK composta p/ mestre | — |
| `id_empresa` | integer | = mestre |
| `id_funcionario` | integer FK | Operador logado (via vínculo usuário→funcionário) — **[COMPLEMENTAR]**: hoje `usuario` não tem FK para `funcionario`; como resolver? |
| `id_variacao` | integer FK `produto_barra` | Variação recebida |
| `credito_debito` | enum C/D | **`C`** (entrada soma) |
| `qtd_produto` | numeric(14,3) | Quantidade recebida (`qCom` no XML) |
| `preco_custo` | numeric(12,2) | Custo unitário (`vUnCom` no XML) — P7 |
| `preco_venda` | numeric(12,2) | Preço de venda vigente no momento (snapshot para auditoria) |
| `valor_desconto` / `valor_acrescimo` | numeric(12,2) | `vDesc` / rateio de frete+IPI+ST — **[COMPLEMENTAR]**: ratear no custo ou só registrar? |
| `produto_oferta` | boolean | `false` |
| `origem` | text | `'entrada xml'` / `'entrada manual'` |

### Efeitos e tabelas relacionadas

- **`produto_estoque`** — saldo somado automaticamente pela trigger (não mexer via Java).
- **`fornecedor`** (V016) — casado pelo **CNPJ do emitente** (`emit/CNPJ`); CNPJ é único por
  tenant. Sem match → oferta de cadastro rápido (modal, mesmo mecanismo `PlanoContasModal`)
  pré-preenchido com `emit` do XML (razão social, IE, endereço).
- **`produto` / `produto_barra`** (V017) — item do XML casado por, nesta ordem:
  1. `cEAN`/`cEANTrib` → `produto_barra.ean` (único por tenant quando preenchido);
  2. `cProd` (código no fornecedor) → **[COMPLEMENTAR]**: hoje não guardamos o código do
     produto no fornecedor em lugar nenhum — criar vínculo (tabela produto×fornecedor) ou
     casar só por EAN + manual?
  3. Sem match → operador vincula a uma variação existente **ou** cadastra o produto na hora
     — **[COMPLEMENTAR]**: cadastro rápido embutido (descrição/NCM/custo vindos do XML) ou
     obriga a passar pela tela de Produtos?
- **`contas_pagar`** (V026) — o XML traz as duplicatas (`cobr/dup`: `nDup`, `dVenc`, `vDup`).
  Gerar 1 linha por duplicata com `nota_fiscal`, `id_fornecedor`, `id_plano_contas` (o do
  fornecedor), `data_vencimento`, `valor_pagar` — **[COMPLEMENTAR]**: gera automático na
  confirmação, opcional (checkbox), ou fica fora desta spec?
- **`outbox_evento`** (V022) — P1/P2: a entrada muda saldo → evento de estoque no outbox na
  mesma transação, para os canais replicarem. Na Fase 1 (sem canal ativo) o evento é gravado
  e fica sem consumidor; o formato segue §3.3 da spec-mãe.
- **`cfg_produto_ncm`** (V017) — validação/lookup do NCM dos itens do XML.

## Mapeamento XML NF-e → banco (modelo 55, layout 4.00)

| Caminho no XML | Campo | Destino |
|---|---|---|
| `infNFe/@Id` (chave de acesso, 44 dígitos) | chave da NF-e | **Idempotência (P2)** — ver "Impacto no banco" |
| `ide/nNF` | número da NF | `produto_movimento_mestre.nota_fiscal` |
| `ide/serie` | série | ⚠️ sem coluna hoje — ver "Impacto no banco" |
| `ide/dhEmi` | emissão | só no XML bruto (auditoria) |
| `emit/CNPJ`, `emit/xNome`, `emit/IE`, `emit/enderEmit` | emitente | match/cadastro de `fornecedor` |
| `det/prod/cProd` | código no fornecedor | match (ver questão acima) |
| `det/prod/cEAN`, `cEANTrib` | GTIN | match `produto_barra.ean` (ignorar `SEM GTIN`) |
| `det/prod/xProd` | descrição | exibição na conferência; default p/ cadastro rápido |
| `det/prod/NCM` | NCM | validação contra `cfg_produto_ncm` |
| `det/prod/uCom`, `qCom`, `vUnCom` | unidade/qtd/custo | `qtd_produto`, `preco_custo` — **[COMPLEMENTAR]**: conversão de unidade (compra em CX12, vende em UN)? |
| `det/prod/vDesc`, `vFrete`, `vOutro`, `imposto/IPI`, `ICMS ST` | descontos/acréscimos | `valor_desconto`/`valor_acrescimo` (+ rateio? questão acima) |
| `total/ICMSTot/vNF` | total da nota | conferência (soma dos itens deve bater) |
| `cobr/dup` (`nDup`, `dVenc`, `vDup`) | duplicatas | `contas_pagar` (ver questão acima) |
| XML completo | payload bruto | `JSONB`/texto para auditoria (P3) — ver "Impacto no banco" |

## Tela

**[COMPLEMENTAR]** — esta seção é um esqueleto; confirmar/ajustar o desenho.

- **Listagem** (`/entradas`): colunas Data, Fornecedor, Nota Fiscal, Qtde de itens, Valor
  total, Origem (XML/manual) — padrão consolidado (50/página, janela deslizante, ordenação
  com allowlist, cabeçalho/rodapé fixos, `AjudaDaTela`, ícone da tela). Ação de linha:
  **visualizar** (verde, read-only). ⚠️ Sem editar/excluir: o mestre é **imutável (P3)** —
  correção é feita por movimento de estorno **[COMPLEMENTAR]**: confirmar essa regra e como
  o estorno aparece na UI.
- **Nova entrada**: escolha do fluxo (botão "Importar XML" + botão "Lançamento manual").
- **Conferência (comum aos dois fluxos):** cabeçalho (fornecedor, nota, data) + grade de
  itens (variação, descrição, qtd, custo unitário, subtotal) + total; itens sem match
  destacados com ação "vincular / cadastrar". Campos decimais e datas seguem `masks.ts`
  (vírgula abre decimais, `dd/mm/aaaa`, never `<input type="date">`).
- Papéis: `ADMIN` e `OPERADOR` — **[COMPLEMENTAR]**: operador pode confirmar entrada ou só
  preparar (aprovação do admin)?

## Critérios de aceitação (viram testes)

Rascunho — refinar junto com as decisões acima:

- Dado um XML de NF-e válido cujo emitente e todos os itens casam com o cadastro, quando
  confirmado, então cria 1 mestre `COMPRA` + N detalhes `C` e o saldo de cada variação em
  `produto_estoque` sobe exatamente a quantidade da nota.
- Dado o **mesmo XML importado duas vezes** (mesma chave de acesso), quando confirmado de
  novo, então a segunda é rejeitada com 409 e **nenhum saldo é duplicado** (P2).
- Dado um XML cujo emitente não existe no cadastro, quando importado, então a pré-entrada
  aponta o fornecedor pendente e oferece cadastro rápido pré-preenchido; não confirma
  enquanto pendente.
- Dado um item sem correspondência de EAN, quando importado, então o item fica pendente de
  vínculo e a confirmação é bloqueada até resolver (vincular ou cadastrar).
- Dado um lançamento manual com fornecedor, 2 variações e quantidades fracionadas (ex.:
  1,500), quando confirmado, então o ledger grava `numeric(14,3)` sem arredondar.
- Dado um lançamento manual sem fornecedor, quando confirmado, então 400 (fornecedor é
  obrigatório na entrada, ainda que a coluna seja nullable).
- Dado um XML malformado ou que não é NF-e modelo 55, quando enviado, então 422 com mensagem
  amigável (Problem Details) e nada é gravado.
- Dado que a soma dos itens difere de `vNF`, quando importado, então a conferência exibe a
  divergência — **[COMPLEMENTAR]**: bloqueia ou só avisa?
- Dado uma entrada confirmada, então existe evento de estoque no `outbox_evento` gravado na
  mesma transação (P2).
- **[COMPLEMENTAR]** — critérios de contas a pagar / atualização de custo, conforme decisões.

## Impacto no contrato de API (implementado — difere do rascunho original)

```
POST   /api/v1/estoque/entradas                       confirma a entrada — comum aos 3 fluxos
                                                        {idFornecedor, idEmpresa?, notaFiscal?, dataMovimento?,
                                                         chaveNfe?, serieNota?, xmlBruto?, valorRateio?,
                                                         itens:[{idVariacao, qtd, precoCusto}],
                                                         contasPagar?:[{numeroDuplicata?, dataVencimento, valor}]}
GET    /api/v1/estoque/entradas?idFornecedor=&notaFiscal=&pagina=&limite=&ordenarPor=&direcao=   lista paginada
GET    /api/v1/estoque/entradas/{id}                   detalhe (mestre + itens)
PUT    /api/v1/estoque/entradas/{id}/itens/{idDetalhe}  corrige qtd/precoCusto de um item já confirmado
POST   /api/v1/estoque/entradas/planilha/preview        multipart: planilha → linhas casadas/pendentes, NÃO grava
GET    /api/v1/estoque/entradas/planilha/modelo         baixa o modelo .xlsx em branco
POST   /api/v1/estoque/entradas/xml/preview             multipart: XML da NF-e → pré-entrada (fornecedor/empresa/
                                                         itens casados ou pendentes), NÃO grava
POST   /api/v1/estoque/entradas/{id}/cancelar           ADMIN-only — {motivoCancelamento}; estorna estoque e
                                                         apaga contas_pagar geradas pela entrada
```

Todos sob `/api/v1/**` (JWT tenant, RLS — P8). `POST .../cancelar` é ADMIN-only; os demais são
ADMIN e OPERADOR sem distinção (mesmo nível de Transferência de Estoque/Devolução de
Produtos). Erros em Problem Details (RFC 9457). `GET /api/v1/estoque/entradas` aceita também
`idEmpresa`/`dataInicial`/`dataFinal` (filtros da listagem, 2026-08-19).

## Impacto no banco (implementado — V019 alterada + V031/V032 novas, banco ainda em construção)

As três lacunas identificadas no rascunho original foram todas resolvidas, como propostas:

1. **Chave de acesso da NF-e** — `produto_movimento_mestre.chave_nfe text` +
   `UNIQUE (id_tenant, chave_nfe) WHERE chave_nfe IS NOT NULL` (idempotência P2). Adicionada
   direto em `V019__estoque.sql` (banco ainda em construção — schema muda nas migrations já
   existentes, não em `V028+`, ver `docs/PROGRESSO.md`), junto com `id_usuario` (quem
   confirmou, FK pra `usuario`) e `serie_nota smallint`.
2. **XML bruto (P3/auditoria)** — tabela `entrada_xml (id_tenant, id_movimento, xml_bruto
   text, importado_em)`, RLS, `db/migration/V031__estoque_entrada.sql`. Ainda sem gravação
   real (Fase 3 pendente), mas o contrato de confirmação já aceita `xmlBruto` opcional.

Duas tabelas novas além do proposto no rascunho:

3. **`produto_fornecedor`** (V031) — vínculo `codigo_fornecedor` (cProd do XML) × `id_variacao`
   + `unidade_compra`/`fator_conversao` (conversão de unidade de compra→venda, questão 6),
   `UNIQUE (id_tenant, id_fornecedor, codigo_fornecedor)`.
4. **`cfg_geral.id_plano_contas_compra_mercadoria`** (V032) — FK composta pro plano de contas
   usado nas `contas_pagar` geradas pela entrada; V032 também semeia a árvore mínima até
   "3.03.001.001 Compra de Mercadoria para Revenda" pra tenants que ainda não a tinham.
   `cfg_geral.cfg_rateia_frete_entrada`/`cfg_reajusta_preco_entrada` (booleans, default
   `false`) foram direto em `V023__cfg_geral.sql` (banco em construção).
5. **Cancelamento (2026-08-19)** — `produto_movimento_mestre` ganhou
   `cancelado`/`data_cancelamento`/`id_usuario_cancelamento`/`motivo_cancelamento` (editado em
   `V019__estoque.sql`, mesmo padrão de `venda.cancelada`); `contas_pagar.id_movimento` (V026,
   editado in-place) liga cada duplicata gerada à entrada de origem, permitindo apagá-las no
   cancelamento. `produto_movimento_mestre_chave_nfe_uk` (idempotência do XML) ganhou `AND
   cancelado = false` pra permitir reimportar a mesma NF-e depois de cancelar.
   `V024__rls_dominio.sql` ganhou `GRANT UPDATE (cancelado, data_cancelamento,
   id_usuario_cancelamento, motivo_cancelamento) ON produto_movimento_mestre TO niner_app` —
   grant de coluna, não de tabela, preservando a imutabilidade P3 do resto da linha.

## Impacto nas integrações

Entrada de estoque dispara sincronização de saldo para os canais (P1) via outbox (P2).
Nenhum adapter novo — só o evento. Sem canal ativo na Fase 1, sem efeito visível.

## Non-goals desta feature

- Manifestação do destinatário / download automático de XML da SEFAZ (o XML chega por
  upload de arquivo).
- Pedido de compra / cotação (entrada é sempre de nota já emitida ou lançamento direto).
- NFC-e / cupom (modelo 65) e CT-e — só NF-e modelo 55.
- Devolução ao fornecedor (tipo `DEVOLUCAO` — spec própria).
- **[COMPLEMENTAR]** — confirmar a lista.

## Questões abertas (todas respondidas — ver "Estado de implementação" no topo)

1. Rateio de frete/IPI/ICMS-ST no custo unitário — sim/não/configurável? sim, configurável
2. Atualizar `produto.preco_custo` na entrada e recalcular `preco_venda` pelo
   `percentual_venda` (com `reajustado_em`)? Automático, com confirmação, ou nunca? sim, configurável
3. Gerar `contas_pagar` a partir das duplicatas do XML — automático/opcional/fora? sim
4. Vínculo produto×fornecedor (`cProd`) para melhorar o match nas próximas importações? sim, mas produto fica livre de obrigatoriedade de fornecedor, seguir o desenho exato das tabelas atuais
5. Cadastro rápido de produto a partir do item do XML — embutido ou não? sim
6. Conversão de unidade de compra (CX/FD) → unidade de venda (UN)? sim
7. Correção de entrada confirmada: estorno formal (novo movimento `D`) ou edição do 
   detalhe (V019 permite UPDATE no detalhe)? sim
8. `id_funcionario` do detalhe: como ligar o usuário logado ao funcionário? criar tabela de ligação usuario / funcionario

## Ajuda da tela (R22 / §3.7.1)

- **`chave_tela`: `estoque.entrada.lista`** e **`estoque.entrada.form`** — ver
  `web/src/components/AjudaDaTela.tsx`. `urlVideo`: `null` por ora. A tela de detalhes
  (`EntradaMercadoriaDetalhe.tsx`) reaproveita a chave `.lista`, não tem entrada própria.

## Métrica de sucesso

Importar uma NF-e de 50 itens, com todos os produtos já cadastrados com EAN, em menos de
2 minutos do upload à confirmação (zero digitação de item).
