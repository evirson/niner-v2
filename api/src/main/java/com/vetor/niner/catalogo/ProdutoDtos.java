package com.vetor.niner.catalogo;

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
     * {@code idGrade} é obrigatório (400 se ausente) quando o tenant usa cor/grade em
     * {@code cfg_geral.cfg_usa_cor_grade} (verificado no serviço); ignorado (gravado como
     * {@code null}) quando o tenant não usa — mesmo princípio dos antigos
     * {@code nomeVarianteLinha}/{@code nomeVarianteColuna}.
     *
     * <p>{@code idPerfilFiscal} (2026-08-18, `docs/MODULOFISCAL.md` §6.2/DF3) — opcional aqui de
     * propósito (coluna sem {@code NOT NULL}, {@code produto.id_perfil_fiscal} em V035): quem
     * cobra o preenchimento antes de emitir é a Conformidade Fiscal, não este CRUD.
     */
    public record ProdutoRequest(
            @NotBlank @Size(max = 200) String descricao,
            @Size(max = 60) String marca,
            @Size(max = 60) String referencia,
            @NotNull @DecimalMin("0") BigDecimal precoCusto,
            @NotNull @DecimalMin("0") BigDecimal percentualVenda,
            @NotNull @DecimalMin("0") BigDecimal precoVenda,
            OffsetDateTime dataInicioOferta,
            OffsetDateTime dataFinalOferta,
            BigDecimal precoOferta,
            @Size(max = 20) String codigoNcm,
            BigDecimal pesoBruto,
            BigDecimal pesoLiquido,
            Long idGrade,
            Boolean ativo,
            List<Long> categorias,
            Long idPerfilFiscal) {
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
            Long idGrade,
            String descricaoGrade,
            boolean ativo,
            List<CategoriaSelecionada> categorias,
            List<ProdutoImagemDtos.ImagemResponse> imagens,
            Long idPerfilFiscal,
            String nomePerfilFiscal,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm,
            OffsetDateTime reajustadoEm) {
    }

    /** Listagem paginada por número de página, mesmo padrão de `cadastros.*` (2026-07-21). */
    public record PaginaProdutos(
            List<ProdutoResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Resultado do DELETE: {@code acao} é {@code "excluido"} ou {@code "inativado"}. */
    public record ExclusaoProdutoResponse(String acao, String motivo) {
    }
}
