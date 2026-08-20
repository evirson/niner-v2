package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.comum.tempo.FusoDaUf;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.fiscal.documento.DocumentoFiscalRepositorio.DocumentoParaArquivar;
import com.vetor.niner.fiscal.documento.DocumentoFiscalRepositorio.EventoParaArquivar;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoRepositorio.InutilizacaoParaArquivar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arquivamento do XML fiscal autorizado no bucket privado (DF21, ADR-014 —
 * {@code docs/HANDOFF-ARQUIVAMENTO-XML.md}). Consumidor da infra que já existia sem quem a usasse:
 * até aqui {@code documento_fiscal.xml_objeto_bucket}/{@code xml_hash} ficavam {@code NULL} para
 * sempre.
 *
 * <h2>F2 — nunca dentro de transação de banco</h2>
 *
 * <p>Este serviço <b>não é</b> {@code @Transactional}: gravar no bucket é chamada de rede. As
 * leituras e a gravação do ponteiro usam suas próprias transações curtas em
 * {@link DocumentoFiscalRepositorio}/{@link FiscalInutilizacaoRepositorio} — mesmo padrão do resto
 * do módulo fiscal.
 *
 * <h2>O arquivamento nunca pode derrubar a emissão/cancelamento/inutilização</h2>
 *
 * <p>Os métodos {@code *SeAplicavel} <b>nunca lançam</b>: uma falha de bucket vira log e a
 * pendência fica para {@link ArquivamentoXmlJob} — a mesma garantia do F3 ("a venda nunca espera a
 * SEFAZ") estendida para "a venda/cancelamento/inutilização nunca esperam o bucket".
 *
 * <h2>P2 — idempotência</h2>
 *
 * <p>A chave do objeto é determinística (chave de acesso / evento / faixa) e a área
 * {@link AreaPrivada#FISCAL_XML} recusa sobrescrever (409). Reprocessar o mesmo documento nunca
 * duplica nem corrompe: se já está arquivado ({@code xml_objeto_bucket} preenchido), o método volta
 * sem fazer nada; se o bucket já tem a chave com o MESMO conteúdo (segunda passada de um caminho
 * que falhou entre gravar e apontar a coluna), a coluna é apontada sem regravar; se o conteúdo
 * divergir, é erro real e paramos — nunca "resolvemos" apagando ou trocando de caminho.
 */
@Service
public class ArquivamentoXmlService {

    private static final Logger log = LoggerFactory.getLogger(ArquivamentoXmlService.class);

    // O ano/mês do caminho do objeto sai do instante LOCAL da UF do emitente (2026-08-20), nunca do
    // offset cru que o pgjdbc devolve (sempre UTC) nem de um fuso fixo: os dois erram o mês perto
    // da virada do dia. Ver FusoDaUf — antes disto havia aqui uma constante `America/Sao_Paulo`.

    private static final Pattern PROT_NFE =
            Pattern.compile("<((?:[\\w.-]+:)?protNFe)\\b([^>]*)>(.*?)</(?:[\\w.-]+:)?protNFe>", Pattern.DOTALL);

    private final DocumentoFiscalRepositorio repositorio;
    private final FiscalInutilizacaoRepositorio inutilizacaoRepositorio;
    private final ArmazenamentoPrivado armazenamento;
    private final ValidadorXsd validador;

    public ArquivamentoXmlService(DocumentoFiscalRepositorio repositorio,
                                  FiscalInutilizacaoRepositorio inutilizacaoRepositorio,
                                  ArmazenamentoPrivado armazenamento, ValidadorXsd validador) {
        this.repositorio = repositorio;
        this.inutilizacaoRepositorio = inutilizacaoRepositorio;
        this.armazenamento = armazenamento;
        this.validador = validador;
    }

    // ---------------------------------------------------------------- caminho quente (best-effort)

    /** Chamado logo após {@code marcarAutorizado(...)} já ter commitado. Nunca lança. */
    public void arquivarDocumentoSeAplicavel(long idDocumentoFiscal) {
        try {
            arquivarDocumento(idDocumentoFiscal);
        } catch (RuntimeException e) {
            log.warn("Falha ao arquivar XML do documento fiscal {} — fica pendente para o job de recuperação: {}",
                    idDocumentoFiscal, e.toString());
        }
    }

    /** Chamado logo após {@code registrarTentativaCancelamento(...)} quando a SEFAZ autorizou. */
    public void arquivarEventoSeAplicavel(long idEvento) {
        try {
            arquivarEvento(idEvento);
        } catch (RuntimeException e) {
            log.warn("Falha ao arquivar XML do evento fiscal {} — fica pendente para o job de recuperação: {}",
                    idEvento, e.toString());
        }
    }

    /** Chamado logo após {@code registrarTentativa(...)} quando a SEFAZ homologou. */
    public void arquivarInutilizacaoSeAplicavel(long idInutilizacao) {
        try {
            arquivarInutilizacao(idInutilizacao);
        } catch (RuntimeException e) {
            log.warn("Falha ao arquivar XML da inutilização {} — fica pendente para o job de recuperação: {}",
                    idInutilizacao, e.toString());
        }
    }

    // ---------------------------------------------------------------- entrada (XML do fornecedor)

    /**
     * Move o XML da <b>NF-e do fornecedor</b> de {@code entrada_xml.xml_bruto} para o bucket fiscal
     * (V051). Nunca lança — o chamador é o fluxo de entrada, que não pode falhar por causa disto.
     *
     * <p><b>Por que o XML nasce no banco e só depois vem para cá.</b> Gravar no MinIO dentro da
     * transação que move estoque seria I/O de rede segurando a transação (F2), e um rollback depois
     * do upload deixaria objeto órfão numa área <b>imutável</b>, que não aceita apagar. Assim o XML
     * nunca se perde: com o bucket fora do ar ele fica na coluna e o job leva depois.
     *
     * <p><b>Por que arquivar a nota de compra, se ela não é nossa.</b> A rotina de <b>devolução ao
     * fornecedor</b> só emite contra uma nota de entrada arquivada — "arquivada" tem de significar
     * o mesmo que significa para a nota de saída: área WORM, guarda de 5 anos, prova de origem.
     */
    public void arquivarEntradaXmlSeAplicavel(long idMovimento) {
        try {
            arquivarEntradaXml(idMovimento);
        } catch (RuntimeException e) {
            log.warn("Falha ao arquivar XML da entrada {} — o XML segue no banco e o job recupera: {}",
                    idMovimento, e.toString());
        }
    }

    /** Variante que <b>lança</b> — usada pelo job e por teste, mesmo padrão das irmãs. */
    public void arquivarEntradaXml(long idMovimento) {
        DocumentoFiscalRepositorio.EntradaXmlParaArquivar entrada =
                repositorio.buscarEntradaParaArquivar(idMovimento).orElse(null);
        // Já arquivada, sem XML (entrada manual/planilha) ou sem chave (não veio de NF-e): nada a
        // fazer, e não é erro — só entrada por XML tem o que arquivar.
        if (entrada == null || entrada.xmlObjetoBucketAtual() != null
                || entrada.xmlBruto() == null || entrada.chaveNfe() == null) {
            return;
        }

        // Prefixo `entrada/` separando do que a loja EMITIU: misturar nota de terceiro com nota
        // própria no mesmo caminho faria o pacote do contador (DF22) sair com as duas.
        OffsetDateTime local = entrada.dataMovimento()
                .atZoneSameInstant(FusoDaUf.deOuPadrao(entrada.uf())).toOffsetDateTime();
        String caminho = "entrada/%d/%02d/%s.xml".formatted(
                local.getYear(), local.getMonthValue(), entrada.chaveNfe());

        byte[] bytes = entrada.xmlBruto().getBytes(StandardCharsets.UTF_8);
        String chave = gravarComIdempotencia(caminho, bytes);
        repositorio.marcarEntradaXmlArquivada(idMovimento, chave, sha256(bytes));
    }

    // ---------------------------------------------------------------- documento (nfeProc)

    /** Variante que <b>lança</b> (sem o wrapper "nunca lança") — usada pelo job, que já tem seu
     *  próprio tratamento de erro por item (ver {@link ArquivamentoXmlJob}), e por teste. */
    public void arquivarDocumento(long idDocumentoFiscal) {
        DocumentoParaArquivar doc = repositorio.buscarParaArquivar(idDocumentoFiscal).orElse(null);
        if (doc == null) {
            return; // não está mais AUTORIZADO, ou não existe — nada a fazer aqui
        }
        if (doc.xmlObjetoBucketAtual() != null) {
            return; // P2: já arquivado
        }

        String nfeProc = montarNfeProc(doc.xmlAssinado(), doc.statusSefaz(), doc.chaveAcesso());
        validador.validarProcNfe(nfeProc);

        // Ano/mês do caminho saem do instante LOCAL da UF do emitente, nunca do offset cru que o
        // pgjdbc devolve (sempre UTC) nem de um fuso fixo: os dois erram o mês perto da virada do
        // dia, e a pasta tem de bater com o mês do dhEmi da própria nota.
        OffsetDateTime local = doc.dataEmissao()
                .atZoneSameInstant(FusoDaUf.deOuPadrao(doc.uf())).toOffsetDateTime();
        String caminho = "%d/%02d/%d/%s.xml".formatted(
                local.getYear(), local.getMonthValue(), doc.modelo(), doc.chaveAcesso());

        byte[] bytes = nfeProc.getBytes(StandardCharsets.UTF_8);
        String chave = gravarComIdempotencia(caminho, bytes);
        repositorio.marcarXmlArquivado(idDocumentoFiscal, chave, sha256(bytes));
    }

    /**
     * Monta o {@code nfeProc} (handoff §5) — {@code NFe} + {@code protNFe} <b>por concatenação de
     * texto</b>, nunca por reserialização (DOM/JAXB/pretty-print invalidaria a assinatura em
     * silêncio). {@code xmlAssinado} já é exatamente {@code <NFe ...>...</NFe>}, sem prólogo
     * ({@link MontadorXmlNfce#montar} não emite {@code <?xml ?>}) — é colado byte a byte.
     */
    private static String montarNfeProc(String xmlAssinado, String statusSefazBruto, String chaveAcesso) {
        String protNFe = extrairProtNFe(statusSefazBruto);
        if (protNFe == null) {
            throw new IllegalStateException(
                    "status_sefaz do documento (chave %s) não contém <protNFe> — não é possível montar o nfeProc."
                            .formatted(chaveAcesso));
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><nfeProc versao=\"4.00\" xmlns=\""
                + MontadorXmlNfce.NS + "\">" + xmlAssinado + protNFe + "</nfeProc>";
    }

    /**
     * Extrai {@code <protNFe>...</protNFe>} de {@code status_sefaz} (a resposta CRUA da SEFAZ,
     * envelope SOAP incluído — {@code documento_fiscal.status_sefaz}, F9) ignorando prefixo de
     * namespace, mesmo princípio de {@code SefazTransporte.extrair}. Diferente daquele método
     * (que só lê valores escalares), aqui o elemento tem FILHOS — se a declaração do prefixo
     * estiver só num ancestral (não no próprio {@code protNFe}), o fragmento extraído sozinho
     * ficaria com prefixo pendurado; por isso a declaração é recuperada do corpo inteiro e
     * reinjetada na tag extraída quando necessário. Sem prefixo (caso comum, o único observado
     * contra a SEFAZ-PR real no B0), o fragmento herda o {@code xmlns} default do {@code nfeProc}
     * que o envolve — exatamente o formato do exemplo oficial (handoff §5).
     */
    private static String extrairProtNFe(String corpo) {
        if (corpo == null) {
            return null;
        }
        Matcher m = PROT_NFE.matcher(corpo);
        if (!m.find()) {
            return null;
        }
        String tag = m.group(1);
        String atributos = m.group(2);
        String conteudo = m.group(3);

        String xmlnsExtra = "";
        int doisPontos = tag.indexOf(':');
        if (doisPontos > 0) {
            String prefixo = tag.substring(0, doisPontos);
            if (!atributos.contains("xmlns:" + prefixo)) {
                Matcher decl = Pattern.compile("xmlns:" + Pattern.quote(prefixo) + "=\"([^\"]+)\"").matcher(corpo);
                if (decl.find()) {
                    xmlnsExtra = " xmlns:" + prefixo + "=\"" + decl.group(1) + "\"";
                }
            }
        }
        return "<" + tag + atributos + xmlnsExtra + ">" + conteudo + "</" + tag + ">";
    }

    // ---------------------------------------------------------------- evento (cancelamento)

    public void arquivarEvento(long idEvento) {
        EventoParaArquivar evt = repositorio.buscarEventoParaArquivar(idEvento).orElse(null);
        if (evt == null || evt.xmlObjetoBucketAtual() != null || evt.xmlEvento() == null) {
            return;
        }

        OffsetDateTime local = evt.criadoEm().atZoneSameInstant(FusoDaUf.deOuPadrao(evt.uf())).toOffsetDateTime();
        String caminho = "%d/%02d/%d/%s-%s-%d.xml".formatted(
                local.getYear(), local.getMonthValue(), evt.modelo(),
                evt.chaveAcesso(), evt.tipoEvento(), evt.sequencia());

        byte[] bytes = evt.xmlEvento().getBytes(StandardCharsets.UTF_8);
        String chave = gravarComIdempotencia(caminho, bytes);
        repositorio.marcarEventoArquivado(idEvento, chave);
    }

    // ---------------------------------------------------------------- inutilização

    public void arquivarInutilizacao(long idInutilizacao) {
        InutilizacaoParaArquivar inu = inutilizacaoRepositorio.buscarParaArquivar(idInutilizacao).orElse(null);
        if (inu == null || inu.xmlObjetoBucketAtual() != null || inu.xmlInutilizacao() == null) {
            return;
        }

        OffsetDateTime local = inu.criadoEm().atZoneSameInstant(FusoDaUf.deOuPadrao(inu.uf())).toOffsetDateTime();
        String caminho = "%d/%02d/%d/inut-%d-%d-%d.xml".formatted(
                local.getYear(), local.getMonthValue(), inu.modelo(),
                inu.serie(), inu.numeroInicial(), inu.numeroFinal());

        byte[] bytes = inu.xmlInutilizacao().getBytes(StandardCharsets.UTF_8);
        String chave = gravarComIdempotencia(caminho, bytes);
        inutilizacaoRepositorio.marcarArquivado(idInutilizacao, chave);
    }

    // ---------------------------------------------------------------- idempotência (handoff §4.2)

    /**
     * {@code AreaPrivada#FISCAL_XML} recusa sobrescrever (409) — nunca um bug silencioso de
     * "segunda gravação apagou a primeira". No 409, confere se é a MESMA gravação de uma passada
     * anterior que falhou entre gravar e apontar a coluna (conteúdo igual — segue em frente) ou uma
     * divergência real (para e registra erro; nunca resolve sozinho apagando ou trocando de chave).
     */
    private String gravarComIdempotencia(String caminhoRelativo, byte[] conteudo) {
        try {
            return armazenamento.gravar(AreaPrivada.FISCAL_XML, caminhoRelativo, conteudo, "application/xml");
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.CONFLICT) {
                throw e;
            }
            String chave = "tenants/%d/%s/%s"
                    .formatted(TenantContext.idTenantAtual(), AreaPrivada.FISCAL_XML.prefixo(), caminhoRelativo);
            byte[] existente = armazenamento.ler(AreaPrivada.FISCAL_XML, chave);
            if (!Arrays.equals(existente, conteudo)) {
                throw new IllegalStateException(
                        "XML já arquivado em '%s' diverge do conteúdo que se tentou gravar agora — bug ou chave repetida, não resolvido automaticamente."
                                .formatted(chave));
            }
            return chave;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular o hash do XML arquivado.", e);
        }
    }
}
