package com.vetor.niner.fiscal.nfse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * O que varia por município — o F10 do módulo fiscal aplicado à prefeitura.
 *
 * <p>Guarda em {@code cfg_municipio_nfse} o que o ADN respondeu, com {@code consultado_em} para
 * que linha velha seja reconsultada em vez de envelhecer em silêncio. A tabela é GLOBAL: é fato
 * sobre a cidade, igual para todos os lojistas dela.
 *
 * <h2>⚠️ O {@code enviar_im} não tem padrão seguro, e é honesto dizer isso</h2>
 *
 * <p>Mandar a IM onde o município não a quer devolve {@code E0120}; omiti-la onde ele a exige
 * devolve {@code E0116}. <b>Os dois lados falham</b>, então não existe default correto — o que
 * existe é descoberta. Enquanto ninguém descobriu, o padrão é <b>não enviar</b>, por dois motivos:
 * é o que funciona em produção no único município que medimos (Curitiba), e o {@code E0120} tem
 * mensagem mais direta que o {@code E0116}, cujo texto acusa omissão mesmo quando a IM foi
 * enviada.
 */
@Service
public class MunicipioNfseService {

    private final JdbcClient jdbc;

    public MunicipioNfseService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Se a Inscrição Municipal vai na DPS daquele município e ambiente.
     *
     * <p>⚠️ Sem linha, devolve {@code false} — ver o javadoc da classe para por que "não enviar" é
     * o palpite menos ruim, e não uma escolha segura.
     */
    @Transactional(readOnly = true)
    public boolean enviarInscricaoMunicipal(int codigoIbge, boolean producao) {
        return jdbc.sql("""
                        SELECT COALESCE(enviar_im, false) FROM cfg_municipio_nfse
                         WHERE codigo_ibge = ? AND ambiente = ?::ambiente_fiscal
                        """)
                .params(codigoIbge, producao ? "PRODUCAO" : "HOMOLOGACAO")
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<Parametros> buscar(int codigoIbge, boolean producao) {
        return jdbc.sql("""
                        SELECT codigo_ibge, aderente_emissor_nacional, enviar_im,
                               prazo_cancelamento_horas, consultado_em IS NOT NULL AS consultado
                          FROM cfg_municipio_nfse
                         WHERE codigo_ibge = ? AND ambiente = ?::ambiente_fiscal
                        """)
                .params(codigoIbge, producao ? "PRODUCAO" : "HOMOLOGACAO")
                .query((rs, n) -> new Parametros(
                        rs.getInt("codigo_ibge"),
                        (Boolean) rs.getObject("aderente_emissor_nacional"),
                        (Boolean) rs.getObject("enviar_im"),
                        (Integer) rs.getObject("prazo_cancelamento_horas"),
                        rs.getBoolean("consultado")))
                .optional();
    }

    /** Grava o que o ADN respondeu sobre o convênio, sem tocar no que foi descoberto por sondagem. */
    @Transactional
    public void registrarConvenio(int codigoIbge, boolean producao,
                                  ParametrosMunicipaisClient.Convenio convenio) {
        jdbc.sql("""
                        INSERT INTO cfg_municipio_nfse (
                            codigo_ibge, ambiente, aderente_emissor_nacional, consultado_em)
                        VALUES (?, ?::ambiente_fiscal, ?, now())
                        ON CONFLICT (codigo_ibge, ambiente) DO UPDATE
                            SET aderente_emissor_nacional = EXCLUDED.aderente_emissor_nacional,
                                consultado_em = now()
                        """)
                .params(codigoIbge, producao ? "PRODUCAO" : "HOMOLOGACAO",
                        convenio.aderenteEmissorNacional())
                .update();
    }

    /**
     * Grava o que a sondagem do assistente descobriu sobre a IM.
     *
     * <p>⚠️ Só o assistente escreve aqui — a descoberta custa uma DPS de sondagem, e fazê-la numa
     * emissão de verdade gastaria número do lojista para responder uma pergunta de configuração.
     */
    @Transactional
    public void registrarEnvioDeIm(int codigoIbge, boolean producao, boolean enviarIm,
                                   String observacao) {
        jdbc.sql("""
                        INSERT INTO cfg_municipio_nfse (
                            codigo_ibge, ambiente, enviar_im, observacao, consultado_em)
                        VALUES (?, ?::ambiente_fiscal, ?, ?, now())
                        ON CONFLICT (codigo_ibge, ambiente) DO UPDATE
                            SET enviar_im = EXCLUDED.enviar_im,
                                observacao = EXCLUDED.observacao,
                                consultado_em = now()
                        """)
                .params(codigoIbge, producao ? "PRODUCAO" : "HOMOLOGACAO", enviarIm, observacao)
                .update();
    }

    /**
     * Prazo de cancelamento em horas, quando conhecido.
     *
     * <p>⚠️ É competência <b>municipal</b> e o ADN não devolve: 24 h em Curitiba, 5 dias no padrão
     * nacional, outros valores em outras cidades. Vazio significa <b>desconhecido</b>, e a tela
     * avisa que o prazo não foi confirmado em vez de afirmar um número inventado.
     */
    @Transactional(readOnly = true)
    public Optional<Integer> prazoCancelamentoHoras(int codigoIbge, boolean producao) {
        return buscar(codigoIbge, producao).map(Parametros::prazoCancelamentoHoras)
                .filter(p -> p != null);
    }

    public record Parametros(int codigoIbge, Boolean aderenteEmissorNacional, Boolean enviarIm,
                             Integer prazoCancelamentoHoras, boolean consultado) {
    }
}
