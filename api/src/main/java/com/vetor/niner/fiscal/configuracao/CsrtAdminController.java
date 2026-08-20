package com.vetor.niner.fiscal.configuracao;

import com.vetor.niner.fiscal.configuracao.CsrtAdminService.CsrtUfResponse;
import com.vetor.niner.fiscal.configuracao.CsrtAdminService.SalvarCsrtRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CSRT por UF no backoffice da plataforma — leitura para staff, gravação só para SUPER_ADMIN.
 *
 * <p><b>Por que vive no módulo fiscal e não em {@code plataforma/}:</b> a tabela
 * {@code cfg_csrt_resptec} é global e sem RLS, igual a {@code cfg_uf_autorizador}, e quem a lê no
 * caminho quente é o montador do XML. Um serviço de domínio buscando em {@code plataforma.*} seria
 * a travessia de plano que P9 proíbe — a única permitida é a cota de vendas. O controller estar na
 * superfície {@code /api/admin} não muda de plano nada: é a mesma referência nacional, mantida
 * pela plataforma em vez de por script.
 *
 * <p>⚠️ <b>O CSRT nunca volta pela API</b> — nem para SUPER_ADMIN. A tela recebe só
 * {@code definido}, o {@code idCsrt} (que é público, vai no XML em claro) e quando foi trocado.
 */
@RestController
@RequestMapping("/api/admin/fiscal/csrt")
public class CsrtAdminController {

    private final CsrtAdminService service;

    public CsrtAdminController(CsrtAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<CsrtUfResponse> listar(@AuthenticationPrincipal Jwt jwt) {
        return service.listar(jwt);
    }

    @PutMapping("/{uf}/{ambiente}")
    public CsrtUfResponse salvar(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable String uf,
                                 @PathVariable int ambiente,
                                 @Valid @RequestBody SalvarCsrtRequest req) {
        return service.salvar(jwt, uf, ambiente, req);
    }

    @DeleteMapping("/{uf}/{ambiente}")
    public ResponseEntity<Void> excluir(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable String uf,
                                        @PathVariable int ambiente) {
        service.excluir(jwt, uf, ambiente);
        return ResponseEntity.noContent().build();
    }
}
