package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.armazenamento.AreaPrivada;
import com.vetor.niner.comum.armazenamento.ArmazenamentoPrivado;
import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.estoque.entrada.NfeXmlParser;
import com.vetor.niner.fiscal.configuracao.CsrtService;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DestinatarioDevolucao;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DevolucaoParaMontar;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.ItemDevolucao;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.TotaisDevolucao;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Emitente;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.MontagemInvalidaException;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ResponsavelTecnico;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Monta a NF-e de <b>devolução de compra</b> (modelo 55, <b>saída</b>) a partir de um movimento
 * {@code DEVOLUCAO_COMPRA} já gravado — o espelho, na direção da entrada, do
 * {@link DevolucaoFiscalAssembler} do B9.
 *
 * <h2>Emitente e destinatário</h2>
 *
 * <p>Definição do dono do produto (2026-08-20): <b>emitente é a empresa que recebeu a mercadoria</b>
 * e <b>destinatário é o emitente do XML</b> — o fornecedor, com os dados <b>lidos do próprio XML
 * arquivado</b>, não do cadastro de fornecedor. O cadastro é editável e pode ter divergido; o XML é
 * o que o fornecedor declarou, está imutável no bucket, e é justamente por existir que esta rotina
 * pode acontecer.
 *
 * <h2>Espelhar, não recalcular (a mesma regra do B9)</h2>
 *
 * <p>Toda a tributação vem de {@code entrada_nfe_item}, gravada no ato da entrada (V051). O motor
 * tributário <b>não roda aqui</b>: ele produziria os números do cadastro de hoje, e a devolução
 * sairia divergente da nota que ela devolve — o oposto do que a legislação pede.
 *
 * <p><b>O CFOP é a única coisa que muda</b>, e muda porque tem de mudar: o da entrada é o da venda
 * do fornecedor. Ver {@link CfopDevolucaoCompra}.
 *
 * <h2>Um item devolvido pode virar vários itens na nota</h2>
 *
 * <p>A mesma variação pode ocupar mais de um {@code nItem} na nota do fornecedor (grade, lotes) —
 * e cada um tem tributação própria. A quantidade devolvida é alocada entre eles em ordem de
 * {@code numero_item} (FIFO) e cada alocação vira um item da devolução, referenciando o
 * {@code nItem} de origem. Sem isso, uma devolução de 3 unidades espalhadas em 2 itens da nota
 * original teria de escolher arbitrariamente uma tributação para o todo.
 */
@Component
public class DevolucaoCompraFiscalAssembler {

    private final JdbcClient jdbc;
    private final ArmazenamentoPrivado armazenamento;
    private final NinerProperties.RespTec respTec;
    private final CsrtService csrt;

    public DevolucaoCompraFiscalAssembler(JdbcClient jdbc, ArmazenamentoPrivado armazenamento,
                                          NinerProperties propriedades, CsrtService csrt) {
        this.jdbc = jdbc;
        this.armazenamento = armazenamento;
        this.respTec = propriedades.fiscal().respTec();
        this.csrt = csrt;
    }

    /**
     * @return vazio quando o fiscal está desligado para a empresa (F12 — a devolução acontece, nota
     *         nenhuma é criada, nenhum erro é lançado)
     * @throws MontagemInvalidaException quando falta algo que impede montar a nota — sempre com o
     *         motivo por extenso (F11), nunca transmitindo o que a SEFAZ vai recusar
     */
    @Transactional(readOnly = true)
    public Optional<DevolucaoParaMontar> montar(long idEmpresa, long idMovimentoDevolucao) {
        ConfigEmpresa config = buscarConfig(idEmpresa);
        if (config == null || !config.emiteNfe()) {
            return Optional.empty();
        }

        Devolucao dev = buscarDevolucao(idMovimentoDevolucao);
        NfeXmlParser.EmitenteNfe fornecedor = lerEmitenteDoXmlArquivado(dev.idMovimentoOrigem());
        List<ItemOriginal> originais = buscarItensDaNotaDeEntrada(dev.idMovimentoOrigem());

        List<ItemDevolucao> itens = alocarEEspelhar(idMovimentoDevolucao, originais);
        if (itens.isEmpty()) {
            throw new MontagemInvalidaException(
                    "A devolução não tem itens com correspondência na nota de entrada — não há o que devolver "
                            + "fiscalmente.");
        }
        exigirTributacaoCompativelComOCrt(config, itens);

        Emitente emitente = new Emitente(config.cnpj(), config.razaoSocial(), config.nomeFantasia(),
                config.inscricaoEstadual(), config.crt(), config.logradouro(), config.numero(),
                config.complemento(), config.bairro(), config.codigoMunicipioIbge(), config.cidade(),
                config.uf(), config.cep(), config.telefone());

        DestinatarioDevolucao destinatario = destinatario(fornecedor);
        // idDest: 1 interna, 2 interestadual — decidido pela UF do fornecedor contra a da loja.
        int idDestino = config.uf() != null && config.uf().equalsIgnoreCase(fornecedor.uf()) ? 1 : 2;

        return Optional.of(new DevolucaoParaMontar(
                config.ambiente(), config.serieNfe(), 0, 0, OffsetDateTime.now(),
                "DEVOLUCAO DE COMPRA", dev.chaveNfeOrigem(),
                // 1 = SAÍDA: a mercadoria está voltando ao fornecedor. É o que distingue esta nota
                // da devolução ao consumidor, que é de entrada.
                1, idDestino,
                emitente, destinatario, itens, somar(itens),
                "Devolucao de compra referente a NF-e " + dev.chaveNfeOrigem()
                        + (dev.notaFiscalOrigem() == null ? "" : " (nota " + dev.notaFiscalOrigem() + ")"),
                new ResponsavelTecnico(respTec.cnpj(), respTec.contato(), respTec.email(), respTec.telefone(),
                        csrt.buscar(config.uf(), config.ambiente().codigo())
                                .map(CsrtService.Csrt::idCsrt).orElse(null),
                        csrt.buscar(config.uf(), config.ambiente().codigo())
                                .map(CsrtService.Csrt::codigo).orElse(null)),
                "Nainer 1.0"));
    }

    /**
     * Recusa montar quando a tributação espelhada do fornecedor é <b>incompatível com o CRT da
     * loja</b> (auditoria 2026-08-21, item 19; decisão do dono do produto em 2026-08-22).
     *
     * <p>O espelhamento copia {@code cst_icms} de {@code entrada_nfe_item} — a tributação que <b>o
     * fornecedor</b> declarou — e o montador emite {@code ICMS00}/{@code ICMS20} com {@code pICMS}
     * e {@code vICMS} destacados. Mas o {@code <emit>} desta nota leva o CRT da <b>nossa</b> loja,
     * que por DF37 é 1 (Simples), 2 (Simples com excesso de sublimite) ou 4 (MEI). O motor
     * tributário declara o invariante: <i>CRT 1/4 emite com CSOSN; só o CRT 2, com excesso de
     * sublimite, pode usar CST</i> — e esta rotina não passa pelo motor, por desenho.
     *
     * <p>Sem esta recusa, uma loja do Simples que compra de distribuidor do Lucro Real (CST 00,
     * pICMS 18) e devolve emite NF-e com {@code <CRT>1</CRT>} e {@code <ICMS00>} destacando ICMS
     * próprio. Na melhor hipótese a SEFAZ rejeita por incompatibilidade; na pior <b>autoriza</b>, e
     * a loja destacou imposto que não apura, gerando crédito indevido ao fornecedor.
     *
     * <p>⚠️ <b>Recusar é o mínimo seguro, não a solução final.</b> A conversão para CSOSN (102, ou
     * 500 quando a entrada veio com ST) levando o ICMS original para {@code infAdic} é regra
     * tributária e depende do contador chancelar. Enquanto isso, parar é melhor que emitir
     * documento fiscal errado — e é o mesmo padrão que o montador já usa para CSOSN 202/900.
     */
    private static void exigirTributacaoCompativelComOCrt(ConfigEmpresa config, List<ItemDevolucao> itens) {
        if (config.crt() == 2) {
            return;                       // único CRT que pode destacar ICMS próprio por CST
        }
        boolean algumComCst = itens.stream().anyMatch(i -> i.cstIcms() != null && !i.cstIcms().isBlank());
        if (algumComCst) {
            throw new MontagemInvalidaException(
                    "A nota de entrada traz tributação por CST (ICMS destacado pelo fornecedor), e esta empresa "
                            + "emite pelo Simples Nacional (CRT " + config.crt() + "), que usa CSOSN. Emitir assim "
                            + "destacaria imposto que a loja não apura. A nota de devolução não pode ser montada — "
                            + "consulte o contador para definir o CSOSN correto desta operação.");
        }
    }

    /**
     * Aloca a quantidade devolvida de cada variação entre os itens da nota original, em ordem de
     * {@code numero_item}, e rateia a tributação pela proporção devolvida — mesma aritmética do B9:
     * quando a devolução é total a razão é 1 e os valores passam intactos, que é o caso comum.
     */
    private List<ItemDevolucao> alocarEEspelhar(long idMovimentoDevolucao, List<ItemOriginal> originais) {
        Map<Long, BigDecimal> aDevolverPorVariacao = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT id_variacao, SUM(qtd_produto) AS qtd
                          FROM produto_movimento_detalhe
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                         GROUP BY id_variacao
                        """)
                .param(idMovimentoDevolucao)
                .query((rs, n) -> aDevolverPorVariacao.put(rs.getLong("id_variacao"), rs.getBigDecimal("qtd")))
                .list();

        List<ItemDevolucao> itens = new ArrayList<>();
        int nItem = 1;
        for (ItemOriginal original : originais) {
            BigDecimal restante = aDevolverPorVariacao.get(original.idVariacao());
            if (restante == null || restante.signum() <= 0) {
                continue;
            }
            BigDecimal alocada = restante.min(original.quantidade());
            aDevolverPorVariacao.put(original.idVariacao(), restante.subtract(alocada));

            BigDecimal proporcao = original.quantidade().signum() == 0
                    ? BigDecimal.ONE
                    : alocada.divide(original.quantidade(), 10, RoundingMode.HALF_UP);
            boolean total = proporcao.compareTo(BigDecimal.ONE) == 0;

            itens.add(new ItemDevolucao(
                    nItem++, original.numeroItem(),
                    original.codigoProduto(), original.codigoEan(), original.descricao(),
                    original.codigoNcm(), original.cest(),
                    CfopDevolucaoCompra.de(original.cfop()),
                    original.unidadeComercial(), alocada, original.valorUnitario(),
                    ratear(original.valorProduto(), proporcao, total),
                    // ⛔ O desconto que o FORNECEDOR deu na nota de entrada, rateado pela quantidade
                    // devolvida (2026-09-02, pendência 60 — o mesmo defeito da devolução de venda,
                    // na ponta vizinha): sem ele a nota de saída declarava o valor BRUTO de uma
                    // compra que custou menos.
                    ratear(original.valorDesconto(), proporcao, total),
                    original.unidadeComercial(), alocada, original.valorUnitario(),
                    original.origemMercadoria(),
                    original.cstIcms(), original.csosn(),
                    ratear(original.baseIcms(), proporcao, total), original.percReducaoBc(),
                    original.aliquotaIcms(), ratear(original.valorIcms(), proporcao, total),
                    ratear(original.baseSt(), proporcao, total), ratear(original.valorIcmsSt(), proporcao, total),
                    ratear(original.baseStRetido(), proporcao, total),
                    ratear(original.icmsStRetido(), proporcao, total),
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    original.cstPis(), ratear(original.basePis(), proporcao, total),
                    original.aliquotaPis(), ratear(original.valorPis(), proporcao, total),
                    original.cstCofins(), ratear(original.baseCofins(), proporcao, total),
                    original.aliquotaCofins(), ratear(original.valorCofins(), proporcao, total),
                    // IBS/CBS: a nota do fornecedor de hoje ainda nao os declara (obrigatorio so em
                    // 04/01/2027). Zerado ate a entrada passar a trazer o grupo — nunca inventado.
                    null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        BigDecimal sobra = aDevolverPorVariacao.values().stream()
                .filter(q -> q.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sobra.signum() > 0) {
            // Só acontece se o movimento de devolução tiver escapado da validação de saldo — barrar
            // aqui é defesa em profundidade: uma nota que devolve mais do que a original trouxe é
            // rejeição garantida, e pior, uma que devolve item que a nota não tinha é fraude.
            throw new MontagemInvalidaException(
                    "A devolução tem quantidade acima do que a nota de entrada trouxe — a nota não pode ser "
                            + "montada. Cancele a devolução e refaça a seleção.");
        }
        return itens;
    }

    private static BigDecimal ratear(BigDecimal valor, BigDecimal proporcao, boolean total) {
        if (valor == null) {
            return BigDecimal.ZERO;
        }
        return total ? valor : valor.multiply(proporcao).setScale(2, RoundingMode.HALF_UP);
    }

    private TotaisDevolucao somar(List<ItemDevolucao> itens) {
        BigDecimal baseIcms = BigDecimal.ZERO, icms = BigDecimal.ZERO, baseSt = BigDecimal.ZERO;
        BigDecimal icmsSt = BigDecimal.ZERO, produtos = BigDecimal.ZERO, desconto = BigDecimal.ZERO;
        BigDecimal pis = BigDecimal.ZERO, cofins = BigDecimal.ZERO;
        for (ItemDevolucao i : itens) {
            baseIcms = baseIcms.add(i.baseCalculoIcms());
            icms = icms.add(i.valorIcms());
            baseSt = baseSt.add(i.baseCalculoSt());
            icmsSt = icmsSt.add(i.valorIcmsSt());
            produtos = produtos.add(i.valorProduto());
            desconto = desconto.add(XmlFiscal.nz(i.valorDesconto()));
            pis = pis.add(i.valorPis());
            cofins = cofins.add(i.valorCofins());
        }
        // vNF = produtos − desconto + ST: o imposto retido compõe o total da nota de devolução do
        // mesmo jeito que compôs o da nota de entrada, e o desconto do fornecedor sai dele pelo
        // mesmo motivo (2026-09-02, pendência 60). Os dois lados mudam juntos: somar o desconto e
        // não descontá-lo do total deixaria o XML internamente inconsistente.
        return new TotaisDevolucao(baseIcms, icms, baseSt, icmsSt, produtos, desconto,
                pis, cofins, produtos.subtract(desconto).add(icmsSt),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Lê o emitente do XML <b>arquivado</b> da entrada. Prefere o bucket (onde o XML descansa desde
     * a V051) e cai na coluna legada quando o arquivamento ainda não rodou — as duas fontes contêm
     * o mesmo documento.
     */
    private NfeXmlParser.EmitenteNfe lerEmitenteDoXmlArquivado(long idMovimentoEntrada) {
        record Guardado(String chave, String bruto) {
        }
        Guardado guardado = jdbc.sql("""
                        SELECT xml_objeto_bucket, xml_bruto FROM entrada_xml
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                        """)
                .param(idMovimentoEntrada)
                .query((rs, n) -> new Guardado(rs.getString("xml_objeto_bucket"), rs.getString("xml_bruto")))
                .optional()
                .orElseThrow(() -> new MontagemInvalidaException(
                        "A entrada não tem XML arquivado — sem a nota de origem não é possível emitir a "
                                + "devolução ao fornecedor."));

        byte[] xml;
        if (guardado.chave() != null) {
            xml = armazenamento.ler(AreaPrivada.FISCAL_XML, guardado.chave());
        } else if (guardado.bruto() != null) {
            xml = guardado.bruto().getBytes(StandardCharsets.UTF_8);
        } else {
            throw new MontagemInvalidaException("A entrada não tem XML arquivado.");
        }
        return NfeXmlParser.parse(new java.io.ByteArrayInputStream(xml)).emitente();
    }

    /** {@code indIEDest = 1} (contribuinte com IE): fornecedor que emitiu NF-e é contribuinte. */
    private static DestinatarioDevolucao destinatario(NfeXmlParser.EmitenteNfe f) {
        if (f.cnpj() == null || f.cnpj().isBlank()) {
            throw new MontagemInvalidaException("O XML da entrada não traz o CNPJ do fornecedor.");
        }
        if (f.codigoMunicipio() == 0) {
            throw new MontagemInvalidaException(
                    "O XML da entrada nao traz o codigo IBGE do municipio do fornecedor (cMun), obrigatorio "
                            + "no grupo `dest` da nota de devolucao.");
        }
        return new DestinatarioDevolucao(f.cnpj(), f.razaoSocial(), 1, f.ie(),
                f.logradouro(), f.numero(), null, f.bairro(),
                f.codigoMunicipio(), f.municipio(), f.uf(), f.cep(), f.telefone(), false);
    }

    // ---------------------------------------------------------------- leituras

    private ConfigEmpresa buscarConfig(long idEmpresa) {
        return jdbc.sql("""
                        SELECT fc.emite_nfe, fc.ambiente::text AS ambiente, fc.serie_nfe, fc.crt,
                               e.cnpj, e.razao_social, e.nome_fantasia, e.inscricao_estadual,
                               e.endereco, e.numero, e.complemento, e.bairro, e.codigo_municipio_ibge,
                               e.cidade, e.estado AS uf, e.cep, e.telefone
                          FROM fiscal_config_empresa fc
                          JOIN empresa e ON e.id_tenant = fc.id_tenant AND e.id_empresa = fc.id_empresa
                         WHERE fc.id_tenant = plataforma.tenant_atual() AND fc.id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> new ConfigEmpresa(
                        rs.getBoolean("emite_nfe"), AmbienteSefaz.valueOf(rs.getString("ambiente")),
                        rs.getInt("serie_nfe"), rs.getInt("crt"),
                        rs.getString("cnpj"), rs.getString("razao_social"), rs.getString("nome_fantasia"),
                        rs.getString("inscricao_estadual"), rs.getString("endereco"), rs.getString("numero"),
                        rs.getString("complemento"), rs.getString("bairro"), rs.getInt("codigo_municipio_ibge"),
                        rs.getString("cidade"), rs.getString("uf"), rs.getString("cep"), rs.getString("telefone")))
                .optional().orElse(null);
    }

    private Devolucao buscarDevolucao(long idMovimento) {
        return jdbc.sql("""
                        SELECT d.id_movimento_origem, o.chave_nfe, o.nota_fiscal
                          FROM produto_movimento_mestre d
                          JOIN produto_movimento_mestre o
                            ON o.id_tenant = d.id_tenant AND o.id_movimento = d.id_movimento_origem
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_movimento = ?
                           AND d.tipo_movimento = 'DEVOLUCAO_COMPRA' AND d.cancelado = false
                        """)
                .param(idMovimento)
                .query((rs, n) -> new Devolucao(rs.getLong("id_movimento_origem"), rs.getString("chave_nfe"),
                        (Integer) rs.getObject("nota_fiscal", Integer.class)))
                .optional()
                .orElseThrow(() -> new MontagemInvalidaException(
                        "Devolução de compra não encontrada ou já cancelada."));
    }

    private List<ItemOriginal> buscarItensDaNotaDeEntrada(long idMovimentoEntrada) {
        return jdbc.sql("""
                        SELECT numero_item, id_variacao, codigo_produto, codigo_ean, descricao, codigo_ncm, cest,
                               cfop, unidade_comercial, quantidade, valor_unitario, valor_produto, valor_desconto,
                               origem_mercadoria, cst_icms, csosn, base_calculo_icms, perc_reducao_bc,
                               aliquota_icms, valor_icms, base_calculo_st, valor_icms_st, base_st_retido,
                               icms_st_retido, cst_pis, base_calculo_pis, aliquota_pis, valor_pis,
                               cst_cofins, base_calculo_cofins, aliquota_cofins, valor_cofins
                          FROM entrada_nfe_item
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                           AND id_variacao IS NOT NULL
                         ORDER BY numero_item
                        """)
                .param(idMovimentoEntrada)
                .query((rs, n) -> new ItemOriginal(
                        rs.getInt("numero_item"), rs.getLong("id_variacao"), rs.getString("codigo_produto"),
                        rs.getString("codigo_ean"), rs.getString("descricao"), rs.getString("codigo_ncm"),
                        rs.getString("cest"), rs.getString("cfop"), rs.getString("unidade_comercial"),
                        rs.getBigDecimal("quantidade"), rs.getBigDecimal("valor_unitario"),
                        rs.getBigDecimal("valor_produto"), rs.getBigDecimal("valor_desconto"),
                        rs.getInt("origem_mercadoria"),
                        rs.getString("cst_icms"), rs.getString("csosn"), rs.getBigDecimal("base_calculo_icms"),
                        rs.getBigDecimal("perc_reducao_bc"), rs.getBigDecimal("aliquota_icms"),
                        rs.getBigDecimal("valor_icms"), rs.getBigDecimal("base_calculo_st"),
                        rs.getBigDecimal("valor_icms_st"), rs.getBigDecimal("base_st_retido"),
                        rs.getBigDecimal("icms_st_retido"), rs.getString("cst_pis"),
                        rs.getBigDecimal("base_calculo_pis"), rs.getBigDecimal("aliquota_pis"),
                        rs.getBigDecimal("valor_pis"), rs.getString("cst_cofins"),
                        rs.getBigDecimal("base_calculo_cofins"), rs.getBigDecimal("aliquota_cofins"),
                        rs.getBigDecimal("valor_cofins")))
                .list();
    }

    private record Devolucao(long idMovimentoOrigem, String chaveNfeOrigem, Integer notaFiscalOrigem) {
    }

    private record ConfigEmpresa(boolean emiteNfe, AmbienteSefaz ambiente, int serieNfe, int crt,
                                 String cnpj, String razaoSocial, String nomeFantasia, String inscricaoEstadual,
                                 String logradouro, String numero, String complemento, String bairro,
                                 int codigoMunicipioIbge, String cidade, String uf, String cep, String telefone) {
    }

    private record ItemOriginal(int numeroItem, long idVariacao, String codigoProduto, String codigoEan,
                                String descricao, String codigoNcm, String cest, String cfop,
                                String unidadeComercial, BigDecimal quantidade, BigDecimal valorUnitario,
                                BigDecimal valorProduto, BigDecimal valorDesconto,
                                int origemMercadoria, String cstIcms, String csosn,
                                BigDecimal baseIcms, BigDecimal percReducaoBc, BigDecimal aliquotaIcms,
                                BigDecimal valorIcms, BigDecimal baseSt, BigDecimal valorIcmsSt,
                                BigDecimal baseStRetido, BigDecimal icmsStRetido, String cstPis,
                                BigDecimal basePis, BigDecimal aliquotaPis, BigDecimal valorPis,
                                String cstCofins, BigDecimal baseCofins, BigDecimal aliquotaCofins,
                                BigDecimal valorCofins) {
    }
}
