package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.MontagemInvalidaException;

import java.util.Map;

/**
 * CFOP da <b>devolução de compra</b>, derivado do CFOP que o fornecedor usou na nota de entrada.
 *
 * <h2>Por que derivar, e não copiar</h2>
 *
 * <p>O CFOP da nota de entrada é o da <b>venda do fornecedor</b> — {@code 6101} é "venda de
 * produção do estabelecimento, interestadual". Copiá-lo para a nossa nota declararia que <b>nós</b>
 * estamos vendendo produção própria: operação errada, e ou a SEFAZ recusa ou (pior) autoriza uma
 * nota que diz outra coisa do que aconteceu.
 *
 * <p>O que o CFOP do fornecedor faz é <b>determinar</b> o nosso, por esta correspondência (fechada
 * com o dono do produto em 2026-08-20):
 *
 * <table border="1">
 *   <caption>De-para</caption>
 *   <tr><th>Fornecedor usou</th><th>Nossa devolução</th></tr>
 *   <tr><td>5.101 / 5.102 (mesma UF)</td><td><b>5.202</b> — devolução de compra para comercialização</td></tr>
 *   <tr><td>6.101 / 6.102 (outra UF)</td><td><b>6.202</b></td></tr>
 *   <tr><td>5.401 / 5.403 (com ST)</td><td><b>5.411</b></td></tr>
 *   <tr><td>6.401 / 6.403 (com ST)</td><td><b>6.411</b></td></tr>
 * </table>
 *
 * <p><b>O primeiro dígito acompanha o do fornecedor</b> — e isso não é coincidência: se ele emitiu
 * {@code 5xxx}, a operação foi dentro do estado, e a volta também é; se emitiu {@code 6xxx}, a
 * volta é interestadual. Os três últimos dígitos é que viram o código de devolução.
 *
 * <p><b>202, não 201:</b> {@code x.201} é devolução de compra para <b>industrialização</b> e
 * {@code x.202} para <b>comercialização</b>. O Nainer atende varejo, que compra para revender.
 */
final class CfopDevolucaoCompra {

    /**
     * Sufixo (3 dígitos) do fornecedor → sufixo da devolução. O primeiro dígito não entra aqui: ele
     * é copiado do CFOP de origem.
     */
    private static final Map<String, String> DE_PARA = Map.of(
            "101", "202",   // venda de produção → devolução de compra p/ comercialização
            "102", "202",   // venda de mercadoria adquirida de terceiros → idem
            "103", "202",   // venda de produção, entrega a ordem do adquirente
            "104", "202",   // venda de mercadoria de terceiros, entrega a ordem
            "401", "411",   // venda de produção sujeita a ST → devolução de compra com ST
            "403", "411",   // venda de mercadoria de terceiros sujeita a ST → idem
            "405", "411");  // venda de mercadoria de terceiros em regime de ST (substituído)

    private CfopDevolucaoCompra() {
    }

    /**
     * @param cfopDaEntrada CFOP declarado pelo fornecedor no item da NF-e (4 dígitos)
     * @return o CFOP da devolução
     * @throws MontagemInvalidaException quando o CFOP de origem não está no de-para — F11: falhar
     *         aqui, dizendo qual é o código desconhecido, é melhor que transmitir uma nota com CFOP
     *         chutado. Operação fora do de-para (remessa, consignação, industrialização por
     *         encomenda) tem devolução com regra própria e precisa de decisão, não de palpite.
     */
    static String de(String cfopDaEntrada) {
        String limpo = cfopDaEntrada == null ? "" : cfopDaEntrada.trim();
        if (limpo.length() != 4 || !limpo.chars().allMatch(Character::isDigit)) {
            throw new MontagemInvalidaException(
                    "A nota de entrada tem um item com CFOP inválido (\"" + cfopDaEntrada + "\") — "
                            + "não é possível montar a devolução sem saber a operação de origem.");
        }
        String sufixo = DE_PARA.get(limpo.substring(1));
        if (sufixo == null) {
            throw new MontagemInvalidaException(("Não há regra de devolução para o CFOP %s usado pelo fornecedor. "
                    + "O sistema devolve compra para revenda (CFOP de origem 101, 102, 103, 104, 401, 403 ou 405); "
                    + "outras operações — remessa, consignação, industrialização por encomenda — têm regra própria "
                    + "e precisam ser tratadas separadamente.").formatted(limpo));
        }
        return limpo.charAt(0) + sufixo;
    }
}
