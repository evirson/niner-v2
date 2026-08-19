package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.motor.MotorTributarioDtos.ItemTributado;
import com.vetor.niner.fiscal.motor.MotorTributarioDtos.TotaisTributarios;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Contrato de entrada do montador de XML (docs/MODULOFISCAL.md §14, bloco B5). Tudo imutável e
 * <b>sem I/O</b> — a leitura de empresa/cliente/venda acontece antes, e o montador só transforma
 * dado em XML. Mesma filosofia do motor tributário: testável por tabela, sem Testcontainers.
 *
 * <p>A parte tributária já vem calculada pelo {@code fiscal.motor} ({@link ItemTributado} e
 * {@link TotaisTributarios}) — o montador <b>nunca recalcula imposto</b>, só escolhe em qual
 * grupo do XML cada valor entra. É essa separação que faz o motor poder ser testado sem XML e o
 * XML poder ser testado sem SEFAZ.
 */
public final class MontagemNfceDtos {

    private MontagemNfceDtos() {
    }

    /** Ambiente da SEFAZ — 1 produção, 2 homologação (`tpAmb`). */
    public enum AmbienteSefaz {
        PRODUCAO(1), HOMOLOGACAO(2);

        private final int codigo;

        AmbienteSefaz(int codigo) {
            this.codigo = codigo;
        }

        public int codigo() {
            return codigo;
        }
    }

    /**
     * Tudo que o grupo {@code emit} do XML precisa. Vem de {@code empresa} +
     * {@code fiscal_config_empresa}; a tela de Conformidade Fiscal (B3) é quem garante que nada
     * disso está vazio antes de a emissão ser ligada.
     */
    public record Emitente(
            String cnpj,
            String razaoSocial,
            String nomeFantasia,
            String inscricaoEstadual,
            int crt,
            String logradouro,
            String numero,
            String complemento,
            String bairro,
            int codigoMunicipioIbge,
            String municipio,
            String uf,
            String cep,
            String telefone) {
    }

    /**
     * Destinatário — <b>opcional na NFC-e</b>: venda anônima no balcão omite o grupo {@code dest}
     * inteiro, que é o caso mais comum do varejo (§9.6). Quando informado, o CPF/CNPJ é o mínimo.
     *
     * <p>{@code indicadorIe}: 1 contribuinte · 2 isento · 9 não contribuinte. O v1 <b>recusa</b>
     * emitir NFC-e para contribuinte (indicador 1) — NFC-e não serve para revenda (DF13) — mas
     * essa recusa é do serviço de emissão, não deste montador.
     */
    public record Destinatario(
            String cpfCnpj,
            String nome,
            int indicadorIe,
            Integer codigoMunicipioIbge,
            String municipio,
            String uf) {
    }

    /**
     * O item como o XML o descreve — a parte <b>não tributária</b>, que o motor não conhece.
     * {@code nItem} amarra este item ao {@link ItemTributado} correspondente.
     *
     * <p>{@code gtin} nulo/vazio vira {@code SEM GTIN} no XML: o SKU interno do Niner
     * ({@code produto_barra.sku}, EAN-13 com prefixo 9) <b>nunca</b> pode ir no {@code cEAN} —
     * ele não é registrado na GS1 e a SEFAZ valida contra a base oficial.
     */
    public record ItemNota(
            int nItem,
            String codigoProduto,
            String gtin,
            String descricao,
            String ncm,
            String cest,
            String unidadeComercial,
            BigDecimal quantidade,
            BigDecimal valorUnitario,
            String unidadeTributavel,
            BigDecimal quantidadeTributavel,
            BigDecimal valorUnitarioTributavel,
            /** {@code produto.origem_mercadoria} (0-8, TOrig do XSD) — vai no {@code orig} do grupo
             *  de ICMS. Default 0 (Nacional): não há tela hoje pra marcar Importado. */
            int origemMercadoria) {
    }

    /** Uma forma de pagamento do grupo {@code pag}. {@code tPag}: 01 dinheiro · 03 crédito ·
     *  04 débito · 17 PIX · 99 outros. */
    public record Pagamento(String codigoMeioPagamento, BigDecimal valor, String bandeira, String cnpjCredenciadora) {
    }

    /** Grupo {@code infRespTec} — o responsável técnico pelo software emissor (a Vetor). */
    public record ResponsavelTecnico(String cnpj, String contato, String email, String telefone) {
    }

    /**
     * Endereços de consulta da UF, para o {@code infNFeSupl} (QR Code e link de consulta).
     * ⚠️ São <b>dados da UF</b>, não constantes de código (F10) — no PR o host de consulta não é
     * o mesmo do webservice, achado que custou um {@code cStat 878} no B0.
     */
    public record UrlsConsultaUf(String urlQrCode, String urlConsultaChave) {
    }

    /**
     * CSC (Código de Segurança do Contribuinte) já decifrado — necessário só para o QR Code
     * <b>online</b> (NT 2015.002 v2: {@code hashQRCode = SHA-1(chave+token)}, formato
     * {@code chave|2|tpAmb|idCSC|hashQRCode}). A contingência (offline) não usa: ali quem garante
     * a autenticidade é a assinatura RSA do certificado, não o CSC — ver {@code qrCodeOffline}.
     */
    public record CscEmpresa(String id, String token) {
    }

    /**
     * A nota inteira, pronta para virar XML. {@code numero}/{@code serie}/{@code codigoNumerico}
     * vêm da reserva de numeração (F4); {@code itensTributados} e {@code totais} vêm do motor.
     */
    public record NotaParaMontar(
            AmbienteSefaz ambiente,
            int serie,
            long numero,
            int codigoNumerico,
            OffsetDateTime emissao,
            String naturezaOperacao,
            int tipoEmissao,
            Emitente emitente,
            Destinatario destinatario,
            List<ItemNota> itens,
            List<ItemTributado> itensTributados,
            TotaisTributarios totais,
            List<Pagamento> pagamentos,
            BigDecimal troco,
            String informacoesComplementares,
            ResponsavelTecnico responsavelTecnico,
            UrlsConsultaUf urls,
            CscEmpresa csc,
            String versaoAplicativo) {
    }

    /** Resultado da montagem: o XML e a chave que ele carrega (necessária para assinar no B6). */
    public record XmlMontado(String chaveAcesso, String xml) {
    }

    /**
     * Falha de montagem — sempre <b>explícita</b> (F11), nunca um XML torto que a SEFAZ rejeita
     * no caixa. Vale o mesmo princípio do motor: erro aqui é uma mensagem na tela; erro na SEFAZ
     * é a venda parada com o cliente na frente.
     */
    public static class MontagemInvalidaException extends RuntimeException {
        public MontagemInvalidaException(String mensagem) {
            super(mensagem);
        }
    }
}
