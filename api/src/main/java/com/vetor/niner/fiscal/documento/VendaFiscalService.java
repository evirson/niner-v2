package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.web.ConflitoDadosException;
import com.vetor.niner.fiscal.documento.EmissaoNfceService.ResultadoEmissao;
import com.vetor.niner.fiscal.nfse.NfseConfigService;
import com.vetor.niner.fiscal.nfse.NfseEmissaoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
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

    private static final Logger log = LoggerFactory.getLogger(VendaFiscalService.class);

    private final VendaFiscalAssembler assembler;
    private final EmissaoNfceService emissao;
    private final DocumentoFiscalRepositorio documentos;
    private final NfseEmissaoService nfseEmissao;
    private final NfseConfigService nfseConfig;

    public VendaFiscalService(VendaFiscalAssembler assembler, EmissaoNfceService emissao,
            DocumentoFiscalRepositorio documentos, NfseEmissaoService nfseEmissao,
            NfseConfigService nfseConfig) {
        this.assembler = assembler;
        this.emissao = emissao;
        this.documentos = documentos;
        this.nfseEmissao = nfseEmissao;
        this.nfseConfig = nfseConfig;
    }

    /**
     * @param incluirCpf 2026-08-19 — resposta do operador na pergunta "incluir CPF na nota?",
     *         perguntada antes de chamar este método (ver {@code ComprovantePapeletaModal.tsx});
     *         nunca mais decidido sozinho a partir do cliente da venda.
     * @return vazio quando o fiscal está desligado para a empresa (F12) — a tela não mostra nada,
     *         exatamente como se o módulo fiscal não existisse
     */
    public Optional<ResultadoEmissao> emitirNfce(Jwt jwt, long idVenda, boolean incluirCpf, String observacao) {
        long idTenant = ((Number) jwt.getClaim("tid")).longValue();
        long idEmpresa = ((Number) jwt.getClaim("eid")).longValue();
        Integer idUsuario = Integer.parseInt(jwt.getSubject());

        exigirVendaNaoCancelada(idEmpresa, idVenda);
        exigirVendaSemNota(idEmpresa, idVenda);

        // ⚠️ Medido ANTES de qualquer emissão, e uma vez só: as duas pernas precisam concordar
        // sobre o que a venda tem, e duas leituras é onde nasce divergência.
        boolean temMercadoria = documentos.temMercadoriaNaVenda(idEmpresa, idVenda);
        BigDecimal valorServicos = documentos.somarServicosDaVenda(idEmpresa, idVenda);
        boolean temServico = valorServicos != null && valorServicos.signum() > 0;

        // ---------- perna 1: a mercadoria (NFC-e 65 / NF-e 55) ----------
        // ⭐ Venda 100% serviço NÃO passa pelo montador (2026-09-01). Até aqui ela chegava lá e
        // levava 409 — "só tem serviços, imprima a papeleta" —, o que impedia o PDV de emitir
        // QUALQUER documento para o caso normal de petshop e de consultório.
        Optional<ResultadoEmissao> daMercadoria = temMercadoria
                ? assembler.montar(idTenant, idEmpresa, idVenda, idUsuario, incluirCpf, observacao).map(emissao::emitir)
                : Optional.empty();

        // ---------- perna 2: os serviços (N NFS-e, uma por código) ----------
        boolean nfseLigada = temServico
                && Boolean.TRUE.equals(nfseConfig.buscar(idEmpresa).emiteNfse());
        List<NfseEmissaoService.Resultado> daNfse = nfseLigada
                ? emitirServicos(idVenda)
                : List.of();

        // ---------- o que a tela vai mostrar ----------
        // ⚠️ O aviso descreve o que ficou SEM documento, e só existe quando a NFS-e está
        // DESLIGADA. Com ela ligada, uma nota que falha aparece na própria lista `nfse`, com o
        // motivo — repetir o aviso ali diria que falta configurar algo que já está configurado.
        String aviso = temServico && !nfseLigada
                ? avisoDeServicosForaDaNota(valorServicos, temMercadoria)
                : null;

        if (daMercadoria.isEmpty() && daNfse.isEmpty()) {
            // Nada saiu. Duas causas, dois desfechos — e confundi-las é o defeito de 26/08
            // ("40 notas sem XML"), em que duas populações com conselhos opostos viraram um aviso só:
            //
            //  (a) a venda não tem serviço nenhum e o fiscal está desligado  → 204, F12: a tela
            //      não mostra NADA, exatamente como se o módulo fiscal não existisse;
            //  (b) a venda TEM serviço e a NFS-e está desligada              → resultado com o
            //      aviso, porque aqui o silêncio é o defeito de 31/08 de novo: parte do
            //      faturamento sem documento e ninguém para contar ao operador.
            return aviso == null
                    ? Optional.empty()
                    : Optional.of(ResultadoEmissao.semMercadoria(idVenda, temMercadoria).comAvisoServicos(aviso));
        }

        return Optional.of(daMercadoria
                .orElseGet(() -> ResultadoEmissao.semMercadoria(idVenda, temMercadoria))
                .comAvisoServicos(aviso)
                .comNfse(daNfse));
    }

    /**
     * Emite as NFS-e da venda, <b>sem deixar a falha delas derrubar a NFC-e</b>.
     *
     * <p>⛔ São impostos diferentes, com autorizadores diferentes: a NFC-e autorizada pela SEFAZ
     * continua válida se o Sefin recusar a DPS, e o contrário também. Deixar a exceção subir daria
     * ao operador um erro no lugar do cupom que ele já tem na mão — e a venda já está registrada
     * (F3).
     *
     * <p>⚠️ Os 409 preventivos do {@code VendaNfseAssembler} (F11 — "falta a Inscrição Municipal",
     * "falta o código de serviço no cadastro") são exatamente o que o operador precisa ler, então
     * a mensagem é <b>preservada</b> e não trocada por um texto genérico. É a lição de
     * {@code feedback_front_engole_mensagem_de_erro_do_back}, aplicada do lado do servidor: o
     * trabalho de escrever a mensagem certa já está feito, seria invisível se eu a substituísse.
     */
    private List<NfseEmissaoService.Resultado> emitirServicos(long idVenda) {
        try {
            return nfseEmissao.emitirDaVenda(idVenda);
        } catch (ResponseStatusException e) {
            String motivo = e.getReason() != null ? e.getReason() : e.getMessage();
            log.warn("NFS-e da venda {} não emitida: {}", idVenda, motivo);
            return List.of(new NfseEmissaoService.Resultado(0L, "NAO_EMITIDA", null, null, null, motivo));
        } catch (RuntimeException e) {
            log.error("Falha inesperada ao emitir NFS-e da venda {}", idVenda, e);
            return List.of(new NfseEmissaoService.Resultado(0L, "NAO_EMITIDA", null, null, null,
                    "Não foi possível emitir a nota de serviço. A venda está registrada; "
                            + "tente de novo em Fiscal › Documentos Fiscais."));
        }
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
     * <p>⭐ <b>Desde 2026-09-01 (pendência #78) o PDV EMITE a NFS-e</b>, e este aviso mudou de
     * papel: ele descreve o que ficou <b>sem documento</b>, e só sobra quando a NFS-e está
     * <b>desligada</b> para a empresa. Com a NFS-e ligada, o serviço sai em nota e o aviso é
     * {@code null} — mantê-lo diria ao operador que falta algo que não falta, e aviso que aparece
     * sempre é aviso que ninguém lê.
     *
     * <p>Enquanto ela estiver desligada, o aviso continua valendo o que valia: troca "ele não
     * sabe" por "ele sabe", que é a diferença que importa numa fiscalização.
     *
     * @param temMercadoria muda a frase, não só o texto: numa venda mista a NFC-e cobriu
     *         <b>parte</b> do valor; numa venda 100% serviço <b>não saiu documento nenhum</b>, e
     *         dizer "a nota emitida cobre só as mercadorias" ali seria mentira — não há nota.
     */
    private String avisoDeServicosForaDaNota(BigDecimal valorServicos, boolean temMercadoria) {
        if (valorServicos == null || valorServicos.signum() <= 0) {
            return null;
        }
        String valor = valorServicos.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                .replace('.', ',');
        String comum = "Serviço é ISS municipal e tem documento próprio (NFS-e), que está "
                + "DESLIGADA para esta empresa. Ligue em Fiscal › Configuração da NFS-e, ou emita "
                + "pelo portal da sua prefeitura.";
        return temMercadoria
                ? "Esta venda tem R$ %s em SERVIÇOS, que não entram na NFC-e — a nota emitida cobre "
                        .formatted(valor) + "só as mercadorias. " + comum
                : "Esta venda é só de SERVIÇOS (R$ %s) e NÃO saiu documento fiscal nenhum. "
                        .formatted(valor) + comum;
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
    /**
     * Recusa emitir nota de uma venda <b>cancelada</b> (2026-09-02, pendência #84).
     *
     * <p>⚠️ <b>Por que agora:</b> até esta data o único caminho de tela até aqui era o PDV, logo
     * depois de efetivar a venda — que não tem como estar cancelada. Ao abrir a <b>primeira</b>
     * emissão pela papeleta reaberta (Pesquisa de Vendas), passou a existir um caminho para uma
     * venda antiga, e "a tela não oferece" nunca foi proteção (P4).
     *
     * <p>⛔ Emitir aqui declararia à SEFAZ uma operação <b>desfeita</b>, e o cancelamento da venda
     * não teria mais o que cancelar do lado fiscal — a nota nasceria válida contra nada.
     */
    private void exigirVendaNaoCancelada(long idEmpresa, long idVenda) {
        if (documentos.vendaCancelada(idEmpresa, idVenda)) {
            throw new ConflitoDadosException(
                    "A venda #" + idVenda + " foi cancelada — não é possível emitir nota fiscal para ela.");
        }
    }

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
