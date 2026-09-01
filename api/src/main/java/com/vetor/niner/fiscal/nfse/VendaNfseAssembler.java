package com.vetor.niner.fiscal.nfse;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lê uma venda e produz <b>uma DPS por código de serviço distinto</b>.
 *
 * <h2>Por que o retorno é uma lista, e não um documento</h2>
 *
 * <p>Porque a DPS carrega <b>um</b> {@code cServ} (leiaute 1-1, medido — ver V102). Uma venda de
 * petshop com banho e tosa ({@code 050801}) e consulta veterinária ({@code 050101}) gera
 * <b>duas</b> NFS-e. Somar tudo num código "dominante" declararia serviço errado para parte do
 * valor, com alíquota e local de incidência errados junto.
 *
 * <p>⚠️ Isto é o oposto do {@code VendaFiscalAssembler}, que produz <b>uma</b> NFC-e por venda.
 * As duas cardinalidades convivem: a mesma venda de oficina rende uma NFC-e (as peças) e N NFS-e
 * (a mão de obra, por código).
 *
 * <h2>F11 — o bloqueio é preventivo, e acontece ANTES de qualquer número ser consumido</h2>
 *
 * <p>Toda checagem daqui responde 409 com o que falta e onde resolver. É a diferença entre o
 * lojista corrigir um cadastro e o operador tomar {@code E0712} com o cliente na frente.
 */
@Service
public class VendaNfseAssembler {

    private final JdbcClient jdbc;

    public VendaNfseAssembler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Limite de {@code xDescServ} no schema. Descrição maior volta como E1235. */
    private static final int MAX_DESCRICAO = 1000;

    @Transactional(readOnly = true)
    public PlanoDeEmissao montar(long idVenda) {
        Cabecalho cab = buscarCabecalho(idVenda);
        List<LinhaServico> linhas = buscarLinhasDeServico(idVenda);

        if (linhas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta venda não tem nenhum serviço. A NFS-e é o documento da mão de obra; "
                    + "mercadoria sai em NFC-e ou NF-e.");
        }
        exigirCadastroCompleto(cab, linhas);

        // LinkedHashMap: a ordem das notas segue a ordem em que os serviços foram lançados no PDV,
        // que é a ordem que o operador vê na tela e no comprovante.
        Map<String, List<LinhaServico>> porCodigo = new LinkedHashMap<>();
        for (LinhaServico l : linhas) {
            porCodigo.computeIfAbsent(l.codigoTributacaoNacional(), k -> new ArrayList<>()).add(l);
        }

        List<DpsDaVenda> saida = new ArrayList<>();
        for (var grupo : porCodigo.entrySet()) {
            saida.add(agrupar(cab, grupo.getKey(), grupo.getValue()));
        }
        return new PlanoDeEmissao(cab, saida);
    }

    /**
     * O que a venda rende: o cabeçalho resolvido uma vez e as N notas.
     *
     * <p>Os dois juntos de propósito — buscar o cabeçalho de novo no serviço de emissão seria ler
     * a mesma venda duas vezes, e duas leituras é onde nasce divergência.
     */
    public record PlanoDeEmissao(Cabecalho cabecalho, List<DpsDaVenda> notas) {
    }

    private DpsDaVenda agrupar(Cabecalho cab, String codigo, List<LinhaServico> linhas) {
        BigDecimal bruto = BigDecimal.ZERO;
        BigDecimal desconto = BigDecimal.ZERO;
        for (LinhaServico l : linhas) {
            bruto = bruto.add(l.precoVenda().multiply(l.quantidade()));
            desconto = desconto.add(l.valorDesconto());
        }
        // ⚠️ Arredonda UMA vez, no fim (P7) — não a cada linha. Rateio de desconto em serviço com
        // quantidade fracionária é dízima, e arredondar por linha faz o total divergir do que a
        // venda cobrou.
        bruto = bruto.setScale(2, RoundingMode.HALF_UP);
        desconto = desconto.setScale(2, RoundingMode.HALF_UP);

        LinhaServico primeira = linhas.get(0);
        exigirTratamentoUniforme(codigo, primeira, linhas);
        return new DpsDaVenda(
                codigo,
                primeira.codigoTributacaoMunicipal(),
                descricaoAgregada(linhas),
                bruto,
                desconto,
                bruto.subtract(desconto),
                primeira.aliquotaIss(),
                primeira.issRetido(),
                primeira.localIncidencia(),
                linhas);
    }

    /**
     * O {@code xDescServ}: as descrições dos serviços daquele código, separadas por " · ".
     *
     * <p>⚠️ Cortar em 1000 caracteres não é detalhe: descrição maior volta como {@code E1235}
     * depois de o número já ter sido reservado. O corte é feito aqui, com reticências, para que o
     * que o lojista vê no documento auxiliar seja o que foi enviado.
     */
    private String descricaoAgregada(List<LinhaServico> linhas) {
        StringBuilder texto = new StringBuilder();
        for (LinhaServico l : linhas) {
            if (!texto.isEmpty()) {
                texto.append(" · ");
            }
            texto.append(l.descricao());
        }
        String completo = texto.toString();
        return completo.length() <= MAX_DESCRICAO ? completo
                : completo.substring(0, MAX_DESCRICAO - 3) + "...";
    }

    /**
     * As checagens do F11. Cada mensagem diz <b>o que falta e onde resolver</b> — mensagem que só
     * diz "não foi possível emitir" transforma um cadastro incompleto numa caça ao servidor.
     */
    private void exigirCadastroCompleto(Cabecalho cab, List<LinhaServico> linhas) {
        if (cab.cnpj() == null || cab.cnpj().replaceAll("\\D", "").length() != 14) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A empresa está sem CNPJ válido. Preencha em Empresa antes de emitir NFS-e.");
        }
        if (cab.codigoMunicipioIbge() == null || cab.codigoMunicipioIbge() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A empresa está sem o código de município (IBGE). Ele é preenchido "
                    + "automaticamente ao informar o CEP na tela de Empresa.");
        }
        if (!cab.emiteNfse()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A emissão de NFS-e está desligada para esta empresa. Ligue em "
                    + "Configuração da NFS-e.");
        }
        // ⛔ E0712 — para optante do Simples não existe emissão sem a alíquota efetiva. Barrar
        // aqui é o que impede o operador de descobrir isso pelo código de erro do SEFIN.
        boolean optante = cab.optaSimples() == MontadorXmlDps.SIMPLES_ME_EPP
                || cab.optaSimples() == MontadorXmlDps.SIMPLES_MEI;
        if (optante && cab.aliquotaSimplesEfetiva() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Falta a alíquota efetiva do Simples Nacional em Configuração da NFS-e. Sem "
                    + "ela o Sefin Nacional recusa a nota (E0712). O valor está no extrato do "
                    + "PGDAS-D do mês anterior.");
        }
        List<String> semCodigo = linhas.stream()
                .filter(l -> l.codigoTributacaoNacional() == null
                        || l.codigoTributacaoNacional().isBlank())
                .map(LinhaServico::descricao)
                .distinct()
                .toList();
        if (!semCodigo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sem código de serviço (LC 116) em: " + String.join(", ", semCodigo)
                    + ". Informe na aba Serviço do cadastro do produto — a lista tem busca por "
                    + "texto e sugestão pelo ramo da loja.");
        }
        // ⛔ Serviço cujo código exige bloco extra da DPS (obra, atvEvento, lsadppu, explRod) não é
        // montável pelo v1 — e a trava tem de estar AQUI (2026-09-01).
        //
        // Até esta data ela existia só no front, que recusava a ESCOLHA do código. O cadastro
        // passou a gravar qualquer código (decisão dele), e sem esta guarda o pedido seguiria para
        // a montagem: o XML sairia sem o grupo obrigatório, o Sefin recusaria com um código de
        // erro que ninguém traduz — e a rejeição viria DEPOIS de reservar o nDPS, queimando
        // numeração a cada tentativa. Barrar antes disso é o mesmo princípio do F11.
        //
        // ⚠️ Guarda de tela nunca foi proteção (P4): quem chama a API direto, ou um serviço
        // cadastrado antes desta regra, nunca passou pelo seletor.
        List<String> comBlocoExtra = linhas.stream()
                .filter(l -> l.grupoDps() != null && !l.grupoDps().isBlank())
                .map(l -> l.descricao() + " (" + l.codigoTributacaoNacional() + ", exige o bloco "
                        + l.grupoDps() + ")")
                .distinct()
                .toList();
        if (!comBlocoExtra.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O layout nacional exige informações extras que o Nainer ainda não emite em: "
                    + String.join(", ", comBlocoExtra)
                    + ". O cadastro do serviço está correto — o que falta é o bloco no XML. "
                    + "Fale com o suporte antes de tentar de novo.");
        }
    }

    private Cabecalho buscarCabecalho(long idVenda) {
        return jdbc.sql("""
                        SELECT v.id_empresa, v.data_venda, v.cancelada,
                               e.cnpj, e.inscricao_municipal, e.codigo_municipio_ibge,
                               c.nome AS nome_cliente, c.cpf_cnpj,
                               COALESCE(cfg.emite_nfse, false)    AS emite_nfse,
                               cfg.ambiente,
                               COALESCE(cfg.serie, 1)             AS serie,
                               cfg.aliquota_simples_efetiva,
                               fce.crt
                          FROM venda v
                          JOIN empresa e
                            ON e.id_tenant = v.id_tenant AND e.id_empresa = v.id_empresa
                          LEFT JOIN cliente c
                            ON c.id_tenant = v.id_tenant AND c.id_cliente = v.id_cliente
                          LEFT JOIN fiscal_config_nfse cfg
                            ON cfg.id_tenant = v.id_tenant AND cfg.id_empresa = v.id_empresa
                          LEFT JOIN fiscal_config_empresa fce
                            ON fce.id_tenant = v.id_tenant AND fce.id_empresa = v.id_empresa
                         WHERE v.id_tenant = plataforma.tenant_atual()
                           AND v.id_venda = ?
                        """)
                .param(idVenda)
                .query((rs, n) -> {
                    if (rs.getBoolean("cancelada")) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Venda cancelada não gera NFS-e.");
                    }
                    long idEmpresa = rs.getLong("id_empresa");
                    Integer municipio = rs.getInt("codigo_municipio_ibge");
                    if (rs.wasNull()) {
                        municipio = null;
                    }
                    BigDecimal aliqSn = rs.getBigDecimal("aliquota_simples_efetiva");
                    int crt = rs.getInt("crt");
                    if (rs.wasNull()) {
                        crt = 1;
                    }
                    return new Cabecalho(idEmpresa, rs.getObject("data_venda", OffsetDateTime.class),
                            rs.getString("cnpj"), rs.getString("inscricao_municipal"), municipio,
                            rs.getString("nome_cliente"), rs.getString("cpf_cnpj"),
                            rs.getBoolean("emite_nfse"),
                            "PRODUCAO".equals(rs.getString("ambiente")),
                            rs.getInt("serie"), aliqSn, opSimpNacDe(crt));
                })
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Venda não encontrada"));
    }

    /**
     * CRT do cadastro fiscal → {@code opSimpNac} da DPS. É <b>derivado</b>, nunca perguntado de
     * novo: guardar o regime em dois lugares é criar divergência.
     *
     * <p>CRT 1 (Simples) e 2 (Simples com excesso de sublimite) são ME/EPP; CRT 4 é MEI. O produto
     * não atende Lucro Real/Presumido (DF37), e o CHECK do banco já barra o CRT 3.
     */
    private static int opSimpNacDe(int crt) {
        return crt == 4 ? MontadorXmlDps.SIMPLES_MEI : MontadorXmlDps.SIMPLES_ME_EPP;
    }

    /**
     * As linhas de SERVIÇO da venda. ⚠️ Espelho exato do filtro do {@code VendaFiscalAssembler},
     * do outro lado: lá {@code tipo_item = 'MERCADORIA'}, aqui {@code = 'SERVICO'}. Os dois
     * juntos cobrem a venda inteira, sem sobreposição e sem buraco.
     */
    private List<LinhaServico> buscarLinhasDeServico(long idVenda) {
        return jdbc.sql("""
                        SELECT pmd.id_variacao, pmd.qtd_produto, pmd.preco_venda,
                               pmd.valor_desconto, p.descricao,
                               ps.codigo_tributacao_nacional, ps.codigo_tributacao_municipal,
                               ps.aliquota_iss, ps.iss_retido_padrao,
                               s.local_incidencia, s.grupo_dps
                          FROM produto_movimento_detalhe pmd
                          JOIN produto_movimento_mestre pmm
                            ON pmm.id_tenant = pmd.id_tenant AND pmm.id_movimento = pmd.id_movimento
                          JOIN produto_barra pb
                            ON pb.id_tenant = pmd.id_tenant AND pb.id_variacao = pmd.id_variacao
                          JOIN produto p
                            ON p.id_tenant = pb.id_tenant AND p.id_produto = pb.id_produto
                          LEFT JOIN produto_servico ps
                            ON ps.id_tenant = p.id_tenant AND ps.id_produto = p.id_produto
                          LEFT JOIN cfg_servico_lc116 s
                            ON s.codigo = ps.codigo_tributacao_nacional
                         WHERE pmd.id_tenant = plataforma.tenant_atual()
                           AND pmm.id_venda = ? AND pmm.tipo_movimento = 'VENDA'
                           AND pb.tipo_item = 'SERVICO'
                         ORDER BY pmd.id_movimento_detalhe
                        """)
                .param(idVenda)
                .query((rs, n) -> new LinhaServico(
                        rs.getLong("id_variacao"),
                        rs.getString("descricao"),
                        rs.getBigDecimal("qtd_produto"),
                        rs.getBigDecimal("preco_venda"),
                        rs.getBigDecimal("valor_desconto") == null ? BigDecimal.ZERO
                                : rs.getBigDecimal("valor_desconto"),
                        rs.getString("codigo_tributacao_nacional"),
                        rs.getString("codigo_tributacao_municipal"),
                        rs.getBigDecimal("aliquota_iss"),
                        rs.getBoolean("iss_retido_padrao"),
                        rs.getString("local_incidencia"),
                        rs.getString("grupo_dps")))
                .list();
    }

    /**
     * Dentro de um mesmo {@code cTribNac}, alíquota e retenção têm de ser iguais — e se não forem,
     * isto <b>recusa</b> em vez de escolher uma.
     *
     * <p>⚠️ Escrevi este método depois de pegar o defeito na releitura: o {@code agrupar} tomava os
     * dois valores da <b>primeira</b> linha e descartava a divergência <b>em silêncio</b>. É o
     * padrão que já custou caro neste repositório — agrupar registros que não levam ao mesmo
     * conselho —, e aqui o custo seria uma nota emitida com alíquota que metade dos serviços não
     * pratica, plausível o bastante para ninguém notar.
     *
     * <p>Legalmente a alíquota é do par (município, código), então divergir é <b>erro de
     * cadastro</b>, não caso legítimo. Por isso a mensagem manda corrigir o cadastro, e não
     * oferece "escolha uma".
     */
    private void exigirTratamentoUniforme(String codigo, LinhaServico primeira,
                                          List<LinhaServico> linhas) {
        for (LinhaServico l : linhas) {
            boolean aliquotaDiverge = primeira.aliquotaIss() == null
                    ? l.aliquotaIss() != null
                    : l.aliquotaIss() == null || primeira.aliquotaIss().compareTo(l.aliquotaIss()) != 0;
            if (aliquotaDiverge || primeira.issRetido() != l.issRetido()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Os serviços \"" + primeira.descricao() + "\" e \"" + l.descricao()
                        + "\" têm o mesmo código de serviço (" + codigo + ") mas alíquota de ISS "
                        + "ou retenção diferentes. Como eles saem na MESMA NFS-e, o cadastro "
                        + "precisa concordar — a alíquota é definida pelo município para o código, "
                        + "não por serviço.");
            }
        }
    }

    /** Uma NFS-e a emitir: o grupo de linhas de um código, já somado. */
    public record DpsDaVenda(
            String codigoTributacaoNacional,
            String codigoTributacaoMunicipal,
            String descricaoServico,
            BigDecimal valorServicos,
            BigDecimal valorDesconto,
            BigDecimal baseCalculo,
            BigDecimal aliquotaIss,
            boolean issRetido,
            String localIncidencia,
            List<LinhaServico> linhas) {
    }

    public record LinhaServico(
            long idVariacao, String descricao, BigDecimal quantidade, BigDecimal precoVenda,
            BigDecimal valorDesconto, String codigoTributacaoNacional,
            String codigoTributacaoMunicipal, BigDecimal aliquotaIss, boolean issRetido,
            String localIncidencia,
            /** Preenchido (obra, atvEvento, lsadppu, explRod) = a DPS exige um bloco que o v1 não
             *  monta. Vem da fonte oficial, junto do local de incidência — a emissão recusa antes
             *  de reservar número, e o cadastro do serviço continua livre para gravar o código. */
            String grupoDps) {
    }

    /** Tudo que vem da venda, da empresa e da configuração — resolvido uma vez só. */
    public record Cabecalho(
            long idEmpresa, OffsetDateTime dataVenda, String cnpj, String inscricaoMunicipal,
            Integer codigoMunicipioIbge, String nomeCliente, String cpfCnpjCliente,
            boolean emiteNfse, boolean ambienteProducao, int serie,
            BigDecimal aliquotaSimplesEfetiva, int optaSimples) {

        /** Competência = o mês da venda, no fuso da loja. */
        public LocalDate competencia(ZoneId fusoDaLoja) {
            return dataVenda.atZoneSameInstant(fusoDaLoja).toLocalDate().withDayOfMonth(1);
        }
    }
}
