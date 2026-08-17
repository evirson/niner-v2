package com.vetor.niner.fiscal.motor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Contrato do motor tributário (docs/MODULOFISCAL.md §8). Tudo aqui é imutável e sem I/O — a
 * resolução do perfil fiscal no banco acontece <b>antes</b>, e a regra já chega resolvida em cada
 * item. É isso que torna o motor testável por tabela, sem Testcontainers.
 *
 * <p><b>DF37 — o Niner atende só MEI e Simples Nacional.</b> Lucro Real e Lucro Presumido estão
 * fora do produto, e com eles saíram: o regime de apuração (o CRT já diz tudo), a alíquota
 * ad-valorem de PIS/COFINS (sempre dentro do DAS) e o IPI (idem). O que restou de tributo
 * realmente calculado é ICMS, os CST de tratamento próprio de PIS/COFINS e o IBS/CBS da reforma.
 */
public final class MotorTributarioDtos {

    private MotorTributarioDtos() {
    }

    // ---------------------------------------------------------------- entrada

    /**
     * O que a EMPRESA é. Só o {@code crt} sobrou como eixo: <b>1</b> Simples Nacional, <b>2</b>
     * Simples com excesso de sublimite, <b>4</b> MEI. O CRT 3 (Regime Normal) é recusado — não é
     * caso não implementado, é escopo de produto (DF37).
     */
    public record ContextoFiscalEmpresa(int crt, String ufEmitente) {
    }

    /** O que está sendo vendido/devolvido, já com desconto rateado por item pelo PDV. */
    public record OperacaoFiscal(
            TipoOperacao tipoOperacao,
            String ufDestino,
            TipoDestinatario tipoDestinatario,
            List<ItemOperacao> itens) {
    }

    public record ItemOperacao(
            int nItem,
            BigDecimal quantidade,
            BigDecimal valorUnitario,
            BigDecimal valorDesconto,
            BigDecimal valorAcrescimo,
            RegraFiscal regra) {
    }

    /**
     * Uma linha de {@code cfg_perfil_fiscal_regra} já resolvida para o contexto (CRT × UF ×
     * destinatário × operação), acrescida do que veio de {@code cfg_cclasstrib}.
     *
     * <p>{@code cstIcms} só é válido para <b>CRT 2</b> (§8.2). {@code aliquotaPis}/
     * {@code aliquotaCofins} só valem para os CST de tratamento próprio (04 monofásico, 06
     * alíquota zero) — no CST 99, que é o caso normal, tudo é zero.
     */
    public record RegraFiscal(
            String cfop,
            String cstIcms,        // só CRT 2
            String csosn,          // CRT 1/2/4
            BigDecimal aliquotaIcms,
            BigDecimal percReducaoBc,
            BigDecimal aliquotaFcp,
            String cstPis,
            BigDecimal aliquotaPis,
            String cstCofins,
            BigDecimal aliquotaCofins,
            String cstIbsCbs,
            String cClassTrib,
            BigDecimal percReducaoIbs,
            BigDecimal percReducaoCbs,
            String codigoBeneficio) {
    }

    public enum TipoOperacao { VENDA, DEVOLUCAO, TRANSFERENCIA, REMESSA, BONIFICACAO }

    public enum TipoDestinatario { CONSUMIDOR_FINAL, CONTRIBUINTE, NAO_CONTRIBUINTE }

    // ---------------------------------------------------------------- saída

    public record TributacaoResultado(
            List<ItemTributado> itens,
            TotaisTributarios totais,
            String versaoMotor,
            List<String> avisos) {
    }

    /**
     * O item calculado. Espelha a <b>camada 3</b> do §7.4: é isto que
     * {@code documento_fiscal_item} congela, e o que permite responder ao fisco "por que esta nota
     * saiu assim" anos depois. Nunca é recalculado — perfil corrigido em 2027 não muda nota de 2026.
     */
    public record ItemTributado(
            int nItem,
            String cfop,
            BigDecimal valorProduto,
            BigDecimal valorDesconto,
            BigDecimal valorAcrescimo,
            Icms icms,
            Contribuicao pis,
            Contribuicao cofins,
            IbsCbs ibsCbs,
            BigDecimal valorTotalTributos) {
    }

    /** {@code cst} e {@code csosn} são mutuamente exclusivos — o mesmo CHECK do banco (V035). */
    public record Icms(
            String cst,
            String csosn,
            BigDecimal baseCalculo,
            BigDecimal aliquota,
            BigDecimal valor,
            BigDecimal percReducaoBc,
            BigDecimal aliquotaFcp,
            BigDecimal valorFcp) {
    }

    public record Contribuicao(String cst, BigDecimal baseCalculo, BigDecimal aliquota, BigDecimal valor) {
    }

    /** Reforma tributária (§8.5). Em 2026 o IBS é integralmente estadual — {@code pIBSMun} = 0. */
    public record IbsCbs(
            boolean aplicavel,
            String cst,
            String cClassTrib,
            BigDecimal baseCalculo,
            BigDecimal aliquotaIbsUf,
            BigDecimal valorIbsUf,
            BigDecimal aliquotaIbsMun,
            BigDecimal valorIbsMun,
            BigDecimal aliquotaCbs,
            BigDecimal valorCbs) {
    }

    public record TotaisTributarios(
            BigDecimal valorProdutos,
            BigDecimal valorDesconto,
            BigDecimal valorAcrescimo,
            BigDecimal baseIcms,
            BigDecimal valorIcms,
            BigDecimal valorFcp,
            BigDecimal valorPis,
            BigDecimal valorCofins,
            BigDecimal baseIbsCbs,
            BigDecimal valorIbsUf,
            BigDecimal valorIbsMun,
            BigDecimal valorCbs,
            BigDecimal valorTotalTributos,
            BigDecimal valorNota) {
    }

    /**
     * Falha de cálculo. É sempre <b>explícita</b> (F11): sem regra que case, ou com CST/CSOSN
     * incompatível com o CRT, o motor recusa — nunca chuta um CFOP ou uma alíquota. Errar em
     * silêncio aqui vira multa para o lojista.
     */
    public static class TributacaoInvalidaException extends RuntimeException {
        public TributacaoInvalidaException(String mensagem) {
            super(mensagem);
        }
    }
}
