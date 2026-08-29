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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inutilização de faixa de numeração (§10.4, bloco B8) — {@code NFeInutilizacao4}. O que importa
 * provar: a tela detecta buracos sozinha (número queimado por rejeição, nunca autorizado), a
 * inutilização homologada os tapa, uma recusa da SEFAZ não some com a tentativa (P3), e a
 * validação preventiva (F11) barra faixa com número já usado ou ainda não alocado sem chamar SEFAZ.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FiscalInutilizacaoTest {

    private static final String SENHA_CERTIFICADO = "senha-teste-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    SefazTransporte transporte;

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- fixture (mesmo recipe de CancelamentoNfceTest)

    private String assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Inutilizacao %s","email":"dono%s@lojainut.com",
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

    private byte[] gerarPfx(String cnpj) throws Exception {
        Path arquivo = tempDir.resolve("cert-" + cnpj + ".pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process processo = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "inut-" + cnpj, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=EMPRESA INUTILIZACAO LTDA:" + cnpj + ", O=TESTE, C=BR",
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

    private long criarPerfilFiscalCsosn102(long idTenant) {
        long idPerfil = jdbc.sql("""
                        INSERT INTO cfg_perfil_fiscal (id_tenant, nome) VALUES (?, 'PERFIL INUTILIZACAO NFCE')
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
                                {"itens":[{"idVariacao":%d,"qtd":1}],"descontoVenda":0,
                                 "idCliente":%d,"idFuncionario":%d,
                                 "pagamentos":[{"idCarteira":%d,"valorPago":%s,"numeroParcelas":1}]}
                                """.formatted(idVariacao, idCliente, idFuncionario, idCarteira, valor)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idVenda")).longValue();
    }

    /** Monta o cenário completo e emite uma NFC-e <b>rejeitada</b> — número 1 fica queimado sem
     *  nunca ter virado nota, o buraco clássico que a inutilização existe para tapar. */
    private long vendaComNfceRejeitada(String token, long idTenant, long idEmpresa, String cnpj) throws Exception {
        completarDadosDaEmpresa(idTenant, idEmpresa, cnpj);
        enviarCertificado(token, idEmpresa, cnpj);
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO INUTILIZACAO NFCE", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Inutilizacao");
        long idFuncionario = criarFuncionario(token, "Vendedor Inutilizacao");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "225", "Rejeicao: falha no schema XML",
                        null, null, "<retEnviNFe><infProt><cStat>225</cStat></infProt></retEnviNFe>"));
        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isOk());

        Mockito.reset(transporte);
        return idVenda;
    }

    private static int numeroDaNota(JdbcClient jdbc, long idTenant, long idVenda) {
        return jdbc.sql("SELECT numero FROM documento_fiscal WHERE id_tenant = ? AND id_venda = ?")
                .params(idTenant, idVenda).query(Integer.class).single();
    }

    // ---------------------------------------------------------------- casos

    @Test
    void detectaBuracoEInutilizacaoHomologadaFechaOBuraco() throws Exception {
        String token = assinarNovoTenant("homologada");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVenda = vendaComNfceRejeitada(token, idTenant, idEmpresa, "11222333000181");
        int numero = numeroDaNota(jdbc, idTenant, idVenda);

        String buracos = mvc.perform(get("/api/v1/fiscal/inutilizacoes/buracos")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("modelo", "65").param("serie", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> faixas = JsonPath.read(buracos, "$");
        assertThat(faixas)
                .as("o número rejeitado aparece como buraco detectado sozinho")
                .anySatisfy(f -> {
                    assertThat(((Number) f.get("numeroInicial")).intValue()).isEqualTo(numero);
                    assertThat(((Number) f.get("numeroFinal")).intValue()).isEqualTo(numero);
                });

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "102", "Inutilizacao de numero homologado",
                        "141260009999999", null,
                        "<retInutNFe><infInut><cStat>102</cStat></infInut></retInutNFe>"));

        String resposta = mvc.perform(post("/api/v1/fiscal/inutilizacoes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idEmpresa":%d,"modelo":65,"serie":1,"numeroInicial":%d,"numeroFinal":%d,
                                 "justificativa":"Numero queimado por rejeicao de schema, nunca autorizado"}
                                """.formatted(idEmpresa, numero, numero)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(resposta, "$.protocolo")).isEqualTo("141260009999999");

        Boolean autorizado = jdbc.sql("""
                        SELECT autorizado FROM fiscal_inutilizacao
                         WHERE id_tenant = ? AND id_empresa = ? AND numero_inicial = ? AND numero_final = ?
                        """)
                .params(idTenant, idEmpresa, numero, numero).query(Boolean.class).single();
        assertThat(autorizado).as("a inutilização homologada fica registrada como autorizada (P3)").isTrue();

        String buracosDepois = mvc.perform(get("/api/v1/fiscal/inutilizacoes/buracos")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("modelo", "65").param("serie", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(buracosDepois, "$"))
                .as("depois de homologada, o buraco não aparece mais").isEmpty();

    }

    /** SEFAZ recusa: a tentativa fica registrada (P3), mas o buraco continua aberto. */
    @Test
    void inutilizacaoRecusadaPelaSefazGravaTentativaMasNaoFechaOBuraco() throws Exception {
        String token = assinarNovoTenant("recusada");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        long idVenda = vendaComNfceRejeitada(token, idTenant, idEmpresa, "22333444000162");
        int numero = numeroDaNota(jdbc, idTenant, idVenda);

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RespostaSefaz(200, "563", "Rejeicao: faixa nao pertence ao emitente",
                        null, null, "<retInutNFe><infInut><cStat>563</cStat></infInut></retInutNFe>"));

        mvc.perform(post("/api/v1/fiscal/inutilizacoes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idEmpresa":%d,"modelo":65,"serie":1,"numeroInicial":%d,"numeroFinal":%d,
                                 "justificativa":"Numero queimado por rejeicao de schema, nunca autorizado"}
                                """.formatted(idEmpresa, numero, numero)))
                .andExpect(status().isConflict());

        Boolean autorizado = jdbc.sql("""
                        SELECT autorizado FROM fiscal_inutilizacao
                         WHERE id_tenant = ? AND id_empresa = ? AND numero_inicial = ? AND numero_final = ?
                        """)
                .params(idTenant, idEmpresa, numero, numero).query(Boolean.class).single();
        assertThat(autorizado).as("a tentativa recusada fica registrada mesmo assim (P3)").isFalse();

        String buracosDepois = mvc.perform(get("/api/v1/fiscal/inutilizacoes/buracos")
                        .header("Authorization", "Bearer " + token)
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("modelo", "65").param("serie", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(buracosDepois, "$"))
                .as("a recusa não fecha o buraco — ele continua aparecendo").hasSize(1);
    }

    /** F11: número já autorizado por nota de verdade nunca pode ser inutilizado — bloqueia sem SEFAZ. */
    @Test
    void naoDeixaInutilizarNumeroJaAutorizadoPorNotaDeVerdade() throws Exception {
        String token = assinarNovoTenant("ja-autorizado");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        completarDadosDaEmpresa(idTenant, idEmpresa, "33444555000143");
        enviarCertificado(token, idEmpresa, "33444555000143");
        ligarFiscal(token, idEmpresa);
        long idPerfil = criarPerfilFiscalCsosn102(idTenant);
        long idVariacao = criarProdutoComPerfil(token, idTenant, "PRODUTO JA AUTORIZADO", idPerfil, "30.00");
        long idCliente = criarClienteAnonimo(token, "Cliente Ja Autorizado");
        long idFuncionario = criarFuncionario(token, "Vendedor Ja Autorizado");
        long idCarteira = carteiraDinheiroComTpag(token, idTenant);
        abrirCaixa(token, idCarteira);
        long idVenda = efetivarVenda(token, idVariacao, idCliente, idFuncionario, idCarteira, "30.00");

        Mockito.when(transporte.enviar(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            String enviNFe = inv.getArgument(2);
            int i = enviNFe.indexOf("Id=\"NFe") + "Id=\"NFe".length();
            String chave = enviNFe.substring(i, enviNFe.indexOf('"', i));
            return new RespostaSefaz(200, "100", "Autorizado o uso da NF-e", "141260001999999", chave,
                    "<retEnviNFe><protNFe><infProt><cStat>100</cStat></infProt></protNFe></retEnviNFe>");
        });
        mvc.perform(post("/api/v1/pdv/vendas/" + idVenda + "/nfce").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isOk());
        Mockito.reset(transporte);
        int numero = numeroDaNota(jdbc, idTenant, idVenda);

        mvc.perform(post("/api/v1/fiscal/inutilizacoes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idEmpresa":%d,"modelo":65,"serie":1,"numeroInicial":%d,"numeroFinal":%d,
                                 "justificativa":"Tentativa indevida de inutilizar nota autorizada"}
                                """.formatted(idEmpresa, numero, numero)))
                .andExpect(status().isConflict());

        Mockito.verifyNoInteractions(transporte);
    }

    /** F11: número que a série ainda nem alocou não pode ser "buraco" nenhum — bloqueia sem SEFAZ. */
    @Test
    void naoDeixaInutilizarNumeroAindaNaoAlocado() throws Exception {
        String token = assinarNovoTenant("nao-alocado");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        completarDadosDaEmpresa(idTenant, idEmpresa, "44555666000124");
        enviarCertificado(token, idEmpresa, "44555666000124");
        ligarFiscal(token, idEmpresa);

        mvc.perform(post("/api/v1/fiscal/inutilizacoes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idEmpresa":%d,"modelo":65,"serie":1,"numeroInicial":1,"numeroFinal":10,
                                 "justificativa":"Faixa que a serie nunca chegou a alocar ainda"}
                                """.formatted(idEmpresa)))
                .andExpect(status().isConflict());

        Mockito.verifyNoInteractions(transporte);
    }
    /**
     * ⭐ <b>Nota em contingência não é buraco</b> — nem para a tela sugerir, nem para o POST aceitar.
     *
     * <p>O cenário real: a SEFAZ cai, a loja emite em contingência (cupom na mão do consumidor, XML
     * assinado, esperando o dreno de 24 h). Até 2026-08-27, a tela de Inutilização <b>oferecia
     * esses números como buraco</b> e o servidor aceitava. A SEFAZ homologa a inutilização — e
     * <b>inutilização homologada não se desfaz</b>. Quando o dreno transmitisse, cada nota voltaria
     * recusada: consumidores com cupom e sem documento fiscal.
     *
     * <p>⚠️ Os guardas de <b>faixa</b> (número final ≥ próximo, sobreposição, justificativa) estavam
     * todos corretos e não pegavam nada: o furo era a <b>lista de situações</b>, uma camada abaixo.
     */
    @Test
    void naoDeixaInutilizarNumeroDeNotaEmContingencia() throws Exception {
        String token = assinarNovoTenant("contingencia-buraco");
        long idTenant = idTenantDo(token);
        long idEmpresa = idEmpresaDo(token);
        completarDadosDaEmpresa(idTenant, idEmpresa, "55666777000165");
        enviarCertificado(token, idEmpresa, "55666777000165");
        ligarFiscal(token, idEmpresa);

        // Uma nota em contingência ocupando o número 1: XML assinado, aguardando o dreno.
        jdbc.sql("""
                        INSERT INTO documento_fiscal (id_tenant, id_empresa, modelo, serie, numero,
                                                       situacao, tipo_operacao, tipo_emissao, ambiente,
                                                       chave_acesso, valor_total)
                        VALUES (?, ?, 65, 1, 1, 'CONTINGENCIA', 'VENDA_CONSUMIDOR', 9, 'HOMOLOGACAO',
                                '41260111222333000181650010000000011000000017', 30.00)
                        """)
                .params(idTenant, idEmpresa).update();
        // A numeração precisa ter passado do 1, senão o guarda de "número ainda não alocado" é que
        // barraria — e o teste passaria pelo motivo errado.
        jdbc.sql("""
                        INSERT INTO fiscal_numeracao (id_tenant, id_empresa, modelo, serie, proximo_numero)
                        VALUES (?, ?, 65, 1, 5)
                        ON CONFLICT (id_tenant, id_empresa, modelo, serie)
                        DO UPDATE SET proximo_numero = 5
                        """)
                .params(idTenant, idEmpresa).update();

        // A tela não pode nem oferecer.
        String buracos = mvc.perform(get("/api/v1/fiscal/inutilizacoes/buracos")
                        .param("idEmpresa", String.valueOf(idEmpresa)).param("modelo", "65").param("serie", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // ⚠️ Asserção sobre a FAIXA, não sobre o texto do JSON: o endpoint devolve
        // {numeroInicial, numeroFinal}, então procurar "1" no corpo casaria com qualquer coisa.
        java.util.List<java.util.Map<String, Object>> faixas =
                com.jayway.jsonpath.JsonPath.read(buracos, "$");
        org.assertj.core.api.Assertions.assertThat(faixas)
                .as("o número da nota em contingência não pode ser oferecido como buraco: " + buracos)
                .noneMatch(f -> ((Number) f.get("numeroInicial")).intValue() <= 1
                        && ((Number) f.get("numeroFinal")).intValue() >= 1);

        // E o POST recusa, mesmo que alguém chame a API direto.
        mvc.perform(post("/api/v1/fiscal/inutilizacoes").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"idEmpresa":%d,"modelo":65,"serie":1,"numeroInicial":1,"numeroFinal":1,
                                 "justificativa":"Tentativa indevida sobre nota em contingencia"}
                                """.formatted(idEmpresa)))
                .andExpect(status().isConflict());

        Mockito.verifyNoInteractions(transporte);
    }
}
