package com.vetor.niner.vendas.cancelamento;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.fiscal.documento.CancelamentoNfceService;
import com.vetor.niner.fiscal.documento.DocumentoFiscalRepositorio;
import com.vetor.niner.fiscal.sefaz.SefazAutorizadorService;
import com.vetor.niner.fiscal.sefaz.SefazDtos.Autorizador;
import com.vetor.niner.financeiro.caixa.CaixaService;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.CancelamentoEfetivadoResponse;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.CancelarVendaRequest;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.ItemVendaDetalhe;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.PagamentoVendaDetalhe;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.PaginaVendasCancelamento;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.ParcelaRecebidaDetalhe;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.VendaDetalheCancelamentoResponse;
import com.vetor.niner.vendas.cancelamento.CancelamentoVendaDtos.VendaParaCancelamentoResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Cancelamento de Venda — ver {@code package-info.java} pro resumo de escopo (comissão/fiscal/
 * TEF fora do v1; caixa exigido é o de HOJE, não o da data original da venda).
 */
@Service
public class CancelamentoVendaService {

    private static final int TAMANHO_PAGINA_PADRAO = 50;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;
    private static final int PERIODO_MAXIMO_DIAS = 365;
    private static final int MODELO_NFCE = 65;
    private static final int PRAZO_PADRAO_MINUTOS = 30;
    private static final DateTimeFormatter FMT_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "dataVenda", "v.data_venda",
            "numeroVenda", "v.id_venda",
            "valorVenda", "valor_venda",
            "nomeCliente", "nome_cliente",
            "nomeEmpresa", "nome_empresa",
            "nomeFuncionario", "nome_funcionario");

    private final JdbcClient jdbc;
    private final CaixaService caixaService;
    private final CancelamentoNfceService cancelamentoNfceService;
    private final DocumentoFiscalRepositorio documentoFiscalRepositorio;
    private final SefazAutorizadorService autorizadores;
    private final FusoDaLoja fusoDaLoja;

    public CancelamentoVendaService(JdbcClient jdbc, CaixaService caixaService,
                                    CancelamentoNfceService cancelamentoNfceService,
                                    DocumentoFiscalRepositorio documentoFiscalRepositorio,
                                    SefazAutorizadorService autorizadores, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.caixaService = caixaService;
        this.cancelamentoNfceService = cancelamentoNfceService;
        this.documentoFiscalRepositorio = documentoFiscalRepositorio;
        this.autorizadores = autorizadores;
        this.fusoDaLoja = fusoDaLoja;
    }

    @Transactional(readOnly = true)
    public PaginaVendasCancelamento listar(Jwt jwt, Long numeroVenda, Long idEmpresa, Long idCliente,
                                            LocalDate dataInicial, LocalDate dataFinal, Long idFuncionario,
                                            Integer pagina, Integer limite, String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String coluna = ordenarPor == null ? "v.data_venda" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "v.data_venda");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE v.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (numeroVenda != null) {
            filtro.append(" AND v.id_venda = ?");
            params.add(numeroVenda);
        } else {
            if (dataInicial == null || dataFinal == null) {
                throw new IllegalArgumentException("Informe a data inicial e a data final, ou o número da venda.");
            }
            if (dataInicial.isAfter(dataFinal)) {
                throw new IllegalArgumentException("Data inicial não pode ser maior que a data final.");
            }
            if (dataInicial.plusDays(PERIODO_MAXIMO_DIAS).isBefore(dataFinal)) {
                throw new IllegalArgumentException(
                        "Período de consulta não pode exceder " + PERIODO_MAXIMO_DIAS + " dias.");
            }
            filtro.append(" AND (v.data_venda AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?");
            params.add(dataInicial);
            params.add(dataFinal);

            if (idEmpresa != null) {
                filtro.append(" AND v.id_empresa = ?");
                params.add(idEmpresa);
            }
            if (idCliente != null) {
                filtro.append(" AND v.id_cliente = ?");
                params.add(idCliente);
            }
            if (idFuncionario != null) {
                filtro.append("""
                         AND EXISTS (
                             SELECT 1 FROM produto_movimento_mestre pmm2
                             JOIN produto_movimento_detalhe pmd2
                                    ON pmd2.id_movimento = pmm2.id_movimento AND pmd2.id_tenant = pmm2.id_tenant
                             WHERE pmm2.id_tenant = v.id_tenant AND pmm2.id_venda = v.id_venda
                                   AND pmm2.tipo_movimento = 'VENDA' AND pmd2.id_funcionario = ?
                         )
                        """);
                params.add(idFuncionario);
            }
            // Fora da busca direta por número, só vendas ainda canceláveis aparecem por padrão.
            filtro.append(" AND v.cancelada = false");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM venda v" + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);

        String baseSelect = """
                        SELECT v.id_venda, v.id_empresa, e.razao_social AS nome_empresa, v.data_venda,
                               v.id_cliente, c.nome AS nome_cliente,
                               MAX(pmd.id_funcionario) AS id_funcionario, MAX(fn.nome) AS nome_funcionario,
                               v.cancelada,
                               COALESCE(SUM(CASE WHEN pmd.credito_debito = 'D'
                                   THEN pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo
                                   ELSE 0 END), 0) AS valor_venda,
                               EXISTS (
                                   SELECT 1 FROM contas_receber cr
                                   JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                                   WHERE cr.id_tenant = v.id_tenant AND cr.id_venda = v.id_venda
                                         AND tc.categoria_carteira = 'CREDIARIO' AND cr.data_recebimento IS NOT NULL
                               ) AS bloqueada_crediario
                        FROM venda v
                        JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
                        LEFT JOIN cliente c ON c.id_cliente = v.id_cliente AND c.id_tenant = v.id_tenant
                        LEFT JOIN produto_movimento_mestre pmm
                               ON pmm.id_venda = v.id_venda AND pmm.id_tenant = v.id_tenant AND pmm.tipo_movimento = 'VENDA'
                        LEFT JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        LEFT JOIN funcionario fn ON fn.id_funcionario = pmd.id_funcionario AND fn.id_tenant = pmd.id_tenant
                        """;
        String agrupamento = " GROUP BY v.id_venda, v.id_empresa, e.razao_social, v.data_venda, v.id_cliente, c.nome, v.cancelada";
        String ordenacao = " ORDER BY " + coluna + " " + direcaoOrdenacao
                + ", v.id_venda " + direcaoOrdenacao + " LIMIT ? OFFSET ?";

        List<VendaParaCancelamentoResponse> itens = jdbc.sql(baseSelect + filtro + agrupamento + ordenacao)
                .params(paramsPagina)
                .query((rs, n) -> new VendaParaCancelamentoResponse(
                        rs.getLong("id_venda"), rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                        rs.getObject("data_venda", OffsetDateTime.class),
                        getLongOuNulo(rs, "id_cliente"), rs.getString("nome_cliente"),
                        getLongOuNulo(rs, "id_funcionario"), rs.getString("nome_funcionario"),
                        rs.getBigDecimal("valor_venda"), rs.getBoolean("cancelada"), rs.getBoolean("bloqueada_crediario")))
                .list();

        return new PaginaVendasCancelamento(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public VendaDetalheCancelamentoResponse buscarDetalhe(Jwt jwt, long idVenda) {
        Venda venda = buscarVenda(idVenda);
        List<ItemVendaDetalhe> itens = buscarItens(idVenda);
        List<PagamentoVendaDetalhe> pagamentos = buscarPagamentos(idVenda);
        List<ParcelaRecebidaDetalhe> parcelasRecebidas = buscarParcelasCredarioRecebidas(idVenda);
        BigDecimal valorVenda = itens.stream().map(ItemVendaDetalhe::valorItem).reduce(BigDecimal.ZERO, BigDecimal::add);
        PrazoNfce prazo = buscarPrazoNfce(idVenda);
        // Mesmo guard que `cancelar` aplica (RN-02) — aqui só para a tela poder avisar antes.
        boolean caixaAbertoHoje = caixaService.caixaAbertoHoje(venda.idEmpresa(), idUsuario(jwt));

        return new VendaDetalheCancelamentoResponse(
                venda.idVenda(), venda.idEmpresa(), venda.nomeEmpresa(), venda.dataVenda(),
                venda.idCliente(), venda.nomeCliente(), venda.idFuncionario(), venda.nomeFuncionario(),
                valorVenda, venda.cancelada(), venda.dataCancelamento(), venda.nomeUsuarioCancelamento(),
                venda.motivoCancelamento(), itens, pagamentos, !parcelasRecebidas.isEmpty(), parcelasRecebidas,
                prazo.dataAutorizacao(), prazo.prazoMinutos(), prazo.expiraEm(), prazo.expirado(),
                caixaAbertoHoje);
    }

    /**
     * Prazo de cancelamento da NFC-e (§ da SEFAZ por UF, {@code cfg_uf_autorizador}, 30 min
     * padrão) — exposto na tela ANTES de tentar cancelar (2026-08-19, achado testando a venda
     * 590 ao vivo): sem isso, o usuário só descobria que o prazo tinha passado depois de
     * preencher o motivo e apertar "Cancelar", com uma mensagem de erro genérica. Vazio quando a
     * venda não tem NFC-e autorizada — cancelamento nesse caso não depende de prazo nenhum.
     */
    private record PrazoNfce(OffsetDateTime dataAutorizacao, Integer prazoMinutos,
                             OffsetDateTime expiraEm, boolean expirado) {
        static PrazoNfce vazio() {
            return new PrazoNfce(null, null, null, false);
        }
    }

    private PrazoNfce buscarPrazoNfce(long idVenda) {
        return documentoFiscalRepositorio.buscarAutorizadoParaCancelamento(idVenda)
                .map(doc -> {
                    Autorizador autorizador = autorizadores.buscar(doc.uf(), MODELO_NFCE, doc.ambiente().codigo());
                    int prazoMinutos = autorizador.prazoCancelamentoMinutos() != null
                            ? autorizador.prazoCancelamentoMinutos() : PRAZO_PADRAO_MINUTOS;
                    OffsetDateTime expiraEm = doc.dataAutorizacao().plusMinutes(prazoMinutos);
                    return new PrazoNfce(doc.dataAutorizacao(), prazoMinutos, expiraEm,
                            OffsetDateTime.now().isAfter(expiraEm));
                })
                .orElseGet(PrazoNfce::vazio);
    }

    @Transactional
    public CancelamentoEfetivadoResponse cancelar(Jwt jwt, long idVenda, CancelarVendaRequest req) {
        long idUsuario = idUsuario(jwt);

        Cabecalho venda = jdbc.sql("""
                        SELECT v.id_venda, v.id_empresa, e.razao_social AS nome_empresa, v.cancelada,
                               v.data_cancelamento, u.nome_usuario AS nome_usuario_cancelamento
                        FROM venda v
                        JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
                        LEFT JOIN usuario u ON u.id_usuario = v.id_usuario_cancelamento AND u.id_tenant = v.id_tenant
                        WHERE v.id_tenant = plataforma.tenant_atual() AND v.id_venda = ?
                        FOR UPDATE OF v
                        """)
                .param(idVenda)
                .query((rs, n) -> new Cabecalho(
                        rs.getLong("id_venda"), rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                        rs.getBoolean("cancelada"), rs.getObject("data_cancelamento", OffsetDateTime.class),
                        rs.getString("nome_usuario_cancelamento")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Venda não encontrada."));

        if (venda.cancelada()) {
            throw new ConflitoDadosException(
                    "A venda nº " + venda.idVenda() + " já foi cancelada em "
                            + fusoDaLoja.formatar(venda.dataCancelamento(), venda.idEmpresa(), FMT_DATA)
                            + " por " + venda.nomeUsuarioCancelamento() + ".");
        }

        // RN-03 — checado ANTES do caixa, pro operador receber a mensagem certa já na primeira tentativa.
        List<ParcelaRecebidaDetalhe> parcelasRecebidas = buscarParcelasCredarioRecebidas(idVenda);
        if (!parcelasRecebidas.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "Esta venda é de crediário e já possui parcela(s) recebida(s). Não é possível cancelá-la, "
                            + "em nenhuma hipótese. Cancele os recebimentos, para depois cancelar a venda.");
            for (ParcelaRecebidaDetalhe p : parcelasRecebidas) {
                msg.append("\n- Parcela ").append(p.numeroParcela())
                        .append(", venc. ").append(FMT_DATA.format(p.dataVencimento()))
                        .append(", recebida em ").append(FMT_DATA.format(p.dataRecebimento()))
                        .append(", R$ ").append(p.valorRecebido());
            }
            throw new ConflitoDadosException(msg.toString());
        }

        // RN-02 — simplificado: exige o caixa de HOJE aberto (do usuário logado, na empresa da
        // venda), não o caixa da data original da venda (o sistema não sabe reabrir um caixa já
        // fechado de um dia passado).
        if (!caixaService.caixaAbertoHoje(venda.idEmpresa(), idUsuario)) {
            throw new IllegalStateException(
                    "O caixa de hoje da empresa " + venda.nomeEmpresa() + " está fechado. É necessário abrir o "
                            + "caixa de hoje antes de cancelar a venda nº " + venda.idVenda() + ".");
        }

        // §10.1 (B8): estoque/caixa e fiscal nunca divergem — ou os dois andam, ou nenhum anda.
        // Se esta venda tem NFC-e autorizada, o cancelamento na SEFAZ acontece ANTES de qualquer
        // reversão; se a SEFAZ recusar (ou o prazo de 30 min já tiver passado), o método abaixo
        // lança e NADA do resto desta transação roda. Sem nota fiscal (F12/DF13), devolve
        // silenciosamente e o fluxo segue idêntico a antes deste bloco existir.
        //
        // ⚠️ Desvio deliberado do F2 ("nenhum I/O de rede dentro de transação de banco"): esta
        // chamada roda dentro da transação de `cancelar()`, ao contrário da emissão (B7), que
        // separa em transações curtas. A emissão acontece em TODA venda (hot path — segurar a
        // conexão nela travaria o caixa numa loja cheia); cancelamento é ação manual e rara do
        // ADMIN, e aqui a atomicidade é desejável: ou a venda inteira (fiscal+estoque+caixa)
        // reverte junto, ou nada reverte — não existe janela intermediária pra divergir.
        cancelamentoNfceService.cancelarSeAplicavel(venda.idEmpresa(), idVenda, (int) idUsuario, req.motivo());

        OffsetDateTime agora = OffsetDateTime.now();
        jdbc.sql("""
                        UPDATE venda SET cancelada = true, data_cancelamento = ?, id_usuario_cancelamento = ?,
                            motivo_cancelamento = ?
                        WHERE id_tenant = plataforma.tenant_atual() AND id_venda = ?
                        """)
                .params(agora, idUsuario, req.motivo(), idVenda)
                .update();

        estornarEstoque(venda.idEmpresa(), idVenda);

        jdbc.sql("DELETE FROM caixa_detalhe WHERE id_tenant = plataforma.tenant_atual() AND id_venda = ?")
                .param(idVenda)
                .update();

        jdbc.sql("""
                        DELETE FROM contas_receber_detalhe
                        WHERE id_tenant = plataforma.tenant_atual() AND id_conta_receber IN (
                            SELECT id_conta_receber FROM contas_receber
                            WHERE id_tenant = plataforma.tenant_atual() AND id_venda = ?
                        )
                        """)
                .param(idVenda)
                .update();

        jdbc.sql("DELETE FROM contas_receber WHERE id_tenant = plataforma.tenant_atual() AND id_venda = ?")
                .param(idVenda)
                .update();

        // Reabre qualquer vale-mercadoria resgatado nesta venda (2026-08-03, pedido do dono do
        // produto) — a venda que consumiu o vale foi desfeita, então o crédito volta a valer
        // pro cliente usar numa venda futura. Sem rastro de que já foi usado uma vez (mesma
        // filosofia da exclusão física de caixa_detalhe/contas_receber acima); WHERE
        // id_venda_debito = ? já cobre o caso raro de mais de um vale na mesma venda (split-tender).
        jdbc.sql("""
                        UPDATE venda_devolucao SET vale_usado = false, id_venda_debito = NULL
                        WHERE id_tenant = plataforma.tenant_atual() AND id_venda_debito = ?
                        """)
                .param(idVenda)
                .update();

        return new CancelamentoEfetivadoResponse(idVenda, agora);
    }

    /** Devolve ao estoque cada item vendido — novo {@code produto_movimento_mestre} (tipo
     *  CANCELAMENTO) + um {@code produto_movimento_detalhe} 'C' por item (a trigger já existente
     *  baixa/soma {@code produto_estoque} sozinha, mesmo mecanismo do PDV/Transferência). */
    private void estornarEstoque(long idEmpresa, long idVenda) {
        record ItemVendido(long idVariacao, BigDecimal qtd, BigDecimal precoVenda, BigDecimal precoCusto, Long idFuncionario) {
        }
        List<ItemVendido> itensVendidos = jdbc.sql("""
                        SELECT pmd.id_variacao, pmd.qtd_produto, pmd.preco_venda, pmd.preco_custo, pmd.id_funcionario
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                              AND pmd.credito_debito = 'D'
                        """)
                .param(idVenda)
                .query((rs, n) -> new ItemVendido(
                        rs.getLong("id_variacao"), rs.getBigDecimal("qtd_produto"), rs.getBigDecimal("preco_venda"),
                        rs.getBigDecimal("preco_custo"), getLongOuNulo(rs, "id_funcionario")))
                .list();
        if (itensVendidos.isEmpty()) return;

        long idMovimento = jdbc.sql("""
                        INSERT INTO produto_movimento_mestre (id_tenant, id_empresa, tipo_movimento, id_venda)
                        VALUES (plataforma.tenant_atual(), ?, 'CANCELAMENTO', ?)
                        RETURNING id_movimento
                        """)
                .params(idEmpresa, idVenda).query(Long.class).single();

        for (ItemVendido item : itensVendidos) {
            // preco_custo repete o da VENDA original (não uma nova leitura de produto.preco_custo)
            // — cancelamento é a reversão exata daquela venda, o custo tem que ser o mesmo que
            // saiu, mesmo que o cadastro do produto já tenha mudado de preço desde então.
            jdbc.sql("""
                            INSERT INTO produto_movimento_detalhe
                                (id_tenant, id_movimento, id_empresa, id_variacao, credito_debito, qtd_produto,
                                 preco_venda, preco_custo, id_funcionario)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'C', ?, ?, ?, ?)
                            """)
                    .params(idMovimento, idEmpresa, item.idVariacao(), item.qtd(), item.precoVenda(), item.precoCusto(), item.idFuncionario())
                    .update();
        }
    }

    private record Venda(long idVenda, long idEmpresa, String nomeEmpresa, OffsetDateTime dataVenda,
                          Long idCliente, String nomeCliente, Long idFuncionario, String nomeFuncionario,
                          boolean cancelada, OffsetDateTime dataCancelamento, String nomeUsuarioCancelamento,
                          String motivoCancelamento) {
    }

    private Venda buscarVenda(long idVenda) {
        record CabecalhoVenda(long idVenda, long idEmpresa, String nomeEmpresa, OffsetDateTime dataVenda,
                               Long idCliente, String nomeCliente, boolean cancelada, OffsetDateTime dataCancelamento,
                               String nomeUsuarioCancelamento, String motivoCancelamento) {
        }
        CabecalhoVenda c = jdbc.sql("""
                        SELECT v.id_venda, v.id_empresa, e.razao_social AS nome_empresa, v.data_venda,
                               v.id_cliente, cli.nome AS nome_cliente, v.cancelada, v.data_cancelamento,
                               v.motivo_cancelamento, u.nome_usuario AS nome_usuario_cancelamento
                        FROM venda v
                        JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
                        LEFT JOIN cliente cli ON cli.id_cliente = v.id_cliente AND cli.id_tenant = v.id_tenant
                        LEFT JOIN usuario u ON u.id_usuario = v.id_usuario_cancelamento AND u.id_tenant = v.id_tenant
                        WHERE v.id_tenant = plataforma.tenant_atual() AND v.id_venda = ?
                        """)
                .param(idVenda)
                .query((rs, n) -> new CabecalhoVenda(
                        rs.getLong("id_venda"), rs.getLong("id_empresa"), rs.getString("nome_empresa"),
                        rs.getObject("data_venda", OffsetDateTime.class),
                        getLongOuNulo(rs, "id_cliente"), rs.getString("nome_cliente"),
                        rs.getBoolean("cancelada"), rs.getObject("data_cancelamento", OffsetDateTime.class),
                        rs.getString("nome_usuario_cancelamento"), rs.getString("motivo_cancelamento")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Venda não encontrada."));

        FuncionarioVenda fv = buscarFuncionarioDaVenda(idVenda);
        return new Venda(c.idVenda(), c.idEmpresa(), c.nomeEmpresa(), c.dataVenda(), c.idCliente(), c.nomeCliente(),
                fv.idFuncionario(), fv.nomeFuncionario(), c.cancelada(), c.dataCancelamento(),
                c.nomeUsuarioCancelamento(), c.motivoCancelamento());
    }

    private record FuncionarioVenda(Long idFuncionario, String nomeFuncionario) {
    }

    private FuncionarioVenda buscarFuncionarioDaVenda(long idVenda) {
        return jdbc.sql("""
                        SELECT pmd.id_funcionario, fn.nome AS nome_funcionario
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                        LEFT JOIN funcionario fn ON fn.id_funcionario = pmd.id_funcionario AND fn.id_tenant = pmd.id_tenant
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                        LIMIT 1
                        """)
                .param(idVenda)
                .query((rs, n) -> new FuncionarioVenda(getLongOuNulo(rs, "id_funcionario"), rs.getString("nome_funcionario")))
                .optional()
                .orElse(new FuncionarioVenda(null, null));
    }

    private List<ItemVendaDetalhe> buscarItens(long idVenda) {
        return jdbc.sql("""
                        SELECT p.descricao AS descricao_produto, co.descricao AS variacao_cor,
                               ta.descricao AS variacao_tamanho, pmd.qtd_produto, pmd.preco_venda,
                               pmd.qtd_produto * pmd.preco_venda - pmd.valor_desconto + pmd.valor_acrescimo AS valor_item
                        FROM produto_movimento_mestre pmm
                        JOIN produto_movimento_detalhe pmd
                               ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                              AND pmd.credito_debito = 'D'
                        JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                        JOIN produto p        ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                        WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                        ORDER BY pmd.id_movimento_detalhe
                        """)
                .param(idVenda)
                .query((rs, n) -> new ItemVendaDetalhe(
                        rs.getString("descricao_produto"), rs.getString("variacao_cor"), rs.getString("variacao_tamanho"),
                        rs.getBigDecimal("qtd_produto"), rs.getBigDecimal("preco_venda"), rs.getBigDecimal("valor_item")))
                .list();
    }

    private List<PagamentoVendaDetalhe> buscarPagamentos(long idVenda) {
        return jdbc.sql("""
                        SELECT cr.id_carteira, tc.nome_carteira, tc.categoria_carteira::text AS categoria_carteira,
                               cr.valor_receber, cr.data_recebimento IS NOT NULL AS quitado
                        FROM contas_receber cr
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ?
                        ORDER BY cr.id_conta_receber
                        """)
                .param(idVenda)
                .query((rs, n) -> new PagamentoVendaDetalhe(
                        rs.getLong("id_carteira"), rs.getString("nome_carteira"), rs.getString("categoria_carteira"),
                        rs.getBigDecimal("valor_receber"), rs.getBoolean("quitado")))
                .list();
    }

    private List<ParcelaRecebidaDetalhe> buscarParcelasCredarioRecebidas(long idVenda) {
        return jdbc.sql("""
                        SELECT cr.numero_parcela, cr.data_vencimento, cr.data_recebimento, cr.valor_recebido
                        FROM contas_receber cr
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ?
                              AND tc.categoria_carteira = 'CREDIARIO' AND cr.data_recebimento IS NOT NULL
                        ORDER BY cr.numero_parcela
                        """)
                .param(idVenda)
                .query((rs, n) -> new ParcelaRecebidaDetalhe(
                        rs.getInt("numero_parcela"), rs.getObject("data_vencimento", OffsetDateTime.class),
                        rs.getObject("data_recebimento", OffsetDateTime.class), rs.getBigDecimal("valor_recebido")))
                .list();
    }

    private record Cabecalho(long idVenda, long idEmpresa, String nomeEmpresa, boolean cancelada,
                              OffsetDateTime dataCancelamento, String nomeUsuarioCancelamento) {
    }

    /** {@code rs.getObject(coluna, Long.class)} não funciona pra colunas {@code integer}
     *  (driver do Postgres só converte pro tipo exato) — {@code getLong}+{@code wasNull} é o
     *  jeito seguro de ler uma coluna {@code integer} nullable como {@code Long}. */
    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas administradores podem cancelar vendas.");
        }
    }

    private static long idUsuario(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
