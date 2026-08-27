package com.vetor.niner;

import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Concede a grade cheia a um operador, em teste.
 *
 * <p><b>Por que passou a ser necessário (2026-08-27).</b> Com o RBAC ligado, operador nasce
 * <b>sem acesso a nada</b> — é a regra do produto. Os testes que criam um operador para exercitar
 * outra coisa (caixa próprio, empresa liberada, filtro por empresa) começaram a receber 403 antes
 * de chegar ao que queriam testar. Conceder tudo aqui restaura a intenção original: o teste volta
 * a falar sobre a regra que ele nasceu para verificar, não sobre permissão.
 *
 * <p>⚠️ <b>Isto não enfraquece o RBAC nos testes.</b> Quem verifica a trava é
 * {@code PermissaoPorTelaTest}, que trabalha justamente com operador SEM grade. Se alguém apagar a
 * trava, aquele teste reprova — este aqui não esconde nada, só evita que 14 testes de outros
 * assuntos virassem testes de permissão por acidente.
 */
final class PermissaoDeTeste {

    private PermissaoDeTeste() {
    }

    /** Libera todas as telas (com todas as ações que cada uma tem) para {@code idUsuario}. */
    static void liberarTudo(MockMvc mvc, String tokenAdmin, long idUsuario) throws Exception {
        String telas = mvc.perform(get("/api/v1/telas").header("Authorization", "Bearer " + tokenAdmin))
                .andReturn().getResponse().getContentAsString();

        // ⚠️ Filtro em Java, não no JsonPath: `$[?(@.adminApenas == false)]` devolvia lista vazia,
        // o PUT respondia 200 com corpo `[]` e NADA era concedido — o teste então falhava lá na
        // frente, com um 403 que parecia bug do RBAC.
        List<java.util.Map<String, Object>> todas = JsonPath.read(telas, "$");
        List<String> chaves = todas.stream()
                .filter(t -> !Boolean.TRUE.equals(t.get("adminApenas")))
                .map(t -> (String) t.get("chave"))
                .toList();
        String corpo = chaves.stream()
                .map(c -> """
                        {"chaveTela":"%s","acessar":true,"incluir":true,"alterar":true,"excluir":true}""".formatted(c))
                .collect(Collectors.joining(",", "[", "]"));

        // ⚠️ Exige 200: sem isto, um PUT recusado não concederia nada e o teste falharia lá na
        // frente com "403 onde era esperado 404" — apontando para a regra errada.
        mvc.perform(put("/api/v1/usuarios/" + idUsuario + "/permissoes")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(APPLICATION_JSON).content(corpo))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /** Mesma coisa, quando o teste tem o e-mail do operador em vez do id. */
    static void liberarTudoPorEmail(MockMvc mvc, String tokenAdmin, String email) throws Exception {
        String lista = mvc.perform(get("/api/v1/usuarios?nome=&status=TODOS&pagina=1&limite=200")
                        .header("Authorization", "Bearer " + tokenAdmin))
                .andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(lista,
                "$.itens[?(@.email == '" + email.toLowerCase() + "')].idUsuario");
        if (ids.isEmpty()) {
            throw new AssertionError("usuário não encontrado para liberar permissões: " + email);
        }
        liberarTudo(mvc, tokenAdmin, ids.get(0));
    }
}
