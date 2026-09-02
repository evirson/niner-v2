package com.vetor.niner;

import com.vetor.niner.fiscal.documento.MontadorXmlNfeDevolucao;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DestinatarioDevolucao;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DevolucaoParaMontar;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.ItemDevolucao;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.TotaisDevolucao;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Emitente;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.MontagemInvalidaException;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ResponsavelTecnico;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.XmlMontado;
import com.vetor.niner.fiscal.documento.ValidadorXsd;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Montagem da NF-e de devolução (modelo 55, entrada) — bloco B9, §10.2 do estudo fiscal.
 *
 * <p>Mesma filosofia do {@code MontadorXmlNfceTest}: monta uma devolução realista e <b>valida
 * contra o XSD oficial</b>, que é a única prova de que a estrutura está correta. O que importa
 * especialmente aqui é o <b>Ajuste SINIEF 8/2026</b> (referência dupla — chave no cabeçalho +
 * item a item no {@code det}) e a garantia de que a tributação é <b>espelhada</b>, nunca
 * recalculada.
 */
class MontadorXmlNfeDevolucaoTest {

    private final MontadorXmlNfeDevolucao montador = new MontadorXmlNfeDevolucao();
    private final ValidadorXsd validador = new ValidadorXsd();

    /** Fixture ancorada ao meio-dia — nunca `now()`, ver feedback_testes_frageis_por_relogio. */
    private static final OffsetDateTime EMISSAO =
            OffsetDateTime.of(2026, 8, 19, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

    /** Chave de uma NFC-e real de homologação (a venda 590 deste projeto) — 44 posições. */
    private static final String CHAVE_ORIGINAL = "41260837829453000135650010000000421480365360";

    /** Mesmo esqueleto do teste da NFC-e: o XSD exige `ds:Signature` dentro de `TNFe`, e assinar
     *  é responsabilidade do B6 — aqui se prova tudo menos a assinatura. */
    private static String comAssinaturaFalsa(String xml, String chave) {
        String signature = """
                <Signature xmlns="http://www.w3.org/2000/09/xmldsig#"><SignedInfo>\
                <CanonicalizationMethod Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>\
                <SignatureMethod Algorithm="http://www.w3.org/2000/09/xmldsig#rsa-sha1"/>\
                <Reference URI="#NFe%s"><Transforms>\
                <Transform Algorithm="http://www.w3.org/2000/09/xmldsig#enveloped-signature"/>\
                <Transform Algorithm="http://www.w3.org/TR/2001/REC-xml-c14n-20010315"/>\
                </Transforms><DigestMethod Algorithm="http://www.w3.org/2000/09/xmldsig#sha1"/>\
                <DigestValue>AAAA</DigestValue></Reference></SignedInfo>\
                <SignatureValue>AAAA</SignatureValue>\
                <KeyInfo><X509Data><X509Certificate>AAAA</X509Certificate></X509Data></KeyInfo>\
                </Signature>""".formatted(chave);
        return xml.replace("</NFe>", signature + "</NFe>");
    }

    private void validarEstrutura(XmlMontado montado) {
        validador.validarNfe(comAssinaturaFalsa(montado.xml(), montado.chaveAcesso()));
    }

    // ------------------------------------------------------------------ o caso central

    @Test
    void devolucaoEhValidaPeloXsdOficialESaiComoNotaDeEntradaModelo55() {
        XmlMontado montado = montador.montar(devolucaoDeUmItem("102", null));

        assertThatCode(() -> validarEstrutura(montado)).doesNotThrowAnyException();
        assertThat(montado.chaveAcesso()).hasSize(44).containsOnlyDigits();
        assertThat(montado.xml())
                .contains("<mod>55</mod>")
                .contains("<tpNF>0</tpNF>")      // 0 = entrada
                .contains("<finNFe>4</finNFe>")  // 4 = devolução
                .contains("<tpImp>1</tpImp>");   // DANFE retrato (A4), não o 4 da NFC-e
    }

    /**
     * <b>Ajuste SINIEF 8/2026, o ponto central desta feature.</b> A referência à nota de origem sai
     * <b>só a nível de item</b>: cada {@code det} diz qual item da nota original está voltando, que
     * é o que torna a devolução parcial rastreável.
     *
     * <p>⚠️ A ausência do {@code ide/NFref} é tão testada quanto a presença do
     * {@code DFeReferenciado}, e por um motivo caro: a primeira versão emitia os dois níveis, <b>o
     * XSD aceitou</b>, e a SEFAZ rejeitou na transmissão real com cStat 1010 ("referenciamento de
     * documento a nivel de nota e a nivel de item"). Este assert é a rede que impede o
     * {@code NFref} de voltar por refatoração.
     */
    @Test
    void referenciaANotaOriginalSomenteItemAItem() {
        XmlMontado montado = montador.montar(devolucaoDeUmItem("102", null));

        validarEstrutura(montado);
        assertThat(montado.xml())
                .as("referência item a item (det/DFeReferenciado) — exigência do Ajuste para devolução parcial")
                .contains("<DFeReferenciado><chaveAcesso>" + CHAVE_ORIGINAL + "</chaveAcesso><nItem>3</nItem></DFeReferenciado>");
        assertThat(montado.xml())
                .as("NFref junto do DFeReferenciado é rejeitado pela SEFAZ (cStat 1010)")
                .doesNotContain("<NFref>");
    }

    /**
     * A garantia que separa esta feature de um recálculo: os valores tributários saem <b>iguais</b>
     * aos que entraram (vindos de `documento_fiscal_item` da nota original). Um motor rodando aqui
     * produziria números do cadastro de HOJE — e a devolução divergiria da venda que referencia.
     */
    @Test
    void tributacaoEhEspelhadaDaNotaOriginalNaoRecalculada() {
        ItemDevolucao item = itemBase("102", null)
                .comIcms(new BigDecimal("100.00"), new BigDecimal("19.50"), new BigDecimal("19.50"))
                .build(1, 3);
        XmlMontado montado = montador.montar(devolucaoCom(List.of(item)));

        validarEstrutura(montado);
        // CSOSN 102 não destaca ICMS no XML (grupo ICMSSN102 só tem orig+CSOSN) — o que se prova
        // aqui é que os valores de PIS/COFINS/vTotTrib passaram intactos.
        assertThat(montado.xml())
                .contains("<PIS><PISOutr><CST>99</CST><vBC>0.00</vBC><pPIS>0.00</pPIS><vPIS>0.00</vPIS></PISOutr></PIS>")
                .contains("<vTotTrib>12.34</vTotTrib>");
    }

    @Test
    void csosn500DeStRetidoEhEspelhadoComoNaNotaOriginal() {
        XmlMontado montado = montador.montar(devolucaoDeUmItem("500", null));

        validarEstrutura(montado);
        assertThat(montado.xml()).contains("<ICMSSN500><orig>0</orig><CSOSN>500</CSOSN></ICMSSN500>");
    }

    /** CRT 2 (Simples com excesso de sublimite) emite com CST, não CSOSN — a devolução espelha. */
    @Test
    void cstDeIcmsTributadoEhEspelhadoComBaseEAliquotaDaNotaOriginal() {
        ItemDevolucao item = itemBase(null, "00")
                .comIcms(new BigDecimal("100.00"), new BigDecimal("19.50"), new BigDecimal("19.50"))
                .build(1, 3);
        XmlMontado montado = montador.montar(devolucaoCom(List.of(item)));

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<ICMS00><orig>0</orig><CST>00</CST><modBC>3</modBC>"
                        + "<vBC>100.00</vBC><pICMS>19.50</pICMS><vICMS>19.50</vICMS></ICMS00>");
    }

    /**
     * O grupo {@code dest} é obrigatório no modelo 55 (na NFC-e ele é omitido na venda anônima) —
     * e é justamente o caso mais comum do balcão: NFC-e sem CPF. O assembler resolve para a
     * própria loja; aqui se prova que o XML sai completo e válido nesse cenário.
     */
    @Test
    void consumidorNaoIdentificadoNaVendaOriginalGeraDevolucaoParaAPropriaLoja() {
        DevolucaoParaMontar dev = devolucaoCom(List.of(itemBase("102", null).build(1, 3)),
                destinatarioPropriaLoja());
        XmlMontado montado = montador.montar(dev);

        validarEstrutura(montado);
        assertThat(montado.xml())
                .contains("<dest><CNPJ>37829453000135</CNPJ>")
                .contains("<indIEDest>1</indIEDest>")
                .as("contribuinte (indIEDest=1) leva IE").contains("<IE>9122793165</IE>");
    }

    /**
     * A devolução não movimenta dinheiro na nota — o crédito ao cliente é o vale-mercadoria,
     * documento interno. {@code tPag=90} (Sem Pagamento) é o que o XSD prevê para isso.
     *
     * <p>⚠️ {@code vPag} vai junto mesmo assim: a anotação do XSD diz que ele pode ser omitido com
     * {@code tPag=90}, mas o schema o declara obrigatório — e quem valida é o schema. Sem esta
     * asserção o par anotação × schema voltaria a divergir sem ninguém notar.
     */
    @Test
    void devolucaoSaiSemPagamentoMasComVpagZeroQueOSchemaExige() {
        XmlMontado montado = montador.montar(devolucaoDeUmItem("102", null));

        validarEstrutura(montado);
        assertThat(montado.xml()).contains("<pag><detPag><tPag>90</tPag><vPag>0.00</vPag></detPag></pag>");
    }

    /**
     * CSRT (NT 2018.005) — o par {@code idCSRT} + {@code hashCSRT} é <b>obrigatório na NF-e modelo
     * 55</b>: sem ele a SEFAZ rejeita com cStat 975 (achado transmitindo de verdade, 2026-08-19).
     *
     * <p>O hash é conferido contra um valor calculado <b>fora</b> do código de produção
     * (SHA-1 de {@code CSRT + chave}, digest bruto em Base-64), não contra o que o montador
     * produziu — um teste que chamasse {@code XmlFiscal.hashCsrt} dos dois lados passaria mesmo se
     * a fórmula estivesse errada, que é exatamente o erro que dá cStat 976.
     */
    @Test
    void csrtSaiNoInfRespTecComHashSha1EmBase64() throws Exception {
        XmlMontado montado = montador.montar(devolucaoDeUmItem("102", null));

        byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(
                ("CSRTDETESTEPARAOMONTADOR1234567890" + montado.chaveAcesso())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hashEsperado = java.util.Base64.getEncoder().encodeToString(digest);

        validarEstrutura(montado);
        assertThat(hashEsperado).as("digest bruto em Base-64, não o hex").hasSize(28);
        assertThat(montado.xml())
                .contains("<idCSRT>01</idCSRT>")
                .contains("<hashCSRT>" + hashEsperado + "</hashCSRT>");
    }

    // ------------------------------------------------------------------ recusas explícitas (F11)

    /** Melhor recusar aqui, dizendo onde configurar, do que assinar e transmitir uma nota que a
     *  SEFAZ devolve com um cStat 975 que não menciona variável de ambiente nenhuma. */
    @Test
    void devolucaoSemCsrtConfiguradoEhRecusadaAntesDeTransmitir() {
        ResponsavelTecnico semCsrt = new ResponsavelTecnico(
                "37829453000135", "MITRYUSCASH", "suporte@nainer.com.br", "4133334444", null, null);
        DevolucaoParaMontar dev = new DevolucaoParaMontar(
                AmbienteSefaz.HOMOLOGACAO, 1, 1, 12345678, EMISSAO, "DEVOLUCAO DE VENDA",
                CHAVE_ORIGINAL, 0, 1, emitente(), destinatarioPropriaLoja(),
                List.of(itemBase("102", null).build(1, 3)), totais(), null, semCsrt, "1.0");

        assertThatThrownBy(() -> montador.montar(dev))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("CSRT");
    }

    @Test
    void devolucaoSemChaveDaNotaOriginalEhRecusada() {
        DevolucaoParaMontar dev = new DevolucaoParaMontar(
                AmbienteSefaz.HOMOLOGACAO, 1, 1, 12345678, EMISSAO, "DEVOLUCAO DE VENDA",
                null, 0, 1, emitente(), destinatarioPropriaLoja(), List.of(itemBase("102", null).build(1, 3)),
                totais(), null, respTec(), "1.0");

        assertThatThrownBy(() -> montador.montar(dev))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("SINIEF 8/2026");
    }

    @Test
    void itemSemONumeroDoItemOriginalEhRecusadoPorQuebrarARastreabilidade() {
        ItemDevolucao semOriginal = itemBase("102", null).build(1, 0);

        assertThatThrownBy(() -> montador.montar(devolucaoCom(List.of(semOriginal))))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("item a item");
    }

    @Test
    void devolucaoSemDestinatarioEhRecusadaPorqueOModelo55Exige() {
        DevolucaoParaMontar dev = new DevolucaoParaMontar(
                AmbienteSefaz.HOMOLOGACAO, 1, 1, 12345678, EMISSAO, "DEVOLUCAO DE VENDA",
                CHAVE_ORIGINAL, 0, 1, emitente(), null, List.of(itemBase("102", null).build(1, 3)),
                totais(), null, respTec(), "1.0");

        assertThatThrownBy(() -> montador.montar(dev))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("destinatário");
    }

    /** CSOSN que exige grupo de ST completo: recusa explícita, nunca uma nota "quase certa". */
    @Test
    void csosnQueOV1NaoSabeEspelharEhRecusadoComMensagemQueEnsinaASaida() {
        assertThatThrownBy(() -> montador.montar(devolucaoDeUmItem("900", null)))
                .isInstanceOf(MontagemInvalidaException.class)
                .hasMessageContaining("contador");
    }

    /**
     * ⛔ <b>Venda com desconto devolve o valor que a venda teve, não o de tabela</b> (pendência 60).
     *
     * <p>Até 2026-09-02 o {@code vDesc} era fixo em ZERO e o {@code vNF} era a soma dos
     * {@code vProd}: devolver uma venda de R$ 199,90 com R$ 19,90 de desconto emitia nota de
     * entrada de <b>R$ 199,90</b> referenciando uma NFC-e cujo {@code vNF} é <b>R$ 180,00</b> — a
     * nota afirmando um valor que a operação nunca teve. Todo o resto do sistema (vale, DRE,
     * Lucratividade, Comissões) já trabalha no líquido desde 29/08; a nota fiscal era o último
     * lugar no bruto.
     *
     * <p>⚠️ <b>As três asserções são inseparáveis</b>: a tag no item, a tag no total e a conta
     * {@code vNF = vProd − vDesc}. Somar o desconto e não descontá-lo do total deixaria o XML
     * internamente inconsistente — e é exatamente a conta que a SEFAZ refaz.
     *
     * <p>⭐ E a validação contra o <b>XSD oficial</b> é o que dá valor a este teste: a posição do
     * {@code vDesc} dentro de {@code prod} é fixada pelo schema (entre {@code vUnTrib} e
     * {@code indTot}), e errar a ordem é rejeição garantida que nenhuma asserção de texto pegaria.
     */
    @Test
    void itemComDescontoLevaVDescEOTotalSaiLiquido() {
        ItemDevolucao comDesconto = itemBase("102", null).comDesconto("19.90").build(1, 3);
        TotaisDevolucao totais = new TotaisDevolucao(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("199.90"), new BigDecimal("19.90"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("180.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("12.34"));

        XmlMontado montado = montador.montar(new DevolucaoParaMontar(
                AmbienteSefaz.HOMOLOGACAO, 1, 1, 12345678, EMISSAO,
                "DEVOLUCAO DE VENDA", CHAVE_ORIGINAL, 0, 1, emitente(), destinatarioPropriaLoja(),
                List.of(comDesconto), totais,
                "Devolucao referente a NFC-e " + CHAVE_ORIGINAL, respTec(), "Niner PDV 1.0"));

        assertThatCode(() -> validarEstrutura(montado)).doesNotThrowAnyException();
        assertThat(montado.xml())
                .as("o desconto do item vai no XML, na posição que o XSD exige")
                .contains("<vUnTrib>199.900000</vUnTrib><vDesc>19.90</vDesc><indTot>1</indTot>")
                .as("e no total da nota")
                .contains("<vDesc>19.90</vDesc>")
                .as("vNF = vProd - vDesc: o valor que a venda de fato teve")
                .contains("<vProd>199.90</vProd>")
                .contains("<vNF>180.00</vNF>");
    }

    /**
     * O par negativo do teste acima: sem desconto, <b>nenhuma tag</b> sai no item. A tag é opcional
     * no schema, e escrever {@code 0.00} em toda linha de toda nota só polui o XML — mas o que
     * torna este teste necessário é outra coisa: um montador que emitisse a tag sempre passaria no
     * teste acima e ninguém notaria.
     */
    @Test
    void itemSemDescontoNaoEscreveATagNoProduto() {
        XmlMontado montado = montador.montar(devolucaoDeUmItem("102", null));

        validarEstrutura(montado);
        assertThat(montado.xml())
                .as("sem desconto, o item vai direto de vUnTrib para indTot")
                .contains("<vUnTrib>199.900000</vUnTrib><indTot>1</indTot>");
    }

    // ------------------------------------------------------------------ fixtures

    private DevolucaoParaMontar devolucaoDeUmItem(String csosn, String cstIcms) {
        return devolucaoCom(List.of(itemBase(csosn, cstIcms).build(1, 3)));
    }

    private DevolucaoParaMontar devolucaoCom(List<ItemDevolucao> itens) {
        return devolucaoCom(itens, destinatarioPropriaLoja());
    }

    private DevolucaoParaMontar devolucaoCom(List<ItemDevolucao> itens, DestinatarioDevolucao dest) {
        return new DevolucaoParaMontar(
                AmbienteSefaz.HOMOLOGACAO, 1, 1, 12345678, EMISSAO,
                "DEVOLUCAO DE VENDA", CHAVE_ORIGINAL, 0, 1, emitente(), dest, itens, totais(),
                "Devolucao referente a NFC-e " + CHAVE_ORIGINAL, respTec(), "Niner PDV 1.0");
    }

    private static Emitente emitente() {
        return new Emitente("37829453000135", "MITRYUSCASH LTDA", "MITRYUSCASH", "9122793165", 1,
                "RUA MARIO CHALBAUD BISCAIA", "25", null, "NOVO MUNDO", 4106902, "CURITIBA", "PR",
                "81050240", "4133334444");
    }

    /** Destinatário = a própria loja, o caso da NFC-e sem consumidor identificado (DF20). */
    private static DestinatarioDevolucao destinatarioPropriaLoja() {
        return new DestinatarioDevolucao("37829453000135", "MITRYUSCASH LTDA", 1, "9122793165",
                "RUA MARIO CHALBAUD BISCAIA", "25", null, "NOVO MUNDO", 4106902, "CURITIBA", "PR",
                "81050240", "4133334444", true);
    }

    private static ResponsavelTecnico respTec() {
        return new ResponsavelTecnico("37829453000135", "MITRYUSCASH", "suporte@nainer.com.br", "4133334444",
                "01", "CSRTDETESTEPARAOMONTADOR1234567890");
    }

    private static TotaisDevolucao totais() {
        return new TotaisDevolucao(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("199.90"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("199.90"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("12.34"));
    }

    /** Builder mínimo — o record de item tem 40+ campos e repetir isso por teste esconderia o que
     *  cada caso realmente exercita. */
    private static ItemBuilder itemBase(String csosn, String cstIcms) {
        return new ItemBuilder(csosn, cstIcms);
    }

    private static final class ItemBuilder {
        private final String csosn;
        private final String cstIcms;
        private BigDecimal baseIcms = BigDecimal.ZERO;
        private BigDecimal aliquotaIcms = BigDecimal.ZERO;
        private BigDecimal valorIcms = BigDecimal.ZERO;
        /** `null` = sem desconto, que é o caso da esmagadora maioria das vendas. */
        private BigDecimal valorDesconto;

        private ItemBuilder(String csosn, String cstIcms) {
            this.csosn = csosn;
            this.cstIcms = cstIcms;
        }

        ItemBuilder comDesconto(String valor) {
            this.valorDesconto = new BigDecimal(valor);
            return this;
        }

        ItemBuilder comIcms(BigDecimal base, BigDecimal aliquota, BigDecimal valor) {
            this.baseIcms = base;
            this.aliquotaIcms = aliquota;
            this.valorIcms = valor;
            return this;
        }

        ItemDevolucao build(int nItem, int nItemOriginal) {
            return new ItemDevolucao(
                    nItem, nItemOriginal, "9001000031453", null, "BOLSA FEM ZETI REF: ZT-2070 MARROM",
                    "42022210", null, "1202", "UN",
                    BigDecimal.ONE, new BigDecimal("199.90"), new BigDecimal("199.90"), valorDesconto,
                    "UN", BigDecimal.ONE, new BigDecimal("199.90"), 0,
                    cstIcms, csosn, baseIcms, BigDecimal.ZERO, aliquotaIcms, valorIcms,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    "99", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "99", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    new BigDecimal("12.34"));
        }
    }
}
