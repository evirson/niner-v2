package com.vetor.niner.fiscal.nfse;

import com.vetor.niner.comum.seguranca.EmpresaDaSessao;
import com.vetor.niner.identidade.permissao.Tela;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Configuração da NFS-e — singleton por empresa, no molde da {@code fiscal.configuracao}.
 *
 * <p>Sem {@code POST} e sem {@code DELETE}: a linha nasce no primeiro {@code PUT} (upsert) e nunca
 * é removida — desligar é {@code emiteNfse=false}, não apagar.
 */
@RestController
@RequestMapping("/api/v1/fiscal/nfse")
@Tela("fiscal.nfse-configuracao")
public class NfseConfigController {

    private final NfseConfigService service;

    public NfseConfigController(NfseConfigService service) {
        this.service = service;
    }

    @GetMapping("/{idEmpresa}")
    public NfseConfigService.Config buscar(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable long idEmpresa) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.buscar(idEmpresa);
    }

    @PutMapping("/{idEmpresa}")
    public NfseConfigService.Config salvar(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable long idEmpresa,
                                           @RequestBody NfseConfigService.Config req) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.salvar(idEmpresa, req);
    }

    /**
     * ⚠️ {@code PUT} e não {@code POST}, de propósito: no RBAC deste projeto o verbo decide a ação,
     * e testar conexão é <b>alterar</b> (grava o resultado do teste), não <b>incluir</b>. Declarar
     * {@code POST} aqui faria o {@code AcoesPorTelaConferemTest} exigir "incluir" numa tela que é
     * só de configuração.
     */
    @PutMapping("/{idEmpresa}/testar-conexao")
    public NfseConfigService.Teste testar(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable long idEmpresa) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.testarConexao(idEmpresa);
    }

    /** O assistente: o que passou, o que falta e onde resolver cada pendência. */
    @PutMapping("/{idEmpresa}/verificar")
    public List<NfseConfigService.Verificacao> verificar(@AuthenticationPrincipal Jwt jwt,
                                                         @PathVariable long idEmpresa) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.verificar(idEmpresa);
    }

    /**
     * Alíquota do ISS sugerida pelo ADN para um código de serviço.
     *
     * <p>⭐ É o que evita o chamado: em vez de o lojista procurar na lei municipal, o sistema
     * pergunta à fonte. ⚠️ Corpo vazio é resposta legítima — nem todo município publicou a tabela.
     */
    @GetMapping("/{idEmpresa}/aliquota-sugerida")
    public Map<String, Object> aliquotaSugerida(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable long idEmpresa,
                                                @RequestParam String cTribNac,
                                                @RequestParam(required = false) String cTribMun) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.aliquotaSugerida(idEmpresa, cTribNac, cTribMun)
                .map(a -> Map.<String, Object>of(
                        "encontrada", true,
                        "percentual", a.percentual(),
                        "incide", a.incide(),
                        "vigenteDesde", a.vigenteDesde() == null ? "" : a.vigenteDesde()))
                .orElse(Map.of("encontrada", false,
                        "aviso", "Este município não publicou a alíquota deste serviço no ADN. "
                                + "Informe a alíquota manualmente — confirme com seu contador."));
    }
}
