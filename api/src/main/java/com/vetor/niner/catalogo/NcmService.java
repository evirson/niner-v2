package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.NcmDtos.NcmResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
                        SELECT codigo_ncm, descricao_ncm, alq_federal_nacional, alq_federal_importado,
                               alq_estadual, alq_municipal
                        FROM cfg_produto_ncm
                        WHERE codigo_ncm = ?
                        """)
                .param(codigo)
                .query((rs, n) -> new NcmResponse(
                        rs.getString("codigo_ncm"), rs.getString("descricao_ncm"),
                        rs.getBigDecimal("alq_federal_nacional"), rs.getBigDecimal("alq_federal_importado"),
                        rs.getBigDecimal("alq_estadual"), rs.getBigDecimal("alq_municipal")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "NCM não encontrado."));
    }

    /** Pesquisa por nome (2026-08-11, pedido do dono do produto: quem cadastra um produto nem
     *  sempre sabe o código de cabeça) — {@code ILIKE} por {@code descricao_ncm}, mesmo idioma
     *  de busca por nome já usado em {@code EtiquetaEmissaoService.buscarProdutos}/
     *  {@code buscarFornecedores}. Limitado a 30 linhas; sem termo, não busca (a tabela tem
     *  milhares de códigos oficiais).
     *
     *  <p>⚠️ <b>`unaccent` acrescentado em 2026-08-31, e era defeito de verdade:</b> <b>10.419</b>
     *  das 10.515 descrições da Receita têm acento, e o modal força MAIÚSCULAS — quem digitava
     *  "ALGODAO" recebia <i>"Nenhum NCM encontrado"</i> para um código que existe, desde que a
     *  busca por nome nasceu em 2026-08-11. Medido na tela; a extensão (V016) e o
     *  {@code unaccent_imutavel} já existiam no banco, então o conserto foi de uma linha. */
    @Transactional(readOnly = true)
    public List<NcmResponse> buscarPorNome(String busca) {
        if (busca == null || busca.isBlank()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT codigo_ncm, descricao_ncm, alq_federal_nacional, alq_federal_importado,
                               alq_estadual, alq_municipal
                        FROM cfg_produto_ncm
                        WHERE unaccent_imutavel(descricao_ncm) ILIKE unaccent_imutavel(?)
                        ORDER BY descricao_ncm
                        LIMIT 30
                        """)
                .param("%" + busca.trim() + "%")
                .query((rs, n) -> new NcmResponse(
                        rs.getString("codigo_ncm"), rs.getString("descricao_ncm"),
                        rs.getBigDecimal("alq_federal_nacional"), rs.getBigDecimal("alq_federal_importado"),
                        rs.getBigDecimal("alq_estadual"), rs.getBigDecimal("alq_municipal")))
                .list();
    }
}
