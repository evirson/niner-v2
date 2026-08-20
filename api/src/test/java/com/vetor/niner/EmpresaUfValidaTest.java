package com.vetor.niner;

import com.vetor.niner.comum.tempo.FusoDaUf;
import com.vetor.niner.fiscal.documento.ChaveAcesso;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code empresa.estado} só aceita UF válida (V049) — e as três listas de siglas do projeto não
 * podem divergir.
 *
 * <p><b>Por que isso virou regra de banco em 2026-08-20:</b> a UF deixou de ser só endereço
 * impresso e passou a decidir o <b>fuso da loja</b> ({@link FusoDaUf}) e <b>para qual SEFAZ</b> o
 * documento é transmitido. Sigla errada não falha no cadastro: falha na primeira venda do cliente,
 * com hora errada no cupom ou nota mandada para o autorizador de outro estado.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EmpresaUfValidaTest {

    private static final String[] UFS_DO_BRASIL = {
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA",
            "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"};

    @Autowired
    JdbcClient jdbc;

    /**
     * As 27 siglas vivem em três lugares — o CHECK do banco, {@link ChaveAcesso} (que monta a chave
     * de acesso) e {@link FusoDaUf} (que decide o fuso). Divergir faria uma aceitar o que a outra
     * recusa, e o sintoma seria em produção.
     */
    @Test
    void asTresListasDeUfDoProjetoConcordam() {
        for (String uf : UFS_DO_BRASIL) {
            assertThat(ChaveAcesso.codigoUfDe(uf)).as("cUF de %s", uf).isBetween(11, 53);
            assertThat(FusoDaUf.de(uf)).as("fuso de %s", uf).isNotNull();
            // O CHECK aceita a sigla: prova pelo caminho do banco, não por leitura do DDL.
            assertThat(bancoAceita(uf)).as("banco aceita %s", uf).isTrue();
        }
        assertThat(UFS_DO_BRASIL).hasSize(27);
    }

    @Test
    void bancoRecusaSiglaQueNaoExiste() {
        assertThat(bancoAceita("XX")).isFalse();
        assertThat(bancoAceita("pr")).as("minúscula: o serviço normaliza, o banco não").isFalse();
        assertThat(bancoAceita("PARANA")).isFalse();
    }

    /** NULL continua valendo: o signup cria a empresa sem UF e o lojista preenche depois. */
    @Test
    void ufVaziaContinuaPermitida() {
        assertThat(bancoAceita(null)).isTrue();
    }

    /**
     * O fuso de cada UF tem de ser um dos quatro do Brasil. Pega erro de digitação em identificador
     * IANA — {@code America/Manaos} compilaria e só explodiria em runtime, no cliente.
     */
    @Test
    void todaUfCaiNumDosQuatroFusosDoBrasil() {
        for (String uf : UFS_DO_BRASIL) {
            ZoneId fuso = FusoDaUf.de(uf);
            int offsetHoras = fuso.getRules().getStandardOffset(java.time.Instant.now()).getTotalSeconds() / 3600;
            assertThat(offsetHoras).as("offset de %s (%s)", uf, fuso).isBetween(-5, -2);
        }
        // As duas manchas do mapa que a decisão de 2026-08-20 deixou de fora, explicitamente:
        // Fernando de Noronha (UTC−2) recebe o fuso de Recife, e o oeste do AM recebe o de Manaus.
        assertThat(FusoDaUf.de("PE").getId()).isEqualTo("America/Recife");
        assertThat(FusoDaUf.de("AM").getId()).isEqualTo("America/Manaus");
        assertThat(FusoDaUf.de("AC").getId()).isEqualTo("America/Rio_Branco");
    }

    /**
     * Prova a regra <b>pelo banco</b>, não lendo o DDL: insere uma empresa descartável com a sigla e
     * vê se o CHECK deixa passar. Num tenant próprio, apagado ao fim de cada tentativa — o teste não
     * pode deixar lixo nem depender do que outro teste semeou.
     */
    private boolean bancoAceita(String uf) {
        long idTenant = tenantDescartavel();
        try {
            jdbc.sql("""
                            INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, estado, cfg_nome_etiqueta)
                            VALUES (?, 1, 'EMPRESA DE TESTE UF', ?, '{sku}')
                            """)
                    .params(idTenant, uf)
                    .update();
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        } finally {
            jdbc.sql("DELETE FROM empresa WHERE id_tenant = ?").param(idTenant).update();
        }
    }

    private Long idTenant;

    private long tenantDescartavel() {
        if (idTenant == null) {
            idTenant = jdbc.sql("""
                            INSERT INTO plataforma.tenant (nome_conta, slug, email_contato)
                            VALUES ('TESTE UF', 'teste-uf-check', 'teste-uf@exemplo.com.br')
                            ON CONFLICT (slug) DO UPDATE SET nome_conta = EXCLUDED.nome_conta
                            RETURNING id_tenant
                            """)
                    .query(Long.class).single();
        }
        return idTenant;
    }

    @Test
    void servicoRecusaUfInvalidaAntesDoBanco() {
        assertThatThrownBy(() -> FusoDaUf.de("XX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UF inválida");
    }
}
