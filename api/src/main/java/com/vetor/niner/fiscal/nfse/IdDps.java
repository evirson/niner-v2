package com.vetor.niner.fiscal.nfse;

import org.springframework.stereotype.Component;

/**
 * Monta o atributo {@code Id} do {@code <infDPS>} — 45 caracteres.
 *
 * <pre>
 *   Id = "DPS" + cMun(7) + tpInsc(1) + inscrição federal(14) + série(5) + nDPS(15)
 *          3   +   7     +     1     +        14             +    5     +   15    = 45
 * </pre>
 *
 * <p>É <b>determinístico</b>: mesmo município, CNPJ, série e número produzem sempre o mesmo Id.
 * É isso que permite recuperar uma nota órfã com {@code GET /dps/{id}} depois de um timeout, em
 * vez de reenviar cego e tomar {@code E0014} (DPS duplicada).
 *
 * <p>⛔ <b>Determinístico é o Id, não a chave de acesso.</b> A documentação do {@code finance-v}
 * afirma que a chave é "os 45 caracteres do Id + DV(5)" e isso foi <b>medido como falso</b>: o Id
 * sem o prefixo tem 42 caracteres, a chave tem 50, e as duas divergem no 11º. A chave leva o
 * {@code nNFSe} que o SEFIN atribui e um código numérico <b>aleatório</b> de 9 posições
 * ({@code docs/MODULONFSE.md} §2.7). Quem tentar calculá-la implementa um caminho que não existe.
 *
 * <p>⚠️ O {@code E0004} do SEFIN ("conteúdo do identificador difere da concatenação dos campos")
 * é o que aparece quando este Id e o corpo do XML discordam — quase sempre porque um dos dois foi
 * montado com a série ou o número em formato diferente. Por isso a formatação mora aqui, num lugar
 * só, e o montador do XML lê os mesmos valores.
 */
@Component
public class IdDps {

    /** 3 + 7 + 1 + 14 + 5 + 15. */
    public static final int TAMANHO = 45;

    /** 1 = CPF, 2 = CNPJ, 3 = NIF. O produto só emite por CNPJ. */
    public static final int TIPO_INSCRICAO_CNPJ = 2;

    public String montar(int codigoMunicipioIbge, String cnpj, int serie, long numeroDps) {
        String municipio = String.valueOf(codigoMunicipioIbge);
        if (municipio.length() != 7) {
            throw new IllegalArgumentException(
                    "Código de município IBGE deve ter 7 dígitos: " + codigoMunicipioIbge);
        }
        String digitos = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (digitos.length() != 14) {
            throw new IllegalArgumentException("CNPJ do emitente deve ter 14 dígitos");
        }
        if (serie < 1 || serie > 99_999) {
            throw new IllegalArgumentException("Série fora da faixa 1..99999: " + serie);
        }
        if (numeroDps < 1 || numeroDps > 999_999_999_999_999L) {
            throw new IllegalArgumentException("Número da DPS fora da faixa: " + numeroDps);
        }

        String id = "DPS" + municipio + TIPO_INSCRICAO_CNPJ + digitos
                + String.format("%05d", serie)
                + String.format("%015d", numeroDps);

        // Guarda de sanidade: o XSD recusa qualquer coisa fora de 45, e o erro que ele devolve
        // (E1235) não diz "o Id tem 44" — diz "falha no esquema", que manda o diagnóstico longe.
        if (id.length() != TAMANHO) {
            throw new IllegalStateException(
                    "Id da DPS saiu com " + id.length() + " caracteres (esperado " + TAMANHO + ")");
        }
        return id;
    }

    /**
     * Monta o {@code Id} do pedido de registro de evento — 59 caracteres,
     * {@code "PRE" + chave(50) + tipoEvento(6)}, no pattern {@code PRE[0-9]{56}}.
     *
     * <p>⚠️ O {@code nSeqEvento} <b>não</b> entra no Id: vai só no corpo. O esboço do
     * {@code MAPA.md} do {@code finance-v} sugere o contrário; vale o código deles, que emite em
     * produção, e foi o que a nossa emissão de 2026-08-31 confirmou.
     */
    public String montarIdEvento(String chaveAcesso, String tipoEvento) {
        if (chaveAcesso == null || !chaveAcesso.matches("\\d{50}")) {
            throw new IllegalArgumentException("Chave de acesso da NFS-e deve ter 50 dígitos");
        }
        if (tipoEvento == null || !tipoEvento.matches("\\d{6}")) {
            throw new IllegalArgumentException("Tipo de evento deve ter 6 dígitos");
        }
        return "PRE" + chaveAcesso + tipoEvento;
    }
}
