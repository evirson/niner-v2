package com.vetor.niner.configuracao.importacao;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Isola o processamento de UMA linha de planilha num SAVEPOINT (transação {@code NESTED}) dentro
 * da transação grande do arquivo inteiro (2026-08-11, corrigindo bug real: validar a planilha de
 * Produtos quebrava com {@code current transaction is aborted, commands ignored until end of
 * transaction block}, Postgres 25P02). Sem isto, uma exceção vinda direto do banco (violação de
 * constraint, etc.) numa linha deixa a conexão inteira "abortada" e toda linha seguinte falha em
 * cascata, mesmo sendo válida — o {@code catch (RuntimeException e)} de cada
 * {@link ImportadorDeTabela} pegava a exceção em Java, mas nunca desfazia, no banco, o que a linha
 * ruim tentou gravar. {@code PROPAGATION_NESTED} exige {@code nestedTransactionAllowed=true} no
 * transaction manager (ver {@code TenantConfig}) e suporte a savepoint no driver JDBC (Postgres
 * tem).
 */
@Component
public class ImportacaoSavepointExecutor {

    private final TransactionTemplate nested;

    public ImportacaoSavepointExecutor(PlatformTransactionManager transactionManager) {
        this.nested = new TransactionTemplate(transactionManager);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public <T> T executar(Supplier<T> acao) {
        return nested.execute(status -> acao.get());
    }

    public void executarSemRetorno(Runnable acao) {
        nested.executeWithoutResult(status -> acao.run());
    }
}
