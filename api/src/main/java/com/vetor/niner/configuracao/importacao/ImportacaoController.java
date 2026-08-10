package com.vetor.niner.configuracao.importacao;

import com.vetor.niner.configuracao.importacao.ImportacaoDtos.DeteccaoArquivo;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.ProgressoResponse;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.TabelaImportavel;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Rotina de Importação de Dados (docs/telas/importacao-dados.md), superfície do tenant
 * (`/api/v1`, JWT + RLS). ADMIN-only, checado em {@link ImportacaoService}.
 */
@RestController
@RequestMapping("/api/v1/importacao")
public class ImportacaoController {

    private final ImportacaoService service;

    public ImportacaoController(ImportacaoService service) {
        this.service = service;
    }

    @GetMapping("/tabelas")
    public List<TabelaImportavel> listarTabelas(@AuthenticationPrincipal Jwt jwt) {
        return service.listarTabelas(jwt);
    }

    /** Detecção automática de tipo de arquivo (tela única de importação, 2026-08-09) — lê só o
     *  cabeçalho, sem processar nenhuma linha. {@code tabela} vem {@code null} quando não deu
     *  pra identificar com confiança (a tela pede escolha manual nesse caso). */
    @PostMapping(value = "/detectar", consumes = "multipart/form-data")
    public DeteccaoArquivo detectar(@AuthenticationPrincipal Jwt jwt, @RequestPart("arquivo") MultipartFile arquivo) {
        return service.detectar(jwt, arquivo);
    }

    @GetMapping("/{tabela}/modelo")
    public ResponseEntity<byte[]> modelo(@AuthenticationPrincipal Jwt jwt, @PathVariable String tabela) {
        byte[] planilha = service.modelo(jwt, tabela);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(tabela + "_modelo.xlsx").build().toString())
                .body(planilha);
    }

    /**
     * {@code confirmar=false} (default) só simula — nenhuma linha é gravada, o relatório mostra
     * o que aconteceria. {@code confirmar=true} grava de verdade. {@code escolhas} é um JSON
     * livre (cada tabela interpreta suas próprias chaves — ver docs/telas/importacao-dados.md).
     */
    @PostMapping(value = "/{tabela}/processar", consumes = "multipart/form-data")
    public RelatorioImportacao processar(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable String tabela,
                                          @RequestPart("arquivo") MultipartFile arquivo,
                                          @RequestPart(value = "escolhas", required = false) String escolhas,
                                          @RequestParam(value = "confirmar", defaultValue = "false") boolean confirmar,
                                          @RequestParam(value = "idProgresso", required = false) String idProgresso) {
        return service.processar(jwt, tabela, arquivo, escolhas, confirmar, idProgresso);
    }

    /** Polling de progresso (2026-08-11) — a tela consulta isto a cada poucos milissegundos
     *  enquanto {@code /processar} está em voo, pro gauge mostrar registro atual/total real em
     *  vez de só uma animação. */
    @GetMapping("/progresso/{idProgresso}")
    public ProgressoResponse progresso(@AuthenticationPrincipal Jwt jwt, @PathVariable String idProgresso) {
        return service.progresso(jwt, idProgresso);
    }
}
