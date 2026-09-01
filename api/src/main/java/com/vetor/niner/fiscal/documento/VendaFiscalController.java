package com.vetor.niner.fiscal.documento;

import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.fiscal.documento.EmissaoNfceService.ResultadoEmissao;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emissão da NFC-e de uma venda do PDV (§9.6, bloco B7). Chamado pela tela <b>depois</b> que
 * {@code POST /api/v1/pdv/vendas} já confirmou (201) — a venda nunca depende deste passo (F3).
 */
@RestController
@RequestMapping("/api/v1/pdv/vendas")
@Tela("pdv")
public class VendaFiscalController {

    private final VendaFiscalService service;

    public VendaFiscalController(VendaFiscalService service) {
        this.service = service;
    }

    /** {@code incluirCpf} (2026-08-19) — resposta da pergunta feita ao operador antes de emitir
     *  (ver {@code ComprovantePapeletaModal.tsx}); nunca mais inferido sozinho do cliente da
     *  venda. */
    /**
     * @param observacao 2026-09-01 — texto livre digitado pelo operador <b>antes de emitir</b>, que
     *         vai para o {@code infCpl} do XML e aparece em INFORMAÇÕES COMPLEMENTARES no DANFE.
     *         ⚠️ Tem de ser antes de emitir, não antes de imprimir: depois da autorização o XML
     *         está assinado na SEFAZ, e um texto acrescentado só ao papel faria o DANFE divergir do
     *         documento que vale. Opcional — {@code null} não gera linha nenhuma.
     */
    public record EmitirNfceRequest(boolean incluirCpf, String observacao) {
    }

    /**
     * 200 com o resultado quando o fiscal está ligado (autorizado, rejeitado, contingência etc.);
     * 204 sem corpo quando está desligado para a empresa — a tela não mostra nada, como se o
     * módulo fiscal não existisse (F12).
     */
    @PostMapping("/{idVenda}/nfce")
    public ResponseEntity<ResultadoEmissao> emitir(@AuthenticationPrincipal Jwt jwt, @PathVariable long idVenda,
                                                   @RequestBody EmitirNfceRequest req) {
        return service.emitirNfce(jwt, idVenda, req.incluirCpf(), req.observacao())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
