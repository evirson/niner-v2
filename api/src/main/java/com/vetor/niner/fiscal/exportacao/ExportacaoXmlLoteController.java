package com.vetor.niner.fiscal.exportacao;

import com.vetor.niner.comum.seguranca.EmpresaDaSessao;
import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteDtos.ResumoExportacaoXml;
import com.vetor.niner.fiscal.exportacao.ExportacaoXmlLoteService.PacoteZip;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Exportação de XML em Lote (`docs/telas/exportacao-xml-lote.md`) — superfície do tenant
 * (`/api/v1`, JWT + RLS).
 *
 * <p>⚠️ <b>Não é mais ADMIN-only</b> (corrigido na auditoria de 2026-08-29, rodada 4). Este javadoc
 * dizia <i>"ADMIN-only, conferido no serviço"</i> e o {@code exigirAdmin} correspondente estava
 * <b>morto</b> havia tempo — quem governa é o RBAC por tela e ação, o que significa que o
 * administrador <b>pode conceder</b> a exportação do XML fiscal a um operador. Isso pode ser
 * exatamente o desejado; o que não podia continuar era a proibição escrita que não existe, que é o
 * defeito que esta base já pagou duas vezes.
 */
@RestController
@RequestMapping("/api/v1/fiscal/exportacao-xml")
@Tela("fiscal.exportacao-xml")
public class ExportacaoXmlLoteController {

    private final ExportacaoXmlLoteService service;

    public ExportacaoXmlLoteController(ExportacaoXmlLoteService service) {
        this.service = service;
    }

    /**
     * Pré-conferência: quantas notas o período tem, quantas já têm XML arquivado e como o arquivo
     * vai se chamar. É o que impede o clique que geraria um pacote vazio, ou que estouraria o teto.
     */
    @GetMapping("/resumo")
    public ResumoExportacaoXml resumo(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam long idEmpresa,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) Integer modelo) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        return service.resumir(jwt, idEmpresa, dataInicial, dataFinal, modelo);
    }

    /**
     * O ZIP. O nome do arquivo vai no {@code Content-Disposition} — é dele que o navegador tira o
     * nome sugerido no "Salvar como", então ele é montado no servidor (P4), não no front.
     */
    @GetMapping
    public ResponseEntity<byte[]> exportar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam long idEmpresa,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
            @RequestParam(required = false) Integer modelo,
            // ⭐ Período grande é particionado, não recusado (2026-08-26). A tela chama este mesmo
            // endpoint uma vez por parte, repassando o `ateIdDocumento` que a pré-conferência
            // congelou — sem ele, uma nota emitida durante o download deslocaria a paginação.
            @RequestParam(required = false, defaultValue = "1") int parte,
            @RequestParam(required = false) Long ateIdDocumento) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        PacoteZip pacote = service.exportar(jwt, idEmpresa, dataInicial, dataFinal, modelo, parte, ateIdDocumento);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pacote.nomeArquivo()).build().toString())
                .body(pacote.conteudo());
    }
}
