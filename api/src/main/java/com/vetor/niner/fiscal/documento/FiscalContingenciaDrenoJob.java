package com.vetor.niner.fiscal.documento;

import com.vetor.niner.comum.tenant.TenantContext;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.documento.DocumentoFiscalRepositorio.EmpresaEmContingencia;
import com.vetor.niner.fiscal.documento.DocumentoFiscalRepositorio.NotaPendente;
import com.vetor.niner.fiscal.sefaz.SefazAutorizadorService;
import com.vetor.niner.fiscal.sefaz.SefazDtos.FalhaDeComunicacaoException;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazDtos.ServicoSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drena a fila de notas emitidas em contingência (docs/MODULOFISCAL.md §9.7).
 *
 * <p><b>Sem este job a contingência é armadilha:</b> a nota sai, o cupom vai para a mão do
 * consumidor, e o documento fica parado para sempre — o prazo legal (24 h no PR, coluna em
 * {@code cfg_uf_autorizador}, F10) vence sem ninguém perceber.
 *
 * <h2>Ordem de emissão, não ordem de descoberta</h2>
 *
 * <p>A fila é drenada por {@code data_emissao} crescente. A SEFAZ rejeita nota cuja numeração pula
 * — transmitir a de número 12 antes da 10 cria buraco aparente e complica a conferência.
 *
 * <h2>P8 em caminho não-requisição</h2>
 *
 * <p>Não há JWT aqui: o job varre <b>todos</b> os tenants. Por isso a varredura roda como
 * {@code niner_owner} numa consulta explicitamente global, e <b>cada</b> transmissão entra em
 * {@link TenantContext#comTenant} antes de tocar em dado de domínio. Esquecer isso faria o RLS
 * barrar tudo — ou, pior, o job trabalhar no tenant errado.
 */
@Component
public class FiscalContingenciaDrenoJob {

    private static final Logger log = LoggerFactory.getLogger(FiscalContingenciaDrenoJob.class);

    private static final int MODELO_NFCE = 65;
    private static final String SERVICO_AUTORIZACAO = "NFeAutorizacao4";
    private static final String SERVICO_STATUS = "NFeStatusServico4";

    /** Quantas notas por empresa por rodada: drenar devagar evita saturar a SEFAZ recém-restabelecida. */
    private static final int LOTE_POR_EMPRESA = 20;

    private final SefazTransporte transporte;
    private final SefazAutorizadorService autorizadores;
    private final FiscalCertificadoService certificados;
    private final FiscalContingenciaService contingencia;
    private final DocumentoFiscalRepositorio repositorio;

    public FiscalContingenciaDrenoJob(SefazTransporte transporte,
                                      SefazAutorizadorService autorizadores,
                                      FiscalCertificadoService certificados,
                                      FiscalContingenciaService contingencia,
                                      DocumentoFiscalRepositorio repositorio) {
        this.transporte = transporte;
        this.autorizadores = autorizadores;
        this.certificados = certificados;
        this.contingencia = contingencia;
        this.repositorio = repositorio;
    }

    /** A cada 5 minutos: rápido o bastante para o prazo de 24 h, devagar o bastante para não pesar. */
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void drenar() {
        for (EmpresaEmContingencia empresa : repositorio.empresasComFilaPendente()) {
            try {
                TenantContext.comTenant(empresa.idTenant(), () -> drenarEmpresa(empresa));
            } catch (Exception e) {
                // Uma empresa com problema não pode impedir as outras de drenar.
                log.warn("Falha ao drenar contingência do tenant {} empresa {}: {}",
                        empresa.idTenant(), empresa.idEmpresa(), e.toString());
            }
        }
    }

    private void drenarEmpresa(EmpresaEmContingencia empresa) {
        // Antes de tentar a fila inteira, uma pergunta barata: a SEFAZ voltou? Sem isso, cada
        // rodada gastaria 20 timeouts de 10 s para descobrir o que uma chamada responde.
        if (!sefazRespondendo(empresa)) {
            return;
        }

        // A SEFAZ respondeu — a empresa sai da contingência. As notas já emitidas em tpEmis 9
        // continuam na fila e são transmitidas assim mesmo: o cupom entregue diz contingência, e
        // reemitir como normal geraria uma segunda nota para a mesma venda.
        if (contingencia.ativa(empresa.idEmpresa())) {
            contingencia.sair(empresa.idEmpresa(),
                    "Automática: SEFAZ voltou a responder (NFeStatusServico4).");
            log.info("Tenant {} empresa {} saiu da contingência.", empresa.idTenant(), empresa.idEmpresa());
        }

        for (NotaPendente nota : repositorio.pendentesDeContingencia(empresa.idEmpresa(), LOTE_POR_EMPRESA)) {
            transmitir(empresa, nota);
        }
    }

    private boolean sefazRespondendo(EmpresaEmContingencia empresa) {
        try {
            String url = autorizadores.urlDe(empresa.uf(), MODELO_NFCE, empresa.ambienteCodigo(),
                    ServicoSefaz.STATUS_SERVICO);
            CertificadoParaAssinatura cert = certificados.carregarAtivoParaAssinatura(empresa.idEmpresa());
            String consulta = ("<consStatServ versao=\"4.00\" xmlns=\"%s\">"
                    + "<tpAmb>%d</tpAmb><cUF>%d</cUF><xServ>STATUS</xServ></consStatServ>")
                    .formatted(MontadorXmlNfce.NS, empresa.ambienteCodigo(), empresa.codigoUfIbge());

            RespostaSefaz r = transporte.enviar(url, SERVICO_STATUS, consulta,
                    cert.pkcs12(), cert.senha(), empresa.impressaoDigital());
            return "107".equals(r.cStat());   // 107 = Serviço em Operação
        } catch (FalhaDeComunicacaoException e) {
            return false;
        } catch (Exception e) {
            log.warn("Não foi possível consultar o status da SEFAZ (tenant {}): {}",
                    empresa.idTenant(), e.toString());
            return false;
        }
    }

    private void transmitir(EmpresaEmContingencia empresa, NotaPendente nota) {
        try {
            String url = autorizadores.urlDe(empresa.uf(), MODELO_NFCE, empresa.ambienteCodigo(),
                    ServicoSefaz.AUTORIZACAO);
            CertificadoParaAssinatura cert = certificados.carregarAtivoParaAssinatura(empresa.idEmpresa());
            String enviNFe = ("<enviNFe versao=\"4.00\" xmlns=\"%s\">"
                    + "<idLote>%d</idLote><indSinc>1</indSinc>%s</enviNFe>")
                    .formatted(MontadorXmlNfce.NS, nota.id(), nota.xmlAssinado());

            repositorio.marcarTransmitindo(nota.id());
            RespostaSefaz resposta = transporte.enviar(url, SERVICO_AUTORIZACAO, enviNFe,
                    cert.pkcs12(), cert.senha(), empresa.impressaoDigital());

            if (resposta.autorizado()) {
                repositorio.marcarAutorizado(nota.id(), resposta);
                return;
            }
            if (resposta.emProcessamento()) {
                repositorio.registrarProcessamento(nota.id(), resposta);
                return;
            }

            // ⚠️ A pior situação do módulo (§9.7): a nota que o consumidor JÁ TEM na mão foi
            // rejeitada. O cupom entregue é inválido e não há como desfazer a entrega. Fica
            // gravado como rejeição normal — o alarme alto é da tela de Documentos Fiscais, que
            // precisa destacar rejeitado + tpEmis 9. Silenciar aqui esconderia exatamente o caso
            // que exige ação humana.
            boolean denegado = resposta.cStat() != null && resposta.cStat().startsWith("30");
            repositorio.marcarRecusado(nota.id(), resposta, denegado);
            log.error("NOTA EM CONTINGÊNCIA REJEITADA — cupom já entregue ao consumidor. "
                            + "tenant={} documento={} chave={} cStat={} motivo={}",
                    empresa.idTenant(), nota.id(), nota.chaveAcesso(), resposta.cStat(), resposta.xMotivo());

        } catch (FalhaDeComunicacaoException e) {
            // A SEFAZ respondeu ao status e caiu de novo: para a rodada desta empresa em vez de
            // insistir nas outras 19 notas. Volta na próxima.
            repositorio.registrarFalhaDeComunicacao(nota.id(), e.getMessage());
            throw e;
        }
    }

}
