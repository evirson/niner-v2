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

    /**
     * Espião do validador de schema, usado por um teste só
     * ({@link #falhaDepoisDeReservarONumeroDeixaRastroComONumeroQueimado}).
     *
     * <p>⚠️ {@code @MockitoSpyBean}, não {@code @MockitoBean}: os outros nove testes desta classe
     * precisam da validação <b>de verdade</b> — trocá-la por um mock mudo desligaria o F11 na
     * suíte inteira para provar um caso, que é o oposto de um teste.
     */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.vetor.niner.fiscal.documento.ValidadorXsd validador;

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
                                 "cscId":"000001","cscToken":"csc-fake-de-teste-0123456789abcdef01"}
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
        // Regra da venda a CONTRIBUINTE (2026-08-24): o CFOP vem da regra, e ate esta data a busca
        // usava CONSUMIDOR_FINAL literal — toda venda pegava o CFOP da operacao errada. 5405 e a
        // revenda com ST, que e o caso de quem compra para revender.
        jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal_regra
                            (id_tenant, id_perfil_fiscal, crt, uf_destino, tipo_destinatario, tipo_operacao,
                             cfop, cfop_interestadual, csosn, cst_pis, cst_cofins)
                        VALUES (?, ?, 1, '*', 'CONTRIBUINTE', 'VENDA_CONTRIBUINTE', '5405', '6404', '500', '99', '99')
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

        long idVariacao = jdbc.sql("""
                        INSERT INTO produto_barra (id_tenant, id_produto, sku)
                        VALUES (?, ?, gerar_ean13_interno())
                        RETURNING id_variacao
                        """)
                .params(idTenant, idProduto).query(Long.class).single();
        abastecerEstoqueDoFixture(idTenant, idVariacao);
        return idVariacao;
    }

    /**
     * Dá estoque à variação recém-criada, em todas as empresas do tenant.
     *
     * <p>Desde a V054 o débito não pode deixar o saldo negativo quando
     * {@code cfg_permite_estoque_negativo} está desligado — que é o padrão. Este teste não é sobre
     * estoque: ele precisa de uma venda como <b>fixture</b>, e uma venda de verdade tem estoque
     * antes. Abastecer aqui é mais honesto que ligar o parâmetro e fingir que a regra não existe.
     */
    private void abastecerEstoqueDoFixture(long idTenant, long idVariacao) {
        jdbc.sql("""
                        INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque)
                        SELECT ?, e.id_empresa, ?, 1000 FROM empresa e WHERE e.id_tenant = ?
                        ON CONFLICT (id_tenant, id_empresa, id_variacao)
                        DO UPDATE SET qtd_estoque = produto_estoque.qtd_estoque + 1000
                        """)
                .params(idTenant, idVariacao, idTenant).update();
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
    /**
     * Cliente <b>pessoa jurídica</b> contribuinte de ICMS — o caso que sai em NF-e 55.
     *
     * <p>⚠️ {@code fisicaJuridica:false} é o que faz dele PJ: a coluna vale {@code true} para
     * pessoa FÍSICA (é ela que exige gênero e CPF de 11 dígitos). Por isso o documento aqui é
     * CNPJ, não CPF.
     */
    private long criarClienteContribuinte(String token, long idTenant, String nome) throws Exception {
        long idCategoria = criarCategoriaCliente(token, "PADRAO " + nome);
        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":false,"nome":"%s","idCategoriaCliente":%d,
                                 "cpfCnpj":"11222333000181"}
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
                .contains("<orig>1</orig>")
                // ⭐ O caso POSITIVO da Lei 12.741/2012 (2026-09-01): com tributos apurados, o
                // valor aproximado entra no `infCpl` — ou seja, no XML autorizado, não só no
                // desenho do DANFE. Antes ele era escrito pelo React na hora de imprimir: a
                // informação que a lei exige NO DOCUMENTO existia no papel e não no documento.
                .contains("Valor aproximado dos tributos: R$ 38,00")
                .contains("Lei 12.741/2012");

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
    void vendaAPessoaJuridicaSemNfeLigadaFicaNaoEmitidaMasVendaContinuaRegistrada() throws Exception {
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
                // A mensagem tem que dizer o motivo REAL: desde 2026-08-24 o gatilho e ser pessoa
                // juridica, nao ser contribuinte — e a mensagem antiga falava em contribuinte para
                // um cliente que podia estar declarado como nao contribuinte.
                .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("pessoa jurídica")));

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

    /** Liga também a NF-e 55 — o {@link #ligarFiscal} padrão deixa {@code emiteNfe:false}, que é o
     *  caso da maioria das lojas. Série 7 de propósito: prova que a 55 usa a série PRÓPRIA. */
    private void ligarFiscalComNfe(String token, long idEmpresa) throws Exception {
        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crt":1,"emiteNfce":true,"emiteNfe":true,
                                 "ambiente":"HOMOLOGACAO","serieNfce":1,"serieNfe":7,"serieContingencia":9,
                                 "cscId":"000001","cscToken":"csc-fake-de-teste-0123456789abcdef01"}
                                """))
                .andExpect(status().isOk());
    }

    /** Completa o que a NF-e 55 exige e a NFC-e não: endereço e inscrição estadual. */
    private void completarCadastroParaNfe(long idTenant, long idCliente) {
        jdbc.sql("""
                        UPDATE cliente SET rg_ie = '1234567890', endereco = 'RUA DAS FLORES',
                               numero = '100', bairro = 'CENTRO', cidade = 'CURITIBA', estado = 'PR',
                               cep = '80010000', codigo_municipio_ibge = 4106902, telefone = '4133334444'
                         WHERE id_tenant = ? AND id_cliente = ?
                        """)
                .params(idTenant, idCliente).update();
    }

    /**
     * Venda a contribuinte de ICMS sai em <b>NF-e 55</b>, não em NFC-e (2026-08-24).
     *
     * <p>⚠️ Confere o <b>XML gravado</b>, não só a situação: o que prova que saiu a nota certa é o
     * {@code mod} 55, a série própria, o {@code enderDest} (que a NFC-e nem tem), a IE do
     * destinatário e a <b>ausência</b> do QR Code — o grupo {@code infNFeSupl} não existe no XSD do
     * 55, então incluí-lo seria rejeição de schema. Um teste que olhasse só o status passaria com
     * uma NFC-e disfarçada de 55.
     */
    @Test
    void vendaAContribuinteSaiEmNfe55ComEnderecoESemQrCode() throws Exception {
        String token = assinarNovoTenant("nfe-contrib");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "33444555000143";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscalComNfe(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO PARA REVENDA", idPerfil, "80.00");
        long idCliente = criarClienteContribuinte(token, idTenant, "Revendedora Ltda");
        completarCadastroParaNfe(idTenant, idCliente);
        long idFuncionario = criarFuncionario(token, "Vendedor NFe");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);

        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "80.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));

        var resultado = mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":true}"))
                .andReturn();
        String resp = resultado.getResponse().getContentAsString();
        assertThat(resultado.getResponse().getStatus()).as("corpo: " + resp).isEqualTo(200);
        org.junit.jupiter.api.Assertions.assertEquals("AUTORIZADO", JsonPath.read(resp, "$.situacao"));
        // ⚠️ O modelo tem que chegar no JSON: e por ele que o PDV decide imprimir DANFE A4 em vez
        // do DANFCE termico. Sem isso a nota sairia certa e o caixa imprimiria o documento errado.
        assertThat(((Number) JsonPath.read(resp, "$.modelo")).intValue()).isEqualTo(55);

        DocumentoNfe doc = jdbc.sql("""
                        SELECT modelo, serie, xml_assinado FROM documento_fiscal
                         WHERE id_tenant = ? AND id_venda = ?
                        """)
                .params(idTenant, idVenda)
                .query((rs, n) -> new DocumentoNfe(rs.getInt("modelo"), rs.getInt("serie"),
                        rs.getString("xml_assinado")))
                .single();

        assertThat(doc.modelo()).as("venda a contribuinte tem que sair em NF-e, não NFC-e").isEqualTo(55);
        assertThat(doc.serie()).as("a NF-e usa a série PRÓPRIA (serie_nfe), não a da NFC-e").isEqualTo(7);

        // ⚠️ O MODELO faz parte da CHAVE DE ACESSO (posições 21-22), e o dígito verificador é
        // calculado sobre ela inteira. Com o modelo fixo em 65 na montagem da chave, a nota saía
        // com a chave dizendo 65 e o XML dizendo 55 — e a SEFAZ devolvia cStat 253, "Dígito
        // Verificador da chave de acesso composta inválida". Só apareceu na PRIMEIRA transmissão
        // real (2026-08-24): com transporte simulado o DV bate, porque nós geramos os dois lados.
        String chaveDoBanco = jdbc.sql("SELECT chave_acesso FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(String.class).single();
        assertThat(chaveDoBanco).hasSize(44);
        assertThat(chaveDoBanco.substring(20, 22)).as("o modelo dentro da chave").isEqualTo("55");

        String xml = doc.xml();
        assertThat(xml).contains("<mod>55</mod>");
        assertThat(xml).as("endereço do destinatário é obrigatório na 55").contains("<enderDest>");
        assertThat(xml).contains("<xLgr>RUA DAS FLORES</xLgr>");
        assertThat(xml).as("IE do contribuinte").contains("<IE>1234567890</IE>");
        assertThat(xml).as("indIEDest 1 = contribuinte").contains("<indIEDest>1</indIEDest>");
        assertThat(xml).as("ele revende, não é consumidor final").contains("<indFinal>0</indFinal>");
        assertThat(xml).as("tpImp 1 = DANFE retrato").contains("<tpImp>1</tpImp>");
        assertThat(xml).as("mesma UF do emitente").contains("<idDest>1</idDest>");
        // ⚠️ O grupo do QR Code não existe no XSD da NF-e 55 — presença aqui é rejeição de schema.
        assertThat(xml).as("NF-e 55 não tem QR Code").doesNotContain("infNFeSupl");
        assertThat(xml).doesNotContain("qrCode");

        // ⭐⭐ A OUTRA PONTA: o DANFE tem de MOSTRAR o que o XML tem (relato dele, 2026-09-01).
        //
        // Eu passei a gravar o infCpl no XML e NÃO liguei a leitura — a consulta do DANFE
        // preenchia `informacoesComplementares` só com o texto de devolução, e o papel saía com um
        // travessão. Ele reportou TRÊS coisas ("a observação não sai", "os tributos não saem",
        // "tem que sair o número da venda") que eram UMA só, e os três dados estavam no XML o
        // tempo todo — medido no banco antes de eu tocar em qualquer código.
        //
        // ⚠️ Um teste que confira só o XML passa com o DANFE inteiro em branco. É a família de
        // feedback_corrigir_uma_ponta_sem_varrer_as_outras, e desta vez fui eu.
        long idDoc = jdbc.sql("SELECT id_documento_fiscal FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Long.class).single();
        String danfe = mvc.perform(get("/api/v1/fiscal/documentos/" + idDoc + "/danfe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(danfe, "$.informacoesComplementares"))
                .as("o que o operador vê no papel tem de ser o que a SEFAZ recebeu")
                .contains("CONTRATO: " + idVenda)
                .contains("VENDEDOR: ");
    }

    private record DocumentoNfe(int modelo, int serie, String xml) {
    }

    /**
     * PJ <b>não</b> contribuinte também sai em NF-e 55, mas <b>sem inscrição estadual</b>.
     *
     * <p>Desde 2026-08-24 o gatilho é ser pessoa jurídica, não ser contribuinte. Mas a IE continua
     * sendo exigida só de quem se declarou contribuinte ({@code indIEDest = 1}): para 2 (isento) e
     * 9 (não contribuinte) o <b>XSD não aceita</b> a tag {@code IE}, então mandá-la seria rejeição
     * de schema. É o caso do escritório que tem CNPJ e não tem inscrição estadual.
     */
    @Test
    void pessoaJuridicaNaoContribuinteSaiEmNfeSemInscricaoEstadual() throws Exception {
        String token = assinarNovoTenant("pj-nao-contrib");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "33444555000143";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscalComNfe(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO USO PROPRIO", idPerfil, "40.00");
        long idCliente = criarClienteContribuinte(token, idTenant, "Escritorio Ltda");
        completarCadastroParaNfe(idTenant, idCliente);
        // PJ com CNPJ e endereço completo, mas NÃO contribuinte — e sem IE nenhuma no cadastro.
        jdbc.sql("UPDATE cliente SET indicador_ie = 9, rg_ie = NULL WHERE id_tenant = ? AND id_cliente = ?")
                .params(idTenant, idCliente).update();
        long idFuncionario = criarFuncionario(token, "Vendedor PJ");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);

        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "40.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));

        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("AUTORIZADO"));

        DocumentoNfe doc = jdbc.sql("""
                        SELECT modelo, serie, xml_assinado FROM documento_fiscal
                         WHERE id_tenant = ? AND id_venda = ?
                        """)
                .params(idTenant, idVenda)
                .query((rs, n) -> new DocumentoNfe(rs.getInt("modelo"), rs.getInt("serie"),
                        rs.getString("xml_assinado")))
                .single();

        assertThat(doc.modelo()).as("toda PJ sai em NF-e desde 2026-08-24").isEqualTo(55);
        assertThat(doc.xml()).contains("<indIEDest>9</indIEDest>");
        // ⚠️ O XSD recusa a tag IE quando indIEDest != 1 — mandá-la seria rejeição de schema.
        // Olha a IE do DESTINATÁRIO, não a do emitente: o grupo <emit> tem <IE> sempre, e um
        // doesNotContain("<IE>") cru reprovaria a nota certa.
        String dest = doc.xml().substring(doc.xml().indexOf("<dest>"), doc.xml().indexOf("</dest>"));
        assertThat(dest).as("sem IE para quem não é contribuinte").doesNotContain("<IE>");
    }

    /**
     * Cadastro incompleto <b>não trava o caixa</b> — F3.
     *
     * <p>A NF-e 55 exige endereço, e o cliente contribuinte pode ter sido cadastrado só com CNPJ.
     * A venda é registrada, o documento fica em {@code NAO_EMITIDO} dizendo <b>qual campo falta</b>,
     * e nada é transmitido. A primeira versão desta funcionalidade respondia 409 aqui e travava o
     * fechamento da venda — foi este teste que pegou.
     */
    @Test
    void contribuinteComCadastroIncompletoNaoTravaAVendaEDizOQueFalta() throws Exception {
        String token = assinarNovoTenant("nfe-incompleto");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "33444555000143";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscalComNfe(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO SEM ENDERECO", idPerfil, "25.00");
        // Contribuinte, mas SEM endereço/IE — o cadastro que a NFC-e aceitava e a NF-e não aceita.
        long idCliente = criarClienteContribuinte(token, idTenant, "Contribuinte Sem Endereco");
        long idFuncionario = criarFuncionario(token, "Vendedor Incompleto");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);

        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "25.00");

        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("NAO_EMITIDO"))
                .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("endereço")))
                .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("inscrição estadual")));

        Mockito.verifyNoInteractions(transporte);

        Boolean cancelada = jdbc.sql("SELECT cancelada FROM venda WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Boolean.class).single();
        assertThat(cancelada).as("F3: a venda continua registrada").isFalse();
    }

    /**
     * Venda para OUTRO estado usa o CFOP interestadual da regra — nunca o interno (2026-08-24).
     *
     * <p><b>O caso que a SEFAZ recusou de verdade.</b> Uma venda do PR para um contribuinte de SP
     * saía com {@code idDest 2} (correto) e CFOP {@code 5405} (interno), e voltou
     * <b>cStat 733</b> — "CFOP de operação interna e idDest difere de 1". O CFOP vem da regra
     * fiscal, e a regra tinha um CFOP só.
     *
     * <p>⚠️ <b>Por que a regra carrega os dois CFOPs em vez de o sistema derivar um do outro:</b>
     * trocar o 5 pelo 6 mantendo o sufixo funciona para os CFOPs comuns (5102 → 6102), mas não é
     * regra geral — {@code 5405} não vira {@code 6405}, que <b>não existe</b> na tabela oficial
     * (o correspondente é {@code 6404}). Derivar às cegas emitiria nota com CFOP inválido ou, pior,
     * com um CFOP válido que descreve outra operação.
     */
    @Test
    void vendaParaOutroEstadoUsaOCfopInterestadualDaRegra() throws Exception {
        String token = assinarNovoTenant("nfe-interestadual");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "33444555000143";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);   // empresa no PR
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscalComNfe(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO INTERESTADUAL", idPerfil, "70.00");
        long idCliente = criarClienteContribuinte(token, idTenant, "Revendedora Paulista");
        completarCadastroParaNfe(idTenant, idCliente);
        // O que muda tudo: destinatário em SP, enquanto a empresa emitente é do PR.
        jdbc.sql("""
                        UPDATE cliente SET estado = 'SP', cidade = 'EMBU DAS ARTES',
                               codigo_municipio_ibge = 3515004
                         WHERE id_tenant = ? AND id_cliente = ?
                        """)
                .params(idTenant, idCliente).update();
        long idFuncionario = criarFuncionario(token, "Vendedor Interestadual");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);

        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "70.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));

        var resultado = mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":true}"))
                .andReturn();
        String resp = resultado.getResponse().getContentAsString();
        assertThat(resultado.getResponse().getStatus()).as("corpo: " + resp).isEqualTo(200);

        String xml = jdbc.sql("SELECT xml_assinado FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(String.class).single();

        // As duas coisas que a SEFAZ cruza: idDest 2 (outra UF) e CFOP 6xxx (interestadual).
        assertThat(xml).as("outra UF").contains("<idDest>2</idDest>");
        assertThat(xml).as("CFOP interestadual da regra").contains("<CFOP>6404</CFOP>");
        assertThat(xml).as("o CFOP interno não pode aparecer").doesNotContain("<CFOP>5405</CFOP>");
        assertThat(xml).contains("<UF>SP</UF>");
    }

    /**
     * Sem CFOP interestadual cadastrado, a venda para fora do estado é recusada <b>com o motivo</b>
     * — nunca com um CFOP derivado (F11).
     */
    @Test
    void vendaParaOutroEstadoSemCfopInterestadualDizOQueFalta() throws Exception {
        String token = assinarNovoTenant("nfe-sem-cfop-inter");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "33444555000143";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscalComNfe(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        // Tira o CFOP interestadual que o fixture cadastra — simula a regra que só cobre o estado.
        jdbc.sql("UPDATE cfg_perfil_fiscal_regra SET cfop_interestadual = NULL WHERE id_tenant = ? AND id_perfil_fiscal = ?")
                .params(idTenant, idPerfil).update();
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO SEM CFOP FORA", idPerfil, "70.00");
        long idCliente = criarClienteContribuinte(token, idTenant, "Cliente De Fora");
        completarCadastroParaNfe(idTenant, idCliente);
        jdbc.sql("UPDATE cliente SET estado = 'SP', codigo_municipio_ibge = 3515004 WHERE id_tenant = ? AND id_cliente = ?")
                .params(idTenant, idCliente).update();
        long idFuncionario = criarFuncionario(token, "Vendedor Sem Cfop");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);

        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "70.00");

        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("CFOP interestadual")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("SP")));

        Mockito.verifyNoInteractions(transporte);
    }
    /**
     * ⭐ <b>Duplo clique não emite a segunda nota.</b> Nada impedia isso até a V082: o índice de
     * {@code id_venda} era ÍNDICE, não UNIQUE, e cada chamada sorteava {@code cNF} novo e reservava
     * número novo — a segunda nota passava limpa por toda restrição existente.
     *
     * <p>⚠️ <b>Não precisa de má-fé:</b> duplo clique no PDV, retry de rede do front ou uma
     * reimpressão produzem a mesma sequência. Duas NFC-e autorizadas para uma venda são receita e
     * ICMS declarados em dobro, e só se desfazem cancelando na SEFAZ dentro de 30 minutos.
     *
     * <p>Decisão do dono do produto (2026-08-27): <b>recusar nomeando a nota que já existe</b>, em
     * vez de devolver a nota anterior em silêncio.
     */
    @Test
    void segundaEmissaoDaMesmaVendaEhRecusadaSemQueimarNumero() throws Exception {
        String token = assinarNovoTenant("duplo-clique");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "11222333000181";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO DUPLO CLIQUE", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Balcao");
        long idFuncionario = criarFuncionario(token, "Vendedor Fiscal");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));

        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isOk());

        int proximoAntes = proximoNumero(idTenant, idEmpresa);

        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isConflict())
                // ⚠️ "nota fiscal", não "NFC-e" (auditoria 2026-08-29, rodada 4): a trava passou a
                // valer para QUALQUER modelo, porque este mesmo endpoint emite NF-e 55 quando o
                // cliente é PJ e a decisão do modelo acontece DEPOIS dela. Prender o texto antigo
                // prenderia a versão que deixava a venda a PJ passar direto e queimar número.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("já tem a nota fiscal")));

        // ⚠️ Conferir o BANCO, não só o status: um teste que valida 409 passaria mesmo se a recusa
        // viesse do índice único DEPOIS de reservar número — e número queimado vira buraco na
        // numeração, que vira inutilização formal perante a SEFAZ.
        assertThat(proximoNumero(idTenant, idEmpresa))
                .as("a recusa não pode consumir número da sequência")
                .isEqualTo(proximoAntes);

        long notas = jdbc.sql("SELECT count(*) FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Long.class).single();
        assertThat(notas).as("uma venda, uma nota").isEqualTo(1L);
    }

    /**
     * ⭐ Falha <b>depois</b> de o número estar reservado deixa RASTRO — pendência #71.
     *
     * <p>A numeração é reservada antes de montar o XML (tem de ser: o número entra na chave de
     * acesso), e entre a reserva e a gravação correm três passos que podem lançar — montar,
     * assinar e validar contra o schema. Antes de 2026-09-01, se qualquer um falhasse, o número
     * ficava consumido na sequência e <b>não existia linha nenhuma</b> em {@code documento_fiscal}:
     * um buraco silencioso, que só reaparece meses depois como pendência de inutilização perante a
     * SEFAZ, sem ninguém saber o que houve ali.
     *
     * <p>⚠️ <b>A asserção que importa é a do BANCO.</b> Um teste que conferisse só o erro HTTP
     * passaria com o defeito inteiro presente — o operador vê o erro dos dois jeitos; o que muda é
     * o que fica registrado. E ela confere as duas coisas: que a linha existe <b>e</b> que ela
     * carrega o número, porque {@code gravarNaoEmitido} (que já existia) grava série e número
     * <b>nulos</b> e não resolveria nada aqui.
     *
     * <p>⚠️ O item #71 apontava só a devolução ao fornecedor. Conferindo o código, a emissão da
     * <b>venda</b> tinha o mesmo defeito — o que já estava resolvido lá era a recusa por
     * duplicidade, que acontece antes de reservar. É por isso que este teste está aqui.
     */
    @Test
    void falhaDepoisDeReservarONumeroDeixaRastroComONumeroQueimado() throws Exception {
        String token = assinarNovoTenant("queimado");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "11222333000181";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO NUMERO QUEIMADO", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Queimado");
        long idFuncionario = criarFuncionario(token, "Vendedor Queimado");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        // ⚠️ A linha de fiscal_numeracao só nasce na PRIMEIRA reserva — antes disso a consulta
        // devolve zero linhas. Nesta empresa recém-criada, o primeiro número é o 1.
        int numeroQueEsperoQueimar = jdbc.sql("""
                        SELECT proximo_numero FROM fiscal_numeracao
                         WHERE id_tenant = ? AND id_empresa = ? AND modelo = 65
                        """)
                .params(idTenant, idEmpresa).query(Integer.class).optional().orElse(1);

        // Sabota o passo de validação — é o último dos três que rodam com o número já reservado.
        Mockito.doThrow(new IllegalStateException("schema recusou o XML (sabotagem do teste)"))
                .when(validador).validarNfe(org.mockito.ArgumentMatchers.anyString());
        try {
            mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce")
                    .header("Authorization", "Bearer " + token)
                    .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"));
        } catch (Exception esperado) {
            // A exceção sobe de propósito: o que este teste mede é o que ficou GRAVADO.
        } finally {
            Mockito.reset(validador);
        }

        var queimado = jdbc.sql("""
                        SELECT numero, serie, situacao::text AS situacao, motivo_nao_emissao
                          FROM documento_fiscal
                         WHERE id_tenant = ? AND id_empresa = ? AND numero = ?
                        """)
                .params(idTenant, idEmpresa, numeroQueEsperoQueimar)
                .query((rs, n) -> rs.getString("situacao") + "|" + rs.getString("motivo_nao_emissao"))
                .optional();

        assertThat(queimado)
                .as("o número %d foi consumido: tem de existir linha em documento_fiscal, senão "
                        + "ele vira buraco silencioso na numeração", numeroQueEsperoQueimar)
                .isPresent();
        assertThat(queimado.get()).startsWith("NAO_EMITIDO|");
        assertThat(queimado.get())
                .as("o motivo é o que alguém vai ler meses depois para decidir se inutiliza a faixa")
                .contains("Número reservado e não utilizado")
                .contains("sabotagem do teste");
    }

    /**
     * ⭐ O campo INFORMAÇÕES COMPLEMENTARES (`infCpl`) — pedido dele em 2026-09-01.
     *
     * <p>Até essa data o Nainer mandava {@code infCpl} <b>nulo</b>: o grupo {@code <infAdic>} nem
     * era gerado e o DANFE imprimia um travessão. Agora leva contrato, forma de pagamento,
     * vendedor, o <b>valor aproximado dos tributos</b> e a <b>observação do operador</b>.
     *
     * <p>⛔ <b>A asserção que mais importa é a da quebra de linha</b>, e ela existe porque o defeito
     * aconteceu: a primeira versão juntava as partes com {@code "\n"} e o XSD oficial recusou
     * <b>toda</b> nota — o padrão do campo é {@code [!-ÿ]{1}[ -ÿ]{0,}[!-ÿ]{1}}, cuja faixa começa
     * no <b>espaço</b> (0x20), e {@code \n} é 0x0A. Cinco testes que já existiam ficaram vermelhos
     * na hora. ⚠️ O operador digita num {@code <textarea>}, então o Enter dele traz o mesmo 0x0A
     * pela porta do usuário — por isso o teste manda uma observação <b>com quebra de linha
     * dentro</b>, que é o caso que reprova a versão sem sanitização.
     */
    @Test
    void informacoesComplementaresLevamContratoPagamentoVendedorTributosEObservacao() throws Exception {
        String token = assinarNovoTenant("infcpl");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        String cnpj = "11222333000181";

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO INFCPL", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente InfCpl");
        long idFuncionario = criarFuncionario(token, "Vendedor InfCpl");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));

        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        // Observação COM quebra de linha e espaços repetidos, de propósito.
                        .content("{\"incluirCpf\":false,\"observacao\":\"Entrega\\nsexta   14h\"}"))
                .andExpect(status().isOk());

        String infCpl = jdbc.sql("""
                        SELECT substring(xml_assinado from '<infCpl>(.*?)</infCpl>')
                          FROM documento_fiscal
                         WHERE id_tenant = ? AND id_venda = ?
                        """)
                .params(idTenant, idVenda).query(String.class).single();

        assertThat(infCpl)
                .as("⛔ o XSD do infCpl recusa 0x0A: quebra de linha aqui rejeita a nota inteira")
                .doesNotContain("\n").doesNotContain("\r")
                .contains("CONTRATO: " + idVenda)
                .contains("FORMA PGTO: DINHEIRO")
                .contains("VENDEDOR: " + idFuncionario + "-VENDEDOR INFCPL")
                .as("a observação do operador entra no XML, não só no papel")
                .contains("OBS.: Entrega sexta 14h")
                // ⚠️ Este produto não tem alíquota IBPT cadastrada, então o total de tributos é
                // ZERO — e a linha da Lei 12.741 NÃO sai. É desenho, não esquecimento: "valor
                // aproximado dos tributos: R$ 0,00" seria pior que a ausência, porque afirma um
                // número. O caso positivo está em
                // vendaComAliquotaIbptResolvidaCalculaVTotTribRealEGravaComOOrigemCorreto.
                .as("com tributos zerados a linha da Lei 12.741 não pode aparecer")
                .doesNotContain("Valor aproximado dos tributos");

        // ⚠️ A leitura pelo DANFE — a outra ponta, e a que faltava — é conferida em
        // vendaAContribuinteSaiEmNfe55ComEnderecoESemQrCode, porque o endpoint do DANFE recusa a
        // NFC-e com 409 (o documento dela é o cupom). Esta venda é de consumidor.
    }

    private int proximoNumero(long idTenant, long idEmpresa) {
        return jdbc.sql("""
                        SELECT proximo_numero FROM fiscal_numeracao
                         WHERE id_tenant = ? AND id_empresa = ? AND modelo = 65
                        """)
                .params(idTenant, idEmpresa).query(Integer.class).single();
    }
}
