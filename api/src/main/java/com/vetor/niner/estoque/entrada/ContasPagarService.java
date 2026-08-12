package com.vetor.niner.estoque.entrada;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * `contas_pagar` (V026) — schema e RLS prontos desde 16/07, mas nenhum service Java tocava essa
 * tabela até aqui. Decisão do dono do produto (2026-08-11): a Entrada de Produtos por Compra só
 * GRAVA as linhas (a partir das duplicatas do XML ou de um lançamento manual opcional) — sem
 * tela própria de consulta/baixa por enquanto. Por isso este service é deliberadamente mínimo:
 * um único método de INSERT, sem controller, sem listar/atualizar/excluir.
 */
@Service
public class ContasPagarService {

    private final JdbcClient jdbc;

    public ContasPagarService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Grava 1 duplicata. Chamado dentro da mesma transação de
     *  {@link EntradaMercadoriaService#efetivar}, nunca isoladamente. {@code idMovimento}
     *  (2026-08-19) é o vínculo que o Cancelamento de Entrada usa pra estornar exatamente as
     *  duplicatas desta entrada — antes não existia, e nota_fiscal+fornecedor não é
     *  garantidamente único o bastante pra isso. */
    @Transactional
    public void gravar(long idEmpresa, long idFornecedor, String idPlanoContas, long idMovimento, Integer notaFiscal,
                        String numeroDuplicata, LocalDate dataVencimento, BigDecimal valor) {
        jdbc.sql("""
                        INSERT INTO contas_pagar
                            (id_tenant, id_empresa, id_fornecedor, id_plano_contas, id_movimento, nota_fiscal,
                             numero_duplicata, data_lancamento, data_vencimento, valor_pagar)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, now(), ?, ?)
                        """)
                .params(idEmpresa, idFornecedor, idPlanoContas, idMovimento, notaFiscal, numeroDuplicata,
                        dataVencimento, valor)
                .update();
    }
}
