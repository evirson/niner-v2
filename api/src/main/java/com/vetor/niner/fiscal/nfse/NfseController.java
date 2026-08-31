package com.vetor.niner.fiscal.nfse;

import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.seguranca.EmpresaDaSessao;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.identidade.permissao.Acao;
import com.vetor.niner.identidade.permissao.PermissaoService;
import com.vetor.niner.identidade.permissao.Tela;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Emissão, consulta e cancelamento da NFS-e.
 *
 * <p>⚠️ Emitir é <b>incluir</b> e cancelar é <b>excluir</b>, na regra que o
 * {@code PermissaoInterceptor} deriva do verbo HTTP — o {@code AcoesPorTelaConferemTest} reprova o
 * build se a tela for catalogada com ações diferentes das que os endpoints exigem. Foi ele que
 * evitou o PDV nascer sem "incluir", o que teria deixado nenhum operador conseguir vender.
 */
@RestController
@RequestMapping("/api/v1/nfse")
@Tela("fiscal.nfse")
public class NfseController {

    private final NfseEmissaoService emissao;
    private final NfseCancelamentoService cancelamento;
    private final NfseDocumentoRepositorio repositorio;
    private final ArmazenamentoPrivado armazenamento;
    private final com.vetor.niner.comum.tempo.FusoDaLoja fusoDaLoja;

    public NfseController(NfseEmissaoService emissao, NfseCancelamentoService cancelamento,
                          NfseDocumentoRepositorio repositorio,
                          ArmazenamentoPrivado armazenamento,
                          com.vetor.niner.comum.tempo.FusoDaLoja fusoDaLoja) {
        this.emissao = emissao;
        this.cancelamento = cancelamento;
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
        this.fusoDaLoja = fusoDaLoja;
    }

    /**
     * Emite as NFS-e da venda — <b>uma por código de serviço</b>, por isso a resposta é lista.
     *
     * <p>A emissão é passo separado do fechamento da venda (DS13, o mesmo
     * {@code cfg_emite_fiscal_apos_venda} da NFC-e): a papeleta sai primeiro e o operador decide.
     * É o que mantém o F3 de pé — uma indisponibilidade do SEFIN não pode parar o balcão.
     */
    @PostMapping("/vendas/{idVenda}/emitir")
    public List<NfseEmissaoService.Resultado> emitir(@PathVariable long idVenda) {
        return emissao.emitirDaVenda(idVenda);
    }

    /**
     * Listagem por período — a aba de NFS-e em Documentos Fiscais.
     *
     * <p>Paginação por número de página, como o resto do produto (não cursor): a tela precisa
     * poder pular para qualquer página.
     */
    @GetMapping
    public Map<String, Object> listar(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam long idEmpresa,
                                      @RequestParam String de,
                                      @RequestParam String ate,
                                      @RequestParam(required = false) String situacao,
                                      @RequestParam(defaultValue = "1") int pagina,
                                      @RequestParam(defaultValue = "50") int limite) {
        EmpresaDaSessao.exigirAcesso(jwt, idEmpresa);
        // ⚠️ O fuso é o DA LOJA, nunca o da JVM: OffsetDateTime.now().getOffset() pega o TZ do
        // container, que só existe em produção — o filtro de data perderia as notas emitidas
        // depois das 21h e o defeito não reproduziria em dev.
        var fuso = fusoDaLoja.da(idEmpresa);
        var inicio = java.time.LocalDate.parse(de).atStartOfDay(fuso).toOffsetDateTime();
        var fim = java.time.LocalDate.parse(ate).plusDays(1).atStartOfDay(fuso).toOffsetDateTime();
        int deslocamento = Math.max(0, (pagina - 1) * limite);
        return Map.of(
                "itens", repositorio.listar(idEmpresa, inicio, fim, situacao, limite, deslocamento),
                "total", repositorio.contar(idEmpresa, inicio, fim, situacao),
                "pagina", pagina,
                "tamanhoPagina", limite);
    }

    /** As notas de uma venda, com situação e motivo — alimenta a fila de pendentes. */
    @GetMapping("/vendas/{idVenda}")
    public List<NfseDocumentoRepositorio.Documento> daVenda(@PathVariable long idVenda) {
        return repositorio.daVenda(idVenda);
    }

    @GetMapping("/{idNfse}")
    public NfseDocumentoRepositorio.Documento buscar(@PathVariable long idNfse) {
        return repositorio.buscar(idNfse).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "NFS-e não encontrada"));
    }

    /**
     * Cancela pelo evento 101101.
     *
     * <p>⚠️ {@code DELETE} porque a ação é "excluir" no RBAC, mas <b>nada é apagado</b>: a nota
     * muda de situação e o evento fica registrado para sempre (F6, e a V102 não dá GRANT de
     * DELETE nessas tabelas).
     */
    @DeleteMapping("/{idNfse}")
    @Acao(PermissaoService.Acao.EXCLUIR)
    public NfseEmissaoService.Resultado cancelar(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable long idNfse,
                                                 @RequestBody CancelamentoRequest req) {
        var doc = repositorio.buscar(idNfse).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "NFS-e não encontrada"));
        return cancelamento.cancelar(idNfse, req.codigoMotivo(), req.motivo(),
                cnpjDaEmpresa(doc.idEmpresa()), idUsuario(jwt));
    }

    /**
     * O XML autorizado, para o contador.
     *
     * <p>⚠️ Servido em memória, nunca por {@code StreamingResponseBody}: o {@code TenantContext} é
     * um {@code ScopedValue} e o corpo de um streaming é escrito depois que o controller retorna,
     * fora do escopo — o {@code ArmazenamentoPrivado} confere o prefixo do tenant a partir dele
     * (P8). XML de uma nota cabe em memória com folga.
     */
    @GetMapping("/{idNfse}/xml")
    public ResponseEntity<ByteArrayResource> xml(@PathVariable long idNfse) {
        var doc = repositorio.buscar(idNfse).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "NFS-e não encontrada"));
        if (doc.xmlChave() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta NFS-e ainda não tem XML arquivado. Só nota autorizada tem — a situação "
                    + "desta é " + doc.situacao().toLowerCase() + ".");
        }
        byte[] conteudo = armazenamento.ler(AreaPrivada.FISCAL_XML, doc.xmlChave());
        String nome = "nfse-" + (doc.numeroNfse() == null ? doc.numeroDps() : doc.numeroNfse())
                + ".xml";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nome + "\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(new ByteArrayResource(conteudo));
    }

    private String cnpjDaEmpresa(long idEmpresa) {
        return repositorio.cnpjDaEmpresa(idEmpresa);
    }

    private static Long idUsuario(Jwt jwt) {
        Object uid = jwt == null ? null : jwt.getClaim("uid");
        return uid == null ? null : Long.valueOf(uid.toString());
    }

    public record CancelamentoRequest(int codigoMotivo, @NotBlank String motivo) {
    }
}
