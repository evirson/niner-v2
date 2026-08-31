package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.fiscal.documento.EmissaoNfceService.ResultadoEmissao;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    /** Modelo da NFC-e — a nota do PDV. A NF-e 55 da devolução não passa por aqui. */
    private static final int MODELO_NFCE = 65;

    private final VendaFiscalAssembler assembler;
    private final EmissaoNfceService emissao;
    private final DocumentoFiscalRepositorio documentos;

    public VendaFiscalService(VendaFiscalAssembler assembler, EmissaoNfceService emissao,
            DocumentoFiscalRepositorio documentos) {
        this.assembler = assembler;
        this.emissao = emissao;
        this.documentos = documentos;
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

        exigirVendaSemNota(idEmpresa, idVenda);
        return assembler.montar(idTenant, idEmpresa, idVenda, idUsuario, incluirCpf)
                .map(emissao::emitir)
                .map(r -> r.comAvisoServicos(avisoDeServicosForaDaNota(idEmpresa, idVenda)));
    }

    /**
     * ⭐ <b>Venda mista emite METADE do documento fiscal — e isso precisa ser DITO</b> (2026-08-31,
     * relato do dono do produto: *"a venda também tinha serviços, por que não emitiu a nota de
     * serviço?"*).
     *
     * <p>Serviço <b>não entra</b> na NFC-e — está certo: mercadoria é ICMS estadual e serviço é ISS
     * municipal, com documento próprio (NFS-e). O {@code VendaNfceAssembler} filtra os serviços
     * corretamente, e a nota da venda 628 saiu com as duas mercadorias e R$ 739,80, sem os R$ 310
     * de serviço. <b>O defeito não era a nota; era o silêncio.</b>
     *
     * <p>A emissão de NFS-e a partir do PDV ainda não existe (pendência #72 — a máquina está
     * pronta, falta o chamador). Até lá, o operador clicava em "emitir", via o cupom sair e ia
     * embora achando que a venda inteira estava documentada — com uma parte do faturamento sem
     * documento nenhum e ninguém para lhe contar. Um aviso não substitui a NFS-e, mas troca
     * "ele não sabe" por "ele sabe", que é a diferença que importa numa fiscalização.
     *
     * <p>Devolve {@code null} quando a venda não tem serviço — a esmagadora maioria das vendas —,
     * e aí a tela não mostra nada.
     */
    private String avisoDeServicosForaDaNota(long idEmpresa, long idVenda) {
        BigDecimal valorServicos = documentos.somarServicosDaVenda(idEmpresa, idVenda);
        if (valorServicos == null || valorServicos.signum() <= 0) {
            return null;
        }
        return ("Esta venda tem R$ %s em SERVIÇOS, que não entram na NFC-e — serviço é ISS "
                + "municipal e tem documento próprio (NFS-e). A nota emitida cobre só as "
                + "mercadorias. A emissão de NFS-e pelo PDV ainda não está disponível: por "
                + "enquanto, emita a nota de serviço pelo portal da sua prefeitura.")
                .formatted(valorServicos.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                        .replace('.', ','));
    }

    /**
     * Recusa a segunda emissão da mesma venda, <b>nomeando a nota que já existe</b>.
     *
     * <p>⭐ <b>Decisão do dono do produto (2026-08-27): recusar, não devolver a nota existente.</b>
     * O caixa vê o que houve — "esta venda já tem a NFC-e nº X" — em vez de receber em silêncio um
     * comprovante que ele não sabe se acabou de ser emitido.
     *
     * <p>⚠️ <b>Antes de reservar número.</b> O índice único da V082 é a última linha de defesa, e
     * chegar até ele significaria queimar um número da sequência para depois falhar — número
     * queimado vira buraco na numeração, que vira inutilização formal perante a SEFAZ.
     *
     * <p>⚠️ Só as situações <b>vivas</b> barram. Rejeitada, denegada, não emitida e cancelada
     * precisam permitir nova tentativa: nos três primeiros casos a nota nunca existiu, e no quarto
     * a operação foi desfeita perante a SEFAZ.
     */
    private void exigirVendaSemNota(long idEmpresa, long idVenda) {
        // ⛔ QUALQUER modelo, não só o 65 (auditoria 2026-08-29, rodada 4). Desde 2026-08-24 este
        // mesmo endpoint emite NF-e 55 quando o cliente é PJ — e quem decide é o assembler, DEPOIS
        // desta trava. Perguntando só pelo 65, a venda a PJ já autorizada passava direto num duplo
        // clique: o número da série da NF-e era QUEIMADO e o índice único da V082 devolvia 409
        // "Registro em uso por outro cadastro". Número queimado vira buraco de numeração e
        // obrigação de inutilização formal — exatamente o que esta guarda existe para evitar.
        String numero = documentos.numeroDaNotaVivaDaVendaQualquerModelo(idEmpresa, idVenda);
        if (numero != null) {
            throw new ConflitoDadosException(
                    "Esta venda já tem a nota fiscal nº " + numero + " emitida. Use a reimpressão em "
                            + "Pesquisa de Vendas.");
        }
    }
}
