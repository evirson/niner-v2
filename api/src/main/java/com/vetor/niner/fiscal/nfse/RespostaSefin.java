package com.vetor.niner.fiscal.nfse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Interpreta a resposta do Sefin Nacional.
 *
 * <h2>⚠️ O {@code codigo_status} é HTTP, e ele NUNCA identifica a causa</h2>
 *
 * <p>Toda recusa vem como <b>400</b>. O código que o usuário vê no portal da prefeitura
 * ({@code E0240}, {@code E0712}, {@code E0116}…) está <b>dentro</b> da lista de erros. Guardar só
 * o HTTP deixa a nota com "NFS-e rejeitada" e nada acionável — é o defeito que o
 * {@code workshop} documentou como o mais caro do módulo deles.
 *
 * <h2>⚠️ E o contêiner do erro MUDA DE NOME entre emissão e evento</h2>
 *
 * <pre>
 *   emissão:  {"erros":[{"Codigo":"E0712","Descricao":"…"}]}    plural, maiúsculo
 *   evento:   {"erro" :[{"codigo":"E0840","descricao":"…"}]}    SINGULAR, minúsculo
 * </pre>
 *
 * <p>Medido nas duas pontas em 2026-08-31. O {@code descreverErros} do {@code workshop} procura
 * {@code erros}/{@code Erros}/{@code mensagens} e <b>não</b> procura {@code erro} — uma recusa de
 * cancelamento cairia no genérico e perderia o código. Aqui as quatro combinações são cobertas.
 *
 * <h2>⭐ {@code Complemento} é o que aciona no {@code E1235}</h2>
 *
 * <p>"Falha no esquema XML do DF-e" não diz nada; o {@code Complemento} diz exatamente qual
 * elemento o validador esperava ("the element 'toma' has incomplete content. List of possible
 * elements expected: CAEPF, IM, xNome"). Foi ele que resolveu duas rejeições nossas, então ele
 * entra na mensagem — não é ruído.
 *
 * <h2>Rejeição × indisponibilidade</h2>
 *
 * <p>Corpo não-JSON (página HTML de gateway) ou 5xx sem lista de erros significa que a DPS
 * <b>não foi avaliada</b>: nenhuma nota foi gerada e o número não queimou. Reenviar com o
 * <b>mesmo</b> número é o caminho certo. Confundir com rejeição faz a nota sumir da fila; o
 * contrário faz o sistema martelar um erro permanente para sempre.
 */
public record RespostaSefin(
        int httpStatus,
        boolean sucesso,
        String chaveAcesso,
        Long numeroNfse,
        String xmlGZipB64,
        String primeiroCodigo,
        String mensagem,
        boolean falhaDeComunicacao,
        String corpoBruto) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Nomes possíveis do contêiner de erro, nas duas operações e nas duas capitalizações. */
    private static final String[] CONTEINERES_DE_ERRO =
            {"erros", "Erros", "erro", "Erro", "mensagens", "Mensagens"};

    /** Nomes possíveis do campo que traz o XML devolvido (emissão × evento). */
    private static final String[] CAMPOS_DE_XML =
            {"nfseXmlGZipB64", "eventoXmlGZipB64", "pedidoRegistroEventoXmlGZipB64"};

    public static RespostaSefin de(NfseTransporte.Retorno retorno) {
        int status = retorno.status();
        boolean ok = status >= 200 && status < 300;
        String corpo = retorno.corpo() == null ? "" : retorno.corpo();

        JsonNode raiz;
        try {
            raiz = JSON.readTree(corpo);
        } catch (Exception naoEhJson) {
            // Página de erro do gateway: o SEFIN nem chegou a olhar a DPS.
            return new RespostaSefin(status, false, null, null, null, null,
                    "Serviço da NFS-e Nacional indisponível (HTTP " + status + "). A DPS não chegou "
                    + "a ser avaliada — nenhuma nota foi gerada e o número não foi consumido. "
                    + "Envie novamente em alguns minutos." + trecho(corpo),
                    true, corpo);
        }

        List<String> erros = extrairErros(raiz);
        String mensagem = erros.isEmpty() ? null : String.join(" • ", erros);
        String primeiroCodigo = erros.isEmpty() ? null : codigoDe(erros.get(0));

        if (ok) {
            return new RespostaSefin(status, true, texto(raiz, "chaveAcesso", "chNFSe", "chAcesso"),
                    numero(raiz), primeiroXml(raiz), null, mensagem, false, corpo);
        }

        // 5xx sem lista de erros também é indisponibilidade — o serviço caiu antes de avaliar.
        boolean indisponivel = erros.isEmpty() && status >= 500;
        String descricao = mensagem != null ? mensagem
                : texto(raiz, "xMotivo", "mensagem", "message", "title");
        if (indisponivel) {
            descricao = "Serviço da NFS-e Nacional indisponível (HTTP " + status + "). A DPS não "
                    + "chegou a ser avaliada — reenvie com o mesmo número.";
        }
        return new RespostaSefin(status, false, null, null, null, primeiroCodigo,
                descricao != null ? descricao : "Recusa sem detalhe (HTTP " + status + ")",
                indisponivel, corpo);
    }

    /** Formato gravado em {@code nfse_documento.motivo_status}: código fiscal NO INÍCIO. */
    private static List<String> extrairErros(JsonNode raiz) {
        List<String> saida = new ArrayList<>();
        for (String nome : CONTEINERES_DE_ERRO) {
            JsonNode lista = raiz.get(nome);
            if (lista == null || !lista.isArray()) {
                continue;
            }
            for (JsonNode item : lista) {
                if (item.isTextual()) {
                    saida.add(item.asText().trim());
                    continue;
                }
                String codigo = texto(item, "Codigo", "codigo", "cStat");
                String descricao = texto(item, "Descricao", "descricao", "xMotivo", "mensagem");
                String complemento = texto(item, "Complemento", "complemento");
                String base = codigo != null && descricao != null ? codigo + " — " + descricao
                        : codigo != null ? codigo : descricao;
                if (base == null || base.isBlank()) {
                    continue;
                }
                // ⭐ O complemento é o que aciona no E1235. Vai junto, entre parênteses.
                saida.add(complemento == null || complemento.isBlank()
                        ? base : base + " (" + complemento + ")");
            }
            if (!saida.isEmpty()) {
                return saida;
            }
        }
        return saida;
    }

    private static String codigoDe(String mensagem) {
        var m = java.util.regex.Pattern.compile("^\\s*(E\\d{4})").matcher(mensagem);
        return m.find() ? m.group(1) : null;
    }

    private static String primeiroXml(JsonNode raiz) {
        for (String campo : CAMPOS_DE_XML) {
            String valor = texto(raiz, campo);
            if (valor != null) {
                return valor;
            }
        }
        return null;
    }

    private static Long numero(JsonNode raiz) {
        String valor = texto(raiz, "nNFSe", "numeroNfse");
        try {
            return valor == null ? null : Long.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String texto(JsonNode no, String... nomes) {
        if (no == null) {
            return null;
        }
        for (String nome : nomes) {
            JsonNode valor = no.get(nome);
            if (valor != null && !valor.isNull() && !valor.asText().isBlank()) {
                return valor.asText().trim();
            }
        }
        return null;
    }

    private static String trecho(String corpo) {
        String limpo = corpo.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return limpo.isEmpty() ? "" : " [retorno: "
                + limpo.substring(0, Math.min(160, limpo.length())) + "]";
    }
}
