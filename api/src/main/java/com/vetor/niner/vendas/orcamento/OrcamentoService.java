package com.vetor.niner.vendas.orcamento;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.CancelarOrcamentoRequest;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.EmitirOrcamentoRequest;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.ItemOrcamentoRequest;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.ItemOrcamentoResponse;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.OrcamentoCanceladoResponse;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.OrcamentoResponse;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.OrcamentoResumoResponse;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.PaginaOrcamentos;
import com.vetor.niner.vendas.orcamento.OrcamentoDtos.SituacaoOrcamento;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Orçamento de Venda (docs/telas/orcamento.md) — frente de loja.
 *
 * <h2>O que este serviço deliberadamente NÃO faz</h2>
 *
 * <p>Orçamento não é venda. Não escreve em {@code produto_movimento_*} (não reserva nem baixa
 * estoque), não gera {@code contas_receber}, não toca {@code caixa_detalhe}, não emite documento
 * fiscal e <b>não consome cota do plano</b> (ADR-015 — a única dimensão cobrada é venda emitida;
 * cobrar pela captação desincentivaria a venda que de fato é cobrada).
 *
 * <h2>Imutável (R1)</h2>
 *
 * <p>Não existe {@code atualizar()}. Depois de emitido, nada muda: nem itens, nem quantidades, nem
 * valores, nem validade. Quem quiser mudar cancela e emite outro — a mesma filosofia da venda.
 * A necessidade de "o cliente levar menos do que orçou" é resolvida no PDV, que pode
 * <b>diminuir</b> quantidade (R2), sem tornar o documento mutável.
 *
 * <h2>⚠️ Vencimento: preguiçoso e por job, os dois</h2>
 *
 * <p>{@link #buscar} marca {@code VENCIDO} na hora ao ler um orçamento cuja validade passou; o
 * {@code OrcamentoVencimentoJob} varre o resto. Só preguiçoso deixaria orçamento nunca consultado
 * eternamente "aberto" poluindo lista e relatório; só job teria uma janela em que a tela mostra
 * aberto algo já vencido. É o mesmo par que {@code ArquivoCompartilhadoService} usa nos PDFs.
 */
@Service
public class OrcamentoService {

    private static final int TAMANHO_PAGINA_PADRAO = 50;
    private static final int TAMANHO_PAGINA_MAXIMO = 200;

    private final JdbcClient jdbc;
    private final ConfiguracaoGeralService configuracaoGeralService;
    private final FusoDaLoja fusoDaLoja;

    public OrcamentoService(JdbcClient jdbc, ConfiguracaoGeralService configuracaoGeralService, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.configuracaoGeralService = configuracaoGeralService;
        this.fusoDaLoja = fusoDaLoja;
    }

    // ------------------------------------------------------------------ emissão

    /**
     * Emite o orçamento. O servidor resolve preço e descrição pelo {@code idVariacao} — a tela
     * <b>nunca</b> manda preço (mesma regra do PDV).
     *
     * <p>O preço resolvido aqui fica <b>congelado</b> em {@code orcamento_item.preco_venda} (R3):
     * é o que faz a data de validade significar alguma coisa.
     */
    @Transactional
    public OrcamentoResponse emitir(Jwt jwt, EmitirOrcamentoRequest req) {
        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();
        long idUsuario = Long.parseLong(jwt.getSubject());

        exigirClienteAtivo(req.idCliente());
        exigirFuncionarioAtivo(req.idFuncionario());

        List<ItemResolvido> itens = resolverItens(req.itens());
        BigDecimal subtotal = itens.stream()
                .map(i -> i.preco().multiply(i.qtd()).setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal desconto = req.valorDesconto() == null ? BigDecimal.ZERO : req.valorDesconto();
        exigirDescontoDentroDoTeto(desconto, subtotal);

        // ⚠️ Validade é dia civil da LOJA (R11). `LocalDate.now()` sem fuso pegaria o TZ do
        // container — que só existe em produção, então o bug não reproduziria em dev.
        LocalDate validade = req.dataValidade() != null
                ? req.dataValidade()
                : fusoDaLoja.hoje(jwt).plusDays(configuracaoGeralService.diasValidadeOrcamento());
        if (validade.isBefore(fusoDaLoja.hoje(jwt))) {
            throw new ConflitoDadosException("A validade do orçamento não pode ser anterior a hoje.");
        }

        long idOrcamento = jdbc.sql("""
                        INSERT INTO orcamento
                            (id_tenant, id_empresa, id_cliente, id_funcionario, id_usuario,
                             data_validade, valor_desconto, observacao)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id_orcamento
                        """)
                .params(idEmpresa, req.idCliente(), req.idFuncionario(), idUsuario,
                        validade, desconto, req.observacao())
                .query(Long.class).single();

        for (ItemResolvido item : itens) {
            jdbc.sql("""
                            INSERT INTO orcamento_item
                                (id_tenant, id_orcamento, id_variacao, qtd_produto, preco_venda)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?)
                            """)
                    .params(idOrcamento, item.idVariacao(), item.qtd(), item.preco())
                    .update();
        }

        return buscarSemVencer(idOrcamento);
    }

    // ------------------------------------------------------------------ leitura

    @Transactional(readOnly = true)
    public PaginaOrcamentos listar(LocalDate dataInicial, LocalDate dataFinal, Long idCliente,
                                    Long idFuncionario, String situacao, Integer pagina, Integer limite,
                                    String nomeCliente, String nomeFuncionario, Long numero) {
        StringBuilder filtro = new StringBuilder();
        List<Object> params = new ArrayList<>();
        // Busca por NOME (2026-08-21) — o PDV e a tela de Orçamentos procuram pelo que o operador
        // sabe de cor ("o orçamento da dona Maria"), não pelo id do cadastro. `unaccent` não está
        // instalado neste banco, então o casamento é por ILIKE simples, que é o que as outras telas
        // de busca do produto já fazem.
        if (nomeCliente != null && !nomeCliente.isBlank()) {
            filtro.append(" AND c.nome ILIKE ?");
            params.add("%" + nomeCliente.trim() + "%");
        }
        if (nomeFuncionario != null && !nomeFuncionario.isBlank()) {
            filtro.append(" AND f.nome ILIKE ?");
            params.add("%" + nomeFuncionario.trim() + "%");
        }
        if (numero != null) {
            filtro.append(" AND o.id_orcamento = ?");
            params.add(numero);
        }
        if (dataInicial != null) {
            // ⚠️ Comparação no fuso da LOJA, nunca no do banco: a sessão do Postgres roda em UTC e
            // um `::date` cru vira o dia seguinte às 21:00 de Brasília.
            filtro.append(" AND (o.data_orcamento AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            params.add(dataInicial);
        }
        if (dataFinal != null) {
            filtro.append(" AND (o.data_orcamento AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            params.add(dataFinal);
        }
        if (idCliente != null) {
            filtro.append(" AND o.id_cliente = ?");
            params.add(idCliente);
        }
        if (idFuncionario != null) {
            filtro.append(" AND o.id_funcionario = ?");
            params.add(idFuncionario);
        }
        if (situacao != null && !situacao.isBlank()) {
            filtro.append(" AND o.situacao = ?::situacao_orcamento");
            params.add(SituacaoOrcamento.valueOf(situacao).name());
        }

        // ⚠️ Os JOINs entram TAMBÉM no count (2026-08-21): o filtro por nome referencia `c`/`f`, e
        // sem eles a contagem quebra com "missing FROM-clause entry". Como são JOINs (não LEFT) por
        // colunas obrigatórias, a contagem continua idêntica quando não há filtro por nome.
        // `id_tenant` explícito em cada junção — P8.
        String base = """
                 FROM orcamento o
                 JOIN cliente c ON c.id_tenant = o.id_tenant AND c.id_cliente = o.id_cliente
                 JOIN funcionario f ON f.id_tenant = o.id_tenant AND f.id_funcionario = o.id_funcionario
                WHERE o.id_tenant = plataforma.tenant_atual()""" + filtro;

        long total = jdbc.sql("SELECT count(*)" + base).params(params).query(Long.class).single();

        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaEfetiva = pagina == null || pagina < 1 ? 1 : pagina;
        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add(tamanho);
        paramsPagina.add((long) (paginaEfetiva - 1) * tamanho);

        List<OrcamentoResumoResponse> itens = jdbc.sql("""
                        SELECT o.id_orcamento, o.data_orcamento, o.data_validade, o.situacao::text AS situacao,
                               c.nome AS nome_cliente, f.nome AS nome_funcionario, o.id_venda, o.valor_desconto,
                               (SELECT COALESCE(SUM(i.qtd_produto * i.preco_venda), 0) FROM orcamento_item i
                                 WHERE i.id_tenant = o.id_tenant AND i.id_orcamento = o.id_orcamento) AS subtotal,
                               (SELECT count(*) FROM orcamento_item i
                                 WHERE i.id_tenant = o.id_tenant AND i.id_orcamento = o.id_orcamento) AS qtd_itens
                          FROM orcamento o
                          JOIN cliente c ON c.id_tenant = o.id_tenant AND c.id_cliente = o.id_cliente
                          JOIN funcionario f ON f.id_tenant = o.id_tenant AND f.id_funcionario = o.id_funcionario
                         WHERE o.id_tenant = plataforma.tenant_atual()
                        """ + filtro + " ORDER BY o.data_orcamento DESC, o.id_orcamento DESC LIMIT ? OFFSET ?")
                .params(paramsPagina)
                .query((rs, n) -> new OrcamentoResumoResponse(
                        rs.getLong("id_orcamento"),
                        rs.getObject("data_orcamento", OffsetDateTime.class),
                        rs.getObject("data_validade", LocalDate.class),
                        SituacaoOrcamento.valueOf(rs.getString("situacao")),
                        rs.getString("nome_cliente"), rs.getString("nome_funcionario"),
                        rs.getBigDecimal("subtotal").subtract(rs.getBigDecimal("valor_desconto")),
                        longOuNulo(rs, "id_venda"), rs.getInt("qtd_itens")))
                .list();

        return new PaginaOrcamentos(itens, paginaEfetiva, tamanho, total);
    }

    /**
     * Detalhe do orçamento — e o lugar onde o vencimento é aplicado <b>na hora</b> (R6).
     *
     * <p>Não é readOnly de propósito: ler um orçamento vencido o marca como {@code VENCIDO}. Sem
     * isso, a tela mostraria "aberto" um documento que o PDV vai recusar em seguida, e o operador
     * descobriria com o cliente na frente.
     */
    @Transactional
    public OrcamentoResponse buscar(Jwt jwt, long idOrcamento) {
        vencerSeAplicavel(idOrcamento, fusoDaLoja.hoje(jwt));
        return buscarSemVencer(idOrcamento);
    }

    // ------------------------------------------------------------------ cancelamento

    @Transactional
    public OrcamentoCanceladoResponse cancelar(Jwt jwt, long idOrcamento, CancelarOrcamentoRequest req) {
        long idUsuario = Long.parseLong(jwt.getSubject());
        SituacaoOrcamento situacao = situacaoAtual(idOrcamento);
        if (situacao != SituacaoOrcamento.ABERTO) {
            throw new ConflitoDadosException(mensagemDeEstadoFinal(situacao, "cancelar"));
        }

        jdbc.sql("""
                        UPDATE orcamento
                           SET situacao = 'CANCELADO', data_cancelamento = now(),
                               id_usuario_cancelamento = ?, motivo_cancelamento = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_orcamento = ?
                        """)
                .params(idUsuario, req.motivo(), idOrcamento).update();

        OffsetDateTime quando = jdbc.sql("""
                        SELECT data_cancelamento FROM orcamento
                         WHERE id_tenant = plataforma.tenant_atual() AND id_orcamento = ?
                        """)
                .param(idOrcamento).query(OffsetDateTime.class).single();

        return new OrcamentoCanceladoResponse(idOrcamento, quando, req.motivo());
    }

    // ------------------------------------------------------------------ usado pelo PDV

    /**
     * Marca o orçamento como efetivado. Chamado <b>de dentro</b> da transação de
     * {@code PdvVendaService.efetivarVenda}.
     *
     * <p>⚠️ Isto <b>não</b> é efeito colateral, e a distinção importa. A regra do projeto ("efeito
     * secundário roda depois do commit") nasceu do signup respondendo 201 com conta inexistente e
     * vale para <b>medição e log</b>. Aqui é vínculo de negócio: se esta gravação falhar, a venda
     * <b>deve</b> falhar junto — senão fica uma venda feita e um orçamento aberto que o cliente
     * usa de novo.
     *
     * @param parcial ao menos um item saiu com quantidade menor que a orçada, ou não saiu
     */
    @Transactional
    public void marcarEfetivado(long idOrcamento, long idVenda, boolean parcial) {
        // ⚠️ `AND situacao = 'ABERTO'` + conferência das linhas afetadas (2026-08-21, auditoria):
        // defesa em profundidade sobre o `FOR UPDATE` de `situacaoAtual`. Sem o predicado, um
        // caminho que chegasse aqui com o orçamento já vendido **sobrescreveria** o `id_venda`, e a
        // primeira venda perderia o vínculo com o documento que ela cumpriu — sem erro nenhum.
        // Zero linhas aqui significa que outra venda ganhou a corrida; falhar alto desfaz esta.
        int linhas = jdbc.sql("""
                        UPDATE orcamento
                           SET situacao = ?::situacao_orcamento, id_venda = ?, data_efetivacao = now(),
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_orcamento = ?
                           AND situacao = 'ABERTO'::situacao_orcamento
                        """)
                .params(parcial ? SituacaoOrcamento.VENDIDO_PARCIAL.name() : SituacaoOrcamento.VENDIDO.name(),
                        idVenda, idOrcamento)
                .update();
        if (linhas == 0) {
            throw new ConflitoDadosException(
                    "O orçamento nº " + idOrcamento + " deixou de estar em aberto enquanto esta venda era registrada"
                            + " — provavelmente ele acabou de virar outra venda. Refaça a venda sem o orçamento.");
        }
    }

    /**
     * Estado + itens do orçamento para o PDV, já vencido se for o caso.
     *
     * @throws ConflitoDadosException quando o orçamento não está {@code ABERTO} — com a mensagem
     *         dizendo <b>por quê</b> (vencido em tal dia, cancelado por fulano), porque "não pode"
     *         sem motivo manda o operador adivinhar na frente do cliente —, ou quando ele foi feito
     *         em <b>outra empresa</b> (ver abaixo)
     */
    @Transactional
    public OrcamentoResponse abrirParaVenda(Jwt jwt, long idOrcamento) {
        vencerSeAplicavel(idOrcamento, fusoDaLoja.hoje(jwt));
        SituacaoOrcamento situacao = situacaoAtual(idOrcamento);
        if (situacao != SituacaoOrcamento.ABERTO) {
            throw new ConflitoDadosException(mensagemDeEstadoFinal(situacao, "transformar em venda"));
        }
        OrcamentoResponse orcamento = buscarSemVencer(idOrcamento);

        // Orçamento pertence à EMPRESA que o fez (decisão do dono do produto, 2026-08-22 —
        // auditoria, item 3). Não é isolamento de tenant (P8 nunca esteve em risco: as duas
        // empresas são do mesmo tenant e o RLS não separa empresas), é regra de negócio: o
        // documento impresso diz "empresa A", e sem esta recusa a venda cairia na B, baixando
        // estoque de um lugar diferente do que a grid do orçamento mostrou.
        long idEmpresaSessao = ((Number) jwt.getClaim("eid")).longValue();
        if (orcamento.idEmpresa() != idEmpresaSessao) {
            throw new ConflitoDadosException(
                    "O orçamento nº " + idOrcamento + " foi feito em " + orcamento.nomeEmpresa()
                            + " e só pode virar venda nessa empresa. Entre na empresa correta para usá-lo.");
        }
        return orcamento;
    }

    // ------------------------------------------------------------------ internos

    /**
     * Marca {@code VENCIDO} se a validade já passou. Usado pela consulta, pelo PDV e pelo job.
     *
     * <p>⚠️ O corte é {@code data_validade < hoje}, não {@code <=}: o orçamento vale <b>até</b> o
     * dia da validade, inclusive. E {@code hoje} vem sempre do fuso da loja — nunca de
     * {@code CURRENT_DATE}, que em UTC viraria o dia seguinte às 21:00 de Brasília.
     */
    void vencerSeAplicavel(long idOrcamento, LocalDate hoje) {
        jdbc.sql("""
                        UPDATE orcamento
                           SET situacao = 'VENCIDO', atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_orcamento = ?
                           AND situacao = 'ABERTO' AND data_validade < ?
                        """)
                .params(idOrcamento, hoje).update();
    }

    /**
     * ⚠️ `FOR UPDATE` — trava a linha do orçamento até o fim da transação (2026-08-21, achado em
     * auditoria).
     *
     * <p>Sem o lock, dois caixas com o mesmo orçamento (ou um duplo clique no F6) liam
     * {@code ABERTO} ao mesmo tempo, passavam pelas duas validações e gravavam **duas vendas
     * completas**: estoque baixado duas vezes, dois lançamentos em contas a receber e no caixa, e a
     * cota do plano consumida em dobro — as duas honrando o preço congelado. O orçamento acabava
     * apontando só para a segunda venda, e a primeira ficava sem rastro de origem.
     *
     * <p>Chamada de dentro da transação de {@code efetivarVenda}, o lock dura até o commit e a
     * segunda transação espera — e aí encontra {@code VENDIDO}, que é um estado final.
     *
     * <p>É a mesma defesa que o vale-mercadoria já tinha (`PdvVendaService`, {@code vale_usado}) e
     * que aqui faltava.
     */
    private SituacaoOrcamento situacaoAtual(long idOrcamento) {
        return jdbc.sql("""
                        SELECT situacao::text FROM orcamento
                         WHERE id_tenant = plataforma.tenant_atual() AND id_orcamento = ?
                         FOR UPDATE
                        """)
                .param(idOrcamento).query(String.class).optional()
                .map(SituacaoOrcamento::valueOf)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Orçamento não encontrado."));
    }

    /** Mensagem que diz o MOTIVO — "não pode" sem explicação manda o operador adivinhar. */
    private String mensagemDeEstadoFinal(SituacaoOrcamento situacao, String acao) {
        String porque = switch (situacao) {
            case VENCIDO -> "já venceu";
            case CANCELADO -> "foi cancelado";
            case VENDIDO -> "já virou venda";
            case VENDIDO_PARCIAL -> "já virou venda (parcial)";
            case ABERTO -> "está aberto";
        };
        String saida = situacao == SituacaoOrcamento.VENCIDO || situacao == SituacaoOrcamento.CANCELADO
                ? " Emita um orçamento novo."
                : "";
        return "Não é possível %s: este orçamento %s.%s".formatted(acao, porque, saida);
    }

    private void exigirDescontoDentroDoTeto(BigDecimal desconto, BigDecimal subtotal) {
        if (desconto.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        // Mesmo teto da venda (R4) — de propósito: um orçamento com desconto que a venda recusaria
        // seria uma promessa que o PDV não consegue cumprir.
        BigDecimal percentualMaximo = configuracaoGeralService.percentualDescontoVenda();
        BigDecimal maximo = subtotal.multiply(percentualMaximo)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
        if (desconto.compareTo(maximo) > 0) {
            throw new ConflitoDadosException(
                    "Desconto de R$ %s excede o máximo de %s%% (R$ %s) permitido em Parâmetros do Sistema."
                            .formatted(desconto.toPlainString(), percentualMaximo.stripTrailingZeros().toPlainString(),
                                    maximo.toPlainString()));
        }
    }

    private void exigirClienteAtivo(long idCliente) {
        boolean ok = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM cliente
                                        WHERE id_tenant = plataforma.tenant_atual()
                                          AND id_cliente = ? AND ativo = true)
                        """).param(idCliente).query(Boolean.class).single());
        if (!ok) {
            throw new ConflitoDadosException("Cliente não encontrado ou inativo.");
        }
    }

    private void exigirFuncionarioAtivo(long idFuncionario) {
        boolean ok = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM funcionario
                                        WHERE id_tenant = plataforma.tenant_atual()
                                          AND id_funcionario = ? AND ativo = true)
                        """).param(idFuncionario).query(Boolean.class).single());
        if (!ok) {
            throw new ConflitoDadosException("Vendedor não encontrado ou inativo.");
        }
    }

    private record ItemResolvido(long idVariacao, BigDecimal qtd, BigDecimal preco) {
    }

    /** Resolve preço pelo cadastro — a tela nunca manda preço. Produto inativo é recusado já aqui:
     *  orçar o que não se pode vender seria prometer o impossível. */
    private List<ItemResolvido> resolverItens(List<ItemOrcamentoRequest> itens) {
        boolean permiteQtdDecimal = configuracaoGeralService.permiteQtdDecimalProduto();
        List<ItemResolvido> resolvidos = new ArrayList<>();
        for (ItemOrcamentoRequest item : itens) {
            if (!permiteQtdDecimal && item.qtd().stripTrailingZeros().scale() > 0) {
                throw new ConflitoDadosException(
                        "Quantidade decimal não é permitida (Parâmetros do Sistema → Estoque).");
            }
            BigDecimal preco = jdbc.sql("""
                            SELECT p.preco_venda
                              FROM produto_barra pb
                              JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                             WHERE pb.id_tenant = plataforma.tenant_atual()
                               AND pb.id_variacao = ? AND p.ativo = true
                            """)
                    .param(item.idVariacao()).query(BigDecimal.class).optional()
                    .orElseThrow(() -> new ConflitoDadosException("Produto informado não existe ou está inativo."));
            resolvidos.add(new ItemResolvido(item.idVariacao(), item.qtd(), preco));
        }
        return resolvidos;
    }

    private OrcamentoResponse buscarSemVencer(long idOrcamento) {
        record Cabecalho(long idEmpresa, String nomeEmpresa, long idCliente, String nomeCliente,
                          String documentoCliente, String telefoneCliente, long idFuncionario,
                          String nomeFuncionario, String nomeUsuario, OffsetDateTime dataOrcamento,
                          LocalDate dataValidade, SituacaoOrcamento situacao, BigDecimal valorDesconto,
                          String observacao, Long idVenda, OffsetDateTime dataEfetivacao,
                          OffsetDateTime dataCancelamento, String nomeUsuarioCancelamento,
                          String motivoCancelamento) {
        }

        Cabecalho c = jdbc.sql("""
                        SELECT o.id_empresa, COALESCE(e.nome_fantasia, e.razao_social) AS nome_empresa,
                               o.id_cliente, cl.nome AS nome_cliente,
                               cl.cpf_cnpj AS documento_cliente, cl.telefone AS telefone_cliente,
                               o.id_funcionario, f.nome AS nome_funcionario, u.nome_usuario,
                               o.data_orcamento, o.data_validade, o.situacao::text AS situacao,
                               o.valor_desconto, o.observacao, o.id_venda, o.data_efetivacao,
                               o.data_cancelamento, uc.nome_usuario AS nome_usuario_cancelamento,
                               o.motivo_cancelamento
                          FROM orcamento o
                          JOIN empresa e     ON e.id_tenant = o.id_tenant AND e.id_empresa = o.id_empresa
                          JOIN cliente cl    ON cl.id_tenant = o.id_tenant AND cl.id_cliente = o.id_cliente
                          JOIN funcionario f ON f.id_tenant = o.id_tenant AND f.id_funcionario = o.id_funcionario
                          JOIN usuario u     ON u.id_tenant = o.id_tenant AND u.id_usuario = o.id_usuario
                          LEFT JOIN usuario uc ON uc.id_tenant = o.id_tenant
                               AND uc.id_usuario = o.id_usuario_cancelamento
                         WHERE o.id_tenant = plataforma.tenant_atual() AND o.id_orcamento = ?
                        """)
                .param(idOrcamento)
                .query((rs, n) -> new Cabecalho(
                        rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                        rs.getLong("id_cliente"), rs.getString("nome_cliente"),
                        rs.getString("documento_cliente"), rs.getString("telefone_cliente"),
                        rs.getLong("id_funcionario"), rs.getString("nome_funcionario"),
                        rs.getString("nome_usuario"),
                        rs.getObject("data_orcamento", OffsetDateTime.class),
                        rs.getObject("data_validade", LocalDate.class),
                        SituacaoOrcamento.valueOf(rs.getString("situacao")),
                        rs.getBigDecimal("valor_desconto"), rs.getString("observacao"),
                        longOuNulo(rs, "id_venda"),
                        rs.getObject("data_efetivacao", OffsetDateTime.class),
                        rs.getObject("data_cancelamento", OffsetDateTime.class),
                        rs.getString("nome_usuario_cancelamento"), rs.getString("motivo_cancelamento")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Orçamento não encontrado."));

        List<ItemOrcamentoResponse> itens = jdbc.sql("""
                        SELECT i.id_orcamento_item, i.id_variacao, pb.sku, p.descricao,
                               co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                               i.qtd_produto, i.preco_venda,
                               p.preco_venda AS preco_atual, p.ativo,
                               COALESCE(pe.qtd_estoque, 0) AS qtd_estoque
                          FROM orcamento_item i
                          JOIN produto_barra pb ON pb.id_tenant = i.id_tenant AND pb.id_variacao = i.id_variacao
                          JOIN produto p        ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          LEFT JOIN cfg_cor co     ON co.id_tenant = pb.id_tenant AND co.id_cor = pb.id_cor
                               AND co.id_cor <> 1
                          LEFT JOIN cfg_tamanho ta ON ta.id_tenant = pb.id_tenant AND ta.id_tamanho = pb.id_tamanho
                               AND ta.id_tamanho <> 1
                          LEFT JOIN produto_estoque pe ON pe.id_tenant = i.id_tenant
                               AND pe.id_variacao = i.id_variacao AND pe.id_empresa = ?
                         WHERE i.id_tenant = plataforma.tenant_atual() AND i.id_orcamento = ?
                         ORDER BY i.id_orcamento_item
                        """)
                .params(c.idEmpresa(), idOrcamento)
                .query((rs, n) -> {
                    BigDecimal qtd = rs.getBigDecimal("qtd_produto");
                    BigDecimal preco = rs.getBigDecimal("preco_venda");
                    return new ItemOrcamentoResponse(
                            rs.getLong("id_orcamento_item"), rs.getLong("id_variacao"),
                            rs.getString("sku"), rs.getString("descricao"),
                            rs.getString("variacao_cor"), rs.getString("variacao_tamanho"),
                            qtd, preco, qtd.multiply(preco).setScale(2, RoundingMode.HALF_UP),
                            rs.getBigDecimal("preco_atual"), !rs.getBoolean("ativo"),
                            rs.getBigDecimal("qtd_estoque"));
                })
                .list();

        BigDecimal subtotal = itens.stream().map(ItemOrcamentoResponse::valorTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new OrcamentoResponse(idOrcamento, c.dataOrcamento(), c.dataValidade(), c.situacao(),
                c.idEmpresa(), c.nomeEmpresa(), c.idCliente(), c.nomeCliente(), c.documentoCliente(),
                c.telefoneCliente(), c.idFuncionario(), c.nomeFuncionario(), c.nomeUsuario(),
                c.observacao(), subtotal, c.valorDesconto(), subtotal.subtract(c.valorDesconto()),
                c.idVenda(), c.dataEfetivacao(), c.dataCancelamento(), c.nomeUsuarioCancelamento(),
                c.motivoCancelamento(), itens);
    }

    /** ⚠️ `rs.getObject(coluna, Long.class)` numa coluna `integer` NÃO lê — o driver recusa e o
     *  erro chega ao usuário como "Registro em uso por outro cadastro" num GET. */
    private static Long longOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }
}
