package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.vetor.niner.cadastros.cliente.Documentos;
import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.LinhaErro;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import com.vetor.niner.configuracao.importacao.ImportacaoPlanilha.LinhaPlanilha;
import com.vetor.niner.financeiro.TipoCarteiraDtos.CategoriaCarteira;
import com.vetor.niner.financeiro.TipoCarteiraDtos.TipoCarteiraRequest;
import com.vetor.niner.financeiro.TipoCarteiraService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Importação de {@code contas_receber} — só crediário (docs/telas/importacao-dados.md, seção
 * "4. contas_receber"). Uma carteira de crediário única (existente ou nova) vale para todo o
 * arquivo. O cliente é resolvido por CPF/CNPJ contra a base ao vivo (não depende de
 * {@code cliente.csv} ter sido importado na mesma sessão); a empresa, pelo {@code codigo_empresa}
 * (2026-08-06 — trocado de nome/fantasia pra código: mais curto de digitar na planilha e sem
 * ambiguidade de maiúscula/acento). As linhas são agrupadas por (cliente, empresa) e cada grupo
 * gera UMA venda sintética
 * ({@code tipo_operacao='VENDA'} default, sem item associado) com {@code data_venda} = menor
 * vencimento do grupo — contorna {@code contas_receber.id_venda NOT NULL} sem alterar o schema.
 * Parcela já paga (tem {@code DATA_RECEBIMENTO}) nasce marcada como recebida, mas NUNCA gera
 * {@code caixa_detalhe} — lançar caixa retroativo quebraria a conciliação do Fechamento de Caixa.
 */
@Service
public class ContasReceberImportador implements ImportadorDeTabela {

    private final JdbcClient jdbc;
    private final TipoCarteiraService tipoCarteiraService;
    private final ImportacaoSavepointExecutor savepoints;
    private final FusoDaLoja fusoDaLoja;

    public ContasReceberImportador(JdbcClient jdbc, TipoCarteiraService tipoCarteiraService, ImportacaoSavepointExecutor savepoints, FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.tipoCarteiraService = tipoCarteiraService;
        this.savepoints = savepoints;
        this.fusoDaLoja = fusoDaLoja;
    }

    @Override
    public String chave() {
        return "contas_receber";
    }

    @Override
    public String titulo() {
        return "Contas a Receber (crediário)";
    }

    @Override
    public String descricao() {
        return "Parcelas de crediário em aberto (ou já pagas) de clientes já cadastrados. Pede uma carteira de crediário para o arquivo inteiro.";
    }

    private static final String[] COLUNAS = {
            "CPF_CNPJ", "EMPRESA", "NUMERO_PARCELA", "DATA_VENCIMENTO", "DATA_RECEBIMENTO",
            "VALOR_RECEBER", "VALOR_JUROS", "VALOR_DESCONTO", "VALOR_RECEBIDO",
    };

    @Override
    public byte[] modeloPlanilha() {
        String[] exemplo1 = {"123.456.789-09", "1", "1", "10/09/2026", "", "150,00", "0", "0", "0"};
        String[] exemplo2 = {"123.456.789-09", "1", "2", "10/10/2026", "", "150,00", "0", "0", "0"};
        return ImportacaoPlanilha.gerarModelo(COLUNAS, exemplo1, exemplo2);
    }

    @Override
    @Transactional
    public RelatorioImportacao processar(List<LinhaPlanilha> linhas, JsonNode escolhas, boolean confirmar, Jwt jwt) {
        // O fuso da loja resolvido UMA vez por importação: é uma consulta ao banco (UF da
        // empresa) e a planilha pode ter milhares de linhas.
        ZoneId fuso = fusoDaLoja.daSessao(jwt);
        long idCarteira = resolverCarteiraCrediario(escolhas);

        record LinhaResolvida(LinhaPlanilha origem, long idCliente, long idEmpresa, int numeroParcela,
                               OffsetDateTime dataVencimento, OffsetDateTime dataRecebimento,
                               BigDecimal valorReceber, BigDecimal valorRecebido) {
        }

        List<LinhaErro> erros = new ArrayList<>();
        // Agrupa por (idCliente, idEmpresa), preservando a ordem de primeira ocorrência.
        Map<String, List<LinhaResolvida>> grupos = new LinkedHashMap<>();
        // Cache de cliente/empresa por chamada (2026-08-10) — variável LOCAL, não campo da
        // classe (o importador é um singleton Spring, compartilhado entre requisições/tenants).
        // Achado real: planilha de 300 mil parcelas repetindo o mesmo CPF fazia 300 mil SELECTs
        // sequenciais no banco, um por linha, sem necessidade — é exatamente esse cliente/essa
        // empresa que se repete em toda parcela da mesma pessoa.
        Map<String, Optional<Long>> clienteCache = new HashMap<>();
        Map<Integer, Optional<Long>> empresaCache = new HashMap<>();

        for (LinhaPlanilha linha : linhas) {
            try {
                String cpfCnpj = Documentos.somenteAlfanumerico(linha.valor("CPF_CNPJ"));
                if (cpfCnpj == null || cpfCnpj.isBlank()) {
                    throw new IllegalArgumentException("CPF_CNPJ é obrigatório.");
                }
                long idCliente = clienteCache.computeIfAbsent(cpfCnpj, this::buscarIdCliente).orElseThrow(() -> new IllegalArgumentException(
                        "Nenhum cliente cadastrado com o CPF/CNPJ \"" + linha.valor("CPF_CNPJ") + "\"."));
                Integer codigoEmpresa = ImportacaoPlanilha.inteiro("EMPRESA", linha.valor("EMPRESA"));
                if (codigoEmpresa == null) {
                    throw new IllegalArgumentException("EMPRESA é obrigatório.");
                }
                long idEmpresa = empresaCache.computeIfAbsent(codigoEmpresa, this::buscarIdEmpresa).orElseThrow(() -> new IllegalArgumentException(
                        "Nenhuma empresa cadastrada com o código " + codigoEmpresa + "."));

                Integer numeroParcela = ImportacaoPlanilha.inteiro("NUMERO_PARCELA", linha.valor("NUMERO_PARCELA"));
                if (numeroParcela == null) {
                    throw new IllegalArgumentException("NUMERO_PARCELA é obrigatório.");
                }
                LocalDate vencimento = ImportacaoPlanilha.data("DATA_VENCIMENTO", linha.valor("DATA_VENCIMENTO"));
                if (vencimento == null) {
                    throw new IllegalArgumentException("DATA_VENCIMENTO é obrigatório.");
                }
                BigDecimal valorReceber = ImportacaoPlanilha.decimal("VALOR_RECEBER", linha.valor("VALOR_RECEBER"));
                if (valorReceber == null || valorReceber.signum() <= 0) {
                    throw new IllegalArgumentException("VALOR_RECEBER deve ser maior que zero.");
                }
                LocalDate recebimento = ImportacaoPlanilha.data("DATA_RECEBIMENTO", linha.valor("DATA_RECEBIMENTO"));
                BigDecimal valorRecebido = ImportacaoPlanilha.decimal("VALOR_RECEBIDO", linha.valor("VALOR_RECEBIDO"));

                String chaveGrupo = idCliente + "|" + idEmpresa;
                grupos.computeIfAbsent(chaveGrupo, k -> new ArrayList<>()).add(new LinhaResolvida(
                        linha, idCliente, idEmpresa, numeroParcela,
                        inicioDoDia(vencimento, fuso), recebimento == null ? null : inicioDoDia(recebimento, fuso),
                        valorReceber, recebimento != null && valorRecebido != null ? valorRecebido : BigDecimal.ZERO));
            } catch (RuntimeException e) {
                erros.add(LinhaErro.de(linha.numeroLinha(), e));
            } finally {
                ImportacaoProgressoContext.avancar();
            }
        }

        int importadas = 0;
        for (List<LinhaResolvida> grupo : grupos.values()) {
            OffsetDateTime dataVenda = grupo.stream().map(LinhaResolvida::dataVencimento)
                    .min(OffsetDateTime::compareTo).orElseThrow();
            long idCliente = grupo.get(0).idCliente();
            long idEmpresa = grupo.get(0).idEmpresa();

            // SAVEPOINT por venda sintética (2026-08-10): uma falha de banco aqui (ou na parcela
            // abaixo) não pode deixar a transação do arquivo inteiro "abortada" para os grupos
            // seguintes — mesmo bug/fix de ProdutoImportador, ver ImportacaoSavepointExecutor.
            //
            // ⚠️ `origem = 'IMPORTACAO'` (V059, 2026-08-22) — esta venda é uma CASCA: existe só
            // porque `contas_receber.id_venda` é obrigatório. Não tem item, movimento de estoque
            // nem custo. Sem a marca, o DRE em regime CAIXA somava a parcela migrada como receita
            // com CMV zerado pelo COALESCE do LEFT JOIN — margem de 100% num relatório de decisão,
            // sobre mercadoria comprada e vendida no sistema anterior. Ver DreService.derivadasCaixa.
            Long idVenda = savepoints.executar(() -> jdbc.sql("""
                            INSERT INTO venda (id_tenant, id_empresa, id_cliente, data_venda,
                                               origem, importado_em)
                            VALUES (plataforma.tenant_atual(), ?, ?, ?, 'IMPORTACAO', now())
                            RETURNING id_venda
                            """)
                    .params(idEmpresa, idCliente, dataVenda)
                    .query(Long.class).single());

            for (LinhaResolvida r : grupo) {
                try {
                    savepoints.executarSemRetorno(() -> jdbc.sql("""
                                    INSERT INTO contas_receber
                                        (id_tenant, id_venda, id_carteira, numero_parcela, data_vencimento,
                                         data_recebimento, valor_receber, valor_recebido, documento_recebido)
                                    VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?)
                                    """)
                            .params(idVenda, idCarteira, r.numeroParcela(), r.dataVencimento(), r.dataRecebimento(),
                                    r.valorReceber(), r.valorRecebido(), r.dataRecebimento() != null)
                            .update());
                    importadas++;
                } catch (RuntimeException e) {
                    erros.add(LinhaErro.de(r.origem().numeroLinha(), e));
                }
            }
        }

        RelatorioImportacao relatorio = RelatorioImportacao.concluir(confirmar, linhas.size(), importadas, 0, erros,
                List.of("Nenhum lançamento de caixa é criado para parcelas já pagas — só o histórico é marcado."));
        if (!relatorio.confirmado()) {
            throw new SimulacaoConcluidaException(relatorio);
        }
        return relatorio;
    }

    /** {@code escolhas}: {@code {"idCarteira": 5}} OU
     *  {@code {"novaCarteira": {"nomeCarteira": "...", "prazoPagamento": 30, "pcMinima": 1, "pcMaxima": 12}}}
     *  — categoria sempre CREDIARIO, permite receber crediário sempre true. */
    private long resolverCarteiraCrediario(JsonNode escolhas) {
        if (escolhas != null && escolhas.hasNonNull("idCarteira")) {
            long id = escolhas.get("idCarteira").asLong();
            String categoria = jdbc.sql("""
                            SELECT categoria_carteira::text FROM tipo_carteira
                            WHERE id_tenant = plataforma.tenant_atual() AND id_carteira = ?
                            """)
                    .param(id).query(String.class).optional()
                    .orElseThrow(() -> new IllegalArgumentException("Carteira informada não existe."));
            if (!"CREDIARIO".equals(categoria)) {
                throw new IllegalArgumentException("A carteira escolhida não é do tipo crediário.");
            }
            return id;
        }
        if (escolhas != null && escolhas.hasNonNull("novaCarteira")) {
            JsonNode n = escolhas.get("novaCarteira");
            String nome = textoObrigatorio(n, "nomeCarteira", "Nome da nova carteira de crediário");
            Integer prazo = n.hasNonNull("prazoPagamento") ? n.get("prazoPagamento").asInt() : null;
            Integer pcMinima = n.hasNonNull("pcMinima") ? n.get("pcMinima").asInt() : null;
            Integer pcMaxima = n.hasNonNull("pcMaxima") ? n.get("pcMaxima").asInt() : null;
            if (prazo == null || pcMinima == null || pcMaxima == null) {
                throw new IllegalArgumentException(
                        "Informe prazo de pagamento e nº mínimo/máximo de parcelas da nova carteira.");
            }
            var criada = tipoCarteiraService.criar(new TipoCarteiraRequest(
                    nome, CategoriaCarteira.CREDIARIO, prazo, pcMinima, pcMaxima, null, null, null, true,
                    null, null, null));
            return criada.idCarteira();
        }
        throw new IllegalArgumentException(
                "Escolha uma carteira de crediário existente ou informe os dados de uma nova antes de importar.");
    }

    private static String textoObrigatorio(JsonNode n, String campo, String rotulo) {
        if (n == null || !n.hasNonNull(campo) || n.get(campo).asText("").isBlank()) {
            throw new IllegalArgumentException(rotulo + " é obrigatório.");
        }
        return n.get(campo).asText().trim();
    }

    private Optional<Long> buscarIdCliente(String cpfCnpjNormalizado) {
        return jdbc.sql("""
                        SELECT id_cliente FROM cliente
                        WHERE id_tenant = plataforma.tenant_atual() AND cpf_cnpj = ?
                        """)
                .param(cpfCnpjNormalizado)
                .query(Long.class).optional();
    }

    private Optional<Long> buscarIdEmpresa(int codigoEmpresa) {
        return jdbc.sql("""
                        SELECT id_empresa FROM empresa
                        WHERE id_tenant = plataforma.tenant_atual() AND codigo_empresa = ?
                        """)
                .param(codigoEmpresa)
                .query(Long.class).optional();
    }

    /**
     * Meia-noite da data digitada na planilha, <b>no fuso da loja</b>.
     *
     * <p>⚠️ Era {@code ZoneId.systemDefault()} até 2026-08-29 (achado do guarda de fuso, ampliado
     * na mesma auditoria). O fuso padrão da JVM é o {@code TZ} do container — que <b>só existe em
     * produção</b> ({@code docker-compose.prod.yml}); em dev e na suíte ele é UTC. Ou seja: o
     * defeito não reproduz na máquina de quem escreve. Com UTC, o vencimento de 01/09 virava
     * {@code 2026-09-01T00:00Z} = <b>31/08 às 21h em Brasília</b>, e a parcela nascia importada com
     * um dia a menos — vencida um dia antes, no relatório e na cobrança.
     */
    private static OffsetDateTime inicioDoDia(LocalDate data, ZoneId fusoDaLoja) {
        return data.atStartOfDay(fusoDaLoja).toOffsetDateTime();
    }

}
