package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.*;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.Contribuicao;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.IbsCbs;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.Icms;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.ItemTributado;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** MOC: em homologação, na NFC-e a frase vai no {@code xProd} do <b>primeiro item</b> —
     *  não na razão social do destinatário (que a NFC-e sem CPF nem tem). Confirmado no B0. */
    private static final String FRASE_HOMOLOGACAO =
            "NOTA FISCAL EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";

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
        // Em homologação o nome do destinatário é livre; o que a SEFAZ exige na NFC-e é a frase
        // no xProd do primeiro item (ver montarItens).
        xml.append(opcional("xNome", d.nome()))
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
            montarImposto(xml, trib);
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
    private void montarImposto(StringBuilder xml, ItemTributado trib) {
        xml.append("<imposto>");
        // vTotTrib (Lei 12.741) é OMITIDO enquanto cfg_ibpt não estiver carregada: o campo é
        // opcional no XSD, e emitir 0,00 afirmaria "zero tributos" no cupom do consumidor — uma
        // informação errada é pior que a ausência dela (§8.6).
        montarIcms(xml, trib.icms());
        montarContribuicao(xml, "PIS", trib.pis());
        montarContribuicao(xml, "COFINS", trib.cofins());
        montarIbsCbs(xml, trib.ibsCbs());
        xml.append("</imposto>");
    }

    /**
     * O grupo de ICMS é escolhido pelo CSOSN/CST — cada um com seu conjunto fechado de campos.
     * Origem da mercadoria vem sempre 0 (nacional): o motor não a carrega, e o cadastro de
     * produto tem {@code origem_mercadoria NOT NULL DEFAULT 0}. ⚠️ Quando o item da nota passar
     * a trazer a origem real do produto, é aqui que ela entra.
     */
    private void montarIcms(StringBuilder xml, Icms icms) {
        xml.append("<ICMS>");
        String csosn = icms.csosn();
        String cst = icms.cst();

        if (csosn != null) {
            if (CSOSN_GRUPO_102.contains(csosn)) {
                xml.append("<ICMSSN102>").append(tag("orig", "0")).append(tag("CSOSN", csosn)).append("</ICMSSN102>");
            } else if ("500".equals(csosn)) {
                // O bloco de ST retido (vBCSTRet/pST/vICMSSTRet) é <xs:sequence minOccurs="0">
                // no XSD — e o motor não calcula esses valores (são do ST retido lá atrás, na
                // compra, não desta venda). Sai só orig+CSOSN, que o schema aceita.
                xml.append("<ICMSSN500>").append(tag("orig", "0")).append(tag("CSOSN", csosn)).append("</ICMSSN500>");
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
                        .append(tag("orig", "0")).append(tag("CST", cst))
                        .append(tag("modBC", "3"))              // 3 = valor da operação
                        .append(tag("vBC", dec(icms.baseCalculo(), 2)))
                        .append(tag("pICMS", dec(icms.aliquota(), 2)))
                        .append(tag("vICMS", dec(icms.valor(), 2)))
                        .append("</ICMS00>");
            } else if (CST_GRUPO_40.contains(cst)) {
                xml.append("<ICMS40>").append(tag("orig", "0")).append(tag("CST", cst)).append("</ICMS40>");
            } else if ("60".equals(cst)) {
                xml.append("<ICMS60>").append(tag("orig", "0")).append(tag("CST", cst)).append("</ICMS60>");
            } else if ("20".equals(cst)) {
                xml.append("<ICMS20>")
                        .append(tag("orig", "0")).append(tag("CST", cst))
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
                .append(tag("vNF", dec(t.valorNota(), 2)))
                .append("</ICMSTot></total>");
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
     * <p><b>Online</b> ({@code tpEmis} 1/3/4): {@code ?p=<chave44>|3|<tpAmb>}. O QR não precisa
     * dizer mais nada porque quem consulta chega na SEFAZ e lê a nota inteira de lá. O estudo
     * documentava só {@code |<versao>} e <b>omitia o tpAmb</b> — era essa a causa dos três
     * {@code cStat 225} do B0.
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
                : "%s?p=%s|3|%d".formatted(nota.urls().urlQrCode(), chave, nota.ambiente().codigo());

        xml.append("<infNFeSupl>")
                .append("<qrCode><![CDATA[").append(qr).append("]]></qrCode>")
                .append(tag("urlChave", nota.urls().urlConsultaChave()))
                .append("</infNFeSupl>");
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

    /** Código IBGE da UF (`cUF`) — os 2 primeiros dígitos do código de município. */
    private static int codigoUfDe(String uf) {
        Integer codigo = CODIGO_UF.get(uf == null ? "" : uf.trim().toUpperCase(Locale.ROOT));
        if (codigo == null) {
            throw new MontagemInvalidaException("UF do emitente inválida: %s.".formatted(uf));
        }
        return codigo;
    }

    private static final Map<String, Integer> CODIGO_UF = Map.ofEntries(
            Map.entry("RO", 11), Map.entry("AC", 12), Map.entry("AM", 13), Map.entry("RR", 14),
            Map.entry("PA", 15), Map.entry("AP", 16), Map.entry("TO", 17), Map.entry("MA", 21),
            Map.entry("PI", 22), Map.entry("CE", 23), Map.entry("RN", 24), Map.entry("PB", 25),
            Map.entry("PE", 26), Map.entry("AL", 27), Map.entry("SE", 28), Map.entry("BA", 29),
            Map.entry("MG", 31), Map.entry("ES", 32), Map.entry("RJ", 33), Map.entry("SP", 35),
            Map.entry("PR", 41), Map.entry("SC", 42), Map.entry("RS", 43), Map.entry("MS", 50),
            Map.entry("MT", 51), Map.entry("GO", 52), Map.entry("DF", 53));

    private static String tag(String nome, String valor) {
        return "<" + nome + ">" + valor + "</" + nome + ">";
    }

    /** Emite a tag só quando há valor — campo opcional vazio é rejeitado, não ignorado. */
    private static String opcional(String nome, String valor) {
        return vazio(valor) ? "" : tag(nome, texto(valor));
    }

    /**
     * Escapa o que o XML não aceita cru. Texto de produto vem do cadastro do lojista e pode ter
     * {@code &} ("SABÃO P&G"), que sozinho quebra o XML — e quebraria só na nota daquele item,
     * o tipo de bug que só aparece em produção.
     */
    private static String texto(String valor) {
        if (valor == null) {
            return "";
        }
        return valor.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Formata para o pattern do XSD: {@code 0|0\.[0-9]{2}|[1-9][0-9]{0,12}(\.[0-9]{2})?} — ou
     * seja, sem zero à esquerda e sempre com as casas decimais completas.
     * {@code BigDecimal.setScale().toPlainString()} já produz exatamente isso.
     */
    private static String dec(BigDecimal valor, int casas) {
        return nz(valor).setScale(casas, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean positivo(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    private static <T> T ouEntao(T valor, T alternativa) {
        return valor == null ? alternativa : valor;
    }

    private static String apenasDigitos(String s) {
        return s == null ? null : s.replaceAll("\\D", "");
    }

    /** CNPJ/IE podem ser alfanuméricos (IN RFB 2.229/2024) — nunca limpar com digits-only. */
    private static String apenasAlfanumerico(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
    }
}
