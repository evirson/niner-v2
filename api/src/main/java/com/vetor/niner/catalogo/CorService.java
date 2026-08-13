package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.CorDtos.CorRequest;
import com.vetor.niner.catalogo.CorDtos.CorResponse;
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
 * da cor ({@code cfg_cor}). Sem tela própria: criada embutida na Emissão de Etiqueta
 * ("+ Nova cor") — a Entrada de Produtos, onde cor nasceria de verdade (na compra), ainda não
 * existe (docs/telas/entrada-mercadoria.md, rascunho). Tabela sob RLS de tenant (V017/V024).
 *
 * <p><b>id_cor=1 é a cor PADRÃO</b> (2026-08-20, ver {@code SignupService}) — reservada,
 * invisível, nunca listada/renomeável por aqui. {@code id_cor} não é mais {@code IDENTITY}
 * (V017): cada tenant tem sua própria numeração, calculada aqui como {@code MAX(id_cor)+1}.
 */
@Service
public class CorService {

    private final JdbcClient jdbc;

    public CorService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<CorResponse> listar() {
        return jdbc.sql("""
                        SELECT id_cor, descricao FROM cfg_cor
                        WHERE id_tenant = plataforma.tenant_atual() AND id_cor <> 1
                        ORDER BY descricao
                        """)
                .query((rs, n) -> new CorResponse(rs.getLong("id_cor"), rs.getString("descricao")))
                .list();
    }

    @Transactional
    public CorResponse criar(CorRequest req) {
        String descricao = req.descricao().trim().toUpperCase(Locale.ROOT);
        try {
            long id = jdbc.sql("""
                            INSERT INTO cfg_cor (id_tenant, id_cor, descricao)
                            VALUES (plataforma.tenant_atual(),
                                COALESCE((SELECT MAX(id_cor) FROM cfg_cor WHERE id_tenant = plataforma.tenant_atual()), 0) + 1,
                                ?)
                            RETURNING id_cor
                            """)
                    .param(descricao)
                    .query(Long.class).single();
            return new CorResponse(id, descricao);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma cor com esse nome.");
        }
    }

    @Transactional
    public CorResponse renomear(long id, CorRequest req) {
        if (id == 1) {
            throw new ResponseStatusException(NOT_FOUND, "Cor não encontrada.");
        }
        String descricao = req.descricao().trim().toUpperCase(Locale.ROOT);
        try {
            int linhas = jdbc.sql("UPDATE cfg_cor SET descricao = ? WHERE id_cor = ? AND id_tenant = plataforma.tenant_atual()")
                    .params(descricao, id)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Cor não encontrada.");
            }
            return new CorResponse(id, descricao);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma cor com esse nome.");
        }
    }
}
