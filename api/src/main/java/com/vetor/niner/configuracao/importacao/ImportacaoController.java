package com.vetor.niner.configuracao.importacao;

import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.TabelaImportavel;
import com.vetor.niner.configuracao.importacao.ProdutoImportador.AnaliseProduto;
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
    private final ProdutoImportador produtoImportador;

    public ImportacaoController(ImportacaoService service, ProdutoImportador produtoImportador) {
        this.service = service;
        this.produtoImportador = produtoImportador;
    }

    /** Passo prévio da importação de produto: detecta colunas de estoque com dado (para o
     *  front pedir o mapeamento pra empresa) e sugere rótulo de variante por grupo de produto —
     *  ver {@link ProdutoImportador#analisar}. Não faz parte da interface genérica porque
     *  nenhuma outra tabela precisa desse reconhecimento estruturado. */
    @PostMapping(value = "/produto/analise", consumes = "multipart/form-data")
    public AnaliseProduto analisarProduto(@AuthenticationPrincipal Jwt jwt, @RequestPart("arquivo") MultipartFile arquivo) {
        service.validarAdmin(jwt);
        return produtoImportador.analisar(arquivo);
    }

    @GetMapping("/tabelas")
    public List<TabelaImportavel> listarTabelas(@AuthenticationPrincipal Jwt jwt) {
        return service.listarTabelas(jwt);
    }

    @GetMapping("/{tabela}/modelo")
    public ResponseEntity<byte[]> modelo(@AuthenticationPrincipal Jwt jwt, @PathVariable String tabela) {
        byte[] csv = service.modelo(jwt, tabela);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(tabela + "_modelo.csv").build().toString())
                .body(csv);
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
                                          @RequestParam(value = "confirmar", defaultValue = "false") boolean confirmar) {
        return service.processar(jwt, tabela, arquivo, escolhas, confirmar);
    }
}
