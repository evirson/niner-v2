package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD de produto + categoria de produto (docs/telas/produto.md). Cada teste assina um tenant
 * novo (self-service, R12) e usa o token real emitido — mesmo caminho de
 * {@link ClienteCrudTest}/{@link FornecedorCrudTest} — para exercitar o fluxo completo
 * (JWT → TenantContext → RLS).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProdutoCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Produto %s","email":"dono%s@lojaproduto.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    /** Perfil fiscal mínimo (1 regra, exigida pelo `@NotEmpty` de `PerfilFiscalRequest`) —
     *  suficiente pra testar o vínculo `produto.id_perfil_fiscal`, sem cobrir o motor tributário
     *  (isso é escopo de {@code PerfilFiscalCrudTest}/{@code MotorTributarioTest}). */
    private long criarPerfilFiscal(String token, String nome) throws Exception {
        String corpo = """
                {"nome":"%s","descricao":null,"ativo":true,"regras":[
                  {"crt":1,"ufDestino":"PR","tipoDestinatario":"CONSUMIDOR_FINAL","tipoOperacao":"VENDA_CONSUMIDOR",
                   "cfop":"5102","cstIcms":null,"csosn":"102","aliquotaIcms":"0","percReducaoBc":"0",
                   "mvaSt":"0","aliquotaFcp":"0","cstPis":"99","aliquotaPis":"0",
                   "cstCofins":"99","aliquotaCofins":"0","cstIbscbs":null,"cclasstrib":null,
                   "codigoBeneficio":null}
                ]}
                """.formatted(nome);
        String resp = mvc.perform(post("/api/v1/fiscal/perfis").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idPerfilFiscal")).longValue();
    }

    private long criarCategoria(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-produto")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoria")).longValue();
    }

    @Test
    void criaProdutoComDadosCompletosECategoriasOrdenadas() throws Exception {
        String token = assinarNovoTenant("completo");
        long eletronicos = criarCategoria(token, "Eletrônicos");
        long informatica = criarCategoria(token, "Informática");
        criarNcm("85171231", "APARELHOS TELEFÔNICOS");

        String produto = """
                {"descricao":"mouse sem fio","marca":"acme","referencia":"ref-1",
                 "precoCusto":"25.00","percentualVenda":"100","precoVenda":"50.00",
                 "codigoNcm":"85171231","categorias":[%d,%d]}
                """.formatted(eletronicos, informatica);

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(produto))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.descricao").value("MOUSE SEM FIO"))
                .andExpect(jsonPath("$.marca").value("ACME"))
                .andExpect(jsonPath("$.precoVenda").value(50.00))
                .andExpect(jsonPath("$.codigoNcm").value("85171231"))
                .andExpect(jsonPath("$.categorias[0].idCategoria").value(eletronicos))
                .andExpect(jsonPath("$.categorias[0].indice").value(0))
                .andExpect(jsonPath("$.categorias[1].idCategoria").value(informatica))
                .andExpect(jsonPath("$.categorias[1].indice").value(1))
                .andExpect(jsonPath("$.ativo").value(true));
    }

    /** Fiscal (2026-08-18, `docs/MODULOFISCAL.md` §6.2/DF3) — opcional aqui de propósito, quem
     *  cobra o preenchimento é a Conformidade Fiscal, não este CRUD. */
    @Test
    void criaProdutoComPerfilFiscal() throws Exception {
        String token = assinarNovoTenant("perfil-fiscal");
        long idPerfil = criarPerfilFiscal(token, "Revenda Simples");

        String produto = """
                {"descricao":"produto com perfil","precoCusto":"10.00","percentualVenda":"100",
                 "precoVenda":"20.00","idPerfilFiscal":%d}
                """.formatted(idPerfil);

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(produto))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idPerfilFiscal").value(idPerfil))
                .andExpect(jsonPath("$.nomePerfilFiscal").value("REVENDA SIMPLES"));
    }

    /** Sem perfil informado, os dois campos voltam {@code null} — nunca bloqueia o cadastro. */
    @Test
    void produtoSemPerfilFiscalContinuaOpcional() throws Exception {
        String token = assinarNovoTenant("sem-perfil-fiscal");

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"produto sem perfil","precoCusto":"1.00","percentualVenda":"0","precoVenda":"1.00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idPerfilFiscal").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nomePerfilFiscal").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void perfilFiscalInexistenteAoCriarProdutoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("perfil-fiscal-inexistente");

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"produto perfil fantasma","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00","idPerfilFiscal":999999}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizarProdutoAlteraPerfilFiscal() throws Exception {
        String token = assinarNovoTenant("atualiza-perfil-fiscal");
        long id = criarProdutoSimples(token, "Produto Atualiza Perfil");
        long idPerfil = criarPerfilFiscal(token, "Isento");

        mvc.perform(put("/api/v1/produtos/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Produto Atualiza Perfil","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00","idPerfilFiscal":%d}
                                """.formatted(idPerfil)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPerfilFiscal").value(idPerfil))
                .andExpect(jsonPath("$.nomePerfilFiscal").value("ISENTO"));
    }

    @Test
    void ncmInexistenteAoCriarProdutoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("ncm-inexistente");

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Produto NCM Fantasma","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00","codigoNcm":"00000000"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pesoLiquidoMaiorQueOPesoBrutoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("peso-invalido");

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Produto Pesado","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00","pesoBruto":"1.000","pesoLiquido":"1.500"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pesoLiquidoIgualAoPesoBrutoEhAceito() throws Exception {
        String token = assinarNovoTenant("peso-igual");

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Produto Peso Igual","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00","pesoBruto":"1.000","pesoLiquido":"1.000"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pesoLiquido").value(1.000));
    }

    @Test
    void consultaNcmExistenteDevolveDescricao() throws Exception {
        String token = assinarNovoTenant("ncm-consulta");
        criarNcm("61091000", "CAMISETAS DE MALHA DE ALGODAO");

        mvc.perform(get("/api/v1/ncm/61091000").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigoNcm").value("61091000"))
                .andExpect(jsonPath("$.descricaoNcm").value("CAMISETAS DE MALHA DE ALGODAO"));
    }

    @Test
    void consultaNcmInexistenteDevolve404() throws Exception {
        String token = assinarNovoTenant("ncm-consulta-404");

        mvc.perform(get("/api/v1/ncm/99999999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** Pesquisa por nome (2026-08-13) — quem cadastra um produto nem sempre sabe o código NCM
     *  de cabeça. */
    @Test
    void pesquisaNcmPorNomeDevolveCorrespondencias() throws Exception {
        String token = assinarNovoTenant("ncm-busca-nome");
        criarNcm("61092000", "CAMISETAS REGATA DE MALHA");
        criarNcm("64041200", "CALCADOS DE TENIS ESPORTIVO");

        mvc.perform(get("/api/v1/ncm?busca=camiseta").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].codigoNcm").value("61092000"));
    }

    @Test
    void pesquisaNcmSemTermoDevolveListaVazia() throws Exception {
        String token = assinarNovoTenant("ncm-busca-vazia");

        mvc.perform(get("/api/v1/ncm").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void produtoSemDescricaoEhRejeitado() throws Exception {
        String token = assinarNovoTenant("sem-descricao");

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"precoCusto\":\"10.00\",\"percentualVenda\":\"10\",\"precoVenda\":\"11.00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void categoriaInexistenteAoCriarProdutoEhRejeitada() throws Exception {
        String token = assinarNovoTenant("categoria-inexistente");

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Produto Fantasma","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00","categorias":[999999]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void categoriaDuplicadaNaListaEhRejeitada() throws Exception {
        String token = assinarNovoTenant("categoria-duplicada");
        long categoria = criarCategoria(token, "Duplicada");

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Produto Duplicado","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00","categorias":[%d,%d]}
                                """.formatted(categoria, categoria)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reordenarCategoriasNaAtualizacaoRefleteNoIndice() throws Exception {
        String token = assinarNovoTenant("reordenar");
        long primeira = criarCategoria(token, "Primeira");
        long segunda = criarCategoria(token, "Segunda");

        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Produto Reordenavel","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00","categorias":[%d,%d]}
                                """.formatted(primeira, segunda)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(resp, "$.idProduto")).longValue();

        mvc.perform(put("/api/v1/produtos/" + id).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Produto Reordenavel","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00","categorias":[%d,%d]}
                                """.formatted(segunda, primeira)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categorias[0].idCategoria").value(segunda))
                .andExpect(jsonPath("$.categorias[0].indice").value(0))
                .andExpect(jsonPath("$.categorias[1].idCategoria").value(primeira))
                .andExpect(jsonPath("$.categorias[1].indice").value(1));
    }

    @Test
    void dataFinalDaOfertaAntesDaInicialEhRejeitada() throws Exception {
        String token = assinarNovoTenant("data-oferta-invalida");
        String inicio = LocalDate.now().plusDays(10) + "T00:00:00Z";
        String fim = LocalDate.now().plusDays(1) + "T00:00:00Z";

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Produto Oferta","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"10.00","dataInicioOferta":"%s",
                                 "dataFinalOferta":"%s","precoOferta":"5.00"}
                                """.formatted(inicio, fim)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ofertaComApenasAlgunsCamposPreenchidosEhRejeitada() throws Exception {
        String token = assinarNovoTenant("oferta-incompleta");
        String inicio = LocalDate.now().plusDays(1) + "T00:00:00Z";

        // só início da oferta, sem final nem preço — os três são obrigatórios juntos (item 4).
        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Oferta Incompleta","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"10.00","dataInicioOferta":"%s"}
                                """.formatted(inicio)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dataInicioDaOfertaNoPassadoEhRejeitada() throws Exception {
        String token = assinarNovoTenant("oferta-passado");
        String ontem = LocalDate.now().minusDays(1) + "T00:00:00Z";
        String amanha = LocalDate.now().plusDays(1) + "T00:00:00Z";

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Oferta No Passado","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"10.00","dataInicioOferta":"%s","dataFinalOferta":"%s",
                                 "precoOferta":"5.00"}
                                """.formatted(ontem, amanha)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void precoDeOfertaMaiorOuIgualAoPrecoDeVendaEhRejeitado() throws Exception {
        String token = assinarNovoTenant("oferta-preco-invalido");
        String inicio = LocalDate.now() + "T00:00:00Z";
        String fim = LocalDate.now().plusDays(7) + "T00:00:00Z";

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Oferta Cara","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"10.00","dataInicioOferta":"%s","dataFinalOferta":"%s",
                                 "precoOferta":"10.00"}
                                """.formatted(inicio, fim)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ofertaValidaComOsTresCamposEhAceita() throws Exception {
        String token = assinarNovoTenant("oferta-valida");
        String inicio = LocalDate.now() + "T00:00:00Z";
        String fim = LocalDate.now().plusDays(7) + "T00:00:00Z";

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Oferta Valida","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"10.00","dataInicioOferta":"%s","dataFinalOferta":"%s",
                                 "precoOferta":"7.50"}
                                """.formatted(inicio, fim)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.precoOferta").value(7.50));
    }

    @Test
    void campoMarcadoObrigatorioNaConfiguracaoDeTelaEhExigidoNoBackend() throws Exception {
        String token = assinarNovoTenant("campo-obrigatorio");

        mvc.perform(put("/api/v1/config-tela/catalogo.produto.form").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("[{\"campo\":\"marca\",\"visivel\":true,\"obrigatorio\":true}]"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"Sem Marca\",\"precoCusto\":\"1.00\",\"percentualVenda\":\"0\",\"precoVenda\":\"1.00\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Com Marca","marca":"ACME","precoCusto":"1.00","percentualVenda":"0",
                                 "precoVenda":"1.00"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void idGradeEhIgnoradoQuandoTenantNaoUsaCorGrade() throws Exception {
        String token = assinarNovoTenant("cor-grade-desligado");
        // cfg_geral nasce com cfgUsaCorGrade=false (V023 default) — não precisa desligar.

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Camiseta","precoCusto":"1.00","percentualVenda":"0","precoVenda":"1.00",
                                 "idGrade":999999}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idGrade").doesNotExist());
    }

    @Test
    void idGradeEhObrigatorioQuandoTenantUsaCorGrade() throws Exception {
        String token = assinarNovoTenant("cor-grade-obrigatorio");
        ativarCorGrade(token);

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Sapato","precoCusto":"1.00","percentualVenda":"0","precoVenda":"1.00"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void produtoComGradeEhAceitoQuandoTenantUsaCorGrade() throws Exception {
        String token = assinarNovoTenant("cor-grade-aceito");
        ativarCorGrade(token);
        long idTamanho = criarTamanho(token, "36");
        long idGrade = criarGrade(token, "Grade 36", idTamanho);

        mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"Sapato","precoCusto":"1.00","percentualVenda":"0","precoVenda":"1.00",
                                 "idGrade":%d}
                                """.formatted(idGrade)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idGrade").value(idGrade))
                .andExpect(jsonPath("$.descricaoGrade").value("GRADE 36"));
    }

    private void ativarCorGrade(String token) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":true,
                                 "cfgPermiteQtdDecimal":true,"cfgPermiteEstoqueNegativo":true,"cfgDiasValidadeOrcamento":15,"cfgExigeNumeroVendaDevolucao":false,
                                 "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,"cfgConsisteValorContasPagar":false,
                                 "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":true}
                                """))
                .andExpect(status().isOk());
    }

    private long criarTamanho(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/tamanhos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"%s\"}".formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idTamanho")).longValue();
    }

    private long criarGrade(String token, String descricao, long idTamanho) throws Exception {
        String resp = mvc.perform(post("/api/v1/grades").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"descricao\":\"%s\",\"idsTamanho\":[%d]}".formatted(descricao, idTamanho)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idGrade")).longValue();
    }

    @Test
    void listagemOrdenaPorColunaEDirecaoPedidas() throws Exception {
        String token = assinarNovoTenant("ordenacao");

        criarProdutoSimples(token, "ORDPROD BETA");
        criarProdutoSimples(token, "ORDPROD ALFA");
        criarProdutoSimples(token, "ORDPROD GAMA");

        mvc.perform(get("/api/v1/produtos").param("descricao", "ORDPROD")
                        .param("ordenarPor", "descricao").param("direcao", "DESC")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].descricao").value("ORDPROD GAMA"));
    }

    @Test
    void excluirProdutoSemVinculoApagaDeVerdade() throws Exception {
        String token = assinarNovoTenant("exclusao-simples");
        long id = criarProdutoSimples(token, "Produto Sem Vinculo");

        mvc.perform(delete("/api/v1/produtos/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("excluido"));

        mvc.perform(get("/api/v1/produtos/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void excluirProdutoComVariacaoInativaEmVezDeExcluir() throws Exception {
        String token = assinarNovoTenant("exclusao-vinculo");
        long id = criarProdutoSimples(token, "Produto Com Variacao");
        long idTenant = extrairIdTenant(token);

        criarVariacaoParaProduto(idTenant, id);

        mvc.perform(delete("/api/v1/produtos/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acao").value("inativado"));

        mvc.perform(get("/api/v1/produtos/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));
    }

    @Test
    void criarVariacaoGravaEanQuandoInformado() throws Exception {
        String token = assinarNovoTenant("variacao-ean");
        long id = criarProdutoSimples(token, "Produto Variacao Com Ean");

        String resp = mvc.perform(post("/api/v1/produtos/" + id + "/variacoes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"ean\":\"7900282887111\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ean").value("7900282887111"))
                .andExpect(jsonPath("$.sku").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long idVariacao = ((Number) JsonPath.read(resp, "$.idVariacao")).longValue();

        // Chamar de novo com a MESMA combinação (produto sem grade, cor/tamanho sempre null)
        // acha a variação existente — o novo ean enviado é ignorado, não sobrescreve.
        mvc.perform(post("/api/v1/produtos/" + id + "/variacoes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"ean\":\"0000000000000\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idVariacao").value(idVariacao))
                .andExpect(jsonPath("$.ean").value("7900282887111"));
    }

    private long criarProdutoSimples(String token, String descricao) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"1.00","percentualVenda":"0","precoVenda":"1.00"}
                                """.formatted(descricao)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private static long extrairIdTenant(String token) {
        String[] partes = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(partes[1]));
        Object tid = JsonPath.read(payload, "$.tid");
        return ((Number) tid).longValue();
    }

    /**
     * Insere um NCM diretamente (não há endpoint de escrita — {@code cfg_produto_ncm} é
     * mantida por script, global, sem tenant). Conecta como {@code niner_owner}: {@code
     * niner_app} só tem SELECT nessa tabela (V017).
     */
    /**
     * P8 no catálogo (pendência 48, 2026-08-31).
     *
     * <p>⚠️ <b>Cuidado ao ler este teste:</b> o NCM é a exceção documentada — {@code
     * cfg_produto_ncm} é GLOBAL, sem {@code id_tenant} e sem RLS (V017), e por isso os dois
     * tenants enxergam o mesmo código de propósito. O que não pode atravessar é o <b>produto</b>.
     */
    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("isolamento-prod-a");
        String tokenB = assinarNovoTenant("isolamento-prod-b");
        long categoriaA = criarCategoria(tokenA, "Categoria A");

        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"produto exclusivo do tenant a","precoCusto":"10.00",
                                 "percentualVenda":"100","precoVenda":"20.00","categorias":[%d]}
                                """.formatted(categoriaA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idProdutoA = ((Number) JsonPath.read(resp, "$.idProduto")).longValue();

        mvc.perform(get("/api/v1/produtos/" + idProdutoA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/produtos/" + idProdutoA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/produtos").param("descricao", "EXCLUSIVO DO TENANT")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(0));

        // ⭐ A categoria do tenant A também não pode ser referenciada de B: sem a checagem, o
        // vizinho amarraria o produto dele a uma categoria que não é sua.
        long categoriaB = criarCategoria(tokenB, "Categoria B");
        mvc.perform(put("/api/v1/produtos/" + idProdutoA).header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"sequestrado pelo tenant b","precoCusto":"10.00",
                                 "percentualVenda":"100","precoVenda":"20.00","categorias":[%d]}
                                """.formatted(categoriaB)))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/produtos/" + idProdutoA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/produtos/" + idProdutoA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descricao").value("PRODUTO EXCLUSIVO DO TENANT A"));
    }

    private void criarNcm(String codigo, String descricao) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_owner", "dev_owner");
             Statement st = c.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO cfg_produto_ncm (codigo_ncm, descricao_ncm) VALUES ('"
                            + codigo + "', '" + descricao + "')");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Insere uma variação mínima referenciando o produto (SKU, V017). */
    private void criarVariacaoParaProduto(long idTenant, long idProduto) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
             Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
            st.executeUpdate(
                    "INSERT INTO produto_barra (id_tenant, id_produto, sku)"
                            + " VALUES (" + idTenant + ", " + idProduto + ", 'SKU-TESTE-" + idProduto + "')");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
