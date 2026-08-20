package com.vetor.niner.comum.seguranca;

import com.vetor.niner.comum.config.NinerProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Emite o access token (JWT HS256) de um usuário do tenant. Claims: {@code sub}
 * (id_usuario), {@code tid} (id_tenant), {@code eid} (id_empresa ativa na sessão, 2026-07-28),
 * {@code aud=[tenant]}, {@code roles}, {@code email}. O {@code tid} alimenta o
 * {@code TenantContext}/RLS (P8, §3.1.1). {@code eid} é a empresa escolhida no login
 * (docs/telas/login-empresa.md) — todo registro que o usuário cria com coluna
 * {@code id_empresa} passa a usar esse valor, não mais "a primeira empresa do tenant".
 */
@Service
public class TokenService {

    /** Sessão do staff dura menos que a do lojista (8h): acesso interno, cross-tenant. */
    private static final Duration EXPIRACAO_STAFF = Duration.ofHours(2);

    private final JwtEncoder encoder;
    private final NinerProperties props;

    public TokenService(JwtEncoder encoder, NinerProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String emitir(long idUsuario, long idTenant, long idEmpresa, String email, List<String> roles) {
        Instant agora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwt().emissor())
                .issuedAt(agora)
                .expiresAt(agora.plus(props.jwt().expiracao()))
                .subject(Long.toString(idUsuario))
                .audience(List.of("tenant"))
                .claim("tid", idTenant)
                .claim("eid", idEmpresa)
                .claim("email", email)
                .claim("roles", roles)
                .build();
        return assinar(claims);
    }

    /**
     * Token do staff da plataforma (backoffice). Validade menor que a do lojista de propósito:
     * é sessão de operador interno com acesso cross-tenant, e sessão longa aqui vale muito mais
     * para quem roubar a máquina.
     */
    public String emitirStaff(long idStaff, String email, String papel) {
        Instant agora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwt().emissor())
                .issuedAt(agora)
                .expiresAt(agora.plus(EXPIRACAO_STAFF))
                .subject(Long.toString(idStaff))
                .audience(List.of("plataforma"))
                .claim("email", email)
                .claim("papel", papel)
                .build();
        return assinar(claims);
    }

    private String assinar(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
