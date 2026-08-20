package com.vetor.niner.financeiro.recebimentocrediario;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.cadastros.cliente.Documentos;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.financeiro.TipoCarteiraDtos.CategoriaCarteira;
import com.vetor.niner.financeiro.caixa.CaixaService;
import com.vetor.niner.financeiro.caixa.CaixaService.VinculoCaixa;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.CarteiraDisponivelResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.ClienteCrediarioResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.ComprovanteRecebimentoResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.EfetivarRecebimentoRequest;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.EstornoEfetivadoResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.LoteRecebimentoResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.PagamentoComprovanteResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.PagamentoRecebimentoRequest;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.ParcelaComprovanteResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.ParcelaCrediarioResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.ParcelaDoLoteResponse;
import com.vetor.niner.financeiro.recebimentocrediario.RecebimentoCrediarioDtos.RecebimentoEfetivadoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Recebimento de Crediário (package-info) — busca de cliente, listagem de parcelas em aberto
 * com multa/juros calculados na hora, e efetivação transacional do recebimento.
 */
@Service
public class RecebimentoCrediarioService {

    private static final int LIMITE_BUSCA_CLIENTE = 20;
    private static final BigDecimal TOLERANCIA_SALDO = new BigDecimal("0.01");
    private static final String FILTRO_CARTEIRAS_PERMITIDAS =
            " permite_receber_crediario = true AND categoria_carteira IN ('AVISTA','CARTAO_DEBITO','CARTAO_CREDITO')";

    private final JdbcClient jdbc;
    private final FusoDaLoja fusoDaLoja;
    private final CaixaService caixaService;

    public RecebimentoCrediarioService(JdbcClient jdbc, CaixaService caixaService, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.caixaService = caixaService;
        this.fusoDaLoja = fusoDaLoja;
    }

    @Transactional(readOnly = true)
    public List<ClienteCrediarioResponse> buscarClientes(String nome, String cpf, String celular) {
        String nomeFiltro = branco(nome) ? null : nome.trim().toUpperCase(Locale.ROOT);
        String cpfFiltro = branco(cpf) ? null : Documentos.somenteDigitos(cpf);
        String celularFiltro = branco(celular) ? null : Documentos.somenteDigitos(celular);
        if (nomeFiltro == null && cpfFiltro == null && celularFiltro == null) {
            throw new IllegalArgumentException("Informe nome, CPF ou celular para localizar o cliente.");
        }

        StringBuilder filtro = new StringBuilder(" WHERE id_tenant = plataforma.tenant_atual() AND ativo = true");
        List<Object> params = new ArrayList<>();
        if (nomeFiltro != null) {
            filtro.append(" AND nome ILIKE ?");
            params.add("%" + nomeFiltro + "%");
        }
        if (cpfFiltro != null) {
            filtro.append(" AND cpf_cnpj ILIKE ?");
            params.add("%" + cpfFiltro + "%");
        }
        if (celularFiltro != null) {
            filtro.append(" AND telefone ILIKE ?");
            params.add("%" + celularFiltro + "%");
        }
        params.add((long) LIMITE_BUSCA_CLIENTE);

        return jdbc.sql("SELECT id_cliente, nome, cpf_cnpj, telefone FROM cliente" + filtro + " ORDER BY nome ASC LIMIT ?")
                .params(params)
                .query((rs, n) -> new ClienteCrediarioResponse(
                        rs.getLong("id_cliente"), rs.getString("nome"), rs.getString("cpf_cnpj"), rs.getString("telefone")))
                .list();
    }

    /** RN002: só parcelas ainda não recebidas; escopo desta tela: categoria CREDIARIO (o nome da tela). */
    @Transactional(readOnly = true)
    public List<ParcelaCrediarioResponse> listarParcelas(long idCliente) {
        ConfigCrediario cfg = buscarConfigCrediario();
        return jdbc.sql("""
                        SELECT cr.id_conta_receber, cr.id_venda, e.codigo_empresa, v.data_venda,
                               cr.numero_parcela, cr.data_vencimento, cr.valor_receber,
                               GREATEST(0, ((now() AT TIME ZONE 'America/Sao_Paulo')::date - (cr.data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date)) AS dias_atraso,
                               (SELECT count(*) FROM contas_receber cr2
                                WHERE cr2.id_tenant = cr.id_tenant AND cr2.id_venda = cr.id_venda
                                      AND cr2.id_carteira = cr.id_carteira) AS total_parcelas
                        FROM contas_receber cr
                        JOIN venda v ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                        JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual()
                              AND cr.data_recebimento IS NULL
                              AND tc.categoria_carteira = 'CREDIARIO'
                              AND v.id_cliente = ?
                        ORDER BY cr.data_vencimento ASC, cr.numero_parcela ASC
                        """)
                .param(idCliente)
                .query((rs, n) -> mapearParcela(rs, cfg))
                .list();
    }

    @Transactional(readOnly = true)
    public List<CarteiraDisponivelResponse> listarCarteirasDisponiveis() {
        return jdbc.sql("""
                        SELECT id_carteira, nome_carteira, categoria_carteira::text AS categoria_carteira
                        FROM tipo_carteira
                        WHERE id_tenant = plataforma.tenant_atual() AND""" + FILTRO_CARTEIRAS_PERMITIDAS + """
                        ORDER BY nome_carteira ASC
                        """)
                .query((rs, n) -> new CarteiraDisponivelResponse(
                        rs.getLong("id_carteira"), rs.getString("nome_carteira"),
                        CategoriaCarteira.valueOf(rs.getString("categoria_carteira"))))
                .list();
    }

    /**
     * Transação única (RN013): trava e revalida cada parcela (ainda aberta, do cliente
     * informado, categoria CREDIARIO), recalcula multa/juros no servidor (nunca confia em
     * valor vindo do front), valida a(s) forma(s) de pagamento e aloca os pagamentos nas
     * parcelas em ordem de vencimento (mais antiga primeiro). Qualquer erro cancela tudo.
     */
    @Transactional
    public RecebimentoEfetivadoResponse efetivarRecebimento(Jwt jwt, EfetivarRecebimentoRequest req) {
        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();
        long idUsuario = Long.parseLong(jwt.getSubject());

        ConfigCrediario cfg = buscarConfigCrediario();
        Map<Long, CarteiraInfo> carteirasPermitidas = mapaCarteirasPermitidas();

        List<Long> idsUnicos = req.idsContaReceber().stream().distinct().toList();
        List<ParcelaResolvida> parcelas = new ArrayList<>();
        for (long idContaReceber : idsUnicos) {
            parcelas.add(resolverParcelaParaRecebimento(idContaReceber, req.idCliente(), cfg, fusoDaLoja.hoje(jwt)));
        }
        parcelas.sort(Comparator.comparing(ParcelaResolvida::dataVencimento).thenComparing(ParcelaResolvida::numeroParcela));

        for (PagamentoRecebimentoRequest pagamento : req.pagamentos()) {
            if (!carteirasPermitidas.containsKey(pagamento.idCarteira())) {
                throw new IllegalArgumentException(
                        "Forma de pagamento informada não permite receber parcela de crediário.");
            }
        }

        BigDecimal totalParcelas = parcelas.stream().map(ParcelaResolvida::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPagamentos =
                req.pagamentos().stream().map(PagamentoRecebimentoRequest::valorPago).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diferenca = totalParcelas.subtract(totalPagamentos);
        if (diferenca.abs().compareTo(TOLERANCIA_SALDO) > 0) {
            throw new IllegalArgumentException(
                    "Os pagamentos informados (R$ " + totalPagamentos + ") não fecham o valor total das parcelas (R$ "
                            + totalParcelas + ").");
        }

        // Absorve o resto de arredondamento de até 1 centavo na última linha de pagamento (mesmo
        // truque de PdvVendaService) — depois disso a soma bate exatamente, sem sobra na alocação.
        List<PagamentoRecebimentoRequest> pagamentosAjustados = new ArrayList<>(req.pagamentos());
        if (diferenca.signum() != 0) {
            int ultimo = pagamentosAjustados.size() - 1;
            PagamentoRecebimentoRequest p = pagamentosAjustados.get(ultimo);
            pagamentosAjustados.set(ultimo, new PagamentoRecebimentoRequest(p.idCarteira(), p.valorPago().add(diferenca)));
        }

        List<Alocacao> alocacoes = alocarPagamentos(parcelas, pagamentosAjustados);

        long idCaixa = caixaService.idCaixaAbertoObrigatorio(idEmpresa, idUsuario);

        long idLote = jdbc.sql("""
                        INSERT INTO contas_receber_lote (id_tenant, id_cliente, id_empresa, id_usuario, valor_total)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?)
                        RETURNING id_lote_recebimento
                        """)
                .params(req.idCliente(), idEmpresa, idUsuario, totalParcelas)
                .query(Long.class).single();

        Map<Long, List<Alocacao>> alocacoesPorParcela =
                alocacoes.stream().collect(Collectors.groupingBy(a -> a.parcela().idContaReceber()));

        for (ParcelaResolvida parcela : parcelas) {
            jdbc.sql("""
                            UPDATE contas_receber SET data_recebimento = now(), valor_recebido = ?,
                                id_empresa_pagamento = ?, id_lote_recebimento = ?
                            WHERE id_tenant = plataforma.tenant_atual() AND id_conta_receber = ?
                            """)
                    .params(parcela.total(), idEmpresa, idLote, parcela.idContaReceber())
                    .update();

            gravarDetalheCartaoSeAplicavel(parcela, alocacoesPorParcela.get(parcela.idContaReceber()), carteirasPermitidas);
        }

        for (Alocacao alocacao : alocacoes) {
            jdbc.sql("""
                            INSERT INTO caixa_detalhe
                                (id_tenant, id_caixa, id_carteira, id_venda, id_lote_recebimento, valor,
                                 tipo_operacao, credito_debito)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, 'RECEBIMENTO_PARCELA_CREDIARIO', 'C')
                            """)
                    .params(idCaixa, alocacao.idCarteira(), alocacao.parcela().idVenda(), idLote, alocacao.valor())
                    .update();
        }

        return new RecebimentoEfetivadoResponse(idLote, parcelas.size(), totalParcelas);
    }

    /**
     * Lotes de recebimento já efetivados, candidatos a estorno (2026-07-29). Nome do cliente é
     * obrigatório (400 se vazio); data inicial/final filtram por {@code data_recebimento}.
     */
    @Transactional(readOnly = true)
    public List<LoteRecebimentoResponse> listarLotes(String nomeCliente, LocalDate dataInicial, LocalDate dataFinal) {
        if (branco(nomeCliente)) {
            throw new IllegalArgumentException("Informe o nome do cliente para localizar os recebimentos.");
        }
        StringBuilder filtro = new StringBuilder(" WHERE crl.id_tenant = plataforma.tenant_atual() AND c.nome ILIKE ?");
        List<Object> params = new ArrayList<>();
        params.add("%" + nomeCliente.trim().toUpperCase(Locale.ROOT) + "%");
        if (dataInicial != null) {
            filtro.append(" AND (crl.data_recebimento AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            params.add(dataInicial);
        }
        if (dataFinal != null) {
            filtro.append(" AND (crl.data_recebimento AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            params.add(dataFinal);
        }

        return jdbc.sql("""
                        SELECT crl.id_lote_recebimento, c.nome AS nome_cliente, crl.data_recebimento, crl.valor_total,
                               (SELECT count(*) FROM contas_receber cr
                                WHERE cr.id_tenant = crl.id_tenant AND cr.id_lote_recebimento = crl.id_lote_recebimento) AS qtd_parcelas,
                               (SELECT string_agg(DISTINCT tc.nome_carteira, ', ' ORDER BY tc.nome_carteira)
                                FROM caixa_detalhe cd JOIN tipo_carteira tc
                                     ON tc.id_carteira = cd.id_carteira AND tc.id_tenant = cd.id_tenant
                                WHERE cd.id_tenant = crl.id_tenant AND cd.id_lote_recebimento = crl.id_lote_recebimento
                                ) AS formas_pagamento
                        FROM contas_receber_lote crl
                        JOIN cliente c ON c.id_cliente = crl.id_cliente AND c.id_tenant = crl.id_tenant
                        """ + filtro + " ORDER BY crl.data_recebimento DESC")
                .params(params)
                .query((rs, n) -> new LoteRecebimentoResponse(
                        rs.getLong("id_lote_recebimento"), rs.getString("nome_cliente"),
                        rs.getObject("data_recebimento", OffsetDateTime.class), rs.getBigDecimal("valor_total"),
                        rs.getInt("qtd_parcelas"), rs.getString("formas_pagamento")))
                .list();
    }

    /**
     * Parcelas de um lote, só para visualização antes de decidir estornar (2026-07-29) — não
     * trava nada (readOnly), diferente de {@link #estornarLote(long)}.
     */
    @Transactional(readOnly = true)
    public List<ParcelaDoLoteResponse> listarParcelasDoLote(long idLoteRecebimento) {
        return jdbc.sql("""
                        SELECT cr.id_conta_receber, cr.id_venda, cr.numero_parcela, cr.data_vencimento, cr.valor_recebido,
                               (SELECT count(*) FROM contas_receber cr2
                                WHERE cr2.id_tenant = cr.id_tenant AND cr2.id_venda = cr.id_venda
                                      AND cr2.id_carteira = cr.id_carteira) AS total_parcelas
                        FROM contas_receber cr
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_lote_recebimento = ?
                        ORDER BY cr.id_venda ASC, cr.numero_parcela ASC
                        """)
                .param(idLoteRecebimento)
                .query((rs, n) -> new ParcelaDoLoteResponse(
                        rs.getLong("id_conta_receber"), rs.getLong("id_venda"), rs.getInt("numero_parcela"),
                        rs.getInt("total_parcelas"), rs.getObject("data_vencimento", OffsetDateTime.class),
                        rs.getBigDecimal("valor_recebido")))
                .list();
    }

    /**
     * Comprovante de pagamento pra impressão térmica 80mm (2026-07-30, {@code
     * docs/telas/comprovante-recebimento-crediario.md}) — cabeçalho (empresa/cliente/data/caixa),
     * uma linha por parcela do lote ({@code multaJuros} é a diferença entre o valor cobrado na
     * hora e o valor original da parcela, nunca recalculado depois) e uma linha por forma de
     * pagamento usada (soma de {@code caixa_detalhe.valor} por {@code id_carteira}).
     */
    @Transactional(readOnly = true)
    public ComprovanteRecebimentoResponse buscarComprovante(long idLoteRecebimento) {
        record Cabecalho(
                String nomeEmpresa, String nomeCliente, String telefoneCliente,
                OffsetDateTime dataPagamento, BigDecimal valorTotal) {
        }

        Cabecalho cabecalho = jdbc.sql("""
                        SELECT e.razao_social, c.nome, c.telefone, crl.data_recebimento, crl.valor_total
                        FROM contas_receber_lote crl
                        JOIN cliente c ON c.id_cliente = crl.id_cliente AND c.id_tenant = crl.id_tenant
                        JOIN empresa e ON e.id_empresa = crl.id_empresa AND e.id_tenant = crl.id_tenant
                        WHERE crl.id_tenant = plataforma.tenant_atual() AND crl.id_lote_recebimento = ?
                        """)
                .param(idLoteRecebimento)
                .query((rs, n) -> new Cabecalho(
                        rs.getString("razao_social"), rs.getString("nome"), rs.getString("telefone"),
                        rs.getObject("data_recebimento", OffsetDateTime.class), rs.getBigDecimal("valor_total")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "O lote de recebimento #" + idLoteRecebimento + " não existe."));

        long idCaixa = jdbc.sql("""
                        SELECT DISTINCT id_caixa FROM caixa_detalhe
                        WHERE id_tenant = plataforma.tenant_atual() AND id_lote_recebimento = ?
                        """)
                .param(idLoteRecebimento)
                .query(Long.class)
                .single();

        List<ParcelaComprovanteResponse> parcelas = jdbc.sql("""
                        SELECT cr.id_venda, cr.numero_parcela, cr.valor_receber, cr.valor_recebido, cr.data_vencimento,
                               (SELECT count(*) FROM contas_receber cr2
                                WHERE cr2.id_tenant = cr.id_tenant AND cr2.id_venda = cr.id_venda
                                      AND cr2.id_carteira = cr.id_carteira) AS total_parcelas
                        FROM contas_receber cr
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_lote_recebimento = ?
                        ORDER BY cr.id_venda ASC, cr.numero_parcela ASC
                        """)
                .param(idLoteRecebimento)
                .query((rs, n) -> new ParcelaComprovanteResponse(
                        rs.getLong("id_venda"), rs.getInt("numero_parcela"), rs.getInt("total_parcelas"),
                        rs.getObject("data_vencimento", OffsetDateTime.class), rs.getBigDecimal("valor_receber"),
                        rs.getBigDecimal("valor_recebido").subtract(rs.getBigDecimal("valor_receber")),
                        rs.getBigDecimal("valor_recebido")))
                .list();

        List<PagamentoComprovanteResponse> pagamentos = jdbc.sql("""
                        SELECT tc.nome_carteira, SUM(cd.valor) AS valor_pago
                        FROM caixa_detalhe cd
                        JOIN tipo_carteira tc ON tc.id_carteira = cd.id_carteira AND tc.id_tenant = cd.id_tenant
                        WHERE cd.id_tenant = plataforma.tenant_atual() AND cd.id_lote_recebimento = ?
                        GROUP BY tc.nome_carteira
                        ORDER BY tc.nome_carteira ASC
                        """)
                .param(idLoteRecebimento)
                .query((rs, n) -> new PagamentoComprovanteResponse(rs.getString("nome_carteira"), rs.getBigDecimal("valor_pago")))
                .list();

        return new ComprovanteRecebimentoResponse(idLoteRecebimento, idCaixa, cabecalho.nomeEmpresa(), cabecalho.nomeCliente(),
                cabecalho.telefoneCliente(), cabecalho.dataPagamento(), parcelas, cabecalho.valorTotal(), pagamentos);
    }

    /**
     * Estorna um lote de recebimento inteiro — nunca uma parcela isolada, já que um lote pode
     * cobrir parcelas de vendas/contratos diferentes recebidas juntas na mesma operação (pedido
     * explícito do dono do produto: estornar uma exige estornar todas as do mesmo lote). Reabre
     * cada parcela, apaga o detalhe de cartão associado (mesma tabela cujo esquecimento numa
     * reversão manual causou um 409 espúrio em 2026-07-29 — ver docs/PROGRESSO.md), apaga os
     * lançamentos de caixa do lote e por fim o próprio cabeçalho (decisão do dono do produto:
     * apagar de verdade, mesmo padrão já usado na exclusão de Transferência de Produtos). Nunca
     * mexe em {@code caixa_mestre} — pode ter lançamentos de outros lotes do mesmo dia/usuário/
     * empresa.
     *
     * <p><b>Caixa fechado bloqueia o estorno (2026-08-14).</b> Antes, o DELETE em
     * {@code caixa_detalhe} era incondicional: estornar um recebimento de um dia já fechado
     * apagava a linha em silêncio e a conferência gravada em
     * {@code caixa_fechamento_conferencia} passava a afirmar um total que não existia mais.
     * Agora o estorno recusa e manda reabrir o caixa (ADMIN, em Fechamento de Caixa) — ver
     * {@link CaixaService#exigirCaixaAbertoParaDesfazer}.
     */
    @Transactional
    public EstornoEfetivadoResponse estornarLote(long idLoteRecebimento) {
        caixaService.exigirCaixaAbertoParaDesfazer(VinculoCaixa.LOTE_RECEBIMENTO, idLoteRecebimento);

        BigDecimal valorTotal = jdbc.sql("""
                        SELECT valor_total FROM contas_receber_lote
                        WHERE id_tenant = plataforma.tenant_atual() AND id_lote_recebimento = ?
                        FOR UPDATE
                        """)
                .param(idLoteRecebimento)
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> new ConflitoDadosException(
                        "O lote de recebimento #" + idLoteRecebimento + " não existe ou já foi estornado."));

        // Trava as parcelas do lote antes de mexer (protege contra estorno duplicado em corrida).
        jdbc.sql("""
                        SELECT id_conta_receber FROM contas_receber
                        WHERE id_tenant = plataforma.tenant_atual() AND id_lote_recebimento = ?
                        FOR UPDATE
                        """)
                .param(idLoteRecebimento)
                .query(Long.class)
                .list();

        jdbc.sql("""
                        DELETE FROM contas_receber_detalhe
                        WHERE id_tenant = plataforma.tenant_atual() AND id_conta_receber IN (
                            SELECT id_conta_receber FROM contas_receber
                            WHERE id_tenant = plataforma.tenant_atual() AND id_lote_recebimento = ?
                        )
                        """)
                .param(idLoteRecebimento)
                .update();

        int qtdParcelas = jdbc.sql("""
                        UPDATE contas_receber SET data_recebimento = NULL, valor_recebido = 0,
                            id_empresa_pagamento = NULL, id_lote_recebimento = NULL
                        WHERE id_tenant = plataforma.tenant_atual() AND id_lote_recebimento = ?
                        """)
                .param(idLoteRecebimento)
                .update();

        jdbc.sql("DELETE FROM caixa_detalhe WHERE id_tenant = plataforma.tenant_atual() AND id_lote_recebimento = ?")
                .param(idLoteRecebimento)
                .update();

        jdbc.sql("DELETE FROM contas_receber_lote WHERE id_tenant = plataforma.tenant_atual() AND id_lote_recebimento = ?")
                .param(idLoteRecebimento)
                .update();

        return new EstornoEfetivadoResponse(idLoteRecebimento, qtdParcelas, valorTotal);
    }

    /** RN010: só quando a maior parte do valor da parcela veio de uma carteira de cartão. */
    private void gravarDetalheCartaoSeAplicavel(
            ParcelaResolvida parcela, List<Alocacao> alocacoesDaParcela, Map<Long, CarteiraInfo> carteiras) {
        if (alocacoesDaParcela == null || alocacoesDaParcela.isEmpty()) {
            return;
        }
        Alocacao dominante = alocacoesDaParcela.stream().max(Comparator.comparing(Alocacao::valor)).orElseThrow();
        CarteiraInfo carteira = carteiras.get(dominante.idCarteira());
        if (carteira.categoria() != CategoriaCarteira.CARTAO_DEBITO && carteira.categoria() != CategoriaCarteira.CARTAO_CREDITO) {
            return;
        }
        BigDecimal taxa = carteira.taxaAdministradora() == null ? BigDecimal.ZERO : carteira.taxaAdministradora();
        BigDecimal valorLiquido = parcela.total()
                .subtract(parcela.total().multiply(taxa).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        jdbc.sql("""
                        INSERT INTO contas_receber_detalhe
                            (id_tenant, id_conta_receber, valor_bruto, taxa_administradora, valor_liquido)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?)
                        """)
                .params(parcela.idContaReceber(), parcela.total(), taxa, valorLiquido)
                .update();
    }

    /**
     * Trava a parcela ({@code FOR UPDATE}) e revalida na hora: pertence ao tenant, ainda não
     * recebida (protege contra corrida de duas telas recebendo a mesma parcela ao mesmo tempo),
     * é do cliente informado, e é de fato uma parcela de crediário. Multa/juros recalculados
     * aqui — nunca confia no que veio da listagem (pode estar minutos desatualizada).
     */
    /**  hoje dia da LOJA (fuso da UF da empresa) — multa e juros contam dias de atraso, e
     *              "hoje" do servidor viraria um dia antes da hora para quem está a oeste. */
    private ParcelaResolvida resolverParcelaParaRecebimento(long idContaReceber, long idClienteEsperado,
                                                            ConfigCrediario cfg, LocalDate hoje) {
        record Linha(long idVenda, long idCliente, BigDecimal valorOriginal, OffsetDateTime dataVencimento,
                      int numeroParcela, String categoriaCarteira) {
        }

        Linha linha = jdbc.sql("""
                        SELECT cr.id_venda, v.id_cliente, cr.valor_receber, cr.data_vencimento, cr.numero_parcela,
                               tc.categoria_carteira::text AS categoria_carteira
                        FROM contas_receber cr
                        JOIN venda v ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
                        JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
                        WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_conta_receber = ?
                              AND cr.data_recebimento IS NULL
                        FOR UPDATE
                        """)
                .param(idContaReceber)
                .query((rs, n) -> new Linha(
                        rs.getLong("id_venda"), rs.getLong("id_cliente"), rs.getBigDecimal("valor_receber"),
                        rs.getObject("data_vencimento", OffsetDateTime.class), rs.getInt("numero_parcela"),
                        rs.getString("categoria_carteira")))
                .optional()
                .orElseThrow(() -> new ConflitoDadosException(
                        "A parcela #" + idContaReceber + " não existe ou já foi recebida — nada foi gravado."));

        if (linha.idCliente() != idClienteEsperado) {
            throw new IllegalArgumentException("A parcela #" + idContaReceber + " não pertence ao cliente informado.");
        }
        if (!"CREDIARIO".equals(linha.categoriaCarteira())) {
            throw new IllegalArgumentException("A parcela #" + idContaReceber + " não é uma parcela de crediário.");
        }

        long diasAtraso = Math.max(0, ChronoUnit.DAYS.between(linha.dataVencimento().toLocalDate(), hoje));
        BigDecimal[] multaJuros = calcularMultaJuros(linha.valorOriginal(), diasAtraso, cfg);
        BigDecimal total = linha.valorOriginal().add(multaJuros[0]).add(multaJuros[1]);
        return new ParcelaResolvida(idContaReceber, linha.idVenda(), linha.dataVencimento(), linha.numeroParcela(), total);
    }

    /**
     * Aloca cada linha de pagamento nas parcelas em ordem (a mais antiga primeiro), consumindo
     * o valor de uma até acabar antes de passar pra próxima — permite que uma única parcela
     * seja coberta por mais de uma forma de pagamento (e vice-versa) mantendo rastreabilidade
     * exata de quanto cada forma pagou de cada parcela/venda (id_venda vai certo em cada
     * lançamento de caixa, RN012). Pressupõe que a soma dos pagamentos já bate exatamente com a
     * soma das parcelas (ajustado antes de chamar) — sem essa garantia sobraria/faltaria valor.
     */
    private static List<Alocacao> alocarPagamentos(List<ParcelaResolvida> parcelas, List<PagamentoRecebimentoRequest> pagamentos) {
        List<Alocacao> alocacoes = new ArrayList<>();
        int idxParcela = 0;
        BigDecimal restanteParcela = parcelas.isEmpty() ? BigDecimal.ZERO : parcelas.get(0).total();

        for (PagamentoRecebimentoRequest pagamento : pagamentos) {
            BigDecimal restantePagamento = pagamento.valorPago();
            while (restantePagamento.signum() > 0 && idxParcela < parcelas.size()) {
                BigDecimal usado = restantePagamento.min(restanteParcela);
                alocacoes.add(new Alocacao(parcelas.get(idxParcela), pagamento.idCarteira(), usado));
                restantePagamento = restantePagamento.subtract(usado);
                restanteParcela = restanteParcela.subtract(usado);
                if (restanteParcela.signum() == 0) {
                    idxParcela++;
                    if (idxParcela < parcelas.size()) {
                        restanteParcela = parcelas.get(idxParcela).total();
                    }
                }
            }
        }
        return alocacoes;
    }

    private record ConfigCrediario(BigDecimal jurosCrediario, int jurosCrediarioDias,
                                    BigDecimal multaCrediario, int multaCrediarioDias) {
    }

    private ConfigCrediario buscarConfigCrediario() {
        return jdbc.sql("""
                        SELECT juros_crediario, juros_crediario_dias, multa_crediario, multa_crediario_dias
                        FROM cfg_geral WHERE id_tenant = plataforma.tenant_atual()
                        """)
                .query((rs, n) -> new ConfigCrediario(
                        rs.getBigDecimal("juros_crediario"), rs.getInt("juros_crediario_dias"),
                        rs.getBigDecimal("multa_crediario"), rs.getInt("multa_crediario_dias")))
                .optional()
                .orElse(new ConfigCrediario(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0));
    }

    /**
     * Multa: percentual único sobre o valor original, só se os dias de atraso passarem da
     * carência ({@code multa_crediario_dias}) — não cresce com o tempo. Juros: percentual AO
     * DIA sobre o valor original, por cada dia de atraso além da carência ({@code
     * juros_crediario_dias}) — cresce a cada dia adicional. Fórmulas confirmadas com o dono do
     * produto em 2026-07-29 (mesma leitura que os rótulos "Juros após (dias)"/"Multa após
     * (dias)" de Parâmetros do Sistema já sugeriam).
     */
    private static BigDecimal[] calcularMultaJuros(BigDecimal valorOriginal, long diasAtraso, ConfigCrediario cfg) {
        BigDecimal multa = diasAtraso > cfg.multaCrediarioDias()
                ? arredondar(valorOriginal.multiply(cfg.multaCrediario()).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))
                : BigDecimal.ZERO;
        long diasComJuros = Math.max(0, diasAtraso - cfg.jurosCrediarioDias());
        BigDecimal juros = diasComJuros == 0 ? BigDecimal.ZERO
                : arredondar(valorOriginal.multiply(cfg.jurosCrediario()).multiply(BigDecimal.valueOf(diasComJuros))
                        .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        return new BigDecimal[]{multa, juros};
    }

    private static BigDecimal arredondar(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean branco(String valor) {
        return valor == null || valor.isBlank();
    }

    private record CarteiraInfo(long idCarteira, CategoriaCarteira categoria, BigDecimal taxaAdministradora) {
    }

    private Map<Long, CarteiraInfo> mapaCarteirasPermitidas() {
        List<CarteiraInfo> lista = jdbc.sql("""
                        SELECT id_carteira, categoria_carteira::text AS categoria_carteira, taxa_administradora
                        FROM tipo_carteira
                        WHERE id_tenant = plataforma.tenant_atual() AND""" + FILTRO_CARTEIRAS_PERMITIDAS)
                .query((rs, n) -> new CarteiraInfo(
                        rs.getLong("id_carteira"), CategoriaCarteira.valueOf(rs.getString("categoria_carteira")),
                        rs.getBigDecimal("taxa_administradora")))
                .list();
        Map<Long, CarteiraInfo> mapa = new HashMap<>();
        for (CarteiraInfo info : lista) {
            mapa.put(info.idCarteira(), info);
        }
        return mapa;
    }

    private record ParcelaResolvida(long idContaReceber, long idVenda, OffsetDateTime dataVencimento,
                                     int numeroParcela, BigDecimal total) {
    }

    private record Alocacao(ParcelaResolvida parcela, long idCarteira, BigDecimal valor) {
    }

    private static ParcelaCrediarioResponse mapearParcela(ResultSet rs, ConfigCrediario cfg) throws SQLException {
        BigDecimal valorOriginal = rs.getBigDecimal("valor_receber");
        long diasAtraso = rs.getLong("dias_atraso");
        BigDecimal[] multaJuros = calcularMultaJuros(valorOriginal, diasAtraso, cfg);
        BigDecimal total = valorOriginal.add(multaJuros[0]).add(multaJuros[1]);
        return new ParcelaCrediarioResponse(
                rs.getLong("id_conta_receber"), rs.getLong("id_venda"), rs.getInt("codigo_empresa"),
                rs.getObject("data_venda", OffsetDateTime.class), rs.getInt("numero_parcela"),
                rs.getInt("total_parcelas"), rs.getObject("data_vencimento", OffsetDateTime.class),
                valorOriginal, multaJuros[0], multaJuros[1], total);
    }
}
