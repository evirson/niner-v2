package com.vetor.niner.configuracao.importacao;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura de planilha Excel (.xlsx/.xls) da Rotina de Importação de Dados
 * (docs/telas/importacao-dados.md) — substituiu o antigo formato CSV em 2026-08-10. Sempre a
 * primeira aba do arquivo; cabeçalho na primeira linha. Célula tipada é normalizada de volta para
 * o mesmo texto que os parsers abaixo ({@link #decimal}/{@link #data}/etc.) já esperavam do CSV —
 * célula numérica formatada como data vira {@code dd/mm/aaaa}, célula numérica comum vira texto
 * plano sem separador de milhar (ponto decimal), célula booleana vira {@code TRUE}/{@code FALSE} —
 * assim nenhuma regra de validação de campo precisou mudar.
 *
 * <p><b>.xlsx lê em streaming (SAX), .xls lê em memória (DOM) — 2026-08-10.</b> O modo antigo
 * (DOM, {@code WorkbookFactory}) monta o arquivo inteiro em memória antes de processar a primeira
 * célula; num arquivo de centenas de milhares de linhas essa montagem é a maior parte do tempo
 * gasto, e fica "muda" pro contador de progresso ({@link ImportacaoProgressoContext}) — achado
 * real testando com uma planilha de Contas a Receber de 660 mil linhas, que ficava minutos
 * "parada" sem nenhum feedback antes de sequer começar a validar linha por linha. {@code .xlsx}
 * (o formato recomendado) passou a ler via {@code XSSFReader} + SAX
 * ({@code XSSFSheetXMLHandler}), que processa uma linha do XML por vez — progresso real desde a
 * primeira linha, bem mais leve em memória. {@code .xls} legado continua em DOM: o formato BIFF8
 * trava em 65.536 linhas por limite do próprio formato (não do Niner), então nunca sofre desse
 * problema.
 */
public final class ImportacaoPlanilha {

    private static final Logger LOG = LoggerFactory.getLogger(ImportacaoPlanilha.class);

    static {
        // Sem limite de tamanho de arquivo/linhas (decisão do dono do produto, 2026-08-10) —
        // mas o Apache POI tem, por padrão, duas proteções contra "zip bomb" (arquivo .xlsx
        // pequeno que descompacta em algo gigante) que dão falso positivo em planilhas grandes e
        // LEGÍTIMAS: a razão mínima de inflação (recusa se o XML descompactado for grande demais
        // em relação ao .xlsx comprimido — comum em planilha com muita célula e pouco texto por
        // célula) e o tamanho máximo de array de bytes ao ler uma entrada do zip. Endpoint é
        // ADMIN-only (2026-08-10, achado real: planilha de Contas a Receber com ~700 mil linhas
        // falhava com "Não foi possível ler o arquivo" — a causa real ficava escondida pelo catch
        // genérico). Vale tanto pro leitor DOM quanto pro streaming (mesma infra de zip por
        // baixo, `ZipSecureFile`).
        ZipSecureFile.setMinInflateRatio(0);
        IOUtils.setByteArrayMaxOverride(2_000_000_000);
    }

    /** dd/mm/aaaa tolerante: separador `/`, `-` ou `.`; dia/mês com 1 ou 2 dígitos; qualquer
     *  coisa depois da data (hora, minuto, segundo) é ignorada. Continua valendo para célula de
     *  texto com data digitada nesse formato (célula numérica formatada como data nunca cai
     *  aqui — já é convertida para esse mesmo texto antes). */
    private static final Pattern PADRAO_DATA = Pattern.compile("^(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})");
    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Assinatura de arquivo ZIP (todo `.xlsx`/OOXML é um ZIP por dentro) — decide entre o
     *  leitor em streaming (.xlsx) e o leitor DOM legado (.xls, OLE2/CFB, assinatura diferente). */
    private static final byte[] ASSINATURA_ZIP = {0x50, 0x4B, 0x03, 0x04};

    private ImportacaoPlanilha() {
    }

    /** Uma linha de dados já mapeada por cabeçalho (colunas em MAIÚSCULAS). {@code numeroLinha}
     *  é 1-based contando o cabeçalho como linha 1 — bate com o número de linha que o usuário vê
     *  no Excel. */
    public record LinhaPlanilha(int numeroLinha, Map<String, String> valores) {

        public String valor(String coluna) {
            String v = valores.get(coluna);
            if (v == null) {
                return null;
            }
            v = v.trim();
            return v.isEmpty() ? null : v;
        }
    }

    public static List<LinhaPlanilha> ler(MultipartFile arquivo) {
        return lerDeBytes(bytesDe(arquivo));
    }

    /** Só o cabeçalho (em MAIÚSCULAS), sem exigir nenhuma linha de dados — usado pela detecção
     *  automática de tipo de arquivo ({@link ImportacaoService#detectar}, 2026-08-10), que só
     *  precisa saber quais colunas o arquivo tem, não processar linha nenhuma ainda. No modo
     *  streaming (.xlsx) isso interrompe a leitura logo após a primeira linha — não escaneia o
     *  arquivo inteiro só pra ler o cabeçalho. */
    public static List<String> lerCabecalho(MultipartFile arquivo) {
        return lerCabecalhoDeBytes(bytesDe(arquivo));
    }

    static List<LinhaPlanilha> lerDeBytes(byte[] bytes) {
        if (isXlsx(bytes)) {
            return lerXlsxEmStreaming(bytes, false).linhas();
        }
        return lerDeBytesDom(bytes);
    }

    /** Usado tanto pelo upload real ({@link #lerCabecalho}) quanto pelo
     *  {@code ImportadorDeTabela#colunasEsperadas()} default (lê o próprio modelo gerado por
     *  {@link #gerarModelo}) — mesma leitura, um único código de verdade. */
    static List<String> lerCabecalhoDeBytes(byte[] bytes) {
        if (isXlsx(bytes)) {
            return lerXlsxEmStreaming(bytes, true).cabecalho();
        }
        return lerCabecalhoDeBytesDom(bytes);
    }

    private static boolean isXlsx(byte[] bytes) {
        if (bytes.length < ASSINATURA_ZIP.length) {
            return false;
        }
        for (int i = 0; i < ASSINATURA_ZIP.length; i++) {
            if (bytes[i] != ASSINATURA_ZIP[i]) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------------------
    // .xlsx — leitura em streaming (SAX), 2026-08-10
    // -------------------------------------------------------------------------------------

    private record ResultadoLeitura(List<String> cabecalho, List<LinhaPlanilha> linhas) {
    }

    /** Sinaliza (via exceção não-checada) que a linha de cabeçalho já foi lida e o resto do
     *  arquivo não precisa ser escaneado — usado só no modo {@code apenasCabecalho}. SAX não tem
     *  um jeito nativo de "parar no meio"; lançar de dentro do callback e capturar em volta de
     *  {@code parser.parse(...)} é o idioma padrão pra isso. */
    private static final class LeituraDoCabecalhoConcluidaException extends RuntimeException {
    }

    private static ResultadoLeitura lerXlsxEmStreaming(byte[] bytes, boolean apenasCabecalho) {
        List<String> cabecalho = new ArrayList<>();
        List<LinhaPlanilha> linhas = new ArrayList<>();
        try (OPCPackage pacote = OPCPackage.open(new ByteArrayInputStream(bytes))) {
            XSSFReader leitor = new XSSFReader(pacote);
            StylesTable estilos = leitor.getStylesTable();
            SharedStrings textosCompartilhados = leitor.getSharedStringsTable();
            Iterator<InputStream> planilhas = leitor.getSheetsData();
            if (!planilhas.hasNext()) {
                throw new IllegalArgumentException("Arquivo sem nenhuma planilha.");
            }
            try (InputStream planilha = planilhas.next()) {
                XMLReader parser = XMLHelper.newXMLReader();
                SheetContentsHandler coletor = new ColetorDeLinhas(cabecalho, linhas, apenasCabecalho);
                parser.setContentHandler(new XSSFSheetXMLHandler(
                        estilos, textosCompartilhados, coletor, new DataFormatterComDataFixa(), false));
                parser.parse(new InputSource(planilha));
            }
        } catch (LeituraDoCabecalhoConcluidaException esperado) {
            // apenasCabecalho=true: já temos o que precisamos, interrompido de propósito.
        } catch (Exception e) {
            LOG.warn("Falha ao abrir planilha de importação ({} bytes)", bytes.length, e);
            throw new IllegalArgumentException("Não foi possível ler o arquivo — envie uma planilha Excel (.xlsx ou .xls).");
        }
        if (cabecalho.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio ou sem cabeçalho.");
        }
        return new ResultadoLeitura(cabecalho, linhas);
    }

    /** Monta cada linha (cabeçalho ou dado) a partir dos eventos célula-a-célula do SAX. Célula
     *  vazia simplesmente não dispara {@link #cell} — o mapa é preenchido por índice de coluna
     *  (via {@link CellReference}) e as ausentes ficam de fora, mesmo efeito do {@code Row} do
     *  modo DOM (que também devolve {@code null} pra célula não criada). A primeira linha que
     *  chega é sempre tratada como cabeçalho, seja qual for seu número real (mesmo critério do
     *  DOM: primeira linha existente, não necessariamente a linha 1 do arquivo). */
    private static final class ColetorDeLinhas implements SheetContentsHandler {
        private final List<String> cabecalho;
        private final List<LinhaPlanilha> linhas;
        private final boolean apenasCabecalho;
        private Map<Integer, String> valoresLinha;
        private boolean temAlgumValor;

        ColetorDeLinhas(List<String> cabecalho, List<LinhaPlanilha> linhas, boolean apenasCabecalho) {
            this.cabecalho = cabecalho;
            this.linhas = linhas;
            this.apenasCabecalho = apenasCabecalho;
        }

        @Override
        public void startRow(int numeroLinha) {
            valoresLinha = new LinkedHashMap<>();
            temAlgumValor = false;
        }

        @Override
        public void cell(String referenciaCelula, String valorFormatado, XSSFComment comentario) {
            int coluna = new CellReference(referenciaCelula).getCol();
            String v = valorFormatado == null ? "" : valorFormatado;
            valoresLinha.put(coluna, v);
            if (!v.isEmpty()) {
                temAlgumValor = true;
            }
        }

        @Override
        public void endRow(int numeroLinha) {
            if (cabecalho.isEmpty()) {
                int maxColuna = valoresLinha.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                for (int c = 0; c <= maxColuna; c++) {
                    cabecalho.add(valoresLinha.getOrDefault(c, "").trim().toUpperCase(Locale.ROOT));
                }
                if (apenasCabecalho) {
                    throw new LeituraDoCabecalhoConcluidaException();
                }
                return;
            }
            if (temAlgumValor) {
                Map<String, String> valores = new LinkedHashMap<>();
                for (int c = 0; c < cabecalho.size(); c++) {
                    valores.put(cabecalho.get(c), valoresLinha.getOrDefault(c, ""));
                }
                linhas.add(new LinhaPlanilha(numeroLinha + 1, valores));
            }
            ImportacaoProgressoContext.avancar();
        }
    }

    /** Formata número/data de célula pro MESMO texto que o modo DOM sempre produziu (ver
     *  {@link #numeroOuData}) — sem isto, o {@code DataFormatter} padrão do POI formataria a
     *  data no padrão de exibição de origem da própria planilha (variável, ex. {@code m/d/yy}),
     *  quebrando {@link #data}, que exige {@code dd/mm/aaaa}. */
    private static final class DataFormatterComDataFixa extends DataFormatter {
        DataFormatterComDataFixa() {
            super(Locale.ROOT);
        }

        @Override
        public String formatRawCellContents(double valor, int indiceFormato, String formatoTexto) {
            if (DateUtil.isADateFormat(indiceFormato, formatoTexto)) {
                return DateUtil.getLocalDateTime(valor).toLocalDate().format(FORMATO_DATA);
            }
            return BigDecimal.valueOf(valor).stripTrailingZeros().toPlainString();
        }
    }

    // -------------------------------------------------------------------------------------
    // .xls (legado, BIFF8) — leitura em memória (DOM); trava em 65.536 linhas por conta
    // própria, nunca precisou de streaming.
    // -------------------------------------------------------------------------------------

    private static List<LinhaPlanilha> lerDeBytesDom(byte[] bytes) {
        Sheet planilha = abrirPrimeiraPlanilha(bytes);
        FormulaEvaluator evaluator = planilha.getWorkbook().getCreationHelper().createFormulaEvaluator();
        Row linhaCabecalho = primeiraLinhaOuFalha(planilha);
        List<String> cabecalho = cabecalhoDaLinha(linhaCabecalho, evaluator);

        List<LinhaPlanilha> linhas = new ArrayList<>();
        for (int i = linhaCabecalho.getRowNum() + 1; i <= planilha.getLastRowNum(); i++) {
            Row row = planilha.getRow(i);
            Map<String, String> valores = new LinkedHashMap<>();
            boolean temAlgumValor = false;
            for (int c = 0; c < cabecalho.size(); c++) {
                String v = valorCelula(row == null ? null : row.getCell(c), evaluator);
                valores.put(cabecalho.get(c), v == null ? "" : v);
                temAlgumValor = temAlgumValor || v != null;
            }
            if (temAlgumValor) {
                linhas.add(new LinhaPlanilha(i + 1, valores));
            }
            ImportacaoProgressoContext.avancar();
        }
        return linhas;
    }

    private static List<String> lerCabecalhoDeBytesDom(byte[] bytes) {
        Sheet planilha = abrirPrimeiraPlanilha(bytes);
        FormulaEvaluator evaluator = planilha.getWorkbook().getCreationHelper().createFormulaEvaluator();
        Row linhaCabecalho = primeiraLinhaOuFalha(planilha);
        return cabecalhoDaLinha(linhaCabecalho, evaluator);
    }

    /** Monta um `.xlsx` com a linha de cabeçalho + as linhas de exemplo dadas (células de texto —
     *  são só exemplos ilustrativos, não precisam de tipo nativo). Célula vazia/nula não é
     *  criada (fica em branco), mesmo efeito de campo vazio entre `;` no antigo CSV. */
    public static byte[] gerarModelo(String[] colunas, String[]... linhasExemplo) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Modelo");
            Row cabecalho = sheet.createRow(0);
            for (int c = 0; c < colunas.length; c++) {
                cabecalho.createCell(c).setCellValue(colunas[c]);
            }
            for (int r = 0; r < linhasExemplo.length; r++) {
                Row linha = sheet.createRow(r + 1);
                String[] valores = linhasExemplo[r];
                for (int c = 0; c < valores.length; c++) {
                    if (valores[c] != null && !valores[c].isEmpty()) {
                        linha.createCell(c).setCellValue(valores[c]);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao gerar o modelo de planilha.", e);
        }
    }

    private static List<String> cabecalhoDaLinha(Row linha, FormulaEvaluator evaluator) {
        List<String> cabecalho = new ArrayList<>();
        int totalColunas = linha.getLastCellNum();
        for (int c = 0; c < totalColunas; c++) {
            String v = valorCelula(linha.getCell(c), evaluator);
            cabecalho.add(v == null ? "" : v.toUpperCase(Locale.ROOT));
        }
        return cabecalho;
    }

    private static Row primeiraLinhaOuFalha(Sheet planilha) {
        Row linha = planilha.getRow(planilha.getFirstRowNum());
        if (linha == null) {
            throw new IllegalArgumentException("Arquivo vazio ou sem cabeçalho.");
        }
        return linha;
    }

    private static Sheet abrirPrimeiraPlanilha(byte[] bytes) {
        Workbook workbook;
        try {
            workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Falha ao abrir planilha de importação ({} bytes)", bytes.length, e);
            throw new IllegalArgumentException("Não foi possível ler o arquivo — envie uma planilha Excel (.xlsx ou .xls).");
        }
        if (workbook.getNumberOfSheets() == 0) {
            throw new IllegalArgumentException("Arquivo sem nenhuma planilha.");
        }
        return workbook.getSheetAt(0);
    }

    private static byte[] bytesDe(MultipartFile arquivo) {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo enviado.");
        }
        try {
            return arquivo.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível ler o arquivo enviado.");
        }
    }

    /** Célula → texto normalizado (mesmo contrato do antigo {@code LinhaCsv}: {@code null} para
     *  vazio/ausente). Fórmula é avaliada antes de aplicar a mesma conversão por tipo. */
    private static String valorCelula(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.FORMULA) {
            CellValue resultado = evaluator.evaluate(cell);
            return switch (resultado.getCellType()) {
                case STRING -> textoOuNulo(resultado.getStringValue());
                case NUMERIC -> numeroOuData(cell, resultado.getNumberValue());
                case BOOLEAN -> resultado.getBooleanValue() ? "TRUE" : "FALSE";
                default -> null;
            };
        }
        return switch (cell.getCellType()) {
            case STRING -> textoOuNulo(cell.getStringCellValue());
            case NUMERIC -> numeroOuData(cell, cell.getNumericCellValue());
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            default -> null; // BLANK, ERROR, _NONE
        };
    }

    private static String textoOuNulo(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** Célula numérica formatada como data vira texto {@code dd/mm/aaaa} (mesmo formato que
     *  {@link #data} já espera); célula numérica comum vira texto plano, sem separador de milhar
     *  e sem notação científica — {@code stripTrailingZeros} evita "36.0" para um tamanho digitado
     *  numa célula numérica. */
    private static String numeroOuData(Cell cell, double numero) {
        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDate data = DateUtil.getLocalDateTime(numero).toLocalDate();
            return data.format(FORMATO_DATA);
        }
        return BigDecimal.valueOf(numero).stripTrailingZeros().toPlainString();
    }

    /** Decimal BR ("1.234,56" ou "35,00") ou texto plano já sem vírgula (caso comum vindo de
     *  célula numérica, já convertida por {@link #numeroOuData}) — só remove separador de milhar
     *  quando há vírgula decimal presente, pra não confundir "3.5" americano com "3.500".
     *  {@code campo} é o nome da coluna (ex. "PRECO_CUSTO") — entra na mensagem de erro pra quem
     *  for corrigir a planilha saber exatamente qual coluna revisar, não só o valor cru. */
    public static BigDecimal decimal(String campo, String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String s = valor.trim();
        if (s.contains(",")) {
            s = s.replace(".", "").replace(",", ".");
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new CampoInvalidoException(campo, valor, "valor numérico inválido");
        }
    }

    public static Integer inteiro(String campo, String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            throw new CampoInvalidoException(campo, valor, "valor inteiro inválido");
        }
    }

    /** Aceita {@code /}, {@code -} ou {@code .} como separador e ignora qualquer hora/minuto/
     *  segundo depois da data — ver {@link #PADRAO_DATA}. */
    public static LocalDate data(String campo, String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        Matcher m = PADRAO_DATA.matcher(valor.trim());
        if (!m.find()) {
            throw new CampoInvalidoException(campo, valor, "data inválida (use dd/mm/aaaa)");
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (DateTimeException e) {
            throw new CampoInvalidoException(campo, valor, "data inválida (use dd/mm/aaaa)");
        }
    }

    /** Remove todo espaço em branco de dentro do valor (não só nas pontas — {@link LinhaPlanilha#valor}
     *  já dá trim) — usado em campos que nunca têm espaço de verdade (ex.: e-mail), onde um
     *  espaço no meio quase sempre é sujeira de copiar/colar de outro sistema, não dado real
     *  (2026-08-06, pedido do dono do produto pro campo EMAIL da importação). */
    public static String semEspacos(String valor) {
        return valor == null ? null : valor.replaceAll("\\s+", "");
    }

    public static boolean booleano(String valor, boolean padrao) {
        if (valor == null || valor.isBlank()) {
            return padrao;
        }
        String s = valor.trim().toUpperCase(Locale.ROOT);
        return s.equals("SIM") || s.equals("S") || s.equals("TRUE") || s.equals("1");
    }
}
