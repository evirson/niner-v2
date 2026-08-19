package com.vetor.niner.plataforma.backup;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Backup pelo backoffice: consultar a situação e disparar fora da janela.
 *
 * <p>O disparo manual não é conveniência: é o que permite <b>testar o backup no dia do deploy</b>,
 * em vez de descobrir na primeira madrugada (ou, pior, no dia do restore) que a credencial estava
 * errada.
 */
@RestController
@RequestMapping("/api/admin/backup")
public class BackupController {

    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> situacao(@AuthenticationPrincipal Jwt jwt) {
        exigirStaff(jwt);
        return service.situacao();
    }

    @PostMapping("/executar")
    public Map<String, String> executar(@AuthenticationPrincipal Jwt jwt) {
        if (!"SUPER_ADMIN".equals(jwt.getClaimAsString("papel"))) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas SUPER_ADMIN dispara backup manual.");
        }
        return Map.of("resultado", service.executar());
    }

    private static void exigirStaff(Jwt jwt) {
        if (jwt.getClaimAsString("papel") == null) {
            throw new ResponseStatusException(FORBIDDEN, "Somente o staff da plataforma.");
        }
    }
}
