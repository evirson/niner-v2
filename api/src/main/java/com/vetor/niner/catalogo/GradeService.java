package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.GradeDtos.GradeRequest;
import com.vetor.niner.catalogo.GradeDtos.GradeResponse;
import com.vetor.niner.catalogo.TamanhoDtos.TamanhoResponse;
import com.vetor.niner.comum.web.ConflitoDadosException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD (criar/listar/atualizar, sem exclusão — protegida pela FK de {@code produto.id_grade})
 * da grade ({@code cfg_grade}) — curva de tamanhos nomeada (ex.: "Grade 36-44"), gerida
 * embutida no formulário de Produto (modal "＋ Gerenciar Grades", docs/telas/produto.md), mesmo
 * espírito de {@code CategoriaProdutoService}. Guarda até {@value #MAX_TAMANHOS} tamanhos em
 * ordem nas colunas fixas {@code id_tamanho1..20} (V017, decisão do dono do produto,
 * 2026-08-08) — este service empacota/desempacota essa lista pro cliente da API nunca ver os
 * slots, só um {@code List<Long>} ordenado.
 *
 * <p><b>id_grade=1 é a grade PADRÃO</b> (2026-08-13, ver {@code SignupService}) — reservada,
 * invisível, nunca listada/editável por aqui. {@code id_grade} não é mais {@code IDENTITY}
 * (V017): cada tenant tem sua própria numeração, calculada aqui como {@code MAX(id_grade)+1}.
 */
@Service
public class GradeService {

    private static final int MAX_TAMANHOS = 20;

    private final JdbcClient jdbc;

    public GradeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<GradeResponse> listar() {
        List<GradeBruta> brutas = jdbc.sql(SELECT_COLUNAS
                        + " FROM cfg_grade WHERE id_tenant = plataforma.tenant_atual() AND id_grade <> 1 ORDER BY descricao")
                .query(GradeService::mapearBruta)
                .list();
        Map<Long, TamanhoResponse> tamanhos = resolverTamanhos(brutas);
        return brutas.stream().map(b -> montarResponse(b, tamanhos)).toList();
    }

    @Transactional(readOnly = true)
    public GradeResponse buscar(long id) {
        if (id == 1) {
            throw new ResponseStatusException(NOT_FOUND, "Grade não encontrada.");
        }
        GradeBruta bruta = jdbc.sql(SELECT_COLUNAS + " FROM cfg_grade WHERE id_grade = ? AND id_tenant = plataforma.tenant_atual()")
                .param(id)
                .query(GradeService::mapearBruta)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Grade não encontrada."));
        return montarResponse(bruta, resolverTamanhos(List.of(bruta)));
    }

    /** Acha a grade já cadastrada com esse nome, ou cria na hora com os tamanhos informados —
     * usado pela Importação de Dados (idempotente: reimportar o mesmo arquivo acha a grade já
     * criada na 1ª vez, em vez de tentar duplicar). Diferente de {@link #criar}, não lança
     * {@code ConflitoDadosException} para nome já existente — assume a grade já cadastrada como
     * a correta e ignora {@code idsTamanho}. */
    @Transactional
    public GradeResponse obterOuCriarPorNome(String descricao, List<Long> idsTamanho) {
        String nome = descricao.trim().toUpperCase(Locale.ROOT);
        Optional<Long> existente = jdbc.sql("""
                        SELECT id_grade FROM cfg_grade
                        WHERE id_tenant = plataforma.tenant_atual() AND descricao = ?
                        """)
                .param(nome).query(Long.class).optional();
        if (existente.isPresent()) {
            return buscar(existente.get());
        }
        return criar(new GradeRequest(nome, idsTamanho));
    }

    /** Acha a grade cujo CONTEÚDO (sequência ORDENADA de tamanhos, slot a slot) é exatamente
     * igual à informada, ou cria uma nova — usado quando a origem não traz nome de grade nenhum
     * (Rotina de Importação de Dados, layout "uma linha = um produto" com {@code TAMANHO_1..20},
     * 2026-08-10). Diferente de {@link #obterOuCriarPorNome} (casa por nome), aqui "já existe" é
     * definido pelo conteúdo — o nome é só um rótulo gerado ({@code nomeSugerido}), com sufixo
     * numérico se já estiver em uso por outra grade de conteúdo diferente. */
    @Transactional
    public GradeResponse obterOuCriarPorTamanhos(List<Long> idsTamanho, String nomeSugerido) {
        Optional<Long> existente = buscarIdPorSlots(idsTamanho);
        if (existente.isPresent()) {
            return buscar(existente.get());
        }
        return criar(new GradeRequest(nomeUnico(nomeSugerido), idsTamanho));
    }

    private Optional<Long> buscarIdPorSlots(List<Long> idsTamanho) {
        List<Object> slots = empacotarSlots(idsTamanho);
        return jdbc.sql("SELECT id_grade FROM cfg_grade WHERE id_tenant = plataforma.tenant_atual() AND "
                        + IGUALDADE_SLOT)
                .params(slots)
                .query(Long.class).optional();
    }

    /** {@code base} normalizado, com sufixo {@code " (2)"}, {@code " (3)"}... se já estiver em
     * uso por outra grade (conteúdo já provado diferente por {@link #buscarIdPorSlots}). */
    private String nomeUnico(String base) {
        String nome = base.trim().toUpperCase(Locale.ROOT);
        for (int sufixo = 2; existeNome(nome); sufixo++) {
            nome = base.trim().toUpperCase(Locale.ROOT) + " (" + sufixo + ")";
        }
        return nome;
    }

    private boolean existeNome(String nome) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM cfg_grade
                                       WHERE id_tenant = plataforma.tenant_atual() AND descricao = ?)
                        """)
                .param(nome).query(Boolean.class).single());
    }

    @Transactional
    public GradeResponse criar(GradeRequest req) {
        String descricao = validarEDescricao(req);
        List<Object> params = new ArrayList<>();
        params.addAll(empacotarSlots(req.idsTamanho()));
        params.add(0, descricao);
        try {
            long id = jdbc.sql("""
                            INSERT INTO cfg_grade (id_tenant, id_grade, descricao, %s)
                            VALUES (plataforma.tenant_atual(),
                                COALESCE((SELECT MAX(id_grade) FROM cfg_grade WHERE id_tenant = plataforma.tenant_atual()), 0) + 1,
                                ?, %s)
                            RETURNING id_grade
                            """.formatted(COLUNAS_SLOT, PLACEHOLDERS_SLOT))
                    .params(params)
                    .query(Long.class).single();
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma grade com esse nome.");
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Tamanho informado não existe.");
        }
    }

    @Transactional
    public GradeResponse atualizar(long id, GradeRequest req) {
        if (id == 1) {
            throw new ResponseStatusException(NOT_FOUND, "Grade não encontrada.");
        }
        String descricao = validarEDescricao(req);
        List<Object> params = new ArrayList<>();
        params.add(descricao);
        params.addAll(empacotarSlots(req.idsTamanho()));
        params.add(id);
        try {
            int linhas = jdbc.sql("UPDATE cfg_grade SET descricao = ?, " + ATRIBUICOES_SLOT
                            + " WHERE id_grade = ? AND id_tenant = plataforma.tenant_atual()")
                    .params(params)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Grade não encontrada.");
            }
            return buscar(id);
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma grade com esse nome.");
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Tamanho informado não existe.");
        }
    }

    /** Garante que {@code idTamanho} faça parte da grade — se já estiver em algum slot, não faz
     * nada; senão anexa no próximo slot livre (extensão automática, usada pelo fluxo Planilha da
     * Entrada de Produtos, 2026-08-11: uma variação de tamanho nova pra um produto já cadastrado
     * é uma ampliação legítima da curva, o mesmo que o usuário faria manualmente em "＋ Gerenciar
     * Grades"). Lança se a grade já estiver com os {@value #MAX_TAMANHOS} slots ocupados —
     * quem chama decide se isso vira pendência pro usuário resolver à mão. */
    @Transactional
    public void garantirTamanhoNaGrade(long idGrade, long idTamanho) {
        GradeResponse grade = buscar(idGrade);
        if (grade.tamanhos().stream().anyMatch(t -> t.idTamanho() == idTamanho)) {
            return;
        }
        if (grade.tamanhos().size() >= MAX_TAMANHOS) {
            throw new IllegalArgumentException("a grade \"" + grade.descricao() + "\" já tem " + MAX_TAMANHOS + " tamanhos.");
        }
        List<Long> idsAtualizados = new ArrayList<>(grade.tamanhos().stream().map(TamanhoResponse::idTamanho).toList());
        idsAtualizados.add(idTamanho);
        atualizar(idGrade, new GradeRequest(grade.descricao(), idsAtualizados));
    }

    private static String validarEDescricao(GradeRequest req) {
        if (req.idsTamanho() != null) {
            if (req.idsTamanho().size() > MAX_TAMANHOS) {
                throw new IllegalArgumentException("Uma grade aceita no máximo " + MAX_TAMANHOS + " tamanhos.");
            }
            long distintos = req.idsTamanho().stream().distinct().count();
            if (distintos != req.idsTamanho().size()) {
                throw new IllegalArgumentException("Tamanho duplicado na grade.");
            }
        }
        return req.descricao().trim().toUpperCase(Locale.ROOT);
    }

    /** Posição na lista (0..19) vira slot 1..20; slots além do tamanho da lista ficam {@code
     * NULL} (não 0 — convenção do projeto para "sem valor" em FK opcional). */
    private static List<Object> empacotarSlots(List<Long> idsTamanho) {
        List<Object> slots = new ArrayList<>(MAX_TAMANHOS);
        for (int i = 0; i < MAX_TAMANHOS; i++) {
            slots.add(idsTamanho != null && i < idsTamanho.size() ? idsTamanho.get(i) : null);
        }
        return slots;
    }

    private Map<Long, TamanhoResponse> resolverTamanhos(List<GradeBruta> brutas) {
        List<Long> ids = brutas.stream().flatMap(b -> b.idsTamanho().stream()).distinct().toList();
        Map<Long, TamanhoResponse> mapa = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return mapa;
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        for (TamanhoResponse t : jdbc.sql("SELECT id_tamanho, descricao FROM cfg_tamanho "
                        + "WHERE id_tenant = plataforma.tenant_atual() AND id_tamanho IN (" + placeholders + ")")
                .params(ids)
                .query((rs, n) -> new TamanhoResponse(rs.getLong("id_tamanho"), rs.getString("descricao")))
                .list()) {
            mapa.put(t.idTamanho(), t);
        }
        return mapa;
    }

    private static GradeResponse montarResponse(GradeBruta b, Map<Long, TamanhoResponse> tamanhos) {
        List<TamanhoResponse> lista = b.idsTamanho().stream().map(tamanhos::get).toList();
        return new GradeResponse(b.idGrade(), b.descricao(), lista);
    }

    private record GradeBruta(long idGrade, String descricao, List<Long> idsTamanho) {
    }

    private static final String COLUNAS_SLOT = String.join(", ",
            java.util.stream.IntStream.rangeClosed(1, MAX_TAMANHOS).mapToObj(i -> "id_tamanho" + i).toList());
    private static final String PLACEHOLDERS_SLOT = String.join(", ", java.util.Collections.nCopies(MAX_TAMANHOS, "?"));
    private static final String ATRIBUICOES_SLOT = String.join(", ",
            java.util.stream.IntStream.rangeClosed(1, MAX_TAMANHOS).mapToObj(i -> "id_tamanho" + i + " = ?").toList());
    private static final String SELECT_COLUNAS = "SELECT id_grade, descricao, " + COLUNAS_SLOT;
    /** {@code IS NOT DISTINCT FROM} (não {@code =}) porque slots não usados são {@code NULL} —
     * comparação NULL-safe para casar duas grades com o mesmo comprimento (ex.: ambas com só
     * 15 tamanhos preenchidos e o resto NULL). */
    private static final String IGUALDADE_SLOT = String.join(" AND ",
            java.util.stream.IntStream.rangeClosed(1, MAX_TAMANHOS)
                    .mapToObj(i -> "id_tamanho" + i + " IS NOT DISTINCT FROM ?").toList());

    private static GradeBruta mapearBruta(ResultSet rs, int rowNum) throws SQLException {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= MAX_TAMANHOS; i++) {
            long v = rs.getLong("id_tamanho" + i);
            if (!rs.wasNull()) {
                ids.add(v);
            }
        }
        return new GradeBruta(rs.getLong("id_grade"), rs.getString("descricao"), ids);
    }
}
