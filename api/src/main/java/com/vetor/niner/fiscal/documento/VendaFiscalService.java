package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.EmissaoNfceService.ResultadoEmissao;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Ponte entre a venda do PDV e a emissão fiscal (§9.6, bloco B7).
 *
 * <p>Orquestra {@link VendaFiscalAssembler} (lê a venda, monta o pedido — transação curta e
 * própria) e {@link EmissaoNfceService} (assina e transmite — transações próprias separadas pela
 * ida à SEFAZ). As duas etapas <b>têm que</b> ser beans diferentes: ver
 * [[feedback_transactional_chamada_interna_rls]] — chamar um método {@code @Transactional} de
 * dentro da própria classe não abre transação, e aqui isso silenciaria o RLS.
 */
@Service
public class VendaFiscalService {

    private final VendaFiscalAssembler assembler;
    private final EmissaoNfceService emissao;

    public VendaFiscalService(VendaFiscalAssembler assembler, EmissaoNfceService emissao) {
        this.assembler = assembler;
        this.emissao = emissao;
    }

    /**
     * @param incluirCpf 2026-08-19 — resposta do operador na pergunta "incluir CPF na nota?",
     *         perguntada antes de chamar este método (ver {@code ComprovantePapeletaModal.tsx});
     *         nunca mais decidido sozinho a partir do cliente da venda.
     * @return vazio quando o fiscal está desligado para a empresa (F12) — a tela não mostra nada,
     *         exatamente como se o módulo fiscal não existisse
     */
    public Optional<ResultadoEmissao> emitirNfce(Jwt jwt, long idVenda, boolean incluirCpf) {
        long idTenant = ((Number) jwt.getClaim("tid")).longValue();
        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();
        Integer idUsuario = Integer.parseInt(jwt.getSubject());

        return assembler.montar(idTenant, idEmpresa, idVenda, idUsuario, incluirCpf).map(emissao::emitir);
    }
}
