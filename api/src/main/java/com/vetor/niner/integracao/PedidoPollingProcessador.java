package com.vetor.niner.integracao;

import com.vetor.niner.canais.CanalDeVenda;
import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import com.vetor.niner.canais.CredenciaisCanal;
import com.vetor.niner.canais.CredenciaisCanalRepositorio;
import com.vetor.niner.integracao.PedidoImportacaoService.PedidoNaoImportavelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * O polling de segurança (M5) — busca os pedidos recentes de cada canal conectado.
 *
 * <p>⭐ Existe porque <b>webhook não é garantia</b>: em dev não há URL pública, os tópicos podem
 * estar desmarcados no painel, e plataforma desativa callback que falha repetidamente. Ver o
 * javadoc de {@link PedidoJob#buscarPedidosRecentes()}.
 *
 * <p>⚠️ <b>Sem {@code @Transactional} nesta fachada</b>, e é decisão: o laço faz chamadas HTTP ao
 * marketplace, e uma transação em volta de todas seguraria uma conexão do pool durante o passeio
 * inteiro — além de fazer a falha do último canal desfazer a importação dos anteriores. Quem
 * transaciona são os repositórios, um passo por vez.
 */
@Component
public class PedidoPollingProcessador {

    private static final Logger log = LoggerFactory.getLogger(PedidoPollingProcessador.class);

    /**
     * Quantos pedidos recentes olhar por canal, por rodada.
     *
     * <p>Vinte a cada quinze minutos cobre com folga uma loja pequena — que é o cliente deste ERP.
     * ⚠️ E o custo de reolhar um pedido já importado é <b>zero em gravação</b>: a chave natural
     * {@code (canal, id_externo)} faz a segunda passada só atualizar status. O que ela custa é uma
     * chamada HTTP, e o limite do ML é 1.500/min por vendedor.
     */
    private static final int RECENTES = 20;

    private final PedidoWebhookRepositorio canais;
    private final CredenciaisCanalRepositorio credenciais;
    private final PedidoImportacaoService importacao;
    private final List<CanalDeVenda> adapters;

    public PedidoPollingProcessador(PedidoWebhookRepositorio canais,
                                    CredenciaisCanalRepositorio credenciais,
                                    PedidoImportacaoService importacao,
                                    List<CanalDeVenda> adapters) {
        this.canais = canais;
        this.credenciais = credenciais;
        this.importacao = importacao;
        this.adapters = adapters;
    }

    /** @return quantos pedidos foram importados (novos ou atualizados) */
    public int varrerTenantCorrente() {
        int importados = 0;
        for (Long idCanal : canais.canaisConectados()) {
            try {
                importados += varrerCanal(idCanal);
            } catch (CanalIndisponivelException e) {
                // Transitório: a próxima rodada tenta. Um canal fora do ar não pode parar os outros.
                log.warn("Polling: canal {} indisponível — {}", idCanal, e.getMessage());
            } catch (RuntimeException e) {
                log.error("Polling: canal {} falhou — {}", idCanal, e.toString());
            }
        }
        return importados;
    }

    private int varrerCanal(long idCanal) {
        CredenciaisCanal cred = credenciais.carregar(idCanal).orElse(null);
        if (cred == null) {
            return 0;
        }
        String tipo = canais.tipoDoCanal(idCanal);
        List<String> ids = adapterDe(tipo).idsDePedidosRecentes(cred, RECENTES);

        int importados = 0;
        for (String idExterno : ids) {
            try {
                importacao.importar(idCanal, tipo, idExterno);
                importados++;
            } catch (PedidoNaoImportavelException e) {
                // ⚠️ Silencioso no nível de INFO, de propósito: enquanto o lojista não vincular o
                // anúncio, ESTE MESMO pedido vai reaparecer a cada 15 minutos. Registrar como erro
                // encheria o log de uma pendência que já está visível na tela — e log que grita
                // todo dia é log que ninguém lê.
                log.debug("Polling: pedido {} do canal {} aguardando vínculo — {}",
                        idExterno, idCanal, e.getMessage());
            }
        }
        return importados;
    }

    private CanalDeVenda adapterDe(String tipo) {
        return adapters.stream()
                .filter(a -> a.tipo().name().equals(tipo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Sem adapter para o canal " + tipo));
    }
}
