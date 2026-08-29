package com.vetor.niner.financeiro.sangria;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Contratos da Sangria de Caixa (V094). Ver {@link SangriaService} para as regras. */
public final class SangriaDtos {

    private SangriaDtos() {
    }

    /**
     * O pedido de sangria.
     *
     * <p>⚠️ Não recebe {@code idCaixa}: a sangria é sempre do caixa <b>aberto do próprio usuário</b>,
     * resolvido no servidor. Aceitar o id pelo corpo deixaria um operador tirar dinheiro do caixa de
     * outra pessoa por chamada direta à API — e a tela nunca teria como oferecer isso.
     */
    public record SangriaRequest(
            @NotBlank(message = "Informe a conta corrente de destino.")
            String idContaCorrente,
            @NotNull @DecimalMin(value = "0.01", message = "O valor da sangria deve ser maior que zero.")
            BigDecimal valor,
            @NotBlank(message = "Informe o plano de contas do lançamento.")
            String idPlanoContas,
            @Size(max = 200)
            String observacao) {
    }

    /** Uma sangria já registrada — o que a grid da tela mostra. */
    public record SangriaResponse(
            long idSangria,
            OffsetDateTime dataSangria,
            BigDecimal valor,
            String idContaCorrente,
            String descricaoContaCorrente,
            String nomeUsuario,
            String observacao) {
    }

    /**
     * O que a tela precisa saber antes de deixar o operador digitar.
     *
     * <p>{@code disponivel} é o dinheiro que a sangria pode tirar — saldo inicial mais entradas
     * menos saídas <b>da carteira de abertura</b> do caixa. Vem do servidor porque é ele quem
     * decide (a validação é refeita no POST); a tela usa só para avisar antes do erro.
     */
    public record SangriaContextoResponse(
            boolean caixaAberto,
            Long idCaixa,
            String nomeCarteira,
            BigDecimal disponivel) {
    }
}
