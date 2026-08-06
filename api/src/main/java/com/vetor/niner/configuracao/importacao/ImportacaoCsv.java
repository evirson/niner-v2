package com.vetor.niner.configuracao.importacao;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura de CSV da Rotina de Importação de Dados (docs/telas/importacao-dados.md). Formato
 * fixo do projeto: delimitador {@code ;}, decimal com vírgula, datas {@code dd/mm/aaaa},
 * UTF-8 (com ou sem BOM). Splitter próprio (sem dependência nova) — tolera campo entre aspas
 * duplas contendo {@code ;} (RFC 4180 simplificado).
 */
public final class ImportacaoCsv {

    /** dd/mm/aaaa tolerante: separador `/`, `-` ou `.`; dia/mês com 1 ou 2 dígitos; qualquer
     *  coisa depois da data (hora, minuto, segundo — ex. "28.07.1980 00:00:00") é ignorada, não
     *  precisa bater o resto da string (2026-08-06, planilhas exportadas de outros sistemas
     *  costumam vir com timestamp completo mesmo em coluna de "só data"). */
    private static final Pattern PADRAO_DATA = Pattern.compile("^(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})");

    private ImportacaoCsv() {
    }

    /** Uma linha de dados já mapeada por cabeçalho (colunas em MAIÚSCULAS). {@code numeroLinha}
     *  é 1-based contando o cabeçalho como linha 1 — bate com o que o usuário vê no Excel. */
    public record LinhaCsv(int numeroLinha, Map<String, String> valores) {

        public String valor(String coluna) {
            String v = valores.get(coluna);
            if (v == null) {
                return null;
            }
            v = v.trim();
            return v.isEmpty() ? null : v;
        }
    }

    public static List<LinhaCsv> ler(MultipartFile arquivo) {
        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo enviado.");
        }
        String texto;
        try {
            texto = new String(arquivo.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível ler o arquivo enviado.");
        }
        if (!texto.isEmpty() && texto.charAt(0) == '﻿') {
            texto = texto.substring(1);
        }
        String[] linhasBrutas = texto.split("\r\n|\n|\r");
        if (linhasBrutas.length == 0 || linhasBrutas[0].isBlank()) {
            throw new IllegalArgumentException("Arquivo vazio ou sem cabeçalho.");
        }

        String[] cabecalho = dividirLinha(linhasBrutas[0]);
        for (int i = 0; i < cabecalho.length; i++) {
            cabecalho[i] = cabecalho[i].trim().toUpperCase(Locale.ROOT);
        }

        List<LinhaCsv> linhas = new ArrayList<>();
        for (int i = 1; i < linhasBrutas.length; i++) {
            String bruta = linhasBrutas[i];
            if (bruta == null || bruta.isBlank()) {
                continue;
            }
            String[] celulas = dividirLinha(bruta);
            Map<String, String> valores = new LinkedHashMap<>();
            for (int c = 0; c < cabecalho.length; c++) {
                valores.put(cabecalho[c], c < celulas.length ? celulas[c].trim() : "");
            }
            linhas.add(new LinhaCsv(i + 1, valores));
        }
        return linhas;
    }

    private static String[] dividirLinha(String linha) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean dentroDeAspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (dentroDeAspas) {
                if (c == '"') {
                    if (i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                        atual.append('"');
                        i++;
                    } else {
                        dentroDeAspas = false;
                    }
                } else {
                    atual.append(c);
                }
            } else if (c == '"') {
                dentroDeAspas = true;
            } else if (c == ';') {
                campos.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString());
        return campos.toArray(new String[0]);
    }

    /** Decimal BR ("1.234,56" ou "35,00"). Sem vírgula, assume que já está em formato simples
     *  ("100") — só remove separador de milhar quando há vírgula decimal presente, pra não
     *  confundir "3.5" americano por engano com "3.500". {@code campo} é o nome da coluna
     *  (ex. "PRECO_CUSTO") — entra na mensagem de erro pra quem for corrigir a planilha saber
     *  exatamente qual coluna revisar, não só o valor cru. */
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

    /** Remove todo espaço em branco de dentro do valor (não só nas pontas — {@link LinhaCsv#valor}
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
