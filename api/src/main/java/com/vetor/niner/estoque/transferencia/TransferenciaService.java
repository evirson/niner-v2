package com.vetor.niner.estoque.transferencia;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.CriarTransferenciaRequest;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.EmpresaResumo;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.ItemTransferenciaRequest;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.ItemTransferenciaResponse;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.PaginaTransferencias;
import com.vetor.niner.estoque.transferencia.TransferenciaDtos.TransferenciaResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Transferência de produtos entre empresas (docs/telas/transferencia-estoque.md). A empresa de
 * origem é sempre a empresa ativa da sessão (claim {@code eid} do JWT) — o operador só escolhe
 * o destino. Aberta a ADMIN e OPERADOR (operação do dia a dia de loja com mais de uma filial,
 * mesma decisão de produto de {@code cadastros.cliente}/PDV — não é sensível como
 * {@code identidade.usuario}).
 */
@Service
public class TransferenciaService {

    private static final int TAMANHO_PAGINA_PADRAO = 50;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    private final JdbcClient jdbc;

    public TransferenciaService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public TransferenciaResponse criar(Jwt jwt, CriarTransferenciaRequest req) {
        long idEmpresaOrigem = ((Number) jwt.getClaim("eid")).longValue();

        if (req.idEmpresaDestino() == idEmpresaOrigem) {
            throw new IllegalArgumentException("A empresa de destino precisa ser diferente da empresa de origem.");
        }
        exigirEmpresaAtiva(req.idEmpresaDestino());

        List<ItemResolvido> itens = resolverItens(req.itens(), idEmpresaOrigem);

        long idTransferencia = jdbc.sql("""
                        INSERT INTO produto_transferencia
                            (id_tenant, id_empresa_origem, id_empresa_destino, id_usuario, observacoes)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?)
                        RETURNING id_transferencia
                        """)
                .params(idEmpresaOrigem, req.idEmpresaDestino(), Long.parseLong(jwt.getSubject()), req.observacoes())
                .query(Long.class).single();

        long idMovimentoOrigem = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_transferencia)
                        VALUES (plataforma.tenant_atual(), ?, 'TRANSFERENCIA', ?)
                        RETURNING id_movimento
                        """)
                .params(idEmpresaOrigem, idTransferencia).query(Long.class).single();
        for (ItemResolvido item : itens) {
            inserirDetalhe(idMovimentoOrigem, idEmpresaOrigem, item.idVariacao(), "D", item.qtd());
        }

        long idMovimentoDestino = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_transferencia)
                        VALUES (plataforma.tenant_atual(), ?, 'TRANSFERENCIA', ?)
                        RETURNING id_movimento
                        """)
                .params(req.idEmpresaDestino(), idTransferencia).query(Long.class).single();
        for (ItemResolvido item : itens) {
            inserirDetalhe(idMovimentoDestino, req.idEmpresaDestino(), item.idVariacao(), "C", item.qtd());
        }

        return montar(idTransferencia);
    }

    @Transactional(readOnly = true)
    public PaginaTransferencias listar(Integer pagina, Integer limite) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);

        long totalItens = jdbc.sql("SELECT count(*) FROM produto_transferencia WHERE id_tenant = plataforma.tenant_atual()")
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Long> ids = jdbc.sql("""
                        SELECT id_transferencia FROM produto_transferencia
                        WHERE id_tenant = plataforma.tenant_atual()
                        ORDER BY data_transferencia DESC, id_transferencia DESC
                        LIMIT ? OFFSET ?
                        """)
                .params((long) tamanho, (long) (paginaAtual - 1) * tamanho)
                .query(Long.class).list();

        List<TransferenciaResponse> itens = ids.stream().map(this::montar).toList();
        return new PaginaTransferencias(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public TransferenciaResponse buscar(long id) {
        return montar(id);
    }

    private void inserirDetalhe(long idMovimento, long idEmpresa, long idVariacao, String creditoDebito, BigDecimal qtd) {
        // credito_debito é um ENUM do Postgres — precisa de cast explícito, o driver não
        // converte um bind param de String sozinho (diferente de um literal 'D'/'C' na SQL,
        // que o Postgres já infere pelo tipo da coluna — por isso aqui não dá pra ser literal
        // porque o valor varia por chamada, origem 'D' × destino 'C').
        jdbc.sql("""
                        INSERT INTO produto_movimento_detalhe
                            (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto, origem)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?::credito_debito, ?, 'transferência entre empresas')
                        """)
                .params(idMovimento, idEmpresa, idVariacao, creditoDebito, qtd)
                .update();
    }

    private void exigirEmpresaAtiva(long idEmpresa) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM empresa
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND ativo = true)
                        """)
                .param(idEmpresa).query(Boolean.class).single());
        if (!existe) {
            throw new IllegalArgumentException("Empresa de destino inválida ou inativa.");
        }
    }

    private record ItemResolvido(long idVariacao, BigDecimal qtd) {
    }

    /**
     * Valida cada item (variação existe e está ativa) e checa saldo disponível na empresa de
     * origem **antes** de qualquer INSERT — {@code produto_estoque} não tem CHECK contra saldo
     * negativo (mesmo motivo de {@code PdvVendaService.resolverItens}, P1).
     */
    private List<ItemResolvido> resolverItens(List<ItemTransferenciaRequest> itens, long idEmpresaOrigem) {
        List<ItemResolvido> resolvidos = new ArrayList<>();
        for (ItemTransferenciaRequest item : itens) {
            var linha = jdbc.sql("""
                            SELECT p.descricao AS descricao_produto,
                                   COALESCE(pe.qtd_estoque, 0) - COALESCE(pe.reservado, 0) AS disponivel
                            FROM produto_barra pb
                            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                            LEFT JOIN produto_estoque pe
                                   ON pe.id_variacao = pb.id_variacao AND pe.id_empresa = ? AND pe.id_tenant = pb.id_tenant
                            WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ? AND p.ativo = true
                            """)
                    .params(idEmpresaOrigem, item.idVariacao())
                    .query((rs, n) -> new Object[]{rs.getString("descricao_produto"), rs.getBigDecimal("disponivel")})
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Produto não encontrado para a variação informada."));

            String descricao = (String) linha[0];
            BigDecimal disponivel = (BigDecimal) linha[1];
            if (disponivel.compareTo(item.qtd()) < 0) {
                throw new ConflitoDadosException(
                        "Estoque insuficiente para " + descricao + " na empresa de origem (disponível: " + disponivel + ").");
            }
            resolvidos.add(new ItemResolvido(item.idVariacao(), item.qtd()));
        }
        return resolvidos;
    }

    private TransferenciaResponse montar(long idTransferencia) {
        record Cabecalho(long idTransferencia, EmpresaResumo origem, EmpresaResumo destino, String nomeUsuario,
                          OffsetDateTime data, String observacoes) {
        }

        Cabecalho cabecalho = jdbc.sql("""
                        SELECT t.id_transferencia, t.observacoes, t.data_transferencia,
                               eo.id_empresa AS id_empresa_origem,
                               COALESCE(eo.nome_fantasia, eo.razao_social) AS nome_origem,
                               ed.id_empresa AS id_empresa_destino,
                               COALESCE(ed.nome_fantasia, ed.razao_social) AS nome_destino,
                               u.nome_usuario
                        FROM produto_transferencia t
                        JOIN empresa eo ON eo.id_empresa = t.id_empresa_origem AND eo.id_tenant = t.id_tenant
                        JOIN empresa ed ON ed.id_empresa = t.id_empresa_destino AND ed.id_tenant = t.id_tenant
                        JOIN usuario u ON u.id_usuario = t.id_usuario AND u.id_tenant = t.id_tenant
                        WHERE t.id_transferencia = ? AND t.id_tenant = plataforma.tenant_atual()
                        """)
                .param(idTransferencia)
                .query((rs, n) -> new Cabecalho(
                        rs.getLong("id_transferencia"),
                        new EmpresaResumo(rs.getLong("id_empresa_origem"), rs.getString("nome_origem")),
                        new EmpresaResumo(rs.getLong("id_empresa_destino"), rs.getString("nome_destino")),
                        rs.getString("nome_usuario"),
                        rs.getObject("data_transferencia", OffsetDateTime.class),
                        rs.getString("observacoes")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transferência não encontrada."));

        List<ItemTransferenciaResponse> itens = jdbc.sql("""
                        SELECT pb.id_variacao, p.descricao AS descricao_produto, vl.descricao AS variacao_linha,
                               vc.descricao AS variacao_coluna, pb.sku, pmd.qtd_produto
                        FROM produto_movimento_mestre pm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pm.id_movimento AND pmd.id_tenant = pm.id_tenant
                        JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                        JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_variante_linha vl
                               ON vl.id_variante_linha = pb.id_variante_linha AND vl.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_variante_coluna vc
                               ON vc.id_variante_coluna = pb.id_variante_coluna AND vc.id_tenant = pb.id_tenant
                        WHERE pm.id_tenant = plataforma.tenant_atual() AND pm.id_transferencia = ?
                              AND pmd.credito_debito = 'D'
                        ORDER BY pmd.id_movimento_detalhe ASC
                        """)
                .param(idTransferencia)
                .query(TransferenciaService::mapearItem)
                .list();

        return new TransferenciaResponse(cabecalho.idTransferencia(), cabecalho.origem(), cabecalho.destino(),
                cabecalho.nomeUsuario(), cabecalho.data(), cabecalho.observacoes(), itens);
    }

    private static ItemTransferenciaResponse mapearItem(ResultSet rs, int rowNum) throws SQLException {
        return new ItemTransferenciaResponse(
                rs.getLong("id_variacao"), rs.getString("descricao_produto"), rs.getString("variacao_linha"),
                rs.getString("variacao_coluna"), rs.getString("sku"), rs.getBigDecimal("qtd_produto"));
    }
}
