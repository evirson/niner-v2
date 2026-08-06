package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.vetor.niner.configuracao.importacao.ImportacaoCsv.LinhaCsv;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.TabelaImportavel;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Dispatcher da Rotina de Importação de Dados (docs/telas/importacao-dados.md). ADMIN-only,
 * verificado aqui uma única vez (em vez de repetir em cada {@link ImportadorDeTabela}).
 */
@Service
public class ImportacaoService {

    private final List<ImportadorDeTabela> importadores;
    private final ImportacaoLoteService loteService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImportacaoService(List<ImportadorDeTabela> importadores, ImportacaoLoteService loteService) {
        this.importadores = importadores;
        this.loteService = loteService;
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
        return localizar(tabela).modeloCsv();
    }

    public RelatorioImportacao processar(Jwt jwt, String tabela, MultipartFile arquivo, String escolhasJson, boolean confirmar) {
        exigirAdmin(jwt);
        ImportadorDeTabela importador = localizar(tabela);
        List<LinhaCsv> linhas = ImportacaoCsv.ler(arquivo);
        if (linhas.isEmpty()) {
            throw new IllegalArgumentException("Arquivo sem nenhuma linha de dados (só o cabeçalho).");
        }
        JsonNode escolhas = lerEscolhas(escolhasJson);

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
