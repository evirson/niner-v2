package com.vetor.niner.financeiro.contacorrente;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de conta corrente (docs/telas/conta-corrente.md). */
public final class ContaCorrenteDtos {

    private ContaCorrenteDtos() {
    }

    /**
     * Corpo de criação/atualização. {@code idContaCorrente} é a própria PK de negócio (o
     * número/código da conta bancária) — obrigatório ao criar; na atualização o código do path
     * prevalece (o campo não é editável, mesmo padrão de {@code cadastros.planocontas}).
     */
    public record ContaCorrenteRequest(
            @NotBlank @Size(max = 20) String idContaCorrente,
            @NotNull Long idEmpresa,
            @NotBlank @Size(max = 20) String idBanco,
            @NotBlank @Size(max = 20) String idAgencia,
            @NotBlank @Size(max = 50) String descricao,
            Boolean ativo,
            LocalDate dataAbertura) {
    }

    public record ContaCorrenteResponse(
            String idContaCorrente,
            long idEmpresa,
            String nomeEmpresa,
            String idBanco,
            String idAgencia,
            String descricao,
            boolean ativo,
            LocalDate dataAbertura,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página — mesmo padrão de {@code cadastros.cliente}. */
    public record PaginaContasCorrente(
            List<ContaCorrenteResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** {@code acao}: {@code "excluido"} ou {@code "inativado"} (vínculo com movimentação). */
    public record ExclusaoContaCorrenteResponse(String acao, String motivo) {
    }

    /** Opção enxuta para selects de outras telas (ex.: Movimentação de Conta Corrente). */
    public record ContaCorrenteOpcaoResponse(String idContaCorrente, String descricao) {
    }
}
