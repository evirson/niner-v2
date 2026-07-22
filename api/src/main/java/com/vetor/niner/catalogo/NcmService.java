package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.NcmDtos.NcmResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Consulta de {@code cfg_produto_ncm} — tabela GLOBAL, sem {@code id_tenant}/RLS (P9): a
 * mesma linha vale para qualquer tenant, por isso não há {@code tenant_atual()} nesta
 * consulta. Só leitura — a tabela é mantida por script (sem tela/endpoint de escrita).
 */
@Service
public class NcmService {

    private final JdbcClient jdbc;

    public NcmService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public NcmResponse buscar(String codigo) {
        return jdbc.sql("""
                        SELECT codigo_ncm, descricao_ncm, aliquota_ibpt
                        FROM cfg_produto_ncm
                        WHERE codigo_ncm = ?
                        """)
                .param(codigo)
                .query((rs, n) -> new NcmResponse(
                        rs.getString("codigo_ncm"), rs.getString("descricao_ncm"), rs.getBigDecimal("aliquota_ibpt")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "NCM não encontrado."));
    }
}
