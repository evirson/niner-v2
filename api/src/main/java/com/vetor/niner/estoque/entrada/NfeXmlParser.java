package com.vetor.niner.estoque.entrada;

import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

/**
 * Parser de NF-e (modelo 55, layout 4.00) pro fluxo XML da Entrada de Produtos por Compra
 * (Fase 3, docs/telas/entrada-mercadoria.md) — lê só os campos que a confirmação da entrada
 * precisa (emitente, itens, duplicatas, chave/série/total). Não valida assinatura digital nem
 * qualquer regra fiscal — isso é responsabilidade da SEFAZ, não do ERP; um XML mal-assinado ou
 * cancelado ainda é lido normalmente aqui (o operador decide se confirma ou não).
 *
 * <p>DOM sem namespace-awareness ({@code setNamespaceAware(false)}) — o XML da NF-e usa um
 * único namespace default, sem prefixo, então ignorá-lo evita todo o boilerplate de
 * {@code NamespaceContext} do XPath e deixa os nomes de tag comparáveis direto. Busca só entre
 * FILHOS DIRETOS de cada elemento (nunca {@code getElementsByTagName}, que varre o documento
 * inteiro) — evita casar tag de mesmo nome em outro ramo da árvore (ex.: <CST> aparece em
 * ICMS/IPI/PIS/COFINS, cada um com significado diferente).
 */
public final class NfeXmlParser {

    private NfeXmlParser() {
    }

    record ItemNfe(int nItem, String cProd, String cEan, String xProd, String ncm, BigDecimal qtd,
                    BigDecimal valorUnitario, BigDecimal valorDesconto, TributacaoItemNfe tributacao) {
    }

    /**
     * Tributação que o <b>fornecedor</b> declarou para o item — lida no ato da importação e gravada
     * em {@code entrada_nfe_item} (V051).
     *
     * <p><b>Por que ler agora e não na hora da devolução.</b> A NF-e de devolução de compra tem de
     * <b>espelhar</b> os valores e bases da nota de origem, para o estorno bater. Parsear o XML
     * arquivado lá na frente é o caminho que o B9 já rejeitou do outro lado ("frágil e
     * desnecessário"): o XML fica no bucket como prova, e o dado consultável fica em tabela.
     *
     * <p><b>Tudo é opcional</b> de propósito. A NF-e permite muitas combinações — CST × CSOSN
     * conforme o regime do emitente, ST presente ou não, IPI presente ou não —, e um item sem o
     * grupo não é erro de XML. Campo ausente vira {@code null}/zero aqui e a importação segue; o
     * que não pode é <b>inventar</b> valor, porque ele reapareceria na nota de devolução.
     *
     * <p>⚠️ O <b>IPI</b> existe aqui e não existe em {@code documento_fiscal_item}: nossa nota de
     * saída é varejo no Simples e não o destaca, mas a nota que a indústria emite para o varejista
     * quase sempre destaca.
     */
    record TributacaoItemNfe(
            String cfop, String unidadeComercial, BigDecimal valorProduto, int origemMercadoria,
            String cest, BigDecimal valorFrete, BigDecimal valorSeguro, BigDecimal valorOutros,
            String cstIcms, String csosn, BigDecimal baseIcms, BigDecimal percReducaoBc,
            BigDecimal aliquotaIcms, BigDecimal valorIcms,
            BigDecimal baseSt, BigDecimal mvaSt, BigDecimal aliquotaSt, BigDecimal valorIcmsSt,
            BigDecimal baseStRetido, BigDecimal icmsStRetido,
            BigDecimal aliquotaFcp, BigDecimal valorFcp,
            String cstIpi, BigDecimal baseIpi, BigDecimal aliquotaIpi, BigDecimal valorIpi,
            String cstPis, BigDecimal basePis, BigDecimal aliquotaPis, BigDecimal valorPis,
            String cstCofins, BigDecimal baseCofins, BigDecimal aliquotaCofins, BigDecimal valorCofins) {
    }

    record DuplicataNfe(String numero, LocalDate vencimento, BigDecimal valor) {
    }

    /** {@code codigoMunicipio} (cMun) entra em 2026-08-20: e obrigatorio no grupo `dest` da NF-e de
     *  devolucao de compra, onde este emitente vira o DESTINATARIO. */
    public record EmitenteNfe(String cnpj, String razaoSocial, String nomeFantasia, String ie,
                        String logradouro, String numero, String bairro, String municipio,
                        int codigoMunicipio, String uf, String cep, String telefone) {
    }

    public record NotaFiscalNfe(String chaveNfe, Integer serie, Integer numero, OffsetDateTime dataEmissao,
                          EmitenteNfe emitente, String cnpjDestinatario, List<ItemNfe> itens,
                          List<DuplicataNfe> duplicatas, BigDecimal valorTotal) {
    }

    public static NotaFiscalNfe parse(InputStream xml) {
        Document doc = carregarDocumento(xml);
        Element infNFe = elementoPorTag(doc, "infNFe");
        String chave = infNFe.getAttribute("Id").replaceFirst("^NFe", "");
        if (chave.isBlank()) {
            throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "XML não é uma NF-e válida (chave de acesso ausente).");
        }

        Element ide = filhoObrigatorio(infNFe, "ide");
        Integer serie = inteiro(texto(ide, "serie"));
        Integer numero = inteiro(texto(ide, "nNF"));
        OffsetDateTime dataEmissao = dataHora(texto(ide, "dhEmi"));

        Element emit = filhoObrigatorio(infNFe, "emit");
        Element enderEmit = filhoObrigatorio(emit, "enderEmit");
        EmitenteNfe emitente = new EmitenteNfe(
                texto(emit, "CNPJ"), texto(emit, "xNome"), texto(emit, "xFant"), texto(emit, "IE"),
                texto(enderEmit, "xLgr"), texto(enderEmit, "nro"), texto(enderEmit, "xBairro"),
                texto(enderEmit, "xMun"), inteiroOuZero(texto(enderEmit, "cMun")),
                texto(enderEmit, "UF"), texto(enderEmit, "CEP"), texto(enderEmit, "fone"));

        // <dest> é obrigatório no schema da NF-e, mas trata como opcional aqui — a ausência não
        // impede a importação, só deixa de casar a empresa automaticamente (2026-08-12).
        Element dest = filhoOpcional(infNFe, "dest");
        String cnpjDestinatario = dest == null ? null : texto(dest, "CNPJ");

        List<ItemNfe> itens = new ArrayList<>();
        for (Element det : filhos(infNFe, "det")) {
            Element prod = filhoObrigatorio(det, "prod");
            String cEan = texto(prod, "cEAN");
            // nItem é atributo do <det>, não elemento. É por ele que a devolução referencia o item
            // da nota original (mesmo mecanismo do DFeReferenciado usado no B9).
            int nItem = det.hasAttribute("nItem") ? Integer.parseInt(det.getAttribute("nItem").trim()) : itens.size() + 1;
            itens.add(new ItemNfe(
                    nItem,
                    texto(prod, "cProd"),
                    (cEan == null || "SEM GTIN".equalsIgnoreCase(cEan)) ? null : cEan,
                    texto(prod, "xProd"), texto(prod, "NCM"),
                    decimal(texto(prod, "qCom")), decimal(texto(prod, "vUnCom")),
                    decimal(texto(prod, "vDesc")),
                    tributacaoDoItem(det, prod)));
        }
        if (itens.isEmpty()) {
            throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "XML não tem nenhum item (<det>) — nada pra importar.");
        }

        List<DuplicataNfe> duplicatas = new ArrayList<>();
        Element cobr = filhoOpcional(infNFe, "cobr");
        if (cobr != null) {
            for (Element dup : filhos(cobr, "dup")) {
                duplicatas.add(new DuplicataNfe(texto(dup, "nDup"), data(texto(dup, "dVenc")), decimal(texto(dup, "vDup"))));
            }
        }

        Element total = filhoObrigatorio(infNFe, "total");
        Element icmsTot = filhoObrigatorio(total, "ICMSTot");
        BigDecimal valorTotal = decimal(texto(icmsTot, "vNF"));

        return new NotaFiscalNfe(chave, serie, numero, dataEmissao, emitente, cnpjDestinatario, itens, duplicatas, valorTotal);
    }

    /**
     * Lê a tributação do item (V051). Percorre {@code prod} e {@code imposto} pescando o que a nota
     * declarar, sem exigir nada: cada grupo (ICMS/ST/IPI/PIS/COFINS) aparece com um nome diferente
     * conforme o CST/CSOSN — {@code ICMS00}, {@code ICMS60}, {@code ICMSSN102}, {@code IPITrib}… —
     * e a NF-e permite combinações demais para valer a pena enumerar. A estratégia é achar o
     * <b>primeiro filho</b> do grupo, qualquer que seja o nome, e ler os campos que existirem.
     */
    private static TributacaoItemNfe tributacaoDoItem(Element det, Element prod) {
        Element imposto = filhoOpcional(det, "imposto");
        Element icmsGrupo = primeiroNeto(imposto, "ICMS");
        Element ipiGrupo = primeiroNeto(imposto, "IPI");
        Element pisGrupo = primeiroNeto(imposto, "PIS");
        Element cofinsGrupo = primeiroNeto(imposto, "COFINS");

        return new TributacaoItemNfe(
                texto(prod, "CFOP"), texto(prod, "uCom"), decimal(texto(prod, "vProd")),
                inteiroOuZero(icmsGrupo == null ? null : texto(icmsGrupo, "orig")),
                texto(prod, "CEST"),
                decimal(texto(prod, "vFrete")), decimal(texto(prod, "vSeg")), decimal(texto(prod, "vOutro")),
                icmsGrupo == null ? null : texto(icmsGrupo, "CST"),
                icmsGrupo == null ? null : texto(icmsGrupo, "CSOSN"),
                decimalDe(icmsGrupo, "vBC"), decimalDe(icmsGrupo, "pRedBC"),
                decimalDe(icmsGrupo, "pICMS"), decimalDe(icmsGrupo, "vICMS"),
                decimalDe(icmsGrupo, "vBCST"), decimalDe(icmsGrupo, "pMVAST"),
                decimalDe(icmsGrupo, "pICMSST"), decimalDe(icmsGrupo, "vICMSST"),
                decimalDe(icmsGrupo, "vBCSTRet"), decimalDe(icmsGrupo, "vICMSSTRet"),
                decimalDe(icmsGrupo, "pFCP"), decimalDe(icmsGrupo, "vFCP"),
                ipiGrupo == null ? null : texto(ipiGrupo, "CST"),
                decimalDe(ipiGrupo, "vBC"), decimalDe(ipiGrupo, "pIPI"), decimalDe(ipiGrupo, "vIPI"),
                pisGrupo == null ? null : texto(pisGrupo, "CST"),
                decimalDe(pisGrupo, "vBC"), decimalDe(pisGrupo, "pPIS"), decimalDe(pisGrupo, "vPIS"),
                cofinsGrupo == null ? null : texto(cofinsGrupo, "CST"),
                decimalDe(cofinsGrupo, "vBC"), decimalDe(cofinsGrupo, "pCOFINS"), decimalDe(cofinsGrupo, "vCOFINS"));
    }

    /**
     * Primeiro elemento-filho de {@code <imposto>/<grupo>} — é onde mora o CST e os valores. O nome
     * varia com a tributação ({@code ICMS00}, {@code ICMSSN102}, {@code IPINT}…), então quem lê não
     * pode depender dele.
     */
    private static Element primeiroNeto(Element imposto, String grupo) {
        Element pai = filhoOpcional(imposto, grupo);
        if (pai == null) {
            return null;
        }
        NodeList filhos = pai.getChildNodes();
        for (int i = 0; i < filhos.getLength(); i++) {
            if (filhos.item(i) instanceof Element elemento) {
                return elemento;
            }
        }
        return null;
    }

    /** {@code null}-safe: grupo ausente vira zero, não NPE. */
    private static BigDecimal decimalDe(Element grupo, String campo) {
        return grupo == null ? BigDecimal.ZERO : decimal(texto(grupo, campo));
    }

    private static int inteiroOuZero(String valor) {
        Integer i = inteiro(valor);
        return i == null ? 0 : i;
    }

    private static Document carregarDocumento(InputStream xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            // Defesa contra XXE — NF-e não usa DTD nem entidades externas, então desligar isso
            // não perde nada e evita a classe inteira de ataque (leitura de arquivo local via
            // entidade, SSRF via DTD remoto).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(xml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Arquivo não é um XML de NF-e válido.");
        }
    }

    private static Element elementoPorTag(Document doc, String nome) {
        NodeList lista = doc.getElementsByTagName(nome);
        if (lista.getLength() == 0) {
            throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "XML não é uma NF-e válida (tag <" + nome + "> não encontrada).");
        }
        return (Element) lista.item(0);
    }

    private static Element filhoObrigatorio(Element pai, String nome) {
        Element e = filhoOpcional(pai, nome);
        if (e == null) {
            throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "XML não é uma NF-e válida (tag <" + nome + "> não encontrada).");
        }
        return e;
    }

    private static Element filhoOpcional(Element pai, String nome) {
        NodeList filhos = pai.getChildNodes();
        for (int i = 0; i < filhos.getLength(); i++) {
            Node n = filhos.item(i);
            if (n instanceof Element el && el.getTagName().equals(nome)) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> filhos(Element pai, String nome) {
        List<Element> resultado = new ArrayList<>();
        NodeList filhos = pai.getChildNodes();
        for (int i = 0; i < filhos.getLength(); i++) {
            Node n = filhos.item(i);
            if (n instanceof Element el && el.getTagName().equals(nome)) {
                resultado.add(el);
            }
        }
        return resultado;
    }

    private static String texto(Element pai, String nome) {
        Element e = filhoOpcional(pai, nome);
        if (e == null) return null;
        String valor = e.getTextContent();
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    private static BigDecimal decimal(String valor) {
        return valor == null ? BigDecimal.ZERO : new BigDecimal(valor);
    }

    private static Integer inteiro(String valor) {
        return valor == null ? null : Integer.valueOf(valor);
    }

    /**
     * ⚠️ As duas datas capturam o erro de parse (achado de auditoria, 2026-08-21).
     *
     * <p>{@code DateTimeParseException} estende {@code DateTimeException}, **não**
     * {@code IllegalArgumentException} — então ela escapava do tratamento de argumento inválido do
     * handler global e virava um <b>500 "Ocorreu um erro"</b>, sem dizer que o problema era o
     * arquivo. Os números do mesmo parser já se comportam bem (`NumberFormatException` <i>é</i>
     * `IllegalArgumentException` e vira 400 com mensagem); só as datas divergiam.
     *
     * <p>Acontece com XML de fornecedor cujo {@code dhEmi} veio sem offset, ou cuja data de
     * vencimento da duplicata veio em formato brasileiro — coisas que o operador só resolve se a
     * mensagem disser qual é o campo.
     */
    private static OffsetDateTime dataHora(String valor) {
        if (valor == null) return null;
        try {
            return OffsetDateTime.parse(valor);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Data/hora inválida no XML da nota: \"" + valor + "\". Esperado o formato da NF-e"
                            + " (ex.: 2026-08-21T10:00:00-03:00).");
        }
    }

    private static LocalDate data(String valor) {
        if (valor == null) return null;
        try {
            return LocalDate.parse(valor, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Data inválida no XML da nota: \"" + valor + "\". Esperado aaaa-mm-dd.");
        }
    }
}
