package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.tempo.FusoDaUf;
import com.vetor.niner.fiscal.configuracao.CsrtService;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DestinatarioDevolucao;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DevolucaoParaMontar;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.ItemDevolucao;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.TotaisDevolucao;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Emitente;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.MontagemInvalidaException;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ResponsavelTecnico;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.XmlMontado;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

import static com.vetor.niner.fiscal.documento.XmlFiscal.apenasAlfanumerico;
import static com.vetor.niner.fiscal.documento.XmlFiscal.apenasDigitos;
import static com.vetor.niner.fiscal.documento.XmlFiscal.codigoUfDe;
import static com.vetor.niner.fiscal.documento.XmlFiscal.dec;
import static com.vetor.niner.fiscal.documento.XmlFiscal.nz;
import static com.vetor.niner.fiscal.documento.XmlFiscal.opcional;
import static com.vetor.niner.fiscal.documento.XmlFiscal.positivo;
import static com.vetor.niner.fiscal.documento.XmlFiscal.tag;
import static com.vetor.niner.fiscal.documento.XmlFiscal.texto;
import static com.vetor.niner.fiscal.documento.XmlFiscal.vazio;

/**
 * Montagem do XML da <b>NF-e de devolução</b> (modelo 55, entrada) — §10.2 do estudo fiscal,
 * bloco B9. Puro, sem I/O, como {@link MontadorXmlNfce}: só transforma {@link DevolucaoParaMontar}
 * em XML.
 *
 * <h2>Por que é um montador separado, e não um parâmetro do MontadorXmlNfce</h2>
 *
 * <p>Os dois compartilham o mesmo XSD e ~70% da estrutura, mas divergem exatamente onde erro custa
 * caro: aqui a tributação <b>não é calculada</b> (vem espelhada da nota original — ver
 * {@link MontagemDevolucaoDtos}), o {@code dest} é obrigatório e completo, existem dois níveis de
 * referência à nota de origem, e não há QR Code. Espremer isso em condicionais dentro do montador
 * da NFC-e — que já emite nota de verdade em produção — trocaria duplicação por risco de
 * regressão num caminho crítico. O que <b>é</b> compartilhado de fato (escape, formatação decimal,
 * código de UF) mora em {@link XmlFiscal}, justamente para não divergir.
 *
 * <h2>Ajuste SINIEF 8/2026 (confirmado com o contador em 2026-08-19)</h2>
 *
 * <p>A referência à nota de origem é feita <b>só a nível de item</b>: cada {@code det} leva um
 * {@code DFeReferenciado} com {@code chaveAcesso} + {@code nItem} do item correspondente na NFC-e.
 * É isso que torna a devolução <b>parcial</b> rastreável — e parcial é o caso comum do balcão.
 *
 * <p>⚠️ <b>Não</b> existe {@code ide/NFref/refNFe} junto. A primeira versão referenciava nos dois
 * níveis (leitura literal do Ajuste), o XSD aceitou, e a <b>SEFAZ rejeitou na transmissão real</b>:
 * <i>"NF-e com referenciamento de documento a nivel de nota e a nivel de item"</i> (cStat 1010,
 * 2026-08-19, PR homologação). Os níveis são mutuamente exclusivos — escolhido o de item, que é
 * estritamente mais informativo (a chave está lá também, mais o {@code nItem}). Lição que vale
 * além daqui: <b>o XSD não é o contrato completo</b>; regra de validação da SEFAZ só aparece
 * transmitindo.
 */
@Component
public class MontadorXmlNfeDevolucao {

    private static final String VERSAO_LAYOUT = "4.00";
    private static final int MODELO_NFE = 55;
    private static final String SEM_GTIN = "SEM GTIN";
    /** Fator de 100 para derivar o `pST` do par base+valor gravado na nota original. */
    private static final BigDecimal CEM = new BigDecimal("100");
    /** {@code tpEmis = 1} — normal. A devolução não entra em contingência offline: é operação de
     *  retaguarda, sem cliente esperando no caixa (diferente da venda, §9.7). */
    private static final int TP_EMIS_NORMAL = 1;

    /** Mesma frase e mesmo motivo do {@code MontadorXmlNfce}: em homologação a SEFAZ exige esta
     *  razão social no destinatário (cStat 598 senão). */
    private static final String FRASE_HOMOLOGACAO_DESTINATARIO =
            "NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";

    private static final DateTimeFormatter DH =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);

    private static final Set<String> CSOSN_GRUPO_102 = Set.of("102", "103", "300", "400");
    private static final Set<String> CST_GRUPO_40 = Set.of("40", "41", "50");
    private static final Set<String> CST_CONTRIB_NT = Set.of("04", "05", "06", "07", "08", "09");
    private static final Set<String> CST_CONTRIB_ALIQ = Set.of("01", "02");

    public XmlMontado montar(DevolucaoParaMontar dev) {
        validar(dev);

        // Fuso da UF do emitente — ver o comentário equivalente em MontadorXmlNfce: TDateTimeUTC
        // proíbe o sufixo Z, data vinda do banco chega em UTC, e a UF é quem diz qual é o offset.
        OffsetDateTime emissaoLocal = dev.emissao()
                .atZoneSameInstant(FusoDaUf.de(dev.emitente().uf())).toOffsetDateTime();
        String cnpjEmitente = apenasAlfanumerico(dev.emitente().cnpj());
        String chave = ChaveAcesso.montar(codigoUfDe(dev.emitente().uf()), emissaoLocal, cnpjEmitente,
                MODELO_NFE, dev.serie(), dev.numero(), TP_EMIS_NORMAL, dev.codigoNumerico());

        StringBuilder xml = new StringBuilder(8192);
        xml.append("<NFe xmlns=\"").append(MontadorXmlNfce.NS).append("\">");
        xml.append("<infNFe versao=\"").append(VERSAO_LAYOUT).append("\" Id=\"NFe").append(chave).append("\">");
        montarIde(xml, dev, chave, emissaoLocal);
        montarEmit(xml, dev.emitente(), cnpjEmitente);
        montarDest(xml, dev);
        montarItens(xml, dev);
        montarTotal(xml, dev.totais());
        xml.append("<transp><modFrete>9</modFrete></transp>");   // 9 = sem frete (cliente trouxe)
        montarPag(xml);
        montarInfAdic(xml, dev);
        montarInfRespTec(xml, dev.responsavelTecnico(), chave);
        xml.append("</infNFe>");
        xml.append("</NFe>");

        return new XmlMontado(chave, xml.toString());
    }

    // ---------------------------------------------------------------- ide

    private void montarIde(StringBuilder xml, DevolucaoParaMontar dev, String chave, OffsetDateTime emissaoLocal) {
        Emitente e = dev.emitente();
        xml.append("<ide>")
                .append(tag("cUF", "%02d".formatted(codigoUfDe(e.uf()))))
                .append(tag("cNF", "%08d".formatted(dev.codigoNumerico())))
                .append(tag("natOp", texto(dev.naturezaOperacao())))
                .append(tag("mod", String.valueOf(MODELO_NFE)))
                .append(tag("serie", String.valueOf(dev.serie())))
                .append(tag("nNF", String.valueOf(dev.numero())))
                .append(tag("dhEmi", emissaoLocal.format(DH)))
                // 0 = entrada (devolução de VENDA, a mercadoria volta para a loja);
                // 1 = saída (devolução de COMPRA, a mercadoria volta ao fornecedor).
                .append(tag("tpNF", String.valueOf(dev.tipoNf())))
                .append(tag("idDest", String.valueOf(dev.idDestino())))
                .append(tag("cMunFG", String.valueOf(e.codigoMunicipioIbge())))
                .append(tag("tpImp", "1"))                    // 1 = DANFE retrato (A4), não o 4 da NFC-e
                .append(tag("tpEmis", String.valueOf(TP_EMIS_NORMAL)))
                .append(tag("cDV", chave.substring(43)))
                .append(tag("tpAmb", String.valueOf(dev.ambiente().codigo())))
                .append(tag("finNFe", "4"))                   // 4 = DEVOLUÇÃO de mercadoria
                // 0 = normal: quem recebe a mercadoria de volta é a LOJA, que não é consumidora
                // final dela (volta pro estoque para ser revendida).
                .append(tag("indFinal", "0"))
                // 0 = não se aplica. `indPres` descreve a presença do COMPRADOR numa operação de
                // saída; numa nota de entrada emitida pela própria loja não há comprador. O XSD
                // documenta o 0 exatamente para este tipo de nota ("complementar ou de ajuste").
                .append(tag("indPres", "0"))
                .append(tag("procEmi", "0"))
                .append(tag("verProc", texto(dev.versaoAplicativo())))
                // ⚠️ NÃO existe `NFref` aqui — a referência à nota de origem é SÓ por item
                // (`det/DFeReferenciado`). Ver o javadoc da classe: a SEFAZ rejeita (cStat 1010)
                // quando os dois níveis vêm juntos.
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
     * Grupo {@code dest} — <b>obrigatório e completo</b> na NF-e 55 (diferente da NFC-e, onde é
     * omitido na venda anônima). Pelo Ajuste SINIEF 8/2026, leva os dados do destinatário da nota
     * de saída original; quando aquela NFC-e saiu sem consumidor identificado, o assembler já
     * resolveu isto para a própria loja (ver {@link DestinatarioDevolucao}).
     */
    private void montarDest(StringBuilder xml, DevolucaoParaMontar dev) {
        DestinatarioDevolucao d = dev.destinatario();
        String doc = apenasAlfanumerico(d.cpfCnpj());
        xml.append("<dest>");
        if (doc != null && doc.length() == 11) {
            xml.append(tag("CPF", doc));
        } else {
            xml.append(tag("CNPJ", doc));
        }
        String xNome = dev.ambiente() == AmbienteSefaz.HOMOLOGACAO ? FRASE_HOMOLOGACAO_DESTINATARIO : d.nome();
        xml.append(tag("xNome", texto(xNome)))
                .append("<enderDest>")
                .append(tag("xLgr", texto(d.logradouro())))
                .append(tag("nro", texto(d.numero())))
                .append(opcional("xCpl", d.complemento()))
                .append(tag("xBairro", texto(d.bairro())))
                .append(tag("cMun", String.valueOf(d.codigoMunicipioIbge())))
                .append(tag("xMun", texto(d.municipio())))
                .append(tag("UF", d.uf()))
                .append(opcional("CEP", apenasDigitos(d.cep())))
                .append(tag("cPais", "1058"))
                .append(tag("xPais", "BRASIL"))
                .append(opcional("fone", apenasDigitos(d.telefone())))
                .append("</enderDest>")
                .append(tag("indIEDest", String.valueOf(d.indicadorIe())));
        // IE só quando o destinatário é contribuinte (indIEDest = 1) — para 2/9 o XSD não aceita.
        if (d.indicadorIe() == 1) {
            xml.append(tag("IE", apenasAlfanumerico(d.inscricaoEstadual())));
        }
        xml.append("</dest>");
    }

    // ---------------------------------------------------------------- det

    private void montarItens(StringBuilder xml, DevolucaoParaMontar dev) {
        for (ItemDevolucao item : dev.itens()) {
            xml.append("<det nItem=\"").append(item.nItem()).append("\">");
            montarProd(xml, item, dev);
            montarImposto(xml, item);
            // ⚠️ Ajuste SINIEF 8/2026 — referência POR ITEM. É isto que torna a devolução parcial
            // rastreável: sem `nItem`, a SEFAZ não sabe qual dos itens da nota original está
            // voltando quando o cliente devolve só parte da compra.
            //
            // ⚠️ `DFeReferenciado` é filho de `det` e vem DEPOIS de `imposto` (a sequence do XSD é
            // prod → imposto → impostoDevol? → infAdProd? → obsItem? → vItem? → DFeReferenciado?).
            // A primeira versão o colocou dentro de `prod`, e o schema recusou — o nome sugere
            // "referência do produto", mas ele descreve o ITEM inteiro da nota.
            xml.append("<DFeReferenciado>")
                    .append(tag("chaveAcesso", dev.chaveNotaOriginal()))
                    .append(tag("nItem", String.valueOf(item.nItemOriginal())))
                    .append("</DFeReferenciado>");
            xml.append("</det>");
        }
    }

    private void montarProd(StringBuilder xml, ItemDevolucao item, DevolucaoParaMontar dev) {
        String gtin = vazio(item.gtin()) ? SEM_GTIN : item.gtin();
        xml.append("<prod>")
                .append(tag("cProd", texto(item.codigoProduto())))
                .append(tag("cEAN", gtin))
                .append(tag("xProd", texto(item.descricao())))
                .append(tag("NCM", texto(item.ncm())))
                .append(opcional("CEST", item.cest()))
                .append(tag("CFOP", item.cfop()))
                .append(tag("uCom", texto(item.unidadeComercial())))
                .append(tag("qCom", dec(item.quantidade(), 4)))
                .append(tag("vUnCom", dec(item.valorUnitario(), 6)))
                .append(tag("vProd", dec(item.valorProduto(), 2)))
                .append(tag("cEANTrib", gtin))
                .append(tag("uTrib", texto(XmlFiscal.ouEntao(item.unidadeTributavel(), item.unidadeComercial()))))
                .append(tag("qTrib", dec(XmlFiscal.ouEntao(item.quantidadeTributavel(), item.quantidade()), 4)))
                .append(tag("vUnTrib", dec(XmlFiscal.ouEntao(item.valorUnitarioTributavel(), item.valorUnitario()), 6)));
        // ⚠️ `vDesc` entre `vUnTrib` e `indTot` — a ordem é do XSD, não escolha nossa (é a mesma
        // sequência do MontadorXmlNfce). Só sai quando é positivo: a tag é opcional no schema, e
        // um `0.00` em toda linha só polui o XML.
        if (item.valorDesconto() != null && item.valorDesconto().signum() > 0) {
            xml.append(tag("vDesc", dec(item.valorDesconto(), 2)));
        }
        xml.append(tag("indTot", "1"))
                .append("</prod>");
    }

    /**
     * Tributação <b>espelhada</b> da nota original — nenhum valor é calculado aqui. A escolha do
     * grupo do XML segue as mesmas regras do {@code MontadorXmlNfce} (é o mesmo XSD), mas os
     * números vêm de {@code documento_fiscal_item} da NFC-e de saída.
     */
    private void montarImposto(StringBuilder xml, ItemDevolucao item) {
        xml.append("<imposto>");
        if (positivo(item.valorTotalTributos())) {
            xml.append(tag("vTotTrib", dec(item.valorTotalTributos(), 2)));
        }
        montarIcms(xml, item);
        montarContribuicao(xml, "PIS", item.cstPis(), item.baseCalculoPis(), item.aliquotaPis(), item.valorPis());
        montarContribuicao(xml, "COFINS", item.cstCofins(), item.baseCalculoCofins(), item.aliquotaCofins(),
                item.valorCofins());
        montarIbsCbs(xml, item);
        xml.append("</imposto>");
    }

    private void montarIcms(StringBuilder xml, ItemDevolucao item) {
        xml.append("<ICMS>");
        String orig = String.valueOf(item.origemMercadoria());
        String csosn = item.csosn();
        String cst = item.cstIcms();

        if (csosn != null) {
            if (CSOSN_GRUPO_102.contains(csosn)) {
                xml.append("<ICMSSN102>").append(tag("orig", orig)).append(tag("CSOSN", csosn)).append("</ICMSSN102>");
            } else if ("500".equals(csosn)) {
                // ⛔ O ST retido também vai na DEVOLUÇÃO (2026-09-02, pendência 23). A nota de
                // entrada é modelo 55 — o mesmo modelo que a SEFAZ recusa com `cStat 938` quando o
                // bloco falta. E aqui os valores são **espelhados** da NFC-e original
                // (`documento_fiscal_item.base_st_retido`/`icms_st_retido`), como todo o resto da
                // tributação: recalcular seria inventar um ST que a venda não declarou.
                //
                // ⚠️ `pST` é DERIVADO (valor / base × 100) porque a coluna não existe em
                // `documento_fiscal_item` — o que está gravado é o par base+valor, e a alíquota
                // coerente com eles é essa. Guardar uma terceira coluna só para isso criaria a
                // chance de ela divergir das outras duas.
                xml.append("<ICMSSN500>").append(tag("orig", orig)).append(tag("CSOSN", csosn));
                BigDecimal baseRet = item.baseStRetido();
                BigDecimal valorRet = item.icmsStRetido();
                if (baseRet != null && valorRet != null && baseRet.signum() > 0) {
                    xml.append(tag("vBCSTRet", dec(baseRet, 2)))
                            .append(tag("pST", dec(valorRet.multiply(CEM)
                                    .divide(baseRet, 4, java.math.RoundingMode.HALF_UP), 4)))
                            .append(tag("vICMSSTRet", dec(valorRet, 2)));
                }
                xml.append("</ICMSSN500>");
            } else {
                throw new MontagemInvalidaException(
                        ("A NFC-e original tem CSOSN %s, que o v1 não sabe espelhar numa devolução "
                                + "(exige grupo de ST completo). Trate esta devolução com o contador.").formatted(csosn));
            }
        } else if (cst != null) {
            if ("00".equals(cst)) {
                xml.append("<ICMS00>")
                        .append(tag("orig", orig)).append(tag("CST", cst))
                        .append(tag("modBC", "3"))
                        .append(tag("vBC", dec(item.baseCalculoIcms(), 2)))
                        .append(tag("pICMS", dec(item.aliquotaIcms(), 2)))
                        .append(tag("vICMS", dec(item.valorIcms(), 2)))
                        .append("</ICMS00>");
            } else if (CST_GRUPO_40.contains(cst)) {
                xml.append("<ICMS40>").append(tag("orig", orig)).append(tag("CST", cst)).append("</ICMS40>");
            } else if ("60".equals(cst)) {
                xml.append("<ICMS60>").append(tag("orig", orig)).append(tag("CST", cst)).append("</ICMS60>");
            } else if ("20".equals(cst)) {
                xml.append("<ICMS20>")
                        .append(tag("orig", orig)).append(tag("CST", cst))
                        .append(tag("modBC", "3"))
                        .append(tag("pRedBC", dec(item.percentualReducaoBc(), 2)))
                        .append(tag("vBC", dec(item.baseCalculoIcms(), 2)))
                        .append(tag("pICMS", dec(item.aliquotaIcms(), 2)))
                        .append(tag("vICMS", dec(item.valorIcms(), 2)))
                        .append("</ICMS20>");
            } else {
                throw new MontagemInvalidaException(
                        ("A NFC-e original tem CST de ICMS %s, que o v1 não sabe espelhar numa devolução "
                                + "(exige ST ou diferimento). Trate esta devolução com o contador.").formatted(cst));
            }
        } else {
            throw new MontagemInvalidaException(
                    "Item da nota original sem CSOSN nem CST de ICMS — devolução não pode ser montada.");
        }
        xml.append("</ICMS>");
    }

    private void montarContribuicao(StringBuilder xml, String nome, String cst,
                                    BigDecimal base, BigDecimal aliquota, BigDecimal valor) {
        xml.append('<').append(nome).append('>');
        if (CST_CONTRIB_NT.contains(cst)) {
            xml.append('<').append(nome).append("NT>").append(tag("CST", cst))
                    .append("</").append(nome).append("NT>");
        } else if (CST_CONTRIB_ALIQ.contains(cst)) {
            xml.append('<').append(nome).append("Aliq>")
                    .append(tag("CST", cst))
                    .append(tag("vBC", dec(base, 2)))
                    .append(tag("p" + nome, dec(aliquota, 2)))
                    .append(tag("v" + nome, dec(valor, 2)))
                    .append("</").append(nome).append("Aliq>");
        } else {
            xml.append('<').append(nome).append("Outr>")
                    .append(tag("CST", cst))
                    .append(tag("vBC", dec(base, 2)))
                    .append(tag("p" + nome, dec(aliquota, 2)))
                    .append(tag("v" + nome, dec(valor, 2)))
                    .append("</").append(nome).append("Outr>");
        }
        xml.append("</").append(nome).append('>');
    }

    private void montarIbsCbs(StringBuilder xml, ItemDevolucao item) {
        if (vazio(item.cstIbsCbs()) || vazio(item.cclasstrib())) {
            return;
        }
        BigDecimal vIbs = nz(item.valorIbsUf()).add(nz(item.valorIbsMun())).setScale(2, RoundingMode.HALF_UP);
        xml.append("<IBSCBS>")
                .append(tag("CST", item.cstIbsCbs()))
                .append(tag("cClassTrib", item.cclasstrib()))
                .append("<gIBSCBS>")
                .append(tag("vBC", dec(item.baseIbsCbs(), 2)))
                .append("<gIBSUF>")
                .append(tag("pIBSUF", dec(item.aliquotaIbsUf(), 4)))
                .append(tag("vIBSUF", dec(item.valorIbsUf(), 2)))
                .append("</gIBSUF>")
                .append("<gIBSMun>")
                .append(tag("pIBSMun", dec(item.aliquotaIbsMun(), 4)))
                .append(tag("vIBSMun", dec(item.valorIbsMun(), 2)))
                .append("</gIBSMun>")
                .append(tag("vIBS", dec(vIbs, 2)))
                .append("<gCBS>")
                .append(tag("pCBS", dec(item.aliquotaCbs(), 4)))
                .append(tag("vCBS", dec(item.valorCbs(), 2)))
                .append("</gCBS>")
                .append("</gIBSCBS>")
                .append("</IBSCBS>");
    }

    // ---------------------------------------------------------------- total / pag / infAdic

    private void montarTotal(StringBuilder xml, TotaisDevolucao t) {
        xml.append("<total><ICMSTot>")
                .append(tag("vBC", dec(t.baseCalculoIcms(), 2)))
                .append(tag("vICMS", dec(t.valorIcms(), 2)))
                .append(tag("vICMSDeson", "0.00"))
                .append(tag("vFCP", "0.00"))
                .append(tag("vBCST", dec(t.baseCalculoSt(), 2)))
                .append(tag("vST", dec(t.valorIcmsSt(), 2)))
                .append(tag("vFCPST", "0.00"))
                .append(tag("vFCPSTRet", "0.00"))
                .append(tag("vProd", dec(t.valorProdutos(), 2)))
                .append(tag("vFrete", "0.00"))
                .append(tag("vSeg", "0.00"))
                .append(tag("vDesc", dec(t.valorDesconto(), 2)))
                .append(tag("vII", "0.00"))
                .append(tag("vIPI", "0.00"))
                .append(tag("vIPIDevol", "0.00"))
                .append(tag("vPIS", dec(t.valorPis(), 2)))
                .append(tag("vCOFINS", dec(t.valorCofins(), 2)))
                .append(tag("vOutro", "0.00"))
                .append(tag("vNF", dec(t.valorTotal(), 2)));
        if (positivo(t.valorTotalTributos())) {
            xml.append(tag("vTotTrib", dec(t.valorTotalTributos(), 2)));
        }
        xml.append("</ICMSTot></total>");
    }

    /**
     * {@code tPag = 90} (Sem Pagamento) — a devolução não movimenta dinheiro na nota: o crédito ao
     * cliente sai pelo vale-mercadoria, que é documento interno da loja, não fiscal.
     *
     * <p>⚠️ {@code vPag} vai junto, com {@code 0.00}: a <b>anotação</b> do XSD diz que ele "poderá
     * ser omitida quando tPag=90", mas o <b>schema</b> o declara obrigatório dentro de
     * {@code detPag} — e é o schema que valida. Achado validando contra o XSD oficial; seguir a
     * anotação teria produzido uma nota rejeitada.
     */
    private void montarPag(StringBuilder xml) {
        xml.append("<pag><detPag>")
                .append(tag("tPag", "90"))
                .append(tag("vPag", "0.00"))
                .append("</detPag></pag>");
    }

    private void montarInfAdic(StringBuilder xml, DevolucaoParaMontar dev) {
        if (vazio(dev.informacoesComplementares())) {
            return;
        }
        xml.append("<infAdic>").append(tag("infCpl", texto(dev.informacoesComplementares()))).append("</infAdic>");
    }

    /**
     * ⚠️ {@code idCSRT}/{@code hashCSRT} são <b>obrigatórios na NF-e modelo 55</b> — sem eles a
     * SEFAZ rejeita com cStat 975, mesmo com todo o resto do {@code infRespTec} preenchido
     * (achado transmitindo de verdade, 2026-08-19; a NFC-e do PR aceita sem). Por isso a validação
     * de entrada exige o CSRT configurado, em vez de emitir uma nota que já se sabe recusada (F11
     * — falha explícita e cedo, não uma rejeição incompreensível depois).
     */
    private void montarInfRespTec(StringBuilder xml, ResponsavelTecnico r, String chaveAcesso) {
        if (r == null) {
            return;
        }
        xml.append("<infRespTec>")
                .append(tag("CNPJ", apenasAlfanumerico(r.cnpj())))
                .append(tag("xContato", texto(r.contato())))
                .append(tag("email", texto(r.email())))
                .append(tag("fone", apenasDigitos(r.telefone())));
        if (!vazio(r.csrt())) {
            xml.append(tag("idCSRT", texto(r.idCsrt())))
                    .append(tag("hashCSRT", XmlFiscal.hashCsrt(r.csrt(), chaveAcesso)));
        }
        xml.append("</infRespTec>");
    }

    // ---------------------------------------------------------------- validação de entrada

    private void validar(DevolucaoParaMontar dev) {
        if (dev == null) {
            throw new MontagemInvalidaException("Devolução não informada.");
        }
        if (dev.itens() == null || dev.itens().isEmpty()) {
            throw new MontagemInvalidaException("Devolução sem itens.");
        }
        if (dev.chaveNotaOriginal() == null || dev.chaveNotaOriginal().length() != 44) {
            throw new MontagemInvalidaException(
                    "A NF-e de devolução precisa referenciar a chave de acesso da nota de venda original "
                            + "(44 posições) — exigência do Ajuste SINIEF 8/2026.");
        }
        if (dev.emitente() == null) {
            throw new MontagemInvalidaException("Emitente não informado.");
        }
        if (dev.destinatario() == null) {
            throw new MontagemInvalidaException(
                    "A NF-e de devolução exige destinatário (grupo dest é obrigatório no modelo 55).");
        }
        String doc = apenasAlfanumerico(dev.destinatario().cpfCnpj());
        if (doc == null || (doc.length() != 11 && doc.length() != 14)) {
            throw new MontagemInvalidaException(
                    "CPF/CNPJ do destinatário da devolução inválido (esperado 11 ou 14 posições).");
        }
        // F11 — falhar aqui, com o motivo por extenso, em vez de montar e assinar uma nota que a
        // SEFAZ já vai recusar com cStat 975 (mensagem que não diz onde configurar o CSRT).
        if (dev.responsavelTecnico() == null || vazio(dev.responsavelTecnico().csrt())) {
            throw new MontagemInvalidaException(
                    CsrtService.mensagemFaltando(
                            dev.emitente() == null ? null : dev.emitente().uf(), 55));
        }
        for (ItemDevolucao item : dev.itens()) {
            if (vazio(item.cfop())) {
                throw new MontagemInvalidaException(
                        "Item %d da devolução sem CFOP.".formatted(item.nItem()));
            }
            if (item.nItemOriginal() <= 0) {
                throw new MontagemInvalidaException(
                        ("Item %d da devolução sem o número do item correspondente na nota original — "
                                + "sem ele a referência item a item do Ajuste SINIEF 8/2026 não pode ser montada.")
                                .formatted(item.nItem()));
            }
        }
    }
}
