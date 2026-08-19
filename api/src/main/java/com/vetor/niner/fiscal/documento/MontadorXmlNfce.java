package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.*;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.Contribuicao;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.IbsCbs;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.Icms;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.ItemTributado;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Montagem do XML da NFC-e modelo 65, layout 4.00 (docs/MODULOFISCAL.md §14, bloco B5).
 * <b>Puro, sem I/O</b> — recebe a nota pronta e devolve o XML; quem lê banco é o chamador.
 *
 * <p><b>Nunca recalcula imposto.</b> Os valores vêm do {@code fiscal.motor} (B4); aqui só se
 * decide <b>em qual grupo do XML</b> cada valor entra — e essa decisão é toda ditada pelo CST/
 * CSOSN, como o §8.2 já mandava ("cada CST/CSOSN determina quais campos são obrigatórios e quais
 * são proibidos; campo a mais rejeita tanto quanto campo a menos").
 *
 * <p><b>A referência desta classe é um XML que a SEFAZ-PR realmente autorizou</b> no B0
 * (cStat 100, protocolo 141260001531993). Estrutura, ordem dos elementos e formatação decimal
 * seguem aquele XML e o XSD oficial (`api/src/main/resources/xsd/`), não suposição.
 *
 * <p><b>Ordem importa.</b> Todo grupo do XSD é {@code xs:sequence}: elemento fora de ordem é
 * rejeição por schema, não aviso. As sequências longas ({@code ide}, {@code prod},
 * {@code imposto}) foram conferidas campo a campo no XSD antes de serem escritas aqui.
 */
@Component
public class MontadorXmlNfce {

    /** Namespace da NF-e — o mesmo para modelo 55 e 65. */
    public static final String NS = "http://www.portalfiscal.inf.br/nfe";

    private static final String VERSAO_LAYOUT = "4.00";
    private static final int MODELO_NFCE = 65;
    private static final String SEM_GTIN = "SEM GTIN";

    /** {@code tpEmis = 9} — contingência offline da NFC-e (§9.7). Muda a chave de acesso e o QR. */
    public static final int TP_EMIS_CONTINGENCIA_OFFLINE = 9;

    /**
     * MOC: em homologação, a frase obrigatória vai no {@code xProd} do <b>primeiro item</b> — o
     * B0 confirmou isso, mas só cobriu venda <b>anônima</b> (sem CPF, {@code dest} inteiro
     * omitido). Ver {@link #montarProd}.
     *
     * <p>⚠️ 2026-08-19 — <b>são DUAS frases diferentes, não uma só.</b> Uma emissão real com
     * cliente identificado (venda 555) rejeitou justamente porque a 1ª correção deste dia (feita
     * numa venda com CPF, sem reparar que ela também mudou {@link #montarProd}) trocou esta
     * constante inteira, quebrando o caso já provado no B0. A SEFAZ exige o texto EXATO — e é
     * diferente pra cada grupo:
     * <ul>
     *   <li>{@code xProd} (item 1): <b>"NOTA FISCAL EMITIDA..."</b> — confirmado no B0 (cStat 100).</li>
     *   <li>{@code dest/xNome}: <b>"NF-E EMITIDA..."</b> — confirmado por rejeição real (cStat 598,
     *       "Razão Social do destinatário diferente de..."), ver {@link #FRASE_HOMOLOGACAO_DESTINATARIO}
     *       e {@link #montarDest}.</li>
     * </ul>
     * Nunca unificar as duas de novo sem ter as DUAS emissões reais (com e sem cliente) provando
     * a mudança — cada uma só foi validada isoladamente.
     */
    private static final String FRASE_HOMOLOGACAO =
            "NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";

    /** Só para {@code dest/xNome} — ver o aviso em {@link #FRASE_HOMOLOGACAO}. */
    private static final String FRASE_HOMOLOGACAO_DESTINATARIO =
            "NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";

    private static final DateTimeFormatter DH =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);

    /**
     * ⚠️ Fuso do emitente, simplificado para o piloto (UF única, PR): o XSD (padrão
     * {@code TDateTimeUTC}) <b>proíbe</b> o sufixo {@code Z} — só aceita offset explícito
     * {@code ±HH:MM}. {@code java.time}'s {@code XXX} imprime exatamente {@code Z} para offset
     * zero, então qualquer {@code OffsetDateTime} que chegue em UTC (é o que o pgjdbc devolve
     * para {@code timestamptz} — o instante é lido corretamente, só o offset vem zerado) rejeita
     * a nota inteira por schema. Achado no B7: nenhum fixture anterior expunha isso porque todos
     * criavam o {@code OffsetDateTime} à mão já com {@code ZoneOffset.ofHours(-3)}; só uma data
     * vinda do banco (venda real) revelou o problema.
     *
     * <p>Reconverter para {@code America/Sao_Paulo} corrige as duas coisas de uma vez: o offset
     * deixa de ser {@code Z} (schema) <b>e</b> a hora impressa volta a ser a hora local da venda,
     * não a hora UTC (uma nota das 19h de Brasília não pode sair dizendo "19h" com offset zero —
     * isso a colocaria três horas no futuro). Quando o produto atender mais de uma UF, isto vira
     * coluna em {@code cfg_uf_autorizador} (F10), não uma constante.
     */
    private static final java.time.ZoneId FUSO_EMISSAO = java.time.ZoneId.of("America/Sao_Paulo");

    // (java.time.ZoneId acima fica qualificado de propósito — só um uso, evita mais um import)

    /** CSOSN que o grupo {@code ICMSSN102} cobre — os quatro sem destaque de ICMS (XSD). */
    private static final Set<String> CSOSN_GRUPO_102 = Set.of("102", "103", "300", "400");
    /** CST que o grupo {@code ICMS40} cobre (XSD). */
    private static final Set<String> CST_GRUPO_40 = Set.of("40", "41", "50");

    /** PIS/COFINS: CST 04-09 vão em {@code PISNT}/{@code COFINSNT}, que <b>não têm campo de
     *  valor</b> — só o CST. CST 49-99 vão em {@code PISOutr}/{@code COFINSOutr}, que exigem
     *  vBC + alíquota + valor. CST 01/02 iriam em {@code PISAliq} (o 01 o motor recusa). */
    private static final Set<String> CST_CONTRIB_NT = Set.of("04", "05", "06", "07", "08", "09");
    private static final Set<String> CST_CONTRIB_ALIQ = Set.of("01", "02");

    public XmlMontado montar(NotaParaMontar nota) {
        return montar(nota, null);
    }

    /**
     * @param assinadorQrOffline assina o texto do QR Code na <b>contingência</b>
     *                           ({@code tpEmis = 9}), onde a autenticidade não pode depender de
     *                           consultar a SEFAZ. Ignorado na emissão normal; <b>obrigatório</b>
     *                           em contingência — sem ele o QR não é verificável e a nota é
     *                           rejeitada por schema.
     */
    public XmlMontado montar(NotaParaMontar nota, AssinadorQrCode assinadorQrOffline) {
        validar(nota);

        // Ver FUSO_EMISSAO: normaliza o offset ANTES de usar em qualquer lugar (chave, dhEmi, QR
        // de contingência) — inclusive a chave de acesso, cujo AAMM tem que refletir o mês LOCAL
        // da venda, não o mês UTC (que pode divergir perto da virada do dia/mês/ano).
        OffsetDateTime emissaoLocal = nota.emissao().atZoneSameInstant(FUSO_EMISSAO).toOffsetDateTime();

        String cnpjEmitente = apenasAlfanumerico(nota.emitente().cnpj());
        String chave = ChaveAcesso.montar(codigoUfDe(nota.emitente().uf()), emissaoLocal, cnpjEmitente,
                MODELO_NFCE, nota.serie(), nota.numero(), nota.tipoEmissao(), nota.codigoNumerico());

        StringBuilder xml = new StringBuilder(8192);
        xml.append("<NFe xmlns=\"").append(NS).append("\">");
        xml.append("<infNFe versao=\"").append(VERSAO_LAYOUT).append("\" Id=\"NFe").append(chave).append("\">");
        montarIde(xml, nota, chave, emissaoLocal);
        montarEmit(xml, nota.emitente(), cnpjEmitente);
        montarDest(xml, nota);
        montarItens(xml, nota);
        montarTotal(xml, nota);
        xml.append("<transp><modFrete>9</modFrete></transp>");   // 9 = sem frete, o caso do balcão
        montarPag(xml, nota);
        montarInfAdic(xml, nota);
        montarInfRespTec(xml, nota.responsavelTecnico());
        xml.append("</infNFe>");
        montarInfNFeSupl(xml, nota, chave, assinadorQrOffline, emissaoLocal);
        xml.append("</NFe>");

        return new XmlMontado(chave, xml.toString());
    }

    // ---------------------------------------------------------------- ide

    private void montarIde(StringBuilder xml, NotaParaMontar nota, String chave, OffsetDateTime emissaoLocal) {
        Emitente e = nota.emitente();
        xml.append("<ide>")
                .append(tag("cUF", "%02d".formatted(codigoUfDe(e.uf()))))
                .append(tag("cNF", "%08d".formatted(nota.codigoNumerico())))
                .append(tag("natOp", texto(nota.naturezaOperacao())))
                .append(tag("mod", String.valueOf(MODELO_NFCE)))
                .append(tag("serie", String.valueOf(nota.serie())))
                .append(tag("nNF", String.valueOf(nota.numero())))
                .append(tag("dhEmi", emissaoLocal.format(DH)))
                .append(tag("tpNF", "1"))                     // 1 = saída
                .append(tag("idDest", "1"))                   // 1 = operação interna (NFC-e é sempre)
                .append(tag("cMunFG", String.valueOf(e.codigoMunicipioIbge())))
                .append(tag("tpImp", "4"))                    // 4 = DANFE NFC-e
                .append(tag("tpEmis", String.valueOf(nota.tipoEmissao())))
                .append(tag("cDV", chave.substring(43)))
                .append(tag("tpAmb", String.valueOf(nota.ambiente().codigo())))
                .append(tag("finNFe", "1"))                   // 1 = normal
                .append(tag("indFinal", "1"))                 // 1 = consumidor final
                .append(tag("indPres", "1"))                  // 1 = presencial
                .append(tag("procEmi", "0"))                  // 0 = emissão por app do contribuinte
                .append(tag("verProc", texto(nota.versaoAplicativo())))
                .append("</ide>");
    }

    // ---------------------------------------------------------------- emit / dest

    private void montarEmit(StringBuilder xml, Emitente e, String cnpj) {
        xml.append("<emit>")
                .append(tag("CNPJ", cnpj))
                .append(tag("xNome", texto(e.razaoSocial())))
                .append(opcional("xFant", e.nomeFantasia()))
                .append("<enderEmit>")
                .append(tag("xLgr", texto(e.logradouro())))
                .append(tag("nro", texto(e.numero())))
                .append(opcional("xCpl", e.complemento()))
                .append(tag("xBairro", texto(e.bairro())))
                .append(tag("cMun", String.valueOf(e.codigoMunicipioIbge())))
                .append(tag("xMun", texto(e.municipio())))
                .append(tag("UF", e.uf()))
                .append(opcional("CEP", apenasDigitos(e.cep())))
                .append(tag("cPais", "1058"))
                .append(tag("xPais", "BRASIL"))
                .append(opcional("fone", apenasDigitos(e.telefone())))
                .append("</enderEmit>")
                .append(tag("IE", apenasAlfanumerico(e.inscricaoEstadual())))
                .append(tag("CRT", String.valueOf(e.crt())))
                .append("</emit>");
    }

    /**
     * Grupo {@code dest} — <b>omitido por completo</b> na venda anônima, que é a maioria do
     * balcão. Não é economia de bytes: a NFC-e sem identificação do consumidor simplesmente não
     * tem destinatário, e emitir o grupo vazio seria rejeitado.
     */
    private void montarDest(StringBuilder xml, NotaParaMontar nota) {
        Destinatario d = nota.destinatario();
        if (d == null) {
            return;
        }
        String doc = apenasAlfanumerico(d.cpfCnpj());
        xml.append("<dest>");
        if (doc != null && doc.length() == 11) {
            xml.append(tag("CPF", doc));
        } else if (doc != null && doc.length() == 14) {
            xml.append(tag("CNPJ", doc));
        }
        // ⚠️ 2026-08-19: em homologação, xNome do destinatário TEM que ser a frase de homologação
        // (FRASE_HOMOLOGACAO), não o nome real do cliente — SEFAZ rejeita com cStat 598 senão
        // ("Razão Social do destinatário diferente de..."). Achado numa emissão real com cliente
        // identificado; o B0 nunca testou esse caminho (só venda anônima, sem dest).
        String xNome = nota.ambiente() == AmbienteSefaz.HOMOLOGACAO ? FRASE_HOMOLOGACAO_DESTINATARIO : d.nome();
        xml.append(opcional("xNome", xNome))
                .append(tag("indIEDest", String.valueOf(d.indicadorIe())))
                .append("</dest>");
    }

    // ---------------------------------------------------------------- det

    private void montarItens(StringBuilder xml, NotaParaMontar nota) {
        Map<Integer, ItemTributado> tributadoPorItem = nota.itensTributados().stream()
                .collect(Collectors.toMap(ItemTributado::nItem, Function.identity()));

        for (ItemNota item : nota.itens()) {
            ItemTributado trib = tributadoPorItem.get(item.nItem());
            if (trib == null) {
                throw new MontagemInvalidaException(
                        "Item %d não tem tributação calculada — o motor e a nota estão fora de sincronia."
                                .formatted(item.nItem()));
            }
            xml.append("<det nItem=\"").append(item.nItem()).append("\">");
            montarProd(xml, item, trib, nota);
            montarImposto(xml, trib, item.origemMercadoria());
            xml.append("</det>");
        }
    }

    private void montarProd(StringBuilder xml, ItemNota item, ItemTributado trib, NotaParaMontar nota) {
        String gtin = vazio(item.gtin()) ? SEM_GTIN : item.gtin().trim();
        // Homologação: a frase obrigatória vai no xProd do PRIMEIRO item (MOC, confirmado no B0).
        String descricao = nota.ambiente() == AmbienteSefaz.HOMOLOGACAO && item.nItem() == 1
                ? FRASE_HOMOLOGACAO
                : texto(item.descricao());

        xml.append("<prod>")
                .append(tag("cProd", texto(item.codigoProduto())))
                .append(tag("cEAN", gtin))
                .append(tag("xProd", descricao))
                .append(tag("NCM", apenasDigitos(item.ncm())))
                .append(opcional("CEST", apenasDigitos(item.cest())))
                .append(tag("CFOP", trib.cfop()))
                .append(tag("uCom", texto(item.unidadeComercial())))
                .append(tag("qCom", dec(item.quantidade(), 4)))
                .append(tag("vUnCom", dec(item.valorUnitario(), 10)))
                .append(tag("vProd", dec(trib.valorProduto(), 2)))
                .append(tag("cEANTrib", gtin))
                .append(tag("uTrib", texto(ouEntao(item.unidadeTributavel(), item.unidadeComercial()))))
                .append(tag("qTrib", dec(ouEntao(item.quantidadeTributavel(), item.quantidade()), 4)))
                .append(tag("vUnTrib", dec(ouEntao(item.valorUnitarioTributavel(), item.valorUnitario()), 10)));
        if (positivo(trib.valorDesconto())) {
            xml.append(tag("vDesc", dec(trib.valorDesconto(), 2)));
        }
        if (positivo(trib.valorAcrescimo())) {
            xml.append(tag("vOutro", dec(trib.valorAcrescimo(), 2)));
        }
        xml.append(tag("indTot", "1"))   // 1 = o valor deste item compõe o total da nota
                .append("</prod>");
    }

    /** Ordem do XSD dentro de {@code imposto}: vTotTrib → ICMS → … → PIS → COFINS → IBSCBS. */
    private void montarImposto(StringBuilder xml, ItemTributado trib, int origemMercadoria) {
        xml.append("<imposto>");
        // vTotTrib (Lei 12.741, §8.6) — resolvido pelo motor a partir da alíquota já casada por
        // NCM/origem (VendaFiscalAssembler, 2026-08-19). minOccurs="0" no XSD: sem alíquota
        // resolvida (produto sem NCM cadastrado) o valor fica zero e o campo é OMITIDO — emitir
        // 0,00 afirmaria "zero tributos" no cupom do consumidor, uma informação errada.
        if (positivo(trib.valorTotalTributos())) {
            xml.append(tag("vTotTrib", dec(trib.valorTotalTributos(), 2)));
        }
        montarIcms(xml, trib.icms(), origemMercadoria);
        montarContribuicao(xml, "PIS", trib.pis());
        montarContribuicao(xml, "COFINS", trib.cofins());
        montarIbsCbs(xml, trib.ibsCbs());
        xml.append("</imposto>");
    }

    /**
     * O grupo de ICMS é escolhido pelo CSOSN/CST — cada um com seu conjunto fechado de campos.
     * {@code origemMercadoria} vem de {@code produto.origem_mercadoria} (0-8, TOrig do XSD, NOT
     * NULL DEFAULT 0) via {@code ItemNota} — 2026-08-19: antes vinha sempre hardcoded "0", porque
     * o motor não a carregava e não havia consumidor nenhum para o dado.
     */
    private void montarIcms(StringBuilder xml, Icms icms, int origemMercadoria) {
        xml.append("<ICMS>");
        String orig = String.valueOf(origemMercadoria);
        String csosn = icms.csosn();
        String cst = icms.cst();

        if (csosn != null) {
            if (CSOSN_GRUPO_102.contains(csosn)) {
                xml.append("<ICMSSN102>").append(tag("orig", orig)).append(tag("CSOSN", csosn)).append("</ICMSSN102>");
            } else if ("500".equals(csosn)) {
                // O bloco de ST retido (vBCSTRet/pST/vICMSSTRet) é <xs:sequence minOccurs="0">
                // no XSD — e o motor não calcula esses valores (são do ST retido lá atrás, na
                // compra, não desta venda). Sai só orig+CSOSN, que o schema aceita.
                xml.append("<ICMSSN500>").append(tag("orig", orig)).append(tag("CSOSN", csosn)).append("</ICMSSN500>");
            } else if ("202".equals(csosn) || "203".equals(csosn)) {
                throw new MontagemInvalidaException(
                        ("CSOSN %s exige o grupo completo de ST (modBCST, vBCST, pICMSST, vICMSST), que o motor "
                                + "ainda não calcula — ST completo entra com a NF-e (§4.2).").formatted(csosn));
            } else if ("900".equals(csosn)) {
                throw new MontagemInvalidaException(
                        "CSOSN 900 (outras) depende de combinação de campos definida caso a caso — fora do v1.");
            } else {
                throw new MontagemInvalidaException("CSOSN %s sem grupo de XML correspondente.".formatted(csosn));
            }
        } else if (cst != null) {
            if ("00".equals(cst)) {
                xml.append("<ICMS00>")
                        .append(tag("orig", orig)).append(tag("CST", cst))
                        .append(tag("modBC", "3"))              // 3 = valor da operação
                        .append(tag("vBC", dec(icms.baseCalculo(), 2)))
                        .append(tag("pICMS", dec(icms.aliquota(), 2)))
                        .append(tag("vICMS", dec(icms.valor(), 2)))
                        .append("</ICMS00>");
            } else if (CST_GRUPO_40.contains(cst)) {
                xml.append("<ICMS40>").append(tag("orig", orig)).append(tag("CST", cst)).append("</ICMS40>");
            } else if ("60".equals(cst)) {
                xml.append("<ICMS60>").append(tag("orig", orig)).append(tag("CST", cst)).append("</ICMS60>");
            } else if ("20".equals(cst)) {
                xml.append("<ICMS20>")
                        .append(tag("orig", orig)).append(tag("CST", cst))
                        .append(tag("modBC", "3"))
                        .append(tag("pRedBC", dec(icms.percReducaoBc(), 2)))
                        .append(tag("vBC", dec(icms.baseCalculo(), 2)))
                        .append(tag("pICMS", dec(icms.aliquota(), 2)))
                        .append(tag("vICMS", dec(icms.valor(), 2)))
                        .append("</ICMS20>");
            } else {
                throw new MontagemInvalidaException(
                        ("CST de ICMS %s exige grupo com substituição tributária ou diferimento, que o motor ainda "
                                + "não calcula — fora do v1 (§4.2).").formatted(cst));
            }
        } else {
            throw new MontagemInvalidaException("Item sem CSOSN nem CST de ICMS — o motor deveria ter recusado antes.");
        }
        xml.append("</ICMS>");
    }

    /**
     * PIS e COFINS têm a mesma estrutura, só muda o prefixo da tag. O CST decide o subgrupo — e
     * é aqui que mora a armadilha: {@code PISNT} (CST 04-09) <b>não tem campo de valor</b>. Se o
     * motor tiver calculado valor para um CST de NT, o XML não teria onde colocá-lo e o número
     * sumiria em silêncio — por isso a recusa explícita (F11) em vez de emitir a nota "quase
     * certa".
     */
    private void montarContribuicao(StringBuilder xml, String nome, Contribuicao c) {
        String cst = c.cst();
        xml.append('<').append(nome).append('>');
        if (CST_CONTRIB_NT.contains(cst)) {
            if (positivo(c.valor())) {
                throw new MontagemInvalidaException(
                        ("%s CST %s é um grupo sem campo de valor no XML (%sNT), mas o cálculo trouxe %s. "
                                + "Revise a alíquota do perfil fiscal — no XML esse valor seria perdido.")
                                .formatted(nome, cst, nome, dec(c.valor(), 2)));
            }
            xml.append('<').append(nome).append("NT>").append(tag("CST", cst))
                    .append("</").append(nome).append("NT>");
        } else if (CST_CONTRIB_ALIQ.contains(cst)) {
            xml.append('<').append(nome).append("Aliq>")
                    .append(tag("CST", cst))
                    .append(tag("vBC", dec(c.baseCalculo(), 2)))
                    .append(tag("p" + nome, dec(c.aliquota(), 2)))
                    .append(tag("v" + nome, dec(c.valor(), 2)))
                    .append("</").append(nome).append("Aliq>");
        } else {
            // 49-99, incluindo o 99 do Simples/MEI — que é o caso normal deste produto (DF37).
            xml.append('<').append(nome).append("Outr>")
                    .append(tag("CST", cst))
                    .append(tag("vBC", dec(c.baseCalculo(), 2)))
                    .append(tag("p" + nome, dec(c.aliquota(), 2)))
                    .append(tag("v" + nome, dec(c.valor(), 2)))
                    .append("</").append(nome).append("Outr>");
        }
        xml.append("</").append(nome).append('>');
    }

    /**
     * Grupo {@code IBSCBS} da reforma (NT 2025.002-RTC) — estrutura {@code TTribNFe} do
     * `DFeTiposBasicos_v1.00.xsd`: CST(3) + cClassTrib(6) + gIBSCBS{vBC, gIBSUF, gIBSMun, vIBS,
     * gCBS}. Omitido quando o perfil fiscal não traz CST/cClassTrib — o motor já avisa nesse caso.
     *
     * <p>{@code vIBS} é o total do IBS (UF + município), campo próprio do XSD — não é derivado
     * pelo receptor.
     */
    private void montarIbsCbs(StringBuilder xml, IbsCbs g) {
        if (g == null || !g.aplicavel()) {
            return;
        }
        BigDecimal vIbs = nz(g.valorIbsUf()).add(nz(g.valorIbsMun())).setScale(2, RoundingMode.HALF_UP);
        xml.append("<IBSCBS>")
                .append(tag("CST", g.cst()))
                .append(tag("cClassTrib", g.cClassTrib()))
                .append("<gIBSCBS>")
                .append(tag("vBC", dec(g.baseCalculo(), 2)))
                .append("<gIBSUF>")
                .append(tag("pIBSUF", dec(g.aliquotaIbsUf(), 4)))
                .append(tag("vIBSUF", dec(g.valorIbsUf(), 2)))
                .append("</gIBSUF>")
                .append("<gIBSMun>")
                .append(tag("pIBSMun", dec(g.aliquotaIbsMun(), 4)))
                .append(tag("vIBSMun", dec(g.valorIbsMun(), 2)))
                .append("</gIBSMun>")
                .append(tag("vIBS", dec(vIbs, 2)))
                .append("<gCBS>")
                .append(tag("pCBS", dec(g.aliquotaCbs(), 4)))
                .append(tag("vCBS", dec(g.valorCbs(), 2)))
                .append("</gCBS>")
                .append("</gIBSCBS>")
                .append("</IBSCBS>");
    }

    // ---------------------------------------------------------------- total

    private void montarTotal(StringBuilder xml, NotaParaMontar nota) {
        var t = nota.totais();
        xml.append("<total><ICMSTot>")
                .append(tag("vBC", dec(t.baseIcms(), 2)))
                .append(tag("vICMS", dec(t.valorIcms(), 2)))
                .append(tag("vICMSDeson", "0.00"))
                .append(tag("vFCP", dec(t.valorFcp(), 2)))
                .append(tag("vBCST", "0.00"))
                .append(tag("vST", "0.00"))
                .append(tag("vFCPST", "0.00"))
                .append(tag("vFCPSTRet", "0.00"))
                .append(tag("vProd", dec(t.valorProdutos(), 2)))
                .append(tag("vFrete", "0.00"))
                .append(tag("vSeg", "0.00"))
                .append(tag("vDesc", dec(t.valorDesconto(), 2)))
                .append(tag("vII", "0.00"))
                .append(tag("vIPI", "0.00"))        // DF37: Simples/MEI não destaca IPI
                .append(tag("vIPIDevol", "0.00"))
                .append(tag("vPIS", dec(t.valorPis(), 2)))
                .append(tag("vCOFINS", dec(t.valorCofins(), 2)))
                .append(tag("vOutro", dec(t.valorAcrescimo(), 2)))
                .append(tag("vNF", dec(t.valorNota(), 2)));
        if (positivo(t.valorTotalTributos())) {
            xml.append(tag("vTotTrib", dec(t.valorTotalTributos(), 2)));
        }
        xml.append("</ICMSTot></total>");
    }

    // ---------------------------------------------------------------- pag / infAdic / respTec

    private void montarPag(StringBuilder xml, NotaParaMontar nota) {
        xml.append("<pag>");
        for (Pagamento p : nota.pagamentos()) {
            xml.append("<detPag>")
                    .append(tag("indPag", "0"))     // 0 = à vista (o caso do balcão)
                    .append(tag("tPag", p.codigoMeioPagamento()))
                    .append(tag("vPag", dec(p.valor(), 2)));
            if (!vazio(p.cnpjCredenciadora()) || !vazio(p.bandeira())) {
                xml.append("<card>")
                        .append(tag("tpIntegra", "2"))   // 2 = não integrado (maquininha à parte)
                        .append(opcional("CNPJ", apenasDigitos(p.cnpjCredenciadora())))
                        .append(opcional("tBand", p.bandeira()))
                        .append("</card>");
            }
            xml.append("</detPag>");
        }
        if (positivo(nota.troco())) {
            xml.append(tag("vTroco", dec(nota.troco(), 2)));
        }
        xml.append("</pag>");
    }

    private void montarInfAdic(StringBuilder xml, NotaParaMontar nota) {
        if (vazio(nota.informacoesComplementares())) {
            return;
        }
        xml.append("<infAdic>").append(tag("infCpl", texto(nota.informacoesComplementares()))).append("</infAdic>");
    }

    private void montarInfRespTec(StringBuilder xml, ResponsavelTecnico r) {
        if (r == null) {
            return;
        }
        xml.append("<infRespTec>")
                .append(tag("CNPJ", apenasDigitos(r.cnpj())))
                .append(tag("xContato", texto(r.contato())))
                .append(tag("email", texto(r.email())))
                .append(tag("fone", apenasDigitos(r.telefone())))
                .append("</infRespTec>");
    }

    /**
     * {@code infNFeSupl} — QR Code e link de consulta. Fica <b>fora</b> de {@code infNFe} (é irmão
     * dele dentro de {@code NFe}), o que também significa que <b>não é assinado</b> pela
     * XMLDSig — daí o QR offline carregar assinatura própria.
     *
     * <p><b>Online</b> ({@code tpEmis} 1/3/4): {@code ?p=<chave44>|2|<tpAmb>|<idCSC>|<hashQRCode>}
     * (NT 2015.002 v2), {@code hashQRCode = SHA-1(chave+cscToken)} em hex maiúsculo. ⚠️
     * <b>Achado em 2026-08-19, testando ao vivo contra o portal da SEFAZ-PR:</b> a versão "3" sem
     * CSC/hash — que passa na autorização, porque o XSD só confere a <i>forma</i> do campo — é
     * recusada pelo <b>portal de consulta</b> com "Url do QRCode mal formatado". Confirmado por
     * comparação com QR Codes reais do PR: a autorização aceita "3" solto, mas quem lê o QR quer
     * "2" com CSC — são validações independentes, uma não implica a outra.
     *
     * <p><b>Offline</b> ({@code tpEmis = 9}): a SEFAZ ainda não tem a nota, então o QR precisa
     * carregar o suficiente para o consumidor conferir na hora — e ser assinado, porque sem a
     * SEFAZ do outro lado nada mais garante que aquele papel não foi inventado.
     *
     * <p>⚠️ <b>O formato offline foi lido do pattern do XSD oficial, não da spec.</b> A §9.5
     * descrevia {@code ?p=<chave>|<versao>||<dhEmi>||<tpAmb>||}, que não bate com o layout: o
     * pattern <i>QRCODE V3 OFFLINE</i> de {@code leiauteNFe_v4.00.xsd} exige
     * {@code chave|3|tpAmb|<dia>|<vNF>|<indicador do destinatário>|<documento>|<assinatura base64>}
     * — <b>o dia do mês</b> (2 dígitos, 01–31), não a data-hora completa, e os campos de
     * destinatário podem vir vazios mas os separadores não. Codificar pela spec teria dado
     * rejeição por schema em toda venda em contingência — justamente quando não há SEFAZ para
     * explicar o erro.
     */
    private void montarInfNFeSupl(StringBuilder xml, NotaParaMontar nota, String chave,
                                  AssinadorQrCode assinadorQrOffline, OffsetDateTime emissaoLocal) {
        String qr = nota.tipoEmissao() == TP_EMIS_CONTINGENCIA_OFFLINE
                ? qrCodeOffline(nota, chave, assinadorQrOffline, emissaoLocal)
                : qrCodeOnline(nota, chave);

        xml.append("<infNFeSupl>")
                .append("<qrCode><![CDATA[").append(qr).append("]]></qrCode>")
                .append(tag("urlChave", nota.urls().urlConsultaChave()))
                .append("</infNFeSupl>");
    }

    private String qrCodeOnline(NotaParaMontar nota, String chave) {
        // O XSD exige idCSC sem zeros à esquerda (`0|[1-9][0-9]{1,5}`) — a SEFAZ credencia e
        // exibe o CSC como "000001", mas o QR Code rejeita esse literal (achado em 2026-08-19,
        // testando contra o XSD oficial: "não tem um aspecto válido em relação ao padrão").
        String idCsc = String.valueOf(Integer.parseInt(nota.csc().id()));
        // ⚠️ hashQRCode NÃO é SHA-1(chave+CSC) — é SHA-1 de TODA a sequência já montada
        // (chave|versao|tpAmb|idCSC) concatenada com o CSC no final. Faltar versao/tpAmb/idCSC no
        // hash foi a causa da rejeição real da SEFAZ (cStat 464 "Código de Hash no QR-Code difere
        // do calculado", achado em 2026-08-19 numa emissão de verdade) — o hash "só chave+CSC" que
        // eu tinha antes até *parecia* certo (mesmo formato geral, mesmo tamanho de 40 hex), mas a
        // SEFAZ recalcula com a fórmula completa e rejeita qualquer divergência, por menor que
        // seja. Confirmado contra a implementação de referência (nfephp-org/sped-nfe, get200()).
        String seq = "%s|2|%d|%s".formatted(chave, nota.ambiente().codigo(), idCsc);
        String hash = sha1Hex(seq + nota.csc().token());
        return "%s?p=%s|%s".formatted(nota.urls().urlQrCode(), seq, hash);
    }

    private static String sha1Hex(String texto) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(texto.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02X", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 indisponível na JVM.", e);
        }
    }

    private String qrCodeOffline(NotaParaMontar nota, String chave, AssinadorQrCode assinador,
                                 OffsetDateTime emissaoLocal) {
        if (assinador == null) {
            throw new MontagemInvalidaException(
                    "Emissão em contingência exige assinador do QR Code — sem assinatura o cupom "
                            + "entregue ao consumidor não é verificável.");
        }

        Destinatario dest = nota.destinatario();
        String indicador = "";
        String documento = "";
        if (dest != null && !vazio(dest.cpfCnpj())) {
            // 1 = CPF, 2 = CNPJ, 3 = documento estrangeiro. Na NFC-e de balcão o normal é não ter
            // destinatário nenhum — e aí os dois campos ficam vazios, mas os separadores ficam.
            documento = apenasDigitos(dest.cpfCnpj());
            indicador = documento.length() == 14 ? "2" : "1";
        }

        // Os parâmetros assinados são exatamente o que vai depois de "?p=" — assinar mais (a URL)
        // ou menos (sem o dia/valor) produz assinatura que o app de consulta rejeita.
        String parametros = "%s|3|%d|%02d|%s|%s|%s".formatted(
                chave, nota.ambiente().codigo(), emissaoLocal.getDayOfMonth(),
                nota.totais().valorNota().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                indicador, documento);

        return "%s?p=%s|%s".formatted(nota.urls().urlQrCode(), parametros, assinador.assinar(parametros));
    }

    /**
     * Assina o texto do QR Code da contingência (RSA-SHA1 sobre os parâmetros, resultado em
     * Base64), com a chave privada do certificado da empresa.
     *
     * <p>É uma função e não uma dependência do montador de propósito: o montador precisa continuar
     * puro e testável sem certificado, e a chave privada só circula em quem realmente assina.
     */
    @FunctionalInterface
    public interface AssinadorQrCode {
        String assinar(String parametros);
    }

    // ---------------------------------------------------------------- validação de entrada

    private void validar(NotaParaMontar nota) {
        if (nota == null) {
            throw new MontagemInvalidaException("Nota não informada.");
        }
        if (nota.itens() == null || nota.itens().isEmpty()) {
            throw new MontagemInvalidaException("Nota sem itens.");
        }
        if (nota.itens().size() > 990) {
            throw new MontagemInvalidaException(
                    "NF-e aceita no máximo 990 itens; esta nota tem %d.".formatted(nota.itens().size()));
        }
        if (nota.pagamentos() == null || nota.pagamentos().isEmpty()) {
            throw new MontagemInvalidaException("NFC-e exige ao menos uma forma de pagamento (grupo pag).");
        }
        if (nota.emitente() == null) {
            throw new MontagemInvalidaException("Emitente não informado.");
        }
        if (nota.urls() == null || vazio(nota.urls().urlQrCode()) || vazio(nota.urls().urlConsultaChave())) {
            throw new MontagemInvalidaException(
                    "URLs de consulta da UF não informadas — sem elas não há QR Code válido (F10).");
        }
        if (nota.tipoEmissao() != TP_EMIS_CONTINGENCIA_OFFLINE && (nota.csc() == null
                || vazio(nota.csc().id()) || vazio(nota.csc().token()))) {
            throw new MontagemInvalidaException(
                    "CSC (Código de Segurança do Contribuinte) não informado — sem ele o QR Code "
                            + "online da NFC-e não é aceito pelo portal de consulta da SEFAZ.");
        }
        for (Pagamento p : nota.pagamentos()) {
            if (vazio(p.codigoMeioPagamento())) {
                throw new MontagemInvalidaException(
                        "Forma de pagamento sem código tPag. Preencha o código na tela de Tipo de Carteira.");
            }
        }
        // A soma dos pagamentos tem que fechar com o total da nota — a SEFAZ rejeita a divergência,
        // e é mais barato descobrir aqui do que no caixa.
        BigDecimal somaPag = nota.pagamentos().stream().map(Pagamento::valor)
                .map(MontadorXmlNfce::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal esperado = nz(nota.totais().valorNota()).add(nz(nota.troco()));
        if (somaPag.compareTo(esperado) != 0) {
            throw new MontagemInvalidaException(
                    ("A soma dos pagamentos (%s) não fecha com o total da nota mais o troco (%s). "
                            + "A SEFAZ rejeita essa divergência.")
                            .formatted(dec(somaPag, 2), dec(esperado, 2)));
        }
    }

    // ---------------------------------------------------------------- auxiliares

    // ⚠️ Estes auxiliares MORAM em `XmlFiscal` desde 2026-08-19 — são compartilhados com o
    // montador da NF-e 55 de devolução, e o escape/formatação tem que ser IDÊNTICO nos dois
    // (ver o javadoc de `XmlFiscal` pro porquê). Os delegadores abaixo existem só pra não mexer
    // nas ~200 chamadas deste arquivo, que já emite nota de verdade em produção.

    private static int codigoUfDe(String uf) {
        return XmlFiscal.codigoUfDe(uf);
    }

    private static String tag(String nome, String valor) {
        return XmlFiscal.tag(nome, valor);
    }

    private static String opcional(String nome, String valor) {
        return XmlFiscal.opcional(nome, valor);
    }

    private static String texto(String valor) {
        return XmlFiscal.texto(valor);
    }

    private static String dec(BigDecimal valor, int casas) {
        return XmlFiscal.dec(valor, casas);
    }

    private static BigDecimal nz(BigDecimal v) {
        return XmlFiscal.nz(v);
    }

    private static boolean positivo(BigDecimal v) {
        return XmlFiscal.positivo(v);
    }

    private static boolean vazio(String s) {
        return XmlFiscal.vazio(s);
    }

    private static <T> T ouEntao(T valor, T alternativa) {
        return XmlFiscal.ouEntao(valor, alternativa);
    }

    private static String apenasDigitos(String s) {
        return XmlFiscal.apenasDigitos(s);
    }

    private static String apenasAlfanumerico(String s) {
        return XmlFiscal.apenasAlfanumerico(s);
    }
}
