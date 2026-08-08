# Spec: Relatório de Estoque                        Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-04 · Módulo(s): `estoque`/`relatorios` · Fase: 2

## Problema

Não existe nenhuma visão consolidada de quanto estoque (e quanto custo) o catálogo representa,
nem uma forma de comparar quantidade entre empresas diferentes — cada tela existente (Produtos,
Diferenças de Estoque) só olha um recorte.

## Solução proposta

Tela `/relatorio-estoque`, disponível a **qualquer papel**, somente consulta — quarta tela do
grupo **Relatórios**. Uma **única tela com um seletor de Modelo** no popup de filtros
(Inventário/Sintético/Analítico, mesmo padrão do totalizador Analítico×Agrupado do Relatório de
Vendas): a troca de modelo muda as colunas da grid exibida, não a tela nem os demais filtros.

## Decisões de escopo

Todas confirmadas com o dono do produto durante o planejamento:

1. **Uma tela só com seletor de Modelo**, não três rotas separadas — os mesmos filtros
   (Empresas/Marca/Categorias/Tipo de Quantidade/Situação do Produto) servem para os 3 modelos.
2. **Marca e Categorias em multi-seleção** (mesmo componente visual de `EmpresaMultiSelect`,
   generalizado em `MultiSelectGenerico.tsx` — reutilizável por qualquer filtro de lista futuro
   que não seja especificamente sobre empresa).
3. **Produtos inativos: filtro à parte** (não uma decisão fixa) — "Situação do Produto"
   (Somente Ativos, padrão / Somente Inativos / Todos), mesmo critério (`p.ativo`) e mesmos
   valores de string (`ATIVOS`/`INATIVOS`/`TODOS`) já usados em `ProdutoService.listar`.
4. **Empresas: ADMIN em multi-select, nenhuma marcada = todas as empresas ATIVAS do tenant**;
   **OPERADOR sempre só a própria empresa**, sem seletor. Diferente dos outros relatórios (onde a
   lista de empresas só filtra linhas), aqui ela **define as colunas dinâmicas** dos modelos
   Sintético/Analítico — por isso é resolvida **antes** da consulta principal, não inferida dos
   dados: uma empresa sem nenhuma movimentação em lugar nenhum ainda precisa aparecer como coluna
   zerada.
5. **Um produto sem nenhuma variação cadastrada nunca aparece** — inner join com
   `produto_barra`: um produto sem SKU não tem como ter tido estoque nunca.
6. **Tipo de Quantidade (Todos/Diferente de Zero/Zerada) filtra a quantidade TOTAL já agregada da
   linha** (todas as variações e empresas selecionadas somadas), não cada empresa
   individualmente — aplicado depois da agregação, em Java.
7. **Ordenação padrão por descrição do produto** (pedido explícito) — a consulta SQL já sai
   ordenada assim e o agrupamento em memória (`LinkedHashMap`) preserva essa ordem sem precisar
   reordenar depois; a tela ainda permite reordenar clicando nas colunas (client-side, mesmo
   padrão de Diferenças de Estoque).

## Os 3 modelos

| Modelo | Granularidade da linha | Colunas | Totaliza? |
|---|---|---|---|
| **Inventário** | Produto (soma variações e empresas) | Descrição, Marca, Referência, Qtd Total, Custo Unitário (`produto.preco_custo`, tenant-wide), Custo Total (`qtdTotal × custoUnitario`) | Sim — Qtd Total e Custo Total no rodapé |
| **Sintético** | Produto (soma variações, abre por empresa) | Descrição, Marca, Referência, **uma coluna por empresa selecionada**, Qtd Total | Sim — cada coluna de quantidade no rodapé |
| **Analítico** | Variação (cor × tamanho) | Descrição, Marca, Referência, Cor, Tamanho, **uma coluna por empresa selecionada**, Qtd Total | Não (não pedido) |

**Colunas dinâmicas por empresa** (Sintético/Analítico): a resposta traz `colunasEmpresa` (id +
nome, ordem fixa) e cada linha traz `qtdPorEmpresa` como lista **posicional**, na mesma ordem —
não um mapa por id. O front usa o índice da coluna pra montar `<th>`/`<td>` e pra ordenar
(`empresa-{indice}` como chave de ordenação sintética).

## Filtros de pesquisa

| Campo | Tipo | Obrigatório | Comportamento |
|---|---|---|---|
| Modelo | Combo | Sim | Inventário (padrão) / Sintético / Analítico |
| Empresas | Multi-select | Não | Só ADMIN. Nenhuma marcada = todas as ativas |
| Marca | Multi-select (`MultiSelectGenerico`) | Não | Valores exatos de `produto.marca`, via `GET /api/v1/produtos/marcas` (distinct, não-vazio, ordenado) |
| Categorias | Multi-select (`MultiSelectGenerico`) | Não | `cfg_categoria_produto` via `GET /api/v1/categorias-produto` (endpoint já existia) |
| Tipo de Quantidade | Combo | Não | Todos (padrão) / Diferente de Zero / Zerada |
| Situação do Produto | Combo | Não | Somente Ativos (padrão) / Somente Inativos / Todos |

## Grid

Cabeçalho/rodapé fixos (`.relatorio-corpo-fixo`), colunas ordenáveis por clique (client-side,
default por descrição). PDF por captura visual (mesmo mecanismo dos demais relatórios) com seção
"Filtros Aplicados" (Modelo, Empresa(s), Marca(s), Categoria(s), Tipo de Quantidade, Situação)
antes da grid.

## Contrato de API

```
GET /api/v1/relatorios/estoque?modelo=INVENTARIO|SINTETICO|ANALITICO
    &idsEmpresa=1&idsEmpresa=2&marcas=X&marcas=Y&idsCategoria=1
    &tipoQuantidade=TODOS|DIFERENTE_DE_ZERO|ZERADA&situacao=ATIVOS|INATIVOS|TODOS
  → { modelo,
      colunasEmpresa: [{idEmpresa,nomeEmpresa}],           // só Sintético/Analítico
      linhasInventario: [{descricaoProduto,marca,referencia,qtdTotal,custoUnitario,custoTotal}],
      linhasSintetico: [{descricaoProduto,marca,referencia,qtdPorEmpresa:[...],qtdTotal}],
      linhasAnalitico: [{descricaoProduto,marca,referencia,variacaoCor,variacaoTamanho,qtdPorEmpresa:[...],qtdTotal}],
      totalInventario: {qtdTotal,custoTotal} | null,
      totalSintetico: {qtdPorEmpresa:[...],qtdTotal} | null }

GET /api/v1/produtos/marcas → ["MARCA A","MARCA B",...]     // novo, distinct não-vazio ordenado
```

Só os campos do `modelo` pedido vêm preenchidos (mesmo padrão do `Totalizador` do Relatório de
Vendas). Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8), qualquer papel.

## Critérios de aceitação (viram testes)

- Dado um produto com 2 variações e estoque em 2 empresas, o Inventário soma tudo num total só e
  calcula o custo total (`qtdTotal × custoUnitario`).
- Dado o mesmo cenário, o Sintético abre uma coluna por empresa com o valor certo, mais uma
  coluna de total.
- Dado um produto com 2 variações, o Analítico traz uma linha por variação, sem totalizador no
  backend.
- Dado um filtro de marca, só produtos daquela marca aparecem.
- Dado um filtro de categoria, só produtos daquela categoria aparecem.
- Dado o filtro Tipo de Quantidade, "Diferente de Zero" e "Zerada" isolam os conjuntos certos.
- Dado nenhum filtro de situação, produtos inativos ficam de fora; "Todos" traz os dois.
- Dado um OPERADOR informando outra empresa, o relatório usa só a própria empresa da sessão
  (coluna única).
- Dado um ADMIN sem filtro de empresa, todas as empresas ativas do tenant viram colunas.
- Dado dois produtos, a ordem padrão é por descrição.
- Dado o catálogo de um tenant, produtos de outro tenant não aparecem (RLS).

Cobertos por `RelatorioEstoqueCrudTest` (11 testes).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `relatorios.estoque.tela`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

Nenhum — tela 100% de leitura sobre tabelas já existentes (`produto`, `produto_barra`,
`produto_estoque`, `produto_categoria`, `cfg_categoria_produto`, `empresa`). Nenhuma migration
nova. Único endpoint novo fora do relatório em si: `GET /api/v1/produtos/marcas`
(`ProdutoController`/`ProdutoService`), usado só para popular o filtro de Marca.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Totalização no modelo Analítico** — não pedido; a granularidade por variação já é o
  suficiente pra conferência linha a linha.
- **Custo por empresa** (Inventário) — o custo é sempre o cadastrado no produto (tenant-wide,
  `produto.preco_custo`), não o custo da última compra por empresa.

## Questões abertas

Nenhuma bloqueante.
