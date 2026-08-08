package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.CategoriaProdutoDtos.CategoriaRequest;
import com.vetor.niner.catalogo.CategoriaProdutoDtos.CategoriaResponse;
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
 * CRUD (criar/listar/renomear, sem exclusão — mesma decisão da categoria de cliente) da
 * categoria de produto. Tabela sob RLS de tenant (V017/V024); o INSERT usa
 * {@code plataforma.tenant_atual()} (contexto já estabelecido pelo
 * {@code TenantAwareTransactionManager} a partir do JWT).
 *
 * <p><b>2026-08-08, achado real de teste:</b> uma query {@code SELECT}/{@code UPDATE} sem
 * nenhum parâmetro amarrado (bind) e sem filtro explícito de {@code id_tenant} — dependendo
 * 100% da política RLS pra isolar — pode devolver linhas de OUTRO tenant sob certas condições de
 * cache de plano do driver JDBC/Postgres (reproduzido de forma determinística com dois tenants
 * em sequência rápida). RLS continua sendo a garantia de fundo (P8), mas todo `SELECT`/`UPDATE`/
 * `DELETE` neste service agora também filtra {@code id_tenant = plataforma.tenant_atual()}
 * explicitamente, como defesa em profundidade — mesmo padrão já usado em {@code
 * ConfiguracaoGeralService} e outros. Mesmo achado provavelmente afeta outras tabelas com
 * `listar()` sem filtro explícito; fora do escopo desta mudança (cor/grade) auditar o
 * restante do código.
 */
@Service
public class CategoriaProdutoService {

    private final JdbcClient jdbc;

    public CategoriaProdutoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<CategoriaResponse> listar() {
        return jdbc.sql("""
                        SELECT id_categoria, nome_categoria
                        FROM cfg_categoria_produto
                        WHERE id_tenant = plataforma.tenant_atual()
                        ORDER BY nome_categoria
                        """)
                .query((rs, n) -> new CategoriaResponse(rs.getLong("id_categoria"), rs.getString("nome_categoria")))
                .list();
    }

    @Transactional
    public CategoriaResponse criar(CategoriaRequest req) {
        String nome = req.nomeCategoria().trim().toUpperCase(Locale.ROOT);
        try {
            long id = jdbc.sql("""
                            INSERT INTO cfg_categoria_produto (id_tenant, nome_categoria)
                            VALUES (plataforma.tenant_atual(), ?)
                            RETURNING id_categoria
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
            int linhas = jdbc.sql("""
                            UPDATE cfg_categoria_produto SET nome_categoria = ?
                            WHERE id_categoria = ? AND id_tenant = plataforma.tenant_atual()
                            """)
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
