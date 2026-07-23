package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Galeria de fotos de produto (docs/infra/armazenamento-imagens.md, ADR-013) — usa
 * {@code fake-gcs-server} ({@link FakeGcsConfiguration}), nenhum teste toca o bucket real.
 * Particularidades cobertas: máximo de 6 fotos por produto (regra de produto, 2026-07-23),
 * validação por magic bytes (não pela extensão/Content-Type enviados), exclusão renumera os
 * índices restantes, reordenação, isolamento de tenant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeGcsConfiguration.class})
class ProdutoImagemCrudTest {

    @Autowired
    MockMvc mvc;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Imagem %s","email":"dono%s@lojaimagem.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long criarProduto(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"10","precoVenda":"11.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private static byte[] pngValido() throws IOException {
        BufferedImage imagem = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagem.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ImageIO.write(imagem, "png", saida);
        return saida.toByteArray();
    }

    private long enviarImagem(String token, long idProduto) throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "foto.png", "image/png", pngValido());
        String resp = mvc.perform(multipart("/api/v1/produtos/" + idProduto + "/imagens")
                        .file(arquivo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        java.util.List<Number> ids = JsonPath.read(resp, "$[*].idImagem");
        return ids.get(ids.size() - 1).longValue();
    }

    @Test
    void enviarImagemDevolveGaleriaComUrlPublicaEIndiceZero() throws Exception {
        String token = assinarNovoTenant("primeira");
        long idProduto = criarProduto(token, "PRODUTO COM FOTO");
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "foto.png", "image/png", pngValido());

        mvc.perform(multipart("/api/v1/produtos/" + idProduto + "/imagens")
                        .file(arquivo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].indice").value(0))
                .andExpect(jsonPath("$[0].url").value(org.hamcrest.Matchers.containsString("niner-erp-dev")));
    }

    @Test
    void arquivoQueNaoEhImagemEhRejeitado() throws Exception {
        String token = assinarNovoTenant("invalido");
        long idProduto = criarProduto(token, "PRODUTO SEM FOTO VALIDA");
        // Extensão/Content-Type dizem "imagem", mas o conteúdo não é — validação é por magic bytes.
        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "foto.png", "image/png", "não é imagem".getBytes());

        mvc.perform(multipart("/api/v1/produtos/" + idProduto + "/imagens")
                        .file(arquivo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setimaFotoEhRejeitada() throws Exception {
        String token = assinarNovoTenant("maximo");
        long idProduto = criarProduto(token, "PRODUTO SEIS FOTOS");
        for (int i = 0; i < 6; i++) {
            enviarImagem(token, idProduto);
        }

        MockMultipartFile setima = new MockMultipartFile("arquivo", "foto.png", "image/png", pngValido());
        mvc.perform(multipart("/api/v1/produtos/" + idProduto + "/imagens")
                        .file(setima)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/produtos/" + idProduto).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imagens.length()").value(6));
    }

    @Test
    void excluirImagemDoMeioRenumeraAsRestantes() throws Exception {
        String token = assinarNovoTenant("exclusao");
        long idProduto = criarProduto(token, "PRODUTO TRES FOTOS");
        long primeira = enviarImagem(token, idProduto);
        long segunda = enviarImagem(token, idProduto);
        long terceira = enviarImagem(token, idProduto);

        String resp = mvc.perform(delete("/api/v1/produtos/" + idProduto + "/imagens/" + segunda)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<Number> indices = JsonPath.read(resp, "$[*].indice");
        assertThat(indices).containsExactly(0, 1);
        java.util.List<Number> idsRestantes = JsonPath.read(resp, "$[*].idImagem");
        assertThat(idsRestantes).containsExactly((int) primeira, (int) terceira);
    }

    @Test
    void reordenarMudaAOrdemDaGaleria() throws Exception {
        String token = assinarNovoTenant("reordenar");
        long idProduto = criarProduto(token, "PRODUTO REORDENAR");
        long primeira = enviarImagem(token, idProduto);
        long segunda = enviarImagem(token, idProduto);

        String resp = mvc.perform(put("/api/v1/produtos/" + idProduto + "/imagens/ordem")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idsImagem\":[%d,%d]}".formatted(segunda, primeira)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<Number> ids = JsonPath.read(resp, "$[*].idImagem");
        assertThat(ids).containsExactly((int) segunda, (int) primeira);
    }

    @Test
    void excluirImagemDeOutroTenantRespondeNaoEncontrado() throws Exception {
        String tokenA = assinarNovoTenant("tenant-a");
        String tokenB = assinarNovoTenant("tenant-b");
        long produtoA = criarProduto(tokenA, "PRODUTO TENANT A");
        long idImagem = enviarImagem(tokenA, produtoA);

        mvc.perform(delete("/api/v1/produtos/" + produtoA + "/imagens/" + idImagem)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/produtos/" + produtoA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imagens.length()").value(1));
    }
}
