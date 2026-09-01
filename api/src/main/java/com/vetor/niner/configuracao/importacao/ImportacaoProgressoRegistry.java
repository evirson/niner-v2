package com.vetor.niner.configuracao.importacao;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro em memória do progresso "ao vivo" de importações em andamento (2026-08-10), consultado
 * por polling do frontend enquanto a chamada síncrona de {@link ImportacaoService#processar}
 * roda — não há fila nem worker, é só um contador por {@code idProgresso} (gerado pelo frontend a
 * cada clique em Validar/Importar), removido ao final da requisição
 * ({@code finally} em {@link ImportacaoService}). A rotina já é "um arquivo por vez" por tela
 * (docs/telas/importacao-dados.md), então não precisa sobreviver a reinício da API nem a mais de
 * um processo por tenant simultâneo.
 */
@Component
public class ImportacaoProgressoRegistry {

    /**
     * ⚠️ A chave carrega o <b>tenant</b> junto (P8, 2026-09-01). O {@code idProgresso} é gerado
     * pelo <b>navegador</b> e chega por path — sem o tenant na chave, o admin de um tenant que
     * conhecesse o id de outro leria o progresso da importação alheia. O que vazava eram três
     * campos ("importando", atual, total) e o id é um UUID efêmero, então o risco prático era
     * baixo — mas P8 não tem exceção de tamanho: <b>nenhuma consulta cruza a fronteira</b>, e
     * fechar aqui custou uma linha.
     */
    private record Chave(long idTenant, String idProgresso) {
    }

    private final Map<Chave, ProgressoImportacao> estados = new ConcurrentHashMap<>();

    public ProgressoImportacao iniciar(long idTenant, String idProgresso) {
        ProgressoImportacao progresso = new ProgressoImportacao();
        estados.put(new Chave(idTenant, idProgresso), progresso);
        return progresso;
    }

    public Optional<ProgressoImportacao> obter(long idTenant, String idProgresso) {
        return Optional.ofNullable(estados.get(new Chave(idTenant, idProgresso)));
    }

    public void finalizar(long idTenant, String idProgresso) {
        estados.remove(new Chave(idTenant, idProgresso));
    }
}
