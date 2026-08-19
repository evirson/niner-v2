package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DestinatarioDevolucao;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DevolucaoParaMontar;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.ItemDevolucao;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.TotaisDevolucao;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Emitente;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ResponsavelTecnico;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Monta o pedido de emissão da <b>NF-e de devolução</b> a partir de uma devolução já gravada —
 * bloco B9, §10.2. É o equivalente do {@link VendaFiscalAssembler} para o caminho de entrada, com
 * uma diferença que define tudo: <b>não existe motor tributário aqui</b>.
 *
 * <h2>Espelhar, não recalcular</h2>
 *
 * <p>Todo valor tributário vem de {@code documento_fiscal_item} da NFC-e original — a nota que
 * está sendo devolvida. Recalcular pelo motor produziria os números do cadastro de <b>hoje</b>
 * (alíquota, CSOSN, perfil fiscal e preço podem ter mudado desde a venda), e a nota de entrada
 * sairia divergente da nota de saída que ela referencia. A legislação pede o oposto: mesmos
 * valores e bases, para o estorno bater.
 *
 * <h2>Devolução parcial: rateio proporcional</h2>
 *
 * <p>Quando o cliente devolve 1 de 3 unidades, os valores do item original são rateados pela
 * proporção {@code qtdDevolvida / qtdVendida}. É aritmética simples de propósito — qualquer regra
 * mais esperta aqui seria recálculo disfarçado.
 *
 * <h2>Quando NÃO emite</h2>
 *
 * <p>Devolve {@code Optional.empty()} — sem erro, sem documento — em três casos, todos decididos
 * com o dono do produto:
 * <ul>
 *   <li>{@code emite_nfe} desligado para a empresa (gate F12, igual à NFC-e);</li>
 *   <li>a devolução não tem venda de origem informada (sem nota para referenciar, não há
 *       devolução fiscal — só o vale-mercadoria);</li>
 *   <li>a venda de origem não tem NFC-e autorizada (venda sem fiscal: nada a devolver).</li>
 * </ul>
 */
@Component
public class DevolucaoFiscalAssembler {

    /** CFOP de devolução de venda, mercadoria de terceiros, operação interna (`cfg_cfop`, V034). */
    private static final String CFOP_DEVOLUCAO_INTERNA = "1202";
    /** Idem, mercadoria sujeita a ST na condição de substituído — quando a venda saiu com ST. */
    private static final String CFOP_DEVOLUCAO_INTERNA_ST = "1411";

    private final JdbcClient jdbc;
    private final NinerProperties.RespTec respTec;

    public DevolucaoFiscalAssembler(JdbcClient jdbc, NinerProperties propriedades) {
        this.jdbc = jdbc;
        this.respTec = propriedades.fiscal().respTec();
    }

    @Transactional(readOnly = true)
    public Optional<DevolucaoParaMontar> montar(long idEmpresa, long idDevolucao) {
        ConfigEmpresa config = buscarConfig(idEmpresa);
        if (config == null || !config.emiteNfe()) {
            return Optional.empty();
        }

        Long idVendaOrigem = buscarVendaDeOrigem(idDevolucao);
        if (idVendaOrigem == null) {
            return Optional.empty();
        }

        NotaOriginal original = buscarNotaOriginalAutorizada(idVendaOrigem);
        if (original == null) {
            return Optional.empty();
        }

        List<ItemDevolvido> devolvidos = buscarItensDevolvidos(idDevolucao);
        if (devolvidos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Devolução nº " + idDevolucao + " não tem itens — não há o que emitir.");
        }
        Map<Long, ItemOriginal> itensOriginais = buscarItensDaNotaOriginal(original.idDocumentoFiscal());

        List<ItemDevolucao> itens = new ArrayList<>();
        int nItem = 1;
        for (ItemDevolvido d : devolvidos) {
            ItemOriginal o = itensOriginais.get(d.idVariacao());
            if (o == null) {
                // Só acontece se a devolução tiver escapado da restrição RN-06 (produto que não
                // está na venda) — barrar aqui de novo é defesa em profundidade: montar uma nota
                // de devolução de item que a NFC-e não vendeu é rejeição garantida na SEFAZ.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        ("O produto \"%s\" não consta na nota fiscal da venda nº %d, então não pode entrar "
                                + "na NF-e de devolução.").formatted(d.descricao(), idVendaOrigem));
            }
            itens.add(espelhar(nItem++, d, o));
        }

        DestinatarioDevolucao destinatario = resolverDestinatario(original, config);
        TotaisDevolucao totais = somar(itens);

        Emitente emitente = new Emitente(config.cnpj(), config.razaoSocial(), config.nomeFantasia(),
                config.inscricaoEstadual(), config.crt(), config.logradouro(), config.numero(),
                config.complemento(), config.bairro(), config.codigoMunicipioIbge(), config.cidade(),
                config.uf(), config.cep(), config.telefone());

        return Optional.of(new DevolucaoParaMontar(
                config.ambiente(), config.serieNfe(), 0, 0, OffsetDateTime.now(),
                "DEVOLUCAO DE VENDA", original.chaveAcesso(), emitente, destinatario, itens, totais,
                montarInfoComplementar(original, destinatario, idDevolucao),
                new ResponsavelTecnico(respTec.cnpj(), respTec.contato(), respTec.email(), respTec.telefone()),
                "Niner PDV 1.0"));
    }

    /**
     * Copia a tributação do item original, rateada pela quantidade devolvida. O rateio usa a razão
     * {@code qtdDevolvida / qtdVendida} sobre cada valor — quando a devolução é total a razão é 1 e
     * os valores passam intactos, que é o caso mais comum.
     */
    private ItemDevolucao espelhar(int nItem, ItemDevolvido d, ItemOriginal o) {
        BigDecimal proporcao = o.quantidade().signum() == 0
                ? BigDecimal.ONE
                : d.qtd().divide(o.quantidade(), 10, RoundingMode.HALF_UP);
        boolean total = proporcao.compareTo(BigDecimal.ONE) == 0;

        return new ItemDevolucao(
                nItem, o.numeroItem(), o.codigoProduto(), o.codigoEan(), o.descricao(), o.codigoNcm(), o.cest(),
                cfopDevolucao(o), o.unidadeComercial(), d.qtd(), o.valorUnitario(),
                ratear(o.valorProduto(), proporcao, total),
                o.unidadeTributavel(), d.qtd(), o.valorUnitarioTrib(), o.origemMercadoria(),
                o.cstIcms(), o.csosn(),
                ratear(o.baseCalculoIcms(), proporcao, total), o.percReducaoBc(), o.aliquotaIcms(),
                ratear(o.valorIcms(), proporcao, total),
                ratear(o.baseCalculoSt(), proporcao, total), ratear(o.valorIcmsSt(), proporcao, total),
                ratear(o.baseStRetido(), proporcao, total), ratear(o.icmsStRetido(), proporcao, total),
                o.percCreditoSn(), ratear(o.valorCreditoSn(), proporcao, total),
                o.cstPis(), ratear(o.baseCalculoPis(), proporcao, total), o.aliquotaPis(),
                ratear(o.valorPis(), proporcao, total),
                o.cstCofins(), ratear(o.baseCalculoCofins(), proporcao, total), o.aliquotaCofins(),
                ratear(o.valorCofins(), proporcao, total),
                o.cstIbsCbs(), o.cclasstrib(), ratear(o.baseIbsCbs(), proporcao, total),
                o.aliquotaIbsUf(), ratear(o.valorIbsUf(), proporcao, total),
                o.aliquotaIbsMun(), ratear(o.valorIbsMun(), proporcao, total),
                o.aliquotaCbs(), ratear(o.valorCbs(), proporcao, total),
                ratear(o.valorTotalTributos(), proporcao, total));
    }

    /** Devolução total passa o valor intacto (evita ruído de arredondamento onde não há rateio). */
    private static BigDecimal ratear(BigDecimal valor, BigDecimal proporcao, boolean total) {
        BigDecimal v = valor == null ? BigDecimal.ZERO : valor;
        return total ? v : v.multiply(proporcao).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * CFOP da entrada. Mercadoria que saiu com ST (CSOSN 500 / CST 60 — comum em calçado e
     * confecção, o ramo do piloto) volta pelo 1411; o resto pelo 1202. Ambos já semeados em
     * {@code cfg_cfop} (V034).
     *
     * <p>Só CFOP <b>interno</b>: a NFC-e é presencial e o consumidor é local por definição, então
     * a devolução dela nunca é interestadual. Se um dia a NF-e de venda entrar (§4.2), aí sim o
     * 2202 passa a fazer sentido e esta decisão precisa da UF do destinatário.
     */
    private static String cfopDevolucao(ItemOriginal o) {
        boolean comSt = "500".equals(o.csosn()) || "60".equals(o.cstIcms());
        return comSt ? CFOP_DEVOLUCAO_INTERNA_ST : CFOP_DEVOLUCAO_INTERNA;
    }

    /**
     * Ajuste SINIEF 8/2026: o {@code dest} leva os dados do destinatário da nota de saída original.
     *
     * <p>⚠️ <b>DF20 — o caso mais comum do balcão</b>: a NFC-e saiu sem CPF (consumidor não
     * identificado), então não há destinatário para copiar. A saída é a <b>própria loja</b> como
     * destinatária — a mercadoria de fato volta para o estoque de quem a vendeu —, com a
     * justificativa nas informações complementares (ver {@link #montarInfoComplementar}). Quando a
     * venda teve CPF informado, o cliente vira o destinatário, e aí o cadastro dele precisa ter
     * endereço: a NF-e 55 exige {@code enderDest} completo, ao contrário da NFC-e.
     */
    private DestinatarioDevolucao resolverDestinatario(NotaOriginal original, ConfigEmpresa config) {
        if (original.idCliente() != null) {
            ClienteDestinatario c = buscarCliente(original.idCliente());
            if (c != null && c.cpfCnpj() != null && !c.cpfCnpj().isBlank()) {
                exigirEnderecoDoCliente(c);
                return new DestinatarioDevolucao(c.cpfCnpj(), c.nome(), c.indicadorIe(), c.inscricaoEstadual(),
                        c.logradouro(), c.numero(), c.complemento(), c.bairro(), c.codigoMunicipioIbge(),
                        c.cidade(), c.uf(), c.cep(), c.telefone(), false);
            }
        }
        return new DestinatarioDevolucao(config.cnpj(), config.razaoSocial(), 1, config.inscricaoEstadual(),
                config.logradouro(), config.numero(), config.complemento(), config.bairro(),
                config.codigoMunicipioIbge(), config.cidade(), config.uf(), config.cep(), config.telefone(), true);
    }

    private void exigirEnderecoDoCliente(ClienteDestinatario c) {
        if (c.logradouro() == null || c.logradouro().isBlank()
                || c.bairro() == null || c.bairro().isBlank()
                || c.codigoMunicipioIbge() == 0 || c.uf() == null || c.uf().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ("A NF-e de devolução precisa do endereço completo do cliente \"%s\" (logradouro, bairro, "
                            + "município e UF) — a NFC-e da venda não exige, mas a nota de entrada exige. "
                            + "Complete o cadastro do cliente e tente de novo.").formatted(c.nome()));
        }
    }

    /** Rastreabilidade em texto livre — o que a nota referencia e, quando aplicável, por que a
     *  loja é a própria destinatária (o auditor lê isto antes de perguntar). */
    private static String montarInfoComplementar(NotaOriginal original, DestinatarioDevolucao dest, long idDevolucao) {
        StringBuilder s = new StringBuilder()
                .append("Devolucao de mercadoria referente a NFC-e ")
                .append(original.chaveAcesso())
                .append(" (vale-mercadoria no ").append(idDevolucao).append(").");
        if (dest.consumidorNaoIdentificado()) {
            s.append(" Nota de entrada emitida contra o proprio estabelecimento porque a venda original ")
                    .append("foi para consumidor final nao identificado.");
        }
        return s.toString();
    }

    private static TotaisDevolucao somar(List<ItemDevolucao> itens) {
        BigDecimal bcIcms = BigDecimal.ZERO, icms = BigDecimal.ZERO, bcSt = BigDecimal.ZERO, st = BigDecimal.ZERO;
        BigDecimal prod = BigDecimal.ZERO, pis = BigDecimal.ZERO, cofins = BigDecimal.ZERO;
        BigDecimal baseIbs = BigDecimal.ZERO, ibsUf = BigDecimal.ZERO, ibsMun = BigDecimal.ZERO;
        BigDecimal cbs = BigDecimal.ZERO, trib = BigDecimal.ZERO;
        for (ItemDevolucao i : itens) {
            bcIcms = bcIcms.add(XmlFiscal.nz(i.baseCalculoIcms()));
            icms = icms.add(XmlFiscal.nz(i.valorIcms()));
            bcSt = bcSt.add(XmlFiscal.nz(i.baseCalculoSt()));
            st = st.add(XmlFiscal.nz(i.valorIcmsSt()));
            prod = prod.add(XmlFiscal.nz(i.valorProduto()));
            pis = pis.add(XmlFiscal.nz(i.valorPis()));
            cofins = cofins.add(XmlFiscal.nz(i.valorCofins()));
            baseIbs = baseIbs.add(XmlFiscal.nz(i.baseIbsCbs()));
            ibsUf = ibsUf.add(XmlFiscal.nz(i.valorIbsUf()));
            ibsMun = ibsMun.add(XmlFiscal.nz(i.valorIbsMun()));
            cbs = cbs.add(XmlFiscal.nz(i.valorCbs()));
            trib = trib.add(XmlFiscal.nz(i.valorTotalTributos()));
        }
        // vNF = soma dos produtos: IBS/CBS não compõem o total (DF32, resolvida pelo XSD no B5).
        return new TotaisDevolucao(bcIcms, icms, bcSt, st, prod, BigDecimal.ZERO, pis, cofins, prod,
                baseIbs, ibsUf, ibsMun, cbs, trib);
    }

    // ---------------------------------------------------------------- leituras

    private Long buscarVendaDeOrigem(long idDevolucao) {
        return jdbc.sql("""
                        SELECT id_venda_credito FROM venda_devolucao
                         WHERE id_tenant = plataforma.tenant_atual() AND id_devolucao = ?
                        """)
                .param(idDevolucao)
                .query((rs, n) -> {
                    long v = rs.getLong("id_venda_credito");
                    return rs.wasNull() ? null : v;
                })
                .optional().orElse(null);
    }

    /** A NFC-e da venda, só quando AUTORIZADA — em contingência a chave existe mas o protocolo
     *  ainda não, e referenciar uma nota que a SEFAZ não confirmou seria rejeição certa. */
    private NotaOriginal buscarNotaOriginalAutorizada(long idVenda) {
        return jdbc.sql("""
                        SELECT id_documento_fiscal, chave_acesso, id_cliente
                          FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual() AND id_venda = ?
                           AND situacao = 'AUTORIZADO' AND modelo = 65
                         ORDER BY criado_em DESC LIMIT 1
                        """)
                .param(idVenda)
                .query((rs, n) -> {
                    Integer idCliente = (Integer) rs.getObject("id_cliente");
                    return new NotaOriginal(rs.getLong("id_documento_fiscal"), rs.getString("chave_acesso"), idCliente);
                })
                .optional().orElse(null);
    }

    private List<ItemDevolvido> buscarItensDevolvidos(long idDevolucao) {
        return jdbc.sql("""
                        SELECT pmd.id_variacao, pmd.qtd_produto, p.descricao
                          FROM produto_movimento_mestre pmm
                          JOIN produto_movimento_detalhe pmd
                                 ON pmd.id_movimento = pmm.id_movimento AND pmd.id_tenant = pmm.id_tenant
                          JOIN produto_barra pb ON pb.id_variacao = pmd.id_variacao AND pb.id_tenant = pmd.id_tenant
                          JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
                         WHERE pmm.id_tenant = plataforma.tenant_atual() AND pmm.id_devolucao = ?
                               AND pmm.tipo_movimento = 'DEVOLUCAO'
                         ORDER BY pmd.id_movimento_detalhe
                        """)
                .param(idDevolucao)
                .query((rs, n) -> new ItemDevolvido(
                        rs.getLong("id_variacao"), rs.getBigDecimal("qtd_produto"), rs.getString("descricao")))
                .list();
    }

    /** Itens da NFC-e original, por {@code id_variacao} — é por ele que a devolução casa cada
     *  produto com o item fiscal correspondente (e com o {@code numero_item}, que vai no
     *  {@code DFeReferenciado}). */
    private Map<Long, ItemOriginal> buscarItensDaNotaOriginal(long idDocumentoFiscal) {
        Map<Long, ItemOriginal> mapa = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT numero_item, id_variacao, codigo_produto, codigo_ean, descricao, codigo_ncm, cest,
                               unidade_comercial, quantidade, valor_unitario, valor_produto,
                               unidade_tributavel, valor_unitario_trib, origem_mercadoria,
                               cst_icms, csosn, base_calculo_icms, perc_reducao_bc, aliquota_icms, valor_icms,
                               base_calculo_st, valor_icms_st, base_st_retido, icms_st_retido,
                               perc_credito_sn, valor_credito_sn,
                               cst_pis, base_calculo_pis, aliquota_pis, valor_pis,
                               cst_cofins, base_calculo_cofins, aliquota_cofins, valor_cofins,
                               cst_ibscbs, cclasstrib, base_ibscbs, aliquota_ibs_uf, valor_ibs_uf,
                               aliquota_ibs_mun, valor_ibs_mun, aliquota_cbs, valor_cbs, valor_total_tributos
                          FROM documento_fiscal_item
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                         ORDER BY numero_item
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new ItemOriginal(
                        rs.getInt("numero_item"), rs.getLong("id_variacao"), rs.getString("codigo_produto"),
                        rs.getString("codigo_ean"), rs.getString("descricao"), rs.getString("codigo_ncm"),
                        rs.getString("cest"), rs.getString("unidade_comercial"), rs.getBigDecimal("quantidade"),
                        rs.getBigDecimal("valor_unitario"), rs.getBigDecimal("valor_produto"),
                        rs.getString("unidade_tributavel"), rs.getBigDecimal("valor_unitario_trib"),
                        rs.getInt("origem_mercadoria"),
                        rs.getString("cst_icms"), rs.getString("csosn"), rs.getBigDecimal("base_calculo_icms"),
                        rs.getBigDecimal("perc_reducao_bc"), rs.getBigDecimal("aliquota_icms"),
                        rs.getBigDecimal("valor_icms"), rs.getBigDecimal("base_calculo_st"),
                        rs.getBigDecimal("valor_icms_st"), rs.getBigDecimal("base_st_retido"),
                        rs.getBigDecimal("icms_st_retido"), rs.getBigDecimal("perc_credito_sn"),
                        rs.getBigDecimal("valor_credito_sn"),
                        rs.getString("cst_pis"), rs.getBigDecimal("base_calculo_pis"),
                        rs.getBigDecimal("aliquota_pis"), rs.getBigDecimal("valor_pis"),
                        rs.getString("cst_cofins"), rs.getBigDecimal("base_calculo_cofins"),
                        rs.getBigDecimal("aliquota_cofins"), rs.getBigDecimal("valor_cofins"),
                        rs.getString("cst_ibscbs"), rs.getString("cclasstrib"), rs.getBigDecimal("base_ibscbs"),
                        rs.getBigDecimal("aliquota_ibs_uf"), rs.getBigDecimal("valor_ibs_uf"),
                        rs.getBigDecimal("aliquota_ibs_mun"), rs.getBigDecimal("valor_ibs_mun"),
                        rs.getBigDecimal("aliquota_cbs"), rs.getBigDecimal("valor_cbs"),
                        rs.getBigDecimal("valor_total_tributos")))
                .list()
                .forEach(i -> mapa.putIfAbsent(i.idVariacao(), i));
        return mapa;
    }

    private ClienteDestinatario buscarCliente(int idCliente) {
        // ⚠️ `rg_ie` guarda RG (pessoa física) OU inscrição estadual (jurídica) na mesma coluna —
        // só vale como IE quando `indicador_ie = 1` (contribuinte), que é o único caso em que o
        // montador emite a tag IE. Mandar um RG como IE seria rejeição na SEFAZ.
        return jdbc.sql("""
                        SELECT cpf_cnpj, nome, indicador_ie, rg_ie, endereco, numero, complemento,
                               bairro, codigo_municipio_ibge, cidade, estado, cep, telefone
                          FROM cliente
                         WHERE id_tenant = plataforma.tenant_atual() AND id_cliente = ?
                        """)
                .param(idCliente)
                .query((rs, n) -> new ClienteDestinatario(
                        rs.getString("cpf_cnpj"), rs.getString("nome"), rs.getInt("indicador_ie"),
                        rs.getString("rg_ie"), rs.getString("endereco"), rs.getString("numero"),
                        rs.getString("complemento"), rs.getString("bairro"), rs.getInt("codigo_municipio_ibge"),
                        rs.getString("cidade"), rs.getString("estado"), rs.getString("cep"), rs.getString("telefone")))
                .optional().orElse(null);
    }

    private ConfigEmpresa buscarConfig(long idEmpresa) {
        return jdbc.sql("""
                        SELECT c.emite_nfe, c.ambiente::text AS ambiente, c.serie_nfe, c.crt,
                               e.cnpj, e.razao_social, e.nome_fantasia, e.inscricao_estadual,
                               e.endereco, e.numero, e.complemento, e.bairro,
                               e.codigo_municipio_ibge, e.cidade, e.estado, e.cep, e.telefone
                          FROM fiscal_config_empresa c
                          JOIN empresa e ON e.id_empresa = c.id_empresa AND e.id_tenant = c.id_tenant
                         WHERE c.id_tenant = plataforma.tenant_atual() AND c.id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> new ConfigEmpresa(
                        rs.getBoolean("emite_nfe"), AmbienteSefaz.valueOf(rs.getString("ambiente")),
                        rs.getInt("serie_nfe"), rs.getInt("crt"), rs.getString("cnpj"), rs.getString("razao_social"),
                        rs.getString("nome_fantasia"), rs.getString("inscricao_estadual"), rs.getString("endereco"),
                        rs.getString("numero"), rs.getString("complemento"), rs.getString("bairro"),
                        rs.getInt("codigo_municipio_ibge"), rs.getString("cidade"), rs.getString("estado"),
                        rs.getString("cep"), rs.getString("telefone")))
                .optional().orElse(null);
    }

    // ---------------------------------------------------------------- registros internos

    private record NotaOriginal(long idDocumentoFiscal, String chaveAcesso, Integer idCliente) {
    }

    private record ItemDevolvido(long idVariacao, BigDecimal qtd, String descricao) {
    }

    private record ClienteDestinatario(String cpfCnpj, String nome, int indicadorIe, String inscricaoEstadual,
                                        String logradouro, String numero, String complemento, String bairro,
                                        int codigoMunicipioIbge, String cidade, String uf, String cep,
                                        String telefone) {
    }

    private record ConfigEmpresa(boolean emiteNfe, AmbienteSefaz ambiente, int serieNfe, int crt,
                                  String cnpj, String razaoSocial, String nomeFantasia, String inscricaoEstadual,
                                  String logradouro, String numero, String complemento, String bairro,
                                  int codigoMunicipioIbge, String cidade, String uf, String cep, String telefone) {
    }

    private record ItemOriginal(int numeroItem, long idVariacao, String codigoProduto, String codigoEan,
                                 String descricao, String codigoNcm, String cest, String unidadeComercial,
                                 BigDecimal quantidade, BigDecimal valorUnitario, BigDecimal valorProduto,
                                 String unidadeTributavel, BigDecimal valorUnitarioTrib, int origemMercadoria,
                                 String cstIcms, String csosn, BigDecimal baseCalculoIcms, BigDecimal percReducaoBc,
                                 BigDecimal aliquotaIcms, BigDecimal valorIcms, BigDecimal baseCalculoSt,
                                 BigDecimal valorIcmsSt, BigDecimal baseStRetido, BigDecimal icmsStRetido,
                                 BigDecimal percCreditoSn, BigDecimal valorCreditoSn,
                                 String cstPis, BigDecimal baseCalculoPis, BigDecimal aliquotaPis, BigDecimal valorPis,
                                 String cstCofins, BigDecimal baseCalculoCofins, BigDecimal aliquotaCofins,
                                 BigDecimal valorCofins, String cstIbsCbs, String cclasstrib, BigDecimal baseIbsCbs,
                                 BigDecimal aliquotaIbsUf, BigDecimal valorIbsUf, BigDecimal aliquotaIbsMun,
                                 BigDecimal valorIbsMun, BigDecimal aliquotaCbs, BigDecimal valorCbs,
                                 BigDecimal valorTotalTributos) {
    }
}
