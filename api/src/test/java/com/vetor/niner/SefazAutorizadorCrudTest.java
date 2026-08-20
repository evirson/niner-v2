package com.vetor.niner;

import com.vetor.niner.fiscal.documento.ChaveAcesso;
import com.vetor.niner.fiscal.sefaz.SefazAutorizadorService;
import com.vetor.niner.fiscal.sefaz.SefazDtos.Autorizador;
import com.vetor.niner.fiscal.sefaz.SefazDtos.ServicoSefaz;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Autorizador por UF (F10) — os endpoints e prazos vêm de {@code cfg_uf_autorizador}, semeada na
 * V034 e completada para as <b>27 unidades da federação</b> na V047. Tabela global, sem RLS: é referência nacional, não dado de tenant, então o teste não
 * precisa de tenant nenhum.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SefazAutorizadorCrudTest {

    /** As 27 unidades da federação — 26 estados e o Distrito Federal. */
    private static final String[] UFS_DO_BRASIL = {
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA",
            "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"};

    @Autowired
    SefazAutorizadorService autorizadores;

    @Test
    void prParaNfceEmHomologacaoTemOsEndpointsDoAutorizadorProprio() {
        String url = autorizadores.urlDe("PR", 65, 2, ServicoSefaz.AUTORIZACAO);

        // ⚠️ O PR NÃO usa SVRS para NFC-e — tem autorizador próprio (confirmado no portal Sped-PR).
        assertThat(url).isEqualTo("https://homologacao.nfce.sefa.pr.gov.br/nfce/NFeAutorizacao4");
    }

    @Test
    void prParaNfceEmProducaoApontaParaOHostDeProducao() {
        String url = autorizadores.urlDe("PR", 65, 1, ServicoSefaz.AUTORIZACAO);

        assertThat(url).isEqualTo("https://nfce.sefa.pr.gov.br/nfce/NFeAutorizacao4");
    }

    /**
     * ⚠️ O achado do {@code cStat 878} no B0: a URL de consulta pública do PR <b>não</b> é o host
     * do webservice. Se este teste passar a apontar para {@code nfce.sefa.pr.gov.br}, o QR Code
     * impresso no cupom leva o consumidor para o lugar errado — e a SEFAZ rejeita a nota.
     */
    @Test
    void asUrlsDeConsultaPublicaNaoSaoOHostDoWebservice() {
        Autorizador pr = autorizadores.buscar("PR", 65, 2);

        assertThat(pr.urlQrCode()).isEqualTo("http://www.fazenda.pr.gov.br/nfce/qrcode");
        assertThat(pr.urlConsultaPublica()).isEqualTo("http://www.fazenda.pr.gov.br/nfce/consulta");
        assertThat(pr.urlQrCode()).doesNotContain("sefa.pr.gov.br");
    }

    @Test
    void prazosLegaisDoPrVemDaTabelaNaoDoCodigo() {
        Autorizador pr = autorizadores.buscar("PR", 65, 2);

        assertThat(pr.prazoCancelamentoMinutos()).isEqualTo(30);    // NFC-e: 30 min
        assertThat(pr.prazoContingenciaHoras()).isEqualTo(24);      // NPF 100/2014
        assertThat(pr.codigoUfIbge()).isEqualTo(41);
        assertThat(pr.nome()).isEqualTo("PROPRIO");
    }

    /**
     * ⚠️ Este teste usava <b>SP</b> como exemplo de UF ausente — deixou de servir na V047, que
     * carregou as 27 unidades da federação. Sobrou como exemplo a sigla inválida, que é o que
     * ainda pode chegar aqui (empresa com UF digitada errada).
     */
    @Test
    void ufNaoCadastradaFalhaComMensagemQueExplicaOEscopo() {
        assertThatThrownBy(() -> autorizadores.buscar("XX", 65, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não está cadastrada");
    }

    /**
     * ✅ <b>Pendência da F0 fechada em 2026-08-19</b> (bloco B9, NF-e de devolução): os endpoints de
     * NF-e modelo 55 do PR nasceram <b>nulos de propósito</b> na V034 ("falhar explicitamente em
     * vez de chutar domínio") e foram preenchidos depois de confirmados na fonte oficial — o
     * portal Sped-PR, a mesma que validou os da NFC-e. O PR tem autorizador <b>próprio</b> também
     * para NF-e 55; não usa SVRS.
     *
     * <p>Este teste era o que documentava a pendência (esperava {@code null}) — virou o que
     * documenta a resolução. Sem a NF-e 55 configurada, o B9 não teria para onde transmitir.
     */
    @Test
    void nfe55DoPrTemEndpointProprioConfirmadoNaFonteOficial() {
        assertThat(autorizadores.urlDe("PR", 55, 2, ServicoSefaz.AUTORIZACAO))
                .isEqualTo("https://homologacao.nfe.sefa.pr.gov.br/nfe/NFeAutorizacao4");
        assertThat(autorizadores.urlDe("PR", 55, 1, ServicoSefaz.AUTORIZACAO))
                .isEqualTo("https://nfe.sefa.pr.gov.br/nfe/NFeAutorizacao4");
        // O host é o de NF-e (`nfe.`), não o de NFC-e (`nfce.`) — trocar um pelo outro é um erro
        // fácil de cometer e que só apareceria na primeira transmissão real.
        assertThat(autorizadores.urlDe("PR", 55, 1, ServicoSefaz.AUTORIZACAO))
                .doesNotContain("nfce.sefa");
    }

    // =============================================================================================
    // V047 (2026-08-20) — as 27 unidades da federação. O produto deixou de ser "do Paraná".
    // =============================================================================================

    /** 27 UFs × 2 modelos × 2 ambientes. Faltar uma linha = lojista daquele estado sem emitir. */
    @Test
    void as27UnidadesDaFederacaoTemAsQuatroCombinacoes() {
        for (String uf : UFS_DO_BRASIL) {
            for (int modelo : new int[] {55, 65}) {
                for (int ambiente : new int[] {1, 2}) {
                    assertThat(autorizadores.urlDe(uf, modelo, ambiente, ServicoSefaz.AUTORIZACAO))
                            .as("%s modelo %d ambiente %d", uf, modelo, ambiente)
                            .isNotBlank();
                }
            }
        }
        assertThat(UFS_DO_BRASIL).hasSize(27);
    }

    /**
     * O {@code cUF} da tabela tem de bater com o mapa do Java ({@link ChaveAcesso#codigoUfDe}), que
     * é quem monta a <b>chave de acesso</b>. Divergir aqui produz chave inválida, e a SEFAZ recusa
     * sem dizer que o problema é o cUF — um dos erros mais caros de diagnosticar do módulo.
     */
    @Test
    void codigoIbgeDaTabelaBateComOMapaQueMontaAChaveDeAcesso() {
        for (String uf : UFS_DO_BRASIL) {
            assertThat(autorizadores.buscar(uf, 65, 1).codigoUfIbge())
                    .as("cUF de %s", uf)
                    .isEqualTo(ChaveAcesso.codigoUfDe(uf));
        }
    }

    /**
     * ⚠️ <b>A armadilha da carga:</b> o autorizador muda com o MODELO. BA e PE autorizam a própria
     * NF-e 55 mas usam a SVRS na NFC-e 65; o MA usa SVAN no 55 e SVRS no 65. Quem assumir "um
     * autorizador por UF" manda a NFC-e da Bahia para o endpoint errado.
     */
    @Test
    void autorizadorMudaComOModeloEmBaPeEMa() {
        assertThat(autorizadores.buscar("BA", 55, 1).nome()).isEqualTo("PROPRIO");
        assertThat(autorizadores.buscar("BA", 65, 1).nome()).isEqualTo("SVRS");
        assertThat(autorizadores.buscar("PE", 55, 1).nome()).isEqualTo("PROPRIO");
        assertThat(autorizadores.buscar("PE", 65, 1).nome()).isEqualTo("SVRS");
        assertThat(autorizadores.buscar("MA", 55, 1).nome()).isEqualTo("SVAN");
        assertThat(autorizadores.buscar("MA", 65, 1).nome()).isEqualTo("SVRS");
    }

    /**
     * Toda linha de NFC-e precisa de QR Code <b>e</b> consulta pública: {@code MontadorXmlNfce}
     * recusa montar o {@code infNFeSupl} sem as duas, então uma UF sem elas não vende.
     */
    @Test
    void todaUfTemQrCodeEConsultaPublicaNaNfce() {
        for (String uf : UFS_DO_BRASIL) {
            for (int ambiente : new int[] {1, 2}) {
                Autorizador a = autorizadores.buscar(uf, 65, ambiente);
                assertThat(a.urlQrCode()).as("QR de %s ambiente %d", uf, ambiente).isNotBlank();
                assertThat(a.urlConsultaPublica()).as("consulta de %s ambiente %d", uf, ambiente).isNotBlank();
            }
        }
    }

    /** O endereço de consulta do consumidor é sempre do estado do emitente, mesmo quando quem
     *  autoriza é a SVRS — o cupom manda o consumidor para a SEFAZ dele, não para a do RS. */
    @Test
    void consultaPublicaEhDaUfDoEmitenteMesmoQuandoQuemAutorizaEhASvrs() {
        Autorizador ce = autorizadores.buscar("CE", 65, 1);

        assertThat(ce.nome()).isEqualTo("SVRS");
        assertThat(autorizadores.urlDe("CE", 65, 1, ServicoSefaz.AUTORIZACAO)).contains("svrs.rs.gov.br");
        assertThat(ce.urlQrCode()).contains("sefaz.ce.gov.br");
        assertThat(ce.urlQrCode()).doesNotContain("svrs");
    }

    /** Os seis serviços existem em toda linha — um nulo só apareceria no dia do cancelamento. */
    @Test
    void osSeisServicosExistemEmTodaLinha() {
        for (String uf : UFS_DO_BRASIL) {
            for (int modelo : new int[] {55, 65}) {
                for (ServicoSefaz servico : ServicoSefaz.values()) {
                    assertThat(autorizadores.urlDe(uf, modelo, 1, servico))
                            .as("%s modelo %d serviço %s", uf, modelo, servico)
                            .isNotBlank();
                }
            }
        }
    }

    @Test
    void ufEhNormalizadaParaMaiusculas() {
        assertThat(autorizadores.urlDe("pr", 65, 2, ServicoSefaz.AUTORIZACAO)).isNotNull();
    }
}
