package com.vetor.niner.financeiro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTOs do cadastro de moeda (forma de recebimento — dinheiro, PIX, cartão, crediário...).
 * O vínculo com tipo de carteira ({@code moeda_detalhe}) é gerido pela tela de Tipo de
 * Carteira, não por aqui — esta tela cuida só dos campos próprios da moeda.
 */
public final class MoedaDtos {

    private MoedaDtos() {
    }

    /**
     * {@code percDesconto}/{@code percAcrescimo} são opcionais (2026-07-23, coluna sem
     * {@code NOT NULL}) — o desconto e o acréscimo nunca coexistem no mesmo registro
     * ({@code MoedaService.validar} rejeita os dois preenchidos ao mesmo tempo).
     */
    public record MoedaRequest(
            @NotBlank @Size(max = 60) String nomeMoeda,
            BigDecimal percDesconto,
            BigDecimal percAcrescimo) {
    }

    public record MoedaResponse(
            long idMoeda,
            String nomeMoeda,
            BigDecimal percDesconto,
            BigDecimal percAcrescimo,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página — mesmo padrão do resto do domínio. */
    public record PaginaMoedas(
            List<MoedaResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /**
     * Resultado do DELETE: {@code moeda} não tem coluna {@code ativo} — sem fallback de
     * inativar (com vínculo em {@code moeda_detalhe}/{@code caixa_detalhe} responde 409).
     */
    public record ExclusaoMoedaResponse(String acao, String motivo) {
    }
}
