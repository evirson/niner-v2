package com.vetor.niner.fiscal.perfil;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de Perfis Fiscais (docs/telas/fiscal-perfil.md). */
public final class PerfilFiscalDtos {

    private PerfilFiscalDtos() {
    }

    /** Espelha o ENUM {@code tipo_destinatario_fiscal} (V035). */
    public enum TipoDestinatarioFiscal {
        CONSUMIDOR_FINAL, CONTRIBUINTE, NAO_CONTRIBUINTE
    }

    /**
     * Espelha o ENUM {@code tipo_operacao_fiscal} (V035) — <b>11 valores, dos quais o v1 usa
     * só os dois primeiros</b> (DF35). Os outros nove nasceram junto porque acrescentar valor a
     * ENUM depois é {@code ALTER TYPE}; a API aceita qualquer um, mas a tela esconde os que o
     * sistema ainda não emite (§4.2 do estudo fiscal).
     */
    public enum TipoOperacaoFiscal {
        VENDA_CONSUMIDOR, DEVOLUCAO_VENDA,
        VENDA_CONTRIBUINTE, VENDA_NAO_PRESENCIAL, TRANSFERENCIA, DEVOLUCAO_FORNECEDOR,
        REMESSA_CONSERTO, RETORNO_CONSERTO, BAIXA_PERDA, BONIFICACAO, COMPLEMENTAR
    }

    /**
     * Uma regra dentro do perfil (contexto → saída tributária). {@code crt} restrito a 1/2/4
     * (DF37); {@code cstIcms} só é aceito quando {@code crt == 2} — o mesmo CHECK do banco
     * (`cfg_perfil_fiscal_regra_icms_crt_ck`), verificado de novo no serviço porque o banco não
     * deve ser a primeira linha de defesa (mensagem legível em vez da violação crua).
     */
    public record PerfilFiscalRegraRequest(
            @NotNull Integer crt,
            @NotBlank @Size(max = 4) String ufDestino,
            @NotNull TipoDestinatarioFiscal tipoDestinatario,
            @NotNull TipoOperacaoFiscal tipoOperacao,
            @NotBlank @Size(min = 4, max = 4) String cfop,
            @Size(min = 2, max = 2) String cstIcms,
            @Size(min = 3, max = 3) String csosn,
            BigDecimal aliquotaIcms,
            BigDecimal percReducaoBc,
            BigDecimal mvaSt,
            BigDecimal aliquotaFcp,
            @Size(min = 2, max = 2) String cstPis,
            BigDecimal aliquotaPis,
            @Size(min = 2, max = 2) String cstCofins,
            BigDecimal aliquotaCofins,
            @Size(min = 3, max = 3) String cstIbscbs,
            @Size(min = 6, max = 6) String cclasstrib,
            String codigoBeneficio) {
    }

    public record PerfilFiscalRegraResponse(
            long idRegra,
            int crt,
            String ufDestino,
            TipoDestinatarioFiscal tipoDestinatario,
            TipoOperacaoFiscal tipoOperacao,
            String cfop,
            String cstIcms,
            String csosn,
            BigDecimal aliquotaIcms,
            BigDecimal percReducaoBc,
            BigDecimal mvaSt,
            BigDecimal aliquotaFcp,
            String cstPis,
            BigDecimal aliquotaPis,
            String cstCofins,
            BigDecimal aliquotaCofins,
            String cstIbscbs,
            String cclasstrib,
            String codigoBeneficio) {
    }

    /**
     * Corpo de gravação. {@code regras} é substituída por completo a cada save — mesmo mecanismo
     * de "apaga e reinsere" de {@code ProdutoService.salvarCategorias}, e não um PATCH por linha.
     */
    public record PerfilFiscalRequest(
            @NotBlank @Size(max = 120) String nome,
            @Size(max = 255) String descricao,
            @NotNull Boolean ativo,
            @NotEmpty List<@Valid PerfilFiscalRegraRequest> regras) {
    }

    public record PerfilFiscalResponse(
            long idPerfilFiscal,
            String nome,
            String descricao,
            boolean ativo,
            List<PerfilFiscalRegraResponse> regras,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Linha da grade — sem as regras, que só carregam no popup de edição. */
    public record PerfilFiscalListaResponse(
            long idPerfilFiscal,
            String nome,
            String descricao,
            boolean ativo,
            int quantidadeRegras,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    public record PaginaPerfisFiscais(
            List<PerfilFiscalListaResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Opção enxuta para o {@code <select>} de Produto — sem paginação, sem checagem de papel. */
    public record PerfilFiscalOpcaoResponse(long idPerfilFiscal, String nome) {
    }

    /**
     * Resultado do DELETE: exclusão real quando nenhum produto aponta para o perfil, fallback
     * para inativar (409 + oferta de inativar) quando há vínculo — padrão do projeto.
     */
    public record ExclusaoPerfilFiscalResponse(String acao, String motivo) {
    }
}
