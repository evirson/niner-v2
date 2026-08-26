package com.vetor.niner.integracao;

import com.vetor.niner.canais.CanalDeVenda;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.CredenciaisCanalRepositorio;
import com.vetor.niner.canais.PrecoDoCanal;
import com.vetor.niner.integracao.SincronizacaoRepositorio.LinhaParaPublicar;
import com.vetor.niner.integracao.outbox.OutboxProcessador.ManipuladorDeEvento;
import com.vetor.niner.integracao.outbox.OutboxRepositorio.EventoPendente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Publica o preço do canal quando o lojista reajusta a tabela da loja (M3, R3) — o manipulador de
 * {@code PRECO_ATUALIZADO}.
 *
 * <h2>⭐ Por que o cálculo mora aqui, e não no gatilho que enfileirou</h2>
 *
 * Seria fácil recalcular {@code anuncio.preco} em SQL, dentro do próprio gatilho. Não fazemos: a
 * regra do preço do canal ({@link PrecoDoCanal}) arredonda <b>HALF_UP</b>, arredonda <b>uma vez no
 * fim</b> e recusa preço nulo. Reescrevê-la em plpgsql criaria <b>duas implementações da mesma
 * regra de dinheiro</b> (P7) — e elas divergem no dia em que só uma for corrigida, num centavo que
 * ninguém explica ao lojista. O gatilho avisa; o cálculo é único.
 *
 * <h2>⚠️ Preço digitado pelo lojista NÃO é tocado</h2>
 *
 * Só chegam aqui as linhas com {@code preco_manual = false}. É a lição de
 * {@code feedback_efeito_derivado_congela_valor_parcial}: guardar por <i>"o usuário editou"</i>,
 * nunca por <i>"eu já calculei"</i>. Sem essa marca, o dia do reajuste geral seria o dia em que
 * todo preço ajustado à mão no marketplace some <b>em silêncio</b>.
 *
 * <h2>⚠️ E a oferta da loja é ignorada, de propósito</h2>
 *
 * O preço deriva de {@code produto.preco_venda}, nunca de {@code preco_oferta} — decisão do dono
 * do produto (§8.6). Promoção de fim de semana no balcão não derruba o preço do anúncio; quem
 * quiser promoção no canal a faz lá. O gatilho da V067 nem dispara quando só a oferta muda.
 */
@Component
public class SincronizacaoPrecoManipulador implements ManipuladorDeEvento {

    private static final Logger log = LoggerFactory.getLogger(SincronizacaoPrecoManipulador.class);

    public static final String TIPO = "PRECO_ATUALIZADO";

    private final SincronizacaoRepositorio repositorio;
    private final CredenciaisCanalRepositorio credenciais;
    private final List<CanalDeVenda> adapters;

    public SincronizacaoPrecoManipulador(SincronizacaoRepositorio repositorio,
                                         CredenciaisCanalRepositorio credenciais,
                                         List<CanalDeVenda> adapters) {
        this.repositorio = repositorio;
        this.credenciais = credenciais;
        this.adapters = adapters;
    }

    @Override
    public String tipo() {
        return TIPO;
    }

    @Override
    public void executar(EventoPendente evento) {
        long idProduto = evento.payload().path("idProduto").asLong();
        if (idProduto == 0) {
            throw new IllegalArgumentException("Evento de preço sem idProduto: " + evento.payload());
        }

        List<LinhaParaPublicar> linhas = repositorio.linhasDerivadasDoProduto(idProduto);
        if (linhas.isEmpty()) {
            log.debug("Produto {} não tem anúncio derivado ativo — nada a publicar.", idProduto);
            return;
        }

        for (LinhaParaPublicar l : linhas) {
            // ⚠️ `precoVendaDaLoja`, e o nome do parâmetro de PrecoDoCanal.derivar é
            // `precoDeVendaDaLoja` justamente para que quem escrever o chamador leia a proibição:
            // nunca `preco_oferta`.
            BigDecimal novo = PrecoDoCanal.derivar(l.precoVendaDaLoja(), l.percPrecoDoCanal());
            if (novo == null) {
                // Preço de venda nulo/zero não vira anúncio a R$ 0,00 — publicar zero num
                // marketplace é pior que não publicar (§8.4).
                repositorio.marcarSincronizacao(List.of(l.idAnuncio()), "ERRO",
                        "Produto sem preço de venda: o anúncio não pode ser publicado a R$ 0,00.");
                continue;
            }

            CredenciaisCanal cred = credenciais.carregar(l.idCanal()).orElse(null);
            if (cred == null) {
                repositorio.marcarSincronizacao(List.of(l.idAnuncio()), "ERRO",
                        "Canal sem credencial. Reconecte o canal em Canais de Venda.");
                continue;
            }

            try {
                adapterDe(l.tipoCanal()).atualizarPreco(cred, l.idExterno(), l.idExternoVariacao(), novo);
                // ⚠️ Grava DEPOIS de publicar. Gravar antes deixaria o ERP afirmando um preço que
                // o canal recusou — e a tela mostraria "R$ 60,00 publicado" com R$ 50,00 no anúncio.
                repositorio.gravarPreco(l.idAnuncio(), novo);
                repositorio.marcarSincronizacao(List.of(l.idAnuncio()), "OK", null);
            } catch (RuntimeException e) {
                repositorio.marcarSincronizacao(List.of(l.idAnuncio()), "ERRO", e.getMessage());
                // Relança: quem decide entre reagendar e dead-letter é o processador. Engolir
                // faria o evento ser dado por processado e a retentativa nunca acontecer.
                throw e;
            }
        }
    }

    private CanalDeVenda adapterDe(String tipo) {
        return adapters.stream()
                .filter(a -> a.tipo().name().equals(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Sem adapter para o canal " + tipo + " — evento não pode ser despachado."));
    }
}
