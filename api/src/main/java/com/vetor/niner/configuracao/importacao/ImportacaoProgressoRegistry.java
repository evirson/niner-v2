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

    private final Map<String, ProgressoImportacao> estados = new ConcurrentHashMap<>();

    public ProgressoImportacao iniciar(String idProgresso) {
        ProgressoImportacao progresso = new ProgressoImportacao();
        estados.put(idProgresso, progresso);
        return progresso;
    }

    public Optional<ProgressoImportacao> obter(String idProgresso) {
        return Optional.ofNullable(estados.get(idProgresso));
    }

    public void finalizar(String idProgresso) {
        estados.remove(idProgresso);
    }
}
