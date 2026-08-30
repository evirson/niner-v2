package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O módulo fiscal respeita a <b>empresa da sessão</b> (auditoria de segurança, 2026-08-27).
 *
 * <p>Decisão do dono do produto: quem não é administrador opera só a empresa em que entrou. É a
 * promessa que a tela de Usuários faz ao pedir "empresas com acesso", e o que as rotas de dinheiro
 * (PDV, caixa, devolução) já cumpriam pelo claim {@code eid}.
 *
 * <p><b>O que estava aberto:</b> 14 endpoints fiscais recebiam {@code idEmpresa} por path/query e
 * não conferiam nada. Um operador da filial 1 punha a <b>filial 2</b> em contingência, inutilizava
 * numeração dela ou baixava o ZIP com todo o XML fiscal dela.
 *
 * <p>⚠️ O isolamento entre <b>contas</b> (P8) nunca esteve em jogo aqui — toda query filtra
 * {@code id_tenant}. O que se atravessava era a fronteira entre <b>empresas da mesma conta</b>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmpresaDaSessaoNoFiscalTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private record Cenario(String tokenOperador, long idEmpresaDaSessao, long idOutraEmpresa) {
    }

    /**
     * Conta com <b>duas</b> empresas; o operador tem acesso só à primeira e entra nela. A segunda
     * existe no mesmo tenant — é exatamente a fronteira que se quer testar.
     */
    private Cenario duasEmpresasEUmOperador(String sufixo) throws Exception {
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content("""
                        {"nomeLoja":"Loja Duas Filiais %s","email":"dono%s@duasfiliais.com",
                         "senha":"segredo123","nomeAdmin":"Dono"}
                        """.formatted(sufixo, sufixo)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tokenAdmin = JsonPath.read(resp, "$.token");
        long idTenant = ((Number) JsonPath.read(resp, "$.idTenant")).longValue();

        String eu = mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + tokenAdmin))
                .andReturn().getResponse().getContentAsString();
        long idEmpresa1 = ((Number) JsonPath.read(eu, "$.empresa.idEmpresa")).longValue();

        long idEmpresa2 = jdbc.sql("""
                        INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, nome_fantasia, ativo, cfg_nome_etiqueta)
                        VALUES (?, 2, 'FILIAL DOIS LTDA', 'FILIAL DOIS', true, 'FILIAL DOIS')
                        RETURNING id_empresa
                        """)
                .param(idTenant).query(Long.class).single();
        assertNotEquals(idEmpresa1, idEmpresa2);

        String operador = mvc.perform(post("/api/v1/usuarios").header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(APPLICATION_JSON).content("""
                                {"nome":"Operador Filial Um","email":"op%s@duasfiliais.com","senha":"senha12345",
                                 "idsEmpresa":[%d]}
                                """.formatted(sufixo, idEmpresa1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long idOperador = ((Number) JsonPath.read(operador, "$.idUsuario")).longValue();
        PermissaoDeTeste.liberarTudo(mvc, tokenAdmin, idOperador);

        String login = mvc.perform(post("/api/publico/login").contentType(APPLICATION_JSON).content("""
                        {"email":"op%s@duasfiliais.com","senha":"senha12345"}
                        """.formatted(sufixo)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Cenario(JsonPath.read(login, "$.token"), idEmpresa1, idEmpresa2);
    }

    @Test
    void operadorNaoAlcancaOFiscalDeOutraEmpresaDaMesmaConta() throws Exception {
        Cenario c = duasEmpresasEUmOperador("bloqueio");
        String auth = "Bearer " + c.tokenOperador();
        long outra = c.idOutraEmpresa();

        // Contingência: o pior dos casos — desligar a emissão normal de outra filial.
        mvc.perform(get("/api/v1/fiscal/contingencia/" + outra).header("Authorization", auth))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/fiscal/contingencia/" + outra + "/entrar").header("Authorization", auth)
                        .contentType(APPLICATION_JSON)
                        .content("{\"justificativa\":\"Tentativa de mexer na filial errada\"}"))
                .andExpect(status().isForbidden());

        // Configuração fiscal: série, ambiente, CSC.
        mvc.perform(get("/api/v1/fiscal/config/" + outra).header("Authorization", auth))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/fiscal/config/" + outra).header("Authorization", auth)
                        .contentType(APPLICATION_JSON).content("""
                                {"crt":1,"emiteNfce":true,"emiteNfe":false,"ambiente":"HOMOLOGACAO",
                                 "serieNfce":1,"serieNfe":1,"serieContingencia":9}
                                """))
                .andExpect(status().isForbidden());

        // Inutilização de numeração e o painel de conformidade.
        mvc.perform(get("/api/v1/fiscal/inutilizacoes").param("idEmpresa", String.valueOf(outra))
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());
        // ⛔ E o POST — que é o que EXECUTA o ato, e inutilização NÃO SE DESFAZ. Este caso faltava:
        // a trava do POST entrou em 2026-08-30 e, sem ele, revertê-la não reprovava nada. O GET
        // acima já era guardado desde 08-27, o que dava a impressão de cobertura.
        mvc.perform(post("/api/v1/fiscal/inutilizacoes").header("Authorization", auth)
                        .contentType(APPLICATION_JSON).content("""
                                {"idEmpresa":%d,"modelo":65,"serie":1,"numeroInicial":1,"numeroFinal":1,
                                 "justificativa":"TENTATIVA DE INUTILIZAR FAIXA DA OUTRA FILIAL"}
                                """.formatted(outra)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fiscal/inutilizacoes/buracos").param("idEmpresa", String.valueOf(outra))
                        .param("modelo", "65").param("serie", "1").header("Authorization", auth))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fiscal/conformidade/" + outra).header("Authorization", auth))
                .andExpect(status().isForbidden());

        // Documentos fiscais e o ZIP da exportação — leitura, mas leitura do XML fiscal alheio.
        mvc.perform(get("/api/v1/fiscal/documentos").param("idEmpresa", String.valueOf(outra))
                        .param("dataInicial", "2026-08-01").param("dataFinal", "2026-08-31")
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fiscal/certificados").param("idEmpresa", String.valueOf(outra))
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());
    }

    /** A própria empresa continua funcionando — a trava não pode ter fechado o uso normal. */
    @Test
    void operadorContinuaOperandoOFiscalDaPropriaEmpresa() throws Exception {
        Cenario c = duasEmpresasEUmOperador("propria");
        String auth = "Bearer " + c.tokenOperador();

        mvc.perform(get("/api/v1/fiscal/contingencia/" + c.idEmpresaDaSessao()).header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/fiscal/config/" + c.idEmpresaDaSessao()).header("Authorization", auth))
                .andExpect(status().isOk());
    }
}
