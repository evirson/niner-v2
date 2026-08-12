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
final class NfeXmlParser {

    private NfeXmlParser() {
    }

    record ItemNfe(String cProd, String cEan, String xProd, String ncm, BigDecimal qtd,
                    BigDecimal valorUnitario, BigDecimal valorDesconto) {
    }

    record DuplicataNfe(String numero, LocalDate vencimento, BigDecimal valor) {
    }

    record EmitenteNfe(String cnpj, String razaoSocial, String nomeFantasia, String ie,
                        String logradouro, String numero, String bairro, String municipio,
                        String uf, String cep, String telefone) {
    }

    record NotaFiscalNfe(String chaveNfe, Integer serie, Integer numero, OffsetDateTime dataEmissao,
                          EmitenteNfe emitente, String cnpjDestinatario, List<ItemNfe> itens,
                          List<DuplicataNfe> duplicatas, BigDecimal valorTotal) {
    }

    static NotaFiscalNfe parse(InputStream xml) {
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
                texto(enderEmit, "xMun"), texto(enderEmit, "UF"), texto(enderEmit, "CEP"), texto(enderEmit, "fone"));

        // <dest> é obrigatório no schema da NF-e, mas trata como opcional aqui — a ausência não
        // impede a importação, só deixa de casar a empresa automaticamente (2026-08-19).
        Element dest = filhoOpcional(infNFe, "dest");
        String cnpjDestinatario = dest == null ? null : texto(dest, "CNPJ");

        List<ItemNfe> itens = new ArrayList<>();
        for (Element det : filhos(infNFe, "det")) {
            Element prod = filhoObrigatorio(det, "prod");
            String cEan = texto(prod, "cEAN");
            itens.add(new ItemNfe(
                    texto(prod, "cProd"),
                    (cEan == null || "SEM GTIN".equalsIgnoreCase(cEan)) ? null : cEan,
                    texto(prod, "xProd"), texto(prod, "NCM"),
                    decimal(texto(prod, "qCom")), decimal(texto(prod, "vUnCom")),
                    decimal(texto(prod, "vDesc"))));
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

    private static OffsetDateTime dataHora(String valor) {
        return valor == null ? null : OffsetDateTime.parse(valor);
    }

    private static LocalDate data(String valor) {
        return valor == null ? null : LocalDate.parse(valor, DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
