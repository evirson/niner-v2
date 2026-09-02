package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.AmbienteSefaz;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.Emitente;
import com.vetor.niner.fiscal.documento.MontagemNfceDtos.ResponsavelTecnico;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contrato de entrada do montador da <b>NF-e de devolução</b> (modelo 55, entrada) — §10.2 do
 * estudo fiscal, bloco B9. Mesma filosofia do {@link MontagemNfceDtos}: imutável, sem I/O, o
 * montador só transforma dado em XML.
 *
 * <h2>A diferença que define este contrato: nada aqui é calculado</h2>
 *
 * <p>A NF-e de devolução <b>espelha</b> a NFC-e original — não passa pelo motor tributário. Cada
 * {@link ItemDevolucao} carrega os valores tributários <b>já gravados</b> em
 * {@code documento_fiscal_item} da nota de saída, lidos como estão. É o que a legislação pede
 * ("mesmos valores e bases, destacando os tributos para estorno/crédito exatamente como na nota de
 * venda") e também o que evita a classe de erro mais cara possível: recalcular hoje, com o cadastro
 * de hoje, uma nota que documenta uma venda de ontem — se a alíquota, o CSOSN ou o preço do
 * produto mudaram no meio, a devolução sairia divergente da venda que ela referencia.
 *
 * <h2>Ajuste SINIEF 8/2026 — a referência é dupla</h2>
 *
 * <p>Confirmado com o contador do dono do produto (2026-08-19) que o Ajuste vale para este caso:
 * <ul>
 *   <li><b>Cabeçalho</b> ({@code ide/NFref/refNFe}): a chave da NFC-e original, sempre.</li>
 *   <li><b>Item a item</b> ({@code det/DFeReferenciado}): {@code chaveAcesso} + {@code nItem} do
 *       item correspondente na nota original — é o que o Ajuste exige para devolução
 *       <b>parcial</b>, o caso mais comum do balcão (cliente devolve parte da compra). O grupo
 *       existe no XSD 4.00 que o projeto versiona (conferido antes de desenhar isto).</li>
 *   <li><b>{@code dest}</b>: os dados do destinatário <b>da nota de saída original</b> — mudança
 *       central do Ajuste, que acabou com a distinção entre destinatário contribuinte e não
 *       contribuinte. Ver {@link DestinatarioDevolucao} para o caso "consumidor não identificado",
 *       que é o mais frequente numa NFC-e de balcão.</li>
 * </ul>
 */
public final class MontagemDevolucaoDtos {

    private MontagemDevolucaoDtos() {
    }

    /**
     * Destinatário da NF-e de devolução — pelo Ajuste SINIEF 8/2026, os dados do destinatário que
     * constava na NF-e de saída original.
     *
     * <p>⚠️ <b>Consumidor não identificado (DF20)</b>: a NFC-e de balcão normalmente sai sem CPF —
     * não há destinatário para copiar. A saída documentada nesse caso é a <b>própria loja</b> como
     * destinatária (a nota de entrada volta a mercadoria para o estoque de quem a vendeu), com a
     * justificativa nas informações complementares. Quem decide isso é o assembler, lendo o
     * {@code dest} do XML da nota original; este record só carrega o resultado, já resolvido.
     *
     * <p>{@code indicadorIe}: 1 contribuinte · 2 isento · 9 não contribuinte. Endereço é
     * obrigatório na NF-e 55 (diferente da NFC-e, onde o grupo inteiro é opcional).
     */
    public record DestinatarioDevolucao(
            String cpfCnpj,
            String nome,
            int indicadorIe,
            String inscricaoEstadual,
            String logradouro,
            String numero,
            String complemento,
            String bairro,
            int codigoMunicipioIbge,
            String municipio,
            String uf,
            String cep,
            String telefone,
            /** {@code true} quando o destinatário é a própria loja porque a NFC-e original saiu sem
             *  consumidor identificado — o montador usa isso só para a frase automática das
             *  informações complementares (rastreabilidade), nunca para mudar a estrutura do XML. */
            boolean consumidorNaoIdentificado) {
    }

    /**
     * Um item devolvido, com a tributação <b>copiada</b> da nota original (ver javadoc da classe).
     *
     * <p>{@code nItemOriginal} é o {@code nItem} daquele produto na NFC-e de saída — vai no
     * {@code DFeReferenciado} do item, e é o que torna a devolução <b>parcial</b> rastreável item a
     * item (Ajuste SINIEF 8/2026). {@code quantidade} pode ser menor que a vendida: o cliente pode
     * devolver 1 de 3 unidades, e aí os valores vêm rateados pelo assembler, nunca recalculados
     * aqui.
     */
    public record ItemDevolucao(
            int nItem,
            int nItemOriginal,
            String codigoProduto,
            String gtin,
            String descricao,
            String ncm,
            String cest,
            String cfop,
            String unidadeComercial,
            BigDecimal quantidade,
            BigDecimal valorUnitario,
            BigDecimal valorProduto,
            /**
             * {@code vDesc} do item, <b>espelhado</b> da nota original e rateado pela quantidade
             * devolvida (2026-09-02, pendência 60).
             *
             * <p>⛔ <b>Sem ele a nota de entrada declarava o valor BRUTO:</b> devolver uma venda de
             * R$ 100 com R$ 10 de desconto emitia devolução de R$ 100 referenciando uma NFC-e cujo
             * {@code vNF} é R$ 90 — a nota afirmando um valor que a operação nunca teve, e todo o
             * resto do sistema (vale, DRE, Lucratividade, Comissões) já no líquido desde 29/08.
             *
             * <p>{@code null} ou zero não gera a tag: o XSD a declara opcional, e escrever
             * {@code 0.00} em toda nota só polui o XML.
             */
            BigDecimal valorDesconto,
            String unidadeTributavel,
            BigDecimal quantidadeTributavel,
            BigDecimal valorUnitarioTributavel,
            int origemMercadoria,
            // ---- ICMS (espelhado, nunca recalculado)
            String cstIcms,
            String csosn,
            BigDecimal baseCalculoIcms,
            BigDecimal percentualReducaoBc,
            BigDecimal aliquotaIcms,
            BigDecimal valorIcms,
            BigDecimal baseCalculoSt,
            BigDecimal valorIcmsSt,
            BigDecimal baseStRetido,
            BigDecimal icmsStRetido,
            BigDecimal percentualCreditoSn,
            BigDecimal valorCreditoSn,
            // ---- PIS / COFINS
            String cstPis,
            BigDecimal baseCalculoPis,
            BigDecimal aliquotaPis,
            BigDecimal valorPis,
            String cstCofins,
            BigDecimal baseCalculoCofins,
            BigDecimal aliquotaCofins,
            BigDecimal valorCofins,
            // ---- IBS / CBS (reforma, LC 214/2025)
            String cstIbsCbs,
            String cclasstrib,
            BigDecimal baseIbsCbs,
            BigDecimal aliquotaIbsUf,
            BigDecimal valorIbsUf,
            BigDecimal aliquotaIbsMun,
            BigDecimal valorIbsMun,
            BigDecimal aliquotaCbs,
            BigDecimal valorCbs,
            BigDecimal valorTotalTributos) {
    }

    /** Totais da devolução — somados dos itens pelo assembler, nunca recalculados no montador. */
    public record TotaisDevolucao(
            BigDecimal baseCalculoIcms,
            BigDecimal valorIcms,
            BigDecimal baseCalculoSt,
            BigDecimal valorIcmsSt,
            BigDecimal valorProdutos,
            BigDecimal valorDesconto,
            BigDecimal valorPis,
            BigDecimal valorCofins,
            BigDecimal valorTotal,
            BigDecimal baseIbsCbs,
            BigDecimal valorIbsUf,
            BigDecimal valorIbsMun,
            BigDecimal valorCbs,
            BigDecimal valorTotalTributos) {
    }

    /**
     * A NF-e de devolução inteira, pronta para virar XML.
     *
     * <p>{@code chaveNotaOriginal} é a chave da NFC-e sendo devolvida — vai no {@code NFref} do
     * cabeçalho e no {@code DFeReferenciado} de cada item (Ajuste SINIEF 8/2026).
     */
    /**
     * @param tipoNf {@code tpNF} — <b>0 entrada</b> (devolução de VENDA: a mercadoria volta para a
     *               loja) ou <b>1 saída</b> (devolução de COMPRA: a mercadoria volta ao fornecedor).
     *               Era fixo em 0 até 2026-08-20, quando a devolução ao fornecedor entrou e passou
     *               a existir o outro sentido.
     * @param idDestino {@code idDest} — 1 interna, 2 interestadual. Na devolução ao consumidor é
     *               sempre 1 (balcão); na de compra depende da UF do fornecedor.
     */
    public record DevolucaoParaMontar(
            AmbienteSefaz ambiente,
            int serie,
            long numero,
            int codigoNumerico,
            OffsetDateTime emissao,
            String naturezaOperacao,
            String chaveNotaOriginal,
            int tipoNf,
            int idDestino,
            Emitente emitente,
            DestinatarioDevolucao destinatario,
            List<ItemDevolucao> itens,
            TotaisDevolucao totais,
            String informacoesComplementares,
            ResponsavelTecnico responsavelTecnico,
            String versaoAplicativo) {
    }
}
