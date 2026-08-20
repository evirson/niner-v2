package com.vetor.niner.estoque.devolucaocompra;

import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.CancelarDevolucaoCompraRequest;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.DevolucaoCompraCanceladaResponse;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.DevolucaoCompraEfetivadaResponse;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.EfetivarDevolucaoCompraRequest;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.ItemDevolvivelResponse;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.NotaFiscalDevolucaoCompraResponse;
import com.vetor.niner.estoque.devolucaocompra.DevolucaoCompraDtos.PaginaEntradasElegiveis;
import com.vetor.niner.fiscal.documento.CancelamentoNfceService;
import com.vetor.niner.fiscal.documento.EmissaoNfeDevolucaoService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Devolução de Produtos Comprados (docs/telas/devolucao-compra.md) - superfície do tenant
 * (/api/v1, JWT + RLS). ADMIN e OPERADOR, como as demais telas de estoque.
 *
 * <h2>A orquestração fiscal mora aqui, e a ordem é diferente nos dois sentidos</h2>
 *
 * <p>Efetivar: grava primeiro, emite depois. Cancelar: cancela a nota primeiro, reverte depois.
 * Não é assimetria gratuita - em cada caso o que vem primeiro é o passo que, se falhar, deixa o
 * sistema no estado <b>menos</b> errado:
 *
 * <ul>
 *   <li><b>Efetivando</b>, a mercadoria vai sair fisicamente. Se a SEFAZ estiver fora, o certo é a
 *       devolução existir com a nota pendente (e o operador segurar a carga) - não perder a
 *       operação inteira.</li>
 *   <li><b>Cancelando</b>, a nota autorizada já declarou que a mercadoria saiu. Devolver o estoque
 *       antes de a SEFAZ confirmar o cancelamento deixaria a loja com a mercadoria em casa e um
 *       documento válido dizendo o contrário - o pior dos dois mundos.</li>
 * </ul>
 *
 * <p>Nos dois casos o controller é o lugar certo porque <b>não é transacional</b>: F2 proíbe
 * chamada de rede dentro de transação de banco, e a SEFAZ pode levar 10 s.
 */
@RestController
@RequestMapping("/api/v1/estoque/devolucao-compra")
public class DevolucaoCompraController {

    private final DevolucaoCompraService service;
    private final EmissaoNfeDevolucaoService emissaoFiscal;
    private final CancelamentoNfceService cancelamentoFiscal;

    public DevolucaoCompraController(DevolucaoCompraService service, EmissaoNfeDevolucaoService emissaoFiscal,
                                     CancelamentoNfceService cancelamentoFiscal) {
        this.service = service;
        this.emissaoFiscal = emissaoFiscal;
        this.cancelamentoFiscal = cancelamentoFiscal;
    }

    /** Primeira grid: as entradas que podem gerar devolução, pelos filtros do popup. */
    @GetMapping("/entradas")
    public PaginaEntradasElegiveis entradas(@RequestParam(required = false) Long idFornecedor,
                                            @RequestParam(required = false) Long idEmpresa,
                                            @RequestParam(required = false) Integer notaFiscal,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicial,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFinal,
                                            @RequestParam(required = false) Integer pagina,
                                            @RequestParam(required = false) Integer limite) {
        return service.listarEntradas(idFornecedor, idEmpresa, notaFiscal, dataInicial, dataFinal, pagina, limite);
    }

    /** Segunda grid: o que desta entrada ainda pode voltar - o menor entre saldo da nota e estoque. */
    @GetMapping("/entradas/{idMovimento}/itens")
    public List<ItemDevolvivelResponse> itens(@PathVariable long idMovimento) {
        return service.itensDevolviveis(idMovimento);
    }

    /**
     * Grava a devolução (baixa de estoque) e emite a NF-e de saída ao fornecedor.
     *
     * <p>Falha na nota NÃO desfaz a devolução (F3), mas - diferente da devolução ao consumidor -
     * <b>impede a mercadoria de viajar</b>. É por isso que a mensagem de cada desfecho diz
     * explicitamente se pode ou não despachar; ver os textos em {@code EmissaoNfeDevolucaoService}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DevolucaoCompraEfetivadaResponse efetivar(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody EfetivarDevolucaoCompraRequest req) {
        DevolucaoCompraEfetivadaResponse dev = service.efetivar(jwt, req);

        Integer idUsuario = Integer.valueOf(jwt.getSubject());
        NotaFiscalDevolucaoCompraResponse nota = emissaoFiscal
                .emitirDevolucaoDeCompraSeAplicavel(dev.idEmpresa(), dev.idMovimento(), idUsuario)
                .map(r -> new NotaFiscalDevolucaoCompraResponse(r.situacao().name(), r.idDocumentoFiscal(),
                        r.chaveAcesso(), r.protocolo(), r.cStat(), r.mensagem()))
                .orElse(null);

        return new DevolucaoCompraEfetivadaResponse(dev.idMovimento(), dev.idMovimentoOrigem(),
                dev.dataMovimento(), dev.idEmpresa(), dev.idFornecedor(), dev.nomeFornecedor(),
                dev.notaFiscalOrigem(), dev.valorTotal(), dev.itens(), nota);
    }

    /**
     * Cancela a devolução: NF-e primeiro (evento 110111), estoque depois.
     *
     * <p>{@code exigirCancelavel} vem antes de tudo - descobrir que a devolução já estava cancelada
     * <b>depois</b> de mandar o evento para a SEFAZ deixaria um cancelamento fiscal órfão, que não
     * dá para desfazer.
     *
     * <p>Se a SEFAZ recusar (ou o prazo da UF tiver vencido), o serviço fiscal lança 409 e
     * <b>nada</b> é revertido - a mensagem explica ao operador o caminho legal, que é pedir a nota
     * de devolução ao fornecedor.
     */
    @PostMapping("/{idMovimento}/cancelar")
    public DevolucaoCompraCanceladaResponse cancelar(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable long idMovimento,
                                                     @Valid @RequestBody CancelarDevolucaoCompraRequest req) {
        long idEmpresa = service.exigirCancelavel(idMovimento);
        Integer idUsuario = Integer.valueOf(jwt.getSubject());

        String protocolo = cancelamentoFiscal
                .cancelarNotaDeDevolucaoDeCompraSeAplicavel(idEmpresa, idMovimento, idUsuario, req.motivo())
                .orElse(null);

        return service.cancelar(jwt, idMovimento, req, protocolo);
    }
}
