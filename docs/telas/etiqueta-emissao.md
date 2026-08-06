# Emissão de Etiqueta de Produtos

**Status (2026-08-05):** primeira implementação real da área — até então só um placeholder "Em
construção" no grupo Implementações Futuras (nascido em 2026-08-04). Seleciona produtos e
quantidades de 3 formas diferentes → grade local editável → escolhe um modelo já criado em
Configuração de Etiqueta (`docs/telas/configuracao-etiqueta.md`) → imprime em lote, cada etiqueta
podendo ser de um produto/variação diferente. Duas rodadas na mesma data: **rodada 1** —
implementação completa (3 modos de seleção, grade, impressão em lote); **rodada 2** — modo
Individual deixou de exigir SKU pré-cadastrado (cria na hora), linha/coluna viraram obrigatórias
por produto, e nasceu o botão "Limpar Lista".

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

- Se o produto usa variação de linha e/ou coluna (`produto.nome_variante_linha`/
  `nome_variante_coluna` não nulos — configurado **por produto**, no cadastro dele, não uma flag
  global de tenant), o respectivo seletor aparece na tela como **obrigatório**, com o próprio nome
  da dimensão como rótulo (ex.: "COR *", "TAMANHO *" em vez de um genérico "Variação de
  Linha/Coluna"). As opções vêm do **catálogo inteiro do tenant** (`GET
  /api/v1/etiqueta-emissao/variantes`), não só das variações que já existem pra este produto
  específico.
- Se o produto não usa nenhuma das duas dimensões, nenhum seletor aparece — só a quantidade.
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

> ⚠️ **Nenhum serviço do sistema grava `tipo_movimento='COMPRA'` ainda** — "Entrada de Produtos
> por Compra" é outra área de Implementações Futuras, ainda não construída. O filtro é real e
> funciona contra o schema existente (confirmado por pesquisa no código antes de implementar), só
> não vai ter dado até aquela tela existir (ou uma carga manual). Documentado aqui e em
> `AjudaDaTela` pra não parecer bug quando vier vazio.

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
resolvido como **por-PRODUTO**, via `produto.nome_variante_linha`/`nome_variante_coluna` (não nulo
= este produto usa aquela dimensão), e **não** a flag global de tenant
(`cfg_usa_variante_linha`/`coluna`, que só controla se o campo aparece no cadastro de Produto). É
a mesma fonte que já decide o rótulo do seletor.

**Novo serviço de domínio, não específico desta tela:** `ProdutoBarraService` + `ProdutoBarraDtos`
(pacote `com.vetor.niner.catalogo`, não `etiquetaemissao`) — o `CLAUDE.md` já antecipava esse
nome/método exato ("quando for construir produto_barra/o serviço de variação, chame
`gerar_ean13_interno()` explicitamente no `ProdutoBarraService.criar()`"), então virou
infraestrutura de domínio compartilhada, reaproveitável por qualquer tela futura que precise
achar-ou-criar uma variação — não só esta. `obterOuCriar(idProduto, idVarianteLinha,
idVarianteColuna)`: valida a obrigatoriedade (mesma regra acima, reforçada no servidor — defesa em
profundidade, já que o frontend também valida), busca a combinação exata (`IS NULL`-aware quando
linha/coluna não se aplicam ao produto), cria com `gerar_ean13_interno()` se não achar.

Endpoint novo `GET /api/v1/etiqueta-emissao/variantes` (catálogo inteiro do tenant) substituiu o
antigo `GET .../produtos/{id}/variacoes` (removido — trazia só as variações já existentes de UM
produto, que deixou de fazer sentido quando o produto pode não ter nenhuma ainda).

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
| `GET` | `/etiqueta-emissao/produtos` | Busca produtos ativos (descrição/SKU), com nome das variantes do produto quando ele usa. |
| `GET` | `/etiqueta-emissao/variantes` | Catálogo inteiro do tenant (todas as variantes de linha e de coluna cadastradas). |
| `POST` | `/etiqueta-emissao/produtos/{idProduto}/variacao` | Acha ou cria a variação (delega pro `ProdutoBarraService`, módulo `catalogo`). Único endpoint com efeito colateral real. |
| `GET` | `/etiqueta-emissao/fornecedores` | Busca leve de fornecedor (não existia endpoint enxuto no projeto, só o paginado completo). |
| `GET` | `/etiqueta-emissao/entradas` | Modo Por Entradas — exige ao menos 1 dos 3 filtros. |
| `GET` | `/etiqueta-emissao/estoques` | Modo Por Estoques — empresa obrigatória (ADMIN explícita, OPERADOR via `eid`). |

`ProdutoBarraService`/`ProdutoBarraDtos` (pacote `catalogo`, ver "Rodada 2" acima) — infraestrutura
de domínio compartilhada, consumida por este controller mas não pertencente a ele.

**14 testes** (`EtiquetaEmissaoCrudTest.java`): busca traz todos os produtos ativos com ou sem
variação, busca traz o nome das variantes quando o produto usa, opções de variante retornam o
catálogo do tenant, criar variação gera SKU novo quando o produto não usa variante, criar variação
exige linha quando o produto usa essa dimensão, criar variação com linha e coluna acha e depois
reaproveita a mesma (não duplica), busca de fornecedores só ativos, Por Entradas rejeita sem filtro
nenhum, Por Entradas soma quantidade de várias compras da mesma variação, Por Entradas filtra por
nota fiscal, Por Estoques exige empresa pra ADMIN, Por Estoques só traz quantidade positiva, Por
Estoques filtra por categoria, e isolamento de tenant (RLS). Suíte de backend inteira: **386/386**.

## Frontend

`web/src/pages/etiquetaemissao/` — `EtiquetaEmissaoForm.tsx` (tela principal: grade + Emitir
Etiquetas + impressão), `SelecaoProdutosModal.tsx` (popup com os 3 modos em abas — Individual/Por
Entradas/Por Estoques), `EscolherModeloModal.tsx` (popup do passo final, lista os modelos de
Configuração de Etiqueta). `web/src/lib/etiquetaEmissao.ts` reúne tipos, chamadas de API,
`mesclarItensEmissao`, `montarSequenciaImpressao`, `paraQuantidadeInteira`.

## Impacto nas integrações

Nenhum — tela 100% interna (configuração de impressão física), não afeta canais de venda.

## Pendências explícitas, fora do escopo desta tela

- **"Por Entradas" sem dado real** até "Entrada de Produtos por Compra" existir (ver aviso acima)
  — o filtro funciona, só não tem o que buscar ainda.
- **Sem cap de quantidade máxima** (diferente do Teste de Impressão) — decisão deliberada, não
  testado imprimindo um lote de milhares de etiquetas de propósito (achado sem querer um estoque
  de teste com 1007 unidades de um produto, não forçado a imprimir pra não arriscar travar o
  navegador renderizando ~1000 nós de DOM só pra confirmar algo que a lógica já cobre).
- **Sem tela própria de listar/editar/excluir variação** — `ProdutoBarraService` só acha-ou-cria,
  não tem CRUD completo (ver `docs/telas/produto.md`, Non-goals).
