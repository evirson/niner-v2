package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.fiscal.documento.DocumentoFiscalRepositorio;
import com.vetor.niner.fiscal.documento.DocumentoFiscalRepositorio.EmpresaEmContingencia;
import com.vetor.niner.fiscal.documento.FiscalContingenciaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contingência offline da NFC-e (docs/MODULOFISCAL.md §9.7, DF19).
 *
 * <p>O que estes casos protegem: <b>uma falha isolada não pode jogar a loja em contingência</b> (a
 * SEFAZ oscila, e contingência tem custo: fila, prazo de 24 h correndo, cupom que o consumidor não
 * consegue consultar na hora), e <b>duas falhas seguidas têm que jogar</b> — senão o caixa fica
 * esperando 10 s de timeout em cada venda enquanto a SEFAZ está fora.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FiscalContingenciaTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FiscalContingenciaService contingencia;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DocumentoFiscalRepositorio documentoFiscalRepositorio;

    // ---------------------------------------------------------------- helpers

    private record Sessao(long idTenant, long idEmpresa) {
    }

    private Sessao assinarNovoTenant(String sufixo) throws Exception {
        String body = """
                {"nomeLoja":"Loja Conting %s","email":"dono%s@lojacont.com",
                 "senha":"segredo123","nomeAdmin":"Dono da Loja"}
                """.formatted(sufixo, sufixo);
        String resp = mvc.perform(post("/api/publico/assinar").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(resp, "$.token");
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        Sessao s = new Sessao(((Number) JsonPath.read(payload, "$.tid")).longValue(),
                ((Number) JsonPath.read(payload, "$.eid")).longValue());

        // O signup não semeia fiscal_config_empresa: a ausência significa "fiscal desligado" (F12).
        // A contingência precisa da linha, então este teste a cria explicitamente.
        //
        // ⚠️ `id_tenant` vai como parâmetro, não como plataforma.tenant_atual(): este INSERT roda
        // fora de transação (auto-commit), e é o doBegin do TenantAwareTransactionManager que
        // define `app.id_tenant`. Sem transação a função devolve null e o NOT NULL barra.
        jdbc.sql("""
                        INSERT INTO fiscal_config_empresa (id_tenant, id_empresa, crt, ambiente, emite_nfce)
                        VALUES (?, ?, 1, 'HOMOLOGACAO', true)
                        """).params(s.idTenant(), s.idEmpresa()).update();
        return s;
    }

    // ---------------------------------------------------------------- casos

    @Test
    void empresaNasceForaDeContingencia() throws Exception {
        Sessao s = assinarNovoTenant("nasce");

        TenantContext.comTenant(s.idTenant(), () -> {
            FiscalContingenciaService.Estado estado = contingencia.consultar(s.idEmpresa());
            assertThat(estado.ativa()).isFalse();
            assertThat(estado.pendentes()).isZero();
            assertThat(estado.serieContingencia()).as("DF33: série 9 separada da série 1 normal").isEqualTo(9);
        });
    }

    /**
     * DF19 — <b>uma</b> falha não entra em contingência. É a metade mais importante da regra: a
     * SEFAZ oscila o dia inteiro, e entrar por qualquer soluço encheria a fila sem necessidade.
     */
    @Test
    void umaFalhaIsoladaNaoEntraEmContingencia() throws Exception {
        Sessao s = assinarNovoTenant("umafalha");

        TenantContext.comTenant(s.idTenant(), () -> {
            boolean entrou = contingencia.registrarFalhaEavaliarEntrada(
                    s.idTenant(), s.idEmpresa(), "timeout");

            assertThat(entrou).isFalse();
            assertThat(contingencia.ativa(s.idEmpresa())).isFalse();
        });
    }

    /** DF19 — a segunda falha dentro da janela entra, e a entrada fica registrada (P3). */
    @Test
    void duasFalhasSeguidasEntramEmContingenciaComJustificativa() throws Exception {
        Sessao s = assinarNovoTenant("duasfalhas");

        TenantContext.comTenant(s.idTenant(), () -> {
            contingencia.registrarFalhaEavaliarEntrada(s.idTenant(), s.idEmpresa(), "timeout");
            boolean entrou = contingencia.registrarFalhaEavaliarEntrada(
                    s.idTenant(), s.idEmpresa(), "connect timed out");

            assertThat(entrou).as("a transição desligada → ligada é avisada uma vez só").isTrue();

            FiscalContingenciaService.Estado estado = contingencia.consultar(s.idEmpresa());
            assertThat(estado.ativa()).isTrue();
            assertThat(estado.desde()).isNotNull();
            assertThat(estado.justificativa())
                    .contains("Automática")
                    .contains("connect timed out");
        });
    }

    /** Já em contingência, novas falhas não repetem o aviso — senão o caixa vê o alerta em toda venda. */
    @Test
    void terceiraFalhaNaoAvisaDeNovo() throws Exception {
        Sessao s = assinarNovoTenant("terceira");

        TenantContext.comTenant(s.idTenant(), () -> {
            contingencia.registrarFalhaEavaliarEntrada(s.idTenant(), s.idEmpresa(), "timeout");
            contingencia.registrarFalhaEavaliarEntrada(s.idTenant(), s.idEmpresa(), "timeout");

            assertThat(contingencia.registrarFalhaEavaliarEntrada(s.idTenant(), s.idEmpresa(), "timeout"))
                    .isFalse();
            assertThat(contingencia.ativa(s.idEmpresa())).isTrue();
        });
    }

    /**
     * Um sucesso no meio <b>quebra a sequência</b>: falha → sucesso → falha não é padrão de queda,
     * é oscilação. Sem esta reinicialização, qualquer empresa acabaria em contingência depois de
     * duas falhas espalhadas por um dia inteiro.
     */
    @Test
    void sucessoNoMeioZeraOContador() throws Exception {
        Sessao s = assinarNovoTenant("zera");

        TenantContext.comTenant(s.idTenant(), () -> {
            contingencia.registrarFalhaEavaliarEntrada(s.idTenant(), s.idEmpresa(), "timeout");
            contingencia.registrarSucesso(s.idTenant(), s.idEmpresa());

            assertThat(contingencia.registrarFalhaEavaliarEntrada(s.idTenant(), s.idEmpresa(), "timeout"))
                    .as("depois do sucesso, esta volta a ser a PRIMEIRA falha").isFalse();
            assertThat(contingencia.ativa(s.idEmpresa())).isFalse();
        });
    }

    /**
     * A contagem de falhas é por empresa. Se fosse global, a queda da SEFAZ para um tenant
     * colocaria <b>todos</b> os outros em contingência — inclusive quem está em outra UF, com
     * outro autorizador, funcionando normalmente (P8 vale até para cache em memória).
     */
    @Test
    void falhaDeUmTenantNaoContaminaOutro() throws Exception {
        Sessao a = assinarNovoTenant("cont-a");
        Sessao b = assinarNovoTenant("cont-b");

        TenantContext.comTenant(a.idTenant(), () -> {
            contingencia.registrarFalhaEavaliarEntrada(a.idTenant(), a.idEmpresa(), "timeout");
            contingencia.registrarFalhaEavaliarEntrada(a.idTenant(), a.idEmpresa(), "timeout");
            assertThat(contingencia.ativa(a.idEmpresa())).isTrue();
        });

        TenantContext.comTenant(b.idTenant(), () -> {
            assertThat(contingencia.ativa(b.idEmpresa()))
                    .as("a queda no tenant A não põe o tenant B em contingência").isFalse();
            assertThat(contingencia.registrarFalhaEavaliarEntrada(b.idTenant(), b.idEmpresa(), "timeout"))
                    .as("o contador de B começa do zero").isFalse();
        });
    }

    /**
     * Sair da contingência <b>preserva</b> {@code contingencia_desde}: é esse horário que sustenta
     * o prazo de transmissão posterior (24 h no PR) e é a primeira coisa que o fisco pergunta.
     * Limpar junto com o flag apagaria a prova de quando a janela começou.
     */
    @Test
    void sairPreservaOHorarioDeEntrada() throws Exception {
        Sessao s = assinarNovoTenant("sair");

        TenantContext.comTenant(s.idTenant(), () -> {
            contingencia.entrar(s.idEmpresa(), "Manual: internet caiu");
            var desde = contingencia.consultar(s.idEmpresa()).desde();
            assertThat(desde).isNotNull();

            contingencia.sair(s.idEmpresa(), "Manual: internet voltou");

            FiscalContingenciaService.Estado depois = contingencia.consultar(s.idEmpresa());
            assertThat(depois.ativa()).isFalse();
            assertThat(depois.desde()).as("o histórico da janela não se apaga ao sair").isEqualTo(desde);
        });
    }

    /** Entrar duas vezes não reinicia o relógio — senão o prazo legal se estenderia sozinho. */
    @Test
    void entrarDeNovoNaoReiniciaORelogio() throws Exception {
        Sessao s = assinarNovoTenant("reentra");

        TenantContext.comTenant(s.idTenant(), () -> {
            contingencia.entrar(s.idEmpresa(), "Manual: primeira");
            var desde = contingencia.consultar(s.idEmpresa()).desde();

            contingencia.entrar(s.idEmpresa(), "Manual: segunda");

            assertThat(contingencia.consultar(s.idEmpresa()).desde())
                    .as("já estando em contingência, o horário de início não se move").isEqualTo(desde);
        });
    }

    /**
     * Regressão do job de drenagem: {@code cfg_uf_autorizador.ambiente} é {@code smallint} (1/2) e
     * {@code fiscal_config_empresa.ambiente} é o enum {@code ambiente_fiscal} — comparar os dois
     * direto no {@code JOIN} falhava em runtime com "operator does not exist: smallint =
     * ambiente_fiscal", nunca pego porque nenhum teste chamava
     * {@link DocumentoFiscalRepositorio#empresasComFilaPendente()} de verdade.
     */
    @Test
    void filaDePendentesEncontraEmpresaComNotaEmContingencia() throws Exception {
        Sessao s = assinarNovoTenant("fila-pendente");
        jdbc.sql("UPDATE empresa SET estado = 'PR' WHERE id_tenant = ? AND id_empresa = ?")
                .params(s.idTenant(), s.idEmpresa()).update();
        jdbc.sql("""
                        INSERT INTO fiscal_certificado (id_tenant, id_empresa, arquivo_cifrado, senha_cifrada, ativo)
                        VALUES (?, ?, '\\x00'::bytea, 'x', true)
                        """)
                .params(s.idTenant(), s.idEmpresa()).update();
        jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, serie, numero, chave_acesso, codigo_numerico,
                            digito_verificador, tipo_operacao, situacao, ambiente, tipo_emissao,
                            data_emissao, valor_produtos, valor_desconto, valor_outros, valor_total,
                            valor_troco, xml_assinado)
                        VALUES (?, ?, 65, 9, 1, ?, '12345678', 9, 'VENDA_CONSUMIDOR',
                                'ASSINADO'::situacao_documento_fiscal, 'HOMOLOGACAO', 9, now(),
                                10.00, 0, 0, 10.00, 0, '<NFe/>')
                        """)
                .params(s.idTenant(), s.idEmpresa(), "41260837829453000135650090000000011123456789")
                .update();

        List<EmpresaEmContingencia> fila = TenantContext.comTenant(s.idTenant(),
                () -> documentoFiscalRepositorio.empresasComFilaPendente());

        assertThat(fila).anySatisfy(e -> {
            assertThat(e.idEmpresa()).isEqualTo(s.idEmpresa());
            assertThat(e.uf()).isEqualTo("PR");
            assertThat(e.ambienteCodigo()).as("HOMOLOGACAO mapeia pro código 2 em cfg_uf_autorizador")
                    .isEqualTo(2);
        });
    }
}
