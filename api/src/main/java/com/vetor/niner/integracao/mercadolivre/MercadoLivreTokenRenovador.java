package com.vetor.niner.integracao.mercadolivre;

import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.CredenciaisCanalRepositorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * O trabalho de renovar tokens — o par transacional de {@link MercadoLivreTokenJob}.
 *
 * <p>Separado dele de propósito: ver o javadoc do job para o motivo (auto-invocação não passa pelo
 * proxy do Spring, e sem transação não há {@code SET LOCAL app.id_tenant}).
 */
@Component
public class MercadoLivreTokenRenovador {

    private static final Logger log = LoggerFactory.getLogger(MercadoLivreTokenRenovador.class);

    /**
     * Renova quando falta menos de 1 hora para vencer.
     *
     * <p>⚠️ A folga não é margem de conforto: é o que impede o token de expirar <b>no meio de uma
     * sincronização</b>. Sem ela, um evento do outbox que passasse na verificação e demorasse
     * alguns segundos levaria 401 do ML — falha que parece problema de credencial e manda o
     * diagnóstico para o lado errado. Uma hora sobre seis é barato e resolve.
     */
    public static final Duration FOLGA = Duration.ofHours(1);

    private final CredenciaisCanalRepositorio credenciais;
    private final MercadoLivreOAuthService oauth;

    public MercadoLivreTokenRenovador(CredenciaisCanalRepositorio credenciais,
                                      MercadoLivreOAuthService oauth) {
        this.credenciais = credenciais;
        this.oauth = oauth;
    }

    /**
     * Renova o que vence dentro da folga, no tenant já estabelecido pelo chamador.
     *
     * <p>⚠️ <b>Sem {@code @Transactional} nesta fachada, e é deliberado.</b> Cada canal faz uma
     * chamada HTTP a terceiro; uma transação só, aberta em volta do laço inteiro, seguraria a
     * conexão do pool durante todas elas e faria a falha do último canal desfazer a renovação dos
     * anteriores. Quem transaciona são os métodos do repositório, um canal por vez — e é por isso
     * que o repositório precisa das próprias anotações.
     *
     * @return quantos tokens foram renovados
     */
    public int renovarDoTenantCorrente() {
        List<CredenciaisCanal> vencendo = credenciais.aRenovar(Instant.now(), FOLGA);
        int renovados = 0;
        for (CredenciaisCanal atual : vencendo) {
            try {
                if (oauth.renovar(atual)) {
                    renovados++;
                }
            } catch (CanalIndisponivelException e) {
                // Transitório: o ML não respondeu agora. NÃO marca erro — a folga de 1 h dá seis
                // rodadas de margem, e desconectar o lojista por um soluço de rede seria pior que
                // o problema. A próxima rodada tenta de novo.
                log.warn("Mercado Livre indisponível ao renovar o canal {}: {}",
                        atual.idCanal(), e.getMessage());
            }
        }
        return renovados;
    }
}
