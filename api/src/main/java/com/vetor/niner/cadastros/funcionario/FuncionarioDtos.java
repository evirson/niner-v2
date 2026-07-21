package com.vetor.niner.cadastros.funcionario;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de funcionário (docs/telas/funcionario.md). */
public final class FuncionarioDtos {

    private FuncionarioDtos() {
    }

    /**
     * Corpo de criação/atualização. Só {@code nome} é obrigatório estruturalmente (NOT NULL
     * no banco) — os demais campos são opcionais, exceto quando marcados obrigatórios pela
     * configuração de tela do tenant ({@code cfg_tela_campo}).
     */
    public record FuncionarioRequest(
            @NotBlank @Size(max = 160) String nome,
            @Size(max = 20) String cpf,
            @Size(max = 30) String telefone,
            @Size(max = 80) String cargo,
            BigDecimal percComissao,
            Boolean ativo) {
    }

    public record FuncionarioResponse(
            long idFuncionario,
            String nome,
            String cpf,
            String telefone,
            String cargo,
            BigDecimal percComissao,
            boolean ativo,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /**
     * Listagem paginada por número de página, ordenada por {@code nome} por padrão — mesmo
     * padrão de {@code cadastros.cliente} (permite pular direto para qualquer página e
     * ordenar por qualquer coluna).
     */
    public record PaginaFuncionarios(
            List<FuncionarioResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Resultado do DELETE: {@code acao} é {@code "excluido"} ou {@code "inativado"}. */
    public record ExclusaoFuncionarioResponse(String acao, String motivo) {
    }
}
