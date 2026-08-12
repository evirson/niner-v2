package com.vetor.niner.estoque.entrada;

import com.vetor.niner.catalogo.GradeService;
import com.vetor.niner.catalogo.ProdutoBarraDtos.ProdutoBarraResponse;
import com.vetor.niner.catalogo.ProdutoBarraService;
import com.vetor.niner.configuracao.importacao.ImportacaoPlanilha;
import com.vetor.niner.configuracao.importacao.ImportacaoPlanilha.LinhaPlanilha;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemPlanilhaPreviewResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fluxo Planilha da Entrada de Produtos por Compra (docs/telas/entrada-mercadoria.md,
 * 2026-08-12) — lê o arquivo (colunas fixas: NOME DO PRODUTO, MARCA, REFERENCIA, COR, TAMANHO,
 * CODIGO BARRAS FABRICANTE, QTD, CUSTO UNITARIO, exemplo do dono do produto em
 * {@code C:\fix\PLANILHA_ENTRADA.xlsx}) e tenta casar cada linha com um produto já cadastrado:
 * primeiro pelo código de barras do FABRICANTE ({@code produto_barra.ean}), senão por
 * descrição+marca+referência (mesmo idioma exato de {@code ProdutoImportador.produtoJaExiste} —
 * comparação exata, sem fuzzy/accent-folding, que não existe em nenhum lugar do projeto). Cor e
 * tamanho seguem a mesma lógica, por nome, restritos à grade do produto encontrado.
 *
 * <p>Preview apenas — não grava nada no ledger. As exceções deliberadas são: a materialização de
 * {@code produto_barra} (via {@link ProdutoBarraService#obterOuCriar}) quando produto+cor+
 * tamanho batem com confiança, e o cadastro automático de cor/tamanho quando o texto da planilha
 * não bate com nada já cadastrado (2026-08-13, pedido do dono do produto: cor e tamanho "que não
 * existirem no cadastro" nascem na hora, sem exigir que o usuário resolva a pendência à mão) —
 * cor entra direto em {@code cfg_cor} (lista solta, sem grade), tamanho entra em
 * {@code cfg_tamanho} e, se o produto casado ainda não tiver esse tamanho na grade, a grade é
 * estendida ({@link GradeService#garantirTamanhoNaGrade}, mesma operação que o usuário faria
 * manualmente em "＋ Gerenciar Grades"). Tudo idempotente e leve, mesmo princípio já usado em
 * Emissão de Etiqueta/fluxo Individual desta mesma tela — só fica "pendente" o que a planilha
 * deixou em branco (coluna COR/TAMANHO vazia num produto que exige grade) ou o caso raro de
 * grade já com os 20 slots ocupados.
 */
@Service
public class EntradaPlanilhaService {

    private static final String[] COLUNAS = {
            "NOME DO PRODUTO", "MARCA", "REFERENCIA", "COR", "TAMANHO",
            "CODIGO BARRAS FABRICANTE", "QTD", "CUSTO UNITARIO"
    };

    private final JdbcClient jdbc;
    private final ProdutoBarraService produtoBarraService;
    private final GradeService gradeService;

    public EntradaPlanilhaService(JdbcClient jdbc, ProdutoBarraService produtoBarraService, GradeService gradeService) {
        this.jdbc = jdbc;
        this.produtoBarraService = produtoBarraService;
        this.gradeService = gradeService;
    }

    public byte[] modelo() {
        return ImportacaoPlanilha.gerarModelo(COLUNAS,
                new String[] {"TENIS ESPORTIVO XPTO", "MARCA EXEMPLO", "REF-001", "PRETO", "42", "7891234567890", "10", "89,90"});
    }

    @Transactional
    public List<ItemPlanilhaPreviewResponse> preview(MultipartFile arquivo) {
        List<LinhaPlanilha> linhas = ImportacaoPlanilha.ler(arquivo);
        List<ItemPlanilhaPreviewResponse> resultado = new ArrayList<>();
        for (LinhaPlanilha linha : linhas) {
            resultado.add(resolverLinha(linha));
        }
        return resultado;
    }

    private ItemPlanilhaPreviewResponse resolverLinha(LinhaPlanilha linha) {
        String nomeProduto = linha.valor("NOME DO PRODUTO");
        String marca = linha.valor("MARCA");
        String referencia = linha.valor("REFERENCIA");
        String cor = linha.valor("COR");
        String tamanho = linha.valor("TAMANHO");
        String ean = ImportacaoPlanilha.semEspacos(linha.valor("CODIGO BARRAS FABRICANTE"));

        BigDecimal qtd;
        BigDecimal custoUnitario;
        try {
            qtd = ImportacaoPlanilha.decimal("QTD", linha.valor("QTD"));
            custoUnitario = ImportacaoPlanilha.decimal("CUSTO UNITARIO", linha.valor("CUSTO UNITARIO"));
        } catch (RuntimeException e) {
            return pendente(linha, nomeProduto, marca, referencia, cor, tamanho, ean, null, null, e.getMessage());
        }
        if (nomeProduto == null) {
            return pendente(linha, nomeProduto, marca, referencia, cor, tamanho, ean, qtd, custoUnitario,
                    "Coluna NOME DO PRODUTO vazia.");
        }
        if (qtd == null || custoUnitario == null) {
            return pendente(linha, nomeProduto, marca, referencia, cor, tamanho, ean, qtd, custoUnitario,
                    "Informe QTD e CUSTO UNITARIO.");
        }

        // 1) código de barras do fabricante — se casar, a variação já está 100% resolvida.
        if (ean != null) {
            Optional<ProdutoBarraResponse> porEan = buscarPorEan(ean);
            if (porEan.isPresent()) {
                ProdutoBarraResponse v = porEan.get();
                return resolvido(linha, nomeProduto, marca, referencia, cor, tamanho, ean, qtd, custoUnitario, v);
            }
        }

        // 2) descrição + marca + referência (exata, mesmo idioma de ProdutoImportador.produtoJaExiste).
        Optional<LinhaProduto> produto = buscarProduto(nomeProduto, marca, referencia);
        if (produto.isEmpty()) {
            return pendente(linha, nomeProduto, marca, referencia, cor, tamanho, ean, qtd, custoUnitario,
                    "Produto não encontrado (descrição + marca + referência não batem com nenhum cadastro).");
        }

        LinhaProduto p = produto.get();

        if (p.idGrade() != null && cor == null) {
            return pendenteComProduto(linha, nomeProduto, marca, referencia, cor, tamanho, ean, qtd, custoUnitario,
                    p.idProduto(), p.idGrade(), "Coluna COR vazia — obrigatória para este produto.");
        }
        if (p.idGrade() != null && tamanho == null) {
            return pendenteComProduto(linha, nomeProduto, marca, referencia, cor, tamanho, ean, qtd, custoUnitario,
                    p.idProduto(), p.idGrade(), "Coluna TAMANHO vazia — obrigatória para este produto.");
        }

        Long idCor = p.idGrade() == null ? null : corOuCriar(cor);
        Long idTamanho = null;
        if (p.idGrade() != null) {
            long idTamanhoCatalogo = tamanhoOuCriar(tamanho);
            try {
                gradeService.garantirTamanhoNaGrade(p.idGrade(), idTamanhoCatalogo);
            } catch (RuntimeException e) {
                return pendenteComProduto(linha, nomeProduto, marca, referencia, cor, tamanho, ean, qtd, custoUnitario,
                        p.idProduto(), p.idGrade(), "Não foi possível adicionar o tamanho \"" + tamanho + "\": " + e.getMessage());
            }
            idTamanho = idTamanhoCatalogo;
        }

        try {
            ProdutoBarraResponse v = produtoBarraService.obterOuCriar(p.idProduto(), idCor, idTamanho, true, ean);
            return resolvido(linha, nomeProduto, marca, referencia, cor, tamanho, ean, qtd, custoUnitario, v);
        } catch (RuntimeException e) {
            return pendenteComProduto(linha, nomeProduto, marca, referencia, cor, tamanho, ean, qtd, custoUnitario,
                    p.idProduto(), p.idGrade(), e.getMessage());
        }
    }

    private record LinhaProduto(long idProduto, Long idGrade) {
    }

    private Optional<LinhaProduto> buscarProduto(String nomeProduto, String marca, String referencia) {
        String descricaoN = nomeProduto.trim().toUpperCase(Locale.ROOT);
        String marcaN = marca == null ? "" : marca.trim().toUpperCase(Locale.ROOT);
        String referenciaN = referencia == null ? "" : referencia.trim().toUpperCase(Locale.ROOT);
        return jdbc.sql("""
                        SELECT id_produto, id_grade FROM produto
                        WHERE id_tenant = plataforma.tenant_atual() AND ativo = true
                          AND UPPER(descricao) = ? AND UPPER(COALESCE(marca, '')) = ?
                          AND UPPER(COALESCE(referencia, '')) = ?
                        LIMIT 1
                        """)
                .params(descricaoN, marcaN, referenciaN)
                .query((rs, n) -> new LinhaProduto(rs.getLong("id_produto"), getLongOuNulo(rs, "id_grade")))
                .optional();
    }

    /** {@code rs.getObject(coluna, Long.class)} não converte `integer` pra `Long` de forma
     *  confiável no driver — mesmo padrão já usado em {@code ProdutoBarraService}/{@code CrmService}. */
    private static Long getLongOuNulo(java.sql.ResultSet rs, String coluna) throws java.sql.SQLException {
        long valor = rs.getLong(coluna);
        return rs.wasNull() ? null : valor;
    }

    /** Acha (por nome, mesmo idioma exato do resto da tela) ou cadastra a cor na hora — cor não
     *  depende de grade, é uma lista solta por tenant, então não há nada além do cadastro em si
     *  a garantir (2026-08-13). */
    private long corOuCriar(String cor) {
        String nome = cor.trim().toUpperCase(Locale.ROOT);
        Optional<Long> existente = jdbc.sql("""
                        SELECT id_cor FROM cfg_cor
                        WHERE id_tenant = plataforma.tenant_atual() AND UPPER(descricao) = ?
                        """)
                .param(nome).query(Long.class).optional();
        if (existente.isPresent()) {
            return existente.get();
        }
        return jdbc.sql("""
                        INSERT INTO cfg_cor (id_tenant, descricao)
                        VALUES (plataforma.tenant_atual(), ?)
                        RETURNING id_cor
                        """)
                .param(nome).query(Long.class).single();
    }

    /** Acha ou cadastra o tamanho no catálogo do tenant ({@code cfg_tamanho}) — mesmo princípio
     *  find-or-create de {@code ProdutoImportador.idTamanhoOuCriar}. Só resolve a existência do
     *  tamanho em si; pertencer (ou não) à grade do produto casado é responsabilidade de quem
     *  chama, via {@link GradeService#garantirTamanhoNaGrade}. */
    private long tamanhoOuCriar(String tamanho) {
        String nome = tamanho.trim().toUpperCase(Locale.ROOT);
        Optional<Long> existente = jdbc.sql("""
                        SELECT id_tamanho FROM cfg_tamanho
                        WHERE id_tenant = plataforma.tenant_atual() AND UPPER(descricao) = ?
                        """)
                .param(nome).query(Long.class).optional();
        if (existente.isPresent()) {
            return existente.get();
        }
        return jdbc.sql("""
                        INSERT INTO cfg_tamanho (id_tenant, descricao)
                        VALUES (plataforma.tenant_atual(), ?)
                        RETURNING id_tamanho
                        """)
                .param(nome).query(Long.class).single();
    }

    private Optional<ProdutoBarraResponse> buscarPorEan(String ean) {
        return jdbc.sql("""
                        SELECT pb.id_variacao, pb.sku, pb.ean, p.descricao, p.marca, p.referencia, p.preco_venda,
                               co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho
                        FROM produto_barra pb
                        JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant
                        WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.ean = ? AND p.ativo = true
                        """)
                .param(ean)
                .query((rs, n) -> new ProdutoBarraResponse(
                        rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("ean"), rs.getString("descricao"),
                        rs.getString("marca"), rs.getString("referencia"), rs.getBigDecimal("preco_venda"),
                        rs.getString("variacao_cor"), rs.getString("variacao_tamanho")))
                .optional();
    }

    private static ItemPlanilhaPreviewResponse resolvido(LinhaPlanilha linha, String nomeProduto, String marca,
            String referencia, String cor, String tamanho, String ean, BigDecimal qtd, BigDecimal custoUnitario,
            ProdutoBarraResponse v) {
        return new ItemPlanilhaPreviewResponse(linha.numeroLinha(), nomeProduto, marca, referencia, cor, tamanho, ean,
                qtd, custoUnitario, true, v.idVariacao(), v.sku(), v.descricao(), v.variacaoCor(), v.variacaoTamanho(),
                null, null, null, null);
    }

    private static ItemPlanilhaPreviewResponse pendente(LinhaPlanilha linha, String nomeProduto, String marca,
            String referencia, String cor, String tamanho, String ean, BigDecimal qtd, BigDecimal custoUnitario,
            String motivo) {
        return new ItemPlanilhaPreviewResponse(linha.numeroLinha(), nomeProduto, marca, referencia, cor, tamanho, ean,
                qtd, custoUnitario, false, null, null, null, null, null, null, null, motivo, null);
    }

    private static ItemPlanilhaPreviewResponse pendenteComProduto(LinhaPlanilha linha, String nomeProduto, String marca,
            String referencia, String cor, String tamanho, String ean, BigDecimal qtd, BigDecimal custoUnitario,
            long idProduto, Long idGrade, String motivo) {
        return new ItemPlanilhaPreviewResponse(linha.numeroLinha(), nomeProduto, marca, referencia, cor, tamanho, ean,
                qtd, custoUnitario, false, null, null, null, null, null, idProduto, idGrade, motivo, null);
    }
}
