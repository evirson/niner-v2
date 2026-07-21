package com.vetor.niner.cadastros.planocontas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de plano de contas (docs/telas/plano-contas.md). */
public final class PlanoContasDtos {

    private PlanoContasDtos() {
    }

    /**
     * Corpo de criação/atualização. {@code codigo} é a própria PK de negócio
     * ({@code id_plano_contas}) — obrigatório ao criar; na atualização o código do path
     * prevalece (o campo não é editável). {@code tipoMovimento} é texto validado contra
     * CRÉDITO/DÉBITO/NEUTRO no serviço (o ENUM do banco tem acentos, então não usamos enum
     * Java aqui). Todos os campos da tabela são NOT NULL — não há campo opcional.
     */
    public record PlanoContasRequest(
            @NotBlank @Size(max = 20) String codigo,
            @NotBlank @Size(max = 120) String descricao,
            @NotBlank String tipoMovimento,
            Boolean incluiDre,
            Boolean incluiFluxoCaixa) {
    }

    public record PlanoContasResponse(
            String idPlanoContas,
            String descricao,
            String tipoMovimento,
            boolean incluiDre,
            boolean incluiFluxoCaixa,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página — mesmo padrão de {@code cadastros.cliente}. */
    public record PaginaPlanosContas(
            List<PlanoContasResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /**
     * Resultado do DELETE: aqui {@code acao} só pode ser {@code "excluido"} —
     * {@code cfg_plano_contas} não tem coluna {@code ativo}, então não existe o fallback de
     * inativar (com vínculo a API responde 409 e nada muda).
     */
    public record ExclusaoPlanoContasResponse(String acao, String motivo) {
    }
}
