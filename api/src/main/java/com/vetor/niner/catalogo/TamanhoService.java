package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.TamanhoDtos.TamanhoRequest;
import com.vetor.niner.catalogo.TamanhoDtos.TamanhoResponse;
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
 * CRUD (criar/listar/renomear, sem exclusão — mesmo padrão de {@code CategoriaProdutoService})
 * do tamanho ({@code cfg_tamanho}). Sem tela própria: criado embutido no modal "Gerenciar
 * Grades" da tela de Produto (docs/telas/produto.md). Tabela sob RLS de tenant (V017/V024).
 *
 * <p><b>id_tamanho=1 é o tamanho PADRÃO</b> (2026-08-20, ver {@code SignupService}) — reservado,
 * invisível, nunca listado/renomeável por aqui. {@code id_tamanho} não é mais {@code IDENTITY}
 * (V017): cada tenant tem sua própria numeração, calculada aqui como {@code MAX(id_tamanho)+1}.
 */
@Service
public class TamanhoService {

    private final JdbcClient jdbc;

    public TamanhoService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<TamanhoResponse> listar() {
        return jdbc.sql("""
                        SELECT id_tamanho, descricao FROM cfg_tamanho
                        WHERE id_tenant = plataforma.tenant_atual() AND id_tamanho <> 1
                        ORDER BY descricao
                        """)
                .query((rs, n) -> new TamanhoResponse(rs.getLong("id_tamanho"), rs.getString("descricao")))
                .list();
    }

    @Transactional
    public TamanhoResponse criar(TamanhoRequest req) {
        String descricao = req.descricao().trim().toUpperCase(Locale.ROOT);
        try {
            long id = jdbc.sql("""
                            INSERT INTO cfg_tamanho (id_tenant, id_tamanho, descricao)
                            VALUES (plataforma.tenant_atual(),
                                COALESCE((SELECT MAX(id_tamanho) FROM cfg_tamanho WHERE id_tenant = plataforma.tenant_atual()), 0) + 1,
                                ?)
                            RETURNING id_tamanho
                            """)
                    .param(descricao)
                    .query(Long.class).single();
            return new TamanhoResponse(id, descricao);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um tamanho com esse nome.");
        }
    }

    @Transactional
    public TamanhoResponse renomear(long id, TamanhoRequest req) {
        if (id == 1) {
            throw new ResponseStatusException(NOT_FOUND, "Tamanho não encontrado.");
        }
        String descricao = req.descricao().trim().toUpperCase(Locale.ROOT);
        try {
            int linhas = jdbc.sql("UPDATE cfg_tamanho SET descricao = ? WHERE id_tamanho = ? AND id_tenant = plataforma.tenant_atual()")
                    .params(descricao, id)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Tamanho não encontrado.");
            }
            return new TamanhoResponse(id, descricao);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe um tamanho com esse nome.");
        }
    }
}
