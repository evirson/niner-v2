package com.vetor.niner.vendas.ordemservico;

import com.vetor.niner.comum.seguranca.EmpresaDaSessao;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.configuracao.geral.ConfiguracaoGeralService;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.ItemRequest;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.ItemResponse;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.LinhaListagem;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.OrdemServicoRequest;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.OrdemServicoResponse;
import com.vetor.niner.vendas.ordemservico.OrdemServicoDtos.PaginaOrdensServico;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Ordem de Serviço (bloco S4 de {@code docs/MODULOSERVICOS.md}).
 *
 * <p>⛔ <b>OS não é orçamento</b> (reforço do dono do produto, 2026-08-28): entidade própria, tabela
 * própria, estados próprios. O que se reaproveita é o <b>mecanismo</b> — a OS concluída abre no PDV
 * pelo F5 e vira venda pelo {@code PdvVendaService}, exatamente como o orçamento faz.
 *
 * <p>⭐ <b>Este serviço NUNCA grava dinheiro.</b> Nada aqui toca {@code caixa_detalhe},
 * {@code contas_receber}, o ledger de estoque ou documento fiscal — quem faz isso é o PDV, onde
 * moram o caixa aberto obrigatório, o split-tender, o desconto máximo, o limite de crédito, a cota
 * do plano, a papeleta e a emissão fiscal. Uma segunda porta de faturamento teria de reimplementar
 * as sete, e é assim que nasce o teto com porta ao lado.
 *
 * <p>A única coisa que ele mexe fora de si é a <b>reserva</b> ({@code produto_estoque.reservado}),
 * por decisão dele (DS15): na oficina a peça é separada fisicamente para aquele carro.
 */
@Service
public class OrdemServicoService {

    /** Estados de que a OS ainda pode sair — depois deles ela é história (DS20). */
    private static final List<String> ESTADOS_EDITAVEIS =
            List.of("ABERTA", "APROVADA", "EM_EXECUCAO", "CONCLUIDA");

    /**
     * ⭐ DS18 — só CONCLUIDA vai ao PDV. Decisão dele em 2026-08-28: o trabalho tem de estar pronto
     * para virar venda; quem cobra adiantado marca como concluída antes.
     */
    private static final String ESTADO_FATURAVEL = "CONCLUIDA";

    private final JdbcClient jdbc;
    private final ConfiguracaoGeralService configuracaoGeralService;

    public OrdemServicoService(JdbcClient jdbc, ConfiguracaoGeralService configuracaoGeralService) {
        this.configuracaoGeralService = configuracaoGeralService;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- leitura

    @Transactional(readOnly = true)
    public PaginaOrdensServico listar(Jwt jwt, String busca, String situacao,
                                      LocalDate dataInicial, LocalDate dataFinal,
                                      int pagina, int limite) {
        StringBuilder filtro = new StringBuilder("""
                 WHERE os.id_tenant = plataforma.tenant_atual()
                   AND os.id_empresa = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(EmpresaDaSessao.idEmpresaDaSessao(jwt));

        if (busca != null && !busca.isBlank()) {
            // Uma busca só, como o balcão procura: placa/animal OU nome do cliente OU o número.
            filtro.append(" AND (upper(os.objeto_servico) LIKE ? OR upper(c.nome) LIKE ?")
                  .append(" OR CAST(os.id_ordem_servico AS text) = ?)");
            String alvo = "%" + busca.trim().toUpperCase(Locale.ROOT) + "%";
            params.add(alvo);
            params.add(alvo);
            params.add(busca.trim());
        }
        if (situacao != null && !situacao.isBlank()) {
            filtro.append(" AND os.situacao = CAST(? AS situacao_ordem_servico)");
            params.add(situacao.trim().toUpperCase(Locale.ROOT));
        }
        // Data no fuso da LOJA dos dois lados — a sessão do Postgres roda em UTC e, a partir das
        // 21:00 de Brasília, `now()` já é o dia seguinte (convenção do projeto desde 2026-08-19).
        if (dataInicial != null) {
            filtro.append(" AND (os.data_abertura AT TIME ZONE 'America/Sao_Paulo')::date >= ?");
            params.add(dataInicial);
        }
        if (dataFinal != null) {
            filtro.append(" AND (os.data_abertura AT TIME ZONE 'America/Sao_Paulo')::date <= ?");
            params.add(dataFinal);
        }

        long total = jdbc.sql("SELECT count(*) FROM ordem_servico os"
                        + " JOIN cliente c ON c.id_tenant = os.id_tenant AND c.id_cliente = os.id_cliente"
                        + filtro)
                .params(params).query(Long.class).single();

        int tamanho = Math.clamp(limite, 1, 100);
        int paginaEfetiva = Math.max(pagina, 1);
        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add(tamanho);
        paramsPagina.add((long) (paginaEfetiva - 1) * tamanho);

        List<LinhaListagem> itens = jdbc.sql("""
                        SELECT os.id_ordem_servico, c.nome AS nome_cliente, os.objeto_servico,
                               os.situacao::text AS situacao, os.data_abertura, os.id_venda,
                               COALESCE((SELECT SUM(i.qtd_produto * i.preco_venda)
                                           FROM ordem_servico_item i
                                          WHERE i.id_tenant = os.id_tenant
                                            AND i.id_ordem_servico = os.id_ordem_servico), 0)
                                 - os.valor_desconto AS total
                          FROM ordem_servico os
                          JOIN cliente c ON c.id_tenant = os.id_tenant AND c.id_cliente = os.id_cliente
                        """ + filtro + " ORDER BY os.data_abertura DESC LIMIT ? OFFSET ?")
                .params(paramsPagina)
                .query((rs, n) -> new LinhaListagem(
                        rs.getLong("id_ordem_servico"), rs.getString("nome_cliente"),
                        rs.getString("objeto_servico"), rs.getString("situacao"),
                        rs.getObject("data_abertura", OffsetDateTime.class),
                        rs.getBigDecimal("total"), idVendaOuNulo(rs)))
                .list();

        int totalPaginas = (int) Math.ceil((double) total / tamanho);
        return new PaginaOrdensServico(itens, paginaEfetiva, tamanho, total, totalPaginas);
    }

    /**
     * A OS pedida, <b>conferindo a empresa da sessão</b> (2026-08-30).
     *
     * <p>⚠️ O guarda fica AQUI, no ponto de entrada público, e não em {@link #montarResposta}: os
     * cinco métodos internos que montam a resposta ({@code criar}, {@code atualizar},
     * {@code mudarSituacao}, {@code cancelar}, {@code abrirParaVenda}) já conferiram a empresa
     * antes de chegar lá — repetir a checagem no montador faria o {@code criar} conferir uma OS
     * que ele mesmo acabou de gravar na empresa da sessão.
     */
    @Transactional(readOnly = true)
    public OrdemServicoResponse buscar(Jwt jwt, long id) {
        exigirEmpresaDaSessao(jwt, id);
        return montarResposta(id);
    }

    private OrdemServicoResponse montarResposta(long id) {
        OrdemServicoResponse cabecalho = jdbc.sql("""
                        SELECT os.id_ordem_servico, os.id_empresa, os.id_cliente, c.nome AS nome_cliente,
                               c.cpf_cnpj AS documento_cliente, c.telefone AS telefone_cliente,
                               e.razao_social AS nome_empresa,
                               os.id_funcionario, f.nome AS nome_funcionario, os.objeto_servico, os.observacao,
                               os.situacao::text AS situacao, os.data_abertura, os.data_conclusao,
                               os.valor_desconto, os.id_venda, os.data_faturamento,
                               os.data_cancelamento, os.motivo_cancelamento, os.criado_em, os.atualizado_em
                          FROM ordem_servico os
                          JOIN cliente c ON c.id_tenant = os.id_tenant AND c.id_cliente = os.id_cliente
                          JOIN funcionario f ON f.id_tenant = os.id_tenant AND f.id_funcionario = os.id_funcionario
                          JOIN empresa e ON e.id_tenant = os.id_tenant AND e.id_empresa = os.id_empresa
                         WHERE os.id_tenant = plataforma.tenant_atual() AND os.id_ordem_servico = ?
                        """)
                .param(id)
                .query(this::mapearCabecalho)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ordem de serviço não encontrada."));

        List<ItemResponse> itens = buscarItens(id);
        BigDecimal totalServicos = somar(itens, "SERVICO");
        BigDecimal totalPecas = somar(itens, "MERCADORIA");
        BigDecimal total = totalServicos.add(totalPecas).subtract(cabecalho.valorDesconto());

        return new OrdemServicoResponse(
                cabecalho.idOrdemServico(), cabecalho.idEmpresa(), cabecalho.idCliente(),
                cabecalho.nomeCliente(), cabecalho.documentoCliente(), cabecalho.telefoneCliente(),
                cabecalho.nomeEmpresa(), cabecalho.idFuncionario(), cabecalho.nomeFuncionario(),
                cabecalho.objetoServico(), cabecalho.observacao(), cabecalho.situacao(),
                cabecalho.dataAbertura(), cabecalho.dataConclusao(), cabecalho.valorDesconto(),
                totalServicos, totalPecas, total, itens,
                cabecalho.idVenda(), cabecalho.dataFaturamento(), cabecalho.dataCancelamento(),
                cabecalho.motivoCancelamento(), cabecalho.criadoEm(), cabecalho.atualizadoEm());
    }

    // ---------------------------------------------------------------- escrita

    @Transactional
    public OrdemServicoResponse criar(Jwt jwt, OrdemServicoRequest req) {
        exigirModuloLigado();
        exigirDescontoDentroDoTeto(req);
        long idEmpresa = EmpresaDaSessao.idEmpresaDaSessao(jwt);
        long idUsuario = Long.parseLong(jwt.getSubject());

        long id = jdbc.sql("""
                        INSERT INTO ordem_servico (id_tenant, id_empresa, id_cliente, id_funcionario,
                                                   id_usuario, objeto_servico, observacao, valor_desconto)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id_ordem_servico
                        """)
                .params(idEmpresa, req.idCliente(), req.idFuncionario(), idUsuario,
                        req.objetoServico().trim().toUpperCase(Locale.ROOT),
                        req.observacao() == null || req.observacao().isBlank()
                                ? null : req.observacao().trim().toUpperCase(Locale.ROOT),
                        req.valorDesconto() == null ? BigDecimal.ZERO : req.valorDesconto())
                .query(Long.class).single();

        gravarItens(id, idEmpresa, req.itens());
        return montarResposta(id);
    }

    /**
     * ⭐ A OS é <b>mutável</b> — é a diferença essencial para o orçamento, e a razão de ela existir:
     * o mecânico abre o motor e acha mais serviço.
     *
     * <p>⚠️ A reserva é recalculada por <b>apaga e regrava</b>: as reservas das linhas atuais são
     * liberadas pelo valor que <b>elas</b> reservaram ({@code qtd_reservada}, guardado por linha) e
     * as novas reservam de novo. Liberar "pela quantidade de agora" deixaria resto pendurado toda
     * vez que alguém corrigisse uma quantidade.
     */
    @Transactional
    public OrdemServicoResponse atualizar(Jwt jwt, long id, OrdemServicoRequest req) {
        exigirEmpresaDaSessao(jwt, id);
        String situacao = situacaoAtual(id);
        exigirEditavel(situacao, "alterar");
        exigirDescontoDentroDoTeto(req);

        // ⛔ A empresa é a DA OS, não a da sessão (achado de auditoria, 2026-08-29). `liberarReservas`
        // sempre leu `os.id_empresa`; `gravarItens` recebia a da sessão. Num tenant com duas
        // empresas, abrir a OS da empresa A com a sessão em B liberava a reserva em A e aplicava em
        // B — e como a OS continua sendo de A, todo `liberarReservas` seguinte descontava de A
        // (onde `GREATEST(reservado - x, 0)` já é 0): **a reserva ficava pendurada em B para
        // sempre**, e o disponível de B nunca voltava.
        // ⚠️ O `jwt` continua no parâmetro porque a assinatura é pública e o controller a usa;
        // trocá-la aqui seria churn sem ganho.
        long idEmpresa = empresaDaOs(id);
        jdbc.sql("""
                        UPDATE ordem_servico SET id_cliente = ?, id_funcionario = ?, objeto_servico = ?,
                               observacao = ?, valor_desconto = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_ordem_servico = ?
                        """)
                .params(req.idCliente(), req.idFuncionario(),
                        req.objetoServico().trim().toUpperCase(Locale.ROOT),
                        req.observacao() == null || req.observacao().isBlank()
                                ? null : req.observacao().trim().toUpperCase(Locale.ROOT),
                        req.valorDesconto() == null ? BigDecimal.ZERO : req.valorDesconto(),
                        id)
                .update();

        liberarReservas(id);
        jdbc.sql("DELETE FROM ordem_servico_item WHERE id_tenant = plataforma.tenant_atual()"
                        + " AND id_ordem_servico = ?")
                .param(id).update();
        gravarItens(id, idEmpresa, req.itens());
        return montarResposta(id);
    }

    /**
     * Avança o estado. Só para frente e um passo por vez — pular de ABERTA direto para CONCLUIDA
     * apagaria a informação de que o trabalho começou, que é o que a tela usa para o operador saber
     * o que está na bancada.
     *
     * <p>⚠️ {@code FATURADA} <b>não</b> se alcança por aqui: é o PDV que a põe, junto com a venda,
     * e o {@code CHECK} do banco garante que as duas andem sempre juntas.
     */
    @Transactional
    public OrdemServicoResponse mudarSituacao(Jwt jwt, long id, String novaSituacao) {
        exigirEmpresaDaSessao(jwt, id);
        String atual = situacaoAtual(id);
        String nova = novaSituacao == null ? "" : novaSituacao.trim().toUpperCase(Locale.ROOT);

        if (nova.equals("FATURADA")) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Uma ordem de serviço vira FATURADA ao ser transformada em venda no PDV, "
                    + "nunca por mudança de estado — é o que garante que exista uma venda por trás.");
        }
        if (nova.equals("CANCELADA")) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Para cancelar, use o cancelamento — ele exige o motivo e devolve a reserva "
                    + "de estoque das peças.");
        }
        int ordemAtual = ESTADOS_EDITAVEIS.indexOf(atual);
        int ordemNova = ESTADOS_EDITAVEIS.indexOf(nova);
        if (ordemNova < 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Situação inválida: " + novaSituacao + ".");
        }
        if (ordemAtual < 0) {
            throw new ResponseStatusException(CONFLICT,
                    "Ordem de serviço " + atual.toLowerCase(Locale.ROOT) + " não muda mais de situação.");
        }
        if (ordemNova <= ordemAtual) {
            throw new ResponseStatusException(CONFLICT,
                    "A situação da ordem de serviço só avança — de " + atual + " não dá para voltar a "
                    + nova + ". Se foi engano, cancele e abra outra.");
        }

        jdbc.sql("""
                        UPDATE ordem_servico
                           SET situacao = CAST(? AS situacao_ordem_servico),
                               data_conclusao = CASE WHEN ? = 'CONCLUIDA' THEN now() ELSE data_conclusao END,
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_ordem_servico = ?
                        """)
                .params(nova, nova, id).update();
        return montarResposta(id);
    }

    /**
     * Cancela e <b>devolve a reserva</b> — DS17: é este o caminho para a OS parada, decidido por ele
     * em 2026-08-28 (*"alguém vai ter a possibilidade de cancelar a OS, aí com ela cancelada volta a
     * peça pro estoque"*). Não há expiração automática.
     *
     * <p>⚠️ OS <b>faturada</b> não se cancela: existe uma venda por trás, com caixa, contas a receber
     * e possivelmente nota fiscal. Quem desfaz isso é o Cancelamento de Venda, que sabe reverter as
     * quatro coisas — cancelar só a OS deixaria a venda viva e o estoque errado.
     */
    @Transactional
    public OrdemServicoResponse cancelar(Jwt jwt, long id, String motivo) {
        exigirEmpresaDaSessao(jwt, id);
        String atual = situacaoAtual(id);
        if (atual.equals("CANCELADA")) {
            throw new ResponseStatusException(CONFLICT, "Esta ordem de serviço já está cancelada.");
        }
        if (atual.equals("FATURADA")) {
            throw new ResponseStatusException(CONFLICT,
                    "Esta ordem de serviço já virou venda. Para desfazer, cancele a venda — é ela "
                    + "que tem caixa, contas a receber e nota fiscal a reverter.");
        }
        liberarReservas(id);
        jdbc.sql("""
                        UPDATE ordem_servico
                           SET situacao = 'CANCELADA', data_cancelamento = now(),
                               id_usuario_cancelamento = ?, motivo_cancelamento = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_ordem_servico = ?
                        """)
                .params(Long.parseLong(jwt.getSubject()), motivo.trim().toUpperCase(Locale.ROOT), id)
                .update();
        // Zera a marca de reserva das linhas: elas já não seguram nada, e deixar o número antigo
        // ali faria um cancelamento repetido liberar duas vezes.
        jdbc.sql("UPDATE ordem_servico_item SET qtd_reservada = 0"
                        + " WHERE id_tenant = plataforma.tenant_atual() AND id_ordem_servico = ?")
                .param(id).update();
        return montarResposta(id);
    }

    // ---------------------------------------------------------------- para o PDV (F5)

    /**
     * As OS que o PDV pode puxar para um cliente — só as <b>concluídas</b> (DS18) e ainda não
     * faturadas. É a consulta do F5.
     */
    @Transactional(readOnly = true)
    public List<LinhaListagem> faturaveisDoCliente(Jwt jwt, long idCliente) {
        return jdbc.sql("""
                        SELECT os.id_ordem_servico, c.nome AS nome_cliente, os.objeto_servico,
                               os.situacao::text AS situacao, os.data_abertura, os.id_venda,
                               COALESCE((SELECT SUM(i.qtd_produto * i.preco_venda)
                                           FROM ordem_servico_item i
                                          WHERE i.id_tenant = os.id_tenant
                                            AND i.id_ordem_servico = os.id_ordem_servico), 0)
                                 - os.valor_desconto AS total
                          FROM ordem_servico os
                          JOIN cliente c ON c.id_tenant = os.id_tenant AND c.id_cliente = os.id_cliente
                         WHERE os.id_tenant = plataforma.tenant_atual()
                           AND os.id_empresa = ?
                           AND os.id_cliente = ?
                           AND os.situacao = CAST(? AS situacao_ordem_servico)
                           AND os.id_venda IS NULL
                         ORDER BY os.data_abertura
                        """)
                .params(EmpresaDaSessao.idEmpresaDaSessao(jwt), idCliente, ESTADO_FATURAVEL)
                .query((rs, n) -> new LinhaListagem(
                        rs.getLong("id_ordem_servico"), rs.getString("nome_cliente"),
                        rs.getString("objeto_servico"), rs.getString("situacao"),
                        rs.getObject("data_abertura", OffsetDateTime.class),
                        rs.getBigDecimal("total"), idVendaOuNulo(rs)))
                .list();
    }

    /**
     * Abre a OS para o PDV faturar. ⛔ Só <b>CONCLUÍDA</b> (DS18) e ainda não faturada.
     *
     * <p>⚠️ A recusa vem com o estado por extenso porque o operador está com o cliente na frente:
     * "esta OS ainda está em execução" resolve sozinha; "não é possível faturar" mandaria ele
     * procurar o problema em outro lugar.
     */
    @Transactional(readOnly = true)
    public OrdemServicoResponse abrirParaVenda(Jwt jwt, long id) {
        OrdemServicoResponse os = montarResposta(id);
        // ⚠️ A OS pertence à EMPRESA que a abriu — a mesma regra que o orçamento ganhou em
        // 2026-08-22 (auditoria, item 3) e que a OS nasceu sem (auditoria 2026-08-29).
        // Não é isolamento de tenant (P8 nunca esteve em risco: as duas empresas são do mesmo
        // tenant), é regra de negócio, e aqui ela custa mais caro que no orçamento: as peças
        // ficam RESERVADAS na empresa da OS. Faturando pela outra, a venda debitava o estoque da
        // empresa errada (que nunca teve as peças → saldo negativo, permitido) enquanto
        // `marcarFaturada` liberava a reserva na empresa certa — as peças físicas ficavam de um
        // lado e a dívida de estoque do outro. A lista do F5 já filtra por empresa, então só uma
        // chamada direta à API chegava aqui; P4 diz que a trava é do servidor, não da tela.
        long idEmpresaSessao = ((Number) jwt.getClaim("eid")).longValue();
        if (os.idEmpresa() != idEmpresaSessao) {
            throw new ConflitoDadosException(
                    "A ordem de serviço nº " + id + " foi aberta em " + os.nomeEmpresa()
                            + " e só pode virar venda nessa empresa. Entre na empresa correta para usá-la.");
        }
        if (os.idVenda() != null) {
            throw new ConflitoDadosException(
                    "A ordem de serviço nº " + id + " já virou a venda nº " + os.idVenda() + ".");
        }
        if (!ESTADO_FATURAVEL.equals(os.situacao())) {
            throw new ConflitoDadosException(
                    "A ordem de serviço nº " + id + " está " + os.situacao().toLowerCase(Locale.ROOT)
                    + " — só é possível faturar depois de concluída.");
        }
        return os;
    }

    /**
     * Marca a OS como faturada e <b>libera a reserva</b>.
     *
     * <p>⛔ <b>Liberar a reserva aqui não é opcional:</b> a venda acabou de debitar
     * {@code qtd_estoque} pelo ledger. Se a reserva ficasse, a mesma peça estaria contada duas
     * vezes — saiu do saldo <b>e</b> continua segurando disponível —, e o estoque disponível ficaria
     * permanentemente menor que o físico, sem nada apontando a causa.
     *
     * <p>⚠️ Chamado <b>dentro</b> da transação da venda: se a venda falhar depois disto, a OS volta
     * a não estar faturada e a reserva volta a existir, juntas.
     */
    @Transactional
    public void marcarFaturada(long id, long idVenda) {
        liberarReservas(id);
        jdbc.sql("UPDATE ordem_servico_item SET qtd_reservada = 0"
                        + " WHERE id_tenant = plataforma.tenant_atual() AND id_ordem_servico = ?")
                .param(id).update();
        int linhas = jdbc.sql("""
                        UPDATE ordem_servico
                           SET situacao = 'FATURADA', id_venda = ?, data_faturamento = now(),
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_ordem_servico = ?
                           AND id_venda IS NULL
                        """)
                .params(idVenda, id).update();
        if (linhas == 0) {
            // ⚠️ UPDATE condicional em vez de conferir antes: duas vendas simultâneas da mesma OS
            // passariam por uma checagem prévia e só uma passa por esta. Mesma técnica que a
            // importação de pedido de marketplace usava.
            throw new ConflitoDadosException(
                    "A ordem de serviço nº " + id + " já havia sido faturada.");
        }
    }

    // ---------------------------------------------------------------- privados

    /**
     * A mesma variação não pode aparecer em duas linhas com <b>preços diferentes</b>.
     *
     * <p>⛔ <b>Por que isto é uma trava e não um detalhe</b> (auditoria 2026-08-29): o PDV congela o
     * preço da OS num {@code Map<idVariacao, preco>} ({@code PdvVendaService}), então de duas
     * linhas da mesma variação <b>só a última sobrevive</b> — enquanto a validação de quantidade
     * soma as duas. Uma OS com {@code TROCA DE ÓLEO} 1× R$ 100 (negociado) e 1× R$ 150 virava uma
     * venda de <b>2 × R$ 150 = R$ 300</b> contra os R$ 250 que o documento impresso promete. O
     * cliente reclama com o papel na mão e nada no sistema explica os R$ 50.
     *
     * <p>⚠️ O orçamento <b>não</b> tem este problema, e a diferença é o motivo de a trava morar
     * aqui: lá o preço vem sempre do cadastro ({@code OrcamentoService.resolverItens}), então duas
     * linhas da mesma variação têm forçosamente o mesmo preço. Na OS o preço pode vir do cliente
     * (limitado ao teto do cadastro), e é isso que abre a divergência.
     *
     * <p>⚠️ Recusar na ORIGEM, não no PDV: travar no fechamento da venda deixaria o operador com o
     * cliente na frente e uma OS impossível de faturar. Aqui ele ainda está montando a ordem.
     */
    private void exigirUmPrecoPorVariacao(List<ItemRequest> itens) {
        Map<Long, BigDecimal> precoPorVariacao = new HashMap<>();
        for (ItemRequest item : itens) {
            // ⛔ Preço NULO não é "sem preço" — é o preço de CADASTRO (auditoria 2026-08-29, rodada
            // 3, sobre a correção da rodada 1). Eu tinha escrito `continue` aqui, e isso reabria o
            // buraco pela porta ao lado: linha A com `precoVenda: 100` (negociado) + linha B da
            // MESMA variação com `precoVenda: null` (→ cadastro, R$ 150) passava pela trava e
            // produzia exatamente a venda de 2 × um dos preços que ela existe para impedir.
            // `gravarItens` e `exigirDescontoDentroDoTeto` já resolvem o null da mesma forma; a
            // trava é que estava discordando das duas.
            BigDecimal preco = item.precoVenda() != null ? item.precoVenda() : precoDeCadastro(item.idVariacao());
            BigDecimal anterior = precoPorVariacao.putIfAbsent(item.idVariacao(), preco);
            if (anterior != null && anterior.compareTo(preco) != 0) {
                throw new ConflitoDadosException(
                        ("O mesmo item aparece duas vezes com preços diferentes (R$ %s e R$ %s). "
                                + "Junte na mesma linha somando a quantidade, ou use o desconto da ordem "
                                + "de serviço — senão a venda cobraria só um dos dois preços.")
                                .formatted(anterior.toPlainString(), preco.toPlainString()));
            }
        }
    }

    private void gravarItens(long idOrdemServico, long idEmpresa, List<ItemRequest> itens) {
        exigirUmPrecoPorVariacao(itens);
        for (ItemRequest item : itens) {
            // O preço vem do cadastro quando o cliente não manda — nunca se aceita preço do cliente
            // sem conferência (o DTO documenta o porquê).
            // ⚠️ O javadoc do DTO promete que preço do cliente não é aceito sem conferência — e até
            // 2026-08-29 nada conferia (achado de auditoria). Hoje o front nunca manda o campo, mas
            // a API é pública ao tenant: sem isto, um cliente da API escolheria quanto custa o
            // serviço. O teto é o preço de cadastro; abaixo dele é desconto legítimo de negociação,
            // acima seria a OS inventando preço que a loja não pratica.
            BigDecimal precoCadastro = precoDeCadastro(item.idVariacao());
            BigDecimal preco = item.precoVenda() != null ? item.precoVenda() : precoCadastro;
            if (preco.compareTo(precoCadastro) > 0) {
                throw new IllegalArgumentException(
                        "O preço informado (R$ " + preco + ") é maior que o preço de cadastro (R$ "
                                + precoCadastro + "). A ordem de serviço não define preço acima da tabela.");
            }
            boolean ehServico = ehServico(item.idVariacao());
            // ⭐ DS15/DS19 — a peça reserva ao ser lançada, antes mesmo da aprovação. Serviço nunca
            // reserva: não tem saldo (V086).
            BigDecimal reservar = ehServico ? BigDecimal.ZERO : item.qtdProduto();

            jdbc.sql("""
                            INSERT INTO ordem_servico_item (id_tenant, id_ordem_servico, id_variacao,
                                                            qtd_produto, preco_venda, id_funcionario, qtd_reservada)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?)
                            """)
                    .params(idOrdemServico, item.idVariacao(), item.qtdProduto(), preco,
                            item.idFuncionario(), reservar)
                    .update();

            if (reservar.signum() > 0) {
                aplicarReserva(idEmpresa, item.idVariacao(), reservar);
            }
        }
    }

    /**
     * Move {@code produto_estoque.reservado} — a estreia dessa coluna, que existe desde a V019 e
     * nunca teve produtor (foi desenhada para o pedido de marketplace, removido em 2026-08-28).
     *
     * <p>⚠️ <b>UPSERT, não UPDATE</b>: a peça pode nunca ter tido movimento nesta empresa, e aí não
     * há linha em {@code produto_estoque} para atualizar — o UPDATE casaria zero linhas e a reserva
     * sumiria em silêncio, que é o modo de falha mais caro deste repositório.
     *
     * <p>⚠️ A reserva <b>não bloqueia venda</b>, só reduz o disponível. É o mesmo raciocínio do
     * {@code cfg_permite_estoque_negativo}: travar o balcão por um número que talvez ninguém alimente
     * é pior que o número estar errado.
     */
    private void aplicarReserva(long idEmpresa, long idVariacao, BigDecimal delta) {
        // ⚠️ UPDATE primeiro, INSERT só se não achou — e NÃO um `INSERT … ON CONFLICT DO UPDATE`.
        // Medido em 2026-08-28: o Postgres avalia os CHECK da tupla PROPOSTA **antes** de resolver o
        // conflito de índice único, então liberar uma reserva (delta negativo) estourava
        // `produto_estoque_reservado_ck` mesmo com a linha existindo e o UPDATE tendo GREATEST.
        // O erro chegava como 409 "registro em uso por outro cadastro", que não fala de reserva
        // nenhuma — é a mesma armadilha do GlobalExceptionHandler já catalogada.
        int linhas = jdbc.sql("""
                        UPDATE produto_estoque
                           SET reservado = GREATEST(reservado + ?, 0), atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_empresa = ? AND id_variacao = ?
                        """)
                .params(delta, idEmpresa, idVariacao)
                .update();

        if (linhas == 0 && delta.signum() > 0) {
            // A peça nunca teve movimento nesta empresa — não há linha para atualizar. Nasce aqui,
            // com saldo zero e a reserva. (Liberar o que não existe é no-op, de propósito: não há
            // reserva a devolver, e criar linha zerada só polui o estoque.)
            //
            // ⚠️ **`ON CONFLICT` obrigatório aqui, e a corrida provou por quê (2026-09-04).** Duas OS
            // abertas ao mesmo tempo com a mesma peça sem linha de estoque: as duas fazem o UPDATE
            // acima, as duas casam ZERO linhas, e as duas chegam neste INSERT — a segunda violava
            // `produto_estoque_uk` e o lojista via *"Registro em uso por outro cadastro — não pode
            // ser excluído"* ao abrir uma OS. O padrão "UPDATE primeiro, INSERT se não achou" é
            // correto sequencialmente e tem uma janela entre os dois comandos.
            //
            // ⭐ E isto **não** contradiz o comentário acima: o problema do CHECK é com delta
            // NEGATIVO (a tupla proposta levaria `reservado = -1`), e este INSERT só roda com
            // `delta.signum() > 0`. A tupla proposta aqui é sempre positiva, então passa o
            // `produto_estoque_reservado_ck` antes de o Postgres resolver o conflito.
            jdbc.sql("""
                            INSERT INTO produto_estoque (id_tenant, id_empresa, id_variacao, qtd_estoque, reservado)
                            VALUES (plataforma.tenant_atual(), ?, ?, 0, ?)
                            ON CONFLICT (id_tenant, id_empresa, id_variacao) DO UPDATE
                               SET reservado = GREATEST(produto_estoque.reservado + EXCLUDED.reservado, 0),
                                   atualizado_em = now()
                            """)
                    .params(idEmpresa, idVariacao, delta)
                    .update();
        }
    }

    /** Devolve ao estoque o que CADA linha reservou — pelo valor guardado nela, não pelo de agora. */
    /**
     * O desconto da OS respeita o mesmo teto da venda ({@code cfg_geral.percentual_desconto_venda}).
     *
     * <p>⛔ Até 2026-08-29 o teto era validado <b>só no front</b> — "teto com porta ao lado", o
     * padrão que este projeto já pagou caro quatro vezes num dia. E aqui ele tem consequência
     * direta: o desconto da OS agora <b>viaja</b> para o campo de desconto do PDV, então uma OS
     * gravada com desconto acima do máximo empurraria esse valor para dentro da venda.
     *
     * <p>⚠️ A conta é sobre o subtotal dos ITENS da OS, que é o mesmo que a tela mostra.
     */
    private void exigirDescontoDentroDoTeto(OrdemServicoRequest req) {
        BigDecimal desconto = req.valorDesconto();
        if (desconto == null || desconto.signum() <= 0) {
            return;
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (ItemRequest item : req.itens()) {
            BigDecimal preco = item.precoVenda() != null ? item.precoVenda() : precoDeCadastro(item.idVariacao());
            subtotal = subtotal.add(preco.multiply(item.qtdProduto()));
        }
        BigDecimal percentualMaximo = configuracaoGeralService.percentualDescontoVenda();
        BigDecimal maximo = percentualMaximo.signum() > 0
                ? subtotal.multiply(percentualMaximo).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN)
                : BigDecimal.ZERO;
        if (desconto.compareTo(maximo) > 0) {
            throw new IllegalArgumentException(
                    "Desconto de R$ " + desconto + " excede o máximo permitido de " + percentualMaximo
                            + "% (R$ " + maximo + ").");
        }
    }

    /**
     * A empresa a que a OS pertence — nunca a da sessão.
     *
     * <p>⚠️ Toda escrita de reserva tem de usar a MESMA empresa que a leitura, senão o saldo some
     * de uma e nasce na outra. A OS é aberta numa empresa e não migra.
     */
    private long empresaDaOs(long idOrdemServico) {
        return jdbc.sql("""
                        SELECT id_empresa FROM ordem_servico
                         WHERE id_tenant = plataforma.tenant_atual() AND id_ordem_servico = ?
                        """)
                .param(idOrdemServico)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ordem de serviço não encontrada."));
    }

    private void liberarReservas(long idOrdemServico) {
        record Reserva(long idEmpresa, long idVariacao, BigDecimal qtd) {
        }
        List<Reserva> reservas = jdbc.sql("""
                        SELECT os.id_empresa, i.id_variacao, i.qtd_reservada
                          FROM ordem_servico_item i
                          JOIN ordem_servico os ON os.id_tenant = i.id_tenant
                                               AND os.id_ordem_servico = i.id_ordem_servico
                         WHERE i.id_tenant = plataforma.tenant_atual()
                           AND i.id_ordem_servico = ? AND i.qtd_reservada > 0
                        """)
                .param(idOrdemServico)
                .query((rs, n) -> new Reserva(rs.getLong("id_empresa"), rs.getLong("id_variacao"),
                        rs.getBigDecimal("qtd_reservada")))
                .list();
        for (Reserva r : reservas) {
            aplicarReserva(r.idEmpresa(), r.idVariacao(), r.qtd().negate());
        }
    }

    /**
     * Recusa quando a OS pedida é de outra empresa que não a da sessão (administrador passa).
     *
     * <p>⛔ {@code abrirParaVenda} ganhou esta checagem na auditoria de 2026-08-29, com o javadoc
     * dizendo <i>"a lista do F5 já filtra por empresa, então só uma chamada direta à API chegava
     * aqui; P4 diz que a trava é do servidor, não da tela"</i> — e os <b>quatro métodos por id ao
     * lado ficaram sem ela</b> (2026-08-30). {@code listar} sempre filtrou por
     * {@code idEmpresaDaSessao}, então a mesma hipótese vale e o mesmo caminho existia: um
     * {@code POST /ordens-servico/12/cancelar} feito da Filial cancelava a OS da <b>Matriz</b> e
     * chamava {@code liberarReservas}, que lê {@code os.id_empresa} — <b>mexendo no estoque
     * reservado da Matriz</b> sem ninguém de lá ter pedido nada, e deixando o documento
     * {@code CANCELADA} com motivo digitado por quem não opera aquela loja. O {@code PUT} idem,
     * reescrevendo itens e desconto.
     *
     * <p>⚠️ É {@code private} de propósito e <b>não</b> carrega {@code @Transactional}: todo
     * chamador já é transacional, então a consulta roda dentro da transação dele e enxerga
     * {@code tenant_atual()}. Anotar aqui seria auto-invocação — a anotação não pegaria, o
     * {@code SET LOCAL} não rodaria, o SELECT viria vazio e o guarda <b>liberaria sempre</b>, que é
     * como um guarda falha em silêncio.
     */
    private void exigirEmpresaDaSessao(Jwt jwt, long id) {
        long idEmpresaDaOs = jdbc.sql("SELECT id_empresa FROM ordem_servico"
                        + " WHERE id_tenant = plataforma.tenant_atual() AND id_ordem_servico = ?")
                .param(id).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ordem de serviço não encontrada."));
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresaDaOs);
    }

    private String situacaoAtual(long id) {
        return jdbc.sql("SELECT situacao::text FROM ordem_servico"
                        + " WHERE id_tenant = plataforma.tenant_atual() AND id_ordem_servico = ?")
                .param(id).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ordem de serviço não encontrada."));
    }

    private void exigirEditavel(String situacao, String acao) {
        if (!ESTADOS_EDITAVEIS.contains(situacao)) {
            throw new ResponseStatusException(CONFLICT,
                    "Não é possível " + acao + " uma ordem de serviço " + situacao.toLowerCase(Locale.ROOT)
                    + ".");
        }
    }

    private boolean ehServico(long idVariacao) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT tipo_item = 'SERVICO' FROM produto_barra
                         WHERE id_tenant = plataforma.tenant_atual() AND id_variacao = ?
                        """)
                .param(idVariacao).query(Boolean.class).optional()
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                        "Item não encontrado no catálogo.")));
    }

    private BigDecimal precoDeCadastro(long idVariacao) {
        return jdbc.sql("""
                        SELECT p.preco_venda
                          FROM produto_barra pb
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                         WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.id_variacao = ?
                        """)
                .param(idVariacao).query(BigDecimal.class).optional()
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                        "Item não encontrado no catálogo."));
    }

    /**
     * ⛔ Abrir OS exige o módulo de serviços ligado (S1/R8) — esconder o item no menu <b>nunca foi
     * proteção</b> (P4), e sem esta trava um POST direto abriria OS numa loja de calçados,
     * reservando estoque por um mecanismo que ninguém naquele tenant sabe que existe.
     *
     * <p>⚠️ Só o <b>criar</b> é travado, de propósito. Alterar, concluir e cancelar continuam
     * valendo com o módulo desligado: quem desliga o módulo com OS abertas trancaria as peças
     * reservadas <b>para sempre</b> — não haveria caminho para devolvê-las ao estoque. Uma trava
     * que impede de sair da situação é pior que a situação.
     */
    private void exigirModuloLigado() {
        if (!configuracaoGeralService.usaServicos()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "O módulo de serviços está desligado. Ligue \"Usa serviços\" em "
                    + "Parâmetros do Sistema para abrir ordens de serviço.");
        }
    }

    /** ⚠️ `getInt` devolve 0 em coluna nula, e 0 minuto é diferente de "sem duração cadastrada" —
     *  daí o `wasNull`. ⚠️ Este javadoc dizia "e nunca `getObject(col, Integer.class)` numa
     *  coluna `integer`" — FALSO, corrigido em 2026-08-30 junto com as outras duas cópias da mesma
     *  frase: o driver aceita `Integer.class` para int2/int4; o que ele recusa é `Long.class`. */
    private static Integer duracaoOuNula(ResultSet rs) throws SQLException {
        int v = rs.getInt("duracao_minutos");
        return rs.wasNull() ? null : v;
    }

    private List<ItemResponse> buscarItens(long idOrdemServico) {
        return jdbc.sql("""
                        SELECT i.id_ordem_servico_item, i.id_variacao, pb.sku, p.descricao,
                               co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho,
                               pb.tipo_item::text AS tipo_item, i.qtd_produto, i.preco_venda,
                               i.id_funcionario, f.nome AS nome_funcionario, i.qtd_reservada,
                               ps.duracao_minutos
                          FROM ordem_servico_item i
                          JOIN produto_barra pb ON pb.id_tenant = i.id_tenant AND pb.id_variacao = i.id_variacao
                          JOIN produto p ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          -- id_cor/id_tamanho = 1 e a sentinela PADRAO (produto sem grade): o
                          -- "<> 1" a transforma em NULL, como faz a pesquisa de produto do PDV.
                          LEFT JOIN cfg_cor co ON co.id_tenant = pb.id_tenant AND co.id_cor = pb.id_cor AND co.id_cor <> 1
                          LEFT JOIN cfg_tamanho ta ON ta.id_tenant = pb.id_tenant AND ta.id_tamanho = pb.id_tamanho AND ta.id_tamanho <> 1
                          LEFT JOIN produto_servico ps ON ps.id_tenant = p.id_tenant AND ps.id_produto = p.id_produto
                          LEFT JOIN funcionario f ON f.id_tenant = i.id_tenant AND f.id_funcionario = i.id_funcionario
                         WHERE i.id_tenant = plataforma.tenant_atual() AND i.id_ordem_servico = ?
                         ORDER BY i.id_ordem_servico_item
                        """)
                .param(idOrdemServico)
                .query((rs, n) -> {
                    BigDecimal qtd = rs.getBigDecimal("qtd_produto");
                    BigDecimal preco = rs.getBigDecimal("preco_venda");
                    return new ItemResponse(
                            rs.getLong("id_ordem_servico_item"), rs.getLong("id_variacao"),
                            rs.getString("sku"), rs.getString("descricao"),
                            rs.getString("variacao_cor"), rs.getString("variacao_tamanho"),
                            rs.getString("tipo_item"),
                            qtd, preco, qtd.multiply(preco),
                            idFuncionarioOuNulo(rs), rs.getString("nome_funcionario"),
                            rs.getBigDecimal("qtd_reservada"), duracaoOuNula(rs));
                })
                .list();
    }

    private static BigDecimal somar(List<ItemResponse> itens, String tipo) {
        return itens.stream()
                .filter(i -> tipo.equals(i.tipoItem()))
                .map(ItemResponse::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** {@code getLong} + {@code wasNull}: o driver recusa {@code getObject(col, Long.class)} sobre
     *  {@code int4}, e o erro sai disfarçado de 409 (achado de 2026-08-20). */
    private static Long idVendaOuNulo(ResultSet rs) throws SQLException {
        long v = rs.getLong("id_venda");
        return rs.wasNull() ? null : v;
    }

    private static Long idFuncionarioOuNulo(ResultSet rs) throws SQLException {
        long v = rs.getLong("id_funcionario");
        return rs.wasNull() ? null : v;
    }

    private OrdemServicoResponse mapearCabecalho(ResultSet rs, int rowNum) throws SQLException {
        return new OrdemServicoResponse(
                rs.getLong("id_ordem_servico"), rs.getLong("id_empresa"), rs.getLong("id_cliente"),
                rs.getString("nome_cliente"), rs.getString("documento_cliente"),
                rs.getString("telefone_cliente"), rs.getString("nome_empresa"),
                rs.getLong("id_funcionario"), rs.getString("nome_funcionario"),
                rs.getString("objeto_servico"), rs.getString("observacao"), rs.getString("situacao"),
                rs.getObject("data_abertura", OffsetDateTime.class),
                rs.getObject("data_conclusao", OffsetDateTime.class),
                rs.getBigDecimal("valor_desconto"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(),
                idVendaOuNulo(rs),
                rs.getObject("data_faturamento", OffsetDateTime.class),
                rs.getObject("data_cancelamento", OffsetDateTime.class),
                rs.getString("motivo_cancelamento"),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }
}
