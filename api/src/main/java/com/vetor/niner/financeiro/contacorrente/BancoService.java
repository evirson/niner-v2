package com.vetor.niner.financeiro.contacorrente;

import com.vetor.niner.financeiro.contacorrente.BancoDtos.BancoResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Consulta de {@code cfg_banco} — tabela GLOBAL, sem {@code id_tenant}/RLS (P9): a mesma linha
 * vale para qualquer tenant, por isso não há {@code tenant_atual()} nesta consulta. Só leitura
 * — a tabela é mantida por script (sem tela/endpoint de escrita), mesmo padrão de {@code
 * catalogo.NcmService}.
 */
@Service
public class BancoService {

    private final JdbcClient jdbc;

    public BancoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public BancoResponse buscar(String codigo) {
        return jdbc.sql("""
                        SELECT codigo_banco, nome_banco
                        FROM cfg_banco
                        WHERE codigo_banco = ?
                        """)
                .param(codigo)
                .query((rs, n) -> new BancoResponse(rs.getString("codigo_banco"), rs.getString("nome_banco")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Banco não encontrado."));
    }
}
