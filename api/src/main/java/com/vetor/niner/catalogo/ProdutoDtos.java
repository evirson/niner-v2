package com.vetor.niner.catalogo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de produto (docs/telas/produto.md). */
public final class ProdutoDtos {

    private ProdutoDtos() {
    }

    /**
     * Corpo de criação/atualização. {@code categorias} é a lista de {@code idCategoria} na
     * ordem escolhida pelo usuário — o servidor deriva o {@code indice} (0, 1, 2…) da posição
     * na lista, então o cliente não precisa (nem deve) enviar índices manualmente.
     * {@code nomeVarianteLinha}/{@code nomeVarianteColuna} só têm efeito se o tenant usar a
     * respectiva variante em {@code cfg_geral} (verificado no serviço).
     */
    public record ProdutoRequest(
            @NotBlank @Size(max = 200) String descricao,
            @Size(max = 60) String marca,
            @Size(max = 60) String referencia,
            @NotNull @DecimalMin("0") BigDecimal precoCusto,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal percentualVenda,
            @NotNull @DecimalMin("0") BigDecimal precoVenda,
            OffsetDateTime dataInicioOferta,
            OffsetDateTime dataFinalOferta,
            BigDecimal precoOferta,
            @Size(max = 20) String codigoNcm,
            BigDecimal pesoBruto,
            BigDecimal pesoLiquido,
            @Size(max = 60) String nomeVarianteLinha,
            @Size(max = 60) String nomeVarianteColuna,
            Boolean ativo,
            List<Long> categorias) {
    }

    public record CategoriaSelecionada(long idCategoria, String nomeCategoria, int indice) {
    }

    public record ProdutoResponse(
            long idProduto,
            String descricao,
            String marca,
            String referencia,
            BigDecimal precoCusto,
            BigDecimal percentualVenda,
            BigDecimal precoVenda,
            OffsetDateTime dataInicioOferta,
            OffsetDateTime dataFinalOferta,
            BigDecimal precoOferta,
            String codigoNcm,
            BigDecimal pesoBruto,
            BigDecimal pesoLiquido,
            String nomeVarianteLinha,
            String nomeVarianteColuna,
            boolean ativo,
            List<CategoriaSelecionada> categorias,
            List<ProdutoImagemDtos.ImagemResponse> imagens,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página, mesmo padrão de `cadastros.*` (2026-07-21). */
    public record PaginaProdutos(
            List<ProdutoResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Resultado do DELETE: {@code acao} é {@code "excluido"} ou {@code "inativado"}. */
    public record ExclusaoProdutoResponse(String acao, String motivo) {
    }
}
