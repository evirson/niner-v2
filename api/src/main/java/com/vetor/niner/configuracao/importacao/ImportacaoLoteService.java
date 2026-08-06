package com.vetor.niner.configuracao.importacao;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Auditoria (P3, V030) de cada execução confirmada da Rotina de Importação de Dados. */
@Service
public class ImportacaoLoteService {

    private final JdbcClient jdbc;

    public ImportacaoLoteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void registrar(Jwt jwt, String tabela, String nomeArquivo, int totalLinhas,
                           int linhasImportadas, int linhasRejeitadas) {
        jdbc.sql("""
                        INSERT INTO importacao_lote
                            (id_tenant, tabela, nome_arquivo, id_usuario, total_linhas, linhas_importadas, linhas_rejeitadas)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?)
                        """)
                .params(tabela, nomeArquivo == null ? "arquivo.csv" : nomeArquivo, Long.parseLong(jwt.getSubject()),
                        totalLinhas, linhasImportadas, linhasRejeitadas)
                .update();
    }
}
