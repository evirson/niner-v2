package com.vetor.niner.integracao;

import com.vetor.niner.canais.CanalDeVenda;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.CredenciaisCanalRepositorio;
import com.vetor.niner.integracao.outbox.OutboxProcessador.ManipuladorDeEvento;
import com.vetor.niner.integracao.outbox.OutboxRepositorio.EventoPendente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Avisa o canal que o pedido foi despachado (M7) — o manipulador de {@code ENVIO_CONFIRMADO}.
 *
 * <p>⚠️ Passa pelo outbox porque toda escrita no canal passa (P2), e sobretudo porque o despacho
 * <b>já aconteceu no mundo físico</b> quando este evento nasce: uma indisponibilidade do
 * marketplace não pode impedir o lojista de registrar que o pacote saiu.
 *
 * <p>⚠️ Para o Mercado Livre este aviso é <b>deliberadamente inerte</b> — em Mercado Envios quem
 * controla o estado do envio é o próprio marketplace. Ver o javadoc de
 * {@code MercadoLivreAdapter.confirmarEnvio}. O manipulador existe assim mesmo porque a Shopee (e
 * o envio próprio) precisam dele, e porque é a interface que a spec definiu — descobrir isso na
 * hora de plugar o segundo canal seria tarde.
 */
@Component
public class EnvioConfirmadoManipulador implements ManipuladorDeEvento {

    private static final Logger log = LoggerFactory.getLogger(EnvioConfirmadoManipulador.class);

    private final ExpedicaoRepositorio expedicao;
    private final CredenciaisCanalRepositorio credenciais;
    private final List<CanalDeVenda> adapters;

    public EnvioConfirmadoManipulador(ExpedicaoRepositorio expedicao,
                                      CredenciaisCanalRepositorio credenciais,
                                      List<CanalDeVenda> adapters) {
        this.expedicao = expedicao;
        this.credenciais = credenciais;
        this.adapters = adapters;
    }

    @Override
    public String tipo() {
        return ExpedicaoService.EVENTO_ENVIO;
    }

    @Override
    public void executar(EventoPendente evento) {
        long idPedido = evento.payload().path("idPedido").asLong();
        if (idPedido == 0) {
            throw new IllegalArgumentException("Evento de envio sem idPedido: " + evento.payload());
        }

        var envio = expedicao.dadosDoEnvio(idPedido);
        CredenciaisCanal cred = credenciais.carregar(envio.idCanal()).orElse(null);
        if (cred == null) {
            // ⚠️ Não é falha: o lojista pode ter desconectado o canal depois de despachar. O
            // pacote saiu de qualquer forma, e o ERP já registrou isso.
            log.info("Pedido {} despachado, mas o canal {} não está mais conectado — nada a avisar.",
                    idPedido, envio.idCanal());
            return;
        }

        adapterDe(envio.tipoCanal()).confirmarEnvio(cred, envio.idExterno(),
                envio.idExternoEnvio(), envio.codigoRastreio());
    }

    private CanalDeVenda adapterDe(String tipo) {
        return adapters.stream()
                .filter(a -> a.tipo().name().equals(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Sem adapter para o canal " + tipo));
    }
}
