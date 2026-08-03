package com.vetor.niner.financeiro;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DTOs do cadastro de tipo de carteira (categoria/prazo/parcelas/taxa/desconto/acréscimo de
 * cada forma de pagamento). Absorveu o cadastro de {@code moeda} em 2026-07-28 — {@code
 * percDesconto}/{@code percAcrescimo} vieram de lá; não existe mais vínculo N:N (a mesma
 * bandeira em categorias diferentes é uma linha por categoria — ex.: "HIPER" em
 * CARTAO_DEBITO e "HIPER" em CARTAO_CREDITO, prazos/taxas independentes).
 */
public final class TipoCarteiraDtos {

    private TipoCarteiraDtos() {
    }

    /**
     * Categoria fixa do tipo de carteira (2026-07-23) — permite ao histórico do cliente
     * separar programaticamente parcelas de crediário das demais formas de pagamento
     * (nome_carteira é texto livre e não serve pra isso).
     */
    public enum CategoriaCarteira {
        AVISTA, CARTAO_DEBITO, CARTAO_CREDITO, CREDIARIO, VALE_MERCADORIA
    }

    /**
     * {@code taxaAdministradora}/{@code percDesconto}/{@code percAcrescimo} são opcionais
     * (colunas sem {@code NOT NULL}) — nem todo tipo de carteira cobra taxa, e desconto/
     * acréscimo nunca coexistem com valor positivo ao mesmo tempo (checagem por valor > 0,
     * não presença — 2026-07-23, mesma regra herdada de {@code moeda}).
     *
     * <p>{@code permiteReceberCrediario} (2026-07-29, RN007 do Recebimento de Crediário): só
     * carteiras marcadas aqui aparecem como opção de pagamento naquela tela.
     */
    public record TipoCarteiraRequest(
            @NotBlank @Size(max = 60) String nomeCarteira,
            @NotNull CategoriaCarteira categoriaCarteira,
            @NotNull Integer prazoPagamento,
            @NotNull Integer pcMinima,
            @NotNull Integer pcMaxima,
            BigDecimal taxaAdministradora,
            BigDecimal percDesconto,
            BigDecimal percAcrescimo,
            @NotNull Boolean permiteReceberCrediario) {
    }

    public record TipoCarteiraResponse(
            long idCarteira,
            String nomeCarteira,
            CategoriaCarteira categoriaCarteira,
            int prazoPagamento,
            int pcMinima,
            int pcMaxima,
            BigDecimal taxaAdministradora,
            BigDecimal percDesconto,
            BigDecimal percAcrescimo,
            boolean permiteReceberCrediario,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página — mesmo padrão do resto do domínio. */
    public record PaginaTiposCarteira(
            List<TipoCarteiraResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /**
     * Resultado do DELETE: {@code tipo_carteira} não tem coluna {@code ativo} — sem fallback
     * de inativar (com vínculo em {@code contas_receber}/{@code caixa_detalhe} responde 409).
     */
    public record ExclusaoTipoCarteiraResponse(String acao, String motivo) {
    }
}
