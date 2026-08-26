package com.vetor.niner.integracao.mercadolivre;

import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import com.vetor.niner.canais.ControleEstoqueCanalGuard;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.CredenciaisCanalRepositorio;
import com.vetor.niner.canais.EstadoOAuthRepositorio;
import com.vetor.niner.canais.EstadoOAuthRepositorio.EstadoConsumido;
import com.vetor.niner.canais.TipoCanal;
import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OAuth do Mercado Livre (bloco M1) — conectar a conta do lojista ao canal do ERP.
 *
 * <h2>O desenho em duas metades, e por que as superfícies são diferentes</h2>
 *
 * <ol>
 *   <li><b>Começar</b> ({@link #urlDeAutorizacao}) roda em {@code /api/v1}, <b>autenticado</b>. É
 *       aqui que se sabe <i>quem</i> está conectando — e é justamente isso que precisa ficar
 *       gravado no {@code state}.</li>
 *   <li><b>Concluir</b> ({@link #concluir}) roda em {@code /api/publico}, <b>anônimo</b>: quem
 *       chega é o navegador do lojista devolvido pelo Mercado Livre, sem JWT nenhum.</li>
 * </ol>
 *
 * <p>⚠️ {@code docs/MODULOMARKETPLACE.md} §9 previa o início também em {@code /api/publico}. Não
 * dá: um endpoint anônimo não tem como saber de que loja é a autorização, e a URI de redirect não
 * aceita parte variável para dizer. Ou o tenant vem do JWT no início, ou viria da URL — e a URL é
 * escolhida por quem chama. A doc foi corrigida junto com este código.
 *
 * <h2>⛔ O erro que este bloco existe para não cometer</h2>
 *
 * Conectar a conta de Mercado Livre de um lojista dentro do tenant de outro (P8). As defesas, em
 * camadas: o {@code state} é imprevisível, de uso único e de vida curta ({@link
 * EstadoOAuthRepositorio}); o canal é conferido como sendo do tenant do chamador <b>antes</b> de
 * o {@code state} nascer; e a gravação filtra {@code id_tenant} no texto do SQL, recusando quando
 * não casa uma linha.
 */
@Service
public class MercadoLivreOAuthService {

    private static final Logger log = LoggerFactory.getLogger(MercadoLivreOAuthService.class);

    private final JdbcClient jdbc;
    private final EstadoOAuthRepositorio estados;
    private final CredenciaisCanalRepositorio credenciais;
    private final ControleEstoqueCanalGuard guardaEstoque;
    private final MercadoLivreOAuth oauth;
    private final NinerProperties.MercadoLivre config;

    public MercadoLivreOAuthService(JdbcClient jdbc, EstadoOAuthRepositorio estados,
                                    CredenciaisCanalRepositorio credenciais,
                                    ControleEstoqueCanalGuard guardaEstoque,
                                    MercadoLivreOAuth oauth, NinerProperties props) {
        this.jdbc = jdbc;
        this.estados = estados;
        this.credenciais = credenciais;
        this.guardaEstoque = guardaEstoque;
        this.oauth = oauth;
        this.config = props.canais().mercadolivre();
    }

    /** O desfecho de uma volta do OAuth, do jeito que o controller precisa para redirecionar. */
    public record Desfecho(boolean sucesso, long idCanal, String mensagem) {
    }

    // ------------------------------------------------------------------ 1. começar (autenticado)

    /**
     * Monta a URL do consentimento do Mercado Livre para este canal.
     *
     * <p>⚠️ O {@code state} nasce <b>depois</b> de conferir que o canal é deste tenant e é de
     * Mercado Livre. Gerá-lo antes criaria um vínculo válido para um canal que o chamador não
     * pode tocar.
     */
    @Transactional
    public String urlDeAutorizacao(Jwt jwt, long idCanal) {
        exigirAdmin(jwt);
        exigirConfigurado();
        // Guarda 1 (§8.1): marketplace exige controle de estoque. Recusar aqui evita mandar o
        // lojista para a tela do ML e trazê-lo de volta para ouvir "não".
        guardaEstoque.exigirControleDeEstoqueLigado();

        String tipo = jdbc.sql("""
                        SELECT tipo::text FROM canal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_canal = ?
                        """)
                .params(idCanal)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Canal não encontrado."));

        if (!TipoCanal.MERCADO_LIVRE.name().equals(tipo)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este canal não é do Mercado Livre.");
        }

        long idTenant = jwt.getClaim("tid") instanceof Number n ? n.longValue() : TenantContext.idTenantAtual();
        long idUsuario = Long.parseLong(jwt.getSubject());
        String estado = estados.criar(idTenant, idCanal, idUsuario);

        return "%s/authorization?response_type=code&client_id=%s&redirect_uri=%s&state=%s".formatted(
                semBarraFinal(config.authUrl()),
                url(config.clientId()),
                url(config.redirectUri()),
                url(estado));
    }

    // -------------------------------------------------------------- 2. concluir (anônimo, sem JWT)

    /**
     * Recebe a volta do Mercado Livre: valida o {@code state}, troca o {@code code} por token e
     * grava a credencial cifrada.
     *
     * <p>⚠️ <b>Não é {@code @Transactional}.</b> No meio dele há uma chamada HTTP a terceiro, que
     * pode demorar dezenas de segundos — segurar uma transação (e uma conexão do pool) aberta
     * durante isso é o defeito de {@code CobrancaService.iniciarPagamento} apontado na auditoria
     * de 2026-08-21. Cada passo abre a sua.
     */
    public Desfecho concluir(String code, String estado, String erroDoMl) {
        // O lojista clicou "cancelar" na tela do ML: não é falha nossa, e a mensagem tem de dizer
        // isso — senão ele vai procurar problema em rede, servidor e credencial.
        if (erroDoMl != null && !erroDoMl.isBlank()) {
            return new Desfecho(false, 0, "Autorização cancelada no Mercado Livre.");
        }
        if (code == null || code.isBlank()) {
            return new Desfecho(false, 0, "O Mercado Livre não devolveu o código de autorização.");
        }

        EstadoConsumido consumido = estados.consumir(estado).orElse(null);
        if (consumido == null) {
            // ⚠️ Mensagem única para inexistente/expirado/reutilizado — ver o javadoc de consumir().
            log.warn("Retorno do Mercado Livre com state inválido, expirado ou já usado.");
            return new Desfecho(false, 0,
                    "Este pedido de conexão não vale mais. Clique em \"Conectar\" novamente.");
        }

        MercadoLivreOAuth.Tokens tokens;
        try {
            tokens = oauth.trocarCodigo(code);
        } catch (MercadoLivreOAuth.AutorizacaoInvalidaException e) {
            log.warn("Troca de código por token recusada pelo Mercado Livre: {}", e.getMessage());
            return new Desfecho(false, consumido.idCanal(),
                    "O Mercado Livre recusou a autorização. Tente conectar novamente.");
        } catch (CanalIndisponivelException e) {
            log.warn("Mercado Livre indisponível ao trocar código por token: {}", e.getMessage());
            return new Desfecho(false, consumido.idCanal(),
                    "O Mercado Livre não respondeu agora. Tente conectar novamente em alguns minutos.");
        }

        // ⭐ Daqui para baixo é dado de tenant: sem entrar no contexto, o UPDATE não casa linha
        // nenhuma e a conexão "daria certo" sem gravar nada (P8).
        return TenantContext.comTenant(consumido.idTenant(), () -> {
            // Guarda 1 de novo, e não é redundância: entre pedir a autorização e voltar dela, o
            // lojista pode ter religado "permite estoque negativo" em outra aba. Conferir só na
            // porta da frente é a trava decorativa da §8.1.
            guardaEstoque.exigirControleDeEstoqueLigado();

            boolean gravou = credenciais.salvar(consumido.idCanal(),
                    new CredenciaisCanal(consumido.idCanal(), tokens.contaExterna(),
                            tokens.accessToken(), tokens.refreshToken(), tokens.expiraEm()));

            if (!gravou) {
                // Canal apagado no meio do caminho — ou, o que importa de verdade, um `state`
                // apontando para canal que não é deste tenant. Nos dois casos: não grava.
                log.error("Retorno do Mercado Livre não gravou credencial: canal {} não pertence ao tenant {}.",
                        consumido.idCanal(), consumido.idTenant());
                return new Desfecho(false, consumido.idCanal(),
                        "Canal não encontrado. Verifique se ele ainda existe e conecte novamente.");
            }
            log.info("Canal {} do tenant {} conectado ao Mercado Livre (vendedor {}).",
                    consumido.idCanal(), consumido.idTenant(), tokens.contaExterna());
            return new Desfecho(true, consumido.idCanal(), "Canal conectado ao Mercado Livre.");
        });
    }

    // ------------------------------------------------------------------------- 3. renovar o token

    /**
     * Renova o {@code access_token} de um canal. Chamado pelo job de renovação, já dentro do
     * {@code TenantContext}.
     *
     * @return {@code true} se renovou
     */
    public boolean renovar(CredenciaisCanal atual) {
        try {
            MercadoLivreOAuth.Tokens novos = oauth.renovar(atual.refreshToken());
            // ⚠️ O ML pode devolver a resposta sem `refresh_token` novo — nesse caso o antigo
            // continua valendo. Gravar null ali apagaria a única chave de renovação e obrigaria o
            // lojista a reautorizar no dia seguinte, sem motivo aparente.
            String refresh = novos.refreshToken() != null ? novos.refreshToken() : atual.refreshToken();
            credenciais.salvar(atual.idCanal(),
                    new CredenciaisCanal(atual.idCanal(),
                            novos.contaExterna() != null ? novos.contaExterna() : atual.contaExterna(),
                            novos.accessToken(), refresh, novos.expiraEm()));
            return true;
        } catch (MercadoLivreOAuth.AutorizacaoInvalidaException e) {
            // Definitivo: o lojista revogou, ou o refresh_token morreu. Insistir de dez em dez
            // minutos para sempre esconderia o problema — marca e para, o painel mostra.
            credenciais.marcarErro(atual.idCanal(),
                    "Autorização do Mercado Livre expirou ou foi revogada. Conecte o canal novamente.");
            log.warn("Canal {}: renovação recusada em definitivo — marcado ERRO. {}",
                    atual.idCanal(), e.getMessage());
            return false;
        }
        // ⚠️ CanalIndisponivelException NÃO é capturada de propósito: é transitória, e quem chama
        // (o job) já trata como "tenta na próxima rodada". Marcar ERRO por uma falha de rede
        // desconectaria o lojista por causa de um soluço de dez segundos.
    }

    // --------------------------------------------------------------------------------- auxiliares

    /** Sem credencial não há integração — e o ERP sobe igual. Mesmo desenho da cobrança. */
    private void exigirConfigurado() {
        if (!config.configurado()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A integração com o Mercado Livre não está configurada neste servidor.");
        }
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem conectar canais de venda.");
        }
    }

    private static String semBarraFinal(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String url(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }
}
