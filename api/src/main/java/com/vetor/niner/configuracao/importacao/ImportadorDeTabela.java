package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.vetor.niner.configuracao.importacao.ImportacaoCsv.LinhaCsv;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Um módulo de importação por tabela (docs/telas/importacao-dados.md). Cada implementação é um
 * bean Spring próprio, descoberto automaticamente por {@link ImportacaoService} (injeção de
 * {@code List<ImportadorDeTabela>>}) — liberar uma nova tabela não exige alterar o dispatcher,
 * só criar mais uma implementação.
 *
 * <p>{@link #processar} deve ser {@code @Transactional} na implementação e, ao final, lançar
 * {@link SimulacaoConcluidaException} quando {@code confirmar == false} (rollback automático de
 * tudo que foi gravado durante a simulação) — ver essa classe para o racional.
 */
public interface ImportadorDeTabela {

    /** Chave usada na URL e no relatório (ex.: {@code "cliente"}). */
    String chave();

    String titulo();

    String descricao();

    /** Modelo `.csv` (cabeçalho + linhas de exemplo), pronto para download. */
    byte[] modeloCsv();

    RelatorioImportacao processar(List<LinhaCsv> linhas, JsonNode escolhas, boolean confirmar, Jwt jwt);
}
