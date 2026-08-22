package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.EmissaoNfceService.PedidoDeEmissao;
import com.vetor.niner.fiscal.documento.FiscalNumeracaoService.NumeroReservado;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.DevolucaoParaMontar;
import com.vetor.niner.fiscal.documento.MontagemDevolucaoDtos.ItemDevolucao;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ItemNota;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.ItemTributado;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Persistência do documento fiscal ao longo da máquina de estados (§9.1).
 *
 * <p><b>Bean separado do {@link EmissaoNfceService} de propósito, não por gosto de camada.</b>
 * O {@code @Transactional} do Spring funciona por proxy: um método anotado <b>chamado de dentro
 * da própria classe</b> não passa pelo proxy e roda <b>sem transação</b>. Aqui isso não seria um
 * detalhe de performance — o {@code TenantAwareTransactionManager} define
 * {@code app.id_tenant} no {@code doBegin} da transação, e é esse setting que as políticas RLS
 * leem (P8). Sem transação não há tenant, e sem tenant o RLS bloqueia tudo.
 *
 * <p>Toda query filtra {@code id_tenant} explicitamente no texto do SQL além do RLS
 * ([[feedback_isolamento_tenant_explicito]]).
 */
@Repository
public class DocumentoFiscalRepositorio {

    private static final int MODELO_NFCE = 65;

    /**
     * Quanto tempo uma nota pode ficar em {@code TRANSMITINDO} antes de o dreno de contingência
     * voltar a considerá-la (auditoria 2026-08-21, item 5).
     *
     * <p><b>Por que o estado entrou na fila.</b> {@code transmitir()} marca {@code TRANSMITINDO}
     * <b>antes</b> de enviar, e as duas consultas do dreno filtravam
     * {@code situacao IN ('CONTINGENCIA','ASSINADO')} — então, se a rede caísse entre o
     * {@code UPDATE} e a resposta, a nota <b>nunca mais</b> era encontrada, apesar de o
     * {@code catch} prometer "volta na próxima". Com o prazo legal de 24 h correndo e o cupom já
     * na mão do consumidor, ela só apareceria como pendente para sempre no painel.
     *
     * <p><b>Por que uma carência, e não pegar de imediato.</b> A transmissão pode estar
     * <i>acontecendo agora</i> — sem a espera, o dreno atropelaria a tentativa em curso. Dez
     * minutos é folgado para qualquer timeout de SEFAZ (o transporte usa dezenas de segundos) e
     * curto perto das 24 h do prazo.
     *
     * <p>⚠️ A retomada <b>não</b> retransmite cego: quem pega uma nota neste estado consulta a
     * chave antes (F5) — ver {@code FiscalContingenciaDrenoJob#sincronizarAntesDeRetransmitir}.
     */
    static final int MINUTOS_CARENCIA_TRANSMITINDO = 10;

    /**
     * UF de um documento que <b>já existe</b>: a que está congelada na chave de acesso, não a que a
     * empresa tem hoje. Ver {@link ChaveAcesso#ufDaChave} para o porquê — em resumo, documento
     * fiscal é registro imutável, e é por esta UF que o cancelamento escolhe a SEFAZ de destino.
     *
     * <p>Sem chave (documento que nunca recebeu número), cai na UF da empresa: é o melhor palpite
     * disponível, e nesse estado não há evento nem arquivamento para errar o destino.
     */
    private static String ufDoDocumento(java.sql.ResultSet rs) throws java.sql.SQLException {
        String daChave = ChaveAcesso.ufDaChave(rs.getString("chave_acesso"));
        return daChave != null ? daChave : rs.getString("uf");
    }

    private final JdbcClient jdbc;

    public DocumentoFiscalRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Estado terminal para a operação que o v1 reconhece mas não sabe emitir (§9.1). <b>Nenhum
     * número é alocado</b> — a venda existe, o motivo fica gravado, e o lojista vê na tela de
     * Documentos Fiscais em vez de descobrir na contabilidade.
     */
    @Transactional
    public long gravarNaoEmitido(PedidoDeEmissao pedido, String motivo) {
        return jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, tipo_operacao, situacao, ambiente,
                            id_venda, id_cliente, valor_total, motivo_nao_emissao, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, ?, 'VENDA_CONSUMIDOR', 'NAO_EMITIDO',
                                ?::ambiente_fiscal, ?, ?, ?, ?, ?)
                        RETURNING id_documento_fiscal
                        """)
                .params(pedido.idEmpresa(), MODELO_NFCE, pedido.ambiente().name(),
                        pedido.idVenda(), pedido.idCliente(), pedido.totais().valorNota(),
                        motivo, pedido.idUsuario())
                .query(Long.class).single();
    }

    @Transactional
    public long gravarAssinado(PedidoDeEmissao pedido, NumeroReservado numero,
                               String chave, String xmlAssinado, int tipoEmissao) {
        var t = pedido.totais();
        long idDocumento = jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, serie, numero, chave_acesso,
                            codigo_numerico, digito_verificador, tipo_operacao, situacao, ambiente,
                            tipo_emissao, id_venda, id_cliente, data_emissao,
                            valor_produtos, valor_desconto, valor_outros, valor_total, valor_troco,
                            valor_icms, valor_fcp, valor_pis, valor_cofins,
                            base_ibs_cbs, valor_ibs_uf, valor_ibs_mun, valor_cbs,
                            valor_trib_federal, valor_trib_estadual, valor_trib_municipal, valor_total_tributos,
                            xml_assinado, xml_hash, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?,
                                'VENDA_CONSUMIDOR', 'ASSINADO', ?::ambiente_fiscal, ?, ?, ?, ?,
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id_documento_fiscal
                        """)
                .params(pedido.idEmpresa(), MODELO_NFCE, numero.serie(), numero.numero(), chave,
                        "%08d".formatted(numero.codigoNumerico()),
                        Integer.parseInt(chave.substring(43)),
                        pedido.ambiente().name(), tipoEmissao,
                        pedido.idVenda(), pedido.idCliente(), pedido.emissao(),
                        t.valorProdutos(), t.valorDesconto(), t.valorAcrescimo(), t.valorNota(),
                        nz(pedido.troco()), t.valorIcms(), t.valorFcp(), t.valorPis(), t.valorCofins(),
                        t.baseIbsCbs(), t.valorIbsUf(), t.valorIbsMun(), t.valorCbs(),
                        t.valorTribFederal(), t.valorTribEstadual(), t.valorTribMunicipal(), t.valorTotalTributos(),
                        xmlAssinado, sha256(xmlAssinado), pedido.idUsuario())
                .query(Long.class).single();
        gravarItens(idDocumento, pedido);
        return idDocumento;
    }

    /**
     * Itens da nota, um por linha em {@code documento_fiscal_item} — a tributação que o motor
     * calculou, decomposta em colunas.
     *
     * <p>⚠️ <b>Gap corrigido em 2026-08-19</b>: a tabela existia desde a V035 e <b>nunca havia
     * recebido um INSERT</b> — 43 documentos fiscais no ambiente de dev, 27 autorizados, e zero
     * itens. O dado não estava perdido (o {@code xml_assinado} sempre guardou tudo), mas só em
     * XML, o que o torna inútil para qualquer consulta. Descoberto ao construir a NF-e de
     * devolução (B9), que precisa <b>espelhar</b> a tributação da nota original item a item: sem
     * esta tabela preenchida não há de onde espelhar, restando fazer parse do XML — frágil e
     * desnecessário, já que o schema previa exatamente isto.
     *
     * <p>⚠️ Notas <b>autorizadas antes desta data</b> seguem sem itens (não há como reconstruí-los
     * sem parsear o XML) — a devolução fiscal delas é recusada explicitamente pelo assembler,
     * com mensagem que explica o motivo, em vez de gerar uma nota incompleta.
     */
    /**
     * Grava a <b>NF-e de devolução</b> assinada (modelo 55, entrada) — bloco B9. Além do
     * cabeçalho e dos itens, grava a linha em {@code documento_fiscal_referencia} com a chave da
     * NFC-e devolvida: é a amarração que permite responder "esta devolução se refere a qual
     * venda?" sem parsear XML.
     *
     * <p>{@code tipo_operacao = DEVOLUCAO_VENDA}, {@code tipo_nf = 0} (entrada), {@code
     * finalidade = 4} (devolução) — os três já previstos no schema desde a V035.
     */
    @Transactional
    public long gravarDevolucaoAssinada(long idEmpresa, long idDevolucao, Integer idUsuario,
                                        DevolucaoParaMontar dev, NumeroReservado numero,
                                        String chave, String xmlAssinado, long idDocumentoOriginal) {
        var t = dev.totais();
        long idDocumento = jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, serie, numero, chave_acesso,
                            codigo_numerico, digito_verificador, tipo_operacao, situacao, ambiente,
                            tipo_emissao, tipo_nf, finalidade, indicador_final, indicador_presenca,
                            id_devolucao, data_emissao,
                            valor_produtos, valor_desconto, valor_total,
                            valor_icms, valor_icms_st, valor_pis, valor_cofins,
                            base_ibs_cbs, valor_ibs_uf, valor_ibs_mun, valor_cbs, valor_total_tributos,
                            xml_assinado, xml_hash, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, 55, ?, ?, ?, ?, ?,
                                'DEVOLUCAO_VENDA', 'ASSINADO', ?::ambiente_fiscal, 1, 0, 4, 0, 0,
                                ?, ?,
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id_documento_fiscal
                        """)
                .params(idEmpresa, numero.serie(), numero.numero(), chave,
                        "%08d".formatted(numero.codigoNumerico()), Integer.parseInt(chave.substring(43)),
                        dev.ambiente().name(), idDevolucao, dev.emissao(),
                        t.valorProdutos(), t.valorDesconto(), t.valorTotal(),
                        t.valorIcms(), t.valorIcmsSt(), t.valorPis(), t.valorCofins(),
                        t.baseIbsCbs(), t.valorIbsUf(), t.valorIbsMun(), t.valorCbs(), t.valorTotalTributos(),
                        xmlAssinado, sha256(xmlAssinado), idUsuario)
                .query(Long.class).single();

        jdbc.sql("""
                        INSERT INTO documento_fiscal_referencia
                            (id_tenant, id_documento_fiscal, chave_referenciada, id_documento_referenciado)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?)
                        """)
                .params(idDocumento, dev.chaveNotaOriginal(), idDocumentoOriginal)
                .update();

        gravarItensDevolucao(idDocumento, dev);
        return idDocumento;
    }

    /**
     * Grava a NF-e de <b>devolução de compra</b> assinada (modelo 55, <b>saída</b>) — irmã de
     * {@link #gravarDevolucaoAssinada}, com três diferenças que não são cosméticas:
     *
     * <ul>
     *   <li>{@code tipo_operacao = 'DEVOLUCAO_FORNECEDOR'} e {@code tipo_nf = 1} (saída): a
     *       mercadoria está indo embora, não voltando;</li>
     *   <li>o vínculo é {@code id_movimento} (o movimento {@code DEVOLUCAO_COMPRA}), não
     *       {@code id_devolucao} — esta operação não tem cabeçalho próprio, ela <b>é</b> o
     *       movimento de estoque;</li>
     *   <li>a referência vai <b>só pela chave</b>, com {@code id_documento_referenciado} nulo: a
     *       nota referenciada é do <b>fornecedor</b> e não existe em {@code documento_fiscal}. A
     *       coluna sempre previu isso ("preenchida quando a nota é do próprio Niner").</li>
     * </ul>
     */
    /*
     * ⚠️ `@Transactional` NÃO é decoração (achado de auditoria, 2026-08-21 — faltava aqui e a irmã
     * `gravarDevolucaoAssinada` tem). Sem ela o método roda em autocommit, e aí o
     * `TenantAwareTransactionManager` nunca executou o `SET LOCAL app.id_tenant`: os
     * `plataforma.tenant_atual()` do VALUES resolvem para NULL, a coluna é NOT NULL, e o INSERT
     * estoura. O usuário via **409 "Registro em uso por outro cadastro"** — mensagem sobre exclusão
     * de cadastro para uma emissão de nota.
     *
     * <p>E o estrago não era só a mensagem: a mercadoria já tinha baixado (o serviço commitou
     * antes), o número fiscal já tinha sido consumido em transação própria, e o documento não
     * chegava a existir nem como pendente — cada nova tentativa queimava outro número.
     *
     * <p>Não existe transação vindo de fora: o controller não é transacional, o serviço já
     * commitou e o assembler fecha a própria transação de leitura antes de retornar.
     */
    @Transactional
    public long gravarDevolucaoCompraAssinada(long idEmpresa, long idMovimento, Integer idUsuario,
                                              DevolucaoParaMontar dev, NumeroReservado numero,
                                              String chave, String xmlAssinado) {
        var t = dev.totais();
        long idDocumento = jdbc.sql("""
                        INSERT INTO documento_fiscal (
                            id_tenant, id_empresa, modelo, serie, numero, chave_acesso,
                            codigo_numerico, digito_verificador, tipo_operacao, situacao, ambiente,
                            tipo_emissao, tipo_nf, finalidade, indicador_final, indicador_presenca,
                            id_movimento, data_emissao,
                            valor_produtos, valor_desconto, valor_total,
                            valor_icms, valor_icms_st, valor_pis, valor_cofins,
                            base_ibs_cbs, valor_ibs_uf, valor_ibs_mun, valor_cbs, valor_total_tributos,
                            xml_assinado, xml_hash, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, 55, ?, ?, ?, ?, ?,
                                'DEVOLUCAO_FORNECEDOR', 'ASSINADO', ?::ambiente_fiscal, 1, 1, 4, 0, 0,
                                ?, ?,
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id_documento_fiscal
                        """)
                .params(idEmpresa, numero.serie(), numero.numero(), chave,
                        "%08d".formatted(numero.codigoNumerico()), Integer.parseInt(chave.substring(43)),
                        dev.ambiente().name(), idMovimento, dev.emissao(),
                        t.valorProdutos(), t.valorDesconto(), t.valorTotal(),
                        t.valorIcms(), t.valorIcmsSt(), t.valorPis(), t.valorCofins(),
                        t.baseIbsCbs(), t.valorIbsUf(), t.valorIbsMun(), t.valorCbs(), t.valorTotalTributos(),
                        xmlAssinado, sha256(xmlAssinado), idUsuario)
                .query(Long.class).single();

        jdbc.sql("""
                        INSERT INTO documento_fiscal_referencia
                            (id_tenant, id_documento_fiscal, chave_referenciada, id_documento_referenciado)
                        VALUES (plataforma.tenant_atual(), ?, ?, NULL)
                        """)
                .params(idDocumento, dev.chaveNotaOriginal())
                .update();

        gravarItensDevolucao(idDocumento, dev);
        return idDocumento;
    }

    /** Itens da devolução — os mesmos valores que foram para o XML, já espelhados da nota
     *  original pelo {@code DevolucaoFiscalAssembler} (nada é recalculado aqui). */
    private void gravarItensDevolucao(long idDocumento, DevolucaoParaMontar dev) {
        for (ItemDevolucao i : dev.itens()) {
            jdbc.sql("""
                            INSERT INTO documento_fiscal_item (
                                id_tenant, id_documento_fiscal, numero_item, id_variacao, codigo_produto,
                                codigo_ean, descricao, codigo_ncm, cest, cfop, unidade_comercial,
                                quantidade, valor_unitario, valor_produto,
                                unidade_tributavel, quantidade_trib, valor_unitario_trib, origem_mercadoria,
                                cst_icms, csosn, base_calculo_icms, perc_reducao_bc, aliquota_icms, valor_icms,
                                base_calculo_st, valor_icms_st, base_st_retido, icms_st_retido,
                                perc_credito_sn, valor_credito_sn,
                                cst_pis, base_calculo_pis, aliquota_pis, valor_pis,
                                cst_cofins, base_calculo_cofins, aliquota_cofins, valor_cofins,
                                cst_ibscbs, cclasstrib, base_ibscbs, aliquota_ibs_uf, valor_ibs_uf,
                                aliquota_ibs_mun, valor_ibs_mun, aliquota_cbs, valor_cbs, valor_total_tributos)
                            VALUES (plataforma.tenant_atual(), ?, ?,
                                    (SELECT pb.id_variacao FROM produto_barra pb
                                      WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.sku = ? LIMIT 1),
                                    ?, COALESCE(?, 'SEM GTIN'), ?, ?, ?, ?, ?,
                                    ?, ?, ?,
                                    ?, ?, ?, ?,
                                    ?, ?, ?, ?, ?, ?,
                                    ?, ?, ?, ?,
                                    ?, ?,
                                    ?, ?, ?, ?,
                                    ?, ?, ?, ?,
                                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(idDocumento, i.nItem(), i.codigoProduto(), i.codigoProduto(),
                            i.gtin(), i.descricao(), i.ncm(), i.cest(), i.cfop(), i.unidadeComercial(),
                            i.quantidade(), i.valorUnitario(), i.valorProduto(),
                            i.unidadeTributavel(), i.quantidadeTributavel(), i.valorUnitarioTributavel(),
                            i.origemMercadoria(),
                            i.cstIcms(), i.csosn(), nz(i.baseCalculoIcms()), nz(i.percentualReducaoBc()),
                            nz(i.aliquotaIcms()), nz(i.valorIcms()),
                            nz(i.baseCalculoSt()), nz(i.valorIcmsSt()), nz(i.baseStRetido()), nz(i.icmsStRetido()),
                            nz(i.percentualCreditoSn()), nz(i.valorCreditoSn()),
                            i.cstPis(), nz(i.baseCalculoPis()), nz(i.aliquotaPis()), nz(i.valorPis()),
                            i.cstCofins(), nz(i.baseCalculoCofins()), nz(i.aliquotaCofins()), nz(i.valorCofins()),
                            i.cstIbsCbs(), i.cclasstrib(), nz(i.baseIbsCbs()), nz(i.aliquotaIbsUf()),
                            nz(i.valorIbsUf()), nz(i.aliquotaIbsMun()), nz(i.valorIbsMun()),
                            nz(i.aliquotaCbs()), nz(i.valorCbs()), nz(i.valorTotalTributos()))
                    .update();
        }
    }

    private void gravarItens(long idDocumento, PedidoDeEmissao pedido) {
        Map<Integer, ItemTributado> tributadoPorItem = pedido.itensTributados().stream()
                .collect(java.util.stream.Collectors.toMap(ItemTributado::nItem, t -> t));

        for (ItemNota item : pedido.itens()) {
            ItemTributado t = tributadoPorItem.get(item.nItem());
            if (t == null) {
                continue;   // não deve acontecer: montador e motor recebem a mesma lista
            }
            var icms = t.icms();
            var pis = t.pis();
            var cofins = t.cofins();
            var ibs = t.ibsCbs();
            boolean temIbs = ibs != null && ibs.aplicavel();
            jdbc.sql("""
                            INSERT INTO documento_fiscal_item (
                                id_tenant, id_documento_fiscal, numero_item, id_variacao, codigo_produto,
                                codigo_ean, descricao, codigo_ncm, cest, cfop, unidade_comercial,
                                quantidade, valor_unitario, valor_produto, valor_desconto,
                                unidade_tributavel, quantidade_trib, valor_unitario_trib, origem_mercadoria,
                                cst_icms, csosn, base_calculo_icms, perc_reducao_bc, aliquota_icms, valor_icms,
                                aliquota_fcp, valor_fcp,
                                cst_pis, base_calculo_pis, aliquota_pis, valor_pis,
                                cst_cofins, base_calculo_cofins, aliquota_cofins, valor_cofins,
                                cst_ibscbs, cclasstrib, base_ibscbs, aliquota_ibs_uf, valor_ibs_uf,
                                aliquota_ibs_mun, valor_ibs_mun, aliquota_cbs, valor_cbs,
                                valor_total_tributos)
                            VALUES (plataforma.tenant_atual(), ?, ?,
                                    -- `ItemNota` é o contrato do montador de XML e não carrega
                                    -- chave interna (só o SKU, que é o cProd da nota) — resolver
                                    -- aqui evita poluí-lo com `idVariacao` só por causa desta FK.
                                    (SELECT pb.id_variacao FROM produto_barra pb
                                      WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.sku = ? LIMIT 1),
                                    ?,
                                    COALESCE(?, 'SEM GTIN'), ?, ?, ?, ?, ?,
                                    ?, ?, ?, ?,
                                    ?, ?, ?, ?,
                                    ?, ?, ?, ?, ?, ?,
                                    ?, ?,
                                    ?, ?, ?, ?,
                                    ?, ?, ?, ?,
                                    ?, ?, ?, ?, ?,
                                    ?, ?, ?, ?,
                                    ?)
                            """)
                    .params(idDocumento, item.nItem(), item.codigoProduto(), item.codigoProduto(),
                            item.gtin(), item.descricao(), item.ncm(), item.cest(), t.cfop(),
                            item.unidadeComercial(),
                            item.quantidade(), item.valorUnitario(), t.valorProduto(), nz(t.valorDesconto()),
                            item.unidadeTributavel() != null ? item.unidadeTributavel() : item.unidadeComercial(),
                            item.quantidadeTributavel() != null ? item.quantidadeTributavel() : item.quantidade(),
                            item.valorUnitarioTributavel() != null ? item.valorUnitarioTributavel() : item.valorUnitario(),
                            item.origemMercadoria(),
                            icms.cst(), icms.csosn(), nz(icms.baseCalculo()), nz(icms.percReducaoBc()),
                            nz(icms.aliquota()), nz(icms.valor()), nz(icms.aliquotaFcp()), nz(icms.valorFcp()),
                            pis.cst(), nz(pis.baseCalculo()), nz(pis.aliquota()), nz(pis.valor()),
                            cofins.cst(), nz(cofins.baseCalculo()), nz(cofins.aliquota()), nz(cofins.valor()),
                            temIbs ? ibs.cst() : null, temIbs ? ibs.cClassTrib() : null,
                            temIbs ? nz(ibs.baseCalculo()) : BigDecimal.ZERO,
                            temIbs ? nz(ibs.aliquotaIbsUf()) : BigDecimal.ZERO,
                            temIbs ? nz(ibs.valorIbsUf()) : BigDecimal.ZERO,
                            temIbs ? nz(ibs.aliquotaIbsMun()) : BigDecimal.ZERO,
                            temIbs ? nz(ibs.valorIbsMun()) : BigDecimal.ZERO,
                            temIbs ? nz(ibs.aliquotaCbs()) : BigDecimal.ZERO,
                            temIbs ? nz(ibs.valorCbs()) : BigDecimal.ZERO,
                            nz(t.valorTotalTributos()))
                    .update();
        }
    }

    /**
     * Nota emitida em contingência, à espera da SEFAZ voltar (§9.7). Estado distinto de
     * {@code TRANSMITINDO}: aqui nada foi enviado ainda, e o cupom <b>já está</b> com o
     * consumidor — é o que o job de drenagem procura, e o que faz o prazo de 24 h correr.
     */
    @Transactional
    public void marcarEmContingencia(long idDocumento) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET situacao = 'CONTINGENCIA', atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .param(idDocumento).update();
    }

    @Transactional
    public void marcarTransmitindo(long idDocumento) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET situacao = 'TRANSMITINDO', tentativas = tentativas + 1,
                               ultima_tentativa_em = now(), atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .param(idDocumento).update();
    }

    @Transactional
    public void marcarAutorizado(long idDocumento, RespostaSefaz resposta) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET situacao = 'AUTORIZADO', protocolo = ?, data_autorizacao = now(),
                               status_sefaz = ?, motivo_sefaz = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(resposta.protocolo(), resposta.corpoXml(), resposta.xMotivo(), idDocumento)
                .update();
    }

    /**
     * Rejeição e denegação. O número <b>continua alocado</b> (§9.1): corrigir e retransmitir usa o
     * MESMO número; abandonar exige inutilização formal. Denegado é fim de linha — não se
     * cancela, não se reaproveita.
     */
    @Transactional
    public void marcarRecusado(long idDocumento, RespostaSefaz resposta, boolean denegado) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET situacao = ?::situacao_documento_fiscal, status_sefaz = ?,
                               motivo_sefaz = ?, ultimo_erro = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(denegado ? "DENEGADO" : "REJEITADO", resposta.corpoXml(),
                        resposta.xMotivo(), resposta.cStat() + " — " + resposta.xMotivo(), idDocumento)
                .update();
    }

    /** Lote aceito, resultado ainda não disponível: segue {@code TRANSMITINDO}, sem erro. */
    @Transactional
    public void registrarProcessamento(long idDocumento, RespostaSefaz resposta) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET status_sefaz = ?, motivo_sefaz = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(resposta.corpoXml(), resposta.xMotivo(), idDocumento).update();
    }

    /**
     * Falha de comunicação — o documento <b>permanece</b> {@code TRANSMITINDO}, nunca vira
     * {@code REJEITADO}. A nota pode ter sido autorizada e só a resposta ter se perdido; quem
     * retransmitir sem consultar a chave antes emite a mesma venda duas vezes (F5).
     */
    @Transactional
    public void registrarFalhaDeComunicacao(long idDocumento, String erro) {
        jdbc.sql("""
                        UPDATE documento_fiscal
                           SET ultimo_erro = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(erro, idDocumento).update();
    }

    /** F7: todo uso do certificado deixa rastro — é o que permite investigar um vazamento. */
    @Transactional
    public void registrarUsoDoCertificado(long idCertificado, long idDocumento, String finalidade) {
        jdbc.sql("""
                        INSERT INTO fiscal_certificado_uso
                            (id_tenant, id_certificado, id_documento_fiscal, finalidade)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?)
                        """)
                .params(idCertificado, idDocumento, finalidade).update();
    }

    // ---------------------------------------------------------------- fila de contingência (§9.7)

    /**
     * Todos os ids de tenant existentes ({@code plataforma.tenant}, GLOBAL, sem RLS — P9). Ponto
     * de entrada dos jobs em caminho não-requisição (sem JWT) para descobrir quem varrer: uma
     * consulta de domínio (RLS FORCE) chamada sem {@link com.vetor.niner.comum.tenant.TenantContext
     * #comTenant} não "vê todos os tenants" como se poderia supor — {@code niner_app} nunca tem
     * {@code BYPASSRLS} (P8), então sem {@code app.id_tenant} definido a política
     * {@code USING (id_tenant = plataforma.tenant_atual())} não bate para NENHUMA linha, de
     * NENHUM tenant, e a consulta volta silenciosamente vazia — não um erro, um job que nunca
     * encontra trabalho. Confirmado ao vivo (2026-08-19): mesmo como {@code niner_owner}, dono das
     * tabelas, {@code SELECT * FROM empresa} sem {@code SET app.id_tenant} devolveu 0 linhas com 5
     * empresas reais no banco. O padrão correto é este: listar os tenants por aqui (sem RLS) e
     * entrar em {@code comTenant} por tenant antes de qualquer consulta de domínio.
     */
    @Transactional(readOnly = true)
    public List<Long> listarTenantIds() {
        return jdbc.sql("SELECT id_tenant FROM plataforma.tenant").query(Long.class).list();
    }

    /**
     * Empresas do tenant corrente com nota emitida em contingência ainda não autorizada. Chamar
     * dentro de {@link com.vetor.niner.comum.tenant.TenantContext#comTenant} — sem tenant no
     * contexto o RLS devolve lista vazia (P8), nunca todos os tenants (ver {@link #listarTenantIds}).
     *
     * <p>O {@code JOIN} com {@code fiscal_certificado} é filtro de existência, não fonte da
     * impressão digital — essa vem de {@link com.vetor.niner.fiscal.certificado.FiscalCertificadoService
     * #carregarAtivoParaAssinatura}, a única fonte confiável para a chave do cache mTLS (o cache
     * é por certificado; um valor de outra origem poderia divergir do certificado que de fato
     * assina, e é exatamente essa divergência que o cache existe para impedir).
     */
    @Transactional(readOnly = true)
    public List<EmpresaEmContingencia> empresasComFilaPendente() {
        return jdbc.sql("""
                        SELECT DISTINCT c.id_tenant, c.id_empresa, e.estado AS uf,
                               CASE c.ambiente WHEN 'PRODUCAO' THEN 1 ELSE 2 END AS ambiente_codigo,
                               u.codigo_uf_ibge
                          FROM documento_fiscal d
                          JOIN fiscal_config_empresa c
                            ON c.id_tenant = d.id_tenant AND c.id_empresa = d.id_empresa
                          JOIN empresa e
                            ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                          JOIN cfg_uf_autorizador u
                            ON u.uf = e.estado AND u.modelo = 65
                           AND u.ambiente = CASE c.ambiente WHEN 'PRODUCAO' THEN 1 ELSE 2 END
                          JOIN fiscal_certificado cert
                            ON cert.id_tenant = d.id_tenant AND cert.id_empresa = d.id_empresa
                           AND cert.ativo = true
                         WHERE d.id_tenant = plataforma.tenant_atual()
                           AND d.tipo_emissao = 9
                           AND (d.situacao IN ('CONTINGENCIA', 'ASSINADO')
                                OR (d.situacao = 'TRANSMITINDO'
                                    AND (d.ultima_tentativa_em IS NULL
                                         OR d.ultima_tentativa_em < now() - INTERVAL '%d minutes')))
                        """.formatted(MINUTOS_CARENCIA_TRANSMITINDO))
                .query((rs, n) -> new EmpresaEmContingencia(
                        rs.getLong("id_tenant"), rs.getLong("id_empresa"), rs.getString("uf"),
                        rs.getInt("ambiente_codigo"), rs.getInt("codigo_uf_ibge")))
                .list();
    }

    /**
     * Fila de uma empresa, <b>em ordem de emissão</b>. A ordem não é estética: transmitir a nota
     * 12 antes da 10 cria buraco aparente na numeração e complica a conferência.
     *
     * <p>Dentro do {@code TenantContext}, o filtro explícito de {@code id_tenant} volta a ser
     * obrigatório (P8) — o job não é exceção.
     */
    @Transactional(readOnly = true)
    public List<NotaPendente> pendentesDeContingencia(long idEmpresa, int limite) {
        return jdbc.sql("""
                        SELECT id_documento_fiscal, chave_acesso, xml_assinado, situacao::text AS situacao
                          FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND id_empresa = ?
                           AND tipo_emissao = 9
                           AND (situacao IN ('CONTINGENCIA', 'ASSINADO')
                                OR (situacao = 'TRANSMITINDO'
                                    AND (ultima_tentativa_em IS NULL
                                         OR ultima_tentativa_em < now() - INTERVAL '%d minutes')))
                           AND xml_assinado IS NOT NULL
                         ORDER BY data_emissao, numero
                         LIMIT ?
                        """.formatted(MINUTOS_CARENCIA_TRANSMITINDO))
                .params(idEmpresa, limite)
                .query((rs, n) -> new NotaPendente(
                        rs.getLong("id_documento_fiscal"), rs.getString("chave_acesso"),
                        rs.getString("xml_assinado"), rs.getString("situacao")))
                .list();
    }

    public record EmpresaEmContingencia(long idTenant, long idEmpresa, String uf, int ambienteCodigo,
                                        int codigoUfIbge) {
    }

    /**
     * @param situacao estado atual — {@code CONTINGENCIA}/{@code ASSINADO} (nunca transmitida) ou
     *                 {@code TRANSMITINDO} (tentativa anterior sem desfecho). A distinção não é
     *                 informativa: em {@code TRANSMITINDO} o dreno <b>tem</b> de consultar a chave
     *                 antes de reenviar (F5), porque a nota pode ter sido autorizada e só a
     *                 resposta ter se perdido.
     */
    public record NotaPendente(long id, String chaveAcesso, String xmlAssinado, String situacao) {

        boolean jaFoiTransmitida() {
            return "TRANSMITINDO".equals(situacao);
        }
    }

    // ---------------------------------------------------------------- cancelamento (§10.1, B8)

    /**
     * A nota AUTORIZADA mais recente de uma venda, ou vazia quando não há nenhuma — o chamador
     * (F12/DF13) trata "vazio" como "sem nota fiscal, cancelamento não passa pela SEFAZ". Pega a
     * mais recente por {@code criado_em}: uma venda com nota rejeitada e reemitida com sucesso
     * tem duas linhas em {@code documento_fiscal}, e é a AUTORIZADA que importa.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<DocumentoParaCancelar> buscarAutorizadoParaCancelamento(long idVenda) {
        return jdbc.sql("""
                        SELECT d.id_documento_fiscal, d.chave_acesso, d.protocolo, d.data_autorizacao,
                               d.ambiente::text AS ambiente, e.estado AS uf, e.cnpj
                          FROM documento_fiscal d
                          JOIN empresa e ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_venda = ?
                           AND d.situacao = 'AUTORIZADO'
                         ORDER BY d.criado_em DESC
                         LIMIT 1
                        """)
                .param(idVenda)
                .query((rs, n) -> new DocumentoParaCancelar(
                        rs.getLong("id_documento_fiscal"), rs.getString("chave_acesso"),
                        rs.getString("protocolo"),
                        rs.getObject("data_autorizacao", java.time.OffsetDateTime.class),
                        MontagemNfceDtos.AmbienteSefaz.valueOf(rs.getString("ambiente")),
                        ufDoDocumento(rs), rs.getString("cnpj")))
                .optional();
    }

    /**
     * Mesma busca, mas pelo <b>movimento de estoque</b> — a NF-e 55 de devolução ao fornecedor não
     * nasce de uma venda, nasce de um {@code produto_movimento_mestre}, e por isso se pendura em
     * {@code id_movimento} e não em {@code id_venda}.
     *
     * <p>{@code modelo = 55} no filtro não é redundância defensiva: um mesmo movimento pode, no
     * futuro, ter mais de um documento pendurado (uma nota e um evento de outro modelo), e cancelar
     * o documento errado é o tipo de engano que só aparece na SEFAZ.
     */
    /*
     * ⚠️ `@Transactional(readOnly = true)` — a irmã do caminho da venda tem, esta não tinha, e a
     * falta era PIOR aqui porque falha em SILÊNCIO (achado de auditoria, 2026-08-21).
     *
     * <p>Em autocommit, `plataforma.tenant_atual()` resolve para NULL, o filtro casa zero linhas e
     * o método devolve `Optional.empty()`. Só que vazio, no contrato deste método, significa "não
     * há NF-e, pode reverter à vontade" — então o cancelamento da devolução ao fornecedor **não
     * enviava o evento 110111**, nem checava o prazo da UF, e mesmo assim devolvia o estoque e
     * respondia 200.
     *
     * <p>Resultado: mercadoria de volta na loja e uma NF-e 55 autorizada e não cancelada declarando
     * que ela saiu — exatamente o cenário que a ordem "nota primeiro, estoque depois" existe para
     * impedir. Sem erro nenhum na tela.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<DocumentoParaCancelar> buscarAutorizadoDeMovimentoParaCancelamento(long idMovimento) {
        return jdbc.sql("""
                        SELECT d.id_documento_fiscal, d.chave_acesso, d.protocolo, d.data_autorizacao,
                               d.ambiente::text AS ambiente, e.estado AS uf, e.cnpj
                          FROM documento_fiscal d
                          JOIN empresa e ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_movimento = ?
                           AND d.modelo = 55
                           AND d.situacao = 'AUTORIZADO'
                         ORDER BY d.criado_em DESC
                         LIMIT 1
                        """)
                .param(idMovimento)
                .query((rs, n) -> new DocumentoParaCancelar(
                        rs.getLong("id_documento_fiscal"), rs.getString("chave_acesso"),
                        rs.getString("protocolo"),
                        rs.getObject("data_autorizacao", java.time.OffsetDateTime.class),
                        MontagemNfceDtos.AmbienteSefaz.valueOf(rs.getString("ambiente")),
                        ufDoDocumento(rs), rs.getString("cnpj")))
                .optional();
    }

    /**
     * Grava a <b>tentativa</b> de cancelamento — sempre, autorizada ou recusada (P3, F11: nunca
     * esconder uma recusa). Só isso; quem marca {@code documento_fiscal.situacao = CANCELADO} é
     * {@link #marcarCancelado}, chamado à parte.
     *
     * <p>⚠️ {@code REQUIRES_NEW}, não o padrão — achado testando o caminho de recusa (B8):
     * {@code CancelamentoVendaService.cancelar()} chama {@link CancelamentoNfceService
     * #cancelarSeAplicavel} de <b>dentro</b> da própria transação (desvio deliberado do F2,
     * documentado lá). Com propagação padrão, este {@code INSERT} entraria nessa MESMA
     * transação — e como a recusa termina lançando {@code ResponseStatusException}, o Spring
     * reverte a transação inteira, apagando junto o registro de auditoria que existe
     * <b>exatamente</b> para sobreviver à recusa. {@code REQUIRES_NEW} garante que o evento
     * commita sozinho, aconteça o que acontecer depois no chamador.
     *
     * <p>⚠️ {@code tentativa} calculado por subquery (não fixo em 1) — achado cancelando uma
     * venda de verdade (2026-08-19): cancelamento (110111) sempre usa {@code sequencia = 1} (é a
     * SEFAZ que exige isso, não um bug), então uma RETENTATIVA da mesma sequência — 1ª tentativa
     * recusada, ou um bug local (já corrigido) lendo errado uma resposta que a SEFAZ na verdade
     * autorizou — precisa de uma tentativa nova (2, 3…) pra não colidir com a UNIQUE e travar o
     * cancelamento pra sempre com "duplicate key", mesmo a SEFAZ aceitando o reenvio.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public long registrarTentativaCancelamento(long idDocumentoFiscal, String justificativa,
                                               boolean autorizado, String protocoloEvento,
                                               String statusSefaz, String motivoSefaz, String xmlEvento,
                                               Integer idUsuario) {
        return jdbc.sql("""
                        INSERT INTO documento_fiscal_evento
                            (id_tenant, id_documento_fiscal, tipo_evento, sequencia, tentativa,
                             justificativa, autorizado, protocolo, status_sefaz, motivo_sefaz,
                             xml_evento, id_usuario)
                        SELECT plataforma.tenant_atual(), ?, '110111', 1, COALESCE(MAX(tentativa), 0) + 1,
                               ?, ?, ?, ?, ?, ?, ?
                          FROM documento_fiscal_evento
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                               AND tipo_evento = '110111' AND sequencia = 1
                        RETURNING id_evento
                        """)
                .params(idDocumentoFiscal, justificativa, autorizado, protocoloEvento, statusSefaz,
                        motivoSefaz, xmlEvento, idUsuario, idDocumentoFiscal)
                .query(Long.class).single();
    }

    /**
     * Marca o documento como {@code CANCELADO} — chamado só quando a SEFAZ autorizou.
     * <b>Propagação padrão, de propósito</b> (diferente de {@link #registrarTentativaCancelamento}):
     * este {@code UPDATE} precisa entrar na <b>mesma</b> transação que reverte a venda em
     * {@code CancelamentoVendaService.cancelar()} — se a reversão falhar por qualquer motivo
     * depois deste ponto, o rollback tem que desfazer o {@code CANCELADO} junto, senão o fiscal
     * fica cancelado com a venda intacta ("estoque/caixa e fiscal nunca divergem", §10.1).
     */
    @Transactional
    public void marcarCancelado(long idDocumentoFiscal) {
        jdbc.sql("""
                        UPDATE documento_fiscal SET situacao = 'CANCELADO', atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .param(idDocumentoFiscal).update();
    }

    public record DocumentoParaCancelar(long idDocumentoFiscal, String chaveAcesso, String protocolo,
                                        java.time.OffsetDateTime dataAutorizacao,
                                        MontagemNfceDtos.AmbienteSefaz ambiente, String uf, String cnpjEmitente) {
    }

    // ---------------------------------------------------------------- consulta pontual (§12, B8)

    /**
     * Contexto mínimo pra montar/enviar uma consulta {@code NFeConsultaProtocolo4} a partir de um
     * {@code id_documento_fiscal} — usado por {@link DocumentoFiscalConsultaService#consultarNaSefaz}.
     *
     * <p>⚠️ Vive aqui, não como método privado do serviço, pela mesma razão de sempre
     * ([[feedback_transactional_chamada_interna_rls]]): {@code consultarNaSefaz} não pode ser
     * {@code @Transactional} (faria a chamada de rede acontecer dentro de uma transação de banco,
     * F2) — e sem transação ativa, uma leitura chamada por {@code this.} nunca teria
     * {@code app.id_tenant} definido. Achado testando ao vivo: a consulta devolvia 404 "documento
     * não encontrado" para um documento que existia, porque o RLS via tenant nenhum.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ContextoConsulta> buscarContextoParaConsulta(long idDocumentoFiscal) {
        return jdbc.sql("""
                        SELECT d.id_empresa, d.modelo, d.chave_acesso, d.ambiente::text AS ambiente, e.estado AS uf
                          FROM documento_fiscal d
                          JOIN empresa e ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_documento_fiscal = ?
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new ContextoConsulta(
                        rs.getLong("id_empresa"), rs.getInt("modelo"), rs.getString("chave_acesso"),
                        MontagemNfceDtos.AmbienteSefaz.valueOf(rs.getString("ambiente")), ufDoDocumento(rs)))
                .optional();
    }

    public record ContextoConsulta(long idEmpresa, int modelo, String chaveAcesso,
                                   MontagemNfceDtos.AmbienteSefaz ambiente, String uf) {
    }

    // ---------------------------------------------------------------- reprocessamento (§12, B8)

    /**
     * Contexto completo pra reprocessar um documento preso (F5): igual a
     * {@link #buscarContextoParaConsulta}, mais {@code situacao} (precondição — só faz sentido
     * reprocessar {@code TRANSMITINDO}/{@code ASSINADO}) e {@code xmlAssinado} (pra retransmitir
     * quando a SEFAZ disser que a nota não consta).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ContextoReprocessamento> buscarParaReprocessar(long idDocumentoFiscal) {
        return jdbc.sql("""
                        SELECT d.id_empresa, d.modelo, d.situacao::text AS situacao, d.chave_acesso,
                               d.xml_assinado, d.ambiente::text AS ambiente, e.estado AS uf
                          FROM documento_fiscal d
                          JOIN empresa e ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_documento_fiscal = ?
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new ContextoReprocessamento(
                        rs.getLong("id_empresa"), rs.getInt("modelo"), rs.getString("situacao"),
                        rs.getString("chave_acesso"), rs.getString("xml_assinado"),
                        MontagemNfceDtos.AmbienteSefaz.valueOf(rs.getString("ambiente")), ufDoDocumento(rs)))
                .optional();
    }

    public record ContextoReprocessamento(long idEmpresa, int modelo, String situacao, String chaveAcesso,
                                          String xmlAssinado, MontagemNfceDtos.AmbienteSefaz ambiente,
                                          String uf) {
    }

    // ---------------------------------------------------------------- arquivamento (docs/HANDOFF-ARQUIVAMENTO-XML.md)

    /**
     * Contexto pra montar o {@code nfeProc} de um documento AUTORIZADO. {@code xmlObjetoBucketAtual}
     * vem junto pra o chamador decidir "já arquivado" sem uma segunda query (P2 — idempotência).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<DocumentoParaArquivar> buscarParaArquivar(long idDocumentoFiscal) {
        return jdbc.sql("""
                        SELECT d.modelo, d.chave_acesso, e.estado AS uf, d.data_emissao,
                               d.xml_assinado, d.status_sefaz, d.xml_objeto_bucket
                          FROM documento_fiscal d
                          JOIN empresa e ON e.id_tenant = d.id_tenant AND e.id_empresa = d.id_empresa
                         WHERE d.id_tenant = plataforma.tenant_atual() AND d.id_documento_fiscal = ?
                           AND d.situacao = 'AUTORIZADO'
                        """)
                .param(idDocumentoFiscal)
                .query((rs, n) -> new DocumentoParaArquivar(
                        rs.getInt("modelo"), rs.getString("chave_acesso"), ufDoDocumento(rs),
                        rs.getObject("data_emissao", java.time.OffsetDateTime.class),
                        rs.getString("xml_assinado"), rs.getString("status_sefaz"),
                        rs.getString("xml_objeto_bucket")))
                .optional();
    }

    /** {@code uf} entra em 2026-08-20: o ano/mês do caminho no bucket saem do instante local da UF
     *  do emitente, não de um fuso fixo — senão a nota de 31/12 de uma loja de Manaus vai parar na
     *  pasta do ano seguinte, onde o contador não procura. */
    public record DocumentoParaArquivar(int modelo, String chaveAcesso, String uf,
                                        java.time.OffsetDateTime dataEmissao,
                                        String xmlAssinado, String statusSefaz, String xmlObjetoBucketAtual) {
    }

    /** F6: {@code xml_objeto_bucket}/{@code xml_hash} só são gravados UMA vez — o trigger de
     *  imutabilidade (V035) recusa um segundo UPDATE depois que o bucket deixou de ser NULL. */
    @Transactional
    public void marcarXmlArquivado(long idDocumentoFiscal, String chave, String hash) {
        jdbc.sql("""
                        UPDATE documento_fiscal SET xml_objeto_bucket = ?, xml_hash = ?, atualizado_em = now()
                         WHERE id_tenant = plataforma.tenant_atual() AND id_documento_fiscal = ?
                        """)
                .params(chave, hash, idDocumentoFiscal)
                .update();
    }

    /**
     * Entradas por XML cujo arquivamento no bucket ainda não aconteceu (V051) — o XML segue na
     * coluna até o job levar. {@code chave_nfe} não nula filtra entrada manual/planilha, que não
     * tem nota de origem e nunca vai ter o que arquivar.
     */
    @Transactional(readOnly = true)
    public List<Long> entradasPendentesDeArquivamento(int limite) {
        return jdbc.sql("""
                        SELECT ex.id_movimento
                          FROM entrada_xml ex
                          JOIN produto_movimento_mestre m
                            ON m.id_tenant = ex.id_tenant AND m.id_movimento = ex.id_movimento
                         WHERE ex.id_tenant = plataforma.tenant_atual()
                           AND ex.xml_objeto_bucket IS NULL AND ex.xml_bruto IS NOT NULL
                           AND m.chave_nfe IS NOT NULL
                         ORDER BY ex.importado_em
                         LIMIT ?
                        """)
                .param(limite).query(Long.class).list();
    }

    /** Dentro do {@code TenantContext} (P8) — ids pendentes de arquivamento desse tenant, mais
     *  antigo primeiro. */
    @Transactional(readOnly = true)
    public List<Long> pendentesDeArquivamento(int limite) {
        return jdbc.sql("""
                        SELECT id_documento_fiscal FROM documento_fiscal
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND situacao = 'AUTORIZADO' AND xml_objeto_bucket IS NULL
                         ORDER BY data_autorizacao
                         LIMIT ?
                        """)
                .param(limite).query(Long.class).list();
    }

    /**
     * Contexto para arquivar o XML da <b>NF-e do fornecedor</b> (V051).
     *
     * <p>Mora neste repositório — e não em {@code estoque} — porque quem arquiva é o
     * {@link ArquivamentoXmlService}, e concentrar aqui evita um segundo caminho de gravação no
     * bucket fiscal com regra própria. A UF vem de {@code empresa} (a nota é de terceiro; a UF que
     * decide o fuso do caminho é a de quem recebeu).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<EntradaXmlParaArquivar> buscarEntradaParaArquivar(long idMovimento) {
        return jdbc.sql("""
                        SELECT ex.xml_bruto, ex.xml_objeto_bucket, m.chave_nfe, m.data_movimento,
                               e.estado AS uf
                          FROM entrada_xml ex
                          JOIN produto_movimento_mestre m
                            ON m.id_tenant = ex.id_tenant AND m.id_movimento = ex.id_movimento
                          JOIN empresa e ON e.id_tenant = m.id_tenant AND e.id_empresa = m.id_empresa
                         WHERE ex.id_tenant = plataforma.tenant_atual() AND ex.id_movimento = ?
                        """)
                .param(idMovimento)
                .query((rs, n) -> new EntradaXmlParaArquivar(
                        rs.getString("xml_bruto"), rs.getString("xml_objeto_bucket"),
                        rs.getString("chave_nfe"),
                        rs.getObject("data_movimento", java.time.OffsetDateTime.class),
                        rs.getString("uf")))
                .optional();
    }

    public record EntradaXmlParaArquivar(String xmlBruto, String xmlObjetoBucketAtual, String chaveNfe,
                                         java.time.OffsetDateTime dataMovimento, String uf) {
    }

    /**
     * Fecha o arquivamento da entrada: grava a chave do objeto e o hash, e <b>zera o
     * {@code xml_bruto}</b> — o XML passa a viver só no bucket, que é o ponto da mudança (tirar
     * meio giga por mês de nota de fornecedor de dentro do Postgres e do {@code pg_dump}).
     *
     * <p>A ordem importa: só zera depois que a gravação no bucket voltou com a chave. Se o MinIO
     * estiver fora, o XML continua na coluna e o job tenta de novo — nunca existe um instante em
     * que o XML não esteja em lugar nenhum.
     */
    @Transactional
    public void marcarEntradaXmlArquivada(long idMovimento, String chave, String hash) {
        jdbc.sql("""
                        UPDATE entrada_xml
                           SET xml_objeto_bucket = ?, xml_hash = ?, arquivado_em = now(), xml_bruto = NULL
                         WHERE id_tenant = plataforma.tenant_atual() AND id_movimento = ?
                           AND xml_objeto_bucket IS NULL
                        """)
                .params(chave, hash, idMovimento)
                .update();
    }

    /** Contexto pra arquivar o XML de um EVENTO autorizado (cancelamento 110111). */
    @Transactional(readOnly = true)
    public java.util.Optional<EventoParaArquivar> buscarEventoParaArquivar(long idEvento) {
        return jdbc.sql("""
                        SELECT e.tipo_evento, e.sequencia, e.xml_evento, e.criado_em, e.xml_objeto_bucket,
                               d.chave_acesso, d.modelo, emp.estado AS uf
                          FROM documento_fiscal_evento e
                          JOIN documento_fiscal d
                            ON d.id_tenant = e.id_tenant AND d.id_documento_fiscal = e.id_documento_fiscal
                          JOIN empresa emp ON emp.id_tenant = d.id_tenant AND emp.id_empresa = d.id_empresa
                         WHERE e.id_tenant = plataforma.tenant_atual() AND e.id_evento = ? AND e.autorizado = true
                        """)
                .param(idEvento)
                .query((rs, n) -> new EventoParaArquivar(
                        rs.getString("tipo_evento"), rs.getInt("sequencia"), rs.getString("xml_evento"),
                        rs.getObject("criado_em", java.time.OffsetDateTime.class),
                        rs.getString("xml_objeto_bucket"), rs.getString("chave_acesso"), rs.getInt("modelo"),
                        ufDoDocumento(rs)))
                .optional();
    }

    public record EventoParaArquivar(String tipoEvento, int sequencia, String xmlEvento,
                                     java.time.OffsetDateTime criadoEm, String xmlObjetoBucketAtual,
                                     String chaveAcesso, int modelo, String uf) {
    }

    /** {@code documento_fiscal_evento} não tem {@code xml_hash} (V035) — só o ponteiro. */
    @Transactional
    public void marcarEventoArquivado(long idEvento, String chave) {
        jdbc.sql("""
                        UPDATE documento_fiscal_evento SET xml_objeto_bucket = ?
                         WHERE id_tenant = plataforma.tenant_atual() AND id_evento = ?
                        """)
                .params(chave, idEvento).update();
    }

    @Transactional(readOnly = true)
    public List<Long> eventosPendentesDeArquivamento(int limite) {
        return jdbc.sql("""
                        SELECT id_evento FROM documento_fiscal_evento
                         WHERE id_tenant = plataforma.tenant_atual()
                           AND autorizado = true AND xml_objeto_bucket IS NULL
                         ORDER BY criado_em
                         LIMIT ?
                        """)
                .param(limite).query(Long.class).list();
    }

    /** {@code valor_troco} é {@code NOT NULL} — troco não informado é zero, nunca ausência. */
    private static java.math.BigDecimal nz(java.math.BigDecimal valor) {
        return valor == null ? java.math.BigDecimal.ZERO : valor;
    }

    private static String sha256(String xml) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular o hash do XML.", e);
        }
    }
}
