package com.vetor.niner.fiscal.documento;

import org.springframework.stereotype.Component;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Valida um XML contra o XSD oficial da SEFAZ (`api/src/main/resources/xsd/`) — bloco B5.
 *
 * <p><b>Por que isto existe em produção, e não só no teste:</b> o F11 manda bloquear antes, nunca
 * deixar a rejeição chegar no caixa. Uma rejeição por schema é a mais barata de evitar e a mais
 * cara de descobrir tarde — o B0 gastou três tentativas com {@code cStat 225} (erro de pattern)
 * até parar de adivinhar e ler o XSD. Validar localmente troca esse ciclo por uma mensagem
 * imediata, sem rede.
 *
 * <p>O {@link Schema} é <b>caro de construir</b> (o leiaute puxa `tiposBasico` e
 * `DFeTiposBasicos` por include) e é thread-safe depois de pronto — por isso é montado uma vez
 * na construção do bean e reusado. O {@link Validator}, ao contrário, <b>não</b> é thread-safe:
 * um novo é criado por chamada.
 */
@Component
public class ValidadorXsd {

    /** XSD raiz de uma NF-e/NFC-e isolada — define o elemento {@code NFe} do tipo {@code TNFe}. */
    public static final String XSD_NFE = "/xsd/nfe_v4.00.xsd";

    /** XSD do lote de envio do evento de cancelamento (110111, B8) — define {@code envEvento}. */
    public static final String XSD_EVENTO_CANCELAMENTO = "/xsd/envEventoCancNFe_v1.00.xsd";

    /** XSD do pedido de inutilização (§10.4, B8) — define {@code inutNFe}, sem envelope de lote. */
    public static final String XSD_INUTILIZACAO = "/xsd/inutNFe_v4.00.xsd";

    private final Schema schemaNfe;
    private final Schema schemaEventoCancelamento;
    private final Schema schemaInutilizacao;

    public ValidadorXsd() {
        this.schemaNfe = carregar(XSD_NFE);
        this.schemaEventoCancelamento = carregar(XSD_EVENTO_CANCELAMENTO);
        this.schemaInutilizacao = carregar(XSD_INUTILIZACAO);
    }

    /**
     * @throws XmlInvalidoException com <b>todos</b> os erros encontrados, não só o primeiro — um
     *         XML recém-montado costuma ter erros em série, e devolver um por vez transformaria a
     *         correção numa sequência de tentativas.
     */
    public void validarNfe(String xml) {
        validar(schemaNfe, xml);
    }

    /** Mesma garantia do {@link #validarNfe}, aplicada ao lote de envio do evento (F11). */
    public void validarEventoCancelamento(String xml) {
        validar(schemaEventoCancelamento, xml);
    }

    /** Mesma garantia do {@link #validarNfe}, aplicada ao pedido de inutilização (F11). */
    public void validarInutilizacao(String xml) {
        validar(schemaInutilizacao, xml);
    }

    private void validar(Schema schema, String xml) {
        List<String> erros = new ArrayList<>();
        Validator validator = schema.newValidator();
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                // Aviso de schema não invalida o documento — a SEFAZ também os ignora.
            }

            @Override
            public void error(SAXParseException e) {
                erros.add(descrever(e));
            }

            @Override
            public void fatalError(SAXParseException e) {
                erros.add(descrever(e));
            }
        });

        try {
            validator.validate(new StreamSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        } catch (SAXException e) {
            erros.add(e.getMessage());
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler o XML para validação.", e);
        }

        if (!erros.isEmpty()) {
            throw new XmlInvalidoException(erros);
        }
    }

    private static String descrever(SAXParseException e) {
        return "linha %d, coluna %d: %s".formatted(e.getLineNumber(), e.getColumnNumber(), e.getMessage());
    }

    /**
     * O {@code SchemaFactory} resolve os {@code xs:include} relativos ao {@code systemId} do
     * arquivo raiz — por isso o XSD é carregado pela {@link URL} do classpath, e não por
     * {@code InputStream}: um stream não tem systemId, e os includes não resolveriam.
     */
    private static Schema carregar(String recurso) {
        URL url = ValidadorXsd.class.getResource(recurso);
        if (url == null) {
            throw new IllegalStateException(
                    "XSD não encontrado no classpath: " + recurso
                            + ". Os schemas oficiais ficam em api/src/main/resources/xsd/.");
        }
        try (InputStream ignorado = url.openStream()) {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            return factory.newSchema(url);
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("Falha ao carregar o XSD " + recurso, e);
        }
    }

    /** XML que não passou no schema oficial. Carrega a lista inteira de erros. */
    public static class XmlInvalidoException extends RuntimeException {

        private final transient List<String> erros;

        public XmlInvalidoException(List<String> erros) {
            super("XML inválido perante o XSD oficial (%d erro%s):%n%s"
                    .formatted(erros.size(), erros.size() == 1 ? "" : "s", String.join(System.lineSeparator(), erros)));
            this.erros = List.copyOf(erros);
        }

        public List<String> erros() {
            return erros;
        }
    }
}
