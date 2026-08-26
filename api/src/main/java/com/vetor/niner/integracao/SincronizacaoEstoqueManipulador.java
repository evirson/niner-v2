package com.vetor.niner.integracao;

import com.vetor.niner.canais.CanalDeVenda;
import com.vetor.niner.canais.CanalDeVenda.SaldoAnuncio;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.CredenciaisCanalRepositorio;
import com.vetor.niner.integracao.SincronizacaoRepositorio.LinhaParaPublicar;
import com.vetor.niner.integracao.outbox.OutboxProcessador.ManipuladorDeEvento;
import com.vetor.niner.integracao.outbox.OutboxRepositorio.EventoPendente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica o saldo do ERP no marketplace (M3, R3) — o manipulador de {@code ESTOQUE_ATUALIZADO}.
 *
 * <p>⭐ <b>É este bloco que transforma a integração em produto.</b> Antes dele o ERP conectava e
 * vinculava, e não publicava nada: o outbox tinha worker, retry e dead-letter funcionando, mas
 * nenhum manipulador registrado — um evento que chegasse à fila virava dead-letter com "nenhum
 * manipulador registrado".
 *
 * <h2>⛔ Duas conversões que só podem errar para um lado</h2>
 *
 * <ol>
 *   <li><b>Fracionário vira inteiro para BAIXO.</b> {@code qtd_estoque} é
 *       {@code numeric(14,3)} (o produto aceita quantidade decimal) e o ML só entende
 *       {@code available_quantity} inteiro. 2,7 vira <b>2</b>, nunca 3: arredondar para cima
 *       promete uma peça que não existe, que é literalmente o overselling que o P1 proíbe.</li>
 *   <li><b>Negativo vira zero.</b> Estoque negativo é permitido no ERP (é parâmetro, e nasce
 *       ligado — V055), mas "−3 disponíveis" não significa nada num anúncio, e o ML recusaria.
 *       Zero é a tradução honesta: não há o que vender.</li>
 * </ol>
 *
 * <h2>⚠️ Manda o anúncio inteiro, não só a variação que mudou</h2>
 *
 * No ML, um {@code PUT} em {@code variations} <b>apaga as que não forem enviadas</b>. O adapter já
 * lê o anúncio antes de escrever como defesa, mas mandar daqui só uma das doze variações ligadas
 * seria confiar a integridade do anúncio do lojista inteiramente àquela segunda linha de defesa.
 */
@Component
public class SincronizacaoEstoqueManipulador implements ManipuladorDeEvento {

    private static final Logger log = LoggerFactory.getLogger(SincronizacaoEstoqueManipulador.class);

    public static final String TIPO = "ESTOQUE_ATUALIZADO";

    private final SincronizacaoRepositorio repositorio;
    private final CredenciaisCanalRepositorio credenciais;
    private final List<CanalDeVenda> adapters;

    public SincronizacaoEstoqueManipulador(SincronizacaoRepositorio repositorio,
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
        long idVariacao = evento.payload().path("idVariacao").asLong();
        long idEmpresa = evento.payload().path("idEmpresa").asLong();
        if (idVariacao == 0 || idEmpresa == 0) {
            // Payload inválido é definitivo: nenhuma espera conserta. Vai ao dead-letter.
            throw new IllegalArgumentException(
                    "Evento de estoque sem idVariacao/idEmpresa: " + evento.payload());
        }

        List<LinhaParaPublicar> linhas = repositorio.linhasDoItemDaVariacao(idVariacao, idEmpresa);
        if (linhas.isEmpty()) {
            // Não é erro: o vínculo pode ter sido desfeito, ou o canal desconectado, entre o
            // movimento de estoque e o despacho. Nada a publicar é sucesso.
            log.debug("Estoque da variação {} não tem anúncio vinculado ativo — nada a publicar.", idVariacao);
            return;
        }

        // Agrupa por (canal, anúncio no canal): um PUT por anúncio, com TODAS as variações ligadas.
        Map<String, List<LinhaParaPublicar>> porItem = new LinkedHashMap<>();
        for (LinhaParaPublicar l : linhas) {
            porItem.computeIfAbsent(l.idCanal() + " " + l.idExterno(), k -> new ArrayList<>()).add(l);
        }

        for (List<LinhaParaPublicar> doItem : porItem.values()) {
            publicarItem(doItem);
        }
    }

    private void publicarItem(List<LinhaParaPublicar> doItem) {
        LinhaParaPublicar referencia = doItem.getFirst();
        List<Long> ids = doItem.stream().map(LinhaParaPublicar::idAnuncio).toList();

        CredenciaisCanal cred = credenciais.carregar(referencia.idCanal()).orElse(null);
        if (cred == null) {
            // Canal conectado sem credencial não deveria existir; se existir, é caso para gente.
            repositorio.marcarSincronizacao(ids, "ERRO",
                    "Canal sem credencial. Reconecte o canal em Canais de Venda.");
            return;
        }

        List<SaldoAnuncio> saldos = doItem.stream()
                .map(l -> new SaldoAnuncio(l.idVariacao(), l.idExterno(), l.idExternoVariacao(),
                        paraInteiroPublicavel(l.disponivel())))
                .toList();

        try {
            adapterDe(referencia.tipoCanal()).atualizarEstoque(cred, referencia.idExterno(), saldos);
            repositorio.marcarSincronizacao(ids, "OK", null);
        } catch (RuntimeException e) {
            // ⚠️ Marca o anúncio e RELANÇA. Marcar e engolir faria o outbox achar que deu certo:
            // o evento seria dado por processado e a retentativa nunca aconteceria — o anúncio
            // ficaria com "ERRO" na tela e ninguém tentaria de novo. Quem decide entre reagendar
            // (transitório) e dead-letter (definitivo) é o processador, pelo tipo da exceção.
            repositorio.marcarSincronizacao(ids, "ERRO", e.getMessage());
            throw e;
        }
    }

    /**
     * O saldo do ERP no formato que o canal aceita.
     *
     * <p>Ver a nota da classe: para baixo, e nunca negativo. As duas conversões erram de propósito
     * para o lado de <b>prometer menos</b> — vender menos do que se tem custa uma venda; vender
     * mais do que se tem custa a reputação do lojista no marketplace.
     */
    public static int paraInteiroPublicavel(BigDecimal disponivel) {
        if (disponivel == null || disponivel.signum() <= 0) {
            return 0;
        }
        return disponivel.setScale(0, RoundingMode.DOWN).intValueExact();
    }

    private CanalDeVenda adapterDe(String tipo) {
        return adapters.stream()
                .filter(a -> a.tipo().name().equals(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Sem adapter para o canal " + tipo + " — evento não pode ser despachado."));
    }
}
