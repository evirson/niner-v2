package com.vetor.niner.comum.arquivocompartilhado;

import com.vetor.niner.comum.config.NinerProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda bytes (ex.: um PDF) em memória por um token aleatório e não-enumerável (UUID), com
 * expiração automática — sem banco, sem object storage, sem custo de infraestrutura nova.
 * Trade-off aceito de propósito: um restart da API derruba tudo que ainda não expirou (aceitável
 * para conteúdo descartável tipo comprovante compartilhado por link; nunca usar para algo que
 * precise sobreviver a um deploy).
 *
 * <p><b>Limite por tenant (2026-08-07):</b> no máximo {@link #MAX_ARQUIVOS_POR_TENANT} arquivos
 * simultâneos por tenant — ao chegar o 21º, o mais antigo (por data de criação) daquele mesmo
 * tenant é apagado antes de aceitar o novo. Existe pra impedir que um único tenant (ou um usuário
 * comprometido dele) esgote a memória do processo — sem isso, nada limitava quantos uploads um
 * tenant podia empilhar ao mesmo tempo, e um abuso derrubaria a API pra **todos** os tenants
 * daquela instância, não só o autor do abuso.
 */
@Service
public class ArquivoCompartilhadoService {

    static final int MAX_ARQUIVOS_POR_TENANT = 20;

    /** Nunca persiste — só vive na memória do processo, pelo tempo configurado. {@code idTenant}
     * existe só pra aplicar o limite por tenant, não pra controle de acesso (quem baixa não
     * informa tenant nenhum — o token já é a única credencial do download). */
    record Arquivo(long idTenant, byte[] conteudo, String tipoConteudo, Instant criadoEm, Instant expiraEm) {
    }

    private final Map<String, Arquivo> arquivos = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int expiracaoHoras;

    public ArquivoCompartilhadoService(NinerProperties props, Clock clock) {
        this.expiracaoHoras = props.arquivoCompartilhado().expiracaoHoras();
        this.clock = clock;
    }

    public String armazenar(long idTenant, byte[] conteudo, String tipoConteudo) {
        limitarPorTenant(idTenant);
        String token = UUID.randomUUID().toString();
        Instant agora = Instant.now(clock);
        Instant expiraEm = agora.plus(expiracaoHoras, ChronoUnit.HOURS);
        arquivos.put(token, new Arquivo(idTenant, conteudo, tipoConteudo, agora, expiraEm));
        return token;
    }

    public Optional<Arquivo> buscar(String token) {
        Arquivo arquivo = arquivos.get(token);
        if (arquivo == null) {
            return Optional.empty();
        }
        if (Instant.now(clock).isAfter(arquivo.expiraEm())) {
            arquivos.remove(token);
            return Optional.empty();
        }
        return Optional.of(arquivo);
    }

    /**
     * Antes de aceitar um novo arquivo, garante que o tenant não passe de {@link
     * #MAX_ARQUIVOS_POR_TENANT} — remove o mais antigo (menor {@code criadoEm}) até caber o novo.
     * Race aceita de propósito: dois uploads simultâneos do mesmo tenant podem, por uma janela
     * curtíssima, deixar o total em 21 em vez de 20 — não é um controle de segurança fino, é só um
     * teto de memória; não vale a complexidade de travar por tenant pra fechar essa janela.
     */
    private void limitarPorTenant(long idTenant) {
        while (contarPorTenant(idTenant) >= MAX_ARQUIVOS_POR_TENANT) {
            arquivos.entrySet().stream()
                    .filter(e -> e.getValue().idTenant() == idTenant)
                    .min(Comparator.comparing(e -> e.getValue().criadoEm()))
                    .ifPresent(e -> arquivos.remove(e.getKey()));
        }
    }

    private long contarPorTenant(long idTenant) {
        return arquivos.values().stream().filter(a -> a.idTenant() == idTenant).count();
    }

    /**
     * Expurga entradas vencidas a cada hora — sem isso, um token gerado e nunca mais baixado
     * ficaria ocupando memória indefinidamente (a expiração dentro de {@link #buscar} só limpa a
     * entrada quando alguém tenta acessar aquele token específico).
     */
    @Scheduled(fixedRate = 3_600_000)
    void limparExpirados() {
        Instant agora = Instant.now(clock);
        arquivos.entrySet().removeIf(e -> agora.isAfter(e.getValue().expiraEm()));
    }
}
