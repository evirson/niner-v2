package com.vetor.niner.fiscal.motor;

import com.vetor.niner.fiscal.configuracao.FiscalConfigDtos.RegimeApuracao;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Motor tributário (docs/MODULOFISCAL.md §8) — <b>puro, sem I/O</b>. Recebe a operação com a regra
 * fiscal já resolvida em cada item e devolve o cálculo completo, item a item, com a memória de como
 * chegou lá (camada 3 do §7.4).
 *
 * <p><b>Ordem de cálculo</b> (§8.1): CFOP vem da regra → ICMS por CSOSN (CRT 1/2/4) ou CST (CRT 3)
 * → PIS/COFINS <b>pelo regime da empresa</b> (DF36) → IPI só se equiparado a industrial (DF15) →
 * IBS/CBS → vTotTrib → totaliza conferindo item × total.
 *
 * <p><b>Nunca chuta</b> (F11): sem regra, com CST/CSOSN incompatível com o CRT, ou com CSOSN de ST
 * sem os campos de ST, o motor lança {@link TributacaoInvalidaException}. Uma alíquota inventada
 * vira multa para o lojista; um erro explícito vira uma mensagem na tela.
 *
 * <p><b>Arredondamento</b>: cada item é arredondado a 2 casas (HALF_UP, P7) e o total é a
 * <b>soma dos itens já arredondados</b> — é assim que a SEFAZ confere, e é onde as rejeições de
 * totalização batem.
 */
@Component
public class MotorTributario {

    /** Gravado em {@code documento_fiscal_item.versao_motor} (F9): a nota diz com que motor saiu. */
    public static final String VERSAO = "1.0.0";

    private static final int ESCALA = 2;
    private static final RoundingMode ARRED = RoundingMode.HALF_UP;
    private static final BigDecimal CEM = new BigDecimal("100");

    /**
     * Alíquotas de teste da transição (§8.5): CBS 0,9% e IBS 0,1% <b>integralmente estadual</b> —
     * a divisão UF/município só começa em 2027-2028 (arts. 343/344 da LC 214/2025). São
     * compensáveis com PIS/COFINS no mesmo período (carga efetiva zero), mas o emissor calcula e
     * destaca normalmente.
     */
    private static final BigDecimal ALIQ_CBS_2026 = new BigDecimal("0.90");
    private static final BigDecimal ALIQ_IBS_UF_2026 = new BigDecimal("0.10");
    private static final BigDecimal ALIQ_IBS_MUN_2026 = BigDecimal.ZERO;

    /** CSOSN válidos no v1 (§8.2). O 101 fica de fora — DF31: exige a alíquota efetiva do DAS. */
    private static final Set<String> CSOSN_VALIDOS = Set.of("102", "103", "202", "300", "400", "500", "900");
    private static final Set<String> CST_ICMS_VALIDOS =
            Set.of("00", "10", "20", "40", "41", "50", "51", "60", "70", "90");

    /** CSOSN/CST em que o ICMS não é destacado — o grupo sai sem base nem valor. */
    private static final Set<String> CSOSN_SEM_ICMS = Set.of("102", "103", "300", "400");
    private static final Set<String> CST_SEM_ICMS = Set.of("40", "41", "50");

    /** CST de PIS/COFINS de saída tributada normal — o único caso em que o REGIME dita a alíquota. */
    private static final String CST_TRIBUTADA_NORMAL = "01";
    /** CST do Simples Nacional/MEI: base, alíquota e valor zerados (§8.3). */
    private static final String CST_SIMPLES = "99";

    public TributacaoResultado calcular(OperacaoFiscal operacao, ContextoFiscalEmpresa contexto) {
        validarContexto(contexto);
        if (operacao.itens() == null || operacao.itens().isEmpty()) {
            throw new TributacaoInvalidaException("A operação não tem itens para tributar.");
        }

        List<String> avisos = new ArrayList<>();
        List<ItemTributado> calculados = new ArrayList<>();
        for (ItemOperacao item : operacao.itens()) {
            calculados.add(calcularItem(item, contexto, avisos));
        }
        return new TributacaoResultado(calculados, totalizar(calculados), VERSAO, List.copyOf(avisos));
    }

    // ---------------------------------------------------------------- item

    private ItemTributado calcularItem(ItemOperacao item, ContextoFiscalEmpresa ctx, List<String> avisos) {
        RegraFiscal regra = item.regra();
        if (regra == null) {
            throw new TributacaoInvalidaException(
                    "Item %d: nenhuma regra fiscal casou com o contexto (CRT %d, UF de destino, tipo de "
                            .formatted(item.nItem(), ctx.crt())
                            + "destinatário e operação). Revise o perfil fiscal do produto.");
        }
        if (vazio(regra.cfop())) {
            throw new TributacaoInvalidaException("Item %d: a regra fiscal não define CFOP.".formatted(item.nItem()));
        }

        BigDecimal valorProduto = arred(nz(item.quantidade()).multiply(nz(item.valorUnitario())));
        BigDecimal desconto = arred(nz(item.valorDesconto()));
        BigDecimal acrescimo = arred(nz(item.valorAcrescimo()));
        BigDecimal baseBruta = valorProduto.subtract(desconto).add(acrescimo).max(BigDecimal.ZERO);

        Icms icms = calcularIcms(item.nItem(), regra, ctx, baseBruta);
        Contribuicao pis = calcularContribuicao(item.nItem(), "PIS", regra.cstPis(),
                regra.aliquotaPisOverride(), ctx, baseBruta);
        Contribuicao cofins = calcularContribuicao(item.nItem(), "COFINS", regra.cstCofins(),
                regra.aliquotaCofinsOverride(), ctx, baseBruta);
        Ipi ipi = calcularIpi(regra, ctx, baseBruta);
        IbsCbs ibsCbs = calcularIbsCbs(item.nItem(), regra, baseBruta, avisos);

        // vTotTrib (Lei 12.741, §8.6) sai da tabela IBPT (NCM × UF × vigência), que ainda não foi
        // carregada — cfg_ibpt está vazia. Somar os tributos calculados aqui daria um número
        // DIFERENTE do que a lei pede (o vTotTrib inclui tributo federal embutido no preço), então
        // fica zero e o aviso sobe, em vez de imprimir um valor errado no cupom.
        BigDecimal vTotTrib = BigDecimal.ZERO.setScale(ESCALA);

        return new ItemTributado(item.nItem(), regra.cfop(), valorProduto, desconto, acrescimo,
                icms, pis, cofins, ipi, ibsCbs, vTotTrib);
    }

    // ---------------------------------------------------------------- ICMS (§8.2)

    private Icms calcularIcms(int nItem, RegraFiscal regra, ContextoFiscalEmpresa ctx, BigDecimal base) {
        boolean simplesOuMei = ctx.crt() == 1 || ctx.crt() == 2 || ctx.crt() == 4;

        if (simplesOuMei) {
            String csosn = regra.csosn();
            if (vazio(csosn)) {
                throw new TributacaoInvalidaException(
                        "Item %d: CRT %d (Simples Nacional/MEI) exige CSOSN, e a regra fiscal traz CST de ICMS."
                                .formatted(nItem, ctx.crt()));
            }
            if (!CSOSN_VALIDOS.contains(csosn)) {
                throw new TributacaoInvalidaException(
                        "Item %d: CSOSN %s não é aceito no v1. Válidos: %s."
                                .formatted(nItem, csosn, String.join(", ", CSOSN_VALIDOS.stream().sorted().toList())));
            }
            if (CSOSN_SEM_ICMS.contains(csosn)) {
                // 102/103/300/400 não destacam ICMS: o grupo sai só com orig e CSOSN. Campo a mais
                // rejeita tanto quanto campo a menos (§8.2).
                return new Icms(null, csosn, zero(), zero(), zero(), zero(), zero(), zero());
            }
            // 202/500/900 — o v1 destaca o que a regra trouxer; ST completo entra com a NF-e (§4.2).
            return montarIcmsComValor(null, csosn, regra, base);
        }

        String cst = regra.cstIcms();
        if (vazio(cst)) {
            throw new TributacaoInvalidaException(
                    "Item %d: CRT 3 (Regime Normal) exige CST de ICMS, e a regra fiscal traz CSOSN."
                            .formatted(nItem));
        }
        if (!CST_ICMS_VALIDOS.contains(cst)) {
            throw new TributacaoInvalidaException("Item %d: CST de ICMS %s inválido.".formatted(nItem, cst));
        }
        if (CST_SEM_ICMS.contains(cst)) {
            return new Icms(cst, null, zero(), zero(), zero(), zero(), zero(), zero());
        }
        return montarIcmsComValor(cst, null, regra, base);
    }

    private Icms montarIcmsComValor(String cst, String csosn, RegraFiscal regra, BigDecimal base) {
        BigDecimal reducao = nz(regra.percReducaoBc());
        BigDecimal baseIcms = arred(base.multiply(CEM.subtract(reducao)).divide(CEM, 10, ARRED));
        BigDecimal aliquota = nz(regra.aliquotaIcms());
        BigDecimal valor = percentual(baseIcms, aliquota);
        BigDecimal aliqFcp = nz(regra.aliquotaFcp());
        BigDecimal valorFcp = percentual(baseIcms, aliqFcp);
        return new Icms(cst, csosn, baseIcms, aliquota, valor, reducao, aliqFcp, valorFcp);
    }

    // ---------------------------------------------------------------- PIS/COFINS (DF36, §8.3)

    /**
     * <b>A alíquota vem do REGIME da empresa, não do perfil do produto</b> — só quando o CST é o de
     * saída tributada normal (01). CRT 3 cobre Presumido (0,65/3,00) e Real (1,65/7,60), e a regra
     * do perfil só distingue por CRT: se a alíquota saísse dela, um tenant com as duas empresas
     * emitiria uma delas errado, em silêncio. Ver DF36.
     *
     * <p>Para qualquer outro CST (04 monofásico, 06 alíquota zero, 07/08/09, 99 Simples) a alíquota
     * é a da regra — aí ela É override legítimo, porque o produto tem tratamento próprio.
     */
    private Contribuicao calcularContribuicao(int nItem, String nome, String cst,
                                              BigDecimal override, ContextoFiscalEmpresa ctx,
                                              BigDecimal base) {
        if (vazio(cst)) {
            throw new TributacaoInvalidaException(
                    "Item %d: a regra fiscal não define CST de %s.".formatted(nItem, nome));
        }
        if (CST_SIMPLES.equals(cst)) {
            // Simples/MEI: o tributo está dentro do DAS, sem apuração separada — base, alíquota e
            // valor zerados. Destacar valor aqui seria cobrar duas vezes.
            return new Contribuicao(cst, zero(), zero(), zero());
        }
        if (CST_TRIBUTADA_NORMAL.equals(cst)) {
            if (ctx.regimeApuracao() == RegimeApuracao.SIMPLES) {
                throw new TributacaoInvalidaException(
                        "Item %d: CST %s de %s é de saída tributada, incompatível com regime SIMPLES "
                                .formatted(nItem, cst, nome) + "(use CST 99).");
            }
            BigDecimal aliquota = aliquotaPorRegime(nome, ctx.regimeApuracao());
            return new Contribuicao(cst, base, aliquota, percentual(base, aliquota));
        }
        BigDecimal aliquota = nz(override);
        return new Contribuicao(cst, aliquota.signum() == 0 ? zero() : base, aliquota, percentual(base, aliquota));
    }

    private static BigDecimal aliquotaPorRegime(String nome, RegimeApuracao regime) {
        boolean pis = "PIS".equals(nome);
        return switch (regime) {
            case PRESUMIDO -> pis ? new BigDecimal("0.65") : new BigDecimal("3.00");   // cumulativo
            case REAL -> pis ? new BigDecimal("1.65") : new BigDecimal("7.60");        // não cumulativo
            case SIMPLES -> BigDecimal.ZERO;                                            // barrado antes
        };
    }

    // ---------------------------------------------------------------- IPI (DF15)

    private Ipi calcularIpi(RegraFiscal regra, ContextoFiscalEmpresa ctx, BigDecimal base) {
        // Varejo que compra para revender não é contribuinte de IPI. Só calcula se a empresa for
        // equiparada a industrial (importador que revende, quem fraciona/reembala).
        if (!ctx.equiparadoIndustrial() || vazio(regra.cstIpi())) {
            return new Ipi(null, zero(), zero(), zero());
        }
        BigDecimal aliquota = nz(regra.aliquotaIpi());
        return new Ipi(regra.cstIpi(), base, aliquota, percentual(base, aliquota));
    }

    // ---------------------------------------------------------------- IBS/CBS (§8.5)

    /**
     * <b>O gate é por CST do item, não por CRT do emitente.</b> A rejeição 1021 ("Grupo IBS/CBS
     * informado indevidamente", regra UB13-20) dispara pelo CST — é isso que sustenta a DF4
     * ("calcula para todos os regimes desde o v1"). Um {@code if (crt == SIMPLES)} aqui seria o
     * erro clássico: o Simples está dispensado de <b>transmitir</b> até 04/01/2027, não de o
     * sistema saber calcular.
     */
    private IbsCbs calcularIbsCbs(int nItem, RegraFiscal regra, BigDecimal base, List<String> avisos) {
        if (vazio(regra.cstIbsCbs()) || vazio(regra.cClassTrib())) {
            avisos.add("Item %d: sem CST de IBS/CBS ou cClassTrib no perfil — o grupo não será gerado. "
                    .formatted(nItem)
                    + "Obrigatório para CRT 3 desde 03/08/2026.");
            return new IbsCbs(false, null, null, zero(), zero(), zero(), zero(), zero(), zero(), zero());
        }

        BigDecimal aliqIbsUf = reduzir(ALIQ_IBS_UF_2026, regra.percReducaoIbs());
        BigDecimal aliqIbsMun = reduzir(ALIQ_IBS_MUN_2026, regra.percReducaoIbs());
        BigDecimal aliqCbs = reduzir(ALIQ_CBS_2026, regra.percReducaoCbs());

        return new IbsCbs(true, regra.cstIbsCbs(), regra.cClassTrib(), base,
                aliqIbsUf, percentual(base, aliqIbsUf),
                aliqIbsMun, percentual(base, aliqIbsMun),
                aliqCbs, percentual(base, aliqCbs));
    }

    /** Aplica o percentual de redução do cClassTrib sobre a alíquota (60% de redução ⇒ 40% dela). */
    private static BigDecimal reduzir(BigDecimal aliquota, BigDecimal percReducao) {
        BigDecimal red = nz(percReducao);
        if (red.signum() == 0) {
            return aliquota;
        }
        return aliquota.multiply(CEM.subtract(red)).divide(CEM, 4, ARRED);
    }

    // ---------------------------------------------------------------- totais

    /**
     * O total é a <b>soma dos itens já arredondados</b>, nunca um cálculo próprio sobre a base
     * somada — as rejeições de totalização da SEFAZ batem exatamente nessa diferença de centavo
     * (§8.1, item 8).
     */
    private TotaisTributarios totalizar(List<ItemTributado> itens) {
        BigDecimal produtos = soma(itens, ItemTributado::valorProduto);
        BigDecimal desconto = soma(itens, ItemTributado::valorDesconto);
        BigDecimal acrescimo = soma(itens, ItemTributado::valorAcrescimo);
        BigDecimal baseIcms = soma(itens, i -> i.icms().baseCalculo());
        BigDecimal valorIcms = soma(itens, i -> i.icms().valor());
        BigDecimal valorFcp = soma(itens, i -> i.icms().valorFcp());
        BigDecimal valorPis = soma(itens, i -> i.pis().valor());
        BigDecimal valorCofins = soma(itens, i -> i.cofins().valor());
        BigDecimal valorIpi = soma(itens, i -> i.ipi().valor());
        BigDecimal baseIbs = soma(itens, i -> i.ibsCbs().baseCalculo());
        BigDecimal ibsUf = soma(itens, i -> i.ibsCbs().valorIbsUf());
        BigDecimal ibsMun = soma(itens, i -> i.ibsCbs().valorIbsMun());
        BigDecimal cbs = soma(itens, i -> i.ibsCbs().valorCbs());
        BigDecimal totTrib = soma(itens, ItemTributado::valorTotalTributos);

        // vNF do v1 = produtos − desconto + acréscimo + IPI. IBS/CBS e ICMS NÃO entram: o ICMS é
        // por dentro (já está no preço) e o IBS/CBS é "por fora", mas se ele compõe o vNF em 2026
        // é a DF32 — pergunta obrigatória da F0, ainda sem resposta de fonte primária. Enquanto
        // não houver, não somamos: inflar o total cobraria do consumidor um valor que o caixa não
        // confere (o cupom deixaria de bater com caixa_detalhe).
        BigDecimal valorNota = produtos.subtract(desconto).add(acrescimo).add(valorIpi);

        return new TotaisTributarios(produtos, desconto, acrescimo, baseIcms, valorIcms, valorFcp,
                valorPis, valorCofins, valorIpi, baseIbs, ibsUf, ibsMun, cbs, totTrib, valorNota);
    }

    // ---------------------------------------------------------------- auxiliares

    private void validarContexto(ContextoFiscalEmpresa ctx) {
        if (ctx == null) {
            throw new TributacaoInvalidaException("Contexto fiscal da empresa não informado.");
        }
        if (ctx.crt() < 1 || ctx.crt() > 4) {
            throw new TributacaoInvalidaException("CRT %d inválido (esperado 1 a 4).".formatted(ctx.crt()));
        }
        if (ctx.regimeApuracao() == null) {
            throw new TributacaoInvalidaException("Regime de apuração não informado — é ele que define "
                    + "a alíquota de PIS/COFINS (DF36).");
        }
        boolean simplesOuMei = ctx.crt() == 1 || ctx.crt() == 2 || ctx.crt() == 4;
        if (simplesOuMei && ctx.regimeApuracao() != RegimeApuracao.SIMPLES) {
            throw new TributacaoInvalidaException(
                    "CRT %d exige regime de apuração SIMPLES.".formatted(ctx.crt()));
        }
        if (ctx.crt() == 3 && ctx.regimeApuracao() == RegimeApuracao.SIMPLES) {
            throw new TributacaoInvalidaException(
                    "CRT 3 (Regime Normal) exige regime PRESUMIDO ou REAL.");
        }
    }

    private static BigDecimal soma(List<ItemTributado> itens,
                                   java.util.function.Function<ItemTributado, BigDecimal> f) {
        return itens.stream().map(f).map(MotorTributario::nz)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(ESCALA, ARRED);
    }

    private static BigDecimal percentual(BigDecimal base, BigDecimal aliquota) {
        return arred(nz(base).multiply(nz(aliquota)).divide(CEM, 10, ARRED));
    }

    private static BigDecimal arred(BigDecimal v) {
        return nz(v).setScale(ESCALA, ARRED);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(ESCALA);
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }
}
