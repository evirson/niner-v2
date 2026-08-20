package com.vetor.niner.plataforma.staff;

import com.vetor.niner.plataforma.staff.StaffDtos.LoginStaffRequest;
import com.vetor.niner.plataforma.staff.StaffDtos.SessaoStaffResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sessão do staff. {@code POST /api/admin/sessao} é o <b>único</b> ponto anônimo de
 * {@code /api/admin/**} (ver {@code SegurancaConfig}); todo o resto exige token com
 * {@code aud=plataforma}.
 */
@RestController
@RequestMapping("/api/admin")
public class StaffController {

    private final StaffService service;

    public StaffController(StaffService service) {
        this.service = service;
    }

    @PostMapping("/sessao")
    public SessaoStaffResponse entrar(@Valid @RequestBody LoginStaffRequest req) {
        return service.login(req);
    }

    /** Quem sou eu — usado pelo backoffice para saber o papel e montar o menu. */
    @GetMapping("/eu")
    public Map<String, Object> eu(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "idStaff", jwt.getSubject(),
                "email", jwt.getClaimAsString("email"),
                "papel", jwt.getClaimAsString("papel"));
    }
}
