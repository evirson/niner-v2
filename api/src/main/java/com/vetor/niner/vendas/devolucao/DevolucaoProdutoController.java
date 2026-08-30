package com.vetor.niner.vendas.devolucao;

import com.vetor.niner.comum.web.MotivoDeFalha;
import com.vetor.niner.identidade.permissao.Tela;

import com.vetor.niner.fiscal.documento.EmissaoNfeDevolucaoService;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.DevolucaoEfetivadaResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.EfetivarDevolucaoRequest;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.NotaFiscalDevolucaoResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.ValeMercadoriaResponse;
import com.vetor.niner.vendas.devolucao.DevolucaoProdutoDtos.VendedorDaVendaResponse;
import jakarta.validation.Valid;
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

/**
 * Devolução de Produtos (docs/telas/devolucao-produtos.md), superfície do tenant (`/api/v1`,
 * JWT + RLS). ADMIN e OPERADOR têm acesso completo.
 */
@RestController
@RequestMapping("/api/v1/vendas/devolucao")
@Tela("devolucao-produto")
public class DevolucaoProdutoController {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(DevolucaoProdutoController.class);

    private final DevolucaoProdutoService service;
    private final EmissaoNfeDevolucaoService emissaoFiscal;

    public DevolucaoProdutoController(DevolucaoProdutoService service, EmissaoNfeDevolucaoService emissaoFiscal) {
        this.service = service;
        this.emissaoFiscal = emissaoFiscal;
    }

    @GetMapping("/vendedor")
    public VendedorDaVendaResponse vendedor(@RequestParam long numeroVenda) {
        return service.buscarVendedorDaVenda(numeroVenda);
    }

    /** Consulta de um vale-mercadoria pelo número — reimpressão e resgate no PDV. */
    @GetMapping("/vale/{idDevolucao}")
    public ValeMercadoriaResponse vale(@PathVariable long idDevolucao) {
        return service.buscarVale(idDevolucao);
    }

    /**
     * Grava a devolução e, em seguida, emite a NF-e de entrada quando aplicável (§10.2, B9).
     *
     * <p>⚠️ <b>A orquestração mora aqui, no controller, de propósito.</b> {@code service.efetivar}
     * é {@code @Transactional} e a emissão faz I/O de rede (até 10 s) — chamá-la de dentro do
     * serviço prenderia a conexão e a trava das linhas pelo tempo da SEFAZ, violando o F2
     * ("nenhuma chamada de rede dentro de transação de banco"). O controller não é transacional,
     * então aqui a devolução já está gravada e commitada quando a emissão começa.
     *
     * <p>⚠️ <b>Falha na nota NÃO desfaz a devolução</b> — F3. Diferente do Cancelamento de Venda
     * (onde a nota tem que ser cancelada ANTES de reverter, senão a nota fica válida sem a venda),
     * aqui a mercadoria <b>já voltou fisicamente</b> ao estoque e o vale-mercadoria já é do
     * cliente: bloquear a devolução porque a SEFAZ está fora travaria o balcão por um motivo que
     * não é do balcão. A nota fica registrada com a situação real em Documentos Fiscais, para ser
     * reprocessada — o mesmo tratamento que a NFC-e da venda já recebe.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DevolucaoEfetivadaResponse efetivar(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EfetivarDevolucaoRequest req) {
        DevolucaoEfetivadaResponse devolucao = service.efetivar(jwt, req);

        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();
        Integer idUsuario = Integer.valueOf(jwt.getSubject());
        NotaFiscalDevolucaoResponse nota;
        try {
            nota = emissaoFiscal
                    .emitirSeAplicavel(idEmpresa, devolucao.idDevolucao(), idUsuario)
                    .map(r -> new NotaFiscalDevolucaoResponse(r.situacao().name(), r.idDocumentoFiscal(),
                            r.chaveAcesso(), r.protocolo(), r.cStat(), r.mensagem()))
                    .orElse(null);
        } catch (RuntimeException e) {
            // ⛔ SEM este catch, o javadoc acima mentia pela metade. "Falha na nota NÃO desfaz a
            // devolução" valia no BANCO (o serviço já commitou) e não valia na RESPOSTA: qualquer
            // exceção da emissão substituía o 201 inteiro, e com ele o `valorVale` — o número do
            // vale-mercadoria que o cliente leva embora.
            //
            // O caminho é do dia a dia, não é hipótese: cliente identificado com cadastro sem
            // logradouro/bairro (a NFC-e não exige, o cadastro aceita) faz
            // `DevolucaoFiscalAssembler.exigirEnderecoDoCliente` responder 409. O estoque voltou, o
            // vale nasceu, e o operador via um ERRO — refazia a devolução, e aí ou nascia um
            // SEGUNDO vale com o estoque voltando duas vezes, ou ele ficava preso, com um vale
            // existente que a tela não sabia mostrar.
            //
            // ⚠️ `RuntimeException` de propósito, e não uma lista de tipos: o que não pode acontecer
            // é a resposta se perder, qualquer que seja a causa. A nota fica registrada com a
            // situação real em Documentos Fiscais e é reprocessável — exatamente o que o javadoc
            // promete. A falha vai no campo `mensagem`, para a tela mostrar ao lado do vale.
            LOG.warn("Devolução {} gravada, mas a NF-e de devolução falhou — o vale foi preservado na resposta",
                    devolucao.idDevolucao(), e);
            nota = new NotaFiscalDevolucaoResponse("FALHA_NA_EMISSAO", 0L, null, null, null,
                    MotivoDeFalha.legivel(e, "Não foi possível emitir a NF-e de devolução."));
        }

        return new DevolucaoEfetivadaResponse(devolucao.idMovimento(), devolucao.idDevolucao(),
                devolucao.valorVale(), devolucao.dataMovimento(), devolucao.idFuncionario(),
                devolucao.nomeFuncionario(), devolucao.itens(), nota);
    }
}
