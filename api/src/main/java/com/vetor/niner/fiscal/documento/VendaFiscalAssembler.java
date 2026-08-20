package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.fiscal.configuracao.CsrtService;
import com.vetor.niner.fiscal.documento.EmissaoNfceService.PedidoDeEmissao;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Destinatario;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Emitente;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ItemNota;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Pagamento;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ResponsavelTecnico;
import com.vetor.niner.fiscal.motor.MotorTributario;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.ContextoFiscalEmpresa;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.ItemOperacao;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.OperacaoFiscal;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.RegraFiscal;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.TipoDestinatario;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.TipoOperacao;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.TributacaoResultado;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Monta o {@link PedidoDeEmissao} a partir de uma venda já gravada pelo PDV (§9.6, bloco B7).
 *
 * <p><b>Bean separado do orquestrador de propósito</b> (mesma razão de
 * {@link DocumentoFiscalRepositorio} — ver [[feedback_transactional_chamada_interna_rls]]): este
 * componente só <b>lê</b>, numa única transação curta, sem I/O de rede; quem chama a SEFAZ é o
 * {@link EmissaoNfceService}, e as duas coisas não podem compartilhar transação (F2).
 *
 * <p><b>Nunca recalcula preço, desconto ou estoque</b> — isso já está gravado por
 * {@code PdvVendaService.efetivarVenda}. Este montador só lê o que foi persistido e decide, para
 * cada item, qual regra fiscal (CFOP/CSOSN/CST) se aplica, antes de entregar tudo ao
 * {@link MotorTributario}.
 */
@Component
public class VendaFiscalAssembler {

    private final JdbcClient jdbc;
    private final MotorTributario motor;
    private final NinerProperties.RespTec respTec;
    private final CsrtService csrt;

    public VendaFiscalAssembler(JdbcClient jdbc, MotorTributario motor, NinerProperties propriedades,
                                CsrtService csrt) {
        this.jdbc = jdbc;
        this.motor = motor;
        this.respTec = propriedades.fiscal().respTec();
        this.csrt = csrt;
    }

    /**
     * @param incluirCpf 2026-08-19 — decisão do operador, perguntada na tela antes de emitir
     *         (nunca mais automático a partir do {@code id_cliente} da venda): {@code true} inclui
     *         o CPF/CNPJ do cliente da venda no grupo {@code dest}; {@code false} emite pra
     *         consumidor não identificado, mesmo que a venda tenha cliente vinculado. Ver
     *         {@link #buscarDestinatario}.
     * @return vazio quando o fiscal está <b>desligado</b> para a empresa (F12: "fiscal off muda
     *         nada" — nenhum documento é criado, nenhum erro é lançado); presente, pronto para
     *         {@link EmissaoNfceService#emitir}, quando está ligado — inclusive quando a venda vai
     *         terminar recusada por DF13 (é o {@code EmissaoNfceService} quem decide isso).
     * @throws ResponseStatusException 409 quando o fiscal está ligado mas falta um pré-requisito
     *         que a tela de Conformidade Fiscal deveria ter pego antes (F11: nunca deixar a
     *         rejeição chegar na SEFAZ por falta de dado básico), ou quando {@code incluirCpf=true}
     *         mas a venda não tem cliente/documento pra honrar a escolha do operador
     */
    @Transactional(readOnly = true)
    public Optional<PedidoDeEmissao> montar(long idTenant, long idEmpresa, long idVenda, Integer idUsuario,
                                            boolean incluirCpf) {
        ConfigEmpresa config = buscarConfig(idEmpresa);
        if (config == null || !config.emiteNfce()) {
            return Optional.empty();
        }

        VendaHeader venda = buscarVenda(idEmpresa, idVenda);
        Destinatario destinatario = incluirCpf ? buscarDestinatarioObrigatorio(venda.idCliente()) : null;
        String ufDestino = destinatario != null && destinatario.uf() != null ? destinatario.uf() : config.uf();

        List<ItemBruto> itensBrutos = buscarItens(idVenda);
        if (itensBrutos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Venda nº " + idVenda + " não tem itens — não há o que emitir.");
        }

        List<ItemOperacao> itensOperacao = new ArrayList<>();
        List<ItemNota> itensNota = new ArrayList<>();
        for (int i = 0; i < itensBrutos.size(); i++) {
            ItemBruto b = itensBrutos.get(i);
            int nItem = i + 1;
            RegraFiscal regra = buscarRegra(b, config.crt(), ufDestino);
            itensOperacao.add(new ItemOperacao(nItem, b.qtd(), b.precoVenda(), b.valorDesconto(),
                    b.valorAcrescimo(), regra, aliquotaTribFederal(b), b.alqEstadual(), b.alqMunicipal()));
            itensNota.add(new ItemNota(nItem, b.sku(), b.gtin(), b.descricao(), b.ncm(), b.cest(),
                    b.unidadeComercial(), b.qtd(), b.precoVenda(), b.unidadeTributavel(), null, null,
                    b.origemMercadoria()));
        }

        TributacaoResultado calculo = motor.calcular(
                new OperacaoFiscal(TipoOperacao.VENDA, ufDestino, TipoDestinatario.CONSUMIDOR_FINAL, itensOperacao),
                new ContextoFiscalEmpresa(config.crt(), config.uf()));

        List<Pagamento> pagamentos = buscarPagamentos(idVenda);

        Emitente emitente = new Emitente(config.cnpj(), config.razaoSocial(), config.nomeFantasia(),
                config.inscricaoEstadual(), config.crt(), config.logradouro(), config.numero(),
                config.complemento(), config.bairro(), config.codigoMunicipioIbge(), config.cidade(),
                config.uf(), config.cep(), config.telefone());

        // CSRT da UF do EMITENTE (não a do destinatário): quem cadastra o responsável técnico é a
        // SEFAZ que autoriza a nota. Ausente, o grupo sai sem idCSRT/hashCSRT — que é o que o PR
        // aceita na NFC-e. UF que exigir (cfg_uf_autorizador.exige_csrt) é barrada aqui, com o
        // motivo por extenso, em vez de assinar e transmitir uma nota que voltaria com cStat 975.
        int tpAmb = config.ambiente().codigo();
        Optional<CsrtService.Csrt> codigo = csrt.buscar(config.uf(), tpAmb);
        if (codigo.isEmpty() && csrt.exigeCsrt(config.uf(), 65, tpAmb)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, CsrtService.mensagemFaltando(config.uf(), 65));
        }
        ResponsavelTecnico responsavelTecnico = new ResponsavelTecnico(
                respTec.cnpj(), respTec.contato(), respTec.email(), respTec.telefone(),
                codigo.map(CsrtService.Csrt::idCsrt).orElse(null),
                codigo.map(CsrtService.Csrt::codigo).orElse(null));

        return Optional.of(new PedidoDeEmissao(idTenant, idEmpresa, (int) idVenda,
                venda.idCliente() == null ? null : venda.idCliente().intValue(), idUsuario,
                config.ambiente(), config.serieNfce(), venda.dataVenda(), "VENDA AO CONSUMIDOR",
                emitente, destinatario, itensNota, calculo.itens(), calculo.totais(), pagamentos,
                // O PDV não tem campo de troco explícito hoje — pagamentos sempre fecham o saldo
                // exato (split-tender). documento_fiscal.valor_troco é NOT NULL: zero é o valor
                // correto quando não houve troco, não um placeholder.
                BigDecimal.ZERO, null, responsavelTecnico, "Niner PDV 1.0"));
    }

    // ---------------------------------------------------------------- leituras

    private ConfigEmpresa buscarConfig(long idEmpresa) {
        return jdbc.sql("""
                        SELECT c.emite_nfce, c.ambiente::text AS ambiente, c.serie_nfce, c.crt,
                               e.cnpj, e.razao_social, e.nome_fantasia, e.inscricao_estadual,
                               e.endereco, e.numero, e.complemento, e.bairro,
                               e.codigo_municipio_ibge, e.cidade, e.estado, e.cep, e.telefone
                          FROM empresa e
                          LEFT JOIN fiscal_config_empresa c
                                 ON c.id_tenant = e.id_tenant AND c.id_empresa = e.id_empresa
                         WHERE e.id_tenant = plataforma.tenant_atual() AND e.id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> {
                    boolean emite = rs.getBoolean("emite_nfce");
                    if (rs.wasNull() || !emite) {
                        return new ConfigEmpresa(false, null, 0, 0, null, null, null, null,
                                null, null, null, null, 0, null, null, null, null);
                    }
                    exigir(rs.getString("cnpj"), "CNPJ da empresa");
                    exigir(rs.getString("estado"), "UF da empresa");
                    int codigoMunicipio = rs.getInt("codigo_municipio_ibge");
                    if (rs.wasNull()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Município (código IBGE) da empresa não está preenchido. "
                                        + "Complete o cadastro da empresa antes de emitir.");
                    }
                    return new ConfigEmpresa(true, AmbienteSefaz.valueOf(rs.getString("ambiente")),
                            rs.getInt("serie_nfce"), rs.getInt("crt"), rs.getString("cnpj"),
                            rs.getString("razao_social"), rs.getString("nome_fantasia"),
                            rs.getString("inscricao_estadual"), rs.getString("endereco"),
                            rs.getString("numero"), rs.getString("complemento"), rs.getString("bairro"),
                            codigoMunicipio, rs.getString("cidade"), rs.getString("estado"),
                            rs.getString("cep"), rs.getString("telefone"));
                })
                .optional()
                .orElse(null);
    }

    private VendaHeader buscarVenda(long idEmpresa, long idVenda) {
        return jdbc.sql("""
                        SELECT id_cliente, data_venda FROM venda
                         WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND id_venda = ?
                        """)
                .params(idEmpresa, idVenda)
                .query((rs, n) -> new VendaHeader(
                        (Integer) rs.getObject("id_cliente"),
                        rs.getObject("data_venda", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Venda nº " + idVenda + " não encontrada."));
    }

    /**
     * Só chamado quando o operador respondeu "sim" à pergunta de incluir CPF (2026-08-19) — por
     * isso é erro explícito, não {@code null} silencioso, quando não há como honrar a escolha:
     * sem cliente vinculado à venda, ou cliente vinculado mas sem CPF/CNPJ cadastrado. A tela já
     * deveria ter escondido essa opção nesse caso ({@code documentoCliente} vem {@code null} no
     * comprovante), mas o backend nunca confia só na tela (P4).
     */
    private Destinatario buscarDestinatarioObrigatorio(Integer idCliente) {
        if (idCliente == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não é possível incluir CPF na nota: esta venda não tem cliente identificado.");
        }
        Destinatario destinatario = buscarDestinatario(idCliente);
        if (destinatario == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não é possível incluir CPF na nota: o cliente desta venda não tem CPF/CNPJ cadastrado.");
        }
        return destinatario;
    }

    /**
     * {@code null} quando o cliente não tem documento — venda de balcão sem CPF, o caso mais
     * comum. NFC-e permite omitir o grupo {@code dest} inteiro nesse caso (confirmado no B0).
     */
    private Destinatario buscarDestinatario(int idCliente) {
        return jdbc.sql("""
                        SELECT cpf_cnpj, nome, indicador_ie, codigo_municipio_ibge, cidade, estado
                          FROM cliente
                         WHERE id_tenant = plataforma.tenant_atual() AND id_cliente = ?
                        """)
                .param(idCliente)
                .query((rs, n) -> {
                    String documento = rs.getString("cpf_cnpj");
                    if (documento == null || documento.isBlank()) {
                        return null;
                    }
                    return new Destinatario(documento, rs.getString("nome"), rs.getInt("indicador_ie"),
                            (Integer) rs.getObject("codigo_municipio_ibge"), rs.getString("cidade"),
                            rs.getString("estado"));
                })
                .optional()
                .orElse(null);
    }

    /** Em ordem de inserção (PK autoincremento) — a mesma ordem em que o PDV lançou os itens. */
    private List<ItemBruto> buscarItens(long idVenda) {
        return jdbc.sql("""
                        SELECT pmd.qtd_produto, pmd.preco_venda, pmd.valor_desconto, pmd.valor_acrescimo,
                               pb.sku, pb.ean, p.descricao, p.codigo_ncm, p.cest, p.origem_mercadoria,
                               p.unidade_comercial, p.unidade_tributavel, p.id_perfil_fiscal,
                               n.alq_federal_nacional, n.alq_federal_importado, n.alq_estadual, n.alq_municipal
                          FROM produto_movimento_detalhe pmd
                          JOIN produto_movimento_mestre pmm
                            ON pmm.id_tenant = pmd.id_tenant AND pmm.id_movimento = pmd.id_movimento
                          JOIN produto_barra pb
                            ON pb.id_tenant = pmd.id_tenant AND pb.id_variacao = pmd.id_variacao
                          JOIN produto p
                            ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          LEFT JOIN cfg_produto_ncm n
                            ON n.codigo_ncm = p.codigo_ncm
                         WHERE pmd.id_tenant = plataforma.tenant_atual()
                           AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                         ORDER BY pmd.id_movimento_detalhe
                        """)
                .param(idVenda)
                .query((rs, n) -> {
                    Integer idPerfil = (Integer) rs.getObject("id_perfil_fiscal");
                    if (idPerfil == null) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "O produto \"" + rs.getString("descricao") + "\" não tem perfil fiscal "
                                        + "configurado. Configure o perfil na tela de Produto antes de emitir.");
                    }
                    return new ItemBruto(rs.getBigDecimal("qtd_produto"), rs.getBigDecimal("preco_venda"),
                            rs.getBigDecimal("valor_desconto"), rs.getBigDecimal("valor_acrescimo"),
                            rs.getString("sku"), rs.getString("ean"), rs.getString("descricao"),
                            rs.getString("codigo_ncm"), rs.getString("cest"), rs.getInt("origem_mercadoria"),
                            rs.getString("unidade_comercial"), rs.getString("unidade_tributavel"), idPerfil,
                            rs.getBigDecimal("alq_federal_nacional"), rs.getBigDecimal("alq_federal_importado"),
                            rs.getBigDecimal("alq_estadual"), rs.getBigDecimal("alq_municipal"));
                })
                .list();
    }

    /**
     * Tributo aproximado (Lei 12.741/2012, §8.6 do estudo) — só a alíquota FEDERAL (estadual/
     * municipal vão direto de {@code ItemBruto} pro {@code ItemOperacao}, sem escolha nenhuma).
     * Escolhe Nacional × Importado pelo primeiro dígito da origem da mercadoria (CST/CSOSN):
     * {@link #ORIGENS_IMPORTADO} = 1/2/6/7; os demais (0/3/4/5/8) são Nacional. 2026-08-19: as 3
     * alíquotas passaram a chegar separadas ao motor (antes eram somadas aqui) pra o DANFE poder
     * exibir o detalhamento federal/estadual/municipal, não só o total.
     */
    private static BigDecimal aliquotaTribFederal(ItemBruto b) {
        return ORIGENS_IMPORTADO.contains(b.origemMercadoria()) ? b.alqFederalImportado() : b.alqFederalNacional();
    }

    private static final Set<Integer> ORIGENS_IMPORTADO = Set.of(1, 2, 6, 7);

    /**
     * A regra mais específica vence: UF exata bate antes do coringa {@code '*'}. Sem regra que
     * case, erro explícito (F11) — nunca uma alíquota chutada.
     */
    private RegraFiscal buscarRegra(ItemBruto item, int crt, String ufDestino) {
        return jdbc.sql("""
                        SELECT r.cfop, r.cst_icms, r.csosn, r.aliquota_icms, r.perc_reducao_bc,
                               r.aliquota_fcp, r.cst_pis, r.aliquota_pis, r.cst_cofins, r.aliquota_cofins,
                               r.cst_ibscbs, r.cclasstrib, r.codigo_beneficio,
                               COALESCE(t.perc_reducao_ibs, 0) AS perc_reducao_ibs,
                               COALESCE(t.perc_reducao_cbs, 0) AS perc_reducao_cbs
                          FROM cfg_perfil_fiscal_regra r
                          LEFT JOIN cfg_cclasstrib t ON t.codigo_cclasstrib = r.cclasstrib
                         WHERE r.id_tenant = plataforma.tenant_atual()
                           AND r.id_perfil_fiscal = ?
                           AND r.crt = ?
                           AND r.tipo_destinatario = 'CONSUMIDOR_FINAL'
                           AND r.tipo_operacao = 'VENDA_CONSUMIDOR'
                           AND r.uf_destino IN (?, '*')
                         ORDER BY (r.uf_destino = ?) DESC
                         LIMIT 1
                        """)
                .params(item.idPerfilFiscal(), crt, ufDestino, ufDestino)
                .query((rs, n) -> new RegraFiscal(rs.getString("cfop"), rs.getString("cst_icms"),
                        rs.getString("csosn"), rs.getBigDecimal("aliquota_icms"),
                        rs.getBigDecimal("perc_reducao_bc"), rs.getBigDecimal("aliquota_fcp"),
                        rs.getString("cst_pis"), rs.getBigDecimal("aliquota_pis"),
                        rs.getString("cst_cofins"), rs.getBigDecimal("aliquota_cofins"),
                        rs.getString("cst_ibscbs"), rs.getString("cclasstrib"),
                        rs.getBigDecimal("perc_reducao_ibs"), rs.getBigDecimal("perc_reducao_cbs"),
                        rs.getString("codigo_beneficio")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Nenhuma regra fiscal para o perfil do produto \"" + item.descricao()
                                + "\" no CRT " + crt + " / UF " + ufDestino + ". "
                                + "Cadastre a regra em Perfil Fiscal antes de emitir."));
    }

    /**
     * Uma linha de {@code pag/detPag} por forma de pagamento — <b>somada</b> por
     * {@code id_carteira}, não uma por parcela: um crediário de 3× no mesmo cartão é uma só forma
     * de pagamento no XML, e {@code contas_receber} grava 3 linhas (uma por parcela) para o mesmo
     * meio.
     */
    private List<Pagamento> buscarPagamentos(long idVenda) {
        return jdbc.sql("""
                        SELECT tc.codigo_tpag, tc.codigo_bandeira, tc.cnpj_credenciadora, tc.nome_carteira,
                               SUM(cr.valor_receber) AS total
                          FROM contas_receber cr
                          JOIN tipo_carteira tc
                            ON tc.id_tenant = cr.id_tenant AND tc.id_carteira = cr.id_carteira
                         WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ?
                         GROUP BY tc.id_carteira, tc.codigo_tpag, tc.codigo_bandeira,
                                  tc.cnpj_credenciadora, tc.nome_carteira
                        """)
                .param(idVenda)
                .query((rs, n) -> {
                    String tpag = rs.getString("codigo_tpag");
                    if (tpag == null || tpag.isBlank()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "A forma de pagamento \"" + rs.getString("nome_carteira") + "\" não tem "
                                        + "código tPag configurado. Configure em Tipo de Carteira antes de emitir.");
                    }
                    return new Pagamento(tpag, rs.getBigDecimal("total"),
                            rs.getString("codigo_bandeira"), rs.getString("cnpj_credenciadora"));
                })
                .list();
    }

    private static void exigir(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    campo + " não está preenchido. Complete o cadastro da empresa antes de emitir.");
        }
    }

    // ---------------------------------------------------------------- registros internos

    private record ConfigEmpresa(boolean emiteNfce, AmbienteSefaz ambiente, int serieNfce, int crt,
                                 String cnpj, String razaoSocial, String nomeFantasia,
                                 String inscricaoEstadual, String logradouro, String numero,
                                 String complemento, String bairro, int codigoMunicipioIbge,
                                 String cidade, String uf, String cep, String telefone) {
    }

    private record VendaHeader(Integer idCliente, OffsetDateTime dataVenda) {
    }

    private record ItemBruto(BigDecimal qtd, BigDecimal precoVenda, BigDecimal valorDesconto,
                             BigDecimal valorAcrescimo, String sku, String gtin, String descricao,
                             String ncm, String cest, int origemMercadoria, String unidadeComercial,
                             String unidadeTributavel, int idPerfilFiscal, BigDecimal alqFederalNacional,
                             BigDecimal alqFederalImportado, BigDecimal alqEstadual, BigDecimal alqMunicipal) {
    }
}
