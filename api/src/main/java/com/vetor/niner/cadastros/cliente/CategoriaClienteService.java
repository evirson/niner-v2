package com.vetor.niner.cadastros.cliente;

import com.vetor.niner.cadastros.cliente.CategoriaClienteDtos.CategoriaRequest;
import com.vetor.niner.cadastros.cliente.CategoriaClienteDtos.CategoriaResponse;
import com.vetor.niner.comum.web.ConflitoDadosException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD (criar/listar/renomear, sem exclusão — docs/telas/cliente.md) da categoria de
 * cliente. Tabela sob RLS de tenant (V016/V024); o INSERT usa {@code plataforma.tenant_atual()}
 * (contexto já estabelecido pelo {@code TenantAwareTransactionManager} a partir do JWT).
 */
@Service
public class CategoriaClienteService {

    private final JdbcClient jdbc;

    public CategoriaClienteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<CategoriaResponse> listar() {
        return jdbc.sql("""
                        SELECT id_categoria_cliente, nome_categoria
                        FROM cfg_categoria_cliente
                        ORDER BY nome_categoria
                        """)
                .query((rs, n) -> new CategoriaResponse(
                        rs.getLong("id_categoria_cliente"), rs.getString("nome_categoria")))
                .list();
    }

    @Transactional
    public CategoriaResponse criar(CategoriaRequest req) {
        String nome = req.nomeCategoria().trim().toUpperCase(Locale.ROOT);
        try {
            long id = jdbc.sql("""
                            INSERT INTO cfg_categoria_cliente (id_tenant, nome_categoria)
                            VALUES (plataforma.tenant_atual(), ?)
                            RETURNING id_categoria_cliente
                            """)
                    .param(nome)
                    .query(Long.class).single();
            return new CategoriaResponse(id, nome);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma categoria com esse nome.");
        }
    }

    @Transactional
    public CategoriaResponse renomear(long id, CategoriaRequest req) {
        String nome = req.nomeCategoria().trim().toUpperCase(Locale.ROOT);
        try {
            int linhas = jdbc.sql("UPDATE cfg_categoria_cliente SET nome_categoria = ? WHERE id_categoria_cliente = ?")
                    .params(nome, id)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Categoria não encontrada.");
            }
            return new CategoriaResponse(id, nome);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma categoria com esse nome.");
        }
    }
}
