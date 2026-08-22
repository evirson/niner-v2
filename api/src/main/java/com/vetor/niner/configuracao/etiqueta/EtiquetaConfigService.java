package com.vetor.niner.configuracao.etiqueta;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.AlinhamentoEtiquetaCampo;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.CampoEtiqueta;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.CampoRequest;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.CampoResponse;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.EtiquetaConfigRequest;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.EtiquetaConfigResponse;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.ExclusaoEtiquetaConfigResponse;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.FonteEtiqueta;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.PaginaEtiquetasConfig;
import com.vetor.niner.configuracao.etiqueta.EtiquetaConfigDtos.ProdutoExemploResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de Configuração de Etiqueta de Produtos (docs/telas/configuracao-etiqueta.md), tabelas
 * {@code cfg_etiqueta_config}/{@code cfg_etiqueta_campo} (V029, RLS de tenant). ⚠️ A tabela
 * {@code cfg_etiqueta_coluna} deixou de existir na V057: a posição de cada coluna é DERIVADA de
 * margem + espaçamento + largura (era dado redundante, e era ali que o erro de digitação entrava).
 * A coleção de campos continua salva com o mesmo padrão de
 * {@code ProdutoService.salvarCategorias}: apaga tudo e reinsere dentro da mesma transação do
 * cabeçalho — nunca diff/patch individual.
 *
 * <p>{@code excluir()} é sempre um DELETE de verdade — diferente de Produto/Plano de Contas, hoje
 * nenhuma outra tabela referencia {@code cfg_etiqueta_config} por FK (a tela de Emissão que vai
 * consumir isso ainda não existe), então não há dependente real pra checar. A coluna {@code
 * ativo} existe só como um "desativar sem apagar" editável direto no formulário (campo comum de
 * {@link EtiquetaConfigRequest}), não como fallback de exclusão.
 */
@Service
public class EtiquetaConfigService {

    /**
     * Teto da busca por digitação — 20, o mesmo dos seletores do PDV e de
     * {@code EtiquetaEmissaoService.LIMITE_BUSCA}.
     *
     * ⚠️ Era 10 e a tela não avisava do corte (auditoria 2026-08-21 item 33, estendido em
     * 2026-08-22 para as duas telas que a auditoria não tinha listado). Quem tinha mais produtos
     * parecidos que o limite não via o seu e concluía "não está cadastrado" — quando estava. O
     * aviso na tela é o que resolve; o número só reduz a frequência.
     */
    private static final int LIMITE_BUSCA = 20;

    private static final int TAMANHO_PAGINA_PADRAO = 20;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "nome", "ec.nome",
            "larguraRoloMm", "ec.largura_rolo_mm",
            "numeroColunas", "ec.numero_colunas",
            "ativo", "ec.ativo");

    private final JdbcClient jdbc;

    public EtiquetaConfigService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PaginaEtiquetasConfig listar(String busca, Integer pagina, Integer limite, String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String colunaOrdenacao = ordenarPor == null ? "ec.nome" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "ec.nome");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE ec.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();
        if (busca != null && !busca.isBlank()) {
            filtro.append(" AND ec.nome ILIKE ?");
            params.add("%" + busca.trim() + "%");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM cfg_etiqueta_config ec" + filtro).params(params).query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + colunaOrdenacao + " " + direcaoOrdenacao
                + ", ec.id_config_etiqueta " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<EtiquetaConfigResponse> itens = jdbc.sql(SELECT_BASE + filtro + ordenacao)
                .params(paramsPagina)
                .query(this::mapear)
                .list();

        return new PaginaEtiquetasConfig(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public EtiquetaConfigResponse buscar(long id) {
        return jdbc.sql(SELECT_BASE + " WHERE ec.id_tenant = plataforma.tenant_atual() AND ec.id_config_etiqueta = ?")
                .param(id)
                .query(this::mapear)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Configuração de etiqueta não encontrada."));
    }

    @Transactional
    public EtiquetaConfigResponse criar(EtiquetaConfigRequest req) {
        validar(req);
        try {
            long id = jdbc.sql("""
                            INSERT INTO cfg_etiqueta_config
                                (id_tenant, nome, largura_rolo_mm, numero_colunas, largura_etiqueta_mm, altura_etiqueta_mm,
                                 margem_esquerda_mm, espacamento_horizontal_mm, espacamento_vertical_mm, ativo)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id_config_etiqueta
                            """)
                    .params(req.nome().trim().toUpperCase(Locale.ROOT), req.larguraRoloMm(), req.numeroColunas(), req.larguraEtiquetaMm(),
                            req.alturaEtiquetaMm(), naoNegativoOuZero(req.margemEsquerdaMm()),
                            naoNegativoOuZero(req.espacamentoHorizontalMm()),
                            naoNegativoOuZero(req.espacamentoVerticalMm()),
                            req.ativo() == null || req.ativo())
                    .query(Long.class).single();
            salvarCampos(id, req.campos());
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma configuração de etiqueta com esse nome.");
        }
    }

    @Transactional
    public EtiquetaConfigResponse atualizar(long id, EtiquetaConfigRequest req) {
        validar(req);
        try {
            int linhas = jdbc.sql("""
                            UPDATE cfg_etiqueta_config SET
                                nome = ?, largura_rolo_mm = ?, numero_colunas = ?, largura_etiqueta_mm = ?,
                                altura_etiqueta_mm = ?, margem_esquerda_mm = ?,
                                espacamento_horizontal_mm = ?, espacamento_vertical_mm = ?,
                                ativo = ?, atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_config_etiqueta = ?
                            """)
                    .params(req.nome().trim().toUpperCase(Locale.ROOT), req.larguraRoloMm(), req.numeroColunas(), req.larguraEtiquetaMm(),
                            req.alturaEtiquetaMm(), naoNegativoOuZero(req.margemEsquerdaMm()),
                            naoNegativoOuZero(req.espacamentoHorizontalMm()),
                            naoNegativoOuZero(req.espacamentoVerticalMm()),
                            req.ativo() == null || req.ativo(), id)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Configuração de etiqueta não encontrada.");
            }
            salvarCampos(id, req.campos());
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma configuração de etiqueta com esse nome.");
        }
    }

    @Transactional
    public ExclusaoEtiquetaConfigResponse excluir(long id) {
        jdbc.sql("DELETE FROM cfg_etiqueta_campo WHERE id_tenant = plataforma.tenant_atual() AND id_config_etiqueta = ?")
                .param(id).update();
        int linhas = jdbc.sql("DELETE FROM cfg_etiqueta_config WHERE id_tenant = plataforma.tenant_atual() AND id_config_etiqueta = ?")
                .param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Configuração de etiqueta não encontrada.");
        }
        return new ExclusaoEtiquetaConfigResponse("excluido", null);
    }

    /**
     * Produtos/variações pra pré-visualizar a etiqueta com dado real — mesmo padrão de busca de
     * {@code RelatorioMovimentacaoProdutosService.buscarVariacoes}, DTO mais rico (referência,
     * preço) que a tela precisa e o Kardex não expõe. Base é {@code produto} (não
     * {@code produto_barra}) com `LEFT JOIN`: a tela de variação/SKU ainda não existe
     * (docs/PROGRESSO.md), então a maioria dos produtos cadastrados não tem nenhuma linha em
     * {@code produto_barra} ainda — excluí-los faria a busca voltar vazia pra praticamente todo
     * mundo. Sem variação, cai no SKU/EAN de mentirinha do canvas (mesmo comportamento de sem
     * produto nenhum selecionado).
     */
    @Transactional(readOnly = true)
    public List<ProdutoExemploResponse> buscarProdutosExemplo(String busca) {
        String filtroBusca = (busca == null || busca.isBlank()) ? "" : " AND (p.descricao ILIKE ? OR pb.sku ILIKE ?)";
        String sql = """
                SELECT COALESCE(pb.id_variacao, -p.id_produto) AS id_variacao, pb.sku,
                       p.descricao, p.marca, p.referencia, p.preco_venda,
                       co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho
                FROM produto p
                LEFT JOIN produto_barra pb ON pb.id_produto = p.id_produto AND pb.id_tenant = p.id_tenant
                LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                WHERE p.id_tenant = plataforma.tenant_atual() AND p.ativo
                """
                + filtroBusca
                + " ORDER BY p.descricao LIMIT " + LIMITE_BUSCA + "";

        var query = jdbc.sql(sql);
        if (!filtroBusca.isEmpty()) {
            String curinga = "%" + busca.trim() + "%";
            query = query.params(List.of(curinga, curinga));
        }
        return query.query((rs, n) -> new ProdutoExemploResponse(
                        rs.getLong("id_variacao"), rs.getString("sku"),
                        rs.getString("descricao"), rs.getString("marca"), rs.getString("referencia"),
                        rs.getBigDecimal("preco_venda"),
                        rs.getString("variacao_cor"), rs.getString("variacao_tamanho")))
                .list();
    }

    /** Apaga todos os campos da config e reinsere na lista recebida. */
    private void salvarCampos(long idConfigEtiqueta, List<CampoRequest> campos) {
        jdbc.sql("DELETE FROM cfg_etiqueta_campo WHERE id_tenant = plataforma.tenant_atual() AND id_config_etiqueta = ?")
                .param(idConfigEtiqueta).update();
        for (CampoRequest campo : campos) {
            jdbc.sql("""
                            INSERT INTO cfg_etiqueta_campo
                                (id_tenant, id_config_etiqueta, campo, posicao_x_mm, posicao_y_mm, largura_mm, altura_mm,
                                 fonte, tamanho_fonte_pt, negrito, fundo_preto, alinhamento, exibir_texto_legivel)
                            VALUES (plataforma.tenant_atual(), ?, ?::campo_etiqueta, ?, ?, ?, ?, ?::fonte_etiqueta, ?, ?, ?, ?::alinhamento_etiqueta_campo, ?)
                            """)
                    .params(idConfigEtiqueta, campo.campo().name(), campo.posicaoXMm(), campo.posicaoYMm(),
                            campo.larguraMm(), campo.alturaMm(), campo.fonte().name(), campo.tamanhoFontePt(),
                            campo.negrito(), campo.fundoPreto(), campo.alinhamento().name(), campo.exibirTextoLegivel())
                    .update();
        }
    }

    /**
     * Validação de servidor (defesa em profundidade, mesmo padrão de {@code TipoCarteiraService}/
     * {@code ProdutoService}): número de colunas dentro do `CHECK` do banco (mensagem amigável em
     * vez de 500), a lista de colunas bate exatamente com `numeroColunas` sem repetir número, e
     * nenhum campo repete nem ultrapassa os limites da etiqueta. Duplicidade é checada aqui
     * porque só apareceria depois de apagar as linhas antigas (mesmo motivo do comentário em
     * {@code ProdutoService.validar}).
     */
    private static void validar(EtiquetaConfigRequest req) {
        if (req.numeroColunas() < 1 || req.numeroColunas() > 4) {
            throw new IllegalArgumentException("Número de colunas deve ser entre 1 e 4.");
        }
        // ⚠️ A última coluna não pode passar da largura do rolo — o excedente é simplesmente
        // cortado na impressão, e o operador só descobre com a etiqueta na mão. Desde a V057 dá
        // para conferir isto no servidor, porque a geometria é derivada: antes, com posições
        // digitadas uma a uma, a checagem existia só na tela.
        BigDecimal fimDaUltimaColuna = naoNegativoOuZero(req.margemEsquerdaMm())
                .add(BigDecimal.valueOf(req.numeroColunas() - 1L)
                        .multiply(req.larguraEtiquetaMm().add(naoNegativoOuZero(req.espacamentoHorizontalMm()))))
                .add(req.larguraEtiquetaMm());
        if (fimDaUltimaColuna.compareTo(req.larguraRoloMm()) > 0) {
            throw new IllegalArgumentException(
                    "As %d colunas ocupam %s mm e não cabem num rolo de %s mm. Revise a margem, a largura da etiqueta ou o espaço entre colunas."
                            .formatted(req.numeroColunas(), fimDaUltimaColuna.stripTrailingZeros().toPlainString(),
                                    req.larguraRoloMm().stripTrailingZeros().toPlainString()));
        }

        Set<CampoEtiqueta> camposUsados = new HashSet<>();
        for (CampoRequest campo : req.campos()) {
            if (!camposUsados.add(campo.campo())) {
                throw new IllegalArgumentException("Campo repetido na etiqueta: " + campo.campo() + ".");
            }
            BigDecimal larguraCampo = campo.larguraMm() == null ? BigDecimal.ZERO : campo.larguraMm();
            BigDecimal alturaCampo = campo.alturaMm() == null ? BigDecimal.ZERO : campo.alturaMm();
            if (campo.posicaoXMm().add(larguraCampo).compareTo(req.larguraEtiquetaMm()) > 0
                    || campo.posicaoYMm().add(alturaCampo).compareTo(req.alturaEtiquetaMm()) > 0) {
                throw new IllegalArgumentException("Campo " + campo.campo() + " ultrapassa os limites da etiqueta.");
            }
        }
    }

    private static BigDecimal naoNegativoOuZero(BigDecimal valor) {
        if (valor == null) {
            return BigDecimal.ZERO;
        }
        if (valor.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Borda não pode ser negativa.");
        }
        return valor;
    }

    private static final String SELECT_BASE = """
            SELECT ec.id_config_etiqueta, ec.nome, ec.largura_rolo_mm, ec.numero_colunas, ec.largura_etiqueta_mm,
                   ec.altura_etiqueta_mm, ec.margem_esquerda_mm, ec.espacamento_horizontal_mm,
                   ec.espacamento_vertical_mm,
                   ec.ativo, ec.criado_em, ec.atualizado_em
            FROM cfg_etiqueta_config ec
            """;

    private EtiquetaConfigResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id_config_etiqueta");
        return new EtiquetaConfigResponse(
                id,
                rs.getString("nome"),
                rs.getBigDecimal("largura_rolo_mm"),
                rs.getInt("numero_colunas"),
                rs.getBigDecimal("largura_etiqueta_mm"),
                rs.getBigDecimal("altura_etiqueta_mm"),
                rs.getBigDecimal("margem_esquerda_mm"),
                rs.getBigDecimal("espacamento_horizontal_mm"),
                rs.getBigDecimal("espacamento_vertical_mm"),
                rs.getBoolean("ativo"),
                buscarCampos(id),
                rs.getObject("criado_em", OffsetDateTime.class),
                rs.getObject("atualizado_em", OffsetDateTime.class));
    }

    private List<CampoResponse> buscarCampos(long idConfigEtiqueta) {
        return jdbc.sql("""
                        SELECT campo::text AS campo, posicao_x_mm, posicao_y_mm, largura_mm, altura_mm,
                               fonte::text AS fonte, tamanho_fonte_pt, negrito, fundo_preto,
                               alinhamento::text AS alinhamento, exibir_texto_legivel
                        FROM cfg_etiqueta_campo
                        WHERE id_tenant = plataforma.tenant_atual() AND id_config_etiqueta = ?
                        ORDER BY id_config_etiqueta_campo
                        """)
                .param(idConfigEtiqueta)
                .query((rs, n) -> new CampoResponse(
                        CampoEtiqueta.valueOf(rs.getString("campo")),
                        rs.getBigDecimal("posicao_x_mm"), rs.getBigDecimal("posicao_y_mm"),
                        rs.getBigDecimal("largura_mm"), rs.getBigDecimal("altura_mm"),
                        FonteEtiqueta.valueOf(rs.getString("fonte")), rs.getBigDecimal("tamanho_fonte_pt"),
                        rs.getBoolean("negrito"), rs.getBoolean("fundo_preto"),
                        AlinhamentoEtiquetaCampo.valueOf(rs.getString("alinhamento")),
                        (Boolean) rs.getObject("exibir_texto_legivel")))
                .list();
    }
}
