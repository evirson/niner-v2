package com.vetor.niner.fiscal.configuracao;

import com.vetor.niner.comum.seguranca.SegredoCifrador;
import com.vetor.niner.fiscal.documento.ChaveAcesso;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Manutenção do CSRT por UF pelo backoffice — a contraparte de escrita do {@link CsrtService}.
 *
 * <p>Segue a mesma fronteira de segredo já usada na configuração da plataforma: <b>o código entra
 * e não sai</b>. A listagem devolve a UF, o {@code idCSRT} (que não é segredo — vai no XML em
 * claro), se há código gravado e quando foi trocado. Nunca o código.
 */
@Service
public class CsrtAdminService {

    private final JdbcClient jdbc;
    private final SegredoCifrador cifrador;

    public CsrtAdminService(JdbcClient jdbc, SegredoCifrador cifrador) {
        this.jdbc = jdbc;
        this.cifrador = cifrador;
    }

    /** Uma linha por UF × ambiente já cadastrada. O código em si nunca aparece aqui. */
    public record CsrtUfResponse(String uf, int ambiente, String idCsrt, boolean definido,
                                 String observacao, OffsetDateTime atualizadoEm) {
    }

    /**
     * @param csrt o código obtido no portal da SEFAZ. Obrigatório na criação; em branco numa UF
     *             que já tem código significa <b>manter</b> o que está gravado (mesma convenção
     *             da senha de SMTP), o que permite corrigir só a observação ou o {@code idCSRT}.
     */
    public record SalvarCsrtRequest(
            // ⚠️ @NotBlank além do @Pattern: por especificação do Bean Validation, @Pattern PASSA
            // quando o valor é null — e a coluna é NOT NULL, então o INSERT estourava e o handler
            // global respondia 409 "Registro em uso por outro cadastro", que não tem nada a ver.
            @NotBlank(message = "Informe o identificador do CSRT (2 dígitos).")
            @Pattern(regexp = "\\d{2}", message = "O identificador do CSRT tem exatamente 2 dígitos.")
            String idCsrt,
            @Size(max = 200) String csrt,
            @Size(max = 500) String observacao) {
    }

    @Transactional(readOnly = true)
    public List<CsrtUfResponse> listar(Jwt jwt) {
        exigirStaff(jwt);
        return jdbc.sql("""
                        SELECT uf, ambiente, id_csrt, observacao, atualizado_em
                          FROM cfg_csrt_resptec ORDER BY uf, ambiente
                        """)
                .query((rs, n) -> new CsrtUfResponse(
                        rs.getString("uf"), rs.getInt("ambiente"), rs.getString("id_csrt").trim(),
                        true, rs.getString("observacao"),
                        rs.getObject("atualizado_em", OffsetDateTime.class)))
                .list();
    }

    @Transactional
    public CsrtUfResponse salvar(Jwt jwt, String uf, int ambiente, SalvarCsrtRequest req) {
        exigirSuperAdmin(jwt);
        String ufNormalizada = validarUf(uf);
        validarAmbiente(ambiente);

        boolean jaExiste = jdbc.sql("SELECT 1 FROM cfg_csrt_resptec WHERE uf = ? AND ambiente = ?")
                .params(ufNormalizada, ambiente).query(Integer.class).optional().isPresent();
        boolean informouCodigo = req.csrt() != null && !req.csrt().isBlank();
        if (!jaExiste && !informouCodigo) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Informe o CSRT obtido no portal da SEFAZ de " + ufNormalizada + ".");
        }

        // Em branco = manter o código atual. Sem isso, salvar só a observação apagaria o segredo.
        String cifrado = informouCodigo ? cifrador.cifrar(req.csrt().trim()) : null;
        jdbc.sql("""
                        INSERT INTO cfg_csrt_resptec (uf, ambiente, id_csrt, csrt_cifrado, observacao)
                        VALUES (?, ?, ?, COALESCE(CAST(? AS text), ''), ?)
                        ON CONFLICT (uf, ambiente) DO UPDATE SET
                            id_csrt      = EXCLUDED.id_csrt,
                            csrt_cifrado = COALESCE(CAST(? AS text), cfg_csrt_resptec.csrt_cifrado),
                            observacao   = EXCLUDED.observacao,
                            atualizado_em = now()
                        """)
                .params(ufNormalizada, ambiente, req.idCsrt(), cifrado,
                        req.observacao() == null || req.observacao().isBlank() ? null : req.observacao().trim(),
                        cifrado)
                .update();

        return listar(jwt).stream()
                .filter(c -> c.uf().equals(ufNormalizada) && c.ambiente() == ambiente)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "CSRT não encontrado após gravar."));
    }

    /**
     * Remove o código de uma UF. ⚠️ Não é operação cosmética: sem CSRT, a UF que o exige para de
     * autorizar — por isso só SUPER_ADMIN, e a tela confirma antes.
     */
    @Transactional
    public void excluir(Jwt jwt, String uf, int ambiente) {
        exigirSuperAdmin(jwt);
        int apagadas = jdbc.sql("DELETE FROM cfg_csrt_resptec WHERE uf = ? AND ambiente = ?")
                .params(validarUf(uf), ambiente).update();
        if (apagadas == 0) {
            throw new ResponseStatusException(NOT_FOUND,
                    "Não há CSRT cadastrado para " + uf.toUpperCase() + " neste ambiente.");
        }
    }

    /**
     * A lista de UFs válidas é a do {@link ChaveAcesso}, que é quem monta a chave de acesso — de
     * propósito: manter uma segunda cópia das 27 siglas aqui só criaria a chance de uma aceitar o
     * que a outra recusa.
     */
    private static String validarUf(String uf) {
        String normalizada = uf == null ? "" : uf.trim().toUpperCase();
        try {
            ChaveAcesso.codigoUfDe(normalizada);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, "UF inválida: " + uf);
        }
        return normalizada;
    }

    private static void validarAmbiente(int ambiente) {
        if (ambiente != 1 && ambiente != 2) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Ambiente inválido: use 1 (produção) ou 2 (homologação).");
        }
    }

    private static void exigirStaff(Jwt jwt) {
        if (jwt.getClaimAsString("papel") == null) {
            throw new ResponseStatusException(FORBIDDEN, "Somente o staff da plataforma acessa o CSRT.");
        }
    }

    private static void exigirSuperAdmin(Jwt jwt) {
        if (!"SUPER_ADMIN".equals(jwt.getClaimAsString("papel"))) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas SUPER_ADMIN altera o CSRT.");
        }
    }
}
