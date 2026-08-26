package com.vetor.niner.canais;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * O parâmetro {@code state} do OAuth de canal — anti-CSRF <b>e</b> o vínculo tenant/canal que a
 * URI de redirect não pode carregar (V065, {@code docs/MODULOMARKETPLACE.md} §2.1).
 *
 * <h2>⭐ Por que esta classe existe</h2>
 *
 * O Mercado Livre exige que a {@code redirect_uri} bata caractere por caractere e <b>não aceita
 * parte variável</b>. Então não há como escrever "…/retorno/{idTenant}". Quando o navegador do
 * lojista volta do consentimento, ele chega <b>sem JWT</b>, e a API precisa descobrir de quem é
 * aquela autorização. É esta linha que responde.
 *
 * <p>Errar isso é a falha de isolamento (P8) mais direta que a integração tem: conectar a conta
 * de Mercado Livre de um lojista dentro do tenant de outro. Por isso o {@code state} é
 * <b>imprevisível</b> (32 bytes de {@link SecureRandom}), <b>de uso único</b> e de <b>validade
 * curta</b> — as três coisas, não uma.
 *
 * <h2>⚠️ Guarda o HASH, nunca o valor</h2>
 *
 * Mesma regra de {@code plataforma.recuperacao_senha}: quem lê o banco não consegue reconstruir
 * um {@code state} válido e sequestrar uma autorização em curso.
 *
 * <h2>⚠️ Mora em {@code plataforma}, sem RLS — de propósito</h2>
 *
 * O endpoint de retorno roda <b>sem</b> {@code TenantContext}: é ele que vai <i>descobrir</i> o
 * tenant. Sob RLS, esta consulta devolveria zero linha <b>em silêncio</b> e toda conexão falharia
 * com "estado inválido" — o mesmo defeito que deixou os jobs do fiscal inertes até 2026-08-19.
 * Por isso o {@code id_tenant} vai <b>explícito</b> em cada comando, e não por política.
 */
@Repository
public class EstadoOAuthRepositorio {

    private static final SecureRandom ALEATORIO = new SecureRandom();

    /**
     * Quanto tempo o {@code state} vale.
     *
     * <p>Dez minutos: o consentimento leva segundos e, se o lojista deixar a aba aberta e voltar
     * no dia seguinte, é melhor recomeçar do que aceitar uma autorização cuja origem ninguém mais
     * lembra. Janela curta é o que limita o estrago de um {@code state} vazado.
     */
    public static final Duration VALIDADE = Duration.ofMinutes(10);

    private final JdbcClient jdbc;

    public EstadoOAuthRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** O que o retorno do OAuth descobre a partir do {@code state}. */
    public record EstadoConsumido(long idTenant, long idCanal, long idUsuario) {
    }

    /**
     * Cria um {@code state} para este tenant/canal e devolve o valor <b>em claro</b> — a única
     * vez em que ele existe fora do navegador do lojista. No banco fica só o hash.
     */
    @Transactional
    public String criar(long idTenant, long idCanal, long idUsuario) {
        String estado = gerar();
        jdbc.sql("""
                        INSERT INTO plataforma.oauth_estado_canal
                               (estado_hash, id_tenant, id_canal, id_usuario, expira_em)
                        VALUES (?, ?, ?, ?, now() + ?::interval)
                        """)
                .params(hash(estado), idTenant, idCanal, idUsuario, VALIDADE.toSeconds() + " seconds")
                .update();
        return estado;
    }

    /**
     * Consome o {@code state}: valida e marca como usado <b>num comando só</b>.
     *
     * <p>⚠️ <b>A atomicidade é o ponto.</b> Ler, conferir em Java e depois marcar deixaria uma
     * janela para duas voltas simultâneas passarem pelo mesmo {@code state}. O {@code UPDATE …
     * WHERE usado_em IS NULL … RETURNING} fecha isso no banco: a segunda não casa linha nenhuma e
     * volta vazia.
     *
     * <p>⚠️ E ele é chamado <b>antes</b> da troca do {@code code} por token, não depois. Um
     * {@code state} que sobrevive a uma troca malsucedida poderia ser reapresentado com outro
     * {@code code} — e o custo de errar para o lado seguro é o lojista clicar "Conectar" de novo.
     *
     * @return vazio quando o {@code state} não existe, já foi usado ou expirou. Os três casos são
     *         deliberadamente indistinguíveis para quem chama de fora: dizer <i>qual</i> deles
     *         ocorreu confirmaria a existência de um {@code state} a quem está tentando adivinhar
     */
    @Transactional
    public Optional<EstadoConsumido> consumir(String estado) {
        if (estado == null || estado.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        UPDATE plataforma.oauth_estado_canal
                           SET usado_em = now()
                         WHERE estado_hash = ? AND usado_em IS NULL AND expira_em > now()
                        RETURNING id_tenant, id_canal, id_usuario
                        """)
                .params(hash(estado))
                .query((rs, n) -> new EstadoConsumido(
                        rs.getLong("id_tenant"), rs.getLong("id_canal"), rs.getLong("id_usuario")))
                .optional();
    }

    private static String gerar() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String estado) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(estado.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
