package com.vetor.niner.fiscal.nfse;

import com.vetor.niner.comum.tempo.FusoDaLoja;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Configuração da NFS-e por empresa, e o <b>assistente</b> que é o coração do "configurar sem
 * suporte".
 *
 * <p>⚠️ Segue o padrão da {@code fiscal.configuracao}: a linha pode não existir, e o {@code GET}
 * devolve 200 com os defaults e {@code configurado=false} — nunca 404. Responder 404 obrigaria a
 * tela a distinguir "empresa inexistente" de "NFS-e ainda não configurada", que é ruído sem ganho.
 */
@Service
public class NfseConfigService {

    private final JdbcClient jdbc;
    private final EmissorDeNfse emissor;
    private final FiscalCertificadoService certificados;
    private final ParametrosMunicipaisClient adn;
    private final MunicipioNfseService municipios;
    private final FusoDaLoja fusoDaLoja;

    public NfseConfigService(JdbcClient jdbc, EmissorDeNfse emissor,
                             FiscalCertificadoService certificados,
                             ParametrosMunicipaisClient adn, MunicipioNfseService municipios,
                             FusoDaLoja fusoDaLoja) {
        this.jdbc = jdbc;
        this.emissor = emissor;
        this.certificados = certificados;
        this.adn = adn;
        this.municipios = municipios;
        this.fusoDaLoja = fusoDaLoja;
    }

    @Transactional(readOnly = true)
    public Config buscar(long idEmpresa) {
        return jdbc.sql("""
                        SELECT COALESCE(c.emite_nfse, false)              AS emite_nfse,
                               COALESCE(c.ambiente::text, 'HOMOLOGACAO')  AS ambiente,
                               COALESCE(c.serie, 1)                       AS serie,
                               c.rbt12, c.simples_anexo, c.aliquota_simples_efetiva,
                               c.ultimo_teste_em, c.ultimo_teste_status, c.ultimo_teste_mensagem,
                               (c.id_config_nfse IS NOT NULL)             AS configurado,
                               e.cnpj, e.inscricao_municipal, e.codigo_municipio_ibge,
                               COALESCE(f.crt, 1)                         AS crt
                          FROM empresa e
                          LEFT JOIN fiscal_config_nfse c
                            ON c.id_tenant = e.id_tenant AND c.id_empresa = e.id_empresa
                          LEFT JOIN fiscal_config_empresa f
                            ON f.id_tenant = e.id_tenant AND f.id_empresa = e.id_empresa
                         WHERE e.id_tenant = plataforma.tenant_atual() AND e.id_empresa = ?
                        """)
                .param(idEmpresa)
                .query((rs, n) -> new Config(
                        idEmpresa, rs.getBoolean("emite_nfse"), rs.getString("ambiente"),
                        rs.getInt("serie"), rs.getBigDecimal("rbt12"),
                        rs.getString("simples_anexo"),
                        rs.getBigDecimal("aliquota_simples_efetiva"),
                        rs.getObject("ultimo_teste_em", OffsetDateTime.class),
                        rs.getString("ultimo_teste_status"), rs.getString("ultimo_teste_mensagem"),
                        rs.getBoolean("configurado"), rs.getString("cnpj"),
                        rs.getString("inscricao_municipal"),
                        (Integer) rs.getObject("codigo_municipio_ibge"), rs.getInt("crt")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Empresa não encontrada"));
    }

    @Transactional
    public Config salvar(long idEmpresa, SalvarConfig req) {
        // ⚠️ Os três campos abaixo são NOT NULL na tabela. Sem esta validação, o nulo chegava ao
        // INSERT e a violação saía pelo handler global como "Registro em uso por outro cadastro —
        // não pode ser excluído": uma mensagem sobre EXCLUSÃO DE CADASTRO para quem estava
        // salvando uma configuração, e que não diz o campo. Recusar aqui, nomeando o campo, é a
        // diferença entre o lojista corrigir sozinho e abrir um chamado.
        if (req.serie() == null || req.serie() < 1 || req.serie() > 99_999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe a Série da DPS, entre 1 e 99999.");
        }
        if (req.emiteNfse() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe se esta empresa emite NFS-e.");
        }
        if (req.ambiente() == null || req.ambiente().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Informe o ambiente da NFS-e (Homologação ou Produção).");
        }
        // ⚠️ Ligar a emissão é o momento do F11: se falta o que impede emitir, o gate não liga —
        // é melhor recusar aqui, com a lista do que falta, do que deixar o operador descobrir no
        // balcão pelo código de erro do SEFIN.
        if (Boolean.TRUE.equals(req.emiteNfse())) {
            List<String> pendencias = pendenciasParaLigar(idEmpresa, req);
            if (!pendencias.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ainda não dá para ligar a emissão de NFS-e: " + String.join("; ", pendencias));
            }
        }
        jdbc.sql("""
                        INSERT INTO fiscal_config_nfse (
                            id_tenant, id_empresa, emite_nfse, ambiente, serie,
                            rbt12, simples_anexo, aliquota_simples_efetiva)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?::ambiente_fiscal, ?, ?, ?, ?)
                        ON CONFLICT (id_tenant, id_empresa) DO UPDATE
                            SET emite_nfse = EXCLUDED.emite_nfse,
                                ambiente   = EXCLUDED.ambiente,
                                serie      = EXCLUDED.serie,
                                rbt12      = EXCLUDED.rbt12,
                                simples_anexo = EXCLUDED.simples_anexo,
                                aliquota_simples_efetiva = EXCLUDED.aliquota_simples_efetiva,
                                atualizado_em = now()
                        """)
                .params(idEmpresa, req.emiteNfse(), req.ambiente(), req.serie(), req.rbt12(),
                        req.simplesAnexo(), req.aliquotaSimplesEfetiva())
                .update();
        return buscar(idEmpresa);
    }

    private List<String> pendenciasParaLigar(long idEmpresa, SalvarConfig req) {
        Config atual = buscar(idEmpresa);
        List<String> faltas = new ArrayList<>();
        if (atual.cnpj() == null || atual.cnpj().replaceAll("\\D", "").length() != 14) {
            faltas.add("a empresa está sem CNPJ válido");
        }
        if (atual.codigoMunicipioIbge() == null) {
            faltas.add("a empresa está sem código de município (IBGE) — ele vem do CEP");
        }
        if (req.aliquotaSimplesEfetiva() == null) {
            faltas.add("falta a alíquota efetiva do Simples, sem a qual o Sefin recusa (E0712); "
                    + "ela está no extrato do PGDAS-D do mês anterior");
        }
        try {
            certificados.carregarAtivoParaAssinatura(idEmpresa);
        } catch (RuntimeException semCertificado) {
            faltas.add("não há certificado A1 ativo — envie em Certificado Digital");
        }
        return faltas;
    }

    /**
     * Testar conexão: {@code GET} de uma chave inexistente.
     *
     * <p>⭐ A resposta <b>esperada</b> é 404 com {@code E2401}. É contraintuitivo e por isso está
     * escrito: um teste que esperasse 200 nunca passaria, e um que aceitasse qualquer coisa não
     * provaria nada. 404+E2401 significa que o mTLS autenticou e a requisição chegou à aplicação.
     */
    @Transactional
    public Teste testarConexao(long idEmpresa) {
        Config cfg = buscar(idEmpresa);
        RespostaSefin r;
        try {
            var cert = certificados.carregarAtivoParaAssinatura(idEmpresa);
            r = emissor.testarConexao(new EmissorDeNfse.Credencial(cert.pkcs12(), cert.senha(),
                    cert.impressaoDigital(), "PRODUCAO".equals(cfg.ambiente())));
        } catch (RuntimeException e) {
            return gravarTeste(idEmpresa, "FALHA", e.getMessage());
        }
        boolean ok = r.httpStatus() == 404
                && (r.primeiroCodigo() == null || "E2401".equals(r.primeiroCodigo()));
        return gravarTeste(idEmpresa, ok ? "OK" : "FALHA",
                ok ? "Conexão e certificado conferidos (HTTP 404 + E2401, que é a resposta "
                     + "esperada para uma chave inexistente)."
                   : "HTTP " + r.httpStatus() + (r.mensagem() == null ? "" : " — " + r.mensagem()));
    }

    private Teste gravarTeste(long idEmpresa, String status, String mensagem) {
        jdbc.sql("""
                        INSERT INTO fiscal_config_nfse (id_tenant, id_empresa, ultimo_teste_em,
                                                        ultimo_teste_status, ultimo_teste_mensagem)
                        VALUES (plataforma.tenant_atual(), ?, now(), ?, ?)
                        ON CONFLICT (id_tenant, id_empresa) DO UPDATE
                            SET ultimo_teste_em = now(),
                                ultimo_teste_status = EXCLUDED.ultimo_teste_status,
                                ultimo_teste_mensagem = EXCLUDED.ultimo_teste_mensagem
                        """)
                .params(idEmpresa, status, mensagem)
                .update();
        return new Teste(status, mensagem, OffsetDateTime.now());
    }

    /**
     * ⭐ O assistente: roda as verificações em sequência e devolve <b>o que passou e o que falta,
     * cada pendência com onde resolver</b>.
     *
     * <p>É o padrão que a Conformidade Fiscal já usa, e a correção de 2026-08 registrou por que
     * ele importa: <i>"'sem IBGE' / 'sem CNAE' no painel não levavam a lugar nenhum"</i>. Item de
     * verificação que não diz onde resolver é item que vira chamado.
     */
    @Transactional
    public List<Verificacao> verificar(long idEmpresa) {
        Config cfg = buscar(idEmpresa);
        List<Verificacao> itens = new ArrayList<>();

        boolean cnpjOk = cfg.cnpj() != null && cfg.cnpj().replaceAll("\\D", "").length() == 14;
        itens.add(new Verificacao("CNPJ da empresa", cnpjOk,
                cnpjOk ? cfg.cnpj() : "Preencha o CNPJ", "/empresa"));

        boolean municipioOk = cfg.codigoMunicipioIbge() != null;
        itens.add(new Verificacao("Código de município (IBGE)", municipioOk,
                municipioOk ? String.valueOf(cfg.codigoMunicipioIbge())
                            : "Informe o CEP na tela de Empresa — o código vem junto", "/empresa"));

        boolean certOk;
        String detalheCert;
        try {
            var cert = certificados.carregarAtivoParaAssinatura(idEmpresa);
            certOk = true;
            detalheCert = "Certificado ativo de " + cert.cnpjTitular();
            if (cnpjOk && cert.cnpjTitular() != null
                    && !cert.cnpjTitular().equals(cfg.cnpj().replaceAll("\\D", ""))) {
                // ⛔ Emitir com certificado de outro CNPJ AUTORIZA a nota — no CNPJ errado. É o
                // pior desfecho possível, porque não parece erro.
                certOk = false;
                detalheCert = "O CNPJ do certificado (" + cert.cnpjTitular()
                        + ") é diferente do da empresa (" + cfg.cnpj() + ")";
            }
        } catch (RuntimeException e) {
            certOk = false;
            detalheCert = "Nenhum certificado A1 ativo";
        }
        itens.add(new Verificacao("Certificado digital A1", certOk, detalheCert,
                "/fiscal/certificado"));

        boolean aliqOk = cfg.aliquotaSimplesEfetiva() != null;
        itens.add(new Verificacao("Alíquota efetiva do Simples", aliqOk,
                aliqOk ? cfg.aliquotaSimplesEfetiva() + "%"
                       : "Obrigatória para optante: sem ela o Sefin recusa (E0712). Está no "
                         + "extrato do PGDAS-D do mês anterior",
                "/fiscal/nfse"));

        // As duas verificações que dependem de rede ficam por último: elas podem demorar, e as
        // anteriores já dizem se vale a pena tentar.
        if (certOk && municipioOk) {
            var cert = certificados.carregarAtivoParaAssinatura(idEmpresa);
            var credencial = new EmissorDeNfse.Credencial(cert.pkcs12(), cert.senha(),
                    cert.impressaoDigital(), "PRODUCAO".equals(cfg.ambiente()));

            var convenio = adn.convenio(cfg.codigoMunicipioIbge(), credencial);
            convenio.ifPresent(c -> municipios.registrarConvenio(
                    cfg.codigoMunicipioIbge(), "PRODUCAO".equals(cfg.ambiente()), c));
            boolean opera = convenio.map(ParametrosMunicipaisClient.Convenio::aderenteEmissorNacional)
                    .orElse(false);
            itens.add(new Verificacao("Município no Emissor Nacional", opera,
                    convenio.isEmpty() ? "Não foi possível consultar o ADN agora — tente de novo"
                            : opera ? "O município opera no Emissor Nacional"
                                    : "⛔ Este município NÃO opera no Emissor Nacional. O Nainer "
                                      + "não emite NFS-e aqui — é limite de escopo, não falta de "
                                      + "configuração",
                    null));

            Teste teste = testarConexao(idEmpresa);
            itens.add(new Verificacao("Conexão com o Sefin Nacional",
                    "OK".equals(teste.status()), teste.mensagem(), null));
        }
        return itens;
    }

    /**
     * Consulta a alíquota do ISS no ADN para um código de serviço.
     *
     * <p>⚠️ Vazio é resposta legítima (São Paulo e Rio não publicaram a tabela): a tela sugere
     * quando tem e pede ao lojista quando não tem — nunca bloqueia por isso.
     */
    @Transactional(readOnly = true)
    public Optional<ParametrosMunicipaisClient.Aliquota> aliquotaSugerida(
            long idEmpresa, String cTribNac, String cTribMun) {
        Config cfg = buscar(idEmpresa);
        if (cfg.codigoMunicipioIbge() == null) {
            return Optional.empty();
        }
        var cert = certificados.carregarAtivoParaAssinatura(idEmpresa);
        var credencial = new EmissorDeNfse.Credencial(cert.pkcs12(), cert.senha(),
                cert.impressaoDigital(), "PRODUCAO".equals(cfg.ambiente()));
        return adn.aliquota(cfg.codigoMunicipioIbge(),
                ParametrosMunicipaisClient.formatoDaApi(cTribNac, cTribMun),
                // ⚠️ LocalDate.now() sem fuso pega o TZ do container, que só existe em produção —
                // o guarda ComparacaoDeDataNoFusoCertoTest me reprovou aqui, e com razão: a
                // competência viraria um mês antes às 21h, e o defeito não reproduz em dev.
                LocalDate.now(fusoDaLoja.da(idEmpresa)).withDayOfMonth(1), credencial);
    }

    /**
     * O que o cliente PODE mandar ao salvar — e só isso (2026-09-01).
     *
     * <p><b>Por que existe, e não se reusa o {@link Config}:</b> a tela nunca conseguiu salvar.
     * O {@code Config} é o DTO de <b>leitura</b> e carrega três componentes <b>primitivos</b>
     * ({@code long idEmpresa}, {@code boolean configurado}, {@code int crt}); usado como
     * {@code @RequestBody}, o Jackson exige os três, e o corpo que o front monta — só os campos
     * editáveis — era recusado inteiro com <i>"Failed to read request"</i>. Medido isolando campo
     * a campo: faltando qualquer um dos três dá 400; com os três, passa. A mensagem não diz qual
     * campo, então o defeito não tinha como ser diagnosticado pela tela.
     *
     * <p>⚠️ Reusar o DTO de leitura ainda tinha um segundo problema, pior que o primeiro: metade
     * dos campos dele é <b>derivada</b> ({@code configurado}, {@code crt}, {@code cnpj},
     * {@code ultimoTeste*}) — aceitá-los no corpo é oferecer ao cliente campos que o servidor
     * calcula, e que ele ignora em silêncio. Entrada e saída são contratos diferentes.
     *
     * <p>Wrappers, não primitivos: campo ausente vira {@code null} e é <b>validado com mensagem
     * que nomeia o campo</b>, em vez de virar {@code 0}/{@code false} sem ninguém perceber.
     */
    public record SalvarConfig(Boolean emiteNfse, String ambiente, Integer serie, BigDecimal rbt12,
                               String simplesAnexo, BigDecimal aliquotaSimplesEfetiva) {
    }

    public record Config(long idEmpresa, Boolean emiteNfse, String ambiente, int serie,
                         BigDecimal rbt12, String simplesAnexo, BigDecimal aliquotaSimplesEfetiva,
                         OffsetDateTime ultimoTesteEm, String ultimoTesteStatus,
                         String ultimoTesteMensagem, boolean configurado, String cnpj,
                         String inscricaoMunicipal, Integer codigoMunicipioIbge, int crt) {
    }

    public record Teste(String status, String mensagem, OffsetDateTime em) {
    }

    /** Um item do assistente: o que é, se passou, o detalhe e ONDE resolver. */
    public record Verificacao(String item, boolean ok, String detalhe, String telaParaResolver) {
    }
}
