package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.vetor.niner.configuracao.importacao.ImportacaoPlanilha.LinhaPlanilha;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.DeteccaoArquivo;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.ProgressoResponse;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.TabelaImportavel;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dispatcher da Rotina de Importação de Dados (docs/telas/importacao-dados.md). ADMIN-only,
 * verificado aqui uma única vez (em vez de repetir em cada {@link ImportadorDeTabela}).
 */
@Service
public class ImportacaoService {

    private final List<ImportadorDeTabela> importadores;
    private final ImportacaoLoteService loteService;
    private final ImportacaoProgressoRegistry progressoRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImportacaoService(List<ImportadorDeTabela> importadores, ImportacaoLoteService loteService,
                              ImportacaoProgressoRegistry progressoRegistry) {
        this.importadores = importadores;
        this.loteService = loteService;
        this.progressoRegistry = progressoRegistry;
    }

    /** Consultado por polling do frontend enquanto {@link #processar} está em voo (2026-08-10) —
     *  ver {@link ImportacaoProgressoRegistry}. Devolve {@code etapa="desconhecido"} quando o id
     *  não existe (ainda não começou, ou já terminou) — não é erro, o front simplesmente ignora. */
    public ProgressoResponse progresso(Jwt jwt, String idProgresso) {
        exigirAdmin(jwt);
        return progressoRegistry.obter(idTenantDe(jwt), idProgresso)
                .map(p -> new ProgressoResponse(p.etapa(), p.atual(), p.total()))
                .orElseGet(() -> new ProgressoResponse("desconhecido", 0, 0));
    }

    /**
     * O tenant do token, usado para chavear o progresso (P8).
     *
     * <p>⚠️ Vem do <b>claim</b>, nunca do corpo ou do path: o {@code idProgresso} é gerado pelo
     * navegador e não prova nada sobre quem está perguntando.
     */
    private static long idTenantDe(Jwt jwt) {
        return ((Number) jwt.getClaim("tid")).longValue();
    }

    /** Exposto para o endpoint de análise prévia de produto (fora da interface genérica, ver
     *  {@link ProdutoImportador#analisar}), que também é ADMIN-only. */
    public void validarAdmin(Jwt jwt) {
        exigirAdmin(jwt);
    }

    public List<TabelaImportavel> listarTabelas(Jwt jwt) {
        exigirAdmin(jwt);
        return importadores.stream()
                .map(i -> new TabelaImportavel(i.chave(), i.titulo(), i.descricao()))
                .toList();
    }

    public byte[] modelo(Jwt jwt, String tabela) {
        exigirAdmin(jwt);
        return localizar(tabela).modeloPlanilha();
    }

    /**
     * Detecção automática de tipo de arquivo (2026-08-10, tela única de importação): compara o
     * cabeçalho do arquivo enviado com {@link ImportadorDeTabela#colunasEsperadas()} de cada
     * tabela cadastrada por similaridade de Jaccard (interseção / união das colunas) — não por
     * contagem bruta, pra não favorecer tabelas com cabeçalho grande (produto, com 33 colunas)
     * sobre uma tabela pequena (fornecedor, 12) só por acaso. Só considera detectado quando a
     * melhor combinação é boa o bastante (≥ 50% de similaridade) E claramente melhor que a
     * segunda colocada (margem ≥ 10 pontos) — evita "adivinhar" entre duas tabelas parecidas
     * (cliente/fornecedor dividem 8 colunas de endereço) quando o arquivo não é nítido o
     * suficiente; nesse caso a tela pede escolha manual.
     */
    public DeteccaoArquivo detectar(Jwt jwt, MultipartFile arquivo) {
        exigirAdmin(jwt);
        List<String> colunasArquivo = ImportacaoPlanilha.lerCabecalho(arquivo);
        Set<String> doArquivo = new HashSet<>(colunasArquivo);

        String melhorTabela = null;
        double melhorScore = 0;
        double segundoMelhorScore = 0;
        for (ImportadorDeTabela importador : importadores) {
            Set<String> esperadas = new HashSet<>(importador.colunasEsperadas());
            Set<String> intersecao = new HashSet<>(doArquivo);
            intersecao.retainAll(esperadas);
            Set<String> uniao = new HashSet<>(doArquivo);
            uniao.addAll(esperadas);
            double score = uniao.isEmpty() ? 0 : (double) intersecao.size() / uniao.size();
            if (score > melhorScore) {
                segundoMelhorScore = melhorScore;
                melhorScore = score;
                melhorTabela = importador.chave();
            } else if (score > segundoMelhorScore) {
                segundoMelhorScore = score;
            }
        }
        boolean confiavel = melhorScore >= 0.5 && (melhorScore - segundoMelhorScore) >= 0.1;
        return new DeteccaoArquivo(confiavel ? melhorTabela : null, colunasArquivo);
    }

    /** {@code idProgresso} é gerado pelo frontend a cada clique em Validar/Importar (um id novo
     *  por chamada) e usado só para o polling de progresso (2026-08-10) — {@code null}/vazio
     *  ainda funciona (gera um id descartável), então a rotina continua utilizável por qualquer
     *  chamador que não precise acompanhar progresso (ex.: um teste automatizado futuro). */
    public RelatorioImportacao processar(Jwt jwt, String tabela, MultipartFile arquivo, String escolhasJson,
                                          boolean confirmar, String idProgresso) {
        exigirAdmin(jwt);
        ImportadorDeTabela importador = localizar(tabela);
        String id = (idProgresso == null || idProgresso.isBlank()) ? UUID.randomUUID().toString() : idProgresso;
        ProgressoImportacao progresso = progressoRegistry.iniciar(idTenantDe(jwt), id);
        try {
            return ImportacaoProgressoContext.comProgresso(progresso, () -> {
                List<LinhaPlanilha> linhas = ImportacaoPlanilha.ler(arquivo);
                if (linhas.isEmpty()) {
                    throw new IllegalArgumentException("Arquivo sem nenhuma linha de dados (só o cabeçalho).");
                }
                JsonNode escolhas = lerEscolhas(escolhasJson);
                ImportacaoProgressoContext.etapa(confirmar ? "importando" : "validando", linhas.size());

                RelatorioImportacao relatorio;
                try {
                    relatorio = importador.processar(linhas, escolhas, confirmar, jwt);
                } catch (SimulacaoConcluidaException simulacao) {
                    return simulacao.relatorio();
                }

                // Só chega aqui quando confirmar=true e a transação do importador já foi commitada.
                loteService.registrar(jwt, tabela, arquivo.getOriginalFilename(),
                        relatorio.totalLinhas(), relatorio.linhasImportadas(), relatorio.linhasRejeitadas());
                return relatorio;
            });
        } finally {
            progressoRegistry.finalizar(idTenantDe(jwt), id);
        }
    }

    private ImportadorDeTabela localizar(String tabela) {
        return importadores.stream()
                .filter(i -> i.chave().equalsIgnoreCase(tabela))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tabela de importação desconhecida: \"" + tabela + "\"."));
    }

    private JsonNode lerEscolhas(String escolhasJson) {
        if (escolhasJson == null || escolhasJson.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(escolhasJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("As escolhas prévias enviadas não são um JSON válido.");
        }
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem usar a rotina de importação de dados.");
        }
    }
}
