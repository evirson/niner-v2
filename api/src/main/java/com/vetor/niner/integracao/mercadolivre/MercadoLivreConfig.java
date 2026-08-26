package com.vetor.niner.integracao.mercadolivre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vetor.niner.comum.config.NinerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fiação do Mercado Livre.
 *
 * <p>O transporte não é {@code @Component} porque precisa de configuração no construtor
 * ({@code client_id}, {@code client_secret}, {@code redirect_uri}, host) — e porque é isso que
 * permite ao teste apontá-lo para o WireMock. Sem sandbox no ML, essa capacidade não é conforto:
 * é a única forma barata de exercitar o fluxo.
 *
 * <p>⚠️ O bean é criado <b>mesmo sem credencial</b>, de propósito. Quem recusa a operação é
 * {@code MercadoLivreOAuthService}, com 503 e uma frase que diz o que falta. Condicionar o bean à
 * credencial faria a aplicação quebrar na subida por falta de dependência — um servidor de
 * desenvolvimento sem chave do ML tem de subir normalmente, como já acontece com a cobrança.
 */
@Configuration
public class MercadoLivreConfig {

    /**
     * ⚠️ Mapper criado aqui, <b>não recebido por parâmetro</b>: esta aplicação não expõe um bean
     * de {@code ObjectMapper}, e pedi-lo num método {@code @Bean} derruba o contexto na subida
     * ("No qualifying bean of type ObjectMapper"). Mesmo padrão de {@code OutboxRepositorio}.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    public MercadoLivreOAuth mercadoLivreOAuth(NinerProperties props) {
        NinerProperties.MercadoLivre ml = props.canais().mercadolivre();
        return new MercadoLivreOAuth(ml.apiUrl(), ml.clientId(), ml.clientSecret(),
                ml.redirectUri(), JSON);
    }
}
