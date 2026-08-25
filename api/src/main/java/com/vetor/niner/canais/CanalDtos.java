package com.vetor.niner.canais;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs da tela de Canais de Venda (R7 — saúde da integração). */
public final class CanalDtos {

    private CanalDtos() {
    }

    /**
     * Um canal conectado (ou já cadastrado e ainda não conectado).
     *
     * <p>⛔ <b>Nenhum campo aqui carrega credencial.</b> {@code canal.credenciais} é segredo
     * cifrado (ADR-005) e <b>nunca volta pela API</b> — a tela mostra {@code contaExterna} (o
     * apelido/id do vendedor no canal, que é público) e o booleano de conectado. Mesma regra do
     * CSRT e do certificado digital.
     */
    public record CanalResponse(
            long idCanal, String tipo, String tipoRotulo, String nome, String status,
            String contaExterna, BigDecimal percPreco, int anunciosVinculados,
            OffsetDateTime criadoEm, OffsetDateTime atualizadoEm) {
    }

    /**
     * Edição do que o lojista controla. Não inclui credencial: conectar/desconectar tem endpoint
     * próprio, porque são operações, não edição de campo.
     */
    public record CanalRequest(
            @NotBlank @Size(max = 60) String nome,
            /**
             * ⚠️ Aceita <b>negativo</b>: decisão do dono do produto em 2026-08-25, o preço do
             * marketplace pode ser menor que o da loja física. Os limites espelham o CHECK da
             * V064 — validar aqui também evita que um erro de digitação vire 500 vindo do banco.
             */
            @DecimalMin(value = "-99.99") @DecimalMax(value = "1000.00") BigDecimal percPreco) {
    }

    /**
     * Painel de saúde (R7). O que o lojista precisa saber sem abrir o painel do marketplace:
     * quantas sincronizações estão presas, e por quê.
     */
    public record SaudeCanaisResponse(
            int pendentes, int comErro, int deadLetter, List<EventoComFalha> falhas) {
    }

    /**
     * Uma sincronização que não passou.
     *
     * <p>⚠️ {@code erro} vem em texto direto do canal, sem tradução. É feio, e é de propósito: a
     * mensagem do Mercado Livre ("item.attributes.invalid") é o que o suporte precisa para
     * pesquisar. Traduzir para "houve um erro" apagaria a única pista.
     */
    public record EventoComFalha(
            long id, String tipo, String agregadoId, String status, int tentativas,
            String erro, OffsetDateTime proximoRetry, OffsetDateTime criadoEm) {
    }
}
