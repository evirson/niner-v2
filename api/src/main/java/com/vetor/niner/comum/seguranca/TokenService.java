package com.vetor.niner.comum.seguranca;

import com.vetor.niner.comum.config.NinerProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Emite o access token (JWT HS256) de um usuário do tenant. Claims: {@code sub}
 * (id_usuario), {@code tid} (id_tenant), {@code aud=[tenant]}, {@code roles}, {@code email}.
 * O {@code tid} alimenta o {@code TenantContext}/RLS (P8, §3.1.1).
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final NinerProperties props;

    public TokenService(JwtEncoder encoder, NinerProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String emitir(long idUsuario, long idTenant, String email, List<String> roles) {
        Instant agora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwt().emissor())
                .issuedAt(agora)
                .expiresAt(agora.plus(props.jwt().expiracao()))
                .subject(Long.toString(idUsuario))
                .audience(List.of("tenant"))
                .claim("tid", idTenant)
                .claim("email", email)
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
