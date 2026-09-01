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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bloco <b>S6/S7</b> — a máquina da NFS-e inteira, do serviço vendido à nota cancelada.
 *
 * <h2>⭐ Por que este teste existe, e por que ele roda de verdade</h2>
 *
 * <p>O fiscal de mercadoria roda com {@code emite_nfe = false} na suíte, e por isso o caminho da
 * devolução ao fornecedor <b>não tem teste</b> — foi assim que dois defeitos reais passaram por
 * 911 testes verdes, um deles deixando uma NF-e 55 autorizada contra uma saída que não aconteceu.
 * Caminho fiscal sem teste não é caminho testado: é caminho ausente.
 *
 * <p>Aqui é o contrário: o {@code EmissorFalso} é o <b>padrão</b>, então numeração, montagem,
 * assinatura, gravação, máquina de estados e cancelamento <b>rodam</b>. O que fica de fora é só o
 * salto de rede — que é justamente a parte que teste automatizado não prova mesmo. Quem provou a
 * aceitação foi o Sefin real, uma vez, com a NFS-e 7308.
 *
 * <h2>O que cada teste protege</h2>
 *
 * <ul>
 *   <li>a cardinalidade nova: <b>uma nota por código de serviço</b>, que quebra a invariante "uma
 *       nota por venda" da NF-e — e quebra de propósito, porque a DPS carrega um {@code cServ} só;</li>
 *   <li>o F11: cada bloqueio preventivo recusa <b>antes</b> de consumir número, com mensagem que
 *       diz o que falta;</li>
 *   <li>o cancelamento: e que ele <b>ficou</b>, não só que foi aceito.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NfseEmissaoIntegracaoTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @TempDir
    Path tempDir;

    /** O CNPJ tem de bater com o da empresa: o upload recusa certificado de outro titular. */
    private static final String CNPJ = "22120254000186";
    private static final String SENHA_PFX = "segredo123";

    // ---- cenário -----------------------------------------------------------------------------

    private record Cenario(String token, long idTenant, long idEmpresa, long idVenda) {
    }

    /**
     * Uma loja de serviço pronta para emitir: módulo ligado, empresa com CNPJ e município,
     * NFS-e configurada com a alíquota do Simples, dois serviços de códigos DIFERENTES vendidos
     * na mesma venda.
     */
    private Cenario prepararLojaQueVendeDoisServicos(String sufixo) throws Exception {
        String token = assinar(sufixo);
        long idTenant = idTenant(token);
        ligarServicos(token);

        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = idEmpresa(c);
            completarEmpresa(c, idEmpresa);
            configurarNfse(c, idEmpresa);

            enviarCertificado(token, idEmpresa);

            long banho = criarServico(token, "BANHO E TOSA", "050801");
            long consulta = criarServico(token, "CONSULTA VETERINARIA", "050101");
            long idVenda = criarVenda(c, idTenant, idEmpresa);
            lancarItem(c, idTenant, idEmpresa, idVenda, variacao(c, idTenant, banho), "80.00");
            lancarItem(c, idTenant, idEmpresa, idVenda, variacao(c, idTenant, consulta), "150.00");
            return new Cenario(token, idTenant, idEmpresa, idVenda);
        }
    }

    // ---- os testes ---------------------------------------------------------------------------

    /**
     * ⭐ O teste central: dois serviços de códigos distintos na MESMA venda geram <b>duas</b>
     * NFS-e. É a consequência direta do leiaute (a DPS tem um {@code cServ} só) e o ponto em que
     * a NFS-e diverge da NF-e — quem espera "uma nota por venda" aqui se engana.
     */
    @Test
    void vendaComDoisCodigosDeServicoGeraDuasNotas() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("dois");

        String resp = mvc.perform(post("/api/v1/nfse/vendas/" + c.idVenda() + "/emitir")
                        .header("Authorization", "Bearer " + c.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<java.util.List<?>>read(resp, "$")).hasSize(2);
        assertThat(JsonPath.<java.util.List<String>>read(resp, "$[*].situacao"))
                .containsExactly("AUTORIZADA", "AUTORIZADA");

        try (Connection conn = abrirConexao(c.idTenant())) {
            // ⚠️ Confere o BANCO, não só o HTTP: um teste que valide o status passaria com a nota
            // gravada sem chave, e "AUTORIZADA sem chave" é o pior estado possível — nota que
            // existe na prefeitura e que o ERP não sabe identificar.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT codigo_tributacao_nacional, situacao::text, chave_acesso,
                                numero_nfse, valor_servicos
                           FROM nfse_documento WHERE id_venda = %d
                          ORDER BY codigo_tributacao_nacional
                         """.formatted(c.idVenda()))) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("050101");
                assertThat(rs.getString(2)).isEqualTo("AUTORIZADA");
                assertThat(rs.getString(3)).hasSize(50);
                assertThat(rs.getLong(4)).isPositive();
                assertThat(rs.getBigDecimal(5)).isEqualByComparingTo("150.00");

                rs.next();
                assertThat(rs.getString(1)).isEqualTo("050801");
                assertThat(rs.getBigDecimal(5)).isEqualByComparingTo("80.00");
                assertThat(rs.next()).as("nenhuma nota a mais").isFalse();
            }
        }
    }

    /**
     * Emitir duas vezes não duplica: a segunda chamada devolve a nota que já existe.
     *
     * <p>⚠️ É o que protege do clique duplo e do reprocessamento — e a guarda mora no banco
     * ({@code nfse_documento_venda_servico_uk}), não só no serviço.
     */
    @Test
    void emitirDuasVezesNaoDuplicaANota() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("idem");

        mvc.perform(post("/api/v1/nfse/vendas/" + c.idVenda() + "/emitir")
                .header("Authorization", "Bearer " + c.token())).andExpect(status().isOk());
        String segunda = mvc.perform(post("/api/v1/nfse/vendas/" + c.idVenda() + "/emitir")
                        .header("Authorization", "Bearer " + c.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(segunda).contains("já está autorizada");
        assertThat(contarNotas(c)).isEqualTo(2);
    }

    /**
     * ⭐ O cancelamento, e a prova de que ele FICOU.
     *
     * <p>HTTP 200 diz que o pedido foi aceito. Quem prova que ficou é o segundo cancelamento: o
     * Sefin responde {@code E0840} ("o evento já está vinculado à NFS-e"), e o
     * {@link com.vetor.niner.fiscal.nfse.EmissorFalso} reproduz isso justamente para este teste
     * poder existir. É o mesmo princípio da sondagem que fizemos contra o servidor real.
     */
    @Test
    void cancelaAnotaEOSegundoCancelamentoConfirmaQueFicou() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("cancel");
        mvc.perform(post("/api/v1/nfse/vendas/" + c.idVenda() + "/emitir")
                .header("Authorization", "Bearer " + c.token())).andExpect(status().isOk());
        long idNfse = primeiroIdNfse(c);

        String motivo = "Servico nao foi prestado ao cliente";
        mvc.perform(delete("/api/v1/nfse/" + idNfse).header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"codigoMotivo\":2,\"motivo\":\"" + motivo + "\"}"))
                .andExpect(status().isOk());

        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT situacao::text FROM nfse_documento WHERE id_nfse = " + idNfse)) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("CANCELADA");
        }

        // O evento ficou registrado (F6: nunca apagado).
        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT tipo_evento, motivo_codigo, situacao FROM nfse_documento_evento "
                     + "WHERE id_nfse = " + idNfse)) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("101101");
            assertThat(rs.getInt(2)).isEqualTo(2);
            assertThat(rs.getString(3)).isEqualTo("REGISTRADO");
        }
    }

    /** ⚠️ Motivo curto é recusado ANTES de sair — a faixa 15..255 é do XSD, não capricho nosso. */
    @Test
    void motivoDeCancelamentoCurtoEhRecusadoAntesDeEnviar() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("motivo");
        mvc.perform(post("/api/v1/nfse/vendas/" + c.idVenda() + "/emitir")
                .header("Authorization", "Bearer " + c.token())).andExpect(status().isOk());
        long idNfse = primeiroIdNfse(c);

        mvc.perform(delete("/api/v1/nfse/" + idNfse).header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"codigoMotivo\":1,\"motivo\":\"errei\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- F11: os bloqueios preventivos --------------------------------------------------------

    /**
     * ⛔ Sem a alíquota efetiva do Simples não há emissão — medido em produção ({@code E0712}).
     *
     * <p>O teste prova que o bloqueio acontece <b>antes de consumir número</b>: é a diferença
     * entre o lojista corrigir um cadastro e o operador tomar um código de erro no balcão.
     */
    @Test
    void semAliquotaDoSimplesRecusaAntesDeConsumirNumero() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("semaliq");
        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement()) {
            st.execute("UPDATE fiscal_config_nfse SET aliquota_simples_efetiva = NULL");
        }

        mvc.perform(post("/api/v1/nfse/vendas/" + c.idVenda() + "/emitir")
                        .header("Authorization", "Bearer " + c.token()))
                .andExpect(status().isConflict());

        assertThat(contarNotas(c)).as("nada foi gravado, nenhum número foi gasto").isZero();
        assertThat(ultimoNumeroReservado(c)).as("a numeração não avançou").isZero();
    }

    /** ⛔ Serviço sem código da LC 116 — e a mensagem NOMEIA o serviço, para o lojista achar. */
    @Test
    void servicoSemCodigoRecusaNomeandoOServico() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("semcod");
        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement()) {
            st.execute("UPDATE produto_servico SET codigo_tributacao_nacional = NULL "
                    + "WHERE id_produto IN (SELECT id_produto FROM produto WHERE descricao = 'BANHO E TOSA')");
        }

        String erro = mvc.perform(post("/api/v1/nfse/vendas/" + c.idVenda() + "/emitir")
                        .header("Authorization", "Bearer " + c.token()))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(erro).contains("BANHO E TOSA");
        assertThat(contarNotas(c)).isZero();
    }

    /** ⛔ Emissão desligada é caminho legítimo (F12) — mas recusa dizendo onde ligar. */
    @Test
    void emissaoDesligadaRecusaDizendoOndeLigar() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("desligado");
        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement()) {
            st.execute("UPDATE fiscal_config_nfse SET emite_nfse = false");
        }

        String erro = mvc.perform(post("/api/v1/nfse/vendas/" + c.idVenda() + "/emitir")
                        .header("Authorization", "Bearer " + c.token()))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(erro).contains("Configuração da NFS-e");
    }

    // ---- o PDV emitindo (pendência #78, 2026-09-01) -------------------------------------------

    /**
     * ⭐ <b>O caso que estava quebrado, e é o normal de petshop e de consultório:</b> uma venda
     * 100% serviço emitida pelo <b>PDV</b>.
     *
     * <p>Até 2026-09-01 este {@code POST} respondia <b>409</b> — o montador da NFC-e descobria que
     * a venda não tinha mercadoria e recusava com <i>"só tem serviços, imprima a papeleta"</i>. Ou
     * seja: a loja que vende <b>só serviço</b> não conseguia emitir documento fiscal <b>nenhum</b>
     * pelo caixa, mesmo com a máquina da NFS-e pronta e já tendo emitido em produção.
     *
     * <p>⚠️ A asserção decisiva é a <b>contagem</b>: duas notas, uma por código de serviço. Um
     * teste que só conferisse o status 200 passaria com uma implementação que emitisse uma nota
     * só, somando os dois códigos — que é justamente o erro que a regra existe para impedir
     * (declararia serviço errado para parte do valor, com alíquota e local de incidência errados
     * junto).
     */
    @Test
    void vendaSoDeServicoEmiteAsNfsePeloPdv() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("pdv");

        String resp = mvc.perform(post("/api/v1/pdv/vendas/" + c.idVenda() + "/nfce")
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(resp, "$.situacao"))
                .as("não é falha: esta venda não tem mercadoria, logo não existe NFC-e a emitir")
                .isEqualTo("SEM_MERCADORIA");
        assertThat(JsonPath.<List<Object>>read(resp, "$.nfse"))
                .as("uma NFS-e por código de serviço distinto — banho/tosa e consulta veterinária")
                .hasSize(2);
        assertThat(JsonPath.<List<String>>read(resp, "$.nfse[*].situacao"))
                .containsOnly("AUTORIZADA");
        assertThat(JsonPath.<Object>read(resp, "$.avisoServicos"))
                .as("o aviso é sobre o que ficou SEM documento; com a NFS-e emitida ele mentiria")
                .isNull();
    }

    /**
     * ⭐ O caso <b>negativo</b> do mesmo caminho: com a NFS-e desligada, o PDV não pode ficar em
     * silêncio.
     *
     * <p>É o defeito de 2026-08-31 relatado pelo dono do produto — o operador emitia, via o cupom
     * e ia embora achando que a venda inteira estava documentada. ⚠️ E aqui ele é pior que na
     * venda mista: <b>não sai documento nenhum</b>, então a mensagem tem de dizer isso, e não
     * repetir "a nota emitida cobre só as mercadorias" — não há nota.
     *
     * <p>⚠️ 200 com aviso, <b>não</b> 204. O 204 é o F12 (fiscal desligado, a tela não mostra
     * nada) e usá-lo aqui devolveria exatamente o silêncio que este teste existe para impedir.
     */
    @Test
    void vendaDeServicoComNfseDesligadaAvisaEmVezDeSilenciar() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("pdv-desligado");
        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement()) {
            st.execute("UPDATE fiscal_config_nfse SET emite_nfse = false");
        }

        String resp = mvc.perform(post("/api/v1/pdv/vendas/" + c.idVenda() + "/nfce")
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<Object>>read(resp, "$.nfse")).isEmpty();
        String aviso = JsonPath.read(resp, "$.avisoServicos");
        assertThat(aviso)
                .as("venda 100%% serviço: dizer que 'a nota cobre só as mercadorias' seria mentira")
                .contains("só de SERVIÇOS")
                .contains("NÃO saiu documento fiscal nenhum")
                .contains("230,00")            // 80,00 do banho + 150,00 da consulta
                .contains("Configuração da NFS-e");
    }

    /**
     * Emitir duas vezes pelo PDV não duplica nota — a segunda chamada devolve as mesmas duas,
     * dizendo que já estão autorizadas.
     *
     * <p>⚠️ Prende o comportamento na porta NOVA. A idempotência já estava testada pelo endpoint
     * de NFS-e ({@code emitirDuasVezesNaoDuplicaANota}), mas quem passou a chamar em produção é o
     * PDV, e teto que vale num caminho e não no vizinho é a família inteira de
     * {@code feedback_teto_com_porta_ao_lado}.
     */
    @Test
    void emitirDuasVezesPeloPdvNaoDuplicaAsNotas() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("pdv-duas");
        var pedido = post("/api/v1/pdv/vendas/" + c.idVenda() + "/nfce")
                .header("Authorization", "Bearer " + c.token())
                .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}");

        mvc.perform(pedido).andExpect(status().isOk());
        String segunda = mvc.perform(post("/api/v1/pdv/vendas/" + c.idVenda() + "/nfce")
                        .header("Authorization", "Bearer " + c.token())
                        .contentType(APPLICATION_JSON).content("{\"incluirCpf\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<Object>>read(segunda, "$.nfse")).hasSize(2);

        try (Connection conn = abrirConexao(c.idTenant());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM nfse_documento WHERE id_venda = ?")) {
            ps.setLong(1, c.idVenda());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("a contagem tem de vir do BANCO: a resposta HTTP repetiria as duas notas "
                                + "mesmo que quatro tivessem sido gravadas")
                        .isEqualTo(2);
            }
        }
    }

    /**
     * ⛔ Serviço com código que exige bloco extra da DPS é recusado <b>antes de reservar número</b>.
     *
     * <p><b>Por que a guarda mudou de lugar em 2026-09-01:</b> ela existia só no front, que
     * recusava a ESCOLHA do código no cadastro — e o efeito colateral foi um relato dele:
     * <i>"coloco o código do serviço e ele não grava"</i>. Era verdade: clicar num código de obra
     * retornava sem gravar, deixando um aviso cinza no rodapé. Por decisão dele o cadastro passou
     * a gravar <b>qualquer</b> código, e a trava veio para cá.
     *
     * <p>⚠️ Sem esta guarda, o pedido seguiria para a montagem: XML sem o grupo obrigatório, e a
     * rejeição do Sefin viria <b>depois</b> de consumir o {@code nDPS} — numeração queimada a cada
     * tentativa, que é exatamente o preço que o {@code cStat 704} cobrou na NFC-e hoje.
     *
     * <p>⚠️ E guarda de tela nunca foi proteção (P4): quem chama a API direto nunca passou pelo
     * seletor.
     */
    @Test
    void servicoQueExigeBlocoExtraRecusaAntesDeConsumirNumero() throws Exception {
        Cenario c = prepararLojaQueVendeDoisServicos("bloco-extra");
        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement()) {
            // 070602 (colocação de vidros) é do grupo "obra" na carga oficial da V099.
            st.execute("UPDATE produto_servico SET codigo_tributacao_nacional = '070602' "
                    + "WHERE id_produto IN (SELECT id_produto FROM produto WHERE descricao = 'BANHO E TOSA')");
        }

        String erro = mvc.perform(post("/api/v1/nfse/vendas/" + c.idVenda() + "/emitir")
                        .header("Authorization", "Bearer " + c.token()))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(erro)
                .as("a mensagem nomeia o serviço, o código e o bloco que falta")
                .contains("BANHO E TOSA").contains("070602").contains("obra");
        assertThat(erro)
                .as("o cadastro NÃO está errado — dizer que está manda o lojista corrigir o que está certo")
                .contains("cadastro do serviço está correto");
        assertThat(contarNotas(c))
                .as("recusar depois de reservar o nDPS queimaria numeração a cada tentativa")
                .isZero();
    }

    /**
     * ⭐ A TELA consegue salvar a configuração — o corpo aqui é <b>exatamente</b> o que o
     * {@code NfseConfiguracaoForm.tsx} monta: só os campos editáveis.
     *
     * <p><b>Por que este teste faltava, e o que isso custou</b> (2026-09-01): todo teste desta
     * classe configurava a NFS-e por {@code INSERT} direto ({@code configurarNfse}), então
     * <b>nenhum</b> passava pelo {@code PUT}. O endpoint recebia o DTO de <b>leitura</b>, cujos
     * componentes {@code idEmpresa}/{@code configurado}/{@code crt} são primitivos, e o Jackson
     * recusava o corpo do front inteiro com <i>"Failed to read request"</i> — a tela nunca
     * conseguiu salvar, com a suíte verde o tempo todo. Atalho no setup do teste não é economia:
     * é o caminho de produção deixando de ser exercitado.
     */
    @Test
    void aTelaSalvaAConfiguracaoComOCorpoQueOFrontManda() throws Exception {
        String token = assinar("salvar-config");
        long idTenant = idTenant(token);
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = idEmpresa(c);

            String resp = mvc.perform(put("/api/v1/fiscal/nfse/" + idEmpresa)
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"emiteNfse":false,"ambiente":"HOMOLOGACAO","serie":7,
                                     "rbt12":null,"simplesAnexo":"III","aliquotaSimplesEfetiva":null}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(JsonPath.<Integer>read(resp, "$.serie")).isEqualTo(7);
            assertThat(JsonPath.<String>read(resp, "$.ambiente")).isEqualTo("HOMOLOGACAO");

            // ⚠️ A asserção que importa é a do BANCO: um 200 provaria só que o corpo foi lido.
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT serie, simples_anexo FROM fiscal_config_nfse")) {
                assertThat(rs.next()).as("o PUT tem de criar a linha no primeiro salvamento").isTrue();
                assertThat(rs.getInt("serie")).isEqualTo(7);
                assertThat(rs.getString("simples_anexo")).isEqualTo("III");
            }
        }
    }

    /**
     * O caso negativo: campo obrigatório ausente é recusado <b>nomeando o campo</b>.
     *
     * <p>⚠️ Sem isto, o nulo chegava ao {@code INSERT} numa coluna {@code NOT NULL} e a violação
     * saía pelo handler global como <i>"Registro em uso por outro cadastro — não pode ser
     * excluído"</i>: uma mensagem sobre exclusão de cadastro para quem está salvando uma
     * configuração. O teste prende a mensagem, não só o status — é ela que decide se o lojista
     * resolve sozinho ou abre um chamado.
     */
    @Test
    void serieAusenteRecusaNomeandoOCampo() throws Exception {
        String token = assinar("salvar-sem-serie");
        long idTenant = idTenant(token);
        try (Connection c = abrirConexao(idTenant)) {
            long idEmpresa = idEmpresa(c);

            String resp = mvc.perform(put("/api/v1/fiscal/nfse/" + idEmpresa)
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content("{\"emiteNfse\":false,\"ambiente\":\"HOMOLOGACAO\"}"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(JsonPath.<String>read(resp, "$.detail"))
                    .as("a mensagem tem de dizer QUAL campo falta")
                    .contains("Série da DPS");
        }
    }

    // ---- utilitários -------------------------------------------------------------------------

    private String assinar(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Petshop %s","email":"dono%s@petshop.com",
                 "senha":"segredo123","nomeAdmin":"Dono"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.token");
    }

    private long idTenant(String token) throws Exception {
        String resp = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.id_tenant")).longValue();
    }

    private Connection abrirConexao(long idTenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "niner_app", "dev_app");
        try (Statement st = c.createStatement()) {
            st.execute("SET app.id_tenant = " + idTenant);
        }
        return c;
    }

    private void ligarServicos(String token) throws Exception {
        String atual = mvc.perform(get("/api/v1/config-geral").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/v1/config-geral").header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(atual.replaceFirst("\"cfgUsaServicos\":\\s*false", "\"cfgUsaServicos\":true")))
                .andExpect(status().isOk());
    }

    private long idEmpresa(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id_empresa FROM empresa LIMIT 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** O signup cria a empresa sem CNPJ nem município — a emissão exige os dois (F11). */
    private void completarEmpresa(Connection c, long idEmpresa) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("UPDATE empresa SET cnpj = '22120254000186', codigo_municipio_ibge = 4106902, "
                    + "inscricao_municipal = '07156092', estado = 'PR' WHERE id_empresa = " + idEmpresa);
            st.execute("INSERT INTO fiscal_config_empresa (id_tenant, id_empresa, crt) "
                    + "VALUES (plataforma.tenant_atual(), " + idEmpresa + ", 1) "
                    + "ON CONFLICT (id_tenant, id_empresa) DO NOTHING");
        }
    }

    private void configurarNfse(Connection c, long idEmpresa) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO fiscal_config_nfse (id_tenant, id_empresa, emite_nfse, ambiente,
                                                    serie, simples_anexo, aliquota_simples_efetiva)
                    VALUES (plataforma.tenant_atual(), %d, true, 'HOMOLOGACAO', 1, 'III', 6.00)
                    """.formatted(idEmpresa));
        }
    }

    private long criarServico(String token, String descricao, String cTribNac) throws Exception {
        String body = """
                {"descricao":"%s","precoCusto":0,"percentualVenda":0,"precoVenda":80.00,
                 "tipoItem":"SERVICO","codigoTributacaoNacional":"%s","aliquotaIss":5.00}
                """.formatted(descricao, cTribNac);
        String resp = mvc.perform(post("/api/v1/produtos").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resp, "$.idProduto")).longValue();
    }

    /**
     * ⚠️ Obter-ou-criar, não criar (2026-08-31). Desde que o serviço passa a nascer com a própria
     * variação — sem ela ficava invisível na Ordem de Serviço e no PDV —, o INSERT cego bate em
     * produto_barra_variacao_uk. Este helper não estava errado: criava a variação à mão porque o
     * cadastro não criava, e o que os testes precisam é TER uma.
     */
    private long variacao(Connection c, long idTenant, long idProduto) throws SQLException {
        try (PreparedStatement busca = c.prepareStatement(
                "SELECT id_variacao FROM produto_barra WHERE id_produto = ? LIMIT 1")) {
            busca.setLong(1, idProduto);
            try (ResultSet rs = busca.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_barra (id_tenant, id_produto, sku) "
                + "VALUES (?, ?, gerar_ean13_interno()) RETURNING id_variacao")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idProduto);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long criarVenda(Connection c, long idTenant, long idEmpresa) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO venda (id_tenant, id_empresa, data_venda) VALUES (?, ?, now()) "
                + "RETURNING id_venda")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void lancarItem(Connection c, long idTenant, long idEmpresa, long idVenda,
                            long idVariacao, String preco) throws SQLException {
        long idMovimento;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO produto_movimento_mestre (id_tenant, tipo_movimento, data_movimento, "
                + "id_empresa, id_venda) VALUES (?, 'VENDA', now(), ?, ?) RETURNING id_movimento")) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idEmpresa);
            ps.setLong(3, idVenda);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                idMovimento = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO produto_movimento_detalhe
                    (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito,
                     qtd_produto, preco_venda)
                VALUES (?, ?, ?, ?, 'D', 1, ?::numeric)
                """)) {
            ps.setLong(1, idTenant);
            ps.setLong(2, idMovimento);
            ps.setLong(3, idEmpresa);
            ps.setLong(4, idVariacao);
            ps.setString(5, preco);
            ps.executeUpdate();
        }
    }

    private long contarNotas(Cenario c) throws Exception {
        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM nfse_documento WHERE id_venda = " + c.idVenda())) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long ultimoNumeroReservado(Cenario c) throws Exception {
        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(max(proximo_numero) - 1, 0) FROM nfse_numeracao")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * ⚠️ O EmissorFalso evita a REDE, não o CERTIFICADO: a emissão carrega e abre o .pfx do
     * lojista para assinar, e sem ele o F11 recusa com 409 antes de qualquer coisa. Foi
     * exatamente isto que os primeiros quatro testes acusaram — e é bom que tenham acusado, porque
     * prova que o bloqueio funciona.
     *
     * <p>O certificado é autoassinado, gerado por {@code keytool}, no mesmo molde do
     * {@code FiscalCertificadoCrudTest}. Ele não vale para a SEFAZ nem para o Sefin — vale para
     * exercitar a assinatura, que é o que este teste precisa.
     */
    private void enviarCertificado(String token, long idEmpresa) throws Exception {
        Path arquivo = tempDir.resolve("nfse-teste.pfx");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool")
                .toString();
        List<String> comando = new ArrayList<>(List.of(
                keytool, "-genkeypair", "-alias", "nfse", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365",
                "-dname", "CN=PETSHOP TESTE LTDA:" + CNPJ + ", O=PETSHOP TESTE, C=BR",
                "-keystore", arquivo.toString(), "-storetype", "PKCS12",
                "-storepass", SENHA_PFX, "-keypass", SENHA_PFX));
        Process processo = new ProcessBuilder(comando).redirectErrorStream(true).start();
        String saida = new String(processo.getInputStream().readAllBytes());
        if (processo.waitFor() != 0) {
            throw new IllegalStateException("keytool falhou: " + saida);
        }
        mvc.perform(multipart("/api/v1/fiscal/certificados")
                        .file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12",
                                Files.readAllBytes(arquivo)))
                        .param("idEmpresa", String.valueOf(idEmpresa))
                        .param("senha", SENHA_PFX)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    private long primeiroIdNfse(Cenario c) throws Exception {
        try (Connection conn = abrirConexao(c.idTenant());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT min(id_nfse) FROM nfse_documento WHERE id_venda = " + c.idVenda())) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
