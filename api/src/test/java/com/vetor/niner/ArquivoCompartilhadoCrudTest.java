package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PDF;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Compartilhamento efêmero de arquivo por link público ({@code comum.arquivocompartilhado}) —
 * upload autenticado (qualquer papel do tenant), download público sem token JWT (quem baixa é o
 * cliente final, no WhatsApp, sem sessão no sistema). Não toca o banco (o cache é só em memória);
 * o {@code TestcontainersConfiguration} entra só porque a aplicação inteira precisa do Postgres
 * pra subir (signup, JWT).
 *
 * <p>Não testado aqui (gap aceito de propósito): a expiração real após
 * {@code niner.arquivo-compartilhado.expiracao-horas} — o projeto não tem nenhum precedente de
 * {@code Clock} mockado em teste, e simular 24h de tempo real não é viável; a garantia de
 * "expira e some" fica só na leitura do código (ver {@link
 * com.vetor.niner.comum.arquivocompartilhado.ArquivoCompartilhadoService}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ArquivoCompartilhadoCrudTest {

    @Autowired
    MockMvc mvc;

    private static final byte[] PDF_VALIDO = "%PDF-1.4\n%%EOF".getBytes();

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Arquivo %s","email":"dono%s@lojaarquivo.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    @Test
    void pdfValidoGeraTokenEFicaBaixavelPublicamenteSemAutenticacao() throws Exception {
        String token = assinarNovoTenant("A1");
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "comprovante.pdf", "application/pdf", PDF_VALIDO);

        String resp = mvc.perform(multipart("/api/v1/arquivos-compartilhados").file(arquivo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tokenArquivo = JsonPath.read(resp, "$.token");

        // Download sem NENHUM header de Authorization — é assim que o cliente final acessa.
        mvc.perform(get("/api/publico/arquivos-compartilhados/" + tokenArquivo))
                .andExpect(status().isOk())
                .andExpect(content().contentType(APPLICATION_PDF))
                .andExpect(content().bytes(PDF_VALIDO));
    }

    @Test
    void uploadSemAutenticacaoEhRejeitado() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "comprovante.pdf", "application/pdf", PDF_VALIDO);
        mvc.perform(multipart("/api/v1/arquivos-compartilhados").file(arquivo))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void arquivoQueNaoEhPdfEhRejeitadoPorMagicBytesENaoPeloContentType() throws Exception {
        String token = assinarNovoTenant("A2");
        // Content-Type mentindo que é PDF, mas o conteúdo real não começa com "%PDF-".
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "falso.pdf", "application/pdf", "não é pdf de verdade".getBytes());

        mvc.perform(multipart("/api/v1/arquivos-compartilhados").file(arquivo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tokenInexistenteResponde404() throws Exception {
        mvc.perform(get("/api/publico/arquivos-compartilhados/token-que-nao-existe"))
                .andExpect(status().isNotFound());
    }

    /**
     * Limite de 20 arquivos por tenant (2026-08-07) — ao chegar o 21º, o mais antigo daquele
     * tenant é apagado. Confirma tanto que o 1º (mais antigo) sumiu quanto que o 2º (ainda dentro
     * do limite) continua — prova que é exatamente 1 arquivo removido, não mais que isso.
     */
    @Test
    void ao21oArquivoDoMesmoTenantOMaisAntigoEhApagado() throws Exception {
        String token = assinarNovoTenant("A3");
        String[] tokensArquivo = new String[21];
        for (int i = 0; i < 21; i++) {
            MockMultipartFile arquivo = new MockMultipartFile("arquivo", "comprovante.pdf", "application/pdf", PDF_VALIDO);
            String resp = mvc.perform(multipart("/api/v1/arquivos-compartilhados").file(arquivo)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            tokensArquivo[i] = JsonPath.read(resp, "$.token");
        }

        // O mais antigo (índice 0) foi expulso pra caber o 21º.
        mvc.perform(get("/api/publico/arquivos-compartilhados/" + tokensArquivo[0]))
                .andExpect(status().isNotFound());
        // O 2º mais antigo (índice 1) ainda está dentro do limite de 20 — continua valendo.
        mvc.perform(get("/api/publico/arquivos-compartilhados/" + tokensArquivo[1]))
                .andExpect(status().isOk());
        // O último enviado (o 21º) obviamente está lá.
        mvc.perform(get("/api/publico/arquivos-compartilhados/" + tokensArquivo[20]))
                .andExpect(status().isOk());
    }

    /** Confirma que o limite de 20 é POR TENANT — um tenant diferente não é afetado nem afeta o
     *  limite de outro (o cache é compartilhado no processo, mas a contagem não pode vazar). */
    @Test
    void limitePorTenantNaoAfetaOutroTenant() throws Exception {
        String tokenTenantA = assinarNovoTenant("A4");
        String tokenTenantB = assinarNovoTenant("B4");

        for (int i = 0; i < 20; i++) {
            MockMultipartFile arquivo = new MockMultipartFile("arquivo", "comprovante.pdf", "application/pdf", PDF_VALIDO);
            mvc.perform(multipart("/api/v1/arquivos-compartilhados").file(arquivo)
                            .header("Authorization", "Bearer " + tokenTenantA))
                    .andExpect(status().isCreated());
        }

        MockMultipartFile arquivoB = new MockMultipartFile("arquivo", "comprovante.pdf", "application/pdf", PDF_VALIDO);
        String resp = mvc.perform(multipart("/api/v1/arquivos-compartilhados").file(arquivoB)
                        .header("Authorization", "Bearer " + tokenTenantB))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tokenArquivoB = JsonPath.read(resp, "$.token");

        // Tenant B enviou só 1 arquivo — os 20 do tenant A não fazem ele ser expulso.
        mvc.perform(get("/api/publico/arquivos-compartilhados/" + tokenArquivoB))
                .andExpect(status().isOk());
    }
}
