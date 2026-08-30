package com.vetor.niner.fiscal.conformidade;

import com.vetor.niner.fiscal.conformidade.ConformidadeFiscalDtos.CategoriaConformidade;
import com.vetor.niner.fiscal.conformidade.ConformidadeFiscalDtos.CategoriaConformidadeResponse;
import com.vetor.niner.fiscal.conformidade.ConformidadeFiscalDtos.ItemPendenciaResponse;
import com.vetor.niner.fiscal.conformidade.ConformidadeFiscalDtos.PaginaPendencias;
import com.vetor.niner.fiscal.conformidade.ConformidadeFiscalDtos.PainelConformidadeResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Conformidade Fiscal (docs/telas/fiscal-conformidade.md, bloco B3) — painel de diagnóstico
 * somente-leitura, por empresa: o que impede ligar o fiscal e emitir, antes que o lojista
 * descubra no caixa (F11). <b>Nada é corrigido aqui</b> — a tela aponta e navega.
 *
 * <p><b>Duas simplificações deliberadas em relação ao rascunho original da spec</b>, decididas
 * ao implementar porque o schema real diverge do que o rascunho assumia:
 *
 * <ol>
 *   <li>{@code produto.unidade_comercial} (NOT NULL DEFAULT 'UN') e
 *       {@code produto.origem_mercadoria} (NOT NULL DEFAULT 0) <b>nunca são nulos</b> — são
 *       colunas com default, não colunas opcionais. Não há como distinguir "o lojista escolheu
 *       UN/nacional de propósito" de "ficou no default sem revisar", e os dois defaults são
 *       exatamente os valores mais comuns e corretos no varejo. Alarme aqui seria falso positivo
 *       em quase todo produto — os dois saíram da lista de checagem. Mesmo raciocínio para
 *       {@code unidade_tributavel}: {@code NULL} é o estado <b>correto</b> ("igual à comercial"),
 *       não uma pendência.</li>
 *   <li>{@code cliente.indicador_ie} é {@code NOT NULL DEFAULT 9} (V016) — a "regra para o nulo"
 *       que a spec original propunha decidir já está resolvida no schema (default 9, não
 *       contribuinte). O que sobra checável é a mesma ambiguidade com outro contorno: PJ com
 *       {@code indicador_ie = 9} pode ser um não-contribuinte de verdade ou só nunca ter sido
 *       revisado — o schema não distingue os dois casos. Vira aviso (nunca bloqueio), sabendo que
 *       é uma aproximação.</li>
 * </ol>
 *
 * <p>A contagem do painel é <b>por registro</b> (quantos produtos têm pendência), não por
 * checagem individual — um produto sem NCM e sem perfil conta 1, não 2. Mantém painel e
 * drill-down sempre consistentes (a mesma lista de IDs).
 */
@Service
public class ConformidadeFiscalService {

    private static final int TAMANHO_PAGINA_PADRAO = 50;
    private static final int TAMANHO_PAGINA_MAXIMO = 200;

    private final JdbcClient jdbc;

    public ConformidadeFiscalService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PainelConformidadeResponse painel(Jwt jwt, long idEmpresa) {        exigirEmpresaDoTenant(idEmpresa);

        Integer crt = crtDaEmpresa(idEmpresa);
        long pendenciasEmpresa = pendenciasEmpresa(idEmpresa).size();
        long pendenciasProdutos = contarProdutos(crt);
        long pendenciasPagamentos = contarPagamentos();
        long avisosClientes = contarClientes();

        boolean bloqueado = pendenciasEmpresa > 0 || pendenciasProdutos > 0 || pendenciasPagamentos > 0;
        long totalBloqueante = pendenciasEmpresa + pendenciasProdutos + pendenciasPagamentos;

        List<CategoriaConformidadeResponse> categorias = List.of(
                new CategoriaConformidadeResponse(CategoriaConformidade.EMPRESA, "Empresa", true, pendenciasEmpresa),
                new CategoriaConformidadeResponse(CategoriaConformidade.PRODUTOS, "Produtos", true, pendenciasProdutos),
                new CategoriaConformidadeResponse(CategoriaConformidade.PAGAMENTOS, "Formas de pagamento", true, pendenciasPagamentos),
                new CategoriaConformidadeResponse(CategoriaConformidade.CLIENTES, "Clientes", false, avisosClientes));

        String veredito = bloqueado
                ? "%d pendência%s bloqueiam a emissão".formatted(totalBloqueante, totalBloqueante == 1 ? "" : "s")
                : "Pronto para emitir";
        return new PainelConformidadeResponse(!bloqueado, veredito, categorias);
    }

    @Transactional(readOnly = true)
    public PaginaPendencias drillDown(Jwt jwt, long idEmpresa, CategoriaConformidade categoria,
                                      Integer pagina, Integer limite) {        exigirEmpresaDoTenant(idEmpresa);

        int tamanho = limite == null ? TAMANHO_PAGINA_PADRAO : Math.min(Math.max(limite, 1), TAMANHO_PAGINA_MAXIMO);
        int paginaAtual = pagina == null ? 1 : Math.max(pagina, 1);

        List<ItemPendenciaResponse> todos = switch (categoria) {
            case EMPRESA -> pendenciasEmpresa(idEmpresa);
            case PRODUTOS -> listarProdutos(crtDaEmpresa(idEmpresa));
            case PAGAMENTOS -> listarPagamentos();
            case CLIENTES -> listarClientes();
        };

        long totalItens = todos.size();
        int totalPaginas = totalItens == 0 ? 1 : (int) Math.ceil(totalItens / (double) tamanho);
        int inicio = Math.min((paginaAtual - 1) * tamanho, todos.size());
        int fim = Math.min(inicio + tamanho, todos.size());
        return new PaginaPendencias(todos.subList(inicio, fim), paginaAtual, tamanho, totalItens, totalPaginas);
    }

    // ---------------------------------------------------------------- Empresa

    private List<ItemPendenciaResponse> pendenciasEmpresa(long idEmpresa) {
        var linha = jdbc.sql("""
                        SELECT e.razao_social, e.cnpj, e.inscricao_estadual, e.codigo_municipio_ibge, e.cnae,
                               (SELECT count(*) FROM fiscal_config_empresa fc
                                 WHERE fc.id_tenant = e.id_tenant AND fc.id_empresa = e.id_empresa) AS tem_config,
                               (SELECT count(*) FROM fiscal_certificado cert
                                 WHERE cert.id_tenant = e.id_tenant AND cert.id_empresa = e.id_empresa
                                   AND cert.ativo AND cert.valido_ate > now()) AS certificados_validos,
                               (SELECT count(*) FROM fiscal_certificado cert
                                 WHERE cert.id_tenant = e.id_tenant AND cert.id_empresa = e.id_empresa
                                   AND cert.ativo AND cert.valido_ate > now()
                                   AND cert.cnpj_titular IS DISTINCT FROM e.cnpj) AS certificados_de_outro_cnpj
                        FROM empresa e
                        WHERE e.id_tenant = plataforma.tenant_atual() AND e.id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> new Object[]{
                        rs.getString("razao_social"), rs.getString("cnpj"), rs.getString("inscricao_estadual"),
                        rs.getObject("codigo_municipio_ibge"), rs.getString("cnae"),
                        rs.getLong("tem_config"), rs.getLong("certificados_validos"), rs.getLong("certificados_de_outro_cnpj")})
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Empresa não encontrada."));

        String razaoSocial = (String) linha[0];
        List<ItemPendenciaResponse> pendencias = new ArrayList<>();
        if ((long) linha[5] == 0L) {
            pendencias.add(item(idEmpresa, razaoSocial, "Nenhuma configuração fiscal cadastrada.", "fiscal.configuracao"));
        }
        if (vazio((String) linha[1])) {
            pendencias.add(item(idEmpresa, razaoSocial, "Empresa sem CNPJ.", "identidade.empresa"));
        }
        if (vazio((String) linha[2])) {
            pendencias.add(item(idEmpresa, razaoSocial, "Empresa sem Inscrição Estadual.", "identidade.empresa"));
        }
        if (linha[3] == null) {
            pendencias.add(item(idEmpresa, razaoSocial, "Empresa sem código de município IBGE.", "identidade.empresa"));
        }
        if (vazio((String) linha[4])) {
            pendencias.add(item(idEmpresa, razaoSocial, "Empresa sem CNAE.", "identidade.empresa"));
        }
        if ((long) linha[6] == 0L) {
            pendencias.add(item(idEmpresa, razaoSocial, "Nenhum certificado digital ativo e dentro da validade.", "fiscal.certificado"));
        } else if ((long) linha[7] > 0L) {
            pendencias.add(item(idEmpresa, razaoSocial, "O certificado ativo é de outro CNPJ.", "fiscal.certificado"));
        }
        return pendencias;
    }

    // ---------------------------------------------------------------- Produtos

    /** {@code crt == null} (empresa sem configuração fiscal) desliga só a checagem de "perfil
     *  sem regra" — sem CRT não há contra o que comparar; NCM e perfil ausente seguem valendo.
     *  {@code produto} não tem {@code id_empresa} — é compartilhado entre as empresas do tenant
     *  (V017), então a única variação por empresa aqui é o CRT. */
    private long contarProdutos(Integer crt) {
        return jdbc.sql("SELECT count(*) FROM produto p " + FILTRO_PRODUTOS_PROBLEMATICOS)
                .params(crt, crt)
                .query(Long.class).single();
    }

    private List<ItemPendenciaResponse> listarProdutos(Integer crt) {
        return jdbc.sql("""
                        SELECT p.id_produto, p.descricao,
                               concat_ws(', ',
                                   CASE WHEN p.codigo_ncm IS NULL THEN 'sem NCM' END,
                                   CASE WHEN p.id_perfil_fiscal IS NULL THEN 'sem perfil fiscal' END,
                                   CASE WHEN p.id_perfil_fiscal IS NOT NULL AND ?::int IS NOT NULL
                                             AND NOT EXISTS (
                                               SELECT 1 FROM cfg_perfil_fiscal_regra r
                                               WHERE r.id_tenant = p.id_tenant
                                                 AND r.id_perfil_fiscal = p.id_perfil_fiscal
                                                 AND r.crt = ?::int)
                                        THEN 'perfil sem regra para o CRT'
                                   END
                               ) AS problema
                        FROM produto p
                        """ + FILTRO_PRODUTOS_PROBLEMATICOS + """
                        ORDER BY p.descricao, p.id_produto
                        """)
                .params(crt, crt, crt, crt)
                .query(ConformidadeFiscalService::mapearItem)
                .list();
    }

    private static final String FILTRO_PRODUTOS_PROBLEMATICOS = """
            WHERE p.id_tenant = plataforma.tenant_atual() AND p.ativo
              -- ⛔ Serviço não tem NCM nem perfil fiscal de ICMS — a tributação dele é ISS, em
              -- NFS-e (V085, bloco S1). Sem este filtro, TODO serviço cadastrado viraria pendência
              -- permanente justamente na tela que existe para dizer que está tudo pronto para
              -- emitir — e pendência que não se resolve treina o operador a ignorar a tela.
              AND p.tipo_item = 'MERCADORIA'
              AND (p.codigo_ncm IS NULL OR p.id_perfil_fiscal IS NULL
                   OR (p.id_perfil_fiscal IS NOT NULL AND ?::int IS NOT NULL AND NOT EXISTS (
                         SELECT 1 FROM cfg_perfil_fiscal_regra r
                         WHERE r.id_tenant = p.id_tenant AND r.id_perfil_fiscal = p.id_perfil_fiscal
                           AND r.crt = ?::int)))
            """;

    // ---------------------------------------------------------------- Pagamentos

    private long contarPagamentos() {
        return jdbc.sql("SELECT count(*) FROM tipo_carteira tc " + FILTRO_PAGAMENTOS_PROBLEMATICOS)
                .query(Long.class).single();
    }

    private List<ItemPendenciaResponse> listarPagamentos() {
        return jdbc.sql("""
                        SELECT tc.id_carteira, tc.nome_carteira,
                               concat_ws(', ',
                                   CASE WHEN tc.codigo_tpag IS NULL THEN 'sem código de forma de pagamento (tPag)' END,
                                   CASE WHEN tc.categoria_carteira IN ('CARTAO_DEBITO', 'CARTAO_CREDITO')
                                             AND tc.codigo_bandeira IS NULL
                                        THEN 'sem bandeira do cartão' END,
                                   CASE WHEN tc.categoria_carteira IN ('CARTAO_DEBITO', 'CARTAO_CREDITO')
                                             AND tc.cnpj_credenciadora IS NULL
                                        THEN 'sem CNPJ da credenciadora' END
                               ) AS problema
                        FROM tipo_carteira tc
                        """ + FILTRO_PAGAMENTOS_PROBLEMATICOS + """
                        ORDER BY tc.nome_carteira, tc.id_carteira
                        """)
                .query(ConformidadeFiscalService::mapearItem)
                .list();
    }

    // ⚠️ cStat 391 real (2026-08-19): SEFAZ-PR rejeitou uma NFC-e com forma de pagamento em cartão
    // porque faltava o CNPJ da credenciadora — mesmo com tpIntegra=2 (maquininha não integrada) e
    // a bandeira preenchida, o grupo <card> incompleto não basta. Sem este item aqui, a
    // Conformidade Fiscal dizia "pronto pra emitir" com uma pendência real invisível.
    private static final String FILTRO_PAGAMENTOS_PROBLEMATICOS = """
            WHERE tc.id_tenant = plataforma.tenant_atual()
              AND (tc.codigo_tpag IS NULL
                   OR (tc.categoria_carteira IN ('CARTAO_DEBITO', 'CARTAO_CREDITO')
                       AND (tc.codigo_bandeira IS NULL OR tc.cnpj_credenciadora IS NULL)))
            """;

    // ---------------------------------------------------------------- Clientes (aviso)

    /** PJ com `indicador_ie = 9` (default) — pode ser não-contribuinte de verdade ou nunca ter
     *  sido revisado; o schema não distingue os dois (ver javadoc da classe). Nunca bloqueia. */
    private long contarClientes() {
        return jdbc.sql("SELECT count(*) FROM cliente c " + FILTRO_CLIENTES_AVISO)
                .query(Long.class).single();
    }

    private List<ItemPendenciaResponse> listarClientes() {
        return jdbc.sql("""
                        SELECT c.id_cliente, c.nome,
                               'pessoa jurídica sem indicador de IE revisado' AS problema
                        FROM cliente c
                        """ + FILTRO_CLIENTES_AVISO + """
                        ORDER BY c.nome, c.id_cliente
                        """)
                .query(ConformidadeFiscalService::mapearItem)
                .list();
    }

    private static final String FILTRO_CLIENTES_AVISO = """
            WHERE c.id_tenant = plataforma.tenant_atual() AND c.ativo
              AND NOT c.fisica_juridica AND c.indicador_ie = 9
            """;

    // ---------------------------------------------------------------- auxiliares

    private Integer crtDaEmpresa(long idEmpresa) {
        return jdbc.sql("""
                        SELECT crt FROM fiscal_config_empresa
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        """)
                .param(idEmpresa).query(Integer.class).optional().orElse(null);
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

    private static ItemPendenciaResponse item(long idRegistro, String identificador, String problema, String tela) {
        return new ItemPendenciaResponse(idRegistro, identificador, problema, tela);
    }

    private static ItemPendenciaResponse mapearItem(ResultSet rs, int rowNum) throws SQLException {
        return new ItemPendenciaResponse(rs.getLong(1), rs.getString(2), rs.getString("problema"), telaPor(rs));
    }

    /** {@code produto}/{@code tipo_carteira}/{@code cliente} já têm tela própria no produto; a
     *  distinção é só pelo nome da coluna de identificador que a query selecionou. */
    private static String telaPor(ResultSet rs) throws SQLException {
        String coluna = rs.getMetaData().getColumnLabel(1);
        return switch (coluna) {
            case "id_produto" -> "catalogo.produto";
            case "id_carteira" -> "financeiro.tipocarteira";
            case "id_cliente" -> "cadastros.cliente";
            default -> null;
        };
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

}
