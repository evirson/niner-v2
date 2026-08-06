package com.vetor.niner.configuracao.etiquetaemissao;

import java.math.BigDecimal;
import java.util.List;

public class EtiquetaEmissaoDtos {

    private EtiquetaEmissaoDtos() {
    }

    /** Resultado da busca de produto (modo Individual, 2026-08-05) — QUALQUER produto ativo, com
     * ou sem `produto_barra` já cadastrado (o modo Individual pode CRIAR a variação na hora — ver
     * `ProdutoBarraService`). `nomeVarianteLinha`/`nomeVarianteColuna` (não nulos quando o
     * PRODUTO usa aquela dimensão — configurado no cadastro dele) dizem ao frontend se o
     * respectivo seletor de variação deve aparecer, e como obrigatório. */
    public record ProdutoOpcaoResponse(
            long idProduto, String descricao, String marca, String referencia,
            String nomeVarianteLinha, String nomeVarianteColuna) {
    }

    public record FornecedorOpcaoResponse(long idFornecedor, String razaoSocial) {
    }

    public record OpcaoVarianteResponse(long id, String descricao) {
    }

    public record OpcoesVarianteResponse(List<OpcaoVarianteResponse> variantesLinha, List<OpcaoVarianteResponse> variantesColuna) {
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
            String variacaoLinha,
            String variacaoColuna,
            BigDecimal quantidadeSugerida) {
    }
}
