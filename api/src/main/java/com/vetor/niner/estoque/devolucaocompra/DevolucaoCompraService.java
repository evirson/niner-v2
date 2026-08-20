package com.vetor.niner.estoque.devolucaocompra;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.CancelarDevolucaoCompraRequest;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.DevolucaoCompraCanceladaResponse;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.DevolucaoCompraEfetivadaResponse;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.EfetivarDevolucaoCompraRequest;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.EntradaElegivelResponse;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.ItemDevolucaoCompraRequest;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.ItemDevolvidoResponse;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.ItemDevolvivelResponse;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.PaginaEntradasElegiveis;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Devolução de Produtos Comprados — devolve mercadoria ao fornecedor de origem (desacordo
 * comercial, defeito, ou outro motivo), dá baixa no estoque e habilita a emissão da NF-e de
 * devolução (modelo 55, <b>saída</b>).
 *
 * <h2>O que esta rotina NÃO faz, por decisão do dono do produto (2026-08-20)</h2>
 *
 * <p><b>Não toca no financeiro.</b> Devolver mercadoria normalmente gera crédito com o fornecedor,
 * e a entrada pode ter gerado duplicatas em {@code contas_pagar} — nada disso é mexido aqui: o
 * lojista negocia o crédito por fora. É o que dispensou uma tabela-cabeçalho (a devolução do
 * consumidor tem {@code venda_devolucao} porque gera vale-mercadoria; aqui não há equivalente) e
 * fez a operação <b>ser</b> o movimento de estoque.
 *
 * <h2>Os dois limites de quantidade, e por que são dois</h2>
 *
 * <ol>
 *   <li><b>Saldo da nota</b> — não se devolve mais do que aquela entrada trouxe, descontando o que
 *       já voltou ({@code vw_entrada_saldo_devolucao});</li>
 *   <li><b>Estoque atual</b> — decisão explícita do dono do produto: <b>só devolve o que ainda está
 *       em estoque</b>. Se a mercadoria já foi vendida, não há o que mandar de volta.</li>
 * </ol>
 *
 * <p>⚠️ O segundo <b>não passa pelo parâmetro</b> {@code cfg_permite_estoque_negativo} (V054), que
 * desde 2026-08-20 decide se as demais rotinas podem deixar saldo negativo. A regra daqui é mais
 * estreita e vale <b>sempre</b>: mesmo com o parâmetro ligado, não se devolve o que não está na
 * loja. <b>Não uniformize isto</b> — a diferença entre vender e devolver é de natureza, não de
 * rigor (regra do dono do produto, 2026-08-20):
 *
 * <ul>
 *   <li><b>Saída para o cliente (PDV):</b> o produto está na mão do operador, na frente dele. Se o
 *       estoque diz zero, quem está errado é o <b>cadastro</b> — travar a venda por causa de um
 *       número desatualizado custaria a venda de um produto que existe. Vender vence, e o negativo
 *       fica como sintoma de que falta acertar o estoque.</li>
 *   <li><b>Devolução ao fornecedor:</b> ninguém tem a mercadoria na mão para conferir, e o sistema
 *       vai <b>emitir uma NF-e declarando que ela está saindo</b>. Se o estoque diz zero, a
 *       hipótese mais provável é que a peça já foi vendida — e a nota afirmaria à SEFAZ uma saída
 *       física que não aconteceu, com o fornecedor esperando uma caixa que nunca chega.</li>
 * </ul>
 *
 * <p>A diferença não é de rigor, é de <b>o que a operação afirma</b>: a venda registra um fato que
 * o operador está vendo; a devolução declara um fato que ninguém conferiu.
 *
 * <h2>Elegibilidade — e o limite duro que ela impõe</h2>
 *
 * <p>Só é devolvível a entrada que tenha <b>XML arquivado</b> e <b>tributação por item gravada</b>
 * ({@code entrada_nfe_item}), porque a nota de devolução precisa espelhar a de origem. As duas
 * coisas passaram a existir em 2026-08-20 (V051), então <b>entrada anterior a essa data não é
 * devolvível</b>, e entrada manual/planilha <b>nunca</b> será — não tem nota de origem. É o mesmo
 * limite que o B9 impôs às vendas antigas.
 */
@Service
public class DevolucaoCompraService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    /**
     * Entrada elegível: COMPRA não cancelada, com XML guardado (no bucket ou ainda na coluna) e com
     * a tributação por item gravada. As três condições juntas — não basta ter o XML se não houver
     * de onde espelhar a tributação.
     */
    private static final String ENTRADA_ELEGIVEL = """
            FROM produto_movimento_mestre m
            JOIN empresa e ON e.id_tenant = m.id_tenant AND e.id_empresa = m.id_empresa
            LEFT JOIN fornecedor f ON f.id_tenant = m.id_tenant AND f.id_fornecedor = m.id_fornecedor
            JOIN entrada_xml ex ON ex.id_tenant = m.id_tenant AND ex.id_movimento = m.id_movimento
            WHERE m.id_tenant = plataforma.tenant_atual()
              AND m.tipo_movimento = 'COMPRA' AND m.cancelado = false
              AND (ex.xml_objeto_bucket IS NOT NULL OR ex.xml_bruto IS NOT NULL)
              AND EXISTS (SELECT 1 FROM entrada_nfe_item i
                           WHERE i.id_tenant = m.id_tenant AND i.id_movimento = m.id_movimento)
            """;

    private final JdbcClient jdbc;

    /**
     * Lê uma coluna {@code integer} anulável como {@code Long}.
     *
     * <p>⚠️ {@code rs.getObject(coluna, Long.class)} <b>não serve</b>: o driver do Postgres recusa
     * com "conversion to class java.lang.Long from int4 not supported", e o erro chega ao
     * controller como {@code DataIntegrityViolationException} — que o handler global traduz para
     * <i>"Registro em uso por outro cadastro"</i>. Ou seja: um erro de leitura vira uma mensagem
     * sobre exclusão, e o diagnóstico vai para o lado errado.
     */
    private static Long longOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    public DevolucaoCompraService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grid do popup de filtros: fornecedor, empresa, nota fiscal e período de entrada — todos
     * opcionais e combináveis.
     *
     * <p>⚠️ Período comparado no fuso da LOJA, nunca no do banco (a sessão roda em UTC e o dia
     * viraria às 21:00 de Brasília) — ver `CLAUDE.md`.
     */
    @Transactional(readOnly = true)
    public PaginaEntradasElegiveis listarEntradas(Long idFornecedor, Long idEmpresa, Integer notaFiscal,
                                                   LocalDate dataInicial, LocalDate dataFinal,
                                                   Integer pagina, Integer limite) {
        StringBuilder filtro = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (idFornecedor != null) {
            filtro.append(" AND m.id_fornecedor = ?");
            params.add(idFornecedor);
        }
        if (idEmpresa != null) {
            filtro.append(" AND m.id_empresa = ?");
            params.add(idEmpresa);
        }
        if (notaFiscal != null) {
            filtro.append(" AND m.nota_fiscal = ?");
            params.add(notaFiscal);
        }
        if (dataInicial != null) {
            filtro.append(" AND (m.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            params.add(dataInicial);
        }
        if (dataFinal != null) {
            filtro.append(" AND (m.data_movimento AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            params.add(dataFinal);
        }

        long total = jdbc.sql("SELECT count(*) " + ENTRADA_ELEGIVEL + filtro)
                .params(params).query(Long.class).single();

        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaEfetiva = pagina == null || pagina < 1 ? 1 : pagina;
        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add(tamanho);
        paramsPagina.add((long) (paginaEfetiva - 1) * tamanho);

        List<EntradaElegivelResponse> itens = jdbc.sql("""
                        SELECT m.id_movimento, m.data_movimento, m.id_empresa,
                               COALESCE(e.nome_fantasia, e.razao_social) AS nome_empresa,
                               m.id_fornecedor, f.razao_social AS nome_fornecedor, f.cnpj AS cnpj_fornecedor,
                               m.nota_fiscal, m.serie_nota, m.chave_nfe,
                               (SELECT COALESCE(SUM(d.qtd_produto * d.preco_custo), 0)
                                  FROM produto_movimento_detalhe d
                                 WHERE d.id_tenant = m.id_tenant AND d.id_movimento = m.id_movimento) AS valor_total,
                               (SELECT count(*) FROM produto_movimento_detalhe d
                                 WHERE d.id_tenant = m.id_tenant AND d.id_movimento = m.id_movimento) AS qtd_itens,
                               EXISTS (SELECT 1 FROM produto_movimento_mestre dv
                                        WHERE dv.id_tenant = m.id_tenant AND dv.id_movimento_origem = m.id_movimento
                                          AND dv.tipo_movimento = 'DEVOLUCAO_COMPRA' AND dv.cancelado = false) AS tem_devolucao
                        """ + ENTRADA_ELEGIVEL + filtro
                        + " ORDER BY m.data_movimento DESC, m.id_movimento DESC LIMIT ? OFFSET ?")
                .params(paramsPagina)
                .query((rs, n) -> new EntradaElegivelResponse(
                        rs.getLong("id_movimento"),
                        rs.getObject("data_movimento", OffsetDateTime.class),
                        rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                        longOuNulo(rs, "id_fornecedor"), rs.getString("nome_fornecedor"),
                        rs.getString("cnpj_fornecedor"),
                        (Integer) rs.getObject("nota_fiscal", Integer.class),
                        (Integer) rs.getObject("serie_nota", Integer.class),
                        rs.getString("chave_nfe"),
                        rs.getBigDecimal("valor_total"), rs.getInt("qtd_itens"),
                        rs.getBoolean("tem_devolucao")))
                .list();

        return new PaginaEntradasElegiveis(itens, paginaEfetiva, tamanho, total);
    }

    /**
     * Segunda grid: o que desta entrada ainda pode voltar. Só devolve linha com
     * {@code qtdMaxima > 0} — item cujo saldo acabou ou que não está mais em estoque não tem por
     * que aparecer para ser selecionado.
     */
    @Transactional(readOnly = true)
    public List<ItemDevolvivelResponse> itensDevolviveis(long idMovimento) {
        exigirEntradaElegivel(idMovimento);
        return jdbc.sql("""
                        SELECT s.id_variacao, pb.sku, p.descricao,
                               co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                               s.qtd_comprada, s.qtd_devolvida, s.qtd_saldo,
                               COALESCE(pe.qtd_estoque, 0) AS qtd_estoque,
                               LEAST(s.qtd_saldo, COALESCE(pe.qtd_estoque, 0)) AS qtd_maxima,
                               ni.valor_unitario, ni.cfop AS cfop_entrada, ni.codigo_produto
                          FROM vw_entrada_saldo_devolucao s
                          JOIN produto_barra pb ON pb.id_tenant = s.id_tenant AND pb.id_variacao = s.id_variacao
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          LEFT JOIN cfg_cor co ON co.id_tenant = pb.id_tenant AND co.id_cor = pb.id_cor AND co.id_cor <> 1
                          LEFT JOIN cfg_tamanho ta ON ta.id_tenant = pb.id_tenant AND ta.id_tamanho = pb.id_tamanho
                               AND ta.id_tamanho <> 1
                          LEFT JOIN produto_estoque pe ON pe.id_tenant = s.id_tenant
                               AND pe.id_variacao = s.id_variacao AND pe.id_empresa = s.id_empresa
                          -- A tributação do item da NOTA. DISTINCT ON porque a mesma variação pode
                          -- aparecer em mais de um `nItem` do XML (grade, lotes): para a grid basta
                          -- um valor unitário; o rateio por item volta a importar na hora da nota.
                          LEFT JOIN LATERAL (
                                SELECT i.valor_unitario, i.cfop, i.codigo_produto
                                  FROM entrada_nfe_item i
                                 WHERE i.id_tenant = s.id_tenant AND i.id_movimento = s.id_movimento
                                   AND i.id_variacao = s.id_variacao
                                 ORDER BY i.numero_item LIMIT 1
                               ) ni ON true
                         WHERE s.id_tenant = plataforma.tenant_atual() AND s.id_movimento = ?
                           AND LEAST(s.qtd_saldo, COALESCE(pe.qtd_estoque, 0)) > 0
                         ORDER BY p.descricao, pb.sku
                        """)
                .param(idMovimento)
                .query((rs, n) -> new ItemDevolvivelResponse(
                        rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("descricao"),
                        rs.getString("variacao_cor"), rs.getString("variacao_tamanho"),
                        rs.getString("codigo_produto"), rs.getString("cfop_entrada"),
                        rs.getBigDecimal("qtd_comprada"), rs.getBigDecimal("qtd_devolvida"),
                        rs.getBigDecimal("qtd_saldo"), rs.getBigDecimal("qtd_estoque"),
                        rs.getBigDecimal("qtd_maxima"), rs.getBigDecimal("valor_unitario")))
                .list();
    }

    /**
     * Efetiva a devolução numa única transação: um {@code produto_movimento_mestre}
     * ({@code tipo_movimento = 'DEVOLUCAO_COMPRA'}, {@code id_movimento_origem} apontando para a
     * compra) + um {@code produto_movimento_detalhe} {@code 'D'} por item. A trigger
     * {@code fn_atualiza_estoque_movimento} baixa o estoque sozinha — mesmo mecanismo do PDV, da
     * Transferência e do Cancelamento.
     *
     * <p>A emissão da nota fiscal <b>não acontece aqui</b>: é chamada depois do commit pelo
     * controller, porque assinar e transmitir é I/O de rede e não pode segurar a transação que
     * move estoque (F2) — e porque falha de nota não pode desfazer a devolução (F3), já que a
     * mercadoria fisicamente saiu.
     */
    @Transactional
    public DevolucaoCompraEfetivadaResponse efetivar(Jwt jwt, EfetivarDevolucaoCompraRequest req) {
        EntradaOrigem origem = exigirEntradaElegivel(req.idMovimentoOrigem());
        long idUsuario = Long.parseLong(jwt.getSubject());

        Map<Long, ItemDevolvivelResponse> devolviveis = new HashMap<>();
        for (ItemDevolvivelResponse item : itensDevolviveis(req.idMovimentoOrigem())) {
            devolviveis.put(item.idVariacao(), item);
        }

        // A mesma variacao pode chegar em mais de uma linha do pedido - o operador digitou duas
        // vezes, ou a tela mandou uma linha por `nItem` da nota. Somar ANTES de validar: conferir
        // linha a linha deixaria duas linhas de 5 passarem contra um estoque de 8, que e
        // exatamente o limite que esta rotina existe para respeitar.
        Map<Long, BigDecimal> pedidoPorVariacao = new LinkedHashMap<>();
        for (ItemDevolucaoCompraRequest pedido : req.itens()) {
            if (pedido.qtd() == null || pedido.qtd().signum() <= 0) {
                throw new ConflitoDadosException("A quantidade a devolver precisa ser maior que zero.");
            }
            pedidoPorVariacao.merge(pedido.idVariacao(), pedido.qtd(), BigDecimal::add);
        }
        if (pedidoPorVariacao.isEmpty()) {
            throw new ConflitoDadosException("Selecione ao menos um produto para devolver.");
        }

        // Estoque lido AGORA e com a linha travada, em vez do valor que a grid mostrou. Entre abrir
        // a tela e confirmar, o PDV pode ter vendido a peca; sem a trava, duas transacoes
        // simultaneas leem o mesmo saldo e as duas passam. A trigger de estoque nao barra nada
        // (o sistema permite negativo em toda outra operacao), entao o unico guarda e este.
        Map<Long, BigDecimal> estoqueAgora = travarEstoque(origem.idEmpresa(), pedidoPorVariacao.keySet());

        List<ItemDevolvidoResponse> itens = new ArrayList<>();
        BigDecimal valorTotal = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> pedido : pedidoPorVariacao.entrySet()) {
            ItemDevolvivelResponse d = devolviveis.get(pedido.getKey());
            if (d == null) {
                throw new ConflitoDadosException(
                        "Um dos produtos selecionados não pertence a esta entrada, já foi devolvido por inteiro "
                                + "ou não está mais em estoque. Refaça a seleção.");
            }
            BigDecimal emEstoque = estoqueAgora.getOrDefault(pedido.getKey(), BigDecimal.ZERO);
            if (pedido.getValue().compareTo(d.qtdSaldo()) > 0) {
                throw new ConflitoDadosException(("Não é possível devolver %s de \"%s\": a nota trouxe %s e %s "
                        + "já foram devolvidos — restam %s.")
                        .formatted(pedido.getValue().toPlainString(), d.descricao(),
                                d.qtdComprada().toPlainString(),
                                d.qtdDevolvida().toPlainString(), d.qtdSaldo().toPlainString()));
            }
            if (pedido.getValue().compareTo(emEstoque) > 0) {
                // Regra do dono do produto: só devolve o que ainda está em estoque. Mensagem
                // separada da anterior de propósito — "já vendi" e "já devolvi" são problemas
                // diferentes, e o operador precisa saber qual dos dois é.
                throw new ConflitoDadosException(("Não é possível devolver %s de \"%s\": há apenas %s em estoque "
                        + "nesta empresa. O que já saiu da loja não pode ser devolvido ao fornecedor.")
                        .formatted(pedido.getValue().toPlainString(), d.descricao(),
                                emEstoque.toPlainString()));
            }
            BigDecimal total = d.valorUnitario() == null
                    ? BigDecimal.ZERO : d.valorUnitario().multiply(pedido.getValue());
            valorTotal = valorTotal.add(total);
            itens.add(new ItemDevolvidoResponse(d.idVariacao(), d.sku(), d.descricao(),
                    pedido.getValue(), d.valorUnitario(), total));
        }

        long idMovimento = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre
                            (id_tenant, id_empresa, tipo_movimento, id_fornecedor, id_movimento_origem,
                             nota_fiscal, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, 'DEVOLUCAO_COMPRA', ?, ?, ?, ?)
                        RETURNING id_movimento
                        """)
                .params(origem.idEmpresa(), origem.idFornecedor(), req.idMovimentoOrigem(),
                        origem.notaFiscal(), idUsuario)
                .query(Long.class).single();

        for (ItemDevolvidoResponse item : itens) {
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito,
                                 qtd_produto, preco_custo, origem)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'D', ?, ?, 'devolucao compra')
                            """)
                    .params(idMovimento, origem.idEmpresa(), item.idVariacao(), item.qtd(),
                            item.valorUnitario() == null ? BigDecimal.ZERO : item.valorUnitario())
                    .update();
        }

        OffsetDateTime dataMovimento = jdbc.sql("""
                        SELECT data_movimento FROM produto_movimento_mestre
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                        """)
                .param(idMovimento).query(OffsetDateTime.class).single();

        return new DevolucaoCompraEfetivadaResponse(idMovimento, req.idMovimentoOrigem(), dataMovimento,
                origem.idEmpresa(), origem.idFornecedor(), origem.nomeFornecedor(), origem.notaFiscal(),
                valorTotal, itens,
                // A nota fiscal e emitida DEPOIS do commit, pelo controller (F2) - aqui ela ainda
                // nao existe, e mentir dizendo que existe seria pior que devolver nulo.
                null);
    }

    /**
     * Le o estoque das variacoes pedidas <b>travando as linhas</b> ({@code FOR UPDATE}), dentro da
     * transacao de {@link #efetivar}.
     *
     * <p>Por que travar, se o resto do sistema nao trava: em toda outra movimentacao o Niner
     * permite estoque negativo de proposito, entao ninguem depende de conferir saldo. Aqui a regra
     * do dono do produto e o contrario - <b>so devolve o que ainda esta em estoque</b> - e uma
     * regra de saldo so vale se a leitura e a gravacao acontecerem sob a mesma trava. Sem ela, o
     * PDV vende a peca entre a grid e o "confirmar", ou duas devolucoes simultaneas leem as mesmas
     * 8 unidades e devolvem 16.
     *
     * <p>{@code ORDER BY id_variacao} nao e cosmetico: fixa a ordem em que as linhas sao travadas,
     * o que evita deadlock entre duas devolucoes que compartilhem produtos em ordens diferentes.
     *
     * @return quantidade em estoque por variacao; variacao sem linha em {@code produto_estoque}
     *         simplesmente nao vem no mapa e o chamador a trata como zero (que e o que ela e)
     */
    private Map<Long, BigDecimal> travarEstoque(long idEmpresa, Collection<Long> variacoes) {
        String marcadores = String.join(", ", variacoes.stream().map(v -> "?").toList());
        List<Object> params = new ArrayList<>();
        params.add(idEmpresa);
        params.addAll(variacoes);

        Map<Long, BigDecimal> saldo = new HashMap<>();
        jdbc.sql("""
                        SELECT id_variacao, qtd_estoque
                          FROM produto_estoque
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_empresa = ?
                           AND id_variacao IN (%s)
                         ORDER BY id_variacao
                           FOR UPDATE
                        """.formatted(marcadores))
                .params(params)
                .query((rs, n) -> saldo.put(rs.getLong("id_variacao"), rs.getBigDecimal("qtd_estoque")))
                .list();
        return saldo;
    }

    /**
     * Confere que a devolução existe e ainda não foi cancelada, <b>antes</b> de qualquer coisa
     * irreversível acontecer.
     *
     * <p>Existe separado de {@link #cancelar} por causa da ordem imposta pelo fiscal: o controller
     * precisa cancelar a NF-e na SEFAZ <b>primeiro</b> (senão o estoque volta e a nota continua
     * valendo), e seria péssimo descobrir só depois disso que a devolução já estava cancelada — o
     * evento 110111 já teria ido para a SEFAZ sem motivo.
     *
     * @return a empresa da devolução, que é de quem é o certificado que vai assinar o cancelamento
     */
    @Transactional(readOnly = true)
    public long exigirCancelavel(long idMovimento) {
        record Situacao(long idEmpresa, boolean cancelado) {
        }
        Situacao s = jdbc.sql("""
                        SELECT id_empresa, cancelado FROM produto_movimento_mestre
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                           AND tipo_movimento = 'DEVOLUCAO_COMPRA'
                        """)
                .param(idMovimento)
                .query((rs, n) -> new Situacao(rs.getLong("id_empresa"), rs.getBoolean("cancelado")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Devolução de compra não encontrada."));
        if (s.cancelado()) {
            throw new ConflitoDadosException("Esta devolução já foi cancelada.");
        }
        return s.idEmpresa();
    }

    /**
     * Cancela uma devolução de compra: marca o movimento e lança um {@code CANCELAMENTO} com
     * {@code 'C'}, devolvendo a mercadoria ao estoque. <b>Nada é apagado</b> — mesmo padrão do
     * Cancelamento de Entrada (P3), e é o que faz o saldo devolvível voltar sozinho, porque
     * {@code vw_entrada_saldo_devolucao} ignora devolução cancelada.
     */
    @Transactional
    public DevolucaoCompraCanceladaResponse cancelar(Jwt jwt, long idMovimento, CancelarDevolucaoCompraRequest req,
                                                     String protocoloCancelamentoNota) {
        long idUsuario = Long.parseLong(jwt.getSubject());

        exigirCancelavel(idMovimento);

        jdbc.sql("""
                        UPDATE produto_movimento_mestre
                           SET cancelado = true, data_cancelamento = now(),
                               id_usuario_cancelamento = ?, motivo_cancelamento = ?
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                        """)
                .params(idUsuario, req.motivo(), idMovimento).update();

        long idEstorno = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre
                            (id_tenant, id_empresa, tipo_movimento, id_fornecedor, id_movimento_origem, id_usuario)
                        SELECT id_tenant, id_empresa, 'CANCELAMENTO', id_fornecedor, id_movimento, ?
                          FROM produto_movimento_mestre
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                        RETURNING id_movimento
                        """)
                .params(idUsuario, idMovimento).query(Long.class).single();

        jdbc.sql("""
                        INSERT INTO produto_movimento_detalhe
                            (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito,
                             qtd_produto, preco_custo, origem)
                        SELECT id_tenant, ?, id_empresa, id_variacao, 'C',
                               qtd_produto, preco_custo, 'cancelamento devolucao compra'
                          FROM produto_movimento_detalhe
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                        """)
                .params(idEstorno, idMovimento).update();

        OffsetDateTime cancelamento = jdbc.sql("""
                        SELECT data_cancelamento FROM produto_movimento_mestre
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                        """)
                .param(idMovimento).query(OffsetDateTime.class).single();

        return new DevolucaoCompraCanceladaResponse(idMovimento, cancelamento, req.motivo(), protocoloCancelamentoNota);
    }

    /**
     * 404/409 com o motivo por extenso em vez de lista vazia: "esta entrada não aparece" é a
     * pergunta que o lojista faria ao suporte, e a resposta ("é anterior ao arquivamento do XML",
     * "foi manual") tem de estar na tela.
     */
    private EntradaOrigem exigirEntradaElegivel(long idMovimento) {
        return jdbc.sql("""
                        SELECT m.id_empresa, m.id_fornecedor, f.razao_social AS nome_fornecedor, m.nota_fiscal
                        """ + ENTRADA_ELEGIVEL + " AND m.id_movimento = ?")
                .param(idMovimento)
                .query((rs, n) -> new EntradaOrigem(
                        rs.getLong("id_empresa"), longOuNulo(rs, "id_fornecedor"),
                        rs.getString("nome_fornecedor"), (Integer) rs.getObject("nota_fiscal", Integer.class)))
                .optional()
                .orElseThrow(() -> new ConflitoDadosException(
                        "Esta entrada não pode gerar devolução ao fornecedor. Só é possível devolver entrada "
                                + "feita por importação de XML, não cancelada, e cujo XML e tributação estejam "
                                + "arquivados — o que passou a ser gravado em 20/08/2026. Entradas manuais ou por "
                                + "planilha não têm nota de origem para espelhar."));
    }

    private record EntradaOrigem(long idEmpresa, Long idFornecedor, String nomeFornecedor, Integer notaFiscal) {
    }
}
