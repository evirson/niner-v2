# Spec: Entrada de Mercadorias (XML NF-e + lançamento manual)      Status: RASCUNHO
Autor: Evirson (dono do produto) + Claude · Data: 2026-07-23 · Módulo(s): `estoque` (entrada) · Fase: 1 — Núcleo do ERP

> **⚠️ RASCUNHO** — estrutura, tabelas e mapeamentos preenchidos a partir do schema existente
> (V019/V026, nada de tabela nova até segunda ordem). As seções marcadas com
> **[COMPLEMENTAR]** precisam da explicação/decisão do Evirson antes de a spec ser aprovada.

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

## Impacto no contrato de API (rascunho — OpenAPI antes da implementação)

```
POST   /api/v1/estoque/entradas/xml          multipart: arquivo .xml → pré-entrada (parse, matches, pendências) — NÃO grava
POST   /api/v1/estoque/entradas              confirma a entrada {idFornecedor, notaFiscal, itens:[{idVariacao, qtd, precoCusto, ...}], chaveNfe?}
GET    /api/v1/estoque/entradas?pagina=&limite=&ordenarPor=&direcao=&idFornecedor=&notaFiscal=
GET    /api/v1/estoque/entradas/{id}         detalhe (mestre + itens)
```

Todos sob `/api/v1/**` (JWT tenant, RLS — P8). Erros em Problem Details (RFC 9457).
A spec-mãe (§3.4) previa `POST /api/v1/estoque/movimentacoes` genérico; esta spec o
especializa em `entradas` — **[COMPLEMENTAR]**: manter o genérico para AJUSTE ou não.

## Impacto no banco

As tabelas do ledger já cobrem o fluxo. Três lacunas identificadas, a decidir se viram
migration (V028+) ou ficam de fora:

1. **Chave de acesso da NF-e** — não há onde guardar os 44 dígitos, e ela é a chave natural
   de idempotência do XML (P2). Proposta: `produto_movimento_mestre.chave_nfe text` +
   `UNIQUE (id_tenant, chave_nfe) WHERE chave_nfe IS NOT NULL`.
2. **Série da NF** — `nota_fiscal` guarda só o número; nota de fornecedores diferentes ou
   séries diferentes podem colidir na exibição (não há constraint, então não bloqueia).
   Proposta: coluna `serie_nota smallint` no mestre, ou deixar só na chave/XML bruto.
3. **XML bruto (P3/auditoria)** — guardar o XML da nota importada. Proposta: tabela
   `entrada_xml (id_movimento FK, chave_nfe, payload xml/text, importado_em)` com RLS, ou
   coluna `JSONB` no mestre. Convenção do projeto: `JSONB` para payloads de integração.

**[COMPLEMENTAR]** — aprovar/rejeitar cada uma das três.

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

## Questões abertas (bloqueiam a aprovação)

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

- **`chave_tela`: `estoque.entrada.lista`** — **[COMPLEMENTAR]** após fechar a UI.
- **`chave_tela`: `estoque.entrada.form`** — **[COMPLEMENTAR]** após fechar a UI.

## Métrica de sucesso

Importar uma NF-e de 50 itens, com todos os produtos já cadastrados com EAN, em menos de
2 minutos do upload à confirmação (zero digitação de item).
