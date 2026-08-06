package com.vetor.niner.cadastros.crm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class CrmDtos {

    private CrmDtos() {
    }

    /** Item de dropdown (categoria de cliente/produto, variação de linha/coluna) — id + rótulo. */
    public record OpcaoCrm(long id, String rotulo) {
    }

    public record OpcoesCrmResponse(
            List<OpcaoCrm> categoriasCliente,
            List<OpcaoCrm> categoriasProduto,
            List<OpcaoCrm> variantesLinha,
            List<OpcaoCrm> variantesColuna) {
    }

    /** Uma linha do resultado — todas as colunas sempre calculadas; o frontend decide quais
     * entram na planilha exportada (checkbox "Dados do Cliente Para Geração"). {@code
     * valorTotalCompras}/{@code ticketMedio}/{@code diasSemUltimaCompra} (2026-08-05) somam/
     * derivam do mesmo histórico usado em {@code primeiraCompra}/{@code ultimaCompra}/{@code
     * numeroCompras} — sempre completo, nunca recortado pelo filtro "Período de Compras". Cliente
     * sem nenhuma compra: {@code valorTotalCompras} zero, {@code ticketMedio}/{@code
     * diasSemUltimaCompra} nulos (não dá pra tirar média nem contar dias de uma última compra que
     * não existe). */
    public record ClienteCrmResponse(
            long idCliente,
            String nome,
            LocalDate dataNascimento,
            String genero,
            String email,
            String celular,
            LocalDate primeiraCompra,
            LocalDate ultimaCompra,
            long numeroCompras,
            BigDecimal valorTotalCompras,
            BigDecimal ticketMedio,
            Long diasSemUltimaCompra) {
    }
}
