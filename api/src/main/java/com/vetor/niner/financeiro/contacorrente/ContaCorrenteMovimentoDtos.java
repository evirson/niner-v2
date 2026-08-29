package com.vetor.niner.financeiro.contacorrente;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs de lançamento (extrato manual) de Conta Corrente (docs/telas/conta-corrente-movimento.md). */
public final class ContaCorrenteMovimentoDtos {

    private ContaCorrenteMovimentoDtos() {
    }

    /**
     * {@code creditoDebito} é texto validado contra "C"/"D" no serviço (o ENUM do banco não tem
     * acento, mas seguimos o mesmo estilo de {@code PlanoContasDtos.tipoMovimento} — texto, não
     * enum Java — pra não duplicar o conjunto de valores em dois lugares).
     */
    public record ContaCorrenteMovimentoRequest(
            @NotBlank @Size(max = 20) String idContaCorrente,
            @NotBlank @Size(max = 20) String idPlanoContas,
            @NotNull OffsetDateTime dataMovimento,
            @NotBlank @Size(max = 25) String numeroDocumento,
            @NotBlank String creditoDebito,
            Boolean compensado,
            @NotNull @DecimalMin(value = "0.01") BigDecimal valor,
            @Size(max = 1000) String observacao) {
    }

    /**
     * {@code idContaPagar} (2026-08-15) só vem preenchido nos movimentos **gerados
     * automaticamente** pela baixa de uma conta a pagar
     * ({@code ContaPagarService.sincronizarMovimentoDeDinheiro}) — lançamento digitado nesta tela
     * sempre traz {@code null}. É o que permite a tela sinalizar a origem na grid e não oferecer
     * editar/excluir num lançamento que pertence a outra tela (ver {@code
     * ContaCorrenteMovimentoService.exigirLancamentoManual}).
     */
    public record ContaCorrenteMovimentoResponse(
            long localizador,
            String idContaCorrente,
            String descricaoContaCorrente,
            String idPlanoContas,
            String descricaoPlanoContas,
            OffsetDateTime dataMovimento,
            String numeroDocumento,
            String creditoDebito,
            boolean compensado,
            BigDecimal valor,
            String observacao,
            Long idContaPagar,
            /**
             * ⚠️ Preenchido = <b>depósito de uma sangria de caixa</b>, e a tela precisa saber
             * (auditoria 2026-08-29). A regra do projeto para tabela de CRUD manual que também
             * recebe lançamento automático tem três partes: (a) expor a marca de origem, (b)
             * recusar a alteração com 409, (c) <b>esconder a ação na grid</b>. A sangria só tinha
             * a (b) — o extrato oferecia o lápis e a lixeira, o operador preenchia o formulário
             * inteiro e levava 409 no Salvar. Caminho oferecido que nunca podia funcionar é pior
             * que caminho não oferecido.
             */
            Long idSangria,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página — mesmo padrão de {@code cadastros.cliente}. */
    public record PaginaContaCorrenteMovimento(
            List<ContaCorrenteMovimentoResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Sem fallback de inativar — lançamento não tem {@code ativo}, exclusão é sempre definitiva. */
    public record ExclusaoContaCorrenteMovimentoResponse(String acao) {
    }
}
