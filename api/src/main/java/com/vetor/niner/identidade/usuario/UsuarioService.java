package com.vetor.niner.identidade.usuario;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.identidade.usuario.UsuarioDtos.EmpresaAcesso;
import com.vetor.niner.identidade.usuario.UsuarioDtos.ExclusaoUsuarioResponse;
import com.vetor.niner.identidade.usuario.UsuarioDtos.HorarioAcessoRequest;
import com.vetor.niner.identidade.usuario.UsuarioDtos.HorarioAcessoResponse;
import com.vetor.niner.identidade.usuario.UsuarioDtos.PaginaUsuarios;
import com.vetor.niner.identidade.usuario.UsuarioDtos.UsuarioRequest;
import com.vetor.niner.identidade.usuario.UsuarioDtos.UsuarioResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * CRUD de usuários do tenant (docs/telas/usuario.md). Tabela {@code usuario} sob RLS (V015/
 * V024) — mesmo padrão de {@code cadastros.funcionario}, mas toda a superfície é restrita a
 * {@code ADMIN} ({@link #exigirAdmin}, mesmo mecanismo de {@code ConfiguracaoGeralService}):
 * gerenciar quem acessa o sistema é sensível o bastante pra não seguir a regra geral de
 * "OPERADOR também tem acesso" do resto de {@code cadastros}.
 *
 * <p><b>Só existe um ADMIN por tenant, para sempre (2026-07-28, decisão de produto).</b> Ele
 * nasce no signup ({@code SignupService.assinar}) e esta tela NUNCA cria nem edita o campo
 * {@code administrador} — {@link #criar} sempre grava {@code false}, {@link #atualizar} nunca
 * toca essa coluna, não importa o que vier no request. Não existe mais "promover a admin" nem
 * "rebaixar admin" por aqui — o privilégio, uma vez concedido (só no signup), é permanente. O
 * índice único parcial {@code usuario_um_admin_uk} (V015) é a garantia de banco por trás disso.
 * (Substitui a trava anterior de "só outro admin pode mudar o nível", removida porque não fazia
 * mais sentido existindo um único admin possível — ver docs/telas/usuario.md.)
 *
 * <p>Empresas com acesso ({@code usuario_empresa}, N:N) são substituídas por completo a cada
 * criação/atualização (mesmo idioma de {@code ProdutoService.salvarCategorias}: apaga tudo e
 * reinsere a lista recebida, dentro da mesma transação) — sempre pelo menos uma
 * ({@code @NotEmpty} no DTO), senão o usuário nasceria sem conseguir operar em nenhuma loja.
 * {@code usuario.id_empresa} (empresa de "lotação", legado) é mantido em sincronia com a
 * primeira empresa da lista, só para não deixar a coluna órfã — não aparece no formulário.
 *
 * <p>Permissão fina por rotina ({@code usuario_rotina}) ainda não tem UI nem é gravada por
 * este serviço — fica para quando o produto definir a granularidade (ver spec §3.3.2, "🔴
 * Mapear usuario_rotina para ADMIN/OPERADOR"). Registrado também em docs/telas/usuario.md.
 */
@Service
public class UsuarioService {

    private static final int TAMANHO_PAGINA_PADRAO = 50;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    private static final Map<String, String> COLUNAS_ORDENAVEIS = Map.of(
            "nome", "u.nome_usuario",
            "email", "u.email",
            "papel", "u.administrador",
            "status", "u.ativo");

    private final JdbcClient jdbc;
    private final PasswordEncoder senhas;

    public UsuarioService(JdbcClient jdbc, PasswordEncoder senhas) {
        this.jdbc = jdbc;
        this.senhas = senhas;
    }

    @Transactional(readOnly = true)
    public PaginaUsuarios listar(Jwt jwt, String nome, String status, Integer pagina, Integer limite,
                                  String ordenarPor, String direcao) {
        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);
        String coluna = ordenarPor == null ? "u.nome_usuario" : COLUNAS_ORDENAVEIS.getOrDefault(ordenarPor, "u.nome_usuario");
        String direcaoOrdenacao = "DESC".equalsIgnoreCase(direcao) ? "DESC" : "ASC";

        StringBuilder filtro = new StringBuilder(" WHERE u.id_tenant = plataforma.tenant_atual()");
        List<Object> params = new ArrayList<>();
        if (nome != null && !nome.isBlank()) {
            filtro.append(" AND (u.nome_usuario ILIKE ? OR u.email ILIKE ?)");
            params.add("%" + nome.trim() + "%");
            params.add("%" + nome.trim() + "%");
        }
        switch (status == null ? "ATIVOS" : status.toUpperCase(Locale.ROOT)) {
            case "INATIVOS" -> filtro.append(" AND u.ativo = false");
            case "TODOS" -> { /* sem filtro de status */ }
            default -> filtro.append(" AND u.ativo = true");
        }

        long totalItens = jdbc.sql("SELECT count(*) FROM usuario u" + filtro).params(params).query(Long.class).single();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);

        List<Object> paramsPagina = new ArrayList<>(params);
        paramsPagina.add((long) tamanho);
        paramsPagina.add((long) (paginaAtual - 1) * tamanho);
        String ordenacao = " ORDER BY " + coluna + " " + direcaoOrdenacao
                + ", u.id_usuario " + direcaoOrdenacao + " LIMIT ? OFFSET ?";
        List<Long> ids = jdbc.sql("SELECT u.id_usuario FROM usuario u" + filtro + ordenacao)
                .params(paramsPagina).query(Long.class).list();

        List<UsuarioResponse> itens = ids.stream().map(this::montar).toList();
        return new PaginaUsuarios(itens, paginaAtual, tamanho, totalItens, totalPaginas);
    }

    @Transactional(readOnly = true)
    public UsuarioResponse buscar(Jwt jwt, long id) {
        return montar(id);
    }

    @Transactional
    public UsuarioResponse criar(Jwt jwt, UsuarioRequest req) {
        validar(req, true);
        try {
            long id = jdbc.sql("""
                            INSERT INTO usuario (id_tenant, id_empresa, nome_usuario, email, senha_hash, ativo,
                                                  administrador, controla_horario_acesso,
                                                  exige_codigo_login)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING id_usuario
                            """)
                    .params(req.idsEmpresa().get(0), req.nome().trim().toUpperCase(Locale.ROOT),
                            req.email().trim().toLowerCase(Locale.ROOT), senhas.encode(req.senha()),
                            req.ativo() == null || req.ativo(), false, Boolean.TRUE.equals(req.controlaHorarioAcesso()),
                            Boolean.TRUE.equals(req.exigeCodigoLogin()))
                    .query(Long.class).single();
            salvarEmpresas(id, req.idsEmpresa());
            salvarHorarios(id, Boolean.TRUE.equals(req.controlaHorarioAcesso()), req.horarios());
            return montar(id);
        } catch (DuplicateKeyException e) {
            throw duplicidade(e);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Não foi possível salvar o usuário.");
        }
    }

    @Transactional
    public UsuarioResponse atualizar(Jwt jwt, long id, UsuarioRequest req) {
        validar(req, false);
        try {
            // administrador NUNCA entra aqui — nem pra manter o valor atual explicitamente,
            // nem pra mudar (ver javadoc da classe). A coluna simplesmente não aparece no SET.
            List<Object> params = new ArrayList<>(List.of(
                    req.idsEmpresa().get(0), req.nome().trim().toUpperCase(Locale.ROOT),
                    req.email().trim().toLowerCase(Locale.ROOT),
                    req.ativo() == null || req.ativo(), Boolean.TRUE.equals(req.controlaHorarioAcesso()),
                    Boolean.TRUE.equals(req.exigeCodigoLogin())));
            String setSenha = "";
            if (req.senha() != null && !req.senha().isBlank()) {
                setSenha = ", senha_hash = ?";
                params.add(senhas.encode(req.senha()));
            }
            params.add(id);
            int linhas = jdbc.sql("""
                            UPDATE usuario SET id_empresa = ?, nome_usuario = ?, email = ?, ativo = ?,
                                                controla_horario_acesso = ?, exige_codigo_login = ?
                            """ + setSenha + """
                            , atualizado_em = now()
                            WHERE id_usuario = ? AND id_tenant = plataforma.tenant_atual()
                            """)
                    .params(params)
                    .update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Usuário não encontrado.");
            }
            salvarEmpresas(id, req.idsEmpresa());
            salvarHorarios(id, Boolean.TRUE.equals(req.controlaHorarioAcesso()), req.horarios());
            return montar(id);
        } catch (DuplicateKeyException e) {
            throw duplicidade(e);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Não foi possível salvar o usuário.");
        }
    }

    /**
     * Exclui, ou inativa em vez de excluir se houver caixa aberto/fechado vinculado
     * ({@code caixa_mestre.id_usuario}, V025) — mesmo padrão de {@code cadastros.cliente}/
     * {@code cadastros.funcionario}. Um usuário nunca pode excluir a própria conta (travaria o
     * próprio acesso e, no limite, o do tenant inteiro se fosse o único ADMIN).
     */
    @Transactional
    public ExclusaoUsuarioResponse excluir(Jwt jwt, long id) {
        if (id == Long.parseLong(jwt.getSubject())) {
            throw new IllegalArgumentException("Você não pode excluir a própria conta.");
        }

        boolean temCaixa = Boolean.TRUE.equals(
                jdbc.sql("SELECT EXISTS (SELECT 1 FROM caixa_mestre WHERE id_usuario = ? AND id_tenant = plataforma.tenant_atual())")
                        .param(id).query(Boolean.class).single());

        if (temCaixa) {
            int linhas = jdbc.sql("""
                            UPDATE usuario SET ativo = false, atualizado_em = now()
                            WHERE id_usuario = ? AND id_tenant = plataforma.tenant_atual()
                            """)
                    .param(id).update();
            if (linhas == 0) {
                throw new ResponseStatusException(NOT_FOUND, "Usuário não encontrado.");
            }
            return new ExclusaoUsuarioResponse("inativado", "Usuário possui caixa(s) associado(s).");
        }

        int linhas = jdbc.sql("DELETE FROM usuario WHERE id_usuario = ? AND id_tenant = plataforma.tenant_atual()")
                .param(id).update();
        if (linhas == 0) {
            throw new ResponseStatusException(NOT_FOUND, "Usuário não encontrado.");
        }
        return new ExclusaoUsuarioResponse("excluido", null);
    }

    private void validar(UsuarioRequest req, boolean criando) {
        boolean senhaInformada = req.senha() != null && !req.senha().isBlank();
        if (criando && !senhaInformada) {
            throw new IllegalArgumentException("Senha é obrigatória para um usuário novo.");
        }
        if (senhaInformada && req.senha().trim().length() < 8) {
            throw new IllegalArgumentException("Senha deve ter ao menos 8 caracteres.");
        }
        if (req.idsEmpresa() == null || req.idsEmpresa().isEmpty()) {
            throw new IllegalArgumentException("Selecione ao menos uma empresa que o usuário poderá acessar.");
        }
        if (Boolean.TRUE.equals(req.controlaHorarioAcesso())) {
            List<HorarioAcessoRequest> horarios = req.horarios() == null ? List.of() : req.horarios();
            Set<Integer> dias = horarios.stream().map(HorarioAcessoRequest::diaSemana).collect(Collectors.toSet());
            if (dias.size() != horarios.size() || !dias.equals(Set.of(1, 2, 3, 4, 5, 6, 7))) {
                throw new IllegalArgumentException(
                        "Informe o horário (ou deixe em branco para sem acesso) dos 7 dias da semana.");
            }
            for (HorarioAcessoRequest h : horarios) {
                if ((h.horaInicio() == null) != (h.horaFim() == null)) {
                    throw new IllegalArgumentException("Informe início e fim do horário juntos, ou deixe os dois em branco.");
                }
                if (h.horaInicio() != null && !h.horaFim().isAfter(h.horaInicio())) {
                    throw new IllegalArgumentException("O horário final deve ser depois do horário inicial.");
                }
            }
        }
    }

    /** Substitui por completo os horários de acesso do usuário — mesmo idioma "apaga tudo e
     *  reinsere" de {@link #salvarEmpresas}. Com o controle desligado, só limpa (nada fica
     *  gravado nem aplicado — coerente com "desligado, não pede nem aplica horário nenhum"). */
    private void salvarHorarios(long idUsuario, boolean controla, List<HorarioAcessoRequest> horarios) {
        jdbc.sql("DELETE FROM usuario_horario_acesso WHERE id_usuario = ? AND id_tenant = plataforma.tenant_atual()")
                .param(idUsuario).update();
        if (!controla || horarios == null) {
            return;
        }
        for (HorarioAcessoRequest h : horarios) {
            jdbc.sql("""
                            INSERT INTO usuario_horario_acesso (id_tenant, id_usuario, dia_semana, hora_inicio, hora_fim)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?)
                            """)
                    .params(idUsuario, h.diaSemana(), h.horaInicio(), h.horaFim())
                    .update();
        }
    }

    /** Substitui por completo a lista de empresas do usuário — mesmo idioma de "apaga tudo e reinsere". */
    private void salvarEmpresas(long idUsuario, List<Long> idsEmpresa) {
        jdbc.sql("DELETE FROM usuario_empresa WHERE id_usuario = ? AND id_tenant = plataforma.tenant_atual()")
                .param(idUsuario).update();
        for (Long idEmpresa : idsEmpresa) {
            jdbc.sql("""
                            INSERT INTO usuario_empresa (id_tenant, id_usuario, id_empresa)
                            VALUES (plataforma.tenant_atual(), ?, ?)
                            """)
                    .params(idUsuario, idEmpresa)
                    .update();
        }
    }

    private record UsuarioBase(String nome, String email, boolean ativo, boolean administrador,
            boolean controlaHorarioAcesso, boolean exigeCodigoLogin, OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    private UsuarioResponse montar(long id) {
        UsuarioBase base = jdbc.sql("""
                        SELECT nome_usuario, email, ativo, administrador, controla_horario_acesso,
                               exige_codigo_login,
                               criado_em, atualizado_em
                        FROM usuario WHERE id_usuario = ? AND id_tenant = plataforma.tenant_atual()
                        """)
                .param(id)
                .query((rs, n) -> new UsuarioBase(
                        rs.getString("nome_usuario"), rs.getString("email"),
                        rs.getBoolean("ativo"), rs.getBoolean("administrador"), rs.getBoolean("controla_horario_acesso"),
                        rs.getBoolean("exige_codigo_login"),
                        rs.getObject("criado_em", OffsetDateTime.class),
                        rs.getObject("atualizado_em", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Usuário não encontrado."));

        List<EmpresaAcesso> empresas = jdbc.sql("""
                        SELECT e.id_empresa, COALESCE(e.nome_fantasia, e.razao_social) AS nome_empresa
                        FROM usuario_empresa ue
                        JOIN empresa e ON e.id_empresa = ue.id_empresa AND e.id_tenant = ue.id_tenant
                        WHERE ue.id_usuario = ? AND ue.id_tenant = plataforma.tenant_atual()
                        ORDER BY e.codigo_empresa ASC
                        """)
                .param(id)
                .query((rs, n) -> new EmpresaAcesso(rs.getLong("id_empresa"), rs.getString("nome_empresa")))
                .list();

        List<HorarioAcessoResponse> horarios = jdbc.sql("""
                        SELECT dia_semana, hora_inicio, hora_fim FROM usuario_horario_acesso
                        WHERE id_usuario = ? AND id_tenant = plataforma.tenant_atual()
                        ORDER BY dia_semana ASC
                        """)
                .param(id)
                .query((rs, n) -> new HorarioAcessoResponse(rs.getInt("dia_semana"),
                        rs.getObject("hora_inicio", LocalTime.class), rs.getObject("hora_fim", LocalTime.class)))
                .list();

        return new UsuarioResponse(id, base.nome(), base.email(), base.ativo(), base.administrador(),
                empresas, base.controlaHorarioAcesso(), horarios, base.exigeCodigoLogin(),
                base.criadoEm(), base.atualizadoEm());
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas administradores podem gerenciar usuários.");
        }
    }

    private static ConflitoDadosException duplicidade(DuplicateKeyException e) {
        String causa = String.valueOf(e.getRootCause());
        if (causa.contains("usuario_email_uk")) {
            return new ConflitoDadosException("Já existe um usuário com esse e-mail.");
        }
        if (causa.contains("usuario_um_admin_uk")) {
            return new ConflitoDadosException("O tenant já tem um administrador — só pode haver um.");
        }
        return new ConflitoDadosException("Já existe um usuário com esses dados.");
    }
}
