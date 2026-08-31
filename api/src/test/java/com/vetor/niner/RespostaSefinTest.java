package com.vetor.niner;

import com.vetor.niner.fiscal.nfse.NfseTransporte;
import com.vetor.niner.fiscal.nfse.RespostaSefin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RespostaSefin} contra as respostas <b>reais</b> do Sefin Nacional, capturadas nas
 * sondagens de 2026-08-29/31 (docs/MODULONFSE.md §2.6 e §2.7).
 *
 * <p>⭐ São payloads que o governo devolveu, não JSON inventado por mim. É o que separa este teste
 * de um que apenas concorda com o parser: se o formato mudar, aqui é onde se descobre.
 */
class RespostaSefinTest {

    private static RespostaSefin ler(int status, String corpo) {
        return RespostaSefin.de(new NfseTransporte.Retorno(status, corpo));
    }

    /** Emissão que deu certo — a NFS-e 7308, Curitiba, 2026-08-31. */
    @Test
    void leEmissaoAutorizada() {
        String corpo = """
                {"tipoAmbiente":1,"versaoAplicativo":"SefinNacional_1.6.0",
                 "dataHoraProcessamento":"2026-08-31T07:48:27.590508-03:00",
                 "idDps":"NFS41069022222120254000186000000000730826087756429072",
                 "chaveAcesso":"41069022222120254000186000000000730826087756429072",
                 "nfseXmlGZipB64":"H4sIAAAAAAAEA",
                 "alertas":null}""";

        RespostaSefin r = ler(201, corpo);

        assertThat(r.sucesso()).isTrue();
        assertThat(r.chaveAcesso()).isEqualTo("41069022222120254000186000000000730826087756429072");
        assertThat(r.xmlGZipB64()).isEqualTo("H4sIAAAAAAAEA");
        assertThat(r.falhaDeComunicacao()).isFalse();
    }

    /** A recusa que provou que a alíquota do Simples é pré-requisito. */
    @Test
    void leRejeicaoDeEmissaoComCodigoFiscal() {
        String corpo = """
                {"tipoAmbiente":1,"versaoAplicativo":"SefinNacional_1.6.0",
                 "idDPS":"DPS410690222212025400018600001000000002001000",
                 "erros":[{"Codigo":"E0712","Descricao":"Para ME/EPP o indicador de informação de \
                valor total de tributos não pode ser informado."}]}""";

        RespostaSefin r = ler(400, corpo);

        assertThat(r.sucesso()).isFalse();
        assertThat(r.falhaDeComunicacao()).as("400 com erros[] é REJEIÇÃO, não indisponibilidade")
                .isFalse();
        assertThat(r.primeiroCodigo()).isEqualTo("E0712");
        // O código fiscal tem de vir NO INÍCIO: é dele que dependem a tarja da tela e o
        // agrupamento do relatório de documentos fiscais.
        assertThat(r.mensagem()).startsWith("E0712 — ");
    }

    /**
     * ⭐ O contêiner muda de nome no evento: "erro" singular e minúsculo. O parser do workshop não
     * cobre este caso, e por isso perderia o código de toda recusa de cancelamento.
     */
    @Test
    void leRejeicaoDeEVENTOComContainerSingularEMinusculo() {
        String corpo = """
                {"tipoAmbiente":1,"versaoAplicativo":"SefinNacional_1.6.0",
                 "erro":[{"codigo":"E0840","descricao":"O Sistema Nacional NFS-e não pode \
                recepcionar o EVENTO DE CANCELAMENTO DE NFS-e, pois o evento de Cancelamento de \
                NFS-e já está vinculado à NFS-e indicada no evento enviado."}]}""";

        RespostaSefin r = ler(400, corpo);

        assertThat(r.primeiroCodigo()).isEqualTo("E0840");
        assertThat(r.mensagem()).startsWith("E0840 — ").contains("já está vinculado");
    }

    /** ⭐ No E1235 o Complemento é o único campo acionável — sem ele a mensagem não resolve nada. */
    @Test
    void preservaOComplementoDoErroDeSchema() {
        String corpo = """
                {"erros":[{"Codigo":"E1235","Descricao":"Falha no esquema XML do DF-e.",
                 "Complemento":"The element 'toma' in namespace 'http://www.sped.fazenda.gov.br/nfse' \
                has incomplete content. List of possible elements expected: 'CAEPF, IM, xNome'."}]}""";

        RespostaSefin r = ler(400, corpo);

        assertThat(r.mensagem())
                .startsWith("E1235 — Falha no esquema XML do DF-e.")
                .contains("CAEPF, IM, xNome");
    }

    // ---- rejeição × indisponibilidade --------------------------------------------------------

    /** Página HTML do gateway: a DPS não foi avaliada e o número NÃO queimou. */
    @Test
    void corpoNaoJsonEhIndisponibilidadeENaoRejeicao() {
        String html = "<!DOCTYPE html><html><body><h1>Server Error</h1>"
                + "<h2>The resource cannot be found.</h2></body></html>";

        RespostaSefin r = ler(404, html);

        assertThat(r.falhaDeComunicacao()).isTrue();
        assertThat(r.mensagem())
                .contains("não chegou a ser avaliada")
                .contains("número não foi consumido")
                .contains("The resource cannot be found");
    }

    @Test
    void cincoXxSemListaDeErrosEhIndisponibilidade() {
        RespostaSefin r = ler(503, "{\"mensagem\":\"Service Unavailable\"}");

        assertThat(r.falhaDeComunicacao()).isTrue();
        assertThat(r.mensagem()).contains("reenvie com o mesmo número");
    }

    /**
     * O par negativo do anterior: 400 COM lista de erros nunca pode virar indisponibilidade.
     * Confundir os dois faz o sistema martelar um erro permanente — foi assim que o finance-v
     * chegou a 2.211 tentativas contra o mesmo E0190.
     */
    @Test
    void quatrocentosComErrosNuncaVirarIndisponibilidade() {
        RespostaSefin r = ler(400, "{\"erros\":[{\"Codigo\":\"E0190\",\"Descricao\":\"CNPJ do "
                + "tomador não encontrado no cadastro CNPJ.\"}]}");

        assertThat(r.falhaDeComunicacao()).isFalse();
        assertThat(r.primeiroCodigo()).isEqualTo("E0190");
    }
}
