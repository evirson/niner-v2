package com.vetor.niner.comum.tempo;

import java.time.ZoneId;

/**
 * Fuso do <b>plano de controle</b> — o relógio da Vetor, não o da loja.
 *
 * <p><b>Por que é uma constante, ao contrário do fuso do lojista.</b> Competência de cota, horário
 * do backup, vigência de assinatura e funil de aquisição são fatos do <b>negócio da Vetor</b>
 * (P9): o mês de cobrança é o mês da Vetor, e a agenda de backup é a agenda da Vetor. Um tenant
 * pode até ter empresas em UFs diferentes — não existe "o fuso do tenant" para desempatar. Então
 * aqui é fixo de propósito, e {@link FusoDaUf} não se aplica.
 *
 * <p>⚠️ O que <b>não</b> é aceitável é o que existia antes de 2026-08-20: comparar em <b>UTC</b>,
 * que é o fuso da sessão do Postgres. Isso fazia a competência da cota virar às 21:00 do último dia
 * do mês — e uma loja bloqueada por cota estourada <b>voltava a vender</b> às 21:05 de 31/08,
 * porque o contador zerava três horas antes da hora.
 */
public final class FusoDaPlataforma {

    /**
     * Sede da Vetor. Em SQL, o mesmo valor aparece literal em
     * {@code AT TIME ZONE 'America/Sao_Paulo'} — os dois lados têm de andar juntos.
     */
    public static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private FusoDaPlataforma() {
    }
}
