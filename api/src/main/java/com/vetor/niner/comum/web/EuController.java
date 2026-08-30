package com.vetor.niner.comum.web;

import com.vetor.niner.identidade.usuario.HorarioAcessoService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Quem sou eu" do tenant logado (§3.4). Prova o primeiro uso pós-signup: com o token
 * do trial, o cliente já enxerga a própria conta. A leitura de {@code usuario} passa
 * pelo RLS (V024) — o contexto vem do claim {@code tid} via TenantAwareTransactionManager.
 */
@RestController
public class EuController {

    private final JdbcClient jdbc;
    private final HorarioAcessoService horarioAcesso;

    public EuController(JdbcClient jdbc, HorarioAcessoService horarioAcesso) {
        this.jdbc = jdbc;
        this.horarioAcesso = horarioAcesso;
    }

    @GetMapping("/api/v1/eu")
    @Transactional(readOnly = true)
    public Map<String, Object> eu(@AuthenticationPrincipal Jwt jwt) {
        long idTenant = ((Number) jwt.getClaim("tid")).longValue();
        long idUsuario = Long.parseLong(jwt.getSubject());
        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();

        var usuario = jdbc.sql(
                        "SELECT id_usuario, nome_usuario, email, administrador FROM usuario WHERE id_tenant = ? AND id_usuario = ?")
                .params(idTenant, idUsuario)
                .query((rs, n) -> Map.<String, Object>of(
                        "idUsuario", rs.getLong("id_usuario"),
                        "nome", rs.getString("nome_usuario"),
                        "email", rs.getString("email"),
                        "papel", rs.getBoolean("administrador") ? "ADMIN" : "OPERADOR"))
                .single();

        var conta = jdbc.sql("SELECT nome_conta, slug, status::text AS status FROM plataforma.tenant WHERE id_tenant = ?")
                .param(idTenant)
                .query((rs, n) -> Map.<String, Object>of(
                        "nomeConta", rs.getString("nome_conta"),
                        "slug", rs.getString("slug"),
                        "status", rs.getString("status")))
                .single();

        // Empresa ativa da sessão (claim eid, 2026-07-28) — escolhida no login quando o
        // usuário tem acesso a mais de uma (usuario_empresa). Exibida no header do front pra
        // deixar claro em qual empresa os cadastros feitos nesta sessão vão cair.
        // ⚠️ `nomeEtiqueta` (2026-08-21) é o texto que sai IMPRESSO no campo "Nome da Empresa" da
        // etiqueta de produto — até esta data o front mandava o literal "NOME DA LOJA" para o papel,
        // na Emissão de verdade e não só na prévia.
        //
        // ⚠️ E NÃO sai de `empresa.cfg_nome_etiqueta`, apesar do nome da coluna: aquilo é herança do
        // ERP legado, onde a etiqueta era um MODELO de texto com marcadores, e o signup ainda o
        // semeia assim ("{sku}\n{descricao}\n{preco_venda}"). Nosso editor posiciona campos, não
        // interpreta marcador — imprimir o conteúdo daquela coluna colocaria "{sku}" no adesivo.
        // O nome da empresa é o nome fantasia (ou a razão social), o mesmo que o ERP mostra no
        // cabeçalho. É a empresa da SESSÃO (claim eid): a Emissão usa empresa só como filtro de
        // estoque, não como emitente da etiqueta.
        var empresa = jdbc.sql("""
                        SELECT id_empresa, COALESCE(nome_fantasia, razao_social) AS nome
                          FROM empresa
                         WHERE id_tenant = ? AND id_empresa = ?
                        """)
                .params(idTenant, idEmpresa)
                .query((rs, n) -> Map.<String, Object>of(
                        "idEmpresa", rs.getLong("id_empresa"),
                        "nome", rs.getString("nome"),
                        "nomeEtiqueta", rs.getString("nome")))
                .single();

        // Plano e cota do mês, no lugar da antiga data de expiração do trial: desde o ADR-015 a
        // conta gratuita NÃO expira — o que a limita é volume de vendas. Vai no /eu (e não só em
        // Minha Conta) porque o painel do lojista precisa disso e é lido por qualquer papel,
        // enquanto Minha Conta é ADMIN-only.
        Map<String, Object> plano = jdbc.sql("""
                        SELECT p.nome, p.gratuito, p.limite_vendas_mes,
                               CASE WHEN u.competencia_vendas = date_trunc('month', now() AT TIME ZONE 'America/Sao_Paulo')::date
                                    THEN u.qtd_vendas_mes ELSE 0 END AS vendas_no_mes
                          FROM plataforma.assinatura a
                          JOIN plataforma.plano p       ON p.id_plano = a.id_plano
                          LEFT JOIN plataforma.uso_tenant u ON u.id_tenant = a.id_tenant
                         WHERE a.id_tenant = ? AND a.status <> 'CANCELADA'
                         ORDER BY a.id_assinatura DESC LIMIT 1
                        """)
                .param(idTenant)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nome", rs.getString("nome"));
                    m.put("gratuito", rs.getBoolean("gratuito"));
                    m.put("limite_vendas_mes", rs.getObject("limite_vendas_mes"));
                    m.put("vendas_no_mes", rs.getInt("vendas_no_mes"));
                    return m;
                })
                .optional().orElse(null);

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("id_tenant", idTenant);
        corpo.put("conta", conta);
        corpo.put("usuario", usuario);
        corpo.put("empresa", empresa);
        corpo.put("plano", plano);
        // Contagem regressiva do aviso visual de horário de acesso (HorarioAcessoGuard,
        // 2026-08-11) — null na imensa maioria das chamadas (ADMIN, controle desligado, ou
        // dentro do horário normal); só vem preenchido nos minutos finais de tolerância antes
        // do bloqueio de verdade (HorarioAcessoFilter, mesma janela de graça).
        // ⛔ A empresa da SESSÃO também aqui (auditoria 2026-08-29, rodada 5). A rodada 3 levou o
        // `eid` até o filtro e **esta ponta ficou para trás**, calculando no fuso do cadastro — e o
        // javadoc de `segundosRestantesTolerancia` EXIGE que as duas contas concordem, porque é
        // disso que depende o aviso bater com o bloqueio. Operador com cadastro na matriz (SP)
        // operando na filial de Manaus via o anel zerar às 17:30 sobre um servidor que estava
        // deixando passar; na direção inversa (Acre), sobrava tempo no anel depois do 403.
        corpo.put("segundosRestantesTolerancia",
                horarioAcesso.segundosRestantesTolerancia(
                        idUsuario, HorarioAcessoService.TOLERANCIA_MINUTOS_PADRAO, idEmpresa));
        return corpo;
    }
}
