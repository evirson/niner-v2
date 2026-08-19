package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Emissão de NFC-e disparada por uma venda real do PDV (§9.6, bloco B7) — a ponta a ponta que
 * amarra tudo: venda → {@link com.vetor.niner.fiscal.documento.VendaFiscalAssembler} (lê venda,
 * resolve perfil fiscal, monta o pedido) → motor tributário → montagem/assinatura de XML real →
 * {@code documento_fiscal} persistido.
 *
 * <p>{@link SefazTransporte} é substituído por {@code @MockitoBean}: a viagem até a SEFAZ real já
 * foi provada no B0 (PoC manual) e é coberta por {@code SefazTransporteTest} (mTLS de verdade
 * contra servidor HTTPS local). Aqui o que importa é que <b>tudo antes e depois</b> da chamada de
 * rede — leitura da venda, resolução de perfil fiscal, cálculo, montagem, assinatura real com
 * certificado real, e a gravação do desfecho — funciona com dados de verdade.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class VendaFiscalEmissaoTest {

    private static final String SENHA_CERTIFICADO = "senha-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    SefazTransporte transporte;

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- fixture: tenant/empresa

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Fiscal Pdv %s","email":"dono%s@lojafiscalpdv.com",
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

    /** Dados mínimos de emitente que o assembler exige (§6.1): CNPJ, UF e município IBGE. */
    private void completarDadosDaEmpresa(long idTenant, long idEmpresa, String cnpj) {
        jdbc.sql("""
                        UPDATE empresa SET cnpj = ?, inscricao_estadual = '1234567890',
                            codigo_municipio_ibge = 4106902, cidade = 'CURITIBA', estado = 'PR',
                            endereco = 'RUA TESTE', numero = '100', bairro = 'CENTRO', cep = '80000000',
                            cnae = '4781400'
                        WHERE id_tenant = ? AND id_empresa = ?
                        """)
                .params(cnpj, idTenant, idEmpresa).update();
    }

    private void ligarFiscal(String token, long idEmpresa) throws Exception {
        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crt":1,"emiteNfce":true,"emiteNfe":false,
                                 "ambiente":"HOMOLOGACAO","serieNfce":1,"serieNfe":1,"serieContingencia":9,
                                 "cscId":"000001","cscToken":"csc-fake-de-teste"}
                                """))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- fixture: certificado (.pfx real)

    private byte[] gerarPfx(String cnpj) throws Exception {
        Path arquivo = tempDir.resolve("cert-" + cnpj + ".pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process processo = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "venda-fiscal-" + cnpj, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=EMPRESA VENDA FISCAL LTDA:" + cnpj + ", O=TESTE, C=BR",
                "-keystore", arquivo.toString(), "-storetype", "PKCS12",
                "-storepass", SENHA_CERTIFICADO, "-keypass", SENHA_CERTIFICADO)
                .redirectErrorStream(true).start();
        String saida = new String(processo.getInputStream().readAllBytes());
        if (processo.waitFor() != 0) {
            throw new IllegalStateException("keytool falhou: " + saida);
        }
        return Files.readAllBytes(arquivo);
    }

    private void enviarCertificado(String token, long idEmpresa, String cnpj) throws Exception {
        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", gerarPfx(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("senha", SENHA_CERTIFICADO)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- fixture: perfil fiscal (CSOSN 102)

    /** Mesma regra do "caso normal do produto" (DF37): CRT 1, CSOSN 102, PIS/COFINS CST 99 zerado. */
    private long criarPerfilFiscalCsosn102(long idTenant) {
        long idPerfil = jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal (id_tenant, nome) VALUES (?, 'PERFIL VENDA FISCAL PDV')
                        RETURNING id_perfil_fiscal
                        """)
                .param(idTenant).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal_regra
                            (id_tenant, id_perfil_fiscal, crt, uf_destino, tipo_destinatario, tipo_operacao,
                             cfop, csosn, cst_pis, cst_cofins)
                        VALUES (?, ?, 1, '*', 'CONSUMIDOR_FINAL', 'VENDA_CONSUMIDOR', '5102', '102', '99', '99')
                        """)
                .params(idTenant, idPerfil).update();
        return idPerfil;
    }

    // ---------------------------------------------------------------- fixture: produto/variação

    private long criarProdutoComPerfil(String token, long idTenant, String descricao, long idPerfil,
                                       String precoVenda) throws Exception {
        jdbc.sql("INSERT INTO cfg_produto_ncm (codigo_ncm, descricao_ncm) VALUES ('61091000', 'NCM TESTE') "
                        + "ON CONFLICT DO NOTHING")
                .update();
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"100",
                                 "precoVenda":"%s","codigoNcm":"61091000"}
                                """.formatted(descricao, precoVenda)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idProduto = ((Number) JsonPath.read(resp, "$.idProduto")).longValue();

        jdbc.sql("UPDATE produto SET id_perfil_fiscal = ? WHERE id_tenant = ? AND id_produto = ?")
                .params(idPerfil, idTenant, idProduto).update();

        return jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, sku)
                        VALUES (?, ?, gerar_ean13_interno())
                        RETURNING id_variacao
                        """)
                .params(idTenant, idProduto).query(Long.class).single();
    }

    // ---------------------------------------------------------------- fixture: cliente/funcionário/caixa/carteira

    private long criarClienteAnonimo(String token, String nome) throws Exception {
        long idCategoria = criarCategoriaCliente(token, "PADRAO " + nome);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"fisicaJuridica\":false,\"nome\":\"%s\",\"idCategoriaCliente\":%d}"
                                .formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    /** Cliente com CPF válido e {@code indicador_ie=1} (contribuinte) — DF13 recusa NFC-e para ele. */
    private long criarClienteContribuinte(String token, long idTenant, String nome) throws Exception {
        long idCategoria = criarCategoriaCliente(token, "PADRAO " + nome);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":true,"nome":"%s","idCategoriaCliente":%d,
                                 "genero":"OUTROS","cpfCnpj":"11144477735"}
                                """.formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCliente = ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
        jdbc.sql("UPDATE cliente SET indicador_ie = 1 WHERE id_tenant = ? AND id_cliente = ?")
                .params(idTenant, idCliente).update();
        return idCliente;
    }

    private long criarCategoriaCliente(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCategoriaCliente")).longValue();
    }

    private long criarFuncionario(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    private long carteiraDinheiroComTpag(String token, long idTenant) throws Exception {
        String resp = mvc.perform(get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = carteiras.stream()
                .filter(c -> "DINHEIRO".equals(c.get("nomeCarteira")))
                .map(c -> ((Number) c.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
        // codigo_tpag ainda não tem tela própria (2026-08-16) — grava direto, mesmo padrão de
        // ConformidadeFiscalCrudTest.configurarEmpresaCompleta.
        jdbc.sql("UPDATE tipo_carteira SET codigo_tpag = '01' WHERE id_tenant = ? AND id_carteira = ?")
                .params(idTenant, idCarteira).update();
        return idCarteira;
    }

    private void abrirCaixa(String token, long idCarteira) throws Exception {
        mvc.perform(post("/api/v1/caixa/abrir").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"idCarteira\":%d,\"saldoInicial\":100.00}".formatted(idCarteira)))
                .andExpect(status().isOk());
    }

    /** Efetiva uma venda de 1 item via o endpoint real do PDV — devolve o {@code idVenda}. */
    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                               long idCarteira, String valor) throws Exception {
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,
                                 "idCliente":%d,"idFuncionario":%d,
                                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                                """.formatted(idVariacao, idCliente, idFuncionario, idCarteira, valor)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
    }

    private static RespostaSefaz autorizada(String chave) {
        return new RespostaSefaz(200, "100", "Autorizado o uso da NF-e", "141260001999999", chave,
                "<retEnviNFe><protNFe><infProt><cStat>100</cStat></infProt></protNFe></retEnviNFe>");
    }

    // ---------------------------------------------------------------- casos

    @Test
    void vendaAutorizadaGravaDocumentoFiscalComProtocolo() throws Exception {
        String token = assinarNovoTenant("autorizada");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "11222333000181";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO VENDA FISCAL", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Balcao");
        long idFuncionario = criarFuncionario(token, "Vendedor Fiscal");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);

        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));

        var resultado = mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andReturn();
        String resp = resultado.getResponse().getContentAsString();
        assertThat(resultado.getResponse().getStatus()).as("corpo: " + resp).isEqualTo(200);
        org.junit.jupiter.api.Assertions.assertEquals("AUTORIZADO", JsonPath.read(resp, "$.situacao"));
        assertThat((String) JsonPath.read(resp, "$.chaveAcesso")).hasSize(44);
        org.junit.jupiter.api.Assertions.assertEquals("141260001999999", JsonPath.read(resp, "$.protocolo"));
        String chave = JsonPath.read(resp, "$.chaveAcesso");

        DocumentoGravado gravado = jdbc.sql("""
                        SELECT situacao, protocolo, xml_assinado, xml_hash, modelo, tipo_emissao
                          FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?
                        """)
                .params(idTenant, idVenda)
                .query((rs, n) -> new DocumentoGravado(rs.getString("situacao"), rs.getString("protocolo"),
                        rs.getString("xml_assinado") != null, rs.getString("xml_hash") != null,
                        rs.getInt("modelo"), rs.getInt("tipo_emissao")))
                .single();

        assertThat(gravado.situacao()).isEqualTo("AUTORIZADO");
        assertThat(gravado.protocolo()).isEqualTo("141260001999999");
        assertThat(gravado.xmlPresente()).isTrue();
        assertThat(gravado.hashPresente()).isTrue();
        assertThat(gravado.modelo()).isEqualTo(65);
        assertThat(gravado.tipoEmissao()).isEqualTo(1);
        assertThat(chave).hasSize(44).containsOnlyDigits();

        // ⚠️ Gap corrigido em 2026-08-19: `documento_fiscal_item` existia desde a V035 e NUNCA
        // recebia INSERT — o ambiente de dev tinha 27 notas autorizadas e zero itens. O dado
        // estava só dentro do `xml_assinado`, inacessível a qualquer consulta. Descoberto ao
        // construir a NF-e de devolução (B9), que precisa espelhar a tributação item a item.
        var item = jdbc.sql("""
                        SELECT i.numero_item, i.id_variacao, i.codigo_produto, i.descricao, i.cfop,
                               i.csosn, i.quantidade, i.valor_produto, i.cst_pis, i.cst_cofins
                          FROM documento_fiscal_item i
                          JOIN documento_fiscal d ON d.id_documento_fiscal = i.id_documento_fiscal
                                                 AND d.id_tenant = i.id_tenant
                         WHERE i.id_tenant = ? AND d.id_venda = ?
                        """)
                .params(idTenant, idVenda)
                .query((rs, n) -> new ItemFiscalGravado(rs.getInt("numero_item"), rs.getLong("id_variacao"),
                        rs.getString("codigo_produto"), rs.getString("descricao"), rs.getString("cfop"),
                        rs.getString("csosn"), rs.getBigDecimal("quantidade"), rs.getBigDecimal("valor_produto"),
                        rs.getString("cst_pis"), rs.getString("cst_cofins")))
                .single();

        assertThat(item.numeroItem()).isEqualTo(1);
        assertThat(item.idVariacao()).as("FK resolvida pelo SKU — é por ela que a devolução casa o item")
                .isEqualTo(idVariacao);
        assertThat(item.descricao()).isEqualTo("PRODUTO VENDA FISCAL");
        assertThat(item.csosn()).isEqualTo("102");
        assertThat(item.cfop()).isEqualTo("5102");
        assertThat(item.valorProduto()).isEqualByComparingTo("30.00");
        assertThat(item.cstPis()).isEqualTo("99");
        assertThat(item.cstCofins()).isEqualTo("99");
    }

    private record ItemFiscalGravado(int numeroItem, long idVariacao, String codigoProduto, String descricao,
                                      String cfop, String csosn, java.math.BigDecimal quantidade,
                                      java.math.BigDecimal valorProduto, String cstPis, String cstCofins) {
    }

    /**
     * Lei 12.741/2012 (§8.6, 2026-08-19) ponta a ponta: NCM com alíquota IBPT real cadastrada em
     * {@code cfg_produto_ncm} + produto com {@code origem_mercadoria} IMPORTADO (1) resolve
     * {@code vTotTrib} de verdade — não mais hardcoded em zero — e o valor aparece gravado em
     * {@code documento_fiscal.valor_total_tributos} E no XML assinado (com o {@code orig} certo).
     */
    @Test
    void vendaComAliquotaIbptResolvidaCalculaVTotTribRealEGravaComOOrigemCorreto() throws Exception {
        String token = assinarNovoTenant("ibpt");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "44555666000124";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO COM IBPT", idPerfil, "100.00");

        // criarProdutoComPerfil cria o NCM 61091000 sem alíquota (ON CONFLICT DO NOTHING) —
        // completa aqui com uma alíquota IBPT real de fixture, e marca o produto como IMPORTADO
        // (origem 1) para exercitar a escolha Nacional × Importado da fórmula.
        jdbc.sql("""
                        UPDATE cfg_produto_ncm
                           SET alq_federal_nacional = 8.00, alq_federal_importado = 20.00,
                               alq_estadual = 18.00, alq_municipal = 0.00
                         WHERE codigo_ncm = '61091000'
                        """)
                .update();
        jdbc.sql("UPDATE produto SET origem_mercadoria = 1 WHERE id_tenant = ? AND descricao = ?")
                .params(idTenant, "PRODUTO COM IBPT").update();

        long idCliente = criarClienteAnonimo(token, "Cliente Ibpt");
        long idFuncionario = criarFuncionario(token, "Vendedor Ibpt");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "100.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));

        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("AUTORIZADO"));

        // Importado (orig 1): federal 20,00 + estadual 18,00 + municipal 0,00 = 38,00% sobre a
        // base de 100,00 = 38,00.
        var gravado = jdbc.sql("""
                        SELECT valor_total_tributos, xml_assinado FROM documento_fiscal
                         WHERE id_tenant = ? AND id_venda = ?
                        """)
                .params(idTenant, idVenda)
                .query((rs, n) -> Map.of("valor", rs.getBigDecimal("valor_total_tributos"),
                        "xml", rs.getString("xml_assinado")))
                .single();

        assertThat((BigDecimal) gravado.get("valor")).isEqualByComparingTo("38.00");
        assertThat((String) gravado.get("xml"))
                .contains("<vTotTrib>38.00</vTotTrib>")
                .contains("<orig>1</orig>");

        // A papeleta (DANFE, §3-4 do estudo) precisa do mesmo dado pronto pra impressão: número
        // oficial da NFC-e (não o id interno da venda), série, e "consumidor não identificado"
        // porque esta venda emitiu com incluirCpf=false.
        mvc.perform(get("/api/v1/pdv/vendas/" + idVenda + "/comprovante").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dadosFiscais.numero").value(1))
                .andExpect(jsonPath("$.dadosFiscais.serie").value(1))
                .andExpect(jsonPath("$.dadosFiscais.documentoConsumidor").doesNotExist())
                .andExpect(jsonPath("$.dadosFiscais.valorTotalTributos").value(38.00));
    }

    /** F12: sem fiscal ligado, o endpoint não devolve corpo e nada é gravado — como se o módulo
     *  fiscal não existisse. A venda em si (já efetivada antes deste passo) não é afetada. */
    @Test
    void fiscalDesligadoNaoGravaNadaEDevolve204() throws Exception {
        String token = assinarNovoTenant("desligado");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        completarDadosDaEmpresa(idTenant, idEmpresa, "22333444000162");
        // NÃO chama ligarFiscal — fiscal_config_empresa nem existe (F12: ausência = desligado).

        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO SEM FISCAL", idPerfil, "10.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Sem Fiscal");
        long idFuncionario = criarFuncionario(token, "Vendedor Sem Fiscal");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "10.00");

        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isNoContent());

        Mockito.verifyNoInteractions(transporte);

        int total = jdbc.sql("SELECT count(*) FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Integer.class).single();
        assertThat(total).as("fiscal desligado não cria documento nenhum").isZero();
    }

    /**
     * DF13: cliente contribuinte de ICMS não pode receber NFC-e. A venda continua registrada
     * (F3) — só o documento fiscal fica {@code NAO_EMITIDO}, sem consumir número (F4).
     */
    @Test
    void vendaAContribuinteFicaNaoEmitidaMasVendaContinuaRegistrada() throws Exception {
        String token = assinarNovoTenant("contribuinte");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "33444555000143";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO PARA CONTRIBUINTE", idPerfil, "50.00");
        long idCliente = criarClienteContribuinte(token, idTenant, "Empresa Contribuinte");
        long idFuncionario = criarFuncionario(token, "Vendedor Contribuinte");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);

        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "50.00");

        // incluirCpf=true de propósito: é a inclusão do CPF/CNPJ do cliente (contribuinte) que
        // faz o destinatário chegar preenchido no motor e disparar a recusa DF13 abaixo.
        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("NAO_EMITIDO"))
                .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("contribuinte")));

        Mockito.verifyNoInteractions(transporte);

        Boolean cancelada = jdbc.sql("SELECT cancelada FROM venda WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Boolean.class).single();
        assertThat(cancelada).as("a venda não é cancelada nem revertida por causa disto").isFalse();
    }

    /** Extrai o {@code <NFe>...</NFe>} de dentro do {@code enviNFe} para devolver como chave da
     *  resposta simulada — o mock precisa "ecoar" a chave que o produto real gerou, senão a
     *  gravação do desfecho compararia com uma chave que não bate com nada. */
    private static String extrairChaveDoEnvi(String enviNFe) {
        int inicio = enviNFe.indexOf("Id=\"NFe") + "Id=\"NFe".length();
        int fim = enviNFe.indexOf('"', inicio);
        return enviNFe.substring(inicio, fim);
    }

    private record DocumentoGravado(String situacao, String protocolo, boolean xmlPresente,
                                    boolean hashPresente, int modelo, int tipoEmissao) {
    }
}
