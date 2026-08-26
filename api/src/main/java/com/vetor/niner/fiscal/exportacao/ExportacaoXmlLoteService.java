package com.vetor.niner.fiscal.exportacao;

import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.comum.tempo.FusoDaUf;
import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteDtos.DocumentoDoPacote;
import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteDtos.EmpresaDoPacote;
import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteDtos.EventoDoPacote;
import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteDtos.ResumoExportacaoXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exportação de XML em Lote (`docs/telas/exportacao-xml-lote.md`, tela {@code fiscal.download} do
 * §11.2 do estudo fiscal) — empacota num único ZIP o {@code nfeProc} de todas as notas emitidas
 * pela empresa num período, mais os eventos de cancelamento autorizados, mais um índice em CSV.
 *
 * <h2>Não é {@code @Transactional} — de propósito</h2>
 *
 * <p>Este serviço lê dezenas (ou milhares) de objetos no MinIO. Segurar uma conexão do pool durante
 * I/O de rede é o que o módulo fiscal evita desde o começo (F2), então as leituras de banco ficam
 * em {@link ExportacaoXmlLoteRepositorio}, num bean separado, com transações curtas que terminam
 * <b>antes</b> do primeiro byte sair do bucket.
 *
 * <h2>⛔ Por que o ZIP é montado em memória e não com {@code StreamingResponseBody}</h2>
 *
 * <p>Porque {@code TenantContext} é um {@code ScopedValue} (Java 25), ligado apenas durante o
 * escopo da requisição. O corpo de um {@code StreamingResponseBody} é escrito <b>depois</b> que o
 * controller retorna, e {@link ArmazenamentoPrivado} monta e confere o prefixo
 * {@code tenants/{id_tenant}/} a partir desse contexto (P8): o streaming quebraria com "Nenhum
 * tenant no contexto da execução atual". O teto de {@link #LIMITE_DOCUMENTOS} documentos é o que
 * mantém essa escolha segura — não é enfeite.
 */
@Service
public class ExportacaoXmlLoteService {

    private static final Logger log = LoggerFactory.getLogger(ExportacaoXmlLoteService.class);

    /** Mesmo limite da lista de Documentos Fiscais: duas telas irmãs, uma regra só. */
    private static final int PERIODO_MAXIMO_DIAS = 365;

    /**
     * Teto por exportação. ~10 KB por {@code nfeProc} ⇒ ~20 MB crus, ~2 MB de ZIP. Acima disso o
     * caminho não é subir o número: é o desenho assíncrono do §11.2 (DF22), com progresso ao vivo e
     * entrega por URL assinada.
     */
    private static final int LIMITE_DOCUMENTOS = 2000;

    /**
     * ⚠️ Sem o BOM, o Excel em pt-BR abre o CSV com todo acento quebrado. É invisível num editor de
     * texto, então fica como constante nomeada em vez de um caractere solto na string.
     */
    private static final String BOM_UTF8 = Character.toString(0xFEFF);

    private static final DateTimeFormatter DATA_HORA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter PASTA_MES = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ExportacaoXmlLoteRepositorio repositorio;
    private final ArmazenamentoPrivado armazenamento;

    public ExportacaoXmlLoteService(ExportacaoXmlLoteRepositorio repositorio, ArmazenamentoPrivado armazenamento) {
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
    }

    // ------------------------------------------------------------------ pré-conferência

    /**
     * O que a tela mostra <b>antes</b> do clique. Nunca toca no bucket: é contagem no banco, e é o
     * que impede tanto o ZIP vazio em silêncio quanto o clique que ia estourar o teto.
     */
    public ResumoExportacaoXml resumir(Jwt jwt, long idEmpresa, LocalDate dataInicial, LocalDate dataFinal,
                                       Integer modelo) {
        exigirAdmin(jwt);
        validarFiltros(dataInicial, dataFinal, modelo);
        EmpresaDoPacote empresa = exigirEmpresa(idEmpresa);

        var contagem = repositorio.contar(idEmpresa, dataInicial, dataFinal, modelo);
        long eventos = repositorio.contarEventos(idEmpresa, dataInicial, dataFinal, modelo);

        int totalPartes = calcularTotalPartes(contagem.total());
        return new ResumoExportacaoXml(
                nomeDoArquivo(empresa, dataInicial, dataFinal, 1, totalPartes),
                contagem.total(),
                contagem.comXml(),
                contagem.total() - contagem.comXml(),
                eventos,
                LIMITE_DOCUMENTOS,
                totalPartes,
                repositorio.maiorIdDocumento(idEmpresa, dataInicial, dataFinal, modelo));
    }

    // ------------------------------------------------------------------ o pacote

    /** ZIP pronto + o nome sugerido do arquivo (é ele que vai no {@code Content-Disposition}). */
    public record PacoteZip(String nomeArquivo, byte[] conteudo) {
    }

    /**
     * Monta <b>uma parte</b> do pacote.
     *
     * <p>⭐ <b>Período grande é particionado, não recusado</b> (decisão do dono do produto em
     * 2026-08-26). Antes, acima de {@link #LIMITE_DOCUMENTOS} a exportação respondia 409 mandando
     * reduzir o período — o que empurrava para o lojista um trabalho que o sistema sabe fazer:
     * quem tem 12.000 notas teria de descobrir sozinho em quantos pedaços cortar, e repetir a tela
     * seis vezes acertando as datas na mão. Agora a tela pede parte 1, 2, 3… e o servidor devolve
     * um ZIP por parte, cada um com {@link #LIMITE_DOCUMENTOS} documentos.
     *
     * <p>⚠️ O teto por parte <b>continua existindo</b> e pelo mesmo motivo de antes: o ZIP é montado
     * em memória porque o {@code TenantContext} é um {@code ScopedValue} que não sobrevive a um
     * streaming. Particionar não afrouxa esse limite — ele é o que torna cada parte segura.
     *
     * @param parte          1-based.
     * @param ateIdDocumento teto vindo da pré-conferência, que congela o conjunto. Ver
     *                       {@code ExportacaoXmlLoteRepositorio#listarDocumentos}.
     */
    public PacoteZip exportar(Jwt jwt, long idEmpresa, LocalDate dataInicial, LocalDate dataFinal, Integer modelo,
                              int parte, Long ateIdDocumento) {
        exigirAdmin(jwt);
        validarFiltros(dataInicial, dataFinal, modelo);
        EmpresaDoPacote empresa = exigirEmpresa(idEmpresa);
        if (parte < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parte inválida.");
        }

        // Sem teto vindo do cliente, congela agora — assim uma exportação de parte única também
        // fica imune a uma nota emitida no meio dela.
        Long teto = ateIdDocumento != null
                ? ateIdDocumento
                : repositorio.maiorIdDocumento(idEmpresa, dataInicial, dataFinal, modelo);

        long totalNoPeriodo = repositorio.contar(idEmpresa, dataInicial, dataFinal, modelo).total();
        int totalPartes = calcularTotalPartes(totalNoPeriodo);
        if (parte > Math.max(totalPartes, 1)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O período tem %d parte(s); a parte %d não existe.".formatted(totalPartes, parte));
        }

        List<DocumentoDoPacote> documentos = repositorio.listarDocumentos(
                idEmpresa, dataInicial, dataFinal, modelo, teto, LIMITE_DOCUMENTOS,
                (parte - 1) * LIMITE_DOCUMENTOS);

        // ⛔ Nunca gerar ZIP vazio em silêncio: um arquivo que abre e não tem nada dentro parece
        // pacote entregue, e o lojista só descobre quando o contador reclama.
        if (documentos.stream().noneMatch(d -> d.xmlObjetoBucket() != null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, documentos.isEmpty()
                    ? "Nenhuma nota fiscal foi emitida por esta empresa no período selecionado."
                    : ("As %d notas do período ainda não tiveram o XML arquivado. O arquivamento é automático "
                            + "e tenta de novo a cada 10 minutos — aguarde e repita a exportação.")
                            .formatted(documentos.size()));
        }

        // Eventos DESTA parte: filtrar por período de novo repetiria o mesmo comprovante de
        // cancelamento em todos os ZIPs, longe da nota a que ele pertence.
        List<EventoDoPacote> eventos = repositorio.listarEventosDosDocumentos(
                documentos.stream().map(DocumentoDoPacote::idDocumentoFiscal).toList());
        ZoneId fuso = FusoDaUf.deOuPadrao(empresa.uf());

        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        Set<Long> incluidos = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(saida, StandardCharsets.UTF_8)) {
            for (DocumentoDoPacote doc : documentos) {
                if (doc.xmlObjetoBucket() == null) {
                    continue;
                }
                byte[] xml = lerDoBucket(doc.xmlObjetoBucket());
                if (xml == null) {
                    continue;   // objeto sumiu do bucket — vai marcado no CSV, não derruba o pacote
                }
                gravarEntrada(zip, caminhoDaSaida(doc, fuso), xml, doc.dataEmissao().toInstant().toEpochMilli());
                incluidos.add(doc.idDocumentoFiscal());
            }
            for (EventoDoPacote evt : eventos) {
                byte[] xml = lerDoBucket(evt.xmlObjetoBucket());
                if (xml == null) {
                    continue;
                }
                gravarEntrada(zip, caminhoDoEvento(evt, fuso), xml, evt.dataEmissao().toInstant().toEpochMilli());
            }
            gravarEntrada(zip, "relatorio.csv", montarCsv(documentos, incluidos, fuso),
                    documentos.getFirst().dataEmissao().toInstant().toEpochMilli());
        } catch (IOException e) {
            // ByteArrayOutputStream não faz I/O de verdade; se chegar aqui é bug, não ambiente.
            throw new IllegalStateException("Falha ao montar o ZIP da exportação de XML.", e);
        }

        return new PacoteZip(
                nomeDoArquivo(empresa, dataInicial, dataFinal, parte, totalPartes), saida.toByteArray());
    }

    /**
     * Quantos ZIPs o período rende. Zero documentos = 1 parte (que vai responder 409 dizendo que
     * não há nota) — devolver 0 partes faria a tela não ter o que pedir e calar sobre o porquê.
     */
    public static int calcularTotalPartes(long totalDocumentos) {
        if (totalDocumentos <= 0) {
            return 1;
        }
        return (int) ((totalDocumentos + LIMITE_DOCUMENTOS - 1) / LIMITE_DOCUMENTOS);
    }

    /**
     * Lê um objeto do bucket fiscal. Devolve {@code null} quando a <b>chave existe no banco mas o
     * objeto não está no bucket</b> — caso raro (arquivamento interrompido entre gravar e apontar a
     * coluna, ou objeto removido à mão) que não pode derrubar a exportação inteira: o documento sai
     * marcado no {@code relatorio.csv}.
     *
     * <p>⚠️ <b>Só o 404 é engolido.</b> Um <b>403</b> significa chave de outro tenant vinda do banco
     * (P8: bug de isolamento) e um <b>503</b> significa MinIO fora do ar — os dois têm de subir e
     * abortar o pacote. Engolir qualquer erro aqui transformaria um vazamento, ou um bucket fora do
     * ar, num ZIP silenciosamente incompleto.
     */
    private byte[] lerDoBucket(String chave) {
        try {
            return armazenamento.ler(AreaPrivada.FISCAL_XML, chave);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("XML apontado pelo banco não está no bucket ({}). Fica de fora do pacote, "
                        + "marcado no relatorio.csv.", chave);
                return null;
            }
            throw e;
        }
    }

    private static void gravarEntrada(ZipOutputStream zip, String caminho, byte[] conteudo, long instante)
            throws IOException {
        ZipEntry entrada = new ZipEntry(caminho);
        entrada.setTime(instante);
        zip.putNextEntry(entrada);
        zip.write(conteudo);
        zip.closeEntry();
    }

    /**
     * {@code AAAA-MM/saidas/{chave}.xml}. O mês sai do instante local da <b>UF do emitente</b> —
     * a mesma conta que {@code ArquivamentoXmlService} faz para montar o caminho no bucket, então a
     * pasta do ZIP é, por construção, a mesma pasta do bucket, e não uma segunda convenção.
     */
    private static String caminhoDaSaida(DocumentoDoPacote doc, ZoneId fuso) {
        return "%s/saidas/%s.xml".formatted(pastaDoMes(doc.dataEmissao().atZoneSameInstant(fuso)), doc.chaveAcesso());
    }

    private static String caminhoDoEvento(EventoDoPacote evt, ZoneId fuso) {
        return "%s/eventos/%s-%s-%d.xml".formatted(pastaDoMes(evt.dataEmissao().atZoneSameInstant(fuso)),
                evt.chaveAcesso(), evt.tipoEvento(), evt.sequencia());
    }

    private static String pastaDoMes(ZonedDateTime local) {
        return PASTA_MES.format(local);
    }

    // ------------------------------------------------------------------ índice

    /**
     * O {@code relatorio.csv} do §11.2 — o arquivo que o contador abre primeiro. Traz <b>todos</b>
     * os documentos do período, inclusive os que ficaram de fora, com a coluna "ARQUIVO NO PACOTE"
     * dizendo por quê: o índice existe justamente para que nada suma sem registro.
     *
     * <p>Separador {@code ;} e <b>BOM UTF-8</b> — sem os dois, o Excel em pt-BR abre o arquivo com
     * acento quebrado e tudo numa coluna só. Valor com vírgula decimal, que é o que ele lê como
     * número.
     */
    private static byte[] montarCsv(List<DocumentoDoPacote> documentos, Set<Long> incluidos, ZoneId fuso) {
        StringBuilder csv = new StringBuilder(BOM_UTF8);
        csv.append("MODELO;SERIE;NUMERO;CHAVE DE ACESSO;EMISSAO;SITUACAO;VALOR TOTAL;")
                .append("DESTINATARIO;CPF/CNPJ;ARQUIVO NO PACOTE\n");
        for (DocumentoDoPacote doc : documentos) {
            csv.append(doc.modelo() == 65 ? "NFC-e (65)" : "NF-e (55)").append(';')
                    .append(doc.serie() == null ? "" : doc.serie()).append(';')
                    .append(doc.numero() == null ? "" : doc.numero()).append(';')
                    .append(texto(doc.chaveAcesso())).append(';')
                    .append(DATA_HORA_BR.format(doc.dataEmissao().atZoneSameInstant(fuso))).append(';')
                    .append(texto(doc.situacao())).append(';')
                    .append(moeda(doc.valorTotal())).append(';')
                    .append(texto(doc.contraparte())).append(';')
                    .append(texto(doc.documentoContraparte())).append(';')
                    .append(incluidos.contains(doc.idDocumentoFiscal())
                            ? texto(caminhoDaSaida(doc, fuso))
                            : "(nao arquivado)")
                    .append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Escapa o que quebraria a coluna: {@code ;}, aspas e quebra de linha dentro do campo. */
    private static String texto(String valor) {
        if (valor == null || valor.isBlank()) {
            return "";
        }
        if (valor.indexOf(';') < 0 && valor.indexOf('"') < 0 && valor.indexOf('\n') < 0) {
            return valor;
        }
        return '"' + valor.replace("\"", "\"\"").replace("\n", " ") + '"';
    }

    private static String moeda(BigDecimal valor) {
        return valor == null ? "0,00" : valor.toPlainString().replace('.', ',');
    }

    // ------------------------------------------------------------------ nome do arquivo

    /**
     * {@code EMPRESA_MM-AAAA.zip} — ou {@code EMPRESA_MM-AAAA_a_MM-AAAA.zip} quando o período
     * <b>cruza meses</b>, que o pedido original ("Empresa + mês + ano da emissão") não previa mas o
     * filtro por intervalo permite.
     *
     * <p>O mês/ano sai das <b>datas do filtro</b>, não das notas encontradas: o nome tem de ser
     * previsível antes de saber o que existe no período (a tela o mostra na pré-conferência), e um
     * período de agosto sem nenhuma nota ainda é "agosto".
     */
    private static String nomeDoArquivo(EmpresaDoPacote empresa, LocalDate dataInicial, LocalDate dataFinal) {
        return nomeDoArquivo(empresa, dataInicial, dataFinal, 1, 1);
    }

    /**
     * ⭐ O sufixo de parte só aparece quando há <b>mais de uma</b>: numerar "parte-1-de-1" um
     * arquivo único seria ruído no nome que o contador arquiva todo mês.
     *
     * <p>Com duas ou mais, o nome carrega <b>a parte e o total</b> (`parte-2-de-7`) — não só o
     * número da parte. Quem recebe sete arquivos precisa saber, olhando qualquer um deles, se
     * recebeu todos; "parte-2" sozinho não responde isso, e faltar um pedaço de um pacote fiscal
     * é o tipo de falha que só aparece meses depois, na fiscalização.
     *
     * <p>Zero à esquerda (`parte-02-de-10`) para o explorador de arquivos ordenar certo — sem ele,
     * a ordenação alfabética põe 10 antes de 2.
     */
    private static String nomeDoArquivo(EmpresaDoPacote empresa, LocalDate dataInicial, LocalDate dataFinal,
                                        int parte, int totalPartes) {
        String nome = empresa.nomeFantasia() == null || empresa.nomeFantasia().isBlank()
                ? empresa.razaoSocial()
                : empresa.nomeFantasia();
        String inicio = mesAno(dataInicial);
        String fim = mesAno(dataFinal);
        String periodo = inicio.equals(fim) ? inicio : inicio + "_a_" + fim;
        String sufixo = totalPartes > 1
                ? "_parte-%0{larg}d-de-%d".replace("{larg}", String.valueOf(String.valueOf(totalPartes).length()))
                        .formatted(parte, totalPartes)
                : "";
        return "%s_%s%s.zip".formatted(sanitizarNome(nome), periodo, sufixo);
    }

    private static String mesAno(LocalDate data) {
        return "%02d-%d".formatted(data.getMonthValue(), data.getYear());
    }

    /**
     * Nome de arquivo seguro: sem acento, sem espaço, sem nada que o Windows recuse ou que quebre o
     * cabeçalho {@code Content-Disposition}. "LOJA DA ESQUINA – MATRIZ (SP)" vira
     * {@code LOJA_DA_ESQUINA_MATRIZ_SP}.
     */
    static String sanitizarNome(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return "EMPRESA";
        }
        String semAcento = Normalizer.normalize(bruto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String limpo = semAcento.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        limpo = limpo.replaceAll("^_+", "").replaceAll("_+$", "");
        if (limpo.isBlank()) {
            return "EMPRESA";
        }
        return limpo.length() > 40 ? limpo.substring(0, 40).replaceAll("_+$", "") : limpo;
    }

    // ------------------------------------------------------------------ guardas

    private EmpresaDoPacote exigirEmpresa(long idEmpresa) {
        return repositorio.buscarEmpresa(idEmpresa)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada."));
    }

    private static void validarFiltros(LocalDate dataInicial, LocalDate dataFinal, Integer modelo) {
        if (dataInicial == null || dataFinal == null) {
            throw new IllegalArgumentException("Informe a data inicial e a data final de emissão.");
        }
        if (dataInicial.isAfter(dataFinal)) {
            throw new IllegalArgumentException("Data inicial não pode ser maior que a data final.");
        }
        if (dataInicial.plusDays(PERIODO_MAXIMO_DIAS).isBefore(dataFinal)) {
            throw new IllegalArgumentException(
                    "Período de exportação não pode exceder " + PERIODO_MAXIMO_DIAS + " dias.");
        }
        if (modelo != null && modelo != 55 && modelo != 65) {
            throw new IllegalArgumentException("Modelo inválido — use 65 (NFC-e) ou 55 (NF-e).");
        }
    }

    /** ADMIN-only, como todo o módulo fiscal (§12: `fiscal.download` | ADMIN). */
    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem exportar o XML fiscal em lote.");
        }
    }
}
