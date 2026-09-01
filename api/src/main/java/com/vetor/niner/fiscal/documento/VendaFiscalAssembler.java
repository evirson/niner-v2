package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.fiscal.configuracao.CsrtService;
import com.vetor.niner.fiscal.documento.EmissaoNfceService.PedidoDeEmissao;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Destinatario;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Emitente;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ItemNota;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ModeloVenda;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Pagamento;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ResponsavelTecnico;
import com.vetor.niner.fiscal.motor.MotorTributario;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.ContextoFiscalEmpresa;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.ItemOperacao;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.OperacaoFiscal;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.RegraFiscal;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.TipoDestinatario;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.TipoOperacao;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.TotaisTributarios;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.TributacaoResultado;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    /**
     * @param observacao 2026-09-01 — texto livre digitado pelo operador <b>antes de emitir</b>, que
     *         entra no {@code infCpl} do XML e, por consequência, no DANFE. Decisão dele entre
     *         digitar antes de emitir ou antes de imprimir: antes de imprimir a nota já está
     *         autorizada, e o papel passaria a dizer algo que o arquivo da SEFAZ não diz.
     *         {@code null}/vazio simplesmente não gera a linha.
     */
    @Transactional(readOnly = true)
    public Optional<PedidoDeEmissao> montar(long idTenant, long idEmpresa, long idVenda, Integer idUsuario,
                                            boolean incluirCpf, String observacao) {
        ConfigEmpresa config = buscarConfig(idEmpresa);
        if (config == null || !config.emiteNfce()) {
            return Optional.empty();
        }

        VendaHeader venda = buscarVenda(idEmpresa, idVenda);
        Destinatario destinatario = incluirCpf ? buscarDestinatarioObrigatorio(venda.idCliente()) : null;
        String ufDestino = destinatario != null && destinatario.uf() != null ? destinatario.uf() : config.uf();

        // ---------- qual documento esta venda gera ----------
        // Pessoa FISICA -> NFC-e; pessoa JURIDICA -> NF-e 55 (decisao do dono do produto em
        // 2026-08-24, substituindo a regra do dia anterior, que olhava so o indicador_ie).
        //
        // O motivo de fundo continua o mesmo (DF13): NFC-e e documento de consumidor final, e
        // quem compra com CNPJ costuma revender ou precisar da nota para credito. Cobrir toda PJ
        // e mais simples de explicar ao lojista ("CNPJ = NF-e") e nunca emite documento de menos.
        //
        // ⚠️ ARMADILHA DE NOME: cliente.fisica_juridica vale TRUE para pessoa FISICA (e ela que
        // exige genero e CPF de 11 digitos). Destinatario.pessoaJuridica ja e o INVERSO, montado
        // em buscarDestinatario — trocar os dois emitiria NF-e para todo consumidor de balcao.
        ModeloVenda modelo = destinatario != null && destinatario.pessoaJuridica()
                ? ModeloVenda.NFE
                : ModeloVenda.NFCE;

        // ⚠️ O TIPO DE DESTINATARIO vem do indicador_ie, NAO do modelo (corrigido em 2026-08-24,
        // na 1a transmissao real). Sao coisas diferentes: uma PJ NAO contribuinte recebe NF-e 55
        // (porque e PJ) mas continua sendo CONSUMIDOR FINAL para efeito de CFOP — ela compra para
        // uso proprio, nao para revender. Amarrar o tipo ao modelo mandava toda PJ para a regra de
        // contribuinte, que nem existe cadastrada, e o CFOP saia errado.
        TipoDestinatario tipoDestinatario = destinatario != null && destinatario.indicadorIe() == 1
                ? TipoDestinatario.CONTRIBUINTE
                : TipoDestinatario.CONSUMIDOR_FINAL;

        // Operacao interestadual: o CFOP muda com o destino (5xxx dentro do estado, 6xxx fora).
        // Mesma comparacao que decide o idDest do XML — as duas TEM que concordar, senao a SEFAZ
        // recusa com cStat 733 ("CFOP de operacao interna e idDest difere de 1").
        boolean interestadual = !ufDestino.equalsIgnoreCase(config.uf());
        String impedimentoNfe = modelo.ehNfe() ? impedimentoParaNfe(config, destinatario) : null;

        List<ItemBruto> itensBrutos = buscarItens(idVenda);
        if (itensBrutos.isEmpty()) {
            // ⚠️ Duas causas diferentes, dois conselhos diferentes — e a mensagem tem de dizer qual
            // é (mesma lição de "40 notas sem XML", 2026-08-26: agrupar populações com conselhos
            // opostos num aviso só manda o operador resolver o problema errado).
            // Venda 100% serviço TEM itens; o que ela não tem é mercadoria, e NFC-e é documento de
            // ICMS. Dizer "não tem itens" mandaria o operador procurar um item que está na tela.
            // ⚠️ Desde 2026-09-01 o PDV NÃO chega mais aqui numa venda 100% serviço: o
            // VendaFiscalService confere `temMercadoriaNaVenda` antes e manda a venda direto para
            // a perna da NFS-e. Esta mensagem sobrevive para quem chama o montador por outro
            // caminho (reprocessamento em Documentos Fiscais) — e por isso teve de ser corrigida:
            // ela ainda dizia que a NFS-e "não é emitida pelo Nainer", o que virou mentira no dia
            // em que a pendência #78 fechou.
            throw new ResponseStatusException(HttpStatus.CONFLICT, somenteServico(idVenda)
                    ? "Venda nº " + idVenda + " só tem serviços — NFC-e/NF-e é documento de "
                      + "mercadoria. O documento fiscal desta venda é a NFS-e: emita pelo PDV, ou "
                      + "ligue a NFS-e em Fiscal › Configuração da NFS-e."
                    : "Venda nº " + idVenda + " não tem itens — não há o que emitir.");
        }

        List<ItemOperacao> itensOperacao = new ArrayList<>();
        List<ItemNota> itensNota = new ArrayList<>();
        for (int i = 0; i < itensBrutos.size(); i++) {
            ItemBruto b = itensBrutos.get(i);
            int nItem = i + 1;
            RegraFiscal regra = buscarRegra(b, config.crt(), ufDestino, tipoDestinatario,
                    interestadual, b.descricao());
            itensOperacao.add(new ItemOperacao(nItem, b.qtd(), b.precoVenda(), b.valorDesconto(),
                    b.valorAcrescimo(), regra, aliquotaTribFederal(b), b.alqEstadual(), b.alqMunicipal()));
            itensNota.add(new ItemNota(nItem, b.sku(), b.gtin(), b.descricao(), b.ncm(), b.cest(),
                    b.unidadeComercial(), b.qtd(), b.precoVenda(), b.unidadeTributavel(), null, null,
                    b.origemMercadoria()));
        }

        TributacaoResultado calculo = motor.calcular(
                // ⚠️ CONTRIBUINTE muda o CFOP que o motor escolhe (revenda, nao consumo) — e e por
                // isso que a venda saiu do modelo 65. Declarar CONSUMIDOR_FINAL aqui contradiria o
                // indFinal=0 e o indIEDest=1 que o XML leva.
                new OperacaoFiscal(TipoOperacao.VENDA, ufDestino, tipoDestinatario, itensOperacao),
                new ContextoFiscalEmpresa(config.crt(), config.uf()));

        // ⚠️ Rateado para fechar com o vNF (2026-08-29). Ver `ratearParaOTotalDaNota`: numa venda
        // MISTA (serviço + mercadoria) os itens são filtrados e os pagamentos não eram, e o XML
        // saía declarando mais dinheiro do que a nota vale.
        List<Pagamento> pagamentos = ratearParaOTotalDaNota(
                buscarPagamentos(idVenda), calculo.totais().valorNota());

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
        // O modelo importa: a exigencia de CSRT e por (UF, modelo) — ha UF que cobra no 55 e nao
        // no 65 (ver docs/MODULOFISCAL.md §9.9).
        if (codigo.isEmpty() && csrt.exigeCsrt(config.uf(), modelo.codigo(), tpAmb)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    CsrtService.mensagemFaltando(config.uf(), modelo.codigo()));
        }
        ResponsavelTecnico responsavelTecnico = new ResponsavelTecnico(
                respTec.cnpj(), respTec.contato(), respTec.email(), respTec.telefone(),
                codigo.map(CsrtService.Csrt::idCsrt).orElse(null),
                codigo.map(CsrtService.Csrt::codigo).orElse(null));

        return Optional.of(new PedidoDeEmissao(impedimentoNfe, idTenant, idEmpresa, (int) idVenda,
                venda.idCliente() == null ? null : venda.idCliente().intValue(), idUsuario,
                config.ambiente(), modelo,
                modelo.ehNfe() ? config.serieNfe() : config.serieNfce(),
                // ⛔ O `dhEmi` é o instante da EMISSÃO, não o da venda (2026-09-01). Aqui ia
                // `venda.dataVenda()`, e a SEFAZ rejeitou com **cStat 704 — "NFC-e com Data-Hora
                // de emissão atrasada. Tolerância de até 5 minutos"**: medido na venda 628, cujas
                // três tentativas saíram todas com `dhEmi 19:54:34` e as de 20:38 e 20:40 não
                // tinham como passar.
                //
                // ⚠️ Quem isso atingia não era só a retransmissão: a loja com
                // `cfg_emite_fiscal_apos_venda` DESLIGADO emite depois, por escolha — e ali toda
                // nota nasceria rejeitada, **queimando um número por tentativa**. Nota emitida
                // hoje para uma venda de ontem sai com a data de hoje, e essa é a resposta
                // correta: o documento fiscal está sendo emitido AGORA.
                //
                // ⭐ As duas devoluções (`DevolucaoFiscalAssembler`, `DevolucaoCompraFiscalAssembler`)
                // já faziam assim; a venda é que era a exceção.
                //
                // ⚠️ Offset do container não importa: `MontadorXmlNfce` normaliza com
                // `atZoneSameInstant(FusoDaUf.de(uf do emitente))` antes de usar no `dhEmi` e no
                // AAMM da chave. O que precisa estar certo aqui é o INSTANTE.
                //
                // ⚠️ A contingência não é afetada: o dreno reenvia o `xml_assinado` como está,
                // não remonta — e ali o `dhEmi` antigo é legítimo (é o momento em que a nota foi
                // emitida offline, com `tpEmis=9` explicando à SEFAZ por que chegou depois).
                OffsetDateTime.now(),
                // A natureza da operacao aparece no DANFE e descreve o que a nota e: "ao consumidor"
                // seria falso numa venda para revenda.
                modelo.ehNfe() ? "VENDA DE MERCADORIA" : "VENDA AO CONSUMIDOR",
                emitente, destinatario, itensNota, calculo.itens(), calculo.totais(), pagamentos,
                // O PDV não tem campo de troco explícito hoje — pagamentos sempre fecham o saldo
                // exato (split-tender). documento_fiscal.valor_troco é NOT NULL: zero é o valor
                // correto quando não houve troco, não um placeholder.
                BigDecimal.ZERO,
                montarInformacoesComplementares(idVenda, venda, buscarFormasDePagamento(idVenda),
                        calculo.totais(), observacao),
                responsavelTecnico, "Niner PDV 1.0"));
    }

    // ---------------------------------------------------------------- leituras

    private ConfigEmpresa buscarConfig(long idEmpresa) {
        return jdbc.sql("""
                        SELECT c.emite_nfce, c.emite_nfe, c.ambiente::text AS ambiente, c.serie_nfce, c.serie_nfe, c.crt,
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
                        return new ConfigEmpresa(false, false, null, 0, 0, 0, null, null, null, null,
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
                    return new ConfigEmpresa(true, rs.getBoolean("emite_nfe"),
                            AmbienteSefaz.valueOf(rs.getString("ambiente")),
                            rs.getInt("serie_nfce"), rs.getInt("serie_nfe"), rs.getInt("crt"), rs.getString("cnpj"),
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
                        SELECT v.id_cliente, v.data_venda,
                               f.id_funcionario AS codigo_vendedor, f.nome AS nome_vendedor
                          FROM venda v
                          LEFT JOIN funcionario f
                            ON f.id_tenant = v.id_tenant AND f.id_funcionario = v.id_funcionario
                         WHERE v.id_tenant = plataforma.tenant_atual()
                           AND v.id_empresa = ? AND v.id_venda = ?
                        """)
                .params(idEmpresa, idVenda)
                .query((rs, n) -> new VendaHeader(
                        (Integer) rs.getObject("id_cliente"),
                        rs.getObject("data_venda", OffsetDateTime.class),
                        (Integer) rs.getObject("codigo_vendedor"),
                        rs.getString("nome_vendedor")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Venda nº " + idVenda + " não encontrada."));
    }

    /**
     * Nomes das carteiras usadas na venda, na ordem em que foram lançadas — o "FORMA PGTO" do
     * campo de informações complementares.
     *
     * <p>⚠️ É o <b>nome da carteira</b> ("DINHEIRO", "CARTAO CREDITO"), não o {@code tPag} do XML:
     * quem lê o DANFE é o lojista e o cliente, e "01" não diz nada a nenhum dos dois. O {@code tPag}
     * continua indo no grupo {@code <pag>}, que é o que a SEFAZ lê.
     */
    private List<String> buscarFormasDePagamento(long idVenda) {
        return jdbc.sql("""
                        SELECT DISTINCT tc.nome_carteira
                          FROM contas_receber cr
                          JOIN tipo_carteira tc
                            ON tc.id_tenant = cr.id_tenant AND tc.id_carteira = cr.id_carteira
                         WHERE cr.id_tenant = plataforma.tenant_atual() AND cr.id_venda = ?
                         ORDER BY tc.nome_carteira
                        """)
                .param(idVenda)
                .query(String.class).list();
    }

    /**
     * Monta o {@code infCpl} — o campo "INFORMAÇÕES COMPLEMENTARES" do DANFE (2026-09-01).
     *
     * <p>Até esta data o Nainer mandava <b>{@code null}</b>: o grupo {@code <infAdic>} nem era
     * gerado, e o DANFE imprimia um travessão. O conteúdo aqui espelha o que o sistema anterior do
     * lojista já emitia, mais duas linhas que ele pediu.
     *
     * <p>⭐ <b>O valor aproximado dos tributos vem para CÁ, e isso é o ponto</b> (pedido dele).
     * Antes ele era desenhado só no DANFE, pelo React — ou seja, existia no papel e <b>não</b>
     * existia no XML autorizado. A Lei 12.741/2012 exige a informação <b>no documento fiscal</b>;
     * texto que só o React desenha não está no documento, está no retrato dele.
     *
     * <p>⭐ E a <b>observação do operador</b> entra aqui, no XML assinado — decisão dele em
     * 2026-09-01, entre digitar antes de emitir ou antes de imprimir. Antes de imprimir a nota já
     * está autorizada, e o DANFE passaria a mostrar um texto que a SEFAZ não tem: o papel diria uma
     * coisa e o arquivo, outra.
     */
    private String montarInformacoesComplementares(long idVenda, VendaHeader venda,
            List<String> formasPagamento, TotaisTributarios totais, String observacao) {
        List<String> linhas = new ArrayList<>();

        StringBuilder cabecalho = new StringBuilder("CONTRATO: ").append(idVenda);
        if (!formasPagamento.isEmpty()) {
            cabecalho.append(", FORMA PGTO: ").append(String.join(" / ", formasPagamento));
        }
        if (venda.nomeVendedor() != null) {
            cabecalho.append(", VENDEDOR: ").append(venda.codigoVendedor()).append('-').append(venda.nomeVendedor());
        }
        // ⚠️ Sem o ";" do fim: ele existia para separar do que vinha depois, e desde que as partes
        // passaram a ser unidas por " | " ele virou "; |" — dois separadores colados, visto na
        // nota 31. O modelo do outro sistema usa ";" porque lá a separação é quebra de linha, que
        // o XSD do infCpl não aceita (ver a nota no fim deste método).
        linhas.add(cabecalho.toString());

        if (observacao != null && !observacao.isBlank()) {
            // ⛔ O operador digita num <textarea> e Enter produz "\n" — que o XSD do infCpl RECUSA
            // (ver a nota no fim deste método). Sem esta troca, o mesmo defeito que a suíte pegou
            // no separador voltaria pela porta do usuário, e aí só na venda real: bastaria alguém
            // apertar Enter para a nota inteira ser rejeitada. Tabulação e CR entram na conta pelo
            // mesmo motivo, e espaços repetidos viram um só para não desperdiçar o campo.
            linhas.add("OBS.: " + observacao.replaceAll("\\s+", " ").strip());
        }

        if (totais.valorTotalTributos() != null && totais.valorTotalTributos().signum() > 0) {
            linhas.add("Valor aproximado dos tributos: R$ " + real(totais.valorTotalTributos())
                    + " (Lei 12.741/2012 - Fonte: IBPT).");
        }

        // Só aparece quando há o que declarar — enquanto as alíquotas da reforma estiverem em zero,
        // uma linha "IBS: R$ 0,00" seria ruído num campo que o lojista lê todo dia.
        if (totais.valorIbsUf() != null && (totais.valorIbsUf().signum() > 0
                || (totais.valorCbs() != null && totais.valorCbs().signum() > 0))) {
            BigDecimal ibs = totais.valorIbsUf().add(nzero(totais.valorIbsMun()));
            linhas.add("REFORMA TRIBUTARIA (LC 214/2025) - BASE IBS/CBS: R$ " + real(totais.baseIbsCbs())
                    + " | IBS: R$ " + real(ibs) + " | CBS: R$ " + real(totais.valorCbs()));
        }

        // ⛔ ESPAÇO, nunca "\n" — e isto foi MEDIDO, não deduzido (2026-09-01).
        //
        // O XSD oficial define infCpl como `[!-ÿ]{1}[ -ÿ]{0,}[!-ÿ]{1}|[!-ÿ]{1}`: a faixa começa no
        // ESPAÇO (0x20), e a quebra de linha é 0x0A — fora dela. A primeira versão juntava com
        // "\n" e o validador XSD recusou TODA nota com:
        //
        //   cvc-pattern-valid: o valor '…' não tem um aspecto válido em relação ao padrão
        //   '[!-ÿ]{1}[ -ÿ]{0,}[!-ÿ]{1}|[!-ÿ]{1}' do tipo '#AnonType_infCplinfAdicinfNFeTNFe'
        //
        // ⭐ Quem pegou foi o F11 (bloqueio preventivo antes de transmitir) + a suíte: 5 testes
        // que já existiam ficaram vermelhos na hora. Sem eles, a primeira venda real depois do
        // deploy é que teria descoberto — com o cliente na frente.
        //
        // ⚠️ E as quebras que aparecem no DANFE do outro sistema NÃO estão no XML: quem quebra é a
        // largura do campo no papel. Texto de uma linha só é o que o leiaute permite.
        //
        // ⚠️ O separador é " | " e não " ": com espaço simples, uma observação que não termina em
        // pontuação colava na frase seguinte — medido no XML da nota 30, que saiu
        // "OBS.: bla bla bla bla Valor aproximado dos tributos: R$ 128,73". A barra é ASCII
        // imprimível (0x7C), dentro da faixa que o XSD aceita.
        return String.join(" | ", linhas);
    }

    private static BigDecimal nzero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 1234.5 → "1.234,50" — o formato que o lojista lê no papel. */
    private static String real(BigDecimal v) {
        return String.format(java.util.Locale.of("pt", "BR"), "%,.2f", nzero(v));
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
        // ⚠️ Lê o cadastro INTEIRO, não só o que a NFC-e usa (2026-08-24): quando o cliente é
        // contribuinte de ICMS a venda sai em NF-e 55, e ali `enderDest` é obrigatório. Buscar de
        // uma vez evita uma segunda consulta e mantém a decisão de modelo fora daqui.
        return jdbc.sql("""
                        SELECT cpf_cnpj, nome, indicador_ie, codigo_municipio_ibge, cidade, estado,
                               rg_ie, endereco, numero, complemento, bairro, cep, telefone,
                               fisica_juridica
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
                            rs.getString("estado"),
                            rs.getString("rg_ie"), rs.getString("endereco"), rs.getString("numero"),
                            rs.getString("complemento"), rs.getString("bairro"), rs.getString("cep"),
                            rs.getString("telefone"),
                            // ⚠️ INVERTE: fisica_juridica = true significa pessoa FÍSICA.
                            !rs.getBoolean("fisica_juridica"));
                })
                .optional()
                .orElse(null);
    }

    /**
     * O que impede esta venda de sair em NF-e 55 — {@code null} quando nada impede.
     *
     * <p>⚠️ <b>Devolve mensagem em vez de lançar, e isso é o ponto</b> (2026-08-24). A primeira
     * versão lançava 409 e <b>travava o fechamento da venda</b> por cadastro incompleto de cliente
     * — violando o <b>F3</b> ("a venda nunca desaparece porque a nota falhou"), que é justamente o
     * princípio que o resto deste módulo protege com cuidado. Quem pegou foi
     * {@code VendaFiscalEmissaoTest.vendaAContribuinteFicaNaoEmitidaMasVendaContinuaRegistrada}.
     * O caminho certo é o que já existia: a venda é registrada, o documento fica em
     * {@code NAO_EMITIDO} com o motivo por extenso, e o lojista resolve em Documentos Fiscais.
     *
     * <p>Dois pré-requisitos:
     * <ol>
     *   <li><b>{@code emite_nfe} ligado</b> na empresa — ter NFC-e ligada não implica ter NF-e:
     *       são séries e credenciamentos distintos;</li>
     *   <li><b>cadastro do cliente completo</b> — a 55 exige {@code enderDest}, que a NFC-e nem
     *       tem. Conferir aqui evita queimar número para receber uma rejeição de XSD que não diria
     *       ao operador que o problema é o cadastro.</li>
     * </ol>
     */
    private String impedimentoParaNfe(ConfigEmpresa config, Destinatario destinatario) {
        if (!config.emiteNfe()) {
            return "O cliente é pessoa jurídica, então a venda exige NF-e (modelo 55) — a NFC-e é "
                    + "documento de consumidor final. A emissão de NF-e não está ligada para esta "
                    + "empresa: ligue em Configuração Fiscal e emita a nota em Documentos Fiscais.";
        }
        String falta = faltaParaNfe(destinatario);
        if (falta != null) {
            return "O cliente é pessoa jurídica, então a venda exige NF-e (modelo 55), que pede o "
                    + "cadastro completo do cliente. Falta: " + falta
                    + ". Complete em Clientes e emita a nota em Documentos Fiscais.";
        }
        return null;
    }

    /**
     * O que falta no cadastro para a venda sair em NF-e 55 — {@code null} quando está tudo lá.
     *
     * <p><b>Por que existe.</b> A NFC-e aceita destinatário só com CPF e nome; a NF-e 55 exige
     * endereço completo e, do contribuinte, a inscrição estadual. Sem esta conferência o defeito
     * apareceria tarde e caro: número de NF-e <b>queimado</b>, rejeição da SEFAZ com uma mensagem
     * de XSD, e o operador sem saber que o problema é o cadastro do cliente. Mesma linha do DF13,
     * que já recusava antes de reservar numeração.
     */
    static String faltaParaNfe(Destinatario d) {
        List<String> faltando = new ArrayList<>();
        if (vazio(d.logradouro())) faltando.add("endereço");
        if (vazio(d.numero())) faltando.add("número");
        if (vazio(d.bairro())) faltando.add("bairro");
        if (d.codigoMunicipioIbge() == null || d.codigoMunicipioIbge() == 0) faltando.add("município (código IBGE)");
        if (vazio(d.uf())) faltando.add("UF");
        // A IE só é exigida de quem se declarou contribuinte — para 2/9 o XSD nem aceita o campo.
        if (d.indicadorIe() == 1 && vazio(d.inscricaoEstadual())) faltando.add("inscrição estadual");
        return faltando.isEmpty() ? null : String.join(", ", faltando);
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    /**
     * A venda tem movimento, mas <b>nenhuma mercadoria</b> — é 100% serviço. Serve só para
     * escolher a mensagem certa: sem isto, a venda de um banho e tosa receberia
     * <i>"não tem itens"</i>, e o operador iria procurar na tela um item que está lá.
     */
    private boolean somenteServico(long idVenda) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1
                                         FROM produto_movimento_detalhe pmd
                                         JOIN produto_movimento_mestre pmm
                                           ON pmm.id_tenant = pmd.id_tenant AND pmm.id_movimento = pmd.id_movimento
                                        WHERE pmd.id_tenant = plataforma.tenant_atual()
                                          AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA')
                        """)
                .param(idVenda).query(Boolean.class).optional().orElse(false));
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
                           -- ⛔ SERVIÇO NÃO ENTRA NA NFC-e/NF-e (V085, bloco S1 do módulo de
                           -- serviços). Mão de obra é fato gerador de ISS e sai em NFS-e, que é
                           -- MUNICIPAL — não em documento de ICMS. Sem este filtro, "BANHO E TOSA"
                           -- iria dentro da nota com NCM e CFOP de mercadoria, e ⚠️ o pior caso
                           -- não é a SEFAZ rejeitar: é AUTORIZAR, e o erro só aparecer numa
                           -- fiscalização.
                           AND pb.tipo_item = 'MERCADORIA'
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
    /**
     * O CFOP da operação: {@code cfop} dentro do estado, {@code cfop_interestadual} para fora
     * (2026-08-24).
     *
     * <p><b>Por que a regra carrega os dois, e o sistema não deriva um do outro.</b> Parece que
     * bastaria trocar o 5 pelo 6 mantendo o sufixo, e para os CFOPs comuns funciona (5102 → 6102).
     * Mas não é regra geral: {@code 5405} (revenda com ST, contribuinte substituído) <b>não</b>
     * vira {@code 6405} — esse CFOP não existe na tabela oficial; o correspondente é {@code 6404}.
     * Derivar às cegas emitiria nota com CFOP inválido ou, pior, com um CFOP válido que descreve
     * outra operação. A escolha é do contador.
     *
     * <p>Faltando o interestadual numa venda para fora do estado, a emissão para <b>aqui</b>, com
     * a UF na mensagem — nunca com um CFOP chutado (F11).
     */
    private static String cfopDaOperacao(ResultSet rs, boolean interestadual, String descricaoProduto,
                                         String ufDestino) throws SQLException {
        // ⚠️ `trim()` obrigatório: as duas colunas são `character(4)` (V017/V061), e o Postgres
        // COMPLETA COM ESPAÇOS o que foi gravado menor. Um `<CFOP>6   </CFOP>` é recusado pelo XSD
        // (`TCfop` é `[0-9]{4}`) já no balcão. A validação de gravação nasceu só em 2026-08-30 —
        // regra cadastrada ANTES disso pode estar curta no banco, e é a leitura que precisa
        // segurar. Corrigir só a escrita deixaria de pé exatamente o caso que já existe.
        if (!interestadual) {
            return exigirCfopCompleto(rs.getString("cfop"), descricaoProduto, "CFOP");
        }
        String cfopFora = rs.getString("cfop_interestadual");
        if (cfopFora == null || cfopFora.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Venda para outro estado (" + ufDestino + ") e a regra fiscal do produto \""
                            + descricaoProduto + "\" não tem CFOP interestadual. O CFOP muda com o "
                            + "destino (5xxx dentro do estado, 6xxx para fora): preencha o CFOP "
                            + "interestadual em Perfil Fiscal antes de emitir.");
        }
        return exigirCfopCompleto(cfopFora, descricaoProduto, "CFOP interestadual");
    }

    /**
     * O CFOP sem os espaços do {@code character(4)}, recusando o que ficou incompleto.
     *
     * <p>F11: a emissão nunca chuta um CFOP nem manda um valor que a SEFAZ vai rejeitar — recusa
     * com uma mensagem que aponta o cadastro. Um {@code "6   "} sairia no XML e voltaria como erro
     * de schema, que não diz nada sobre Perfil Fiscal.
     */
    private static String exigirCfopCompleto(String bruto, String descricaoProduto, String rotulo) {
        String cfop = bruto == null ? "" : bruto.trim();
        if (!cfop.matches("\\d{4}")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O " + rotulo + " da regra fiscal do produto \"" + descricaoProduto + "\" está incompleto (\""
                            + cfop + "\"). Corrija em Perfil Fiscal — o CFOP tem 4 dígitos.");
        }
        return cfop;
    }

    private RegraFiscal buscarRegra(ItemBruto item, int crt, String ufDestino,
                                    TipoDestinatario tipoDestinatario, boolean interestadual,
                                    String descricaoProduto) {
        // ⚠️ Ate 2026-08-24 estes dois eram LITERAIS na consulta: toda venda buscava a regra de
        // CONSUMIDOR_FINAL/VENDA_CONSUMIDOR, mesmo quando o destinatario era contribuinte. O CFOP
        // vem da regra, entao a nota saia com o CFOP da operacao errada.
        String tipo = tipoDestinatario.name();
        String operacao = tipoDestinatario == TipoDestinatario.CONTRIBUINTE
                ? "VENDA_CONTRIBUINTE"
                : "VENDA_CONSUMIDOR";
        return jdbc.sql("""
                        SELECT r.cfop, r.cfop_interestadual, r.cst_icms, r.csosn, r.aliquota_icms, r.perc_reducao_bc,
                               r.aliquota_fcp, r.cst_pis, r.aliquota_pis, r.cst_cofins, r.aliquota_cofins,
                               r.cst_ibscbs, r.cclasstrib, r.codigo_beneficio,
                               COALESCE(t.perc_reducao_ibs, 0) AS perc_reducao_ibs,
                               COALESCE(t.perc_reducao_cbs, 0) AS perc_reducao_cbs
                          FROM cfg_perfil_fiscal_regra r
                          LEFT JOIN cfg_cclasstrib t ON t.codigo_cclasstrib = r.cclasstrib
                         WHERE r.id_tenant = plataforma.tenant_atual()
                           AND r.id_perfil_fiscal = ?
                           AND r.crt = ?
                           AND r.tipo_destinatario = ?::tipo_destinatario_fiscal
                           AND r.tipo_operacao = ?::tipo_operacao_fiscal
                           AND r.uf_destino IN (?, '*')
                         ORDER BY (r.uf_destino = ?) DESC
                         LIMIT 1
                        """)
                .params(item.idPerfilFiscal(), crt, tipo, operacao, ufDestino, ufDestino)
                .query((rs, n) -> new RegraFiscal(cfopDaOperacao(rs, interestadual, descricaoProduto, ufDestino),
                        rs.getString("cst_icms"),
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
    /**
     * Ajusta os pagamentos para somarem <b>exatamente</b> o total da nota.
     *
     * <p>⛔ <b>O defeito que isto conserta é de venda MISTA</b> (achado de auditoria, 2026-08-29).
     * {@code buscarItens} filtra {@code tipo_item = 'MERCADORIA'} — certo, NFC-e é documento de
     * ICMS — mas {@code buscarPagamentos} soma {@code contas_receber} da venda <b>inteira</b>. Numa
     * OS de oficina com R$ 200 de mão de obra + R$ 100 de peça pagos em dinheiro, o XML saía com
     * {@code vNF = 100,00} e {@code vPag = 300,00}.
     *
     * <p>⚠️ E o pior caso não é a SEFAZ rejeitar: é <b>autorizar</b> uma nota que declara R$ 300
     * pagos contra R$ 100 de mercadoria. Venda mista é o caso <b>normal</b> de oficina e petshop
     * (a OS nasce com serviço E peças, DS14), e com {@code cfg_emite_fiscal_apos_venda} ligado a
     * emissão é automática — ninguém olharia o XML.
     *
     * <p>O rateio é <b>proporcional</b> e o resto do arredondamento vai na última forma, mesmo
     * padrão de {@code PdvVendaService.ratear}: assim a soma bate no centavo, que é o que a regra
     * de conferência do modelo 65 exige.
     *
     * <p>⚠️ Venda sem serviço nenhuma — a esmagadora maioria — passa <b>intacta</b> pelo atalho da
     * primeira linha: os valores já são iguais, e reprocessar introduziria risco de arredondamento
     * onde não havia problema.
     */
    // Visível ao teste de propósito: é lógica pura de dinheiro, e testá-la pelo assembler
    // inteiro exigiria montar empresa, perfil fiscal e motor tributário para provar uma soma.
    static List<Pagamento> ratearParaOTotalDaNota(List<Pagamento> pagamentos, BigDecimal valorNota) {
        BigDecimal totalPago = pagamentos.stream()
                .map(Pagamento::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalPago.compareTo(valorNota) == 0 || totalPago.signum() == 0) {
            return pagamentos;
        }

        List<Pagamento> ajustados = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        for (int i = 0; i < pagamentos.size(); i++) {
            Pagamento p = pagamentos.get(i);
            BigDecimal parte = i == pagamentos.size() - 1
                    // A última absorve o resto — garante que a soma feche exatamente no vNF.
                    ? valorNota.subtract(acumulado)
                    : p.valor().multiply(valorNota).divide(totalPago, 2, RoundingMode.DOWN);
            acumulado = acumulado.add(parte);
            ajustados.add(new Pagamento(p.codigoMeioPagamento(), parte, p.bandeira(), p.cnpjCredenciadora()));
        }
        return ajustados;
    }

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

    private record ConfigEmpresa(boolean emiteNfce, boolean emiteNfe, AmbienteSefaz ambiente,
                                 int serieNfce, int serieNfe, int crt,
                                 String cnpj, String razaoSocial, String nomeFantasia,
                                 String inscricaoEstadual, String logradouro, String numero,
                                 String complemento, String bairro, int codigoMunicipioIbge,
                                 String cidade, String uf, String cep, String telefone) {
    }

    /**
     * ⚠️ {@code dataVenda} continua sendo lida e <b>deliberadamente não é usada como data de
     * emissão</b> (2026-09-01) — quem tentar isso reabre o {@code cStat 704}. Ela fica porque é a
     * pergunta que o próximo leitor vai fazer ("temos a data da venda, por que não usá-la?"), e a
     * resposta está no ponto onde a tentação aparece: a montagem do {@code PedidoDeEmissao}.
     */
    private record VendaHeader(Integer idCliente, OffsetDateTime dataVenda,
                               Integer codigoVendedor, String nomeVendedor) {
    }

    private record ItemBruto(BigDecimal qtd, BigDecimal precoVenda, BigDecimal valorDesconto,
                             BigDecimal valorAcrescimo, String sku, String gtin, String descricao,
                             String ncm, String cest, int origemMercadoria, String unidadeComercial,
                             String unidadeTributavel, int idPerfilFiscal, BigDecimal alqFederalNacional,
                             BigDecimal alqFederalImportado, BigDecimal alqEstadual, BigDecimal alqMunicipal) {
    }
}
