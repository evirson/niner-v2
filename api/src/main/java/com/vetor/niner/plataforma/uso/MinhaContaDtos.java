package com.vetor.niner.plataforma.uso;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** DTOs do painel <i>Minha Conta</i> (docs/telas/painel-assinatura.md). */
public final class MinhaContaDtos {

    private MinhaContaDtos() {
    }

    /** Situação da cota na competência corrente. */
    public enum SituacaoUso {
        /** Abaixo de 80% da cota. */
        NORMAL,
        /** 80% ou mais, ainda dentro da cota. */
        ATENCAO,
        /** Cota estourada, consumindo a tolerância. */
        TOLERANCIA,
        /** Tolerância esgotada — a próxima venda é recusada. */
        BLOQUEADO
    }

    public record PlanoAtual(
            long idPlano, String nome, boolean gratuito, BigDecimal precoMensal, BigDecimal precoAnual,
            String ciclo, Integer limiteVendasMes) {
    }

    public record UsoAtual(
            LocalDate competencia, int qtdVendas, Integer limite, Integer restantes,
            int tolerancia, Integer toleranciaRestante, SituacaoUso situacao, LocalDate zeraEm) {
    }

    public record UsoMes(LocalDate competencia, int qtdVendas) {
    }

    public record FaixaSugerida(String nome, Integer limiteVendasMes, BigDecimal precoMensal, BigDecimal precoAnual) {
    }

    public record EmpresaDoTenant(
            long idEmpresa, int codigoEmpresa, String razaoSocial, String nomeFantasia,
            String cnpj, String cidade, String estado, boolean matriz, boolean ativo) {
    }

    public record MinhaContaResponse(
            PlanoAtual plano, UsoAtual uso, List<UsoMes> historico, FaixaSugerida faixaRecomendada,
            List<EmpresaDoTenant> empresas) {
    }

}
