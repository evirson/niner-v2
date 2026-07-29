package com.vetor.niner.vendas;

import com.vetor.niner.vendas.PdvDtos.PdvClienteResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Busca de cliente pro PDV (F6, 2026-07-28) — por nome, CPF/CNPJ ou celular
 * (`cliente.telefone`, rotulado "Celular" na tela de Cliente). Só cliente ativo. Mesmo padrão
 * de {@link PdvProdutoService#buscar}: filtro único, LIMIT {@value #LIMITE_BUSCA}, filtro por
 * {@code id_tenant} explícito além do RLS (Testcontainers conecta como superusuário).
 */
@Service
public class PdvClienteService {

    private static final int LIMITE_BUSCA = 20;

    private final JdbcClient jdbc;

    public PdvClienteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<PdvClienteResponse> buscar(String busca) {
        String filtro = busca == null || busca.isBlank() ? "%" : "%" + busca.trim().toUpperCase(Locale.ROOT) + "%";
        return jdbc.sql("""
                        SELECT id_cliente, nome, cpf_cnpj, telefone
                        FROM cliente
                        WHERE id_tenant = plataforma.tenant_atual() AND ativo = true
                          AND (nome ILIKE ? OR cpf_cnpj ILIKE ? OR telefone ILIKE ?)
                        ORDER BY nome ASC
                        LIMIT ?
                        """)
                .params(filtro, filtro, filtro, LIMITE_BUSCA)
                .query((rs, n) -> new PdvClienteResponse(
                        rs.getLong("id_cliente"), rs.getString("nome"), rs.getString("cpf_cnpj"), rs.getString("telefone")))
                .list();
    }
}
