package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.seguranca.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conformidade Fiscal (docs/telas/fiscal-conformidade.md, bloco B3). Painel somente-leitura —
 * critérios de aceitação adaptados às duas simplificações documentadas em
 * {@code ConformidadeFiscalService} (unidade/origem de produto e indicador_ie de cliente nunca
 * são {@code NULL}, então não entram como checagem; o CRT 3 nunca existe, então não é checado).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConformidadeFiscalCrudTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    // ---------------------------------------------------------------- helpers

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Conformidade %s","email":"dono%s@lojaconf.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static String payload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static long idEmpresaDo(String token) {
        return ((Number) JsonPath.read(payload(token), "$.eid")).longValue();
    }

    private static long idTenantDo(String token) {
        return ((Number) JsonPath.read(payload(token), "$.tid")).longValue();
    }

    private String comoOperador(String tokenAdmin) {
        String p = payload(tokenAdmin);
        return tokens.emitir(
                Long.parseLong(JsonPath.read(p, "$.sub").toString()),
                ((Number) JsonPath.read(p, "$.tid")).longValue(),
                ((Number) JsonPath.read(p, "$.eid")).longValue(),
                JsonPath.read(p, "$.email"),
                List.of("OPERADOR"));
    }

    /** Configura a empresa com o mínimo pra sair do estado "sem configuração fiscal": CRT
     *  Simples (1), CNPJ/IE/município/CNAE preenchidos direto no banco (empresa ainda não tem
     *  tela própria — 08-17), e {@code codigo_tpag} nas 6 carteiras que o signup semeia (também
     *  sem tela própria — sem isso NENHUM tenant chegaria a "pronto", nem um recém-assinado). */
    private void configurarEmpresaCompleta(String token, long idTenant, long idEmpresa, String cnpj) throws Exception {
        jdbc.sql("""
                        UPDATE empresa SET cnpj = ?, inscricao_estadual = '1234567890',
                            codigo_municipio_ibge = 4106902, cnae = '4781400'
                        WHERE id_tenant = ? AND id_empresa = ?
                        """)
                .params(cnpj, idTenant, idEmpresa).update();

        jdbc.sql("""
                        UPDATE tipo_carteira SET codigo_tpag = '01',
                            codigo_bandeira = CASE WHEN categoria_carteira IN ('CARTAO_DEBITO', 'CARTAO_CREDITO')
                                                    THEN '99' ELSE codigo_bandeira END
                        WHERE id_tenant = ?
                        """)
                .param(idTenant).update();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/fiscal/config/" + idEmpresa)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crt":1,"emiteNfce":false,"emiteNfe":false,
                                 "ambiente":"HOMOLOGACAO","serieNfce":1,"serieNfe":1,"serieContingencia":9}
                                """))
                .andExpect(status().isOk());
    }

    private void enviarCertificadoValido(String token, long idEmpresa, String cnpj, int diasValidade) throws Exception {
        long idTenant = idTenantDo(token);
        jdbc.sql("""
                        INSERT INTO fiscal_certificado (id_tenant, id_empresa, arquivo_cifrado, senha_cifrada,
                            cnpj_titular, razao_social_titular, valido_de, valido_ate, impressao_digital, ativo)
                        VALUES (?, ?, '\\x00'::bytea, 'x', ?, 'TITULAR', now() - interval '1 day',
                                now() + (? || ' days')::interval, ?, true)
                        """)
                .params(idTenant, idEmpresa, cnpj, diasValidade, "fp-" + idEmpresa + "-" + diasValidade)
                .update();
    }

    private long criarProduto(String token, String descricao, String codigoNcm) throws Exception {
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"1.00","percentualVenda":"0","precoVenda":"1.00"%s}
                                """.formatted(descricao, codigoNcm == null ? "" : ",\"codigoNcm\":\"" + codigoNcm + "\"")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    private long criarCategoriaCliente(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-cliente")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoriaCliente")).longValue();
    }

    private long criarClientePj(String token, String nome, long idCategoria) throws Exception {
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":false,"nome":"%s","idCategoriaCliente":%d}
                                """.formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    private long criarClientePf(String token, String nome, long idCategoria) throws Exception {
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":true,"nome":"%s","idCategoriaCliente":%d,"genero":"OUTROS"}
                                """.formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    // ---------------------------------------------------------------- Empresa

    @Test
    void tenantSemConfiguracaoFiscalAcusaEBloqueia() throws Exception {
        String token = assinarNovoTenant("sem-config");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronto").value(false))
                .andExpect(jsonPath("$.veredito").value(org.hamcrest.Matchers.containsString("pendência")))
                .andExpect(jsonPath("$.categorias[0].categoria").value("EMPRESA"))
                .andExpect(jsonPath("$.categorias[0].bloqueia").value(true))
                .andExpect(jsonPath("$.categorias[0].quantidadePendencias").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa + "/empresa").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[*].problema").value(
                        org.hamcrest.Matchers.hasItem("Nenhuma configuração fiscal cadastrada.")));
    }

    /** 2026-08-19 — antes desta correção, essas 4 pendências não tinham {@code telaCorrecao}
     *  (o "Corrigir" simplesmente não aparecia): não existia tela nenhuma pra editar CNPJ/
     *  Inscrição Estadual/código de município IBGE/CNAE da empresa. Agora apontam pra
     *  "identidade.empresa" (`/empresas/{id}` no front). */
    @Test
    void pendenciasDeCadastroDaEmpresaApontamParaATelaDeEmpresa() throws Exception {
        String token = assinarNovoTenant("sem-cadastro");
        long idEmpresa = idEmpresaDo(token);

        String resp = mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa + "/empresa")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<java.util.Map<String, Object>> itens = JsonPath.read(resp, "$.itens");
        for (String problema : List.of("Empresa sem CNPJ.", "Empresa sem Inscrição Estadual.",
                "Empresa sem código de município IBGE.", "Empresa sem CNAE.")) {
            var item = itens.stream().filter(i -> problema.equals(i.get("problema"))).findFirst()
                    .orElseThrow(() -> new AssertionError("Pendência não encontrada: " + problema));
            org.assertj.core.api.Assertions.assertThat(item.get("telaCorrecao")).isEqualTo("identidade.empresa");
        }
    }

    @Test
    void empresaCompletaComCertificadoValidoESemProdutosEhVerde() throws Exception {
        String token = assinarNovoTenant("completa");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "11222333000181";

        configurarEmpresaCompleta(token, idTenant, idEmpresa, cnpj);
        enviarCertificadoValido(token, idEmpresa, cnpj, 365);

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronto").value(true))
                .andExpect(jsonPath("$.veredito").value("Pronto para emitir"));
    }

    @Test
    void certificadoVencidoAcusaEBloqueiaEmpresa() throws Exception {
        String token = assinarNovoTenant("cert-vencido");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "22333444000162";

        configurarEmpresaCompleta(token, idTenant, idEmpresa, cnpj);
        enviarCertificadoValido(token, idEmpresa, cnpj, -30); // já vencido

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronto").value(false))
                .andExpect(jsonPath("$.categorias[0].quantidadePendencias").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa + "/empresa").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[*].problema").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("certificado"))));
    }

    // ---------------------------------------------------------------- Produtos

    @Test
    void produtosAtivosSemNcmESemPerfilContamComoPendencia() throws Exception {
        String token = assinarNovoTenant("prod-pendente");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        configurarEmpresaCompleta(token, idTenant, idEmpresa, "33444555000143");
        enviarCertificadoValido(token, idEmpresa, "33444555000143", 365);

        criarProduto(token, "Produto Sem NCM", null);
        criarProduto(token, "Produto Sem NCM 2", null);

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronto").value(false))
                .andExpect(jsonPath("$.categorias[1].categoria").value("PRODUTOS"))
                .andExpect(jsonPath("$.categorias[1].quantidadePendencias").value(2));

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa + "/produtos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(2))
                .andExpect(jsonPath("$.itens[0].problema").value(org.hamcrest.Matchers.containsString("sem NCM")));
    }

    @Test
    void produtoInativoSemNcmNaoConta() throws Exception {
        String token = assinarNovoTenant("prod-inativo");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        configurarEmpresaCompleta(token, idTenant, idEmpresa, "44555666000124");
        enviarCertificadoValido(token, idEmpresa, "44555666000124", 365);

        long idProduto = criarProduto(token, "Produto Inativo", null);
        jdbc.sql("UPDATE produto SET ativo = false WHERE id_tenant = ? AND id_produto = ?")
                .params(idTenant, idProduto).update();

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronto").value(true));
    }

    @Test
    void produtoComPerfilSemRegraParaOCrtApareceNoDrillDown() throws Exception {
        String token = assinarNovoTenant("perfil-sem-regra");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        configurarEmpresaCompleta(token, idTenant, idEmpresa, "55666777000105");
        enviarCertificadoValido(token, idEmpresa, "55666777000105", 365);

        // Perfil existe, mas a única regra é para CRT 2 — a empresa é CRT 1 (configurarEmpresaCompleta).
        long idPerfil = jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal (id_tenant, nome) VALUES (?, 'PERFIL SEM REGRA PARA CRT 1')
                        RETURNING id_perfil_fiscal
                        """)
                .param(idTenant).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal_regra (id_tenant, id_perfil_fiscal, crt, tipo_destinatario,
                            tipo_operacao, cfop, cst_icms)
                        VALUES (?, ?, 2, 'CONSUMIDOR_FINAL', 'VENDA_CONSUMIDOR', '5102', '00')
                        """)
                .params(idTenant, idPerfil).update();

        jdbc.sql("INSERT INTO cfg_produto_ncm (codigo_ncm, descricao_ncm) VALUES ('61099000', 'NCM TESTE')").update();
        long idProduto = criarProduto(token, "Produto Com Perfil Torto", "61099000"); // com NCM, sem pendência de NCM
        jdbc.sql("UPDATE produto SET id_perfil_fiscal = ? WHERE id_tenant = ? AND id_produto = ?")
                .params(idPerfil, idTenant, idProduto).update();

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa + "/produtos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItens").value(1))
                .andExpect(jsonPath("$.itens[0].problema").value("perfil sem regra para o CRT"));
    }

    // ---------------------------------------------------------------- Pagamentos

    @Test
    void carteiraDeCreditoSemBandeiraContaComoPendenciaBloqueante() throws Exception {
        String token = assinarNovoTenant("carteira-sem-bandeira");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        configurarEmpresaCompleta(token, idTenant, idEmpresa, "66777888000186");
        enviarCertificadoValido(token, idEmpresa, "66777888000186", 365);

        // O signup já semeia 6 tipos de carteira padrão, todos sem codigo_tpag (campo ainda sem
        // tela) — todos contam. Cria mais um de crédito, também sem código, pra garantir >0.
        jdbc.sql("""
                        INSERT INTO tipo_carteira (id_tenant, nome_carteira, categoria_carteira,
                            prazo_pagamento, pc_minima, pc_maxima)
                        VALUES (?, 'CARTAO TESTE SEM BANDEIRA', 'CARTAO_CREDITO', 30, 1, 1)
                        """)
                .param(idTenant).update();

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronto").value(false))
                .andExpect(jsonPath("$.categorias[2].categoria").value("PAGAMENTOS"))
                .andExpect(jsonPath("$.categorias[2].quantidadePendencias").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    // ---------------------------------------------------------------- Clientes (aviso)

    @Test
    void clientePfSemIndicadorIeRevisadoNaoConta() throws Exception {
        String token = assinarNovoTenant("cliente-pf");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        configurarEmpresaCompleta(token, idTenant, idEmpresa, "77888999000167");
        enviarCertificadoValido(token, idEmpresa, "77888999000167", 365);
        long idCategoria = criarCategoriaCliente(token, "Padrão");
        criarClientePf(token, "Pessoa Fisica Teste", idCategoria);

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categorias[3].categoria").value("CLIENTES"))
                .andExpect(jsonPath("$.categorias[3].quantidadePendencias").value(0));
    }

    @Test
    void clientePjComIndicadorIeNaoRevisadoContaComoAvisoSemBloquear() throws Exception {
        String token = assinarNovoTenant("cliente-pj");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        configurarEmpresaCompleta(token, idTenant, idEmpresa, "88999000000148");
        enviarCertificadoValido(token, idEmpresa, "88999000000148", 365);
        long idCategoria = criarCategoriaCliente(token, "Atacado");
        criarClientePj(token, "Empresa Pj Teste", idCategoria);

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronto").value(true)) // aviso nunca bloqueia
                .andExpect(jsonPath("$.categorias[3].categoria").value("CLIENTES"))
                .andExpect(jsonPath("$.categorias[3].bloqueia").value(false))
                .andExpect(jsonPath("$.categorias[3].quantidadePendencias").value(1));
    }

    // ---------------------------------------------------------------- papéis, empresas e isolamento

    @Test
    void operadorNaoPodeConsultar() throws Exception {
        String token = assinarNovoTenant("operador-conf");
        String operador = comoOperador(token);
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa).header("Authorization", "Bearer " + operador))
                .andExpect(status().isForbidden());
    }

    @Test
    void duasEmpresasDoMesmoTenantTemVereditosIndependentes() throws Exception {
        String token = assinarNovoTenant("duas-empresas");
        long idTenant = idTenantDo(token);
        long idEmpresaA = idEmpresaDo(token);

        long idEmpresaB = jdbc.sql("""
                        INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, cfg_nome_etiqueta)
                        VALUES (?, 2, 'FILIAL SEM CONFIG', 'ETQ')
                        RETURNING id_empresa
                        """)
                .param(idTenant).query(Long.class).single();

        configurarEmpresaCompleta(token, idTenant, idEmpresaA, "99888777000160");
        enviarCertificadoValido(token, idEmpresaA, "99888777000160", 365);

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresaA).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronto").value(true));

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresaB).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pronto").value(false));
    }

    @Test
    void isolamentoEntreTenants() throws Exception {
        String tokenA = assinarNovoTenant("iso-conf-a");
        String tokenB = assinarNovoTenant("iso-conf-b");
        long idEmpresaA = idEmpresaDo(tokenA);

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresaA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoriaInvalidaNaUrlRespondeBadRequest() throws Exception {
        String token = assinarNovoTenant("categoria-invalida");
        long idEmpresa = idEmpresaDo(token);

        mvc.perform(get("/api/v1/fiscal/conformidade/" + idEmpresa + "/inexistente").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
