# Emissão de Etiqueta de Produtos

**Status (2026-08-05):** primeira implementação real da área — até então só um placeholder "Em
construção" no grupo Implementações Futuras (nascido em 2026-08-04). Seleciona produtos e
quantidades de 3 formas diferentes → grade local editável → escolhe um modelo já criado em
Configuração de Etiqueta (`docs/telas/configuracao-etiqueta.md`) → imprime em lote, cada etiqueta
podendo ser de um produto/variação diferente. Duas rodadas na mesma data: **rodada 1** —
implementação completa (3 modos de seleção, grade, impressão em lote); **rodada 2** — modo
Individual deixou de exigir SKU pré-cadastrado (cria na hora), linha/coluna viraram obrigatórias
por produto, e nasceu o botão "Limpar Lista". **2026-08-08:** modelo de variação "linha/coluna"
(rótulo livre por produto) substituído por **cor + grade** (curva de tamanhos nomeada e
ordenada) — ver seções abaixo e `docs/telas/produto.md`.

## Contexto

Depois de configurar o layout físico da etiqueta (Configuração de Etiqueta), falta a peça que
liga "layout" a "produto real" e manda pra impressora. Pedido veio como lista numerada e fechada:
3 formas de escolher produtos/quantidades (Individual, Por Entradas, Por Estoques), uma grade
editável antes de imprimir, e a obrigatoriedade de escolher o modelo de etiqueta antes de emitir.
Pedido explícito: "vai ficar na aba relatórios" — mesmo grupo do menu que CRM e os outros 5
relatórios, por ser uma ferramenta de consulta/ação pontual, não um cadastro.

## Decisões de escopo (não perguntadas — resolvidas por analogia com o resto do sistema)

- **Grade 100% local, sem endpoint próprio.** Diferente de `ContagemEstoque.tsx` (grid que salva
  cada edição no servidor), aqui a grade é estado local (`ItemEmissao[]`) até o clique em "Emitir
  Etiquetas" — nada é persistido, só serve pra montar a sequência de impressão. Não existe
  "rascunho" de emissão no backend.
- **Modelo de etiqueta reaproveita 100% os endpoints de Configuração de Etiqueta** (`GET
  /api/v1/etiquetas-config` pra listar, `GET /api/v1/etiquetas-config/{id}` pra pegar o layout
  completo) — nenhum código novo no backend só pra esse passo.
- **Empresa (modo Por Estoques) segue o mesmo padrão de todo relatório do sistema:** ADMIN escolhe
  explicitamente num `<select>` (400 se não escolher), OPERADOR sempre usa a própria empresa ativa
  via claim `eid` do JWT, sem seletor visível (mesmo padrão de `RelatorioEstoqueService`/
  `CrmService`).
- **Saiu de "Implementações Futuras" e entrou no grupo "Relatórios"** do menu, ícone
  `IconeEtiqueta` (mesmo de Configuração de Etiqueta).

## Peça central: generalizar o mecanismo de impressão pra produtos DIFERENTES por etiqueta

O "Testar Impressão" (Configuração de Etiqueta) já resolvia "imprimir N cópias de UM produto".
Aqui o problema é "imprimir 1 produto por posição, cada posição podendo ser um produto/variação
diferente, com quantidades diferentes". Solução: achatar a grade numa sequência
(`ItemEmissao[] → ProdutoExemplo[]`, cada item repetido pela própria quantidade —
`montarSequenciaImpressao`), depois distribuir essa sequência pelas colunas/linhas do rolo com uma
função local (`linhasComProdutos` em `EtiquetaEmissaoForm.tsx`, mesma lógica de
`linhasParaImprimir` mas carregando o produto de cada posição junto) — cada etiqueta renderiza com
`CampoEtiquetaVisual` recebendo o `produtoExemplo` daquela posição específica, não um valor fixo
do estado do formulário. Reaproveita 100% o mecanismo de `@page` dinâmico +
`.etiqueta-imprimir`/`MM_PARA_PX_IMPRESSAO` já existentes — só a fonte dos dados por etiqueta
mudou. Sem cap artificial de quantidade máxima (diferente do "Testar Impressão", que tem
`QUANTIDADE_MAXIMA=200`) — aqui a quantidade vem de dado real de negócio (estoque/entrada), um cap
arbitrário atrapalharia uso legítimo de uma loja grande.

## As 3 formas de seleção

### 1. Individual

Busca **qualquer produto ativo**, com ou sem variação/SKU (`produto_barra`) já cadastrado — desde
a rodada 2 (ver abaixo), não é mais preciso ter SKU pré-cadastrado pra emitir etiqueta de um
produto novo.

- Se o produto usa grade (`produto.id_grade <> 1` — a coluna é `NOT NULL DEFAULT 1` e `1` é a
  grade **PADRÃO** sentinela, que significa "não usa grade de verdade"; `db/migration/V017__catalogo.sql:196-199`
  e `api/src/main/java/com/vetor/niner/catalogo/ProdutoBarraService.java:97-99` — configurado **por produto**, no cadastro
  dele, não uma flag global de tenant), os seletores **Cor** e **Tamanho** aparecem como
  **obrigatórios** (revisado 2026-08-08: o antigo par genérico linha/coluna, com rótulo livre por
  produto, virou este par fixo cor+tamanho). Cor vem do **catálogo inteiro do tenant** (`GET
  /api/v1/cores`), com "+ Nova cor" inline (`POST /api/v1/cores`) — continua sendo a válvula de
  escape temporária pra `cfg_cor` (sem tela própria, ver `docs/telas/produto.md`). Tamanho vem
  **restrito à grade do produto** (`GET /api/v1/grades/{idGrade}`), não do catálogo inteiro de
  tamanhos do tenant — coerente com a regra de `ProdutoBarraService.validarObrigatoriedade`
  (tamanho tem que pertencer à grade).
- Se o produto não usa grade, nenhum seletor aparece — só a quantidade.
- "Adicionar à Lista" chama `POST /api/v1/etiqueta-emissao/produtos/{idProduto}/variacao`, que
  **acha** a combinação produto+linha+coluna se já existir, ou **cria na hora** (gera o SKU via
  `gerar_ean13_interno()`) se ainda não existir — sempre resulta em exatamente 1 item novo na
  grade.

### 2. Por Entradas

Soma `produto_movimento_detalhe.qtd_produto` (crédito) de
`produto_movimento_mestre.tipo_movimento = 'COMPRA'`, agrupado por variação, no
período/fornecedor/nota fiscal informados. **Nenhum filtro é obrigatório sozinho, mas exige pelo
menos 1 dos 3** — decisão minha, não pedida, pra não deixar a tela rodar "todas as entradas desde
sempre" sem querer.

> ~~⚠️ **Nenhum serviço do sistema grava `tipo_movimento='COMPRA'` ainda** — "Entrada de Produtos
> por Compra" é outra área de Implementações Futuras, ainda não construída. O filtro é real e
> funciona contra o schema existente, só não vai ter dado até aquela tela existir.~~ —
> **superado em 2026-08-11**: a tela **Entrada de Produtos por Compra**
> (`docs/telas/entrada-mercadoria.md`) grava `tipo_movimento='COMPRA'`
> (`EntradaMercadoriaService.java:131`), então este modo trabalha com **dado real** — é o caso de
> uso principal dele (etiquetar o que acabou de chegar).

#### Chegada por "Emitir Etiquetas desta Nota" (2026-08-14)

A Entrada de Produtos por Compra mostra, na tela de entrada confirmada, o link **"Emitir Etiquetas
desta Nota"** (`web/src/pages/estoque/entrada/EntradaMercadoriaForm.tsx:1315-1328`), que navega
para `/etiqueta-emissao?idFornecedor=…&nomeFornecedor=…&notaFiscal=…`.

Os params `idFornecedor`/`notaFiscal` eram montados lá desde 2026-08-11, mas **esta tela não os
lia** — o operador caía aqui com a lista vazia e tinha que redigitar fornecedor e nota. Corrigido
em 2026-08-14:

- `EtiquetaEmissaoForm` lê os 3 params via `useSearchParams` (helper `origemDaEntrada`,
  `EtiquetaEmissaoForm.tsx:40-48`, tipo `OrigemEntrada` em `web/src/lib/etiquetaEmissao.ts:26-30`).
  Só há origem quando vem um `idFornecedor` numérico e positivo; a nota é **opcional** (entrada
  manual sem número de nota ainda filtra pelo fornecedor).
- Com origem, o popup de seleção **abre sozinho** (`selecaoAberta` nasce `true`, `:66`) já na aba
  **Por Entradas** (`SelecaoProdutosModal.tsx:434`), com **fornecedor e nota fiscal já
  preenchidos** (`SelecaoPorEntradas`, `:228-238`). Basta clicar em **Localizar**.
- O `nomeFornecedor` viaja na URL de propósito: o endpoint de fornecedores busca **por texto, não
  por id**, então sem o nome esta tela precisaria de uma chamada extra só pra mostrar de quem é a
  nota. Com id + nome na URL, a opção escolhida é montada direto — a busca por texto continua
  disponível se o operador quiser trocar de fornecedor.
- Aberta pelo menu (sem params), nada muda: `origemDaEntrada` devolve `null`, o popup começa
  fechado e a aba padrão continua sendo **Individual**.

Os dois lados são só frontend — nenhum endpoint novo, nenhum parâmetro novo em
`GET /etiqueta-emissao/entradas`.

### 3. Por Estoques

`produto_estoque.qtd_estoque` (só `> 0`) da empresa escolhida (obrigatória — ver "Decisões de
escopo") + categoria de produto opcional.

Quantidade de "Por Entradas"/"Por Estoques" pode vir fracionária (produto vendido por peso/medida)
— arredondada pro inteiro mais próximo, mínimo 1 (`paraQuantidadeInteira`), porque etiqueta não
existe em fração.

## Grade — editar, remover, limpar tudo

Adicionar itens já existentes (mesma `idVariacao`) **soma** a quantidade em vez de duplicar a
linha (`mesclarItensEmissao`) — útil quando o usuário busca "Por Estoques" duas vezes com filtros
que se sobrepõem, ou combina um modo com outro. Cada linha tem quantidade editável direto no campo
e um ícone de remover. Botão **"Limpar Lista"** (rodada 2, `EtiquetaEmissaoForm.tsx`) esvazia a
grade inteira de uma vez — desabilitado quando não há nenhum item.

## Rodada 2 (mesmo dia): SKU criado na hora + obrigatoriedade por produto + Limpar Lista

Pedido em 3 itens sobre a tela já construída na rodada 1:

1. No modo Individual, produto sem código de barras cadastrado deixa de ser um bloqueio — o
   sistema detecta que a combinação não existe e cadastra na hora.
2. Se o produto usa variação de linha/coluna, esses seletores viram obrigatórios no modo
   Individual.
3. Botão para limpar todos os produtos selecionados.

**Leitura da obrigatoriedade (item 2 era ambíguo — "se nas configuração são obrigatórias"):**
resolvido como **por-PRODUTO**, via `produto.id_grade` (**`<> 1`** = este produto usa variação; a
coluna é `NOT NULL DEFAULT 1` e `1` é a grade PADRÃO sentinela — nunca testar por `null`), e
**não** a flag global de tenant (`cfg_usa_cor_grade`, que só controla se o campo Grade aparece no
cadastro de Produto).

**Novo serviço de domínio, não específico desta tela:** `ProdutoBarraService` + `ProdutoBarraDtos`
(pacote `com.vetor.niner.catalogo`, não `etiquetaemissao`) — o `CLAUDE.md` já antecipava esse
nome/método exato ("quando for construir produto_barra/o serviço de variação, chame
`gerar_ean13_interno()` explicitamente no `ProdutoBarraService.criar()`"), então virou
infraestrutura de domínio compartilhada, reaproveitável por qualquer tela futura que precise
achar-ou-criar uma variação — não só esta. `obterOuCriar(idProduto, idCor, idTamanho)`: valida a
obrigatoriedade (mesma regra acima, reforçada no servidor — defesa em profundidade, já que o
frontend também valida; também checa que o tamanho pertence à grade do produto), busca a
combinação exata (`IS NULL`-aware quando o produto não usa grade), cria com
`gerar_ean13_interno()` se não achar.

**Revisado 2026-08-08 (cor/grade substitui linha/coluna):** o endpoint
`GET /api/v1/etiqueta-emissao/variantes` (catálogo genérico de linha/coluna do tenant) foi
**removido** — `OpcaoVarianteResponse`/`OpcoesVarianteResponse` não existem mais. No lugar, o
frontend busca cor e tamanho **de fontes separadas e mais específicas**: cor do catálogo inteiro
do tenant (`GET /api/v1/cores`), tamanho restrito à grade do produto selecionado
(`GET /api/v1/grades/{idGrade}`) — reflete que tamanho não é mais uma dimensão livre do tenant,
é sempre relativo a uma grade.

**Testado ao vivo:** produto com variação "BOTA FEMININA AS-250" (usa COR+TAMANHO) exigiu os dois
seletores, mensagem de validação citou o nome real do produto ("Informe a variação de \"COR\".")
quando faltava escolher, e ao confirmar gerou um SKU novo (`9001000000305`, EAN-13 real, nunca
visto antes) direto na grade. Produto sem variação nenhuma ("PRODUTO TESTE HISTORICO DE COMPRAS")
não mostrou seletor nenhum, só a quantidade, e adicionou normalmente. "Limpar Lista" esvaziou a
grade e voltou a ficar desabilitado com a lista vazia.

## Backend

`com.vetor.niner.configuracao.etiquetaemissao` (perto de `configuracao.etiqueta`, já que
reaproveita `cfg_etiqueta_config`) — sem tabela nova. Qualquer papel (`ADMIN`/`OPERADOR`), API
`/api/v1/**`. Endpoints:

| Método | Caminho | O quê |
|---|---|---|
| `GET` | `/etiqueta-emissao/produtos` | Busca produtos ativos (descrição/SKU), com `idGrade` quando o produto usa. |
| `POST` | `/etiqueta-emissao/produtos/{idProduto}/variacao` | Acha ou cria a variação (delega pro `ProdutoBarraService`, módulo `catalogo`). Único endpoint com efeito colateral real. Cor via `GET/POST /api/v1/cores`, tamanho via `GET /api/v1/grades/{idGrade}` (endpoints do módulo `catalogo`, não deste controller). |
| `GET` | `/etiqueta-emissao/fornecedores` | Busca leve de fornecedor (não existia endpoint enxuto no projeto, só o paginado completo). |
| `GET` | `/etiqueta-emissao/entradas` | Modo Por Entradas — exige ao menos 1 dos 3 filtros. |
| `GET` | `/etiqueta-emissao/estoques` | Modo Por Estoques — empresa obrigatória (ADMIN explícita, OPERADOR via `eid`). |

`ProdutoBarraService`/`ProdutoBarraDtos` (pacote `catalogo`, ver "Rodada 2" acima) — infraestrutura
de domínio compartilhada, consumida por este controller mas não pertencente a ele.

Testes (`EtiquetaEmissaoCrudTest.java`, 14 casos): busca traz todos os produtos ativos com ou sem
variação, busca devolve o `idGrade` quando o produto usa grade (é o que faz a tela saber que
precisa pedir cor/tamanho), criar variação gera SKU novo quando o produto não usa grade, criar
variação exige cor/tamanho quando o produto usa grade, criar variação com cor e tamanho acha e
depois reaproveita a mesma (não duplica), criar variação rejeita (400) tamanho que não pertence à
grade do produto, busca de fornecedores só ativos, Por Entradas rejeita sem filtro nenhum, Por
Entradas soma quantidade de várias compras da mesma variação, Por Entradas filtra por nota fiscal,
Por Estoques exige empresa pra ADMIN, Por Estoques só traz quantidade positiva, Por Estoques
filtra por categoria, e isolamento de tenant (RLS). Suíte de backend inteira: **500 testes verdes
em 2026-08-14** (eram 405 quando esta tela nasceu). A chegada por "Emitir Etiquetas desta Nota" é
100% frontend — não acrescentou teste de backend.

## Frontend

`web/src/pages/etiquetaemissao/` — `EtiquetaEmissaoForm.tsx` (tela principal: grade + Emitir
Etiquetas + impressão), `SelecaoProdutosModal.tsx` (popup com os 3 modos em abas — Individual/Por
Entradas/Por Estoques), `EscolherModeloModal.tsx` (popup do passo final, lista os modelos de
Configuração de Etiqueta). `web/src/lib/etiquetaEmissao.ts` reúne tipos, chamadas de API,
`mesclarItensEmissao`, `montarSequenciaImpressao`, `paraQuantidadeInteira` — e, desde 2026-08-14,
o tipo `OrigemEntrada` da chegada por "Emitir Etiquetas desta Nota" (`SelecaoProdutosModal` e
`SelecaoPorEntradas` ganharam a prop opcional `origemEntrada`, `null` por padrão).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `relatorios.etiquetaemissao.tela`** — tela única (não tem par lista/form): as 3
  formas de seleção no popup, o SKU criado na hora no modo Individual, cada busca somando à lista
  (o popup não fecha sozinho), edição/remoção/"Limpar Lista" na grade e a escolha obrigatória do
  modelo antes de imprimir. Erros comuns: cor/tamanho obrigatórios quando o produto usa grade,
  "Por Estoques" só traz saldo positivo, quantidade fracionária arredondada, e "Emitir Etiquetas"
  desabilitado com a lista vazia. Texto em `web/src/components/AjudaDaTela.tsx`. `url_video`:
  `NULL` por ora.
  - Atualizado em 2026-08-14: novo passo sobre a chegada pelo botão "Emitir Etiquetas desta Nota"
    (popup já aberto em Por Entradas, fornecedor e nota preenchidos — `AjudaDaTela.tsx:863`), e o
    erro comum de "Por Entradas" sem resultado, que estava **desatualizado desde 2026-08-11**
    ("nenhuma tela de Entrada de Produtos por Compra existe ainda"), passou a mandar conferir
    fornecedor/período e citar a tela de Entrada de Produtos por Compra, que já existe (`:867`).

## Impacto nas integrações

Nenhum — tela 100% interna (configuração de impressão física), não afeta canais de venda.

## Pendências explícitas, fora do escopo desta tela

- ~~**"Por Entradas" sem dado real** até "Entrada de Produtos por Compra" existir (ver aviso acima)
  — o filtro funciona, só não tem o que buscar ainda.~~ — **superado em 2026-08-11**: a Entrada de
  Produtos por Compra existe e grava `COMPRA` (`EntradaMercadoriaService.java:131`); o modo tem
  dado real.
- **Sem cap de quantidade máxima** (diferente do Teste de Impressão) — decisão deliberada, não
  testado imprimindo um lote de milhares de etiquetas de propósito (achado sem querer um estoque
  de teste com 1007 unidades de um produto, não forçado a imprimir pra não arriscar travar o
  navegador renderizando ~1000 nós de DOM só pra confirmar algo que a lógica já cobre).
- **Sem tela própria de listar/editar/excluir variação** — `ProdutoBarraService` só acha-ou-cria,
  não tem CRUD completo (ver `docs/telas/produto.md`, Non-goals).

---

## ✅ 2026-08-21 — a impressão mudou de modelo (e o nome da loja era um literal)

Esta tela **compartilha o mecanismo de impressão** com o Teste de Impressão da Configuração, então
tudo o que aquela sessão descobriu vale aqui. O detalhe completo está em
`docs/telas/configuracao-etiqueta.md`, seções de 2026-08-21; o resumo do que mudou **nesta tela**:

### Uma página por FILEIRA, não uma folha longa

`@page` passou a valer `larguraDeImpressão × passoVertical`, com `break-after: page` entre fileiras,
e quem encaixa cada página no adesivo é o **sensor de gap** da impressora. O modelo anterior — todas
as fileiras numa folha só — entregava a origem vertical ao driver, que fatiava o trabalho no tamanho
do papel configurado nele; quando esse tamanho não era múltiplo do passo, saíam fileiras em branco e
a primeira etiqueta já nascia fora do adesivo.

Para paginar, o bloco de impressão vai por **portal** para o `<body>` e usa a classe
`.etiqueta-rolo-imprimir` (a antiga `.etiqueta-imprimir` continua existindo — o Orçamento em bobina
a usa). Duas regras do projeto precisaram ser destravadas só durante a impressão, e as duas escondem
o estrago em silêncio: `visibility: hidden` mantém o espaço do que esconde, e o `overflow: hidden`
do shell **corta** o que passa da primeira página em vez de paginar.

### ⚠️ O nome da loja era o literal "NOME DA LOJA"

O campo `NOME_EMPRESA` imprimia essa string fixa — **não só na prévia: aqui também**. Toda etiqueta
emitida até esta data saiu com esse texto no lugar do nome da loja. Agora vem de
`GET /api/v1/eu` (`empresa.nomeEtiqueta`), da empresa da **sessão** — a empresa escolhida no popup
de seleção é filtro de estoque, não emitente da etiqueta.

⚠️ E **não** sai de `empresa.cfg_nome_etiqueta`, apesar do nome da coluna: aquilo é herança do ERP
legado, onde a etiqueta era um modelo de texto com marcadores, e o signup ainda semeia assim
(`{sku}\n{descricao}\n{preco_venda}`). Imprimir aquela coluna colocaria `{sku}` no adesivo.

### Código de barras mais legível

O **módulo é derivado da largura da caixa** (`larguraPx / 95`), então o SVG nasce do tamanho do
viewport e **nada é esticado** — era o esticamento que produzia barras de larguras irregulares. Os
dígitos saíram do SVG para HTML, agrupados **1+6+6** como manda o padrão EAN-13.

⚠️ `shape-rendering="crispEdges"` foi tentado e **revertido no mesmo dia**: ele arredonda cada borda
para a grade de pixels e engrossou as barras a ponto de não lerem. Ver
`docs/telas/configuracao-etiqueta.md`.

---

## Revisão 2026-08-22 — a busca deixou de truncar em silêncio (auditoria, item 33)

`EtiquetaEmissaoService.buscarProdutos` e `buscarFornecedores` cortavam em **10** — o menor limite do
produto — e nenhuma tela dizia nada.

⚠️ **Essas duas buscas alimentam mais telas do que o nome do serviço sugere:** Entrada de Produtos
(form e lista), **Devolução ao Fornecedor**, **Contas a Pagar** (form e lista) e a Emissão de
Etiqueta. Uma distribuidora com 15 cadastros começando por "DISTRIBUIDORA" recebia 10, não achava o
seu, e concluía "não está cadastrado" — com o botão **＋ Novo fornecedor** ao lado, cujo cadastro
rápido deixa de fora, por decisão documentada, a verificação de CNPJ duplicado. Daí em diante as
entradas de nota e as contas a pagar do mesmo fornecedor ficavam divididas entre dois cadastros.

O limite subiu para **20** (o mesmo dos seletores do PDV) e as **seis** telas passaram a avisar
*"Mostrando os primeiros 20 — refine a busca para ver mais."* quando o corte acontece.

⚠️ **O número vive em duas constantes que precisam bater:** `LIMITE_BUSCA` em
`EtiquetaEmissaoService` e `LIMITE_BUSCA_EMISSAO` em `web/src/lib/etiquetaEmissao.ts`. Mudar uma sem
a outra faz o aviso aparecer na hora errada — ou nunca. **O aviso é o que resolve; o número só
reduz a frequência.**
