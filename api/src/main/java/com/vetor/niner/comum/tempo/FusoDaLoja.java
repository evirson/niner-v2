package com.vetor.niner.comum.tempo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Resolve o fuso horário da <b>loja</b> a partir da UF da empresa — e formata data/hora nele.
 *
 * <p>Existe porque data que o usuário lê tem de estar no relógio <b>dele</b>. O driver devolve
 * {@code timestamptz} como {@code OffsetDateTime} em <b>UTC</b>, então formatar direto o que veio do
 * banco imprime 3 h a mais em Curitiba — foi exatamente o defeito encontrado na mensagem de prazo
 * do cancelamento de NFC-e, que dizia "autorizada em 17/08 às 19:44" para uma nota autorizada às
 * 16:44. Como o erro é silencioso (a data existe, só está errada), a regra é: <b>nenhum
 * {@code DateTimeFormatter} do backend recebe um {@code OffsetDateTime} sem passar por aqui.</b>
 *
 * <p>Mapa UF→fuso e a decisão por trás dele: {@link FusoDaUf}.
 *
 * <p><b>Sem cache de propósito.</b> Todos os chamadores de hoje são caminhos frios (mensagem de
 * erro, observação de auditoria) e a consulta é por chave primária. Quando o fuso passar a ser
 * gravado por transação no {@code TenantContext} — que é caminho quente —, aí sim vale cache com
 * invalidação no {@code EmpresaService}.
 *
 * <p>⚠️ P8: a consulta filtra {@code id_tenant} explicitamente, como toda query de domínio.
 */
@Service
public class FusoDaLoja {

    private final JdbcClient jdbc;

    public FusoDaLoja(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Fuso da empresa. Empresa sem UF preenchida (a coluna é anulável e sem CHECK) cai no
     * {@link FusoDaUf#PADRAO} — para <b>exibição</b> isso é aceitável; o caminho fiscal não usa este
     * método, ele exige a UF e falha sem ela.
     */
    @Transactional(readOnly = true)
    public ZoneId da(long idEmpresa) {
        return FusoDaUf.deOuPadrao(jdbc
                .sql("SELECT estado FROM empresa WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?")
                .params(idEmpresa)
                .query(String.class)
                .optional()
                .orElse(null));
    }

    /**
     * Fuso da empresa em que o usuário está logado (claim {@code eid} do JWT) — é o "hoje" que ele
     * enxerga na tela. Num relatório que soma várias empresas, esta é a referência certa: o dia é o
     * da loja onde a pessoa está operando, não o de cada filial (que poderiam estar em UFs
     * diferentes e não teriam um "hoje" comum).
     */
    @Transactional(readOnly = true)
    public ZoneId daSessao(Jwt jwt) {
        Object eid = jwt.getClaim("eid");
        return eid == null ? FusoDaUf.PADRAO : da(((Number) eid).longValue());
    }

    /** "Hoje" no fuso da loja do usuário — nunca {@code LocalDate.now()}, que usa o fuso da JVM
     *  (definido só em produção, pelo {@code TZ} do compose: o bug não reproduz em dev). */
    @Transactional(readOnly = true)
    public LocalDate hoje(Jwt jwt) {
        return LocalDate.now(daSessao(jwt));
    }

    /** Formata no fuso da empresa. {@code null} vira string vazia, não "null" na tela do usuário. */
    public String formatar(OffsetDateTime instante, long idEmpresa, DateTimeFormatter formato) {
        return instante == null ? "" : formato.format(instante.atZoneSameInstant(da(idEmpresa)));
    }

    /** Formata num fuso já conhecido — evita a consulta quando a UF já está em mãos. */
    public static String formatarEm(OffsetDateTime instante, ZoneId fuso, DateTimeFormatter formato) {
        return instante == null ? "" : formato.format(instante.atZoneSameInstant(fuso));
    }
}
