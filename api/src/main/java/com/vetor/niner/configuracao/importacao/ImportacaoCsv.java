package com.vetor.niner.configuracao.importacao;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Leitura de CSV da Rotina de Importação de Dados (docs/telas/importacao-dados.md). Formato
 * fixo do projeto: delimitador {@code ;}, decimal com vírgula, datas {@code dd/mm/aaaa},
 * UTF-8 (com ou sem BOM). Splitter próprio (sem dependência nova) — tolera campo entre aspas
 * duplas contendo {@code ;} (RFC 4180 simplificado).
 */
public final class ImportacaoCsv {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/uuuu");

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
     *  confundir "3.5" americano por engano com "3.500". */
    public static BigDecimal decimal(String valor) {
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
            throw new IllegalArgumentException("Valor numérico inválido: \"" + valor + "\".");
        }
    }

    public static Integer inteiro(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor inteiro inválido: \"" + valor + "\".");
        }
    }

    public static LocalDate data(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(valor.trim(), FORMATO_DATA);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Data inválida (use dd/mm/aaaa): \"" + valor + "\".");
        }
    }

    public static boolean booleano(String valor, boolean padrao) {
        if (valor == null || valor.isBlank()) {
            return padrao;
        }
        String s = valor.trim().toUpperCase(Locale.ROOT);
        return s.equals("SIM") || s.equals("S") || s.equals("TRUE") || s.equals("1");
    }
}
