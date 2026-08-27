package com.vetor.niner.identidade.empresa;

import com.vetor.niner.cadastros.fornecedor.FornecedorService;
import com.vetor.niner.comum.ramo.RamoAtividadeService;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.identidade.empresa.EmpresaDtos.AtualizarEmpresaRequest;
import com.vetor.niner.identidade.empresa.EmpresaDtos.CriarEmpresaRequest;
import com.vetor.niner.identidade.empresa.EmpresaDtos.EmpresaDetalheResponse;
import com.vetor.niner.identidade.empresa.EmpresaDtos.EmpresaResponse;
import com.vetor.niner.plataforma.uso.UsoTenantService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Leitura (e, desde 2026-08-19, atualização) de empresas do tenant, sem paginação — hoje o v1
 * tem no máximo poucas dezenas.
 *
 * <p>Filtro por {@code id_tenant} explícito além do RLS — mesmo motivo documentado em
 * {@code ClienteHistoricoService}/{@code TipoCarteiraService} (Testcontainers conecta como
 * superusuário, que ignora RLS mesmo com {@code FORCE}).
 *
 * <p><b>Edição (2026-08-19):</b> até então a empresa era só leitura — a Conformidade Fiscal
 * apontava CNPJ/Inscrição Estadual/código de município IBGE/CNAE faltando, mas não existia
 * nenhuma tela nem endpoint pra preencher. {@link #buscarPorId}/{@link #atualizar} fecham essa
 * lacuna. {@code razaoSocial}/{@code codigoEmpresa}/{@code matriz} continuam imutáveis por aqui —
 * são estruturais, não fazem parte do formulário.
 */
@Service
public class EmpresaService {

    /** Mesmo modelo que o signup grava na primeira empresa — a coluna é NOT NULL sem default. */
    private static final String ETIQUETA_PADRAO = "{sku}\n{descricao}\n{preco_venda}";

    private final JdbcClient jdbc;
    private final UsoTenantService usoTenant;
    private final RamoAtividadeService ramos;

    public EmpresaService(JdbcClient jdbc, UsoTenantService usoTenant, RamoAtividadeService ramos) {
        this.jdbc = jdbc;
        this.usoTenant = usoTenant;
        this.ramos = ramos;
    }

    /**
     * Inclui uma empresa/CNPJ no tenant — ADMIN-only (2026-08-18, ADR-015). Antes disso, filial
     * era inserida por SQL direto pela equipe; com CNPJ ilimitado em todos os planos, virou
     * autoatendimento pelo painel <i>Minha Conta</i>.
     *
     * <p>O que nasce junto, na mesma transação: {@code codigo_empresa} = maior do tenant + 1
     * (respeita {@code empresa_codigo_empresa_uk}), {@code matriz = false} (a matriz é a primeira,
     * criada no signup), {@code cfg_nome_etiqueta} padrão, e o vínculo em {@code usuario_empresa}
     * do ADMIN que criou — sem ele, a empresa nova não apareceria para ninguém no login.
     *
     * <p>⛔ <b>Este caminho não pode ganhar tela dentro do ERP</b> (decisão do dono do produto,
     * 2026-08-27): incluir CNPJ deixou de ser ato operacional e virou <b>ato comercial</b>, porque
     * a cobrança passou a ser por CNPJ contratado. A inclusão acontece na <b>tela de
     * contratação</b>, que é quem apresenta o plano e a escolha entre acrescentar ao grupo
     * existente ou abrir um grupo separado. O endpoint continua existindo para servir a esse
     * fluxo — hoje ele não é alcançável por nenhuma tela do `web/`.
     *
     * <p>⚠️ O que <b>não</b> mudou: a cota de vendas continua sendo do TENANT, somando todos os
     * CNPJs (regra 3 de 2026-08-27) — incluir um CNPJ não cria cota nova nem assinatura nova.
     * O plano é do tenant, nunca da empresa.
     *
     * <p>O que <b>não</b> nasce junto: plano de contas, tipos de carteira e perfis fiscais são
     * <b>por tenant</b> (ver {@code SignupService.assinar}) e já existem; {@code
     * fiscal_config_empresa} é por empresa, mas só é criada quando o lojista ligar o fiscal
     * naquela empresa (F12) — incluir um CNPJ não presume que ele vá emitir nota.
     */
    @Transactional
    public EmpresaDetalheResponse criar(Jwt jwt, CriarEmpresaRequest req) {
        exigirAdmin(jwt);

        String cnpj = vazioParaNulo(req.cnpj());
        if (cnpj != null && !FornecedorService.cnpjValido(cnpj)) {
            throw new IllegalArgumentException("CNPJ inválido.");
        }

        long idEmpresa;
        try {
            idEmpresa = jdbc.sql("""
                            INSERT INTO empresa (id_tenant, codigo_empresa, razao_social, nome_fantasia, cnpj,
                                                 matriz, cfg_nome_etiqueta, id_ramo)
                            SELECT plataforma.tenant_atual(),
                                   COALESCE(MAX(e.codigo_empresa), 0) + 1,
                                   ?, ?, ?, false, ?, ?
                              FROM empresa e
                             WHERE e.id_tenant = plataforma.tenant_atual()
                            RETURNING id_empresa
                            """)
                    .params(maiusculas(req.razaoSocial()), maiusculas(req.nomeFantasia()), cnpj, ETIQUETA_PADRAO,
                            ramoValido(req.idRamo()))
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe uma empresa com esse CNPJ neste tenant.");
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Dados inválidos para criar a empresa.");
        }

        jdbc.sql("""
                        INSERT INTO usuario_empresa (id_tenant, id_usuario, id_empresa)
                        VALUES (plataforma.tenant_atual(), ?, ?)
                        """)
                .params(Long.parseLong(jwt.getSubject()), idEmpresa)
                .update();

        usoTenant.recontarEmpresas();
        return buscarPorId(jwt, idEmpresa);
    }

    @Transactional(readOnly = true)
    public List<EmpresaResponse> listar() {
        return jdbc.sql("""
                        SELECT id_empresa, codigo_empresa, razao_social, nome_fantasia, ativo
                        FROM empresa
                        WHERE id_tenant = plataforma.tenant_atual()
                        ORDER BY codigo_empresa ASC
                        """)
                .query(EmpresaService::mapear)
                .list();
    }

    /**
     * Empresas que o usuário logado pode operar — usado pela Entrada de Produtos por Compra
     * pra saber em qual empresa dar entrada (2026-08-11). ADMIN vê todas do tenant (mesmo
     * resultado de {@link #listar()}); OPERADOR só as ligadas a ele via {@code usuario_empresa}
     * (mesma query de {@code SignupService.login}, que lista as empresas de um usuário no
     * login em duas voltas).
     */
    @Transactional(readOnly = true)
    public List<EmpresaResponse> listarPermitidas(Jwt jwt) {
        if (ehAdmin(jwt)) {
            return listar();
        }
        long idUsuario = Long.parseLong(jwt.getSubject());
        return jdbc.sql("""
                        SELECT e.id_empresa, e.codigo_empresa, e.razao_social, e.nome_fantasia, e.ativo
                        FROM usuario_empresa ue
                        JOIN empresa e ON e.id_empresa = ue.id_empresa AND e.id_tenant = ue.id_tenant
                        WHERE ue.id_tenant = plataforma.tenant_atual() AND ue.id_usuario = ?
                        ORDER BY e.codigo_empresa ASC
                        """)
                .param(idUsuario)
                .query(EmpresaService::mapear)
                .list();
    }

    /** Ficha completa de uma empresa — ADMIN-only (a mesma tela onde o CNPJ/dados fiscais são
     *  editados não faz sentido pro OPERADOR ver/mexer). */
    @Transactional(readOnly = true)
    public EmpresaDetalheResponse buscarPorId(Jwt jwt, long idEmpresa) {
        exigirAdmin(jwt);
        return jdbc.sql(SELECT_DETALHE + " WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?")
                .param(idEmpresa)
                .query(EmpresaService::mapearDetalhe)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Empresa não encontrada."));
    }

    /**
     * Atualiza identificação/endereço/dados fiscais de uma empresa — ADMIN-only. CNPJ e e-mail,
     * quando informados, passam pela mesma validação do cadastro de Fornecedor
     * ({@link FornecedorService#cnpjValido}/{@link FornecedorService#emailValido} — CNPJ
     * alfanumérico, IN RFB 2.229/2024); em branco é aceito (a Conformidade Fiscal é quem cobra o
     * preenchimento, não este formulário — mesmo princípio de {@code TipoCarteiraForm}).
     * Campos de texto livre são gravados em maiúsculas (convenção do projeto).
     */
    @Transactional
    public EmpresaDetalheResponse atualizar(Jwt jwt, long idEmpresa, AtualizarEmpresaRequest req) {
        exigirAdmin(jwt);
        exigirEmpresaDoTenant(idEmpresa);

        String cnpj = vazioParaNulo(req.cnpj());
        if (cnpj != null && !FornecedorService.cnpjValido(cnpj)) {
            throw new IllegalArgumentException("CNPJ inválido.");
        }
        String email = vazioParaNulo(req.email());
        if (email != null && !FornecedorService.emailValido(email)) {
            throw new IllegalArgumentException("E-mail inválido.");
        }
        String estado = validarUf(req.estado());

        try {
            jdbc.sql("""
                            UPDATE empresa SET
                                nome_fantasia = ?, cnpj = ?, inscricao_estadual = ?, inscricao_municipal = ?,
                                codigo_municipio_ibge = ?, cnae = ?, endereco = ?, numero = ?, complemento = ?,
                                bairro = ?, cidade = ?, estado = ?, cep = ?, telefone = ?, email = ?, id_ramo = ?,
                                atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                            """)
                    .params(
                            maiusculas(req.nomeFantasia()), cnpj, maiusculas(vazioParaNulo(req.inscricaoEstadual())),
                            maiusculas(vazioParaNulo(req.inscricaoMunicipal())), req.codigoMunicipioIbge(),
                            vazioParaNulo(req.cnae()), maiusculas(req.endereco()), maiusculas(req.numero()),
                            maiusculas(req.complemento()), maiusculas(req.bairro()), maiusculas(req.cidade()),
                            estado, vazioParaNulo(req.cep()),
                            vazioParaNulo(req.telefone()), email, ramoValido(req.idRamo()), idEmpresa)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe outra empresa com esse CNPJ neste tenant.");
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Dados inválidos para atualizar a empresa.");
        }

        return buscarPorId(jwt, idEmpresa);
    }

    /**
     * A UF da empresa deixou de ser texto livre em 2026-08-20: ela agora decide o <b>fuso da loja</b>
     * ({@link com.vetor.niner.comum.tempo.FusoDaUf}) e, por tabela, para qual SEFAZ o documento é
     * transmitido ({@code cfg_uf_autorizador}). Sigla inválida vira hora errada no cupom e nota
     * mandada para o autorizador errado — dois defeitos que só aparecem depois, no cliente. O banco
     * recusa também, pelo CHECK da V049; esta validação é para o usuário receber a mensagem certa.
     *
     * <p><b>Vazio continua valendo</b>: o signup cria a empresa <b>sem</b> UF (o funil não pergunta)
     * e o lojista preenche depois em Dados da Empresa. Quem <b>exige</b> a UF é o caminho fiscal,
     * que falha explicitamente sem ela — emitir nota com UF chutada seria pior.
     */
    private static String validarUf(String uf) {
        String normalizada = maiusculas(vazioParaNulo(uf));
        if (normalizada == null) {
            return null;
        }
        try {
            com.vetor.niner.comum.tempo.FusoDaUf.de(normalizada);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "UF inválida: \"" + uf + "\". Informe uma das 27 siglas (ex.: PR, SP, AM).");
        }
        return normalizada;
    }

    private static String vazioParaNulo(String valor) {
        return (valor == null || valor.isBlank()) ? null : valor.trim();
    }

    private static String maiusculas(String valor) {
        String v = vazioParaNulo(valor);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private void exigirEmpresaDoTenant(long idEmpresa) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM empresa
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?)
                        """)
                .param(idEmpresa).query(Boolean.class).single());
        if (!existe) {
            throw new ResponseStatusException(NOT_FOUND, "Empresa não encontrada.");
        }
    }

    private static void exigirAdmin(Jwt jwt) {
        if (!ehAdmin(jwt)) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas ADMIN pode ver ou editar os dados da empresa.");
        }
    }

    private static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }

    private static EmpresaResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        return new EmpresaResponse(
                rs.getLong("id_empresa"),
                rs.getInt("codigo_empresa"),
                rs.getString("razao_social"),
                rs.getString("nome_fantasia"),
                rs.getBoolean("ativo"));
    }

    private static final String SELECT_DETALHE = """
            SELECT id_empresa, codigo_empresa, razao_social, nome_fantasia, matriz, cnpj, inscricao_estadual,
                   inscricao_municipal, codigo_municipio_ibge, cnae, endereco, numero, complemento, bairro,
                   cidade, estado, cep, telefone, email, id_ramo, ativo, criado_em, atualizado_em
            FROM empresa
            """;

    /**
     * Ramo de atividade conferido contra {@code cfg_ramo_atividade} (V072). Id que não existe é
     * <b>recusado</b>, e não silenciosamente ignorado: aqui, ao contrário do signup, o usuário
     * está numa tela do sistema escolhendo numa lista — um id fora dela significa cliente de API
     * mandando lixo, não alguém pulando um campo opcional. {@code null} continua valendo como
     * "não informado".
     */
    private Integer ramoValido(Integer idRamo) {
        if (idRamo == null) {
            return null;
        }
        if (!ramos.existe(idRamo)) {
            throw new IllegalArgumentException("Ramo de atividade inválido.");
        }
        return idRamo;
    }

    /** {@code id_ramo} é {@code smallint} e pode ser nulo — {@code getInt} devolveria 0. */
    private static Integer idRamo(ResultSet rs) throws SQLException {
        int valor = rs.getInt("id_ramo");
        return rs.wasNull() ? null : valor;
    }

    private static EmpresaDetalheResponse mapearDetalhe(ResultSet rs, int rowNum) throws SQLException {
        Object codigoMunicipio = rs.getObject("codigo_municipio_ibge");
        return new EmpresaDetalheResponse(
                rs.getLong("id_empresa"), rs.getInt("codigo_empresa"), rs.getString("razao_social"),
                rs.getString("nome_fantasia"), rs.getBoolean("matriz"), rs.getString("cnpj"),
                rs.getString("inscricao_estadual"), rs.getString("inscricao_municipal"),
                codigoMunicipio == null ? null : ((Number) codigoMunicipio).intValue(), rs.getString("cnae"),
                rs.getString("endereco"), rs.getString("numero"), rs.getString("complemento"), rs.getString("bairro"),
                rs.getString("cidade"), rs.getString("estado"), rs.getString("cep"), rs.getString("telefone"),
                rs.getString("email"), idRamo(rs), rs.getBoolean("ativo"),
                rs.getObject("criado_em", java.time.OffsetDateTime.class),
                rs.getObject("atualizado_em", java.time.OffsetDateTime.class));
    }
}
