package com.vetor.niner.comum.ramo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Ramos de atividade do varejo (V072) — a lista curta que o lojista escolhe, com o CNAE por trás.
 *
 * <p><b>Por que existe.</b> Na contratação o sistema precisa saber o ramo da empresa que entra
 * para poder perguntar a coisa certa quando ela não bate com o ramo do tenant que o cliente já
 * tem (ver {@code docs/telas/ramo-atividade.md}).
 *
 * <p><b>Tabela global, sem RLS</b> — é referência igual para todos, como {@code cfg_produto_ncm}.
 * Por isso este serviço funciona também na superfície pública, onde não existe
 * {@code TenantContext}.
 */
@Service
public class RamoAtividadeService {

    private static final String SQL_LISTAR = """
            SELECT id_ramo, codigo, nome FROM cfg_ramo_atividade ORDER BY ordem
            """;

    /**
     * Ramo sugerido a partir da subclasse CNAE. Códigos genéricos (4789-0/99 e afins) não estão
     * no mapa de propósito — servem a dezenas de atividades, e sugerir a partir deles seria
     * chutar com cara de certeza.
     */
    private static final String SQL_POR_CNAE = """
            SELECT r.id_ramo, r.codigo, r.nome
              FROM cfg_ramo_cnae c
              JOIN cfg_ramo_atividade r ON r.id_ramo = c.id_ramo
             WHERE c.cnae = ?
            """;

    private final JdbcClient jdbc;

    public RamoAtividadeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Ramo> listar() {
        return jdbc.sql(SQL_LISTAR).query(RamoAtividadeService::mapear).list();
    }

    /** Vazio quando o CNAE não identifica um ramo — nesse caso a tela pergunta, sem palpite. */
    @Transactional(readOnly = true)
    public Optional<Ramo> sugerirPorCnae(String cnae) {
        String limpo = cnae == null ? "" : cnae.replaceAll("\\D", "");
        if (limpo.length() != 7) {
            return Optional.empty();
        }
        return jdbc.sql(SQL_POR_CNAE).param(limpo).query(RamoAtividadeService::mapear).optional();
    }

    /** {@code true} se o id existe — usado para validar o que vem do cliente antes de gravar. */
    @Transactional(readOnly = true)
    public boolean existe(Integer idRamo) {
        if (idRamo == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.sql("SELECT exists(SELECT 1 FROM cfg_ramo_atividade WHERE id_ramo = ?)")
                .param(idRamo).query(Boolean.class).single());
    }

    private static Ramo mapear(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Ramo(rs.getInt("id_ramo"), rs.getString("codigo"), rs.getString("nome"));
    }

    public record Ramo(int idRamo, String codigo, String nome) {
    }
}
