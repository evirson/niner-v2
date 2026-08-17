package com.vetor.niner;

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
 * V034. Tabela global, sem RLS: é referência nacional, não dado de tenant, então o teste não
 * precisa de tenant nenhum.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SefazAutorizadorCrudTest {

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

    @Test
    void ufNaoCadastradaFalhaComMensagemQueExplicaOEscopo() {
        assertThatThrownBy(() -> autorizadores.buscar("SP", 65, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não está cadastrada");
    }

    /** NF-e (55) do PR está na tabela, mas sem endpoints — pendência conhecida da F0. Devolver
     *  {@code null} (em vez de estourar) deixa a decisão com quem chama. */
    @Test
    void servicoSemUrlMapeadaDevolveNuloEmVezDeEstourar() {
        String url = autorizadores.urlDe("PR", 55, 2, ServicoSefaz.AUTORIZACAO);

        assertThat(url).isNull();
    }

    @Test
    void ufEhNormalizadaParaMaiusculas() {
        assertThat(autorizadores.urlDe("pr", 65, 2, ServicoSefaz.AUTORIZACAO)).isNotNull();
    }
}
