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

    @Test
    void ufEhNormalizadaParaMaiusculas() {
        assertThat(autorizadores.urlDe("pr", 65, 2, ServicoSefaz.AUTORIZACAO)).isNotNull();
    }
}
