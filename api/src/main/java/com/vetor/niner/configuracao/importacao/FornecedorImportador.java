package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.vetor.niner.cadastros.cliente.Documentos;
import com.vetor.niner.cadastros.fornecedor.FornecedorDtos.FornecedorRequest;
import com.vetor.niner.cadastros.fornecedor.FornecedorService;
import com.vetor.niner.cadastros.planocontas.PlanoContasDtos.PlanoContasRequest;
import com.vetor.niner.cadastros.planocontas.PlanoContasService;
import com.vetor.niner.configuracao.importacao.ImportacaoCsv.LinhaCsv;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.LinhaErro;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Importação de {@code fornecedor} (docs/telas/importacao-dados.md, seção "2. fornecedor"). Um
 * plano de contas único (existente, por código, ou novo, criado na hora com os campos mínimos)
 * vale para todo o arquivo — {@code fornecedor.id_plano_contas} é NOT NULL e o layout de origem
 * normalmente não tem essa classificação. Dedup por CNPJ, mesmo padrão do cliente.
 */
@Service
public class FornecedorImportador implements ImportadorDeTabela {

    private final JdbcClient jdbc;
    private final FornecedorService fornecedorService;
    private final PlanoContasService planoContasService;

    public FornecedorImportador(JdbcClient jdbc, FornecedorService fornecedorService, PlanoContasService planoContasService) {
        this.jdbc = jdbc;
        this.fornecedorService = fornecedorService;
        this.planoContasService = planoContasService;
    }

    @Override
    public String chave() {
        return "fornecedor";
    }

    @Override
    public String titulo() {
        return "Fornecedores";
    }

    @Override
    public String descricao() {
        return "Cadastro de fornecedores. Pede um plano de contas (existente ou novo) para o arquivo inteiro.";
    }

    @Override
    public byte[] modeloCsv() {
        String cabecalho = "RAZAO_SOCIAL;NOME_FANTASIA;CNPJ;INSCRICAO_ESTADUAL;EMAIL;TELEFONE;"
                + "ENDERECO;NUMERO;BAIRRO;CIDADE;ESTADO;CEP";
        String exemplo = "INDUSTRIA DE CALCADOS ABC LTDA;CALCADOS ABC;12.345.678/0001-95;;vendas@abc.com;"
                + "(11) 3222-1111;AV PAULISTA;2000;BELA VISTA;SAO PAULO;SP;01310-000";
        return (cabecalho + "\r\n" + exemplo + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional
    public RelatorioImportacao processar(List<LinhaCsv> linhas, JsonNode escolhas, boolean confirmar, Jwt jwt) {
        String idPlanoContas = resolverPlanoContas(escolhas);

        int importadas = 0, ignoradas = 0;
        List<LinhaErro> erros = new ArrayList<>();

        for (LinhaCsv linha : linhas) {
            try {
                String cnpjNormalizado = Documentos.somenteAlfanumerico(linha.valor("CNPJ"));
                if (cnpjNormalizado != null && !cnpjNormalizado.isBlank() && jaExiste(cnpjNormalizado)) {
                    ignoradas++;
                    continue;
                }
                fornecedorService.criar(montarRequest(linha, idPlanoContas));
                importadas++;
            } catch (RuntimeException e) {
                erros.add(LinhaErro.de(linha.numeroLinha(), e));
            }
        }

        RelatorioImportacao relatorio =
                RelatorioImportacao.concluir(confirmar, linhas.size(), importadas, ignoradas, erros, List.of());
        if (!relatorio.confirmado()) {
            throw new SimulacaoConcluidaException(relatorio);
        }
        return relatorio;
    }

    /**
     * {@code escolhas}: {@code {"idPlanoContas": "3.03.001.000"}} OU
     * {@code {"novoPlanoContas": {"codigo": "...", "descricao": "...", "tipoMovimento": "DEBITO", "natureza": "ANALITICA"}}}
     * — o plano novo nasce sem DRE/fluxo de caixa (o usuário pode completar a classificação
     * depois, na tela de Plano de Contas).
     */
    private String resolverPlanoContas(JsonNode escolhas) {
        if (escolhas != null && escolhas.hasNonNull("idPlanoContas")) {
            String codigo = escolhas.get("idPlanoContas").asText("").trim().toUpperCase(Locale.ROOT);
            if (codigo.isBlank()) {
                throw new IllegalArgumentException("Informe o código do plano de contas.");
            }
            return codigo;
        }
        if (escolhas != null && escolhas.hasNonNull("novoPlanoContas")) {
            JsonNode n = escolhas.get("novoPlanoContas");
            String codigo = textoObrigatorio(n, "codigo", "Código do novo plano de contas");
            String descricao = textoObrigatorio(n, "descricao", "Descrição do novo plano de contas");
            String tipoMovimento = textoObrigatorio(n, "tipoMovimento", "Tipo de movimento do novo plano de contas");
            String natureza = textoObrigatorio(n, "natureza", "Natureza do novo plano de contas");
            planoContasService.criar(new PlanoContasRequest(
                    codigo, descricao, null, tipoMovimento, natureza,
                    false, null, false, null, false, false, false, null, null,
                    "Criado pela Rotina de Importação de Dados."));
            return codigo.trim().toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException(
                "Escolha um plano de contas existente ou informe os dados de um novo antes de importar.");
    }

    private static String textoObrigatorio(JsonNode n, String campo, String rotulo) {
        if (n == null || !n.hasNonNull(campo) || n.get(campo).asText("").isBlank()) {
            throw new IllegalArgumentException(rotulo + " é obrigatório.");
        }
        return n.get(campo).asText().trim();
    }

    private boolean jaExiste(String cnpjNormalizado) {
        return jdbc.sql("""
                        SELECT id_fornecedor FROM fornecedor
                        WHERE id_tenant = plataforma.tenant_atual() AND cnpj = ?
                        """)
                .param(cnpjNormalizado)
                .query(Long.class).optional().isPresent();
    }

    private static FornecedorRequest montarRequest(LinhaCsv linha, String idPlanoContas) {
        String razaoSocial = linha.valor("RAZAO_SOCIAL");
        if (razaoSocial == null) {
            throw new IllegalArgumentException("RAZAO_SOCIAL é obrigatório.");
        }
        return new FornecedorRequest(
                razaoSocial,
                idPlanoContas,
                linha.valor("NOME_FANTASIA"),
                linha.valor("CNPJ"),
                linha.valor("INSCRICAO_ESTADUAL"),
                ImportacaoCsv.semEspacos(linha.valor("EMAIL")),
                linha.valor("TELEFONE"),
                linha.valor("CEP"),
                linha.valor("ENDERECO"),
                linha.valor("NUMERO"),
                linha.valor("BAIRRO"),
                linha.valor("CIDADE"),
                linha.valor("ESTADO"),
                true);
    }
}
