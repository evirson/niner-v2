package com.vetor.niner.comum.seguranca;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Qual empresa a requisição pode tocar.
 *
 * <p>⛔ <b>Quem não é administrador opera só a empresa em que entrou</b> (decisão do dono do
 * produto, 2026-08-27, depois da auditoria de segurança). É a promessa que a tela de Usuários faz
 * ao pedir "empresas com acesso", e o que as rotas de dinheiro — PDV, caixa, devolução, orçamento,
 * recebimento — já cumpriam usando o claim {@code eid}.
 *
 * <p><b>O que estava aberto:</b> o módulo fiscal inteiro recebia {@code idEmpresa} por path ou
 * query e não conferia nada. Um operador da filial 1, com a tela concedida, punha a <b>filial 2</b>
 * em contingência, inutilizava numeração dela ou baixava o ZIP com todo o XML fiscal dela. O
 * isolamento entre <b>contas</b> (P8) seguia intacto — o que se atravessava era a fronteira entre
 * <b>empresas da mesma conta</b>, que é justamente o que "empresas com acesso" existe para
 * governar.
 *
 * <p>⚠️ <b>Isto não substitui o filtro de {@code id_tenant} nas queries</b> (P8): são camadas
 * diferentes. Esta responde "de qual empresa?", aquela responde "de qual conta?".
 */
public final class EmpresaDaSessao {

    private EmpresaDaSessao() {
    }

    /**
     * Recusa quando um não-administrador pede empresa diferente da sessão dele.
     *
     * <p>⚠️ <b>403 e não 404 aqui</b>, ao contrário do cadastro do administrador: a existência da
     * outra empresa não é segredo nenhum (ela aparece no seletor de empresas do login para quem tem
     * acesso), e a pessoa precisa entender que o caminho é trocar de empresa, não que a filial
     * sumiu.
     */
    public static void exigirAcesso(Jwt jwt, long idEmpresaPedida) {
        if (ehAdmin(jwt)) {
            return;
        }
        long daSessao = idEmpresaDaSessao(jwt);
        if (daSessao != idEmpresaPedida) {
            throw new ResponseStatusException(FORBIDDEN,
                    "Você está operando outra empresa nesta sessão. Saia e entre novamente escolhendo "
                            + "a empresa desejada.");
        }
    }

    /** A empresa da sessão (claim {@code eid}) — a que o usuário escolheu ao entrar. */
    public static long idEmpresaDaSessao(Jwt jwt) {
        Object eid = jwt.getClaim("eid");
        if (eid == null) {
            throw new ResponseStatusException(FORBIDDEN, "Sessão sem empresa. Entre novamente.");
        }
        return ((Number) eid).longValue();
    }

    public static boolean ehAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }
}
