package com.vetor.niner.configuracao.etiquetaemissao;

import java.math.BigDecimal;

public class EtiquetaEmissaoDtos {

    private EtiquetaEmissaoDtos() {
    }

    /** Resultado da busca de produto (modo Individual, 2026-08-05) — QUALQUER produto ativo, com
     * ou sem `produto_barra` já cadastrado (o modo Individual pode CRIAR a variação na hora — ver
     * `ProdutoBarraService`). {@code idGrade} (não nulo quando o PRODUTO usa cor/grade) diz ao
     * frontend se os seletores de cor/tamanho devem aparecer (como obrigatórios) — o de tamanho
     * é montado a partir de `GET /api/v1/grades/{idGrade}` (lista já ordenada), o de cor a partir
     * de `GET /api/v1/cores` (tenant inteiro, sem restrição por produto). */
    public record ProdutoOpcaoResponse(
            long idProduto, String descricao, String marca, String referencia, Long idGrade) {
    }

    public record FornecedorOpcaoResponse(long idFornecedor, String razaoSocial) {
    }

    /** Uma variação pronta pra virar linha da grade de emissão — mesmo shape de
     * `EtiquetaConfigDtos.ProdutoExemploResponse` (pra o frontend reaproveitar
     * `CampoEtiquetaVisual` sem conversão), + `quantidadeSugerida` (nula no modo Individual, que
     * pede a quantidade direto do usuário; calculada nos modos Por Entradas/Por Estoques). */
    public record ProdutoEmissaoResponse(
            long idVariacao,
            String sku,
            String descricao,
            String marca,
            String referencia,
            BigDecimal precoVenda,
            String variacaoCor,
            String variacaoTamanho,
            BigDecimal quantidadeSugerida) {
    }
}
