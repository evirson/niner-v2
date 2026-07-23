package com.vetor.niner.financeiro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTOs do cadastro de tipo de carteira (prazo/parcelas/taxa do crediário, cartão etc.). O
 * request recebe as moedas em que este tipo de carteira vale ({@code moedas}, lista de
 * {@code idMoeda}, sem ordem) — o servidor substitui {@code moeda_detalhe} por inteiro a
 * cada save (mesmo princípio de {@code produto_categoria}, só que sem índice).
 */
public final class TipoCarteiraDtos {

    private TipoCarteiraDtos() {
    }

    /**
     * {@code taxaAdministradora} é opcional (2026-07-23, coluna sem {@code NOT NULL}) — nem
     * todo tipo de carteira cobra taxa administradora.
     */
    public record TipoCarteiraRequest(
            @NotBlank @Size(max = 60) String nomeCarteira,
            @NotNull Integer prazoPagamento,
            @NotNull Integer pcMinima,
            @NotNull Integer pcMaxima,
            BigDecimal taxaAdministradora,
            List<Long> moedas) {
    }

    public record TipoCarteiraResponse(
            long idCarteira,
            String nomeCarteira,
            int prazoPagamento,
            int pcMinima,
            int pcMaxima,
            BigDecimal taxaAdministradora,
            List<MoedaSelecionada> moedas,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Moeda vinculada a este tipo de carteira ({@code moeda_detalhe}) — sem ordenação. */
    public record MoedaSelecionada(long idMoeda, String nomeMoeda) {
    }

    /** Listagem paginada por número de página — mesmo padrão do resto do domínio. */
    public record PaginaTiposCarteira(
            List<TipoCarteiraResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /**
     * Resultado do DELETE: {@code tipo_carteira} não tem coluna {@code ativo} — sem fallback
     * de inativar (com vínculo em {@code moeda_detalhe}/{@code contas_receber} responde 409).
     */
    public record ExclusaoTipoCarteiraResponse(String acao, String motivo) {
    }
}
