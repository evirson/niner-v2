package com.vetor.niner.estoque.entrada;

import com.vetor.niner.cadastros.cliente.Documentos;
import com.vetor.niner.catalogo.ProdutoBarraDtos.ProdutoBarraResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.DuplicataXmlPreviewResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.EntradaXmlPreviewResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.FornecedorXmlPreviewResponse;
import com.vetor.niner.estoque.entrada.EntradaMercadoriaDtos.ItemPlanilhaPreviewResponse;
import com.vetor.niner.estoque.entrada.NfeXmlParser.EmitenteNfe;
import com.vetor.niner.estoque.entrada.NfeXmlParser.ItemNfe;
import com.vetor.niner.estoque.entrada.NfeXmlParser.NotaFiscalNfe;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fluxo XML da Entrada de Produtos por Compra (Fase 3, docs/telas/entrada-mercadoria.md,
 * 2026-08-12) — lê o XML da NF-e ({@link NfeXmlParser}) e tenta casar cada item, na mesma
 * ordem de confiança da Planilha, mas SEM inventar nada: 1) EAN (`produto_barra.ean`) — casamento
 * exato, sem ambiguidade; 2) {@code produto_fornecedor} — código do fornecedor (`cProd`) já
 * aprendido de uma entrada anterior deste MESMO fornecedor, também exato. Sem nenhum dos dois,
 * a linha vira pendência — nome/cor/tamanho aparecem só como PALPITE (pra ajudar o operador a
 * escolher mais rápido na tela de "Não Localizados"), nunca resolvem sozinhos: cor e tamanho
 * NUNCA são cadastrados automaticamente aqui (2026-08-12, pedido explícito do dono do produto —
 * diferente da Planilha, que cadastra sozinha porque a planilha foi o próprio lojista quem
 * preencheu; o texto de um XML de fornecedor tem confiança menor pra isso).
 *
 * <p>Preview apenas — não grava nada (nem produto_barra, nem cor/tamanho, nem ledger). O
 * aprendizado de {@code produto_fornecedor} só acontece na confirmação
 * ({@link EntradaMercadoriaService#efetivar}, quando o item carrega {@code codigoFornecedor}).
 */
@Service
public class EntradaXmlService {

    /** "220"/"34" etc. — tamanho isolado como último token do texto, o padrão mais comum em
     *  calçados/confecções (nunca decide sozinho: só vira palpite se bater com um tamanho JÁ
     *  cadastrado em alguma grade do tenant, ver {@link #adivinharTamanho}). */
    private static final Pattern TOKEN_NUMERICO = Pattern.compile("^\\d{1,3}$");

    private final JdbcClient jdbc;

    public EntradaXmlService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public EntradaXmlPreviewResponse preview(MultipartFile arquivo) {
        String xmlTexto = lerTexto(arquivo);
        NotaFiscalNfe nota = NfeXmlParser.parse(new java.io.ByteArrayInputStream(xmlTexto.getBytes(StandardCharsets.UTF_8)));

        // "AND cancelado = false" (2026-08-12, Cancelamento de Entrada) — uma entrada cancelada
        // libera a chave pra reimportar a mesma NF-e corrigida; sem isso o preview continuaria
        // avisando "já importada" pra sempre, mesmo depois de cancelada.
        boolean jaImportada = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM produto_movimento_mestre
                            WHERE id_tenant = plataforma.tenant_atual() AND chave_nfe = ? AND cancelado = false
                        )
                        """)
                .param(nota.chaveNfe()).query(Boolean.class).single());

        FornecedorXmlPreviewResponse fornecedor = buscarFornecedor(nota.emitente());
        Long idEmpresaEncontrada = nota.cnpjDestinatario() == null ? null : buscarIdEmpresaPorCnpj(nota.cnpjDestinatario());
        List<String> coresConhecidas = listarDescricoes("cfg_cor");
        List<String> tamanhosConhecidos = listarDescricoes("cfg_tamanho");

        List<ItemPlanilhaPreviewResponse> itens = new ArrayList<>();
        int numeroLinha = 1;
        for (ItemNfe item : nota.itens()) {
            itens.add(resolverItem(numeroLinha++, item, fornecedor.idFornecedor(), coresConhecidas, tamanhosConhecidos));
        }

        List<DuplicataXmlPreviewResponse> duplicatas = nota.duplicatas().stream()
                .map(d -> new DuplicataXmlPreviewResponse(d.numero(), d.vencimento(), d.valor()))
                .toList();

        return new EntradaXmlPreviewResponse(nota.chaveNfe(), nota.serie(), nota.numero(), nota.dataEmissao(),
                jaImportada, fornecedor, idEmpresaEncontrada, nota.valorTotal(), duplicatas, itens, xmlTexto);
    }

    /** Empresa do tenant casada pelo CNPJ do destinatário do XML (2026-08-12) — mesma
     *  normalização de CNPJ alfanumérico usada em fornecedor/cliente. {@code empresa.cnpj} hoje
     *  não tem nenhuma tela de cadastro que o preencha (schema pronto, sem CRUD ainda), então
     *  na prática isto só encontra algo em tenants que tiverem esse dado carregado por outro
     *  meio (ex.: seed/importação) — sem match, o comportamento de sempre (empresa da sessão)
     *  prevalece, sem quebrar nada. */
    private Long buscarIdEmpresaPorCnpj(String cnpjDestinatario) {
        String cnpjNormalizado = Documentos.somenteAlfanumerico(cnpjDestinatario);
        return jdbc.sql("""
                        SELECT id_empresa FROM empresa
                        WHERE id_tenant = plataforma.tenant_atual() AND cnpj = ? AND ativo = true
                        """)
                .param(cnpjNormalizado)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private static String lerTexto(MultipartFile arquivo) {
        try {
            return new String(arquivo.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o arquivo enviado.", e);
        }
    }

    private ItemPlanilhaPreviewResponse resolverItem(int numeroLinha, ItemNfe item, Long idFornecedor,
                                                       List<String> coresConhecidas, List<String> tamanhosConhecidos) {
        BigDecimal custoUnitario = custoLiquido(item);
        // NCM do XML sempre vale (2026-08-13, pedido do dono do produto). Dois usos, dois graus
        // de confiança: em PENDÊNCIA (só pré-preenche um campo de formulário, sem gravar nada),
        // o dígito cru do XML basta — validar contra cfg_produto_ncm aqui faria o campo vir
        // vazio sempre que a base de NCM local estiver incompleta (o normal: a base oficial da
        // Receita tem milhares de códigos, ninguém carrega isso à mão num ambiente de teste),
        // que era exatamente o bug relatado ("não vem preenchido"). Em linha RESOLVIDA, o NCM
        // vai direto pra um UPDATE em produto.codigo_ncm (FK pra cfg_produto_ncm) na confirmação
        // — aí sim precisa validar antes, senão um código desconhecido quebraria a confirmação
        // inteira com erro de FK.
        String ncmBruto = limparNcm(item.ncm());

        if (item.cEan() != null) {
            Optional<ProdutoBarraResponse> porEan = buscarVariacaoPorEan(item.cEan());
            if (porEan.isPresent()) {
                return resolvido(numeroLinha, item, custoUnitario, porEan.get(), ncmExistenteOuNulo(ncmBruto));
            }
        }

        if (idFornecedor != null && item.cProd() != null) {
            Optional<ProdutoBarraResponse> porCodigo = buscarVariacaoPorCodigoFornecedor(idFornecedor, item.cProd());
            if (porCodigo.isPresent()) {
                return resolvido(numeroLinha, item, custoUnitario, porCodigo.get(), ncmExistenteOuNulo(ncmBruto));
            }
        }

        String corPalpite = adivinharCor(item.xProd(), coresConhecidas);
        String tamanhoPalpite = adivinharTamanho(item.xProd(), tamanhosConhecidos);
        // Cor/tamanho já aparecem em colunas próprias (palpite) e viram a variação — repeti-los
        // dentro da descrição do produto é ruído e, pior, faz cada tamanho da mesma peça virar
        // um "produto" com nome diferente pro operador (2026-08-12, pedido do dono do produto).
        String nomeSemVariacao = removerUltimaOcorrencia(removerUltimaOcorrencia(item.xProd(), tamanhoPalpite), corPalpite);
        String motivo = "Produto não encontrado (sem EAN/código de fornecedor já conhecido) — pesquise ou cadastre.";
        return new ItemPlanilhaPreviewResponse(numeroLinha, nomeSemVariacao, null, null, corPalpite, tamanhoPalpite,
                item.cEan(), item.qtd(), custoUnitario, false, null, null, null, null, null, null, null, motivo,
                item.cProd(), ncmBruto);
    }

    /** Só limpa (dígitos, mesmo formato gravado no banco) — sem checar se existe em {@code
     *  cfg_produto_ncm}. Usado pra pré-preencher o cadastro rápido numa pendência: se o código
     *  não existir de fato, o próprio cadastro do produto recusa na hora de salvar (mensagem
     *  amigável já tratada em {@code ProdutoService}), não precisa barrar aqui. */
    private static String limparNcm(String codigoNcm) {
        if (codigoNcm == null || codigoNcm.isBlank()) {
            return null;
        }
        String codigoN = codigoNcm.replaceAll("[^0-9]", "");
        return codigoN.isEmpty() ? null : codigoN;
    }

    /** Acha (por código, mesmo idioma exato de {@code ProdutoImportador.ncmExistenteOuNulo}) o
     *  NCM na referência global — código que não existe (ou vem vazio/em formato inválido) volta
     *  {@code null} em vez de propagar algo que faria a confirmação falhar por violação de FK
     *  (2026-08-13). Só usado pra linha RESOLVIDA (vai virar UPDATE) — ver {@link #limparNcm}
     *  pra pendência. */
    private String ncmExistenteOuNulo(String ncmBruto) {
        if (ncmBruto == null) {
            return null;
        }
        Boolean existe = jdbc.sql("SELECT EXISTS (SELECT 1 FROM cfg_produto_ncm WHERE codigo_ncm = ?)")
                .param(ncmBruto)
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(existe) ? ncmBruto : null;
    }

    /** Custo líquido do item = (qtd × preço unitário − desconto da linha) ÷ qtd — não confia em
     *  nenhuma tag de total pronta (varia entre emissores), calcula a partir dos campos
     *  garantidamente presentes no schema da NF-e (`qCom`/`vUnCom`/`vDesc`). */
    private static BigDecimal custoLiquido(ItemNfe item) {
        if (item.qtd().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = item.valorUnitario().multiply(item.qtd()).subtract(item.valorDesconto());
        return total.divide(item.qtd(), 2, RoundingMode.HALF_UP);
    }

    private static ItemPlanilhaPreviewResponse resolvido(int numeroLinha, ItemNfe item, BigDecimal custoUnitario,
                                                           ProdutoBarraResponse v, String ncm) {
        return new ItemPlanilhaPreviewResponse(numeroLinha, item.xProd(), null, null, null, null, item.cEan(),
                item.qtd(), custoUnitario, true, v.idVariacao(), v.sku(), v.descricao(), v.variacaoCor(),
                v.variacaoTamanho(), null, null, null, item.cProd(), ncm);
    }

    private Optional<ProdutoBarraResponse> buscarVariacaoPorEan(String ean) {
        return jdbc.sql("""
                        SELECT pb.id_variacao, pb.sku, pb.ean, p.descricao, p.marca, p.referencia, p.preco_venda,
                               co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho
                        FROM produto_barra pb
                        JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                        WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.ean = ? AND p.ativo = true
                        """)
                .param(ean)
                .query(EntradaXmlService::mapearVariacao)
                .optional();
    }

    private Optional<ProdutoBarraResponse> buscarVariacaoPorCodigoFornecedor(long idFornecedor, String codigoFornecedor) {
        return jdbc.sql("""
                        SELECT pb.id_variacao, pb.sku, pb.ean, p.descricao, p.marca, p.referencia, p.preco_venda,
                               co.descricao AS variacao_cor, ta.descricao AS variacao_tamanho
                        FROM produto_fornecedor pf
                        JOIN produto_barra pb ON pb.id_tenant = pf.id_tenant AND pb.id_variacao = pf.id_variacao
                        JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                        LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
                        LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
                        WHERE pf.id_tenant = plataforma.tenant_atual() AND pf.id_fornecedor = ?
                          AND pf.codigo_fornecedor = ? AND p.ativo = true
                        """)
                .params(idFornecedor, codigoFornecedor)
                .query(EntradaXmlService::mapearVariacao)
                .optional();
    }

    private static ProdutoBarraResponse mapearVariacao(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ProdutoBarraResponse(
                rs.getLong("id_variacao"), rs.getString("sku"), rs.getString("ean"), rs.getString("descricao"),
                rs.getString("marca"), rs.getString("referencia"), rs.getBigDecimal("preco_venda"),
                rs.getString("variacao_cor"), rs.getString("variacao_tamanho"));
    }

    /** Casa o CNPJ do emitente (alfanumérico desde jul/2026, mesma normalização de
     *  `Documentos.somenteAlfanumerico` usada no resto do projeto) contra o cadastro do tenant.
     *  {@code idFornecedor} nulo quando não achou — a tela oferece escolher/cadastrar na aba
     *  "Dados Gerais", como já acontece nos outros dois fluxos. */
    private FornecedorXmlPreviewResponse buscarFornecedor(EmitenteNfe emit) {
        String cnpjNormalizado = Documentos.somenteAlfanumerico(emit.cnpj());
        record LinhaFornecedor(long idFornecedor, String razaoSocial, String nomeFantasia, String inscricaoEstadual,
                                String endereco, String numero, String bairro, String cidade, String estado, String cep,
                                String telefone) {
        }
        Optional<LinhaFornecedor> achado = jdbc.sql("""
                        SELECT id_fornecedor, razao_social, nome_fantasia, inscricao_estadual, endereco, numero,
                               bairro, cidade, estado, cep, telefone
                        FROM fornecedor
                        WHERE id_tenant = plataforma.tenant_atual() AND cnpj = ? AND ativo = true
                        """)
                .param(cnpjNormalizado)
                .query((rs, n) -> new LinhaFornecedor(rs.getLong("id_fornecedor"), rs.getString("razao_social"),
                        rs.getString("nome_fantasia"), rs.getString("inscricao_estadual"), rs.getString("endereco"),
                        rs.getString("numero"), rs.getString("bairro"), rs.getString("cidade"), rs.getString("estado"),
                        rs.getString("cep"), rs.getString("telefone")))
                .optional();

        if (achado.isPresent()) {
            LinhaFornecedor f = achado.get();
            return new FornecedorXmlPreviewResponse(f.idFornecedor(), cnpjNormalizado, f.razaoSocial(), f.nomeFantasia(),
                    f.inscricaoEstadual(), f.endereco(), f.numero(), f.bairro(), f.cidade(), f.estado(), f.cep(), f.telefone());
        }
        // Não achou — devolve os dados do PRÓPRIO XML pra pré-preencher o cadastro rápido.
        return new FornecedorXmlPreviewResponse(null, cnpjNormalizado, emit.razaoSocial(), emit.nomeFantasia(),
                emit.ie(), emit.logradouro(), emit.numero(), emit.bairro(), emit.municipio(), emit.uf(), emit.cep(),
                emit.telefone());
    }

    private List<String> listarDescricoes(String tabela) {
        // {@code tabela} nunca vem do cliente — só chamado com as duas constantes literais
        // abaixo ("cfg_cor"/"cfg_tamanho"), sem risco de injeção. Exclui id=1 (cor/tamanho
        // PADRÃO, 2026-08-13) — sem isso, a cor PADRÃO (descricao='') vira candidata no
        // heurístico de `adivinharCor` e pode "casar" com qualquer texto que tenha espaço
        // duplo, retornando um palpite falso em vez de nenhum.
        String colunaId = tabela.equals("cfg_cor") ? "id_cor" : "id_tamanho";
        return jdbc.sql("SELECT descricao FROM " + tabela + " WHERE id_tenant = plataforma.tenant_atual() AND " + colunaId + " <> 1")
                .query(String.class)
                .list();
    }

    /** Nome de cor já cadastrado no tenant que aparece como PALAVRA INTEIRA em algum lugar do
     *  texto — prefere o nome mais longo quando mais de um bate (evita "AZUL" vencer "AZUL
     *  CLARO" quando os dois existem no catálogo). Só um palpite: nunca resolve a linha
     *  sozinho, sempre exige confirmação manual do operador (ver classe). */
    private static String adivinharCor(String texto, List<String> coresConhecidas) {
        if (texto == null) return null;
        String textoN = " " + texto.toUpperCase(Locale.ROOT) + " ";
        return coresConhecidas.stream()
                .filter(cor -> textoN.contains(" " + cor.toUpperCase(Locale.ROOT) + " "))
                .max(java.util.Comparator.comparingInt(String::length))
                .orElse(null);
    }

    /** Tamanho: primeiro tenta um tamanho JÁ cadastrado no tenant que apareça como token inteiro
     *  do texto (funciona pra "P"/"M"/"G" e pra numérico); sem isso, cai pro último token do
     *  texto SE for puramente numérico de 1-3 dígitos (padrão mais comum em calçados/confecção
     *  — "…PRETO 220 34" → "34"), mesmo sem já existir no cadastro, pra dar uma sugestão mesmo
     *  em tenant novo sem nenhum tamanho cadastrado ainda. Sempre só um palpite (ver classe). */
    private static String adivinharTamanho(String texto, List<String> tamanhosConhecidos) {
        if (texto == null) return null;
        String[] tokens = texto.trim().split("\\s+");
        for (String tamanho : tamanhosConhecidos) {
            for (String token : tokens) {
                if (token.equalsIgnoreCase(tamanho)) {
                    return tamanho;
                }
            }
        }
        String ultimo = tokens[tokens.length - 1];
        return TOKEN_NUMERICO.matcher(ultimo).matches() ? ultimo : null;
    }

    /** Remove a ÚLTIMA ocorrência de {@code token} como palavra inteira (case-insensitive) de
     *  {@code texto} — usado pra tirar cor/tamanho já identificados (palpite) da descrição do
     *  produto, já que eles viram colunas/variação próprias e repeti-los na descrição só cria
     *  ruído (e faz cada tamanho da mesma peça parecer um produto diferente). Última ocorrência,
     *  não a primeira, porque o padrão mais comum é o tamanho vir no FINAL do texto — reduz o
     *  risco de cortar um trecho errado quando o token aparece mais de uma vez. {@code token}
     *  nulo (nada identificado) é no-op. */
    private static String removerUltimaOcorrencia(String texto, String token) {
        if (texto == null || token == null || token.isBlank()) return texto;
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(token) + "\\b").matcher(texto);
        int inicio = -1;
        int fim = -1;
        while (m.find()) {
            inicio = m.start();
            fim = m.end();
        }
        if (inicio < 0) return texto;
        return (texto.substring(0, inicio) + texto.substring(fim)).replaceAll("\\s+", " ").trim();
    }
}
