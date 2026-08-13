package com.vetor.niner.cadastros.planocontas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de plano de contas (docs/telas/plano-contas.md), revisado 2026-07-31
 *  (DRE/DFC por conta, hierarquia de 4 níveis via máscara). */
public final class PlanoContasDtos {

    private PlanoContasDtos() {
    }

    /**
     * Corpo de criação/atualização. {@code codigo} é a própria PK de negócio
     * ({@code id_plano_contas}) — obrigatório ao criar, no formato {@code 9.99.999}; na
     * atualização o código do path prevalece (o campo não é editável). {@code tipoMovimento}
     * (CREDITO/DEBITO/NEUTRO) e {@code natureza} (SINTETICA/ANALITICA) são texto validado no
     * serviço contra os ENUMs do banco. {@code grupoDre}/{@code grupoDfc} só são exigidos
     * quando {@code incluiDre}/{@code incluiFluxoCaixa} são {@code true} — o servidor força
     * "NAO_APLICA" quando a flag correspondente é falsa, ignorando o que vier no corpo.
     * {@code sinal} e {@code aceitaLancamento} não entram aqui — são sempre derivados no
     * servidor a partir de {@code tipoMovimento}/{@code natureza} (nunca informados pelo
     * cliente). {@code ativo}/{@code padraoSistema} também não entram — não são editáveis via
     * formulário (mesmo padrão de Cliente/Funcionário/Fornecedor: só o fluxo de exclusão
     * alterna {@code ativo}).
     */
    public record PlanoContasRequest(
            @NotBlank @Size(max = 20) String codigo,
            @NotBlank @Size(min = 3, max = 120) String descricao,
            @Size(max = 40) String descricaoCurta,
            @NotBlank String tipoMovimento,
            @NotBlank String natureza,
            Boolean incluiDre,
            String grupoDre,
            Boolean incluiFluxoCaixa,
            String grupoDfc,
            @Size(max = 500) String observacao) {
    }

    public record PlanoContasResponse(
            String idPlanoContas,
            String descricao,
            String descricaoCurta,
            String tipoMovimento,
            String natureza,
            int nivel,
            String idPlanoContasPai,
            boolean incluiDre,
            String grupoDre,
            boolean incluiFluxoCaixa,
            String grupoDfc,
            int sinal,
            boolean aceitaLancamento,
            boolean padraoSistema,
            boolean ativo,
            String observacao,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página — mesmo padrão de {@code cadastros.cliente}. */
    public record PaginaPlanosContas(
            List<PlanoContasResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /**
     * Resultado do DELETE: {@code "excluido"} quando não há nenhum obstáculo, ou
     * {@code "inativado"} (com {@code motivo}) quando a conta é padrão do sistema, tem contas
     * filhas, ou está vinculada a fornecedor/contas a pagar/caixa/conta corrente — mesmo
     * padrão de fallback já usado em Cliente/Funcionário/Fornecedor. {@code ativo} agora
     * existe na tabela (novo em 2026-07-31), então este cadastro deixou de ser a exceção que
     * só excluía de verdade.
     */
    public record ExclusaoPlanoContasResponse(String acao, String motivo) {
    }
}
