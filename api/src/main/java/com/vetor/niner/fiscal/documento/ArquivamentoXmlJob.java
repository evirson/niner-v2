package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rede de segurança do arquivamento (handoff §4.1, item 2) — varre o que o caminho quente
 * ({@link ArquivamentoXmlService#arquivarDocumentoSeAplicavel} e afins, chamados logo após cada
 * autorização/cancelamento/inutilização) deixou pendente: MinIO fora do ar no momento, restart no
 * meio, ou nota autorizada por um caminho que não passa pelo PDV (drenagem de contingência,
 * reprocessamento). Sem este job, uma falha pontual de bucket vira pendência esquecida para
 * sempre — a guarda legal de 5 anos (F6) não se cumpre sozinha.
 *
 * <p><b>P8 em caminho não-requisição</b>: mesmo padrão de {@link FiscalContingenciaDrenoJob} — sem
 * JWT, a lista de tenants vem de {@link DocumentoFiscalRepositorio#listarTenantIds()}
 * ({@code plataforma.tenant}, GLOBAL, sem RLS, P9), e cada tenant entra em
 * {@link TenantContext#comTenant} <b>antes</b> de qualquer consulta de domínio — inclusive a de
 * "este tenant tem pendência?". Uma consulta de domínio sem tenant no contexto não vê nada de
 * nenhum tenant ({@code niner_app} nunca tem {@code BYPASSRLS}, P8): não é filtro "global", é RLS
 * devolvendo vazio em silêncio. Esta rodada sempre chama {@link #processarTenant} para todo
 * tenant — as consultas internas já são baratas e vazias na maioria das rodadas.
 *
 * <p>Chama os métodos {@code arquivar*} <b>não</b>-wrapped (pacote) de {@link ArquivamentoXmlService}
 * diretamente, porque este job já trata erro por item sozinho — usar as variantes
 * {@code *SeAplicavel} (que engolem exceção) esconderia do log daqui qual item específico falhou.
 */
@Component
public class ArquivamentoXmlJob {

    private static final Logger log = LoggerFactory.getLogger(ArquivamentoXmlJob.class);

    /** Quantos itens por tabela por tenant por rodada — devagar o bastante para não competir com
     *  tráfego de emissão em produção. */
    private static final int LOTE = 50;

    private final DocumentoFiscalRepositorio repositorio;
    private final FiscalInutilizacaoRepositorio inutilizacaoRepositorio;
    private final ArquivamentoXmlService arquivamento;

    public ArquivamentoXmlJob(DocumentoFiscalRepositorio repositorio,
                              FiscalInutilizacaoRepositorio inutilizacaoRepositorio,
                              ArquivamentoXmlService arquivamento) {
        this.repositorio = repositorio;
        this.inutilizacaoRepositorio = inutilizacaoRepositorio;
        this.arquivamento = arquivamento;
    }

    /** A cada 10 minutos: a guarda é de anos, não há pressa de segundos — só não deixar acumular. */
    @Scheduled(fixedRate = 600_000, initialDelay = 90_000)
    public void arquivarPendencias() {
        for (Long idTenant : repositorio.listarTenantIds()) {
            try {
                TenantContext.comTenant(idTenant, () -> processarTenant(idTenant));
            } catch (Exception e) {
                // Um tenant com problema (certificado vencido não impede nada aqui — o arquivamento
                // não assina nem transmite — mas um bug de dados, por exemplo) não pode travar os
                // outros.
                log.warn("Falha ao arquivar pendências fiscais do tenant {}: {}", idTenant, e.toString());
            }
        }
    }

    private void processarTenant(long idTenant) {
        for (Long id : repositorio.pendentesDeArquivamento(LOTE)) {
            try {
                arquivamento.arquivarDocumento(id);
            } catch (Exception e) {
                log.warn("Falha ao arquivar documento fiscal {} (tenant {}): {}", id, idTenant, e.toString());
            }
        }
        for (Long id : repositorio.eventosPendentesDeArquivamento(LOTE)) {
            try {
                arquivamento.arquivarEvento(id);
            } catch (Exception e) {
                log.warn("Falha ao arquivar evento fiscal {} (tenant {}): {}", id, idTenant, e.toString());
            }
        }
        for (Long id : inutilizacaoRepositorio.pendentesDeArquivamento(LOTE)) {
            try {
                arquivamento.arquivarInutilizacao(id);
            } catch (Exception e) {
                log.warn("Falha ao arquivar inutilização {} (tenant {}): {}", id, idTenant, e.toString());
            }
        }
        // XML da NF-e do FORNECEDOR (V051) — entrada por XML cujo arquivamento no bucket ainda não
        // aconteceu (MinIO fora do ar na hora da entrada, ou a chamada pós-commit não rodou). O XML
        // segue no banco até aqui, então nada se perde; o que muda é onde ele descansa.
        for (Long id : repositorio.entradasPendentesDeArquivamento(LOTE)) {
            try {
                arquivamento.arquivarEntradaXml(id);
            } catch (Exception e) {
                log.warn("Falha ao arquivar XML da entrada {} (tenant {}): {}", id, idTenant, e.toString());
            }
        }
    }
}
