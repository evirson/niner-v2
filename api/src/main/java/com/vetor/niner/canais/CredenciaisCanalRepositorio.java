package com.vetor.niner.canais;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vetor.niner.comum.seguranca.SegredoCifrador;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Leitura e gravação de {@code canal.credenciais} — o token do lojista no marketplace.
 *
 * <h2>⚠️ O que é cifrado e o que não é</h2>
 *
 * O JSONB é um objeto <b>em claro</b> cujos campos secretos são cifrados <b>um a um</b>
 * (AES-256-GCM, {@link SegredoCifrador}, chave fora do banco — ADR-005). Não é o blob inteiro:
 * {@code contaExterna} e {@code expiraEm} precisam ser legíveis por SQL. O painel de canais lê
 * {@code credenciais ->> 'contaExterna'} direto na consulta de listagem, e o job de renovação
 * filtra por {@code expiraEm} <b>no banco</b> — cifrar os dois obrigaria a decifrar todo canal de
 * todo tenant a cada rodada só para descobrir quais vencem.
 *
 * <p>Nenhum dos dois é segredo: {@code contaExterna} é o {@code user_id} público do vendedor no
 * ML, e a data de vencimento não abre porta nenhuma. O que abre — {@code accessToken} e
 * {@code refreshToken} — está cifrado.
 *
 * <h2>⚠️ Todo método é {@code @Transactional}, e isso NÃO é decoração</h2>
 *
 * Sem transação o {@code TenantAwareTransactionManager} nunca executa o
 * {@code SET LOCAL app.id_tenant}, {@code plataforma.tenant_atual()} vale NULL e o {@code SELECT}
 * casa zero linha — devolvendo {@code Optional.empty()}, que quase sempre é lido como "não
 * existe, pode seguir". Foi assim que o cancelamento da devolução ao fornecedor devolveu estoque
 * sem avisar a SEFAZ, com resposta 200 (2026-08-21). Ao copiar um método daqui, <b>a anotação é
 * parte do que se copia</b>.
 *
 * <p>E toda consulta filtra {@code id_tenant} <b>no texto do SQL</b>, não só pela política de RLS
 * (P8) — ver {@code docs/infra/isolamento-tenant-rls.md}.
 */
@Repository
public class CredenciaisCanalRepositorio {

    /**
     * ⚠️ Mapper próprio, <b>não injetado</b>: esta aplicação não expõe um bean de
     * {@code ObjectMapper} — injetá-lo derruba o contexto na subida com "No qualifying bean of
     * type ObjectMapper". Mesmo padrão de {@code OutboxRepositorio} e
     * {@code CobrancaWebhookProcessador}. Aqui ele só lê e escreve um JSON nosso de ponta a
     * ponta, que não precisa da configuração do Spring.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final SegredoCifrador cifrador;

    public CredenciaisCanalRepositorio(JdbcClient jdbc, SegredoCifrador cifrador) {
        this.jdbc = jdbc;
        this.cifrador = cifrador;
    }

    /**
     * Grava a credencial e marca o canal como {@code CONECTADO}.
     *
     * <p>É o mesmo caminho da primeira conexão e da renovação de token — de propósito. Duas
     * rotinas de gravação divergiriam no dia em que uma passasse a guardar um campo novo.
     *
     * @return {@code false} quando o canal não é deste tenant (ou não existe). ⚠️ O chamador
     *         <b>tem</b> que tratar: no retorno do OAuth, "não atualizou nada" é o sintoma de uma
     *         tentativa de conectar canal de outro tenant, e engolir isso é exatamente o defeito
     *         que a V065 existe para impedir
     */
    @Transactional
    public boolean salvar(long idCanal, CredenciaisCanal cred) {
        ObjectNode no = JSON.createObjectNode();
        no.put("contaExterna", cred.contaExterna());
        no.put("accessToken", cifrador.cifrar(cred.accessToken()));
        no.put("refreshToken", cred.refreshToken() == null ? null : cifrador.cifrar(cred.refreshToken()));
        no.put("expiraEm", cred.expiraEm() == null ? null : cred.expiraEm().toString());

        boolean gravou = jdbc.sql("""
                        UPDATE canal
                           SET credenciais = CAST(? AS jsonb), status = 'CONECTADO',
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(no.toString(), idCanal)
                .update() == 1;

        if (gravou && cred.contaExterna() != null) {
            mapearContaExterna(idCanal, cred.contaExterna());
        }
        return gravou;
    }

    /**
     * Publica o par (marketplace, vendedor) no mapa global — é por ele que um webhook anônimo
     * descobre de quem é a notificação (V068).
     *
     * <p>⛔ <b>Recusa quando aquela conta já está conectada em outro canal.</b> A mesma conta de
     * Mercado Livre em dois lugares tornaria toda notificação de venda ambígua, e o desempate
     * seria um palpite que erra metade das vezes — importando o pedido de um lojista dentro da
     * loja de outro (P8). Melhor recusar a conexão, com uma frase que diz o que houve.
     */
    private void mapearContaExterna(long idCanal, String contaExterna) {
        int linhas = jdbc.sql("""
                        INSERT INTO plataforma.canal_externo (tipo, conta_externa, id_tenant, id_canal)
                        SELECT c.tipo::text, ?, c.id_tenant, c.id_canal
                          FROM canal c
                         WHERE c.id_tenant = plataforma.tenant_atual() AND c.id_canal = ?
                        ON CONFLICT (tipo, conta_externa) DO UPDATE
                           SET id_tenant = EXCLUDED.id_tenant, id_canal = EXCLUDED.id_canal,
                               atualizado_em = now()
                         WHERE plataforma.canal_externo.id_tenant = EXCLUDED.id_tenant
                           AND plataforma.canal_externo.id_canal  = EXCLUDED.id_canal
                        """)
                .params(contaExterna, idCanal)
                .update();

        if (linhas == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Esta conta do marketplace já está conectada a outro canal. "
                            + "Desconecte-a lá antes de conectá-la aqui — a mesma conta em dois "
                            + "lugares tornaria cada venda ambígua.");
        }
    }

    /**
     * Tira o canal do mapa global.
     *
     * <p>⚠️ Chamado ao desconectar e ao excluir. Deixar a linha para trás faria notificações de
     * uma conta já desconectada continuarem sendo aceitas — e, pior, impediria o lojista de
     * reconectar aquela conta em outro canal, com uma mensagem que não faria sentido para ele.
     */
    @Transactional
    public void esquecerContaExterna(long idCanal) {
        jdbc.sql("""
                        DELETE FROM plataforma.canal_externo
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(idCanal)
                .update();
    }

    /**
     * De quem é esta conta do marketplace? A pergunta que o webhook anônimo faz (V068).
     *
     * <p>⚠️ Roda <b>sem</b> {@code TenantContext}, de propósito — é justamente o que ela descobre.
     * Por isso a tabela é global e sem RLS.
     */
    @Transactional(readOnly = true)
    public Optional<DonoDaConta> donoDaConta(String tipo, String contaExterna) {
        return jdbc.sql("""
                        SELECT id_tenant, id_canal FROM plataforma.canal_externo
                         WHERE tipo = ? AND conta_externa = ?
                        """)
                .params(tipo, contaExterna)
                .query((rs, n) -> new DonoDaConta(rs.getLong("id_tenant"), rs.getLong("id_canal")))
                .optional();
    }

    /** A quem pertence uma conta de marketplace. */
    public record DonoDaConta(long idTenant, long idCanal) {
    }

    /** A credencial decifrada de um canal, ou vazio se ele não tem (nunca conectou/desconectou). */
    @Transactional(readOnly = true)
    public Optional<CredenciaisCanal> carregar(long idCanal) {
        return jdbc.sql("""
                        SELECT id_canal, credenciais
                          FROM canal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                           AND credenciais IS NOT NULL
                        """)
                .params(idCanal)
                .query((rs, n) -> ler(rs.getLong("id_canal"), rs.getString("credenciais")))
                .optional();
    }

    /**
     * Canais conectados cujo token vence dentro da folga — a fila do job de renovação.
     *
     * <p>⚠️ Só traz quem tem {@code refreshToken}: sem ele não há o que renovar, e o lojista
     * precisa autorizar de novo. Incluí-lo aqui faria o job tentar, falhar e marcar erro a cada
     * rodada, escondendo o canal que <b>realmente</b> quebrou atrás de ruído.
     */
    @Transactional(readOnly = true)
    public List<CredenciaisCanal> aRenovar(Instant agora, Duration folga) {
        return jdbc.sql("""
                        SELECT id_canal, credenciais
                          FROM canal
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND status = 'CONECTADO'
                           AND credenciais IS NOT NULL
                           AND credenciais ->> 'refreshToken' IS NOT NULL
                           AND (credenciais ->> 'expiraEm')::timestamptz <= ?
                         ORDER BY id_canal
                        """)
                .params(java.sql.Timestamp.from(agora.plus(folga)))
                .query((rs, n) -> ler(rs.getLong("id_canal"), rs.getString("credenciais")))
                .list();
    }

    /**
     * Marca o canal com problema, preservando a credencial.
     *
     * <p>⚠️ <b>Não apaga o token</b>, ao contrário de "Desconectar". A diferença importa: aqui
     * quem desistiu foi o canal (autorização revogada, token inválido), e apagar a credencial
     * jogaria fora a única pista de qual conta estava conectada. {@code ERRO} já basta para parar
     * a sincronização — e é o painel de saúde que mostra o motivo.
     */
    @Transactional
    public void marcarErro(long idCanal, String motivo) {
        jdbc.sql("""
                        UPDATE canal
                           SET status = 'ERRO',
                               config = COALESCE(config, '{}'::jsonb)
                                        || jsonb_build_object('ultimoErro', CAST(? AS text)),
                               atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(motivo, idCanal)
                .update();
    }

    private CredenciaisCanal ler(long idCanal, String jsonbEmTexto) {
        try {
            JsonNode no = JSON.readTree(jsonbEmTexto);
            String expira = texto(no, "expiraEm");
            return new CredenciaisCanal(
                    idCanal,
                    texto(no, "contaExterna"),
                    decifrar(texto(no, "accessToken")),
                    decifrar(texto(no, "refreshToken")),
                    expira == null ? null : Instant.parse(expira));
        } catch (Exception e) {
            // ⚠️ Não imprime o conteúdo: ele é (parcialmente) segredo. O id do canal basta para
            // achar a linha; o resto se investiga no banco, não no arquivo de log.
            throw new IllegalStateException(
                    "Credencial do canal %d ilegível — reconecte o canal.".formatted(idCanal), e);
        }
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String decifrar(String cifrado) {
        return cifrado == null ? null : cifrador.decifrar(cifrado);
    }
}
