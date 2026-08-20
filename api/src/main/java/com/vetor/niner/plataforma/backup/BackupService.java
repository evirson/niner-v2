package com.vetor.niner.plataforma.backup;

import com.vetor.niner.comum.tempo.FusoDaPlataforma;
import com.vetor.niner.comum.config.NinerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executa o backup do banco: {@code pg_dump} → objeto no MinIO → expurgo do que passou da
 * retenção → registro do resultado em {@code plataforma.configuracao_plataforma}.
 *
 * <p>⚠️ <b>O dump NÃO pode rodar como {@code niner_app}.</b> As tabelas de domínio têm
 * {@code FORCE ROW LEVEL SECURITY} e, sem {@code app.id_tenant} no contexto, a política filtra
 * <b>todas</b> as linhas: o arquivo sairia "com sucesso", pequeno e <b>vazio de dados de
 * cliente</b> — o pior tipo de backup, o que só falha na hora de restaurar. Por isso as
 * credenciais são próprias ({@code NINER_BACKUP_DB_*}), e o serviço <b>recusa</b> rodar como
 * {@code niner_app}.
 *
 * <p>O resultado (inclusive o erro) fica visível na tela de configuração: backup que falha em
 * silêncio é indistinguível de backup que funciona, até o dia do restore.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final String PREFIXO = "plataforma/backup/";
    private static final Duration TIMEOUT_DUMP = Duration.ofMinutes(30);

    private final JdbcClient jdbc;
    private final ObjectProvider<S3Client> s3;
    private final NinerProperties props;
    private final javax.sql.DataSource dataSource;
    private final String url;
    private final String usuario;
    private final String senha;

    public BackupService(JdbcClient jdbc, ObjectProvider<S3Client> s3, NinerProperties props,
            javax.sql.DataSource dataSource,
            @Value("${niner.backup.jdbc-url:}") String url,
            @Value("${niner.backup.usuario:}") String usuario,
            @Value("${niner.backup.senha:}") String senha) {
        this.jdbc = jdbc;
        this.s3 = s3;
        this.props = props;
        this.dataSource = dataSource;
        this.url = url;
        this.usuario = usuario;
        this.senha = senha;
    }

    /**
     * Roda um backup agora. Nunca lança: o chamador é um agendador (ou um clique no backoffice) e
     * o que interessa é o resultado ficar registrado.
     *
     * @return descrição curta do que aconteceu (a mesma que a tela mostra)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String executar() {
        if (usuario.isBlank() || senha.isBlank()) {
            return registrar("ERRO", "Credenciais de backup não configuradas (NINER_BACKUP_USUARIO/SENHA). "
                    + "Precisa ser um usuário sem RLS — nunca o niner_app.");
        }
        String semBypass = motivoSeNaoIgnoraRls();
        if (semBypass != null) {
            return registrar("ERRO", semBypass);
        }

        Path arquivo = null;
        try {
            arquivo = Files.createTempFile("niner-backup-", ".dump");
            executarPgDump(arquivo);
            long tamanho = Files.size(arquivo);
            if (tamanho == 0) {
                return registrar("ERRO", "pg_dump gerou arquivo vazio.");
            }

            String chave = PREFIXO + "niner-" + LocalDateTime.now(FusoDaPlataforma.ZONA).format(CARIMBO) + ".dump";
            enviar(chave, arquivo);
            int expurgados = expurgar();

            String detalhe = "%s (%.1f MB)%s".formatted(chave, tamanho / 1024.0 / 1024.0,
                    expurgados > 0 ? " · %d antigo(s) removido(s)".formatted(expurgados) : "");
            log.info("Backup concluído: {}", detalhe);
            return registrar("OK", detalhe);
        } catch (Exception e) {
            log.error("Backup falhou", e);
            return registrar("ERRO", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            apagarTemporario(arquivo);
        }
    }

    /**
     * Confere se o usuário do backup <b>ignora RLS de verdade</b>, perguntando ao banco — não pelo
     * nome do papel.
     *
     * <p>Verificado no Postgres em 2026-08-19: com {@code FORCE ROW LEVEL SECURITY} (V024), até o
     * <b>dono</b> das tabelas ({@code niner_owner}) enxerga <b>zero linha</b> sem
     * {@code app.id_tenant} no contexto — {@code SELECT count(*) FROM empresa} devolveu 0 para o
     * owner e 3 para o superusuário. Ou seja: "usar o dono" <b>não</b> resolve, e o dump sairia
     * com o esqueleto do banco e nenhum dado de cliente. Só {@code rolbypassrls} salva.
     *
     * @return {@code null} quando pode dumpar; a mensagem de recusa caso contrário
     */
    private String motivoSeNaoIgnoraRls() {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(urlParaJdbc(), usuario, senha);
                java.sql.Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery(
                        "SELECT rolbypassrls OR rolsuper AS pode FROM pg_roles WHERE rolname = current_user")) {
            if (rs.next() && rs.getBoolean("pode")) {
                return null;
            }
            return ("Backup recusado: o usuário '%s' não ignora RLS (sem BYPASSRLS). Com FORCE ROW LEVEL "
                    + "SECURITY, até o dono das tabelas enxerga zero linha sem contexto de tenant — o dump "
                    + "sairia sem nenhum dado de cliente. Use um papel com BYPASSRLS (ex.: niner_backup).")
                    .formatted(usuario);
        } catch (java.sql.SQLException e) {
            return "Backup recusado: não foi possível conectar com as credenciais de backup (" + e.getMessage() + ")";
        }
    }

    private void executarPgDump(Path destino) throws IOException, InterruptedException {
        // Formato custom (-Fc): comprimido e restaurável seletivamente com pg_restore.
        ProcessBuilder pb = new ProcessBuilder("pg_dump", "--format=custom", "--no-owner",
                "--file=" + destino.toAbsolutePath(), "--dbname=" + urlParaLibpq());
        pb.environment().put("PGPASSWORD", senha);
        pb.redirectErrorStream(true);
        Process processo = pb.start();

        String saida;
        try (InputStream is = processo.getInputStream()) {
            saida = new String(is.readAllBytes()).trim();
        }
        if (!processo.waitFor(TIMEOUT_DUMP.toMinutes(), TimeUnit.MINUTES)) {
            processo.destroyForcibly();
            throw new IOException("pg_dump excedeu " + TIMEOUT_DUMP.toMinutes() + " minutos");
        }
        if (processo.exitValue() != 0) {
            throw new IOException("pg_dump falhou (código " + processo.exitValue() + "): " + saida);
        }
    }

    /**
     * {@code jdbc:postgresql://host:porta/base} → {@code postgresql://usuario@host:porta/base}.
     *
     * <p>Sem {@code niner.backup.jdbc-url}, o endereço vem do próprio {@code DataSource} — em
     * teste (Testcontainers) e em produção a URL nem sempre existe como propriedade, mas a conexão
     * sempre sabe para onde aponta. Só o <b>usuário</b> é trocado: o do backup precisa ignorar RLS.
     */
    /** URL JDBC efetiva (a configurada ou a do próprio {@code DataSource}). */
    private String urlParaJdbc() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        try (java.sql.Connection c = dataSource.getConnection()) {
            return c.getMetaData().getURL();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Não foi possível descobrir a URL do banco para o backup", e);
        }
    }

    private String urlParaLibpq() {
        String origem = url;
        if (origem == null || origem.isBlank()) {
            try (java.sql.Connection c = dataSource.getConnection()) {
                origem = c.getMetaData().getURL();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Não foi possível descobrir a URL do banco para o backup", e);
            }
        }
        String semPrefixo = origem.replaceFirst("^jdbc:", "").replaceFirst("\\?.*$", "");
        int barra = semPrefixo.indexOf("//");
        return semPrefixo.substring(0, barra + 2) + usuario + "@" + semPrefixo.substring(barra + 2);
    }

    private void enviar(String chave, Path arquivo) {
        String bucket = props.storage().privado().bucketPrivado();
        s3.getObject().putObject(
                PutObjectRequest.builder().bucket(bucket).key(chave)
                        .contentType("application/octet-stream").build(),
                RequestBody.fromFile(arquivo));
    }

    /** Remove o que passou da retenção configurada. Retenção é do backoffice, não do código. */
    private int expurgar() {
        int dias = jdbc.sql("SELECT backup_retencao_dias FROM plataforma.configuracao_plataforma WHERE id = 1")
                .query(Integer.class).single();
        Instant limite = Instant.now().minus(Duration.ofDays(dias));
        String bucket = props.storage().privado().bucketPrivado();

        List<S3Object> objetos = s3.getObject()
                .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(PREFIXO).build())
                .contents();
        int removidos = 0;
        for (S3Object o : objetos) {
            if (o.lastModified().isBefore(limite)) {
                s3.getObject().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(o.key()).build());
                removidos++;
            }
        }
        return removidos;
    }

    /**
     * Grava o resultado <b>fora</b> da transação de negócio (NOT_SUPPORTED acima): o registro do
     * erro precisa sobreviver mesmo quando a causa da falha for o próprio banco.
     */
    private String registrar(String status, String detalhe) {
        String texto = detalhe != null && detalhe.length() > 500 ? detalhe.substring(0, 500) : detalhe;
        try {
            jdbc.sql("""
                            UPDATE plataforma.configuracao_plataforma
                               SET backup_ultimo_em = now(), backup_ultimo_status = ?, backup_ultimo_detalhe = ?
                             WHERE id = 1
                            """)
                    .params(status, texto).update();
        } catch (RuntimeException e) {
            log.error("Não foi possível registrar o resultado do backup: {}", e.getMessage());
        }
        return status + ": " + texto;
    }

    private static void apagarTemporario(Path arquivo) {
        if (arquivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException e) {
            log.warn("Arquivo temporário de backup não removido: {}", arquivo);
        }
    }

    /** Situação atual, para o agendador decidir se já rodou hoje. */
    @Transactional(readOnly = true)
    public Map<String, Object> situacao() {
        return jdbc.sql("""
                        SELECT backup_habilitado, backup_hora, backup_ultimo_em, backup_ultimo_status
                          FROM plataforma.configuracao_plataforma WHERE id = 1
                        """)
                .query((rs, n) -> Map.<String, Object>of(
                        "habilitado", rs.getBoolean("backup_habilitado"),
                        "hora", rs.getObject("backup_hora", java.time.LocalTime.class),
                        "ultimoEm", String.valueOf(rs.getObject("backup_ultimo_em")),
                        "ultimoStatus", String.valueOf(rs.getString("backup_ultimo_status"))))
                .single();
    }
}
