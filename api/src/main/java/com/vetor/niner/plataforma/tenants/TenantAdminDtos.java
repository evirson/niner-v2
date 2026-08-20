package com.vetor.niner.plataforma.tenants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs da lista/ficha de tenants no backoffice (R17). */
public final class TenantAdminDtos {

    private TenantAdminDtos() {
    }

    /**
     * Uma conta na lista. Traz plano e <b>uso do mês</b> juntos de propósito: no modelo de cota
     * (ADR-015), "quem é o cliente" e "quanto ele está usando" são a mesma pergunta.
     */
    public record TenantResumo(
            long idTenant, String nomeConta, String slug, String emailContato, String status,
            String plano, boolean gratuito, Integer limiteVendasMes, int vendasNoMes, Integer percentualCota,
            int qtdEmpresas, int qtdUsuarios, BigDecimal mrr, OffsetDateTime criadoEm) {
    }

    public record PaginaTenants(List<TenantResumo> itens, long total, int pagina, int limite) {
    }

    /** Ficha: o resumo + histórico de uso e as faturas — o que o suporte precisa numa ligação. */
    public record TenantDetalhe(
            TenantResumo resumo, List<UsoMensal> historico, List<FaturaResumo> faturas, List<EmpresaResumo> empresas) {
    }

    public record UsoMensal(LocalDate competencia, int qtdVendas) {
    }

    public record FaturaResumo(
            long idFatura, LocalDate competencia, String plano, BigDecimal valor, String situacao,
            OffsetDateTime pagoEm) {
    }

    public record EmpresaResumo(int codigoEmpresa, String razaoSocial, String cnpj, String cidade, String estado) {
    }
}
