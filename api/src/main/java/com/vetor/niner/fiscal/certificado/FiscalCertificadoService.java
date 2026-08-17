package com.vetor.niner.fiscal.certificado;

import com.vetor.niner.cadastros.cliente.Documentos;
import com.vetor.niner.comum.seguranca.SegredoCifrador;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoDtos.FiscalCertificadoResponse;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoDtos.FiscalCertificadoUsoResponse;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoDtos.SituacaoCertificado;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Certificado Digital A1 (docs/telas/fiscal-certificado.md) — o segredo de <b>terceiro</b> mais
 * sensível que o produto guarda (F7). <b>Write-only de verdade</b>: nenhum endpoint devolve o
 * arquivo nem a senha, em campo nenhum, nem para ADMIN. Só ADMIN acessa a tela.
 *
 * <p><b>Onde o certificado mora (DF21, revisada em 2026-08-17):</b> no <b>banco do cliente</b>,
 * não em bucket — decisão do dono do produto. O bucket privado ficou só para os XML autorizados.
 * Ganha-se backup/restore junto com o resto do tenant e o isolamento por RLS (P8) de graça, sem
 * depender de política de bucket bem configurada.
 *
 * <p>⚠️ <b>O arquivo é gravado cifrado, não só a senha.</b> O PKCS12 já é um container protegido,
 * mas por uma senha escolhida pelo lojista ou pela AC — curta e sujeita a força bruta
 * <b>offline</b>, sem limite de tentativas, por quem tiver o arquivo. Um dump do banco entregaria
 * exatamente isso. Com o arquivo cifrado pela chave mestra (que vive fora do banco), o dump
 * sozinho não abre nem o {@code .pfx} nem a senha.
 *
 * <p>Ordem do upload — nada é gravado antes das 5 validações passarem (abrir como PKCS12 com a
 * senha, ter chave privada, estar dentro da validade, CNPJ do titular igual ao da empresa,
 * impressão digital ainda não cadastrada e ativa):
 *
 * <ol>
 *   <li>Parseia o {@code .pfx} em memória — nunca grava antes de confirmar que abre.</li>
 *   <li>Extrai metadados do próprio certificado (CNPJ, razão social, validade, impressão
 *       digital) — <b>nunca digitados</b> pelo lojista, que mentiriam se divergissem.</li>
 *   <li>Confere as 5 precondições.</li>
 *   <li>Só então: cifra arquivo e senha (AES-256-GCM, {@link SegredoCifrador}), desativa o
 *       certificado anterior e insere o novo, na mesma transação.</li>
 * </ol>
 */
@Service
public class FiscalCertificadoService {

    /** CN de e-CNPJ ICP-Brasil: {@code RAZAO SOCIAL:14DIGITOS}. Extrai os 14 dígitos finais. */
    private static final Pattern CNPJ_NO_CN = Pattern.compile("(\\d{14})\\s*$");

    private final JdbcClient jdbc;
    private final SegredoCifrador cifrador;

    public FiscalCertificadoService(JdbcClient jdbc, SegredoCifrador cifrador) {
        this.jdbc = jdbc;
        this.cifrador = cifrador;
    }

    @Transactional(readOnly = true)
    public List<FiscalCertificadoResponse> listar(Jwt jwt, long idEmpresa) {
        exigirAdmin(jwt);
        return jdbc.sql("""
                        SELECT id_certificado, id_empresa, cnpj_titular, razao_social_titular,
                               valido_de, valido_ate, impressao_digital, ativo, criado_em
                        FROM fiscal_certificado
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        ORDER BY criado_em DESC
                        """)
                .param(idEmpresa)
                .query(FiscalCertificadoService::mapear)
                .list();
    }

    @Transactional(readOnly = true)
    public List<FiscalCertificadoUsoResponse> listarUsos(Jwt jwt, long idCertificado) {
        exigirAdmin(jwt);
        boolean existe = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM fiscal_certificado
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_certificado = ?)
                        """)
                .param(idCertificado).query(Boolean.class).single());
        if (!existe) {
            throw new ResponseStatusException(NOT_FOUND, "Certificado não encontrado.");
        }
        return jdbc.sql("""
                        SELECT id_uso, finalidade, id_documento_fiscal, id_usuario, ocorrido_em
                        FROM fiscal_certificado_uso
                        WHERE id_tenant = plataforma.tenant_atual() AND id_certificado = ?
                        ORDER BY ocorrido_em DESC
                        """)
                .param(idCertificado)
                .query((rs, n) -> new FiscalCertificadoUsoResponse(
                        rs.getLong("id_uso"), rs.getString("finalidade"),
                        (Long) rs.getObject("id_documento_fiscal"), (Long) rs.getObject("id_usuario"),
                        rs.getObject("ocorrido_em", OffsetDateTime.class)))
                .list();
    }

    @Transactional
    public FiscalCertificadoResponse enviar(Jwt jwt, long idEmpresa, MultipartFile arquivo, String senha) {
        exigirAdmin(jwt);
        if (arquivo == null || arquivo.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Envie o arquivo .pfx do certificado.");
        }
        if (senha == null || senha.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Informe a senha do certificado.");
        }

        byte[] conteudo;
        try {
            conteudo = arquivo.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Não foi possível ler o arquivo enviado.", e);
        }

        CertificadoExtraido extraido = abrirEExtrair(conteudo, senha);

        // Validação 3 — dentro da validade.
        if (extraido.validoAte().isBefore(Instant.now())) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Certificado vencido em %s.".formatted(
                            extraido.validoAte().atOffset(ZoneOffset.UTC).toLocalDate()));
        }

        String cnpjEmpresa = jdbc.sql("""
                        SELECT cnpj FROM empresa
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                        """)
                .param(idEmpresa).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Empresa não encontrada."));

        String cnpjTitularNormalizado = Documentos.somenteAlfanumerico(extraido.cnpjTitular());
        String cnpjEmpresaNormalizado = Documentos.somenteAlfanumerico(cnpjEmpresa);
        // Validação 4 — CNPJ alfanumérico (IN RFB 2.229/2024): compara sempre normalizado,
        // nunca um limpador de dígitos, senão CNPJ com letra compara errado.
        if (cnpjTitularNormalizado == null || !cnpjTitularNormalizado.equals(cnpjEmpresaNormalizado)) {
            throw new ResponseStatusException(CONFLICT,
                    "Certificado é de outro CNPJ (%s); a empresa é %s."
                            .formatted(mascarar(extraido.cnpjTitular()), mascarar(cnpjEmpresa)));
        }

        // Validação 5 — impressão digital ainda não cadastrada e ativa para esta empresa.
        boolean jaExiste = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM fiscal_certificado
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ?
                                         AND impressao_digital = ? AND ativo)
                        """)
                .params(idEmpresa, extraido.impressaoDigital()).query(Boolean.class).single());
        if (jaExiste) {
            throw new ResponseStatusException(CONFLICT, "Este certificado já está cadastrado.");
        }

        // As 5 validações passaram — só agora grava. Arquivo E senha cifrados (ver javadoc da
        // classe): o dump do banco sozinho não abre nenhum dos dois.
        byte[] arquivoCifrado = cifrador.cifrarBytes(conteudo);
        String senhaCifrada = cifrador.cifrar(senha);
        Long idUsuario = idUsuarioDoJwt(jwt);

        jdbc.sql("""
                        UPDATE fiscal_certificado SET ativo = false
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND ativo
                        """)
                .param(idEmpresa).update();

        long idCertificado = jdbc.sql("""
                        INSERT INTO fiscal_certificado (
                            id_tenant, id_empresa, arquivo_cifrado, senha_cifrada,
                            cnpj_titular, razao_social_titular, valido_de, valido_ate,
                            impressao_digital, ativo, id_usuario)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?, ?, ?, ?, ?, ?, true, ?)
                        RETURNING id_certificado
                        """)
                .params(idEmpresa, arquivoCifrado, senhaCifrada, cnpjTitularNormalizado,
                        extraido.razaoSocialTitular(), OffsetDateTime.ofInstant(extraido.validoDe(), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(extraido.validoAte(), ZoneOffset.UTC),
                        extraido.impressaoDigital(), idUsuario)
                .query(Long.class).single();

        return buscar(idCertificado);
    }

    /**
     * Carrega o certificado ativo da empresa, <b>decifrado e pronto para assinar</b> — é o único
     * caminho de leitura do arquivo, e existe para o {@code fiscal.sefaz} (B6).
     *
     * <p><b>Nunca exposto por endpoint</b> (é `public` para o módulo de assinatura, não para a
     * web): o contrato de "write-only" vale para a API, e o Controller não tem rota que chame
     * isto. Todo uso deveria deixar rastro em {@code fiscal_certificado_uso} (F7) — quem chamar
     * registra, porque só quem chama sabe a finalidade e o documento.
     */
    @Transactional(readOnly = true)
    public CertificadoParaAssinatura carregarAtivoParaAssinatura(long idEmpresa) {
        return jdbc.sql("""
                        SELECT id_certificado, arquivo_cifrado, senha_cifrada, cnpj_titular,
                               valido_ate, impressao_digital
                        FROM fiscal_certificado
                        WHERE id_tenant = plataforma.tenant_atual() AND id_empresa = ? AND ativo
                        """)
                .param(idEmpresa)
                .query((rs, n) -> {
                    OffsetDateTime validoAte = rs.getObject("valido_ate", OffsetDateTime.class);
                    if (validoAte != null && validoAte.isBefore(OffsetDateTime.now())) {
                        throw new ResponseStatusException(CONFLICT,
                                "O certificado digital desta empresa venceu em %s. Envie um certificado novo antes de emitir."
                                        .formatted(validoAte.toLocalDate()));
                    }
                    return new CertificadoParaAssinatura(
                            rs.getLong("id_certificado"),
                            cifrador.decifrarBytes(rs.getBytes("arquivo_cifrado")),
                            cifrador.decifrar(rs.getString("senha_cifrada")),
                            rs.getString("cnpj_titular"),
                            rs.getString("impressao_digital"));
                })
                .optional()
                .orElseThrow(() -> new ResponseStatusException(CONFLICT,
                        "Nenhum certificado digital ativo para esta empresa. Envie o certificado A1 antes de emitir."));
    }

    /**
     * O {@code .pfx} decifrado em memória, para assinar/mTLS. <b>Vive o mínimo possível</b> —
     * quem recebe usa e descarta; nada disto vai para log, resposta HTTP ou disco (F7).
     */
    public record CertificadoParaAssinatura(long idCertificado, byte[] pkcs12, String senha,
                                            String cnpjTitular, String impressaoDigital) {
    }

    // ---------------------------------------------------------------- parsing do .pfx

    private record CertificadoExtraido(
            String cnpjTitular, String razaoSocialTitular, Instant validoDe, Instant validoAte, String impressaoDigital) {
    }

    /**
     * Validações 1 e 2 (abre como PKCS12 com a senha, tem chave privada) mais a extração de
     * metadados. Uma falha aqui nunca escreve nada — o método é puro sobre os bytes recebidos.
     */
    private static CertificadoExtraido abrirEExtrair(byte[] conteudo, String senha) {
        KeyStore keyStore;
        String alias;
        try {
            keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(conteudo), senha.toCharArray());
            alias = primeiroAlias(keyStore);
        } catch (IOException | GeneralSecurityException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Senha do certificado incorreta ou arquivo inválido.", e);
        }

        try {
            if (alias == null || !keyStore.isKeyEntry(alias)) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "O arquivo não contém uma chave privada — envie o .pfx completo, não só o certificado público.");
            }
            X509Certificate certificado = (X509Certificate) keyStore.getCertificate(alias);
            String cn = extrairCn(certificado.getSubjectX500Principal().getName());
            Matcher m = CNPJ_NO_CN.matcher(cn == null ? "" : cn);
            String cnpj = m.find() ? m.group(1) : null;
            String razaoSocial = cn != null && cn.contains(":") ? cn.substring(0, cn.indexOf(':')).trim() : cn;

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String impressaoDigital = HexFormat.of().formatHex(sha256.digest(certificado.getEncoded()));

            if (cnpj == null) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "Não foi possível identificar o CNPJ no certificado — formato de CN inesperado.");
            }

            return new CertificadoExtraido(cnpj, razaoSocial,
                    certificado.getNotBefore().toInstant(), certificado.getNotAfter().toInstant(),
                    impressaoDigital);
        } catch (GeneralSecurityException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Não foi possível ler o certificado.", e);
        }
    }

    private static String primeiroAlias(KeyStore keyStore) throws GeneralSecurityException {
        Enumeration<String> aliases = keyStore.aliases();
        return aliases.hasMoreElements() ? aliases.nextElement() : null;
    }

    /** Varre os RDNs do Subject DN (RFC 2253) procurando {@code CN=...} — não assume posição. */
    private static String extrairCn(String subjectDn) {
        for (String rdn : subjectDn.split(",")) {
            String parte = rdn.trim();
            if (parte.regionMatches(true, 0, "CN=", 0, 3)) {
                return parte.substring(3).trim();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- leitura

    private FiscalCertificadoResponse buscar(long idCertificado) {
        return jdbc.sql("""
                        SELECT id_certificado, id_empresa, cnpj_titular, razao_social_titular,
                               valido_de, valido_ate, impressao_digital, ativo, criado_em
                        FROM fiscal_certificado
                        WHERE id_tenant = plataforma.tenant_atual() AND id_certificado = ?
                        """)
                .param(idCertificado)
                .query(FiscalCertificadoService::mapear)
                .single();
    }

    private static FiscalCertificadoResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        boolean ativo = rs.getBoolean("ativo");
        OffsetDateTime validoAte = rs.getObject("valido_ate", OffsetDateTime.class);
        SituacaoCertificado situacao;
        Long diasParaVencer = null;
        if (!ativo) {
            situacao = SituacaoCertificado.SUBSTITUIDO;
        } else if (validoAte != null && validoAte.isBefore(OffsetDateTime.now())) {
            situacao = SituacaoCertificado.VENCIDO;
        } else {
            diasParaVencer = validoAte == null ? null : ChronoUnit.DAYS.between(OffsetDateTime.now(), validoAte);
            situacao = (diasParaVencer != null && diasParaVencer <= 30)
                    ? SituacaoCertificado.VENCE_EM_BREVE
                    : SituacaoCertificado.ATIVO;
        }

        return new FiscalCertificadoResponse(
                rs.getLong("id_certificado"), rs.getLong("id_empresa"),
                rs.getString("cnpj_titular"), rs.getString("razao_social_titular"),
                rs.getObject("valido_de", OffsetDateTime.class), validoAte,
                rs.getString("impressao_digital"), ativo, situacao, diasParaVencer,
                rs.getObject("criado_em", OffsetDateTime.class));
    }

    private static String mascarar(String cnpj) {
        if (cnpj == null || cnpj.length() != 14) {
            return cnpj == null ? "—" : cnpj;
        }
        return cnpj.substring(0, 2) + "." + cnpj.substring(2, 5) + "." + cnpj.substring(5, 8)
                + "/" + cnpj.substring(8, 12) + "-" + cnpj.substring(12);
    }

    private static Long idUsuarioDoJwt(Jwt jwt) {
        Object sub = jwt.getClaim("sub");
        try {
            return sub == null ? null : Long.parseLong(sub.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(FORBIDDEN, "Apenas ADMIN pode gerenciar o certificado digital.");
        }
    }
}
