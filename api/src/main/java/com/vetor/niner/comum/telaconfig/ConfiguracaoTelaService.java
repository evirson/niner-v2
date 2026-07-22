package com.vetor.niner.comum.telaconfig;

import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaDtos.ConfiguracaoCampoRequest;
import com.vetor.niner.comum.telaconfig.ConfiguracaoTelaDtos.ConfiguracaoCampoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Configuração por tenant de quais campos aparecem/são obrigatórios em cada tela
 * (docs/telas/configuracao-tela.md). Um campo sem linha em {@code cfg_tela_campo} usa o
 * default da tela (visível, não obrigatório) — o tenant nasce sem nenhuma configuração.
 *
 * <p>O registro de campos configuráveis por {@code chaveTela} é estático nesta classe:
 * cada tela nova que ganhar essa configuração entra aqui. Campos estruturalmente
 * obrigatórios (ex.: {@code nome}/{@code idCategoriaCliente} do cliente, que são
 * {@code NOT NULL} no banco) não entram no registro — não são configuráveis.
 */
@Service
public class ConfiguracaoTelaService {

    private static final Map<String, List<String>> CAMPOS_POR_TELA = Map.of(
            "cadastros.cliente.form", List.of(
                    "cpfCnpj", "rgIe", "email", "telefone", "whatsapp", "instagram", "facebook",
                    "tiktok", "cep", "endereco", "numero", "complemento", "bairro", "cidade",
                    "estado", "limiteCredito"),
            "cadastros.funcionario.form", List.of("cpf", "telefone", "cargo", "percComissao"),
            "cadastros.fornecedor.form", List.of(
                    "nomeFantasia", "cnpj", "inscricaoEstadual", "email", "telefone", "cep",
                    "endereco", "numero", "bairro", "cidade", "estado"),
            "catalogo.produto.form", List.of(
                    "marca", "referencia", "codigoNcm", "pesoBruto", "pesoLiquido",
                    "dataInicioOferta", "dataFinalOferta", "precoOferta"));

    private final JdbcClient jdbc;

    public ConfiguracaoTelaService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ConfiguracaoCampoResponse> listar(String chaveTela) {
        List<String> campos = camposValidos(chaveTela);

        // Filtro por id_tenant explícito além do RLS: defesa em profundidade (mesmo motivo do
        // INSERT em ConfiguracaoTelaService.salvar/ClienteService.criar usar
        // plataforma.tenant_atual() explicitamente) — também evita que o ambiente de teste
        // (datasource conecta como superusuário, sem RLS) vaze configuração entre tenants.
        Map<String, ConfiguracaoCampoResponse> salvos = jdbc.sql("""
                        SELECT campo, visivel, obrigatorio FROM cfg_tela_campo
                        WHERE chave_tela = ? AND id_tenant = plataforma.tenant_atual()
                        """)
                .param(chaveTela)
                .query((rs, n) -> new ConfiguracaoCampoResponse(
                        rs.getString("campo"), rs.getBoolean("visivel"), rs.getBoolean("obrigatorio")))
                .list()
                .stream()
                .collect(Collectors.toMap(ConfiguracaoCampoResponse::campo, c -> c));

        return campos.stream()
                .map(campo -> salvos.getOrDefault(campo, new ConfiguracaoCampoResponse(campo, true, false)))
                .toList();
    }

    @Transactional
    public List<ConfiguracaoCampoResponse> salvar(String chaveTela, Jwt jwt, List<ConfiguracaoCampoRequest> req) {
        exigirAdmin(jwt);
        List<String> validos = camposValidos(chaveTela);
        for (ConfiguracaoCampoRequest c : req) {
            if (!validos.contains(c.campo())) {
                throw new IllegalArgumentException("Campo não configurável nesta tela: " + c.campo());
            }
            if (c.obrigatorio() && !c.visivel()) {
                throw new IllegalArgumentException("Campo obrigatório não pode estar oculto: " + c.campo());
            }
        }
        for (ConfiguracaoCampoRequest c : req) {
            jdbc.sql("""
                            INSERT INTO cfg_tela_campo (id_tenant, chave_tela, campo, visivel, obrigatorio)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, ?)
                            ON CONFLICT (id_tenant, chave_tela, campo)
                            DO UPDATE SET visivel = excluded.visivel, obrigatorio = excluded.obrigatorio,
                                          atualizado_em = now()
                            """)
                    .params(chaveTela, c.campo(), c.visivel(), c.obrigatorio())
                    .update();
        }
        return listar(chaveTela);
    }

    private static List<String> camposValidos(String chaveTela) {
        List<String> campos = CAMPOS_POR_TELA.get(chaveTela);
        if (campos == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tela desconhecida: " + chaveTela);
        }
        return campos;
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem configurar esta tela.");
        }
    }
}
