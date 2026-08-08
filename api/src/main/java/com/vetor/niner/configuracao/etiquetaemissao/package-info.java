/**
 * Emissão de Etiqueta de Produtos (2026-08-05) — 1ª implementação real desta área (era só
 * placeholder no menu desde 2026-08-04). Seleciona produtos/quantidades de 3 formas diferentes
 * (Individual, Por Entradas, Por Estoques — sempre resultando no mesmo shape,
 * {@code ProdutoEmissaoResponse}, compatível com {@code ProdutoExemplo} do frontend pra
 * reaproveitar {@code CampoEtiquetaVisual} direto) e imprime em lote usando um layout já criado
 * em Configuração de Etiqueta de Produtos ({@code cfg_etiqueta_config}, sem endpoint novo — reúsa
 * {@code GET /api/v1/etiquetas-config}/{@code /{id}}).
 *
 * <p><b>Individual</b> (revisado 2026-08-05; cor/grade 2026-08-08) — busca QUALQUER produto
 * ativo, com ou sem {@code produto_barra} já cadastrado. Se o produto usa grade
 * ({@code produto.id_grade} não nulo), os seletores de cor e tamanho viram obrigatórios na tela
 * — cor vem de {@code GET /api/v1/cores} (tenant inteiro, + "+ Nova cor" via
 * {@code POST /api/v1/cores}, válvula de escape enquanto a Entrada de Produtos não existe),
 * tamanho vem de {@code GET /api/v1/grades/{idGrade}} (só os tamanhos daquela grade, já
 * ordenados). No "Adicionar", chama {@code ProdutoBarraService.obterOuCriar} (módulo
 * {@code catalogo}, não este): acha a variação se já existir, ou **cria na hora** (gera o SKU
 * via {@code gerar_ean13_interno()}) se ainda não existir — não é mais um bloqueio precisar de
 * SKU pré-cadastrado pra emitir etiqueta de um produto novo.
 *
 * <p><b>Por Entradas</b> — soma {@code produto_movimento_detalhe.qtd_produto} (crédito) de
 * {@code produto_movimento_mestre.tipo_movimento = 'COMPRA'} no período/fornecedor/nota fiscal
 * informados (ao menos 1 filtro obrigatório, senão seria uma consulta sem limite). ⚠️ Nenhum
 * serviço do sistema grava `COMPRA` ainda ("Entrada de Produtos por Compra" é outra área de
 * Implementações Futuras, não construída) — o filtro é real e funciona contra o schema existente,
 * só não vai ter dado até aquela tela (ou uma carga manual) existir.
 *
 * <p><b>Por Estoques</b> — {@code produto_estoque.qtd_estoque} (só {@code > 0}) da empresa
 * escolhida (obrigatória; ADMIN escolhe explicitamente, OPERADOR sempre a própria empresa ativa
 * via claim {@code eid} — mesmo padrão de todo relatório do sistema) + categoria opcional.
 *
 * <p>A grade de produtos/quantidades selecionados (excluir item, editar quantidade) é 100%
 * estado local do frontend — não existe endpoint de "grade" nem qualquer persistência até o
 * clique em "Emitir Etiquetas", que só então monta a lista achatada de etiquetas e imprime
 * (mesmo mecanismo client-side de {@code EtiquetaConfigForm.tsx}, generalizado pra produtos
 * diferentes por etiqueta em vez de N cópias do mesmo).
 */
package com.vetor.niner.configuracao.etiquetaemissao;
