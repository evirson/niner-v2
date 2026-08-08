package com.vetor.niner.catalogo;

import java.math.BigDecimal;

public class ProdutoBarraDtos {

    private ProdutoBarraDtos() {
    }

    public record CriarVariacaoRequest(Long idCor, Long idTamanho) {
    }

    /** Uma variação (linha do `produto_barra`) já com os dados do produto/cor/tamanho resolvidos —
     * mesmo shape usado em outros lugares que mostram "produto + variação" (ex.: `EtiquetaConfigDtos
     * .ProdutoExemploResponse`), pra quem consome poder reaproveitar sem conversão. */
    public record ProdutoBarraResponse(
            long idVariacao,
            String sku,
            String descricao,
            String marca,
            String referencia,
            BigDecimal precoVenda,
            String variacaoCor,
            String variacaoTamanho) {
    }
}
