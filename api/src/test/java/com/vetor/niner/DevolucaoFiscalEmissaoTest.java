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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NF-e de devolução (modelo 55, entrada) disparada por uma devolução real da tela — bloco B9,
 * §10.2. É a ponta a ponta que amarra o caminho inteiro: venda com NFC-e autorizada → devolução
 * gravada → {@code DevolucaoFiscalAssembler} lê a nota original → {@code MontadorXmlNfeDevolucao}
 * monta e o XML é assinado de verdade → {@code documento_fiscal} da devolução persistido.
 *
 * <p>Mesma estratégia do {@code VendaFiscalEmissaoTest}: {@link SefazTransporte} mockado (a
 * viagem real é coberta pelo B0 e pelo {@code SefazTransporteTest}), tudo antes e depois dela
 * exercitado com dados de verdade — inclusive certificado real gerado por {@code keytool}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DevolucaoFiscalEmissaoTest {

    private static final String SENHA_CERTIFICADO = "senha-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    SefazTransporte transporte;

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ casos

    /**
     * O caminho completo: venda com NFC-e autorizada, devolução total do único item, NF-e de
     * entrada autorizada. Confere o que o Ajuste SINIEF 8/2026 exige (referência dupla) e o que
     * separa esta feature de um recálculo (tributação espelhada).
     */
    @Test
    void devolucaoDeVendaComNfceEmiteNotaDeEntradaReferenciandoAOriginal() throws Exception {
        Cenario c = prepararVendaComNfceAutorizada("devol-ok", "11222333000181");

        // A partir daqui a SEFAZ responde a emissão da NF-e 55 de devolução.
        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));

        String resp = mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                                """.formatted(c.idVenda(), c.idVariacao())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(resp, "$.notaFiscal.situacao")).isEqualTo("AUTORIZADO");
        long idDocumentoDevolucao = ((Number) JsonPath.read(resp, "$.notaFiscal.idDocumentoFiscal")).longValue();

        var doc = jdbc.sql("""
                        SELECT modelo, tipo_nf, finalidade, tipo_operacao::text AS tipo_operacao, situacao::text AS situacao,
                               serie, numero, xml_assinado
                          FROM documento_fiscal WHERE id_tenant = ? AND id_documento_fiscal = ?
                        """)
                .params(c.idTenant(), idDocumentoDevolucao)
                .query((rs, n) -> new DocDevolucao(rs.getInt("modelo"), rs.getInt("tipo_nf"), rs.getInt("finalidade"),
                        rs.getString("tipo_operacao"), rs.getString("situacao"), rs.getInt("serie"),
                        rs.getInt("numero"), rs.getString("xml_assinado")))
                .single();

        assertThat(doc.modelo()).as("NF-e, não NFC-e").isEqualTo(55);
        assertThat(doc.tipoNf()).as("0 = entrada").isZero();
        assertThat(doc.finalidade()).as("4 = devolução").isEqualTo(4);
        assertThat(doc.tipoOperacao()).isEqualTo("DEVOLUCAO_VENDA");
        assertThat(doc.situacao()).isEqualTo("AUTORIZADO");

        // Ajuste SINIEF 8/2026 — referência SÓ a nível de item, dentro do XML que foi assinado.
        // Os dois níveis juntos passam no XSD mas a SEFAZ rejeita (cStat 1010) — ver
        // MontadorXmlNfeDevolucaoTest.referenciaANotaOriginalSomenteItemAItem.
        assertThat(doc.xml())
                .as("referência item a item").contains("<DFeReferenciado><chaveAcesso>" + c.chaveNfce() + "</chaveAcesso><nItem>1</nItem>")
                .as("sem NFref junto (cStat 1010)").doesNotContain("<NFref>")
                .as("CFOP de devolução interna").contains("<CFOP>1202</CFOP>")
                .as("tributação espelhada da venda (CSOSN 102)").contains("<CSOSN>102</CSOSN>");

        // A amarração também fica em coluna, não só no XML — é o que responde "esta devolução é
        // de qual venda?" sem parsear nada.
        String chaveReferenciada = jdbc.sql("""
                        SELECT chave_referenciada FROM documento_fiscal_referencia
                         WHERE id_tenant = ? AND id_documento_fiscal = ?
                        """)
                .params(c.idTenant(), idDocumentoDevolucao).query(String.class).single();
        assertThat(chaveReferenciada).isEqualTo(c.chaveNfce());

        // E os itens da devolução ficam gravados, com o CFOP de entrada (não o 5102 da venda).
        var item = jdbc.sql("""
                        SELECT numero_item, cfop, csosn, quantidade, valor_produto
                          FROM documento_fiscal_item WHERE id_tenant = ? AND id_documento_fiscal = ?
                        """)
                .params(c.idTenant(), idDocumentoDevolucao)
                .query((rs, n) -> new ItemDevol(rs.getInt("numero_item"), rs.getString("cfop"),
                        rs.getString("csosn"), rs.getBigDecimal("quantidade"), rs.getBigDecimal("valor_produto")))
                .single();
        assertThat(item.cfop()).isEqualTo("1202");
        assertThat(item.csosn()).isEqualTo("102");
        assertThat(item.valorProduto()).isEqualByComparingTo("30.00");
    }

    /**
     * F3 — a nota nunca bloqueia a operação de balcão. A mercadoria já voltou fisicamente ao
     * estoque e o vale-mercadoria já é do cliente: uma rejeição da SEFAZ registra a nota com a
     * situação real e segue, em vez de desfazer a devolução (diferente do Cancelamento de Venda,
     * onde cancelar a nota é pré-requisito de reverter).
     */
    @Test
    void sefazRecusandoANotaNaoDesfazADevolucaoNemOVale() throws Exception {
        Cenario c = prepararVendaComNfceAutorizada("devol-rejeitada", "22333444000162");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "539", "Rejeicao: duplicidade de NF-e", null, null,
                        "<retEnviNFe><protNFe><infProt><cStat>539</cStat></infProt></protNFe></retEnviNFe>"));

        String resp = mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                                """.formatted(c.idVenda(), c.idVariacao())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(resp, "$.notaFiscal.situacao")).isEqualTo("REJEITADO");
        assertThat(((Number) JsonPath.read(resp, "$.valorVale")).doubleValue())
                .as("o vale-mercadoria continua valendo").isEqualTo(30.00);

        long idDevolucao = ((Number) JsonPath.read(resp, "$.idDevolucao")).longValue();
        Boolean cancelada = jdbc.sql("SELECT cancelada FROM venda_devolucao WHERE id_tenant = ? AND id_devolucao = ?")
                .params(c.idTenant(), idDevolucao).query(Boolean.class).single();
        assertThat(cancelada).as("a devolução NÃO é desfeita por causa da nota").isFalse();

        BigDecimal estoque = jdbc.sql("SELECT qtd_estoque FROM produto_estoque WHERE id_tenant = ? AND id_variacao = ?")
                .params(c.idTenant(), c.idVariacao()).query(BigDecimal.class).single();
        assertThat(estoque).as("a mercadoria voltou ao estoque de qualquer forma").isEqualByComparingTo("10.000");
    }

    /** Venda sem NFC-e (fiscal desligado na hora da venda): devolução segue valendo, sem nota. */
    @Test
    void devolucaoDeVendaSemNfceNaoEmiteNotaEnemFalha() throws Exception {
        Cenario c = prepararVendaSemNfce("devol-sem-nfce", "33444555000143");

        String resp = mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"numeroVenda":%d,"itens":[{"idVariacao":%d,"qtd":1}]}
                                """.formatted(c.idVenda(), c.idVariacao())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat((Object) JsonPath.read(resp, "$.notaFiscal")).as("nada a emitir").isNull();
        Mockito.verifyNoInteractions(transporte);
    }

    /** Devolução sem venda de origem: decisão de escopo (2026-08-19) — sem documento para
     *  referenciar não há devolução fiscal, só o vale. */
    @Test
    void devolucaoSemVendaDeOrigemNaoEmiteNota() throws Exception {
        Cenario c = prepararVendaComNfceAutorizada("devol-sem-origem", "44555666000124");
        // O tenant desta fixture exige número da venda; desligar pra poder devolver sem ele.
        desligarExigenciaDeNumeroDaVenda(c.token());
        Mockito.reset(transporte);

        String resp = mvc.perform(post("/api/v1/vendas/devolucao").header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"itens\":[{\"idVariacao\":%d,\"qtd\":1}]}".formatted(c.idVariacao())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat((Object) JsonPath.read(resp, "$.notaFiscal")).isNull();
        Mockito.verifyNoInteractions(transporte);
    }

    // ------------------------------------------------------------------ fixtures

    private record Cenario(String token, long idTenant, long idEmpresa, long idVariacao, long idVenda,
                            String chaveNfce) {
    }

    private record DocDevolucao(int modelo, int tipoNf, int finalidade, String tipoOperacao, String situacao,
                                 int serie, int numero, String xml) {
    }

    private record ItemDevol(int numeroItem, String cfop, String csosn, BigDecimal quantidade,
                              BigDecimal valorProduto) {
    }

    /** Venda real do PDV com NFC-e autorizada — o pré-requisito de toda devolução fiscal. */
    private Cenario prepararVendaComNfceAutorizada(String sufixo, String cnpj) throws Exception {
        String token = assinarNovoTenant(sufixo);
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa, true);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO DEVOLUCAO FISCAL", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Devolucao Fiscal");
        long idFuncionario = criarFuncionario(token, "Vendedor Devolucao Fiscal");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> autorizada(extrairChaveDoEnvi(inv.getArgument(2))));
        String respNfce = mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String chaveNfce = JsonPath.read(respNfce, "$.chaveAcesso");
        Mockito.reset(transporte);

        return new Cenario(token, idTenant, idEmpresa, idVariacao, idVenda, chaveNfce);
    }

    /** Venda igual à de cima, mas com o fiscal DESLIGADO — nenhuma NFC-e é emitida. */
    private Cenario prepararVendaSemNfce(String sufixo, String cnpj) throws Exception {
        String token = assinarNovoTenant(sufixo);
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);

        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO SEM FISCAL", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Sem Fiscal");
        long idFuncionario = criarFuncionario(token, "Vendedor Sem Fiscal");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        return new Cenario(token, idTenant, idEmpresa, idVariacao, idVenda, null);
    }

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Devolucao Fiscal %s","email":"dono%s@lojadevolfiscal.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private static long idEmpresaDo(String token) {
        return ((Number) JsonPath.read(payloadDo(token), "$.eid")).longValue();
    }

    private static long idTenantDo(String token) {
        return ((Number) JsonPath.read(payloadDo(token), "$.tid")).longValue();
    }

    private static String payloadDo(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private void completarDadosDaEmpresa(long idTenant, long idEmpresa, String cnpj) {
        jdbc.sql("""
                        UPDATE empresa SET cnpj = ?, inscricao_estadual = '1234567890',
                               endereco = 'RUA TESTE', numero = '100', bairro = 'CENTRO',
                               codigo_municipio_ibge = 4106902, cidade = 'CURITIBA', estado = 'PR',
                               cep = '80000000', telefone = '4133334444', cnae = '4781400'
                         WHERE id_tenant = ? AND id_empresa = ?
                        """)
                .params(cnpj, idTenant, idEmpresa).update();
    }

    private void ligarFiscal(String token, long idEmpresa, boolean emiteNfe) throws Exception {
        mvc.perform(put("/api/v1/fiscal/config/" + idEmpresa).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"crt":1,"emiteNfce":true,"emiteNfe":%s,
                                 "ambiente":"HOMOLOGACAO","serieNfce":1,"serieNfe":1,"serieContingencia":9,
                                 "cscId":"000001","cscToken":"csc-fake-de-teste"}
                                """.formatted(emiteNfe)))
                .andExpect(status().isOk());
    }

    private void desligarExigenciaDeNumeroDaVenda(String token) throws Exception {
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"percentualDescontoVenda":0,"jurosCrediarioDias":0,"jurosCrediario":0,
                                 "multaCrediarioDias":0,"multaCrediario":0,"cfgUsaCorGrade":false,
                                 "cfgPermiteQtdDecimal":true,"cfgPermiteEstoqueNegativo":true,"cfgDiasValidadeOrcamento":15,"cfgExigeNumeroVendaDevolucao":false,
                                 "cfgRateiaFreteEntrada":false,"cfgReajustaPrecoEntrada":false,
                                 "cfgConsisteValorContasPagar":false,
                                 "idPlanoContasCompraMercadoria":"3.03.001","cfgEmiteFiscalAposVenda":true}
                                """))
                .andExpect(status().isOk());
    }

    private byte[] gerarPfx(String cnpj) throws Exception {
        Path arquivo = tempDir.resolve("cert-" + cnpj + ".pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process p = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "devol-" + cnpj, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=EMPRESA DEVOLUCAO LTDA:" + cnpj + ", O=TESTE, C=BR",
                "-keystore", arquivo.toString(), "-storetype", "PKCS12",
                "-storepass", SENHA_CERTIFICADO, "-keypass", SENHA_CERTIFICADO)
                .redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool falhou: " + saida);
        }
        return Files.readAllBytes(arquivo);
    }

    private void enviarCertificado(String token, long idEmpresa, String cnpj) throws Exception {
        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "cert.pfx",
                                "application/x-pkcs12", gerarPfx(cnpj)))
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("senha", SENHA_CERTIFICADO)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    private long criarPerfilFiscalCsosn102(long idTenant) {
        long idPerfil = jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal (id_tenant, nome) VALUES (?, 'PERFIL DEVOLUCAO FISCAL')
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

    private long criarProdutoComPerfil(String token, long idTenant, String descricao, long idPerfil,
                                        String preco) throws Exception {
        jdbc.sql("INSERT INTO cfg_produto_ncm (codigo_ncm, descricao_ncm) VALUES ('61091000', 'NCM TESTE') "
                + "ON CONFLICT DO NOTHING").update();
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"descricao":"%s","precoCusto":"10.00","percentualVenda":"200","precoVenda":"%s",
                                 "codigoNcm":"61091000"}
                                """.formatted(descricao, preco)))
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

        jdbc.sql("""
                        INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque)
                        VALUES (?, (SELECT id_empresa FROM empresa WHERE id_tenant = ? LIMIT 1), ?, 10)
                        """)
                .params(idTenant, idTenant, idVariacao).update();
        return idVariacao;
    }

    private long criarClienteAnonimo(String token, String nome) throws Exception {
        String respCat = mvc.perform(post("/api/v1/categorias-cliente").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nomeCategoria\":\"GERAL %s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idCategoria = ((Number) JsonPath.read(respCat, "$.idCategoriaCliente")).longValue();

        String resp = mvc.perform(post("/api/v1/clientes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"fisicaJuridica":false,"nome":"%s","idCategoriaCliente":%d}
                                """.formatted(nome, idCategoria)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idCliente")).longValue();
    }

    private long criarFuncionario(String token, String nome) throws Exception {
        String resp = mvc.perform(post("/api/v1/funcionarios").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"nome\":\"%s\"}".formatted(nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idFuncionario")).longValue();
    }

    private long carteiraDinheiroComTpag(String token, long idTenant) throws Exception {
        String resp = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/caixa/carteiras").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> carteiras = JsonPath.read(resp, "$");
        long idCarteira = carteiras.stream()
                .filter(x -> "DINHEIRO".equals(x.get("nomeCarteira")))
                .map(x -> ((Number) x.get("idCarteira")).longValue())
                .findFirst().orElseThrow();
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

    private long efetivarVenda(String token, long idVariacao, long idCliente, long idFuncionario,
                                long idCarteira, String valor) throws Exception {
        String resp = mvc.perform(post("/api/v1/pdv/vendas").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,"idCliente":%d,
                                 "idFuncionario":%d,
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

    private static String extrairChaveDoEnvi(String enviNFe) {
        var m = java.util.regex.Pattern.compile("Id=\"NFe(\\d{44})\"").matcher(enviNFe);
        return m.find() ? m.group(1) : "0".repeat(44);
    }
}
