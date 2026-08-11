package com.vetor.niner.catalogo;

import java.math.BigDecimal;

public class ProdutoBarraDtos {

    private ProdutoBarraDtos() {
    }

    /** {@code ean} (2026-08-11, Entrada de Produtos por Compra) é opcional e só é aplicado
     *  quando a variação é criada agora — se já existe uma variação pra essa combinação
     *  produto/cor/tamanho, o {@code ean} enviado é ignorado (não sobrescreve o que já está
     *  gravado). Sempre {@code null} nas chamadas de Emissão de Etiqueta (que não têm de onde
     *  vir um código de barras do fabricante). */
    public record CriarVariacaoRequest(Long idCor, Long idTamanho, String ean) {
    }

    /** Uma variação (linha do `produto_barra`) já com os dados do produto/cor/tamanho resolvidos —
     * mesmo shape usado em outros lugares que mostram "produto + variação" (ex.: `EtiquetaConfigDtos
     * .ProdutoExemploResponse`), pra quem consome poder reaproveitar sem conversão. {@code ean}
     * (2026-08-11) é o código de barras real do fabricante, nullable — distinto do {@code sku}
     * interno, sempre gerado. */
    public record ProdutoBarraResponse(
            long idVariacao,
            String sku,
            String ean,
            String descricao,
            String marca,
            String referencia,
            BigDecimal precoVenda,
            String variacaoCor,
            String variacaoTamanho) {
    }
}
