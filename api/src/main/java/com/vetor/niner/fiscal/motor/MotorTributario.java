package com.vetor.niner.fiscal.motor;

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
 * <p><b>DF37 — só MEI e Simples Nacional.</b> Com Lucro Real e Presumido fora do produto, o que
 * sobra de tributo efetivamente calculado é ICMS (por CSOSN, ou CST no caso do CRT 2), os CST de
 * tratamento próprio de PIS/COFINS e o IBS/CBS da reforma. PIS/COFINS ad-valorem e IPI não existem
 * aqui: os dois estão dentro do DAS (LC 123/2006, art. 13).
 *
 * <p><b>Ordem de cálculo</b> (§8.1): CFOP vem da regra → ICMS → PIS/COFINS → IBS/CBS → vTotTrib →
 * totaliza conferindo item × total.
 *
 * <p><b>Nunca chuta</b> (F11): sem regra, com CRT fora do escopo, com CST/CSOSN incompatível com o
 * CRT, o motor lança {@link TributacaoInvalidaException}. Uma alíquota inventada vira multa para o
 * lojista; um erro explícito vira uma mensagem na tela.
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

    /** CRT que o produto atende (DF37). O 3, Regime Normal, está fora por escopo. */
    private static final Set<Integer> CRT_ATENDIDOS = Set.of(1, 2, 4);

    /** CSOSN válidos no v1 (§8.2). O 101 fica de fora — DF31: exige a alíquota efetiva do DAS. */
    private static final Set<String> CSOSN_VALIDOS = Set.of("102", "103", "202", "300", "400", "500", "900");
    private static final Set<String> CST_ICMS_VALIDOS =
            Set.of("00", "10", "20", "40", "41", "50", "51", "60", "70", "90");

    /**
     * CSOSN/CST em que o ICMS não é destacado — o grupo sai sem base nem valor. {@code 500}
     * entrou aqui em 2026-08-19 (bug real, venda rejeitada pela SEFAZ, cStat 531 "Total da BC
     * ICMS difere do somatório dos itens"): {@code ICMSSN500} no XML sai só com {@code orig}+
     * {@code CSOSN} (grupo de ST retido é {@code minOccurs="0"} e o motor não o calcula —
     * {@link MontadorXmlNfce#montarIcms}), mas o motor estava calculando uma base não-zero (o
     * valor do produto, sem redução configurada) e somando isso no total da nota — o total
     * declarava uma base que nenhum item de fato carregava no XML. CSOSN 500 significa "ICMS já
     * retido lá atrás"; esta venda não abre uma base nova, então tem que zerar igual 102/103/400.
     */
    private static final Set<String> CSOSN_SEM_ICMS = Set.of("102", "103", "300", "400", "500");

    /**
     * ⚠️ {@code 60} entrou aqui em 2026-08-21 (achado de auditoria): é o <b>gêmeo exato</b> do caso
     * do CSOSN 500 descrito acima, e tinha ficado de fora.
     *
     * <p>O 60 estava só em {@link #CST_ICMS_JA_RETIDO}, que serve apenas para <i>pular a validação
     * de alíquota zerada</i> — o fluxo seguia para {@code montarIcmsComValor} e calculava base =
     * valor do item. Só que {@code MontadorXmlNfce} emite {@code <ICMS60>} com {@code orig}+
     * {@code CST} e mais nada, sem {@code vBC}: o total da nota declarava uma base que nenhum item
     * carregava. É a rejeição <b>cStat 531</b> ("Total da BC ICMS difere do somatório dos itens"),
     * a mesma de 2026-08-19, com o cliente no caixa.
     *
     * <p>Agravante silencioso: com alíquota preenchida no perfil (nada impedia, já que a validação
     * era justamente pulada), o valor também era somado em {@code <vICMS>} e gravado em
     * {@code documento_fiscal.valor_icms} — um ICMS que nenhum item destacou.
     *
     * <p>Alcance: tenants <b>CRT 2</b>, os únicos onde o CHECK do schema permite {@code cst_icms}.
     */
    private static final Set<String> CST_SEM_ICMS = Set.of("40", "41", "50", "60");

    /**
     * CST 60 — ICMS já retido antes por substituição tributária: nesta venda não há ICMS NOVO a
     * destacar (mesma lógica do CSOSN 500), então alíquota zerada aqui é o caso normal, não erro.
     * Os demais CST que destacam valor (00/10/20/51/70/90) precisam de alíquota informada —
     * salvos com zero, a nota sairia com ICMS R$ 0,00 sem avisar ninguém.
     */
    private static final Set<String> CST_ICMS_JA_RETIDO = Set.of("60");

    /** CST do Simples Nacional/MEI: base, alíquota e valor zerados (§8.3). É o caso normal. */
    private static final String CST_SIMPLES = "99";
    /** CST de saída tributada normal — só existe em Lucro Real/Presumido, fora do produto (DF37). */
    private static final String CST_TRIBUTADA_NORMAL = "01";

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
                    ("Item %d: nenhuma regra fiscal casou com o contexto (CRT %d, UF de destino, tipo de "
                            + "destinatário e operação). Revise o perfil fiscal do produto.")
                            .formatted(item.nItem(), ctx.crt()));
        }
        if (vazio(regra.cfop())) {
            throw new TributacaoInvalidaException("Item %d: a regra fiscal não define CFOP.".formatted(item.nItem()));
        }

        BigDecimal valorProduto = arred(nz(item.quantidade()).multiply(nz(item.valorUnitario())));
        BigDecimal desconto = arred(nz(item.valorDesconto()));
        BigDecimal acrescimo = arred(nz(item.valorAcrescimo()));
        BigDecimal baseBruta = valorProduto.subtract(desconto).add(acrescimo).max(BigDecimal.ZERO);

        Icms icms = calcularIcms(item.nItem(), regra, ctx, baseBruta);
        Contribuicao pis = calcularContribuicao(item.nItem(), "PIS", regra.cstPis(), regra.aliquotaPis(), baseBruta);
        Contribuicao cofins = calcularContribuicao(item.nItem(), "COFINS", regra.cstCofins(),
                regra.aliquotaCofins(), baseBruta);
        IbsCbs ibsCbs = calcularIbsCbs(item.nItem(), regra, baseBruta, avisos);

        // vTotTrib (Lei 12.741, §8.6): as 3 alíquotas já vêm RESOLVIDAS pelo chamador (a federal já
        // escolhida Nacional × Importado por origem, NCM × cfg_produto_ncm — 2026-08-19) — o motor
        // só multiplica cada uma pela base, igual a ICMS/PIS/COFINS. Sem alíquota resolvida
        // (produto sem NCM cadastrado, ou NCM sem correspondência local), fica zero sem aviso:
        // informação indisponível, não erro. O detalhamento (federal/estadual/municipal) existe
        // desde 2026-08-19 pra o DANFE poder exibir cada parcela, não só o total.
        BigDecimal tribFederal = percentual(baseBruta, nz(item.aliquotaTribFederal()));
        BigDecimal tribEstadual = percentual(baseBruta, nz(item.aliquotaTribEstadual()));
        BigDecimal tribMunicipal = percentual(baseBruta, nz(item.aliquotaTribMunicipal()));
        BigDecimal vTotTrib = tribFederal.add(tribEstadual).add(tribMunicipal);

        return new ItemTributado(item.nItem(), regra.cfop(), valorProduto, desconto, acrescimo,
                icms, pis, cofins, ibsCbs, tribFederal, tribEstadual, tribMunicipal, vTotTrib);
    }

    // ---------------------------------------------------------------- ICMS (§8.2)

    /**
     * CRT 1 e 4 emitem sempre com CSOSN. O <b>CRT 2</b> é o único que pode usar CST: a empresa com
     * excesso de sublimite recolhe ICMS fora do Simples, e se ela emite com CSOSN ou com CST é
     * divergência de mercado ainda não resolvida (§8.2, pendente do MOC). Enquanto não houver
     * resposta, quem decide é o contador no perfil fiscal — o motor aceita os dois e não chuta.
     */
    private Icms calcularIcms(int nItem, RegraFiscal regra, ContextoFiscalEmpresa ctx, BigDecimal base) {
        String cst = regra.cstIcms();
        if (!vazio(cst)) {
            if (ctx.crt() != 2) {
                throw new TributacaoInvalidaException(
                        ("Item %d: CRT %d (Simples Nacional/MEI) emite com CSOSN, e a regra fiscal traz CST "
                                + "de ICMS. Só o CRT 2, com excesso de sublimite, pode usar CST.")
                                .formatted(nItem, ctx.crt()));
            }
            if (!CST_ICMS_VALIDOS.contains(cst)) {
                throw new TributacaoInvalidaException("Item %d: CST de ICMS %s inválido.".formatted(nItem, cst));
            }
            if (CST_SEM_ICMS.contains(cst)) {
                return new Icms(cst, null, zero(), zero(), zero(), zero(), zero(), zero());
            }
            if (!CST_ICMS_JA_RETIDO.contains(cst) && nz(regra.aliquotaIcms()).signum() == 0) {
                throw new TributacaoInvalidaException(
                        ("Item %d: CST %s de ICMS destaca imposto, mas a regra fiscal não tem alíquota de "
                                + "ICMS informada (está zerada) — a nota sairia com ICMS R$ 0,00.")
                                .formatted(nItem, cst));
            }
            return montarIcmsComValor(cst, null, regra, base);
        }

        String csosn = regra.csosn();
        if (vazio(csosn)) {
            throw new TributacaoInvalidaException(
                    "Item %d: a regra fiscal não define CSOSN nem CST de ICMS.".formatted(nItem));
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
        // 202/900 — Montador ainda recusa esses dois (grupo de ST completo não calculado, §4.2);
        // chega aqui só pra cair na exceção de montagem, não pra declarar valor de verdade.
        return montarIcmsComValor(null, csosn, regra, base);
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

    // ---------------------------------------------------------------- PIS/COFINS (§8.3)

    /**
     * No Simples e no MEI o PIS/COFINS está <b>dentro do DAS</b>, sem apuração separada: CST 99 com
     * base, alíquota e valor zerados. Destacar valor aqui cobraria duas vezes.
     *
     * <p>A exceção legítima são os CST de <b>tratamento próprio</b> — 04 (monofásico), 06 (alíquota
     * zero) — que o optante segrega da receita: cesta básica, medicamento, autopeça, bebida.
     * Aí a alíquota é do produto mesmo, e vem da regra.
     *
     * <p>O CST <b>01</b> (saída tributada normal) é recusado: ele só existe em Lucro Real e
     * Presumido, que não são atendidos (DF37). Aceitá-lo faria uma nota do Simples destacar
     * PIS/COFINS por cima do DAS — cobrança em duplicidade, e silenciosa.
     */
    private Contribuicao calcularContribuicao(int nItem, String nome, String cst,
                                              BigDecimal aliquotaDaRegra, BigDecimal base) {
        if (vazio(cst)) {
            throw new TributacaoInvalidaException(
                    "Item %d: a regra fiscal não define CST de %s.".formatted(nItem, nome));
        }
        if (CST_TRIBUTADA_NORMAL.equals(cst)) {
            throw new TributacaoInvalidaException(
                    ("Item %d: CST %s de %s é de saída tributada normal, que só existe em Lucro Real ou "
                            + "Presumido — regimes fora do escopo do produto. No Simples/MEI use CST 99.")
                            .formatted(nItem, cst, nome));
        }
        if (CST_SIMPLES.equals(cst)) {
            return new Contribuicao(cst, zero(), zero(), zero());
        }
        BigDecimal aliquota = nz(aliquotaDaRegra);
        return new Contribuicao(cst, aliquota.signum() == 0 ? zero() : base, aliquota, percentual(base, aliquota));
    }

    // ---------------------------------------------------------------- IBS/CBS (§8.5)

    /**
     * <b>O gate é por CST do item, não por CRT do emitente.</b> A rejeição 1021 ("Grupo IBS/CBS
     * informado indevidamente", regra UB13-20) dispara pelo CST — é isso que sustenta a DF4
     * ("calcula desde o v1"). Um {@code if (crt == SIMPLES)} aqui seria o erro clássico, e depois
     * da DF37 seria fatal: <b>todas</b> as empresas do produto são Simples ou MEI. Elas estão
     * dispensadas de <b>transmitir</b> até 04/01/2027, não de o sistema saber calcular.
     */
    private IbsCbs calcularIbsCbs(int nItem, RegraFiscal regra, BigDecimal base, List<String> avisos) {
        if (vazio(regra.cstIbsCbs()) || vazio(regra.cClassTrib())) {
            avisos.add(("Item %d: sem CST de IBS/CBS ou cClassTrib no perfil — o grupo não será gerado. "
                    + "Obrigatório para Simples e MEI a partir de 04/01/2027.").formatted(nItem));
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
        BigDecimal baseIbs = soma(itens, i -> i.ibsCbs().baseCalculo());
        BigDecimal ibsUf = soma(itens, i -> i.ibsCbs().valorIbsUf());
        BigDecimal ibsMun = soma(itens, i -> i.ibsCbs().valorIbsMun());
        BigDecimal cbs = soma(itens, i -> i.ibsCbs().valorCbs());
        BigDecimal tribFederal = soma(itens, ItemTributado::valorTribFederal);
        BigDecimal tribEstadual = soma(itens, ItemTributado::valorTribEstadual);
        BigDecimal tribMunicipal = soma(itens, ItemTributado::valorTribMunicipal);
        BigDecimal totTrib = soma(itens, ItemTributado::valorTotalTributos);

        // vNF = produtos − desconto + acréscimo. ICMS não entra (é por dentro, já está no preço) e
        // IBS/CBS é "por fora", mas se compõe o vNF em 2026 é a DF32 — pergunta obrigatória da F0,
        // ainda sem resposta de fonte primária. Enquanto não houver, não somamos: inflar o total
        // cobraria do consumidor um valor que o caixa não confere (o cupom deixaria de bater com
        // caixa_detalhe).
        BigDecimal valorNota = produtos.subtract(desconto).add(acrescimo);

        return new TotaisTributarios(produtos, desconto, acrescimo, baseIcms, valorIcms, valorFcp,
                valorPis, valorCofins, baseIbs, ibsUf, ibsMun, cbs,
                tribFederal, tribEstadual, tribMunicipal, totTrib, valorNota);
    }

    // ---------------------------------------------------------------- auxiliares

    private void validarContexto(ContextoFiscalEmpresa ctx) {
        if (ctx == null) {
            throw new TributacaoInvalidaException("Contexto fiscal da empresa não informado.");
        }
        if (!CRT_ATENDIDOS.contains(ctx.crt())) {
            // Recusa de ESCOPO, não de implementação: dizer "não suportado" sem dizer o porquê
            // faria alguém tentar contornar cadastrando CRT 1 numa empresa do Lucro Presumido.
            throw new TributacaoInvalidaException(
                    ("CRT %d fora do escopo do produto: o Niner atende Simples Nacional (CRT 1 e 2) e "
                            + "MEI (CRT 4). Lucro Real e Lucro Presumido não são atendidos.")
                            .formatted(ctx.crt()));
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
