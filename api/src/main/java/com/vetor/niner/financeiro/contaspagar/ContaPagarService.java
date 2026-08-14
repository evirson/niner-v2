package com.vetor.niner.financeiro.contaspagar;

import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.ContaPagarRequest;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.ContaPagarResponse;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.ExclusaoContaPagarResponse;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.OrigemPagamento;
import com.vetor.niner.financeiro.contaspagar.ContaPagarDtos.PaginaContasPagar;
import com.vetor.niner.financeiro.caixa.CaixaDtos.CaixaStatusResponse;
import com.vetor.niner.financeiro.caixa.CaixaService;
import com.vetor.niner.financeiro.caixa.CaixaService.VinculoCaixa;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de Contas a Pagar / Pagas (docs/telas/contas-pagar.md) — tabela {@code contas_pagar}
 * (V026) sob RLS de tenant, FK composta pra {@code fornecedor}/{@code empresa}/{@code
 * cfg_plano_contas} (mesmo padrão de {@code financeiro.contacorrente}, JOIN qualificado por
 * tenant nas três pontas). Distinto de {@link com.vetor.niner.estoque.entrada.ContasPagarService}
 * (helper interno de INSERT usado só pela Entrada de Produtos por Compra) — este é o CRUD
 * completo por trás da tela própria da conta a pagar.
 */
@Service
public class ContaPagarService {

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "dataVencimento", "cp.data_vencimento",
            "dataPagamento", "cp.data_pagamento",
            "fornecedor", "f.razao_social",
            "empresa", "e.razao_social",
            "notaFiscal", "cp.nota_fiscal",
            "valorPagar", "cp.valor_pagar",
            "valorPago", "cp.valor_pago");

    private final JdbcClient jdbc;
    /** Só pra achar o caixa aberto do usuário na baixa em dinheiro (ver
     *  {@code sincronizarMovimentoDeDinheiro}). */
    private final CaixaService caixaService;

    public ContaPagarService(JdbcClient jdbc, CaixaService caixaService) {
        this.jdbc = jdbc;
        this.caixaService = caixaService;
    }

    @Transactional(readOnly = true)
    public PaginaContasPagar listar(Long idFornecedor, Long idEmpresa, Integer notaFiscal, String numeroDuplicata,
                                     LocalDate dataVencimentoInicial, LocalDate dataVencimentoFinal,
                                     LocalDate dataPagamentoInicial, LocalDate dataPagamentoFinal,
                                     Integer pagina, Integer limite, String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao =
                ordenarPor == null ? "cp.data_vencimento" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "cp.data_vencimento");
        String direcaoOrdenacao = "ASC".equalsIgnoreCase(direcao) ? "ASC" : "DESC";

        StringBuilder filtro = new StringBuilder(" WHERE cp.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();

        if (idFornecedor != null) {
            filtro.append(" AND cp.id_fornecedor = ?");
            params.add(idFornecedor);
        }
        if (idEmpresa != null) {
            filtro.append(" AND cp.id_empresa = ?");
            params.add(idEmpresa);
        }
        if (notaFiscal != null) {
            filtro.append(" AND cp.nota_fiscal = ?");
            params.add(notaFiscal);
        }
        if (numeroDuplicata != null && !numeroDuplicata.isBlank()) {
            filtro.append(" AND cp.numero_duplicata ILIKE ?");
            params.add("%" + numeroDuplicata.trim() + "%");
        }
        // "AT TIME ZONE 'America/Sao_Paulo'" (não UTC, a sessão do banco) — mesma correção já
        // aplicada em EntradaMercadoriaService.listar (2026-08-12): a tela mostra data em
        // horário local do navegador, então o filtro precisa bucketizar pelo mesmo dia local.
        if (dataVencimentoInicial != null) {
            filtro.append(" AND (cp.data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            params.add(dataVencimentoInicial);
        }
        if (dataVencimentoFinal != null) {
            filtro.append(" AND (cp.data_vencimento AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            params.add(dataVencimentoFinal);
        }
        if (dataPagamentoInicial != null) {
            filtro.append(" AND (cp.data_pagamento AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            params.add(dataPagamentoInicial);
        }
        if (dataPagamentoFinal != null) {
            filtro.append(" AND (cp.data_pagamento AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            params.add(dataPagamentoFinal);
        }

        long totalItens = jdbc.sql("""
                        SELECT count(*)
                        FROM contas_pagar cp
                        JOIN fornecedor f ON f.id_tenant = cp.id_tenant AND f.id_fornecedor = cp.id_fornecedor
                        JOIN empresa e ON e.id_tenant = cp.id_tenant AND e.id_empresa = cp.id_empresa
                        """ + filtro)
                .params(params)
                .query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", cp.id_conta_pagar " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<ContaPagarResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(ContaPagarService::mapear)
                .list();

        return new PaginaContasPagar(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public ContaPagarResponse buscar(long idContaPagar) {
        return jdbc.sql(SELECT_BASE + " WHERE cp.id_tenant = plataforma.tenant_atual() AND cp.id_conta_pagar = ?")
                .param(idContaPagar)
                .query(ContaPagarService::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Conta a pagar não encontrada."));
    }

    /**
     * Sincroniza o **movimento de dinheiro** da baixa (2026-08-14, docs/telas/fluxo-caixa.md).
     *
     * <p>Antes desta mudança, pagar uma conta só preenchia {@code data_pagamento} — o dinheiro não
     * passava por {@code caixa_detalhe} nem por {@code conta_corrente_movimento}, e por isso um
     * fluxo de caixa montado sobre as tabelas de dinheiro ficaria praticamente sem saídas.
     *
     * <p>Estratégia: **apaga e regrava**. O DELETE incondicional cobre de uma vez os três casos
     * que dariam errado se tratados separadamente — baixa desfeita (some o movimento), troca de
     * origem (caixa → banco) e correção de valor/data. Como o vínculo é `id_conta_pagar`, nunca
     * apaga movimento de outra origem.
     */
    private void sincronizarMovimentoDeDinheiro(Jwt jwt, long idContaPagar, ContaPagarRequest req) {
        // Caixa fechado bloqueia (2026-08-14): o DELETE abaixo apagaria lançamento de um caixa já
        // conferido, fazendo a conferência gravada mentir. Manda reabrir em vez de passar batido.
        caixaService.exigirCaixaAbertoParaDesfazer(VinculoCaixa.CONTA_PAGAR, idContaPagar);

        jdbc.sql("DELETE FROM caixa_detalhe WHERE id_tenant = plataforma.tenant_atual() AND id_conta_pagar = ?")
                .param(idContaPagar).update();
        jdbc.sql("DELETE FROM conta_corrente_movimento WHERE id_tenant = plataforma.tenant_atual() AND id_conta_pagar = ?")
                .param(idContaPagar).update();

        if (req.dataPagamento() == null) {
            return;
        }

        // Valor que de fato saiu: `valor_pago` quando informado, senão o valor da conta — evita
        // gravar movimento de R$ 0,00 quando a tela manda só a data (baixa "cheia").
        BigDecimal valor = req.valorPago() == null || req.valorPago().compareTo(BigDecimal.ZERO) == 0
                ? req.valorPagar()
                : req.valorPago();
        String idPlanoContas = req.idPlanoContas().trim().toUpperCase(Locale.ROOT);

        if (req.origemPagamento() == OrigemPagamento.CONTA_CORRENTE) {
            if (req.idContaCorrente() == null || req.idContaCorrente().isBlank()) {
                throw new IllegalArgumentException("Informe a conta corrente de onde o pagamento saiu.");
            }
            jdbc.sql("""
                            INSERT INTO conta_corrente_movimento
                                (id_tenant, id_conta_corrente, id_conta_pagar, id_plano_contas, data_movimento,
                                 numero_documento, credito_debito, valor, observacao)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, 'D', ?, ?)
                            """)
                    .params(req.idContaCorrente().trim().toUpperCase(Locale.ROOT), idContaPagar, idPlanoContas,
                            req.dataPagamento(),
                            req.numeroDuplicata() == null ? "PAGAMENTO" : req.numeroDuplicata().trim(),
                            valor, "Pagamento da conta a pagar nº " + idContaPagar)
                    .update();
            return;
        }

        // CAIXA: usa o caixa aberto do usuário (decisão de 2026-08-14) — mesma convenção do PDV e
        // do Recebimento de Crediário, que também não deixam escolher a sessão de caixa.
        CaixaStatusResponse caixa = caixaService.status(jwt);
        if (!caixa.aberto()) {
            throw new IllegalArgumentException(
                    "Não há caixa aberto para registrar o pagamento em dinheiro. Abra o caixa ou pague pela conta corrente.");
        }
        jdbc.sql("""
                        INSERT INTO caixa_detalhe
                            (id_tenant, id_caixa, id_carteira, id_conta_pagar, id_plano_contas, valor,
                             tipo_operacao, credito_debito, observacoes)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, 'DEBITO_CAIXA', 'D', ?)
                        """)
                .params(caixa.idCaixa(), caixa.idCarteira(), idContaPagar, idPlanoContas, valor,
                        "Pagamento da conta a pagar nº " + idContaPagar)
                .update();
    }

    /** A baixa exige dizer de onde o dinheiro saiu — senão o fluxo de caixa fica sem a saída. */
    private static void validarOrigemDoPagamento(ContaPagarRequest req) {
        if (req.dataPagamento() != null && req.origemPagamento() == null) {
            throw new IllegalArgumentException(
                    "Informe de onde saiu o dinheiro (caixa ou conta corrente) ao registrar o pagamento.");
        }
    }

    /**
     * Decide se a edição precisa mexer no movimento de dinheiro — e é aqui que mora a exceção que
     * evita uma armadilha: **conta que já estava paga antes desta mudança (2026-08-14) não tem
     * movimento vinculado**, e exigir a origem dela travaria para sempre qualquer edição
     * (mudar uma observação de uma conta paga em julho devolveria 400 sem saída).
     *
     * <ul>
     *   <li>Tirou o pagamento → sincroniza (apaga o movimento, desfazendo a baixa).</li>
     *   <li>Informou a origem → sincroniza (regrava: cobre troca de origem e correção de valor).</li>
     *   <li>Baixa <b>nova</b> sem origem → 400, que é a regra que dá sentido ao fluxo de caixa.</li>
     *   <li>Conta <b>já paga antes</b>, edição que não mexe no pagamento → não toca no movimento.</li>
     * </ul>
     */
    private void sincronizarMovimentoNaEdicao(
            Jwt jwt, long idContaPagar, ContaPagarRequest req, OffsetDateTime pagamentoAnterior) {
        if (req.dataPagamento() == null || req.origemPagamento() != null) {
            sincronizarMovimentoDeDinheiro(jwt, idContaPagar, req);
            return;
        }
        if (pagamentoAnterior == null) {
            throw new IllegalArgumentException(
                    "Informe de onde saiu o dinheiro (caixa ou conta corrente) ao registrar o pagamento.");
        }
    }

    @Transactional
    public ContaPagarResponse criar(Jwt jwt, ContaPagarRequest req) {
        validarOrigemDoPagamento(req);
        try {
            long idContaPagar = jdbc.sql("""
                            INSERT INTO contas_pagar
                                (id_tenant, id_empresa, id_fornecedor, id_plano_contas, nota_fiscal, numero_duplicata,
                                 data_lancamento, data_vencimento, data_pagamento, valor_pagar, valor_pago,
                                 documento_pago, observacoes)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id_conta_pagar
                            """)
                    .params(req.idEmpresa(), req.idFornecedor(), req.idPlanoContas().trim().toUpperCase(Locale.ROOT),
                            req.notaFiscal(), trimOuNulo(req.numeroDuplicata()), req.dataLancamento(),
                            req.dataVencimento(), req.dataPagamento(), req.valorPagar(),
                            req.valorPago() == null ? BigDecimal.ZERO : req.valorPago(),
                            Boolean.TRUE.equals(req.documentoPago()), trimOuNulo(req.observacoes()))
                    .query(Long.class).single();
            sincronizarMovimentoDeDinheiro(jwt, idContaPagar, req);
            return buscar(idContaPagar);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Fornecedor, empresa ou plano de contas informado não existe.");
        }
    }

    @Transactional
    public ContaPagarResponse atualizar(Jwt jwt, long idContaPagar, ContaPagarRequest req) {
        // Estado do pagamento ANTES do update — é o que distingue "baixa nova" de "conta que já
        // estava paga" (ver sincronizarMovimentoNaEdicao).
        OffsetDateTime pagamentoAnterior = jdbc.sql("""
                        SELECT data_pagamento FROM contas_pagar
                        WHERE id_tenant = plataforma.tenant_atual() AND id_conta_pagar = ?
                        """)
                .param(idContaPagar)
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
        try {
            int linhasAtualizadas = jdbc.sql("""
                            UPDATE contas_pagar SET
                                id_empresa = ?, id_fornecedor = ?, id_plano_contas = ?, nota_fiscal = ?,
                                numero_duplicata = ?, data_lancamento = ?, data_vencimento = ?, data_pagamento = ?,
                                valor_pagar = ?, valor_pago = ?, documento_pago = ?, observacoes = ?, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_conta_pagar = ?
                            """)
                    .params(req.idEmpresa(), req.idFornecedor(), req.idPlanoContas().trim().toUpperCase(Locale.ROOT),
                            req.notaFiscal(), trimOuNulo(req.numeroDuplicata()), req.dataLancamento(),
                            req.dataVencimento(), req.dataPagamento(), req.valorPagar(),
                            req.valorPago() == null ? BigDecimal.ZERO : req.valorPago(),
                            Boolean.TRUE.equals(req.documentoPago()), trimOuNulo(req.observacoes()), idContaPagar)
                    .update();
            if (linhasAtualizadas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Conta a pagar não encontrada.");
            }
            sincronizarMovimentoNaEdicao(jwt, idContaPagar, req, pagamentoAnterior);
            return buscar(idContaPagar);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Fornecedor, empresa ou plano de contas informado não existe.");
        }
    }

    /**
     * Sempre exclui de verdade — {@code contas_pagar} não tem {@code ativo}, nada referencia
     * esta tabela por FK (mesmo caso de {@code conta_corrente_movimento}). Uma conta gerada por
     * uma Entrada ({@code id_movimento} preenchido) pode ser excluída aqui sem problema — se a
     * entrada for cancelada depois, o DELETE por {@code id_movimento} do Cancelamento de Entrada
     * simplesmente não encontra mais nada pra apagar.
     *
     * <p><b>Desfaz o movimento de dinheiro junto (2026-08-14).</b> Até aqui, excluir apagava só
     * a linha de {@code contas_pagar} e deixava órfãos o {@code caixa_detalhe} e o
     * {@code conta_corrente_movimento} gerados pela baixa: o dinheiro seguia saindo do caixa e do
     * banco para sempre, sem nenhuma conta que o justificasse — e como essas colunas de vínculo
     * **não têm FK** (escolha deliberada de V025/V028), o banco não reclamava. O DELETE agora é o
     * mesmo de {@link #sincronizarMovimentoDeDinheiro}, e vem antes do DELETE da conta.
     *
     * <p>Vale para a <b>reabertura</b> também (tirar a data de pagamento na edição), que já
     * desfazia o movimento corretamente por {@code sincronizarMovimentoNaEdicao} — a diferença é
     * que agora os dois caminhos passam pelo mesmo guard de caixa fechado.
     */
    @Transactional
    public ExclusaoContaPagarResponse excluir(Jwt jwt, long idContaPagar) {
        caixaService.exigirCaixaAbertoParaDesfazer(VinculoCaixa.CONTA_PAGAR, idContaPagar);

        jdbc.sql("DELETE FROM caixa_detalhe WHERE id_tenant = plataforma.tenant_atual() AND id_conta_pagar = ?")
                .param(idContaPagar).update();
        jdbc.sql("DELETE FROM conta_corrente_movimento WHERE id_tenant = plataforma.tenant_atual() AND id_conta_pagar = ?")
                .param(idContaPagar).update();

        int linhas = jdbc.sql("DELETE FROM contas_pagar WHERE id_tenant = plataforma.tenant_atual() AND id_conta_pagar = ?")
                .param(idContaPagar).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Conta a pagar não encontrada.");
        }
        return new ExclusaoContaPagarResponse("excluido");
    }

    private static String trimOuNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static final String SELECT_BASE = """
            SELECT cp.id_conta_pagar, cp.id_fornecedor, f.razao_social AS nome_fornecedor,
                   cp.id_empresa, e.razao_social AS nome_empresa, cp.id_plano_contas,
                   pc.descricao AS descricao_plano_contas, cp.nota_fiscal, cp.numero_duplicata,
                   cp.data_lancamento, cp.data_vencimento, cp.data_pagamento, cp.valor_pagar,
                   cp.valor_pago, cp.documento_pago, cp.observacoes, cp.id_movimento,
                   -- Origem do pagamento é DERIVADA do movimento gerado na baixa, não uma coluna
                   -- de contas_pagar: o movimento é a verdade sobre onde o dinheiro está, e assim
                   -- não existe estado duplicado pra sair de sincronia (2026-08-14).
                   (SELECT ccm.id_conta_corrente FROM conta_corrente_movimento ccm
                    WHERE ccm.id_tenant = cp.id_tenant AND ccm.id_conta_pagar = cp.id_conta_pagar
                    LIMIT 1) AS id_conta_corrente_pagamento,
                   EXISTS (SELECT 1 FROM caixa_detalhe cd
                           WHERE cd.id_tenant = cp.id_tenant AND cd.id_conta_pagar = cp.id_conta_pagar)
                       AS pago_pelo_caixa,
                   cp.criado_em, cp.atualizado_em
            FROM contas_pagar cp
            JOIN fornecedor f ON f.id_tenant = cp.id_tenant AND f.id_fornecedor = cp.id_fornecedor
            JOIN empresa e ON e.id_tenant = cp.id_tenant AND e.id_empresa = cp.id_empresa
            JOIN cfg_plano_contas pc ON pc.id_tenant = cp.id_tenant AND pc.id_plano_contas = cp.id_plano_contas
            """;

    /** {@code null} enquanto a conta não foi baixada (ou foi baixada antes de 2026-08-14, quando
     *  a baixa ainda não gerava movimento — ver docs/telas/fluxo-caixa.md). */
    private static OrigemPagamento origemDoPagamento(ResultSet rs) throws SQLException {
        if (rs.getString("id_conta_corrente_pagamento") != null) {
            return OrigemPagamento.CONTA_CORRENTE;
        }
        return rs.getBoolean("pago_pelo_caixa") ? OrigemPagamento.CAIXA : null;
    }

    private static ContaPagarResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new ContaPagarResponse(
                rs.getLong("id_conta_pagar"),
                rs.getLong("id_fornecedor"),
                rs.getString("nome_fornecedor"),
                rs.getLong("id_empresa"),
                rs.getString("nome_empresa"),
                rs.getString("id_plano_contas"),
                rs.getString("descricao_plano_contas"),
                (Integer) rs.getObject("nota_fiscal"),
                rs.getString("numero_duplicata"),
                rs.getObject("data_lancamento", OffsetDateTime.class),
                rs.getObject("data_vencimento", OffsetDateTime.class),
                rs.getObject("data_pagamento", OffsetDateTime.class),
                rs.getBigDecimal("valor_pagar"),
                rs.getBigDecimal("valor_pago"),
                rs.getBoolean("documento_pago"),
                rs.getString("observacoes"),
                origemDoPagamento(rs),
                rs.getString("id_conta_corrente_pagamento"),
                getLongOuNulo(rs, "id_movimento"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }

    /** {@code rs.getObject(coluna, Long.class)} não converte `integer` pra `Long` de forma
     *  confiável no driver — mesmo padrão já usado em `EntradaMercadoriaService`. */
    private static Long getLongOuNulo(ResultSet rs, String coluna) throws SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }
}
