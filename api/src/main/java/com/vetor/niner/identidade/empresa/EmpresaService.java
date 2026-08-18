package com.vetor.niner.identidade.empresa;

import com.vetor.niner.cadastros.fornecedor.FornecedorService;
import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.identidade.empresa.EmpresaDtos.AtualizarEmpresaRequest;
import com.vetor.niner.identidade.empresa.EmpresaDtos.EmpresaDetalheResponse;
import com.vetor.niner.identidade.empresa.EmpresaDtos.EmpresaResponse;
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

    private final JdbcClient jdbc;

    public EmpresaService(JdbcClient jdbc) {
        this.jdbc = jdbc;
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

        try {
            jdbc.sql("""
                            UPDATE empresa SET
                                nome_fantasia = ?, cnpj = ?, inscricao_estadual = ?, inscricao_municipal = ?,
                                codigo_municipio_ibge = ?, cnae = ?, endereco = ?, numero = ?, complemento = ?,
                                bairro = ?, cidade = ?, estado = ?, cep = ?, telefone = ?, email = ?,
                                atualizado_em = now()
                            WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                            """)
                    .params(
                            maiusculas(req.nomeFantasia()), cnpj, maiusculas(vazioParaNulo(req.inscricaoEstadual())),
                            maiusculas(vazioParaNulo(req.inscricaoMunicipal())), req.codigoMunicipioIbge(),
                            vazioParaNulo(req.cnae()), maiusculas(req.endereco()), maiusculas(req.numero()),
                            maiusculas(req.complemento()), maiusculas(req.bairro()), maiusculas(req.cidade()),
                            maiusculas(vazioParaNulo(req.estado())), vazioParaNulo(req.cep()),
                            vazioParaNulo(req.telefone()), email, idEmpresa)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflitoDadosException("Já existe outra empresa com esse CNPJ neste tenant.");
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Dados inválidos para atualizar a empresa.");
        }

        return buscarPorId(jwt, idEmpresa);
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
                   cidade, estado, cep, telefone, email, ativo, criado_em, atualizado_em
            FROM empresa
            """;

    private static EmpresaDetalheResponse mapearDetalhe(ResultSet rs, int rowNum) throws SQLException {
        Object codigoMunicipio = rs.getObject("codigo_municipio_ibge");
        return new EmpresaDetalheResponse(
                rs.getLong("id_empresa"), rs.getInt("codigo_empresa"), rs.getString("razao_social"),
                rs.getString("nome_fantasia"), rs.getBoolean("matriz"), rs.getString("cnpj"),
                rs.getString("inscricao_estadual"), rs.getString("inscricao_municipal"),
                codigoMunicipio == null ? null : ((Number) codigoMunicipio).intValue(), rs.getString("cnae"),
                rs.getString("endereco"), rs.getString("numero"), rs.getString("complemento"), rs.getString("bairro"),
                rs.getString("cidade"), rs.getString("estado"), rs.getString("cep"), rs.getString("telefone"),
                rs.getString("email"), rs.getBoolean("ativo"),
                rs.getObject("criado_em", java.time.OffsetDateTime.class),
                rs.getObject("atualizado_em", java.time.OffsetDateTime.class));
    }
}
