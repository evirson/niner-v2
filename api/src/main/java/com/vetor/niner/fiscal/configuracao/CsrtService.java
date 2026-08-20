package com.vetor.niner.fiscal.configuracao;

import com.vetor.niner.comum.config.NinerProperties;
import com.vetor.niner.comum.seguranca.SegredoCifrador;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolve o <b>CSRT</b> (Código de Segurança do Responsável Técnico, NT 2018.005) da UF em que a
 * nota vai ser emitida.
 *
 * <p><b>Por que por UF.</b> O CSRT é emitido pela SEFAZ de <b>cada</b> unidade da federação para o
 * CNPJ de quem desenvolve o software emissor (a MITRYUSCASH). Até 2026-08-20 o produto tinha um
 * par {@code idCSRT}/{@code CSRT} único na configuração da aplicação — o que só funcionava
 * enquanto todo lojista emitisse no Paraná. Como o Nainer é vendido para as 27 unidades da
 * federação, o código virou <b>linha</b> em {@code cfg_csrt_resptec}, igual ao endpoint do
 * autorizador (F10: "a UF é dado, não código").
 *
 * <p><b>Ambiente também faz parte da chave</b>: homologação e produção são cadastros separados no
 * portal, e usar o de homologação em produção é o tipo de erro que só aparece na primeira venda
 * real.
 *
 * <p><b>Ordem de resolução:</b>
 * <ol>
 *   <li>linha de {@code cfg_csrt_resptec} para (UF, ambiente) — a fonte de verdade, mantida pelo
 *       backoffice sem deploy, porque CSRT novo chega quando entra lojista de estado novo;</li>
 *   <li>o par do {@code application.yml}/env, <b>e só se a UF pedida for a declarada em</b>
 *       {@code niner.fiscal.resp-tec.uf} — é fallback de dev/CI e da primeira subida, não um
 *       curinga: carimbar o código do PR numa nota de SP daria {@code cStat 974} e um diagnóstico
 *       muito pior que "não configurado";</li>
 *   <li>vazio — o montador decide se isso impede a emissão (ver {@link #exigeCsrt}).</li>
 * </ol>
 *
 * <p>⚠️ O valor devolvido é <b>segredo em claro</b>: só circula entre este serviço e o montador,
 * que dele extrai o {@code hashCSRT}. Nunca vai para DTO de controller, log ou resposta de API.
 */
@Service
public class CsrtService {

    private final JdbcClient jdbc;
    private final SegredoCifrador cifrador;
    private final NinerProperties.RespTec respTec;

    public CsrtService(JdbcClient jdbc, SegredoCifrador cifrador, NinerProperties props) {
        this.jdbc = jdbc;
        this.cifrador = cifrador;
        this.respTec = props.fiscal().respTec();
    }

    /** O par que vai no {@code infRespTec}. Nunca serializado — ver o aviso da classe. */
    public record Csrt(String idCsrt, String codigo) {
    }

    /** @param ambiente {@code tpAmb} do XML: 1 produção, 2 homologação. */
    @Transactional(readOnly = true)
    public Optional<Csrt> buscar(String uf, int ambiente) {
        String ufNormalizada = normalizar(uf);
        if (ufNormalizada == null) {
            return Optional.empty();
        }
        Optional<Csrt> doBanco = jdbc.sql("""
                        SELECT id_csrt, csrt_cifrado FROM cfg_csrt_resptec
                        WHERE uf = ? AND ambiente = ?
                        """)
                .params(ufNormalizada, ambiente)
                .query((rs, n) -> new Csrt(rs.getString("id_csrt").trim(),
                        cifrador.decifrar(rs.getString("csrt_cifrado"))))
                .optional();
        if (doBanco.isPresent()) {
            return doBanco;
        }
        // Fallback do env — restrito à UF que o declara. Ver a ordem de resolução no javadoc.
        if (vazio(respTec.csrt()) || vazio(respTec.idCsrt())
                || !ufNormalizada.equals(normalizar(respTec.uf()))) {
            return Optional.empty();
        }
        return Optional.of(new Csrt(respTec.idCsrt().trim(), respTec.csrt().trim()));
    }

    /**
     * A UF exige o par {@code idCSRT}/{@code hashCSRT} neste modelo? A NT 2018.005 deixa isso "a
     * critério da UF", e o próprio PR prova que varia dentro do mesmo estado: cobra na NF-e 55
     * ({@code cStat 975} sem o par) e não cobra na NFC-e 65, que rodou meses sem. Por isso é
     * coluna de {@code cfg_uf_autorizador}, não {@code if}.
     *
     * <p>UF sem linha cadastrada devolve {@code false} — mas nesse caso a emissão já parou antes,
     * em {@code SefazAutorizadorService}, que é quem sabe dizer "esta UF não está cadastrada".
     */
    @Transactional(readOnly = true)
    public boolean exigeCsrt(String uf, int modelo, int ambiente) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT exige_csrt FROM cfg_uf_autorizador
                        WHERE uf = ? AND modelo = ? AND ambiente = ?
                        """)
                .params(normalizar(uf), modelo, ambiente)
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }

    /**
     * Mensagem única de "CSRT não configurado" — F11: falhar antes de assinar, dizendo a UF, o
     * modelo e onde resolver. A SEFAZ responde a isso com {@code cStat 975}, que não diz nada
     * disso; e se o código estiver cadastrado para outro CNPJ, com {@code 974}.
     */
    public static String mensagemFaltando(String uf, int modelo) {
        return ("O modelo %d exige o CSRT (Código de Segurança do Responsável Técnico) da UF %s, "
                + "que não está cadastrado. O código é obtido pelo responsável técnico no portal "
                + "da SEFAZ dessa UF e cadastrado no backoffice, em Configuração › CSRT por UF.")
                .formatted(modelo, uf == null ? "?" : uf.toUpperCase());
    }

    private static String normalizar(String uf) {
        return uf == null || uf.isBlank() ? null : uf.trim().toUpperCase();
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }
}
