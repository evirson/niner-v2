package com.vetor.niner.fiscal.configuracao;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** DTOs da configuração fiscal por empresa (docs/telas/fiscal-configuracao.md). */
public final class FiscalConfigDtos {

    private FiscalConfigDtos() {
    }

    /**
     * Corpo de gravação. Campos opcionais são <b>boxed</b> de propósito: um {@code boolean}
     * primitivo em record de request quebra com 400 quando o JSON omite a chave — o
     * {@code ObjectMapper} do Spring lança {@code MismatchedInputException} onde um mapper avulso
     * aceitaria o default (achado em 2026-08-11, ver feedback_jackson_record_primitivo_e_problemdetails).
     *
     * <p>{@code cscToken} é <b>write-only</b>: nunca volta no response. Enviar {@code null} (ou
     * omitir) <b>preserva</b> o token já gravado — apagá-lo exige {@code removerCsc = true}. Sem
     * essa distinção, um PUT que só muda a série zeraria o CSC em silêncio.
     */
    public record FiscalConfigRequest(
            @NotNull @Min(1) @Max(4) Integer crt,
            @NotNull Boolean emiteNfce,
            @NotNull Boolean emiteNfe,
            @NotNull AmbienteFiscal ambiente,
            @NotNull @Min(1) @Max(999) Integer serieNfce,
            @NotNull @Min(1) @Max(999) Integer serieNfe,
            @NotNull @Min(1) @Max(999) Integer serieContingencia,
            @Size(max = 20) String inscricaoEstadualSt,
            @Size(max = 20) String suframa,
            // ⚠️ O ID do CSC entra no MESMO QR Code e é colado do MESMO portal, na mesma tela
            // (auditoria 2026-08-29). `MontadorXmlNfce` faz `Integer.parseInt` nele: um espaço
            // junto vira `NumberFormatException` — que não é tratada — e a venda recebe **500 sem
            // mensagem** em vez do `cStat 464` legível. Só o token tinha ganhado a proteção.
            @Pattern(regexp = "|\\s*\\d{1,6}\\s*",
                    message = "O ID do CSC é numérico (ex.: 000001) — ele vem ao lado do código no portal da SEFAZ.")
            @Size(max = 60) String cscId,
            // ⚠️ Tamanho MÍNIMO, não só o máximo (auditoria 2026-08-29). É o GÊMEO do defeito que o
            // campo do CSRT ganhou em 2026-08-24 e que ficou aberto aqui: sem piso, o Token do CSC
            // aceita qualquer coisa — o número do credenciamento, um CSRT, um pedaço colado errado
            // — e o erro só aparece na PRÓXIMA NFC-e, como `cStat 464` ("Código de Hash no QR-Code
            // difere do calculado"), que não menciona CSC em lugar nenhum.
            // ⚠️ Isto NÃO teria impedido o caso real de 29/08: o valor gravado tinha 36 caracteres
            // válidos, só não era o credenciado. Piso barra a confusão grosseira, não a troca de
            // um segredo por outro do mesmo formato.
            // ⚠️ VAZIO continua valendo "manter o que está gravado" (convenção do projeto para
            // segredo) — por isso `@Pattern` com alternativa vazia, e não `@Size(min)`, que
            // barraria quem só quer mudar a série ou a inscrição estadual.
            // ⚠️ Piso 16, não 36: o produto atende os 27 entes e quem gera o CSC é cada SEFAZ — travar no
            // tamanho do PR impediria a loja de outra UF de cadastrar o código correto dela, com uma
            // mensagem afirmando um tamanho que não é o seu (o campo é `password`: ninguém veria).
            // O piso serve contra a confusão grosseira (número do credenciamento, 5 dígitos), não
            // contra a troca de um segredo por outro do mesmo formato.
            @Pattern(regexp = "|.{16,200}", flags = Pattern.Flag.DOTALL,
                    message = "O CSC costuma ter 36 caracteres — confira se você não colou o CSRT ou "
                            + "o número do credenciamento por engano. Ele é gerado no portal da "
                            + "SEFAZ, junto com o identificador (ID do CSC).")
            @Size(max = 200) String cscToken,
            Boolean removerCsc) {
    }

    /**
     * {@code configurado} distingue "empresa sem linha em {@code fiscal_config_empresa}" de
     * "empresa configurada com os defaults". O GET responde 200 nos dois casos — 404 obrigaria a
     * tela a separar "empresa inexistente" de "fiscal ainda não ligado", que é ruído sem ganho.
     */
    public record FiscalConfigResponse(
            long idEmpresa,
            String razaoSocialEmpresa,
            boolean configurado,
            int crt,
            boolean emiteNfce,
            boolean emiteNfe,
            AmbienteFiscal ambiente,
            int serieNfce,
            int serieNfe,
            int serieContingencia,
            String inscricaoEstadualSt,
            String suframa,
            String cscId,
            boolean cscConfigurado,
            String versaoTabelaIbpt,
            boolean serieNfceBloqueada,
            boolean serieNfeBloqueada,
            /**
             * {@code true} quando a instalação fixa o ambiente de emissão (hoje, produção): a tela
             * esconde a escolha "homologação × produção" em vez de oferecer algo que o servidor vai
             * sobrescrever. Vem de {@code niner.fiscal.ambiente-fixo}, não do banco — é decisão da
             * instalação, não do lojista.
             */
            boolean ambienteTravado,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /**
     * CSC decifrado, pronto para montar o QR Code da NFC-e — nunca exposto por endpoint (mesmo
     * espírito do {@code CertificadoParaAssinatura}, write-only na API); só
     * {@code EmissaoNfceService} usa.
     */
    public record CscParaEmissao(String id, String token) {
    }

    /** Uma empresa do tenant no seletor do topo da tela. */
    public record EmpresaFiscalResponse(
            long idEmpresa,
            String razaoSocial,
            boolean configurado,
            boolean emiteNfce,
            boolean emiteNfe) {
    }

    /**
     * Uma precondição do F11 que impede ligar um gate de emissão. {@code telaDeCorrecao} é a
     * chave de tela que resolve a pendência — a tela usa pra oferecer o link, e a Conformidade
     * Fiscal (docs/telas/fiscal-conformidade.md) reaproveita o mesmo vocabulário.
     */
    public record PendenciaAtivacao(String codigo, String descricao, String telaDeCorrecao) {
    }

    /** Corpo do 409 quando um gate não pode ser ligado. */
    public record AtivacaoRecusadaResponse(String detail, List<PendenciaAtivacao> pendencias) {
    }

    /**
     * O CRT que a empresa pode ter. Não é enum no banco (a coluna é {@code smallint}, porque o
     * número vai literalmente no XML), mas o domínio é fechado pelo CHECK e pela DF37: o 3, Regime
     * Normal, não existe neste produto.
     */
    public static final java.util.Set<Integer> CRT_ATENDIDOS = java.util.Set.of(1, 2, 4);

    /** Espelha o ENUM {@code ambiente_fiscal} (V035). */
    public enum AmbienteFiscal {
        HOMOLOGACAO, PRODUCAO
    }
}
