package com.vetor.niner.fiscal.documento;

import com.vetor.niner.fiscal.certificado.FiscalCertificadoService;
import com.vetor.niner.fiscal.certificado.FiscalCertificadoService.CertificadoParaAssinatura;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoRepositorio.ContextoEmpresa;
import com.vetor.niner.fiscal.documento.FiscalInutilizacaoRepositorio.InutilizacaoItem;
import com.vetor.niner.fiscal.documento.MontagemInutilizacaoDtos.PedidoInutilizacao;
import com.vetor.niner.fiscal.documento.MontagemInutilizacaoDtos.XmlInutilizacaoMontado;
import com.vetor.niner.fiscal.sefaz.SefazAutorizadorService;
import com.vetor.niner.fiscal.sefaz.SefazDtos.FalhaDeComunicacaoException;
import com.vetor.niner.fiscal.sefaz.SefazDtos.RespostaSefaz;
import com.vetor.niner.fiscal.sefaz.SefazDtos.ServicoSefaz;
import com.vetor.niner.fiscal.sefaz.SefazTransporte;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Inutilização de faixa de numeração (§10.4, bloco B8) — {@code NFeInutilizacao4}.
 *
 * <p><b>Consequência inevitável de F4</b>: número alocado e nunca autorizado deixa buraco, que só
 * se resolve por inutilização formal. Diferente do cancelamento, aqui não há envelope de lote nem
 * evento vinculado a um documento — o pedido é autocontido (UF, CNPJ, ano, faixa, justificativa) e
 * a resposta da SEFAZ é síncrona.
 *
 * <p><b>F2 — nenhuma chamada de rede dentro de transação de banco:</b> este serviço roda inteiro
 * fora de transação; as leituras e a gravação da tentativa usam suas próprias transações curtas em
 * {@link FiscalInutilizacaoRepositorio}.
 */
@Service
public class FiscalInutilizacaoService {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private final FiscalInutilizacaoRepositorio repositorio;
    private final MontadorInutilizacaoNfe montador;
    private final AssinadorXmlNfe assinador;
    private final ValidadorXsd validador;
    private final SefazTransporte transporte;
    private final SefazAutorizadorService autorizadores;
    private final FiscalCertificadoService certificados;

    public FiscalInutilizacaoService(FiscalInutilizacaoRepositorio repositorio, MontadorInutilizacaoNfe montador,
                                     AssinadorXmlNfe assinador, ValidadorXsd validador, SefazTransporte transporte,
                                     SefazAutorizadorService autorizadores, FiscalCertificadoService certificados) {
        this.repositorio = repositorio;
        this.montador = montador;
        this.assinador = assinador;
        this.validador = validador;
        this.transporte = transporte;
        this.autorizadores = autorizadores;
        this.certificados = certificados;
    }

    /**
     * Buracos detectados sozinho (§10.4): números alocados na série que nunca viraram nota nem já
     * foram inutilizados, agrupados em faixas contíguas para a tela sugerir de uma vez.
     */
    public List<FaixaBuraco> detectarBuracos(Jwt jwt, long idEmpresa, int modelo, int serie) {
        exigirAdmin(jwt);
        return agrupar(repositorio.buracos(idEmpresa, modelo, serie));
    }

    public List<InutilizacaoItem> listar(Jwt jwt, long idEmpresa) {
        exigirAdmin(jwt);
        return repositorio.listar(idEmpresa);
    }

    /**
     * @throws ResponseStatusException 409 — faixa inválida, com número já usado por nota de
     *         verdade, já inutilizada, ainda não alocada, ou recusada pela SEFAZ
     */
    public ResultadoInutilizacao executar(Jwt jwt, long idEmpresa, int modelo, int serie, int numeroInicial,
                                          int numeroFinal, String justificativa) {
        exigirAdmin(jwt);
        int idUsuario = (int) Long.parseLong(jwt.getSubject());
        validarFaixaEJustificativa(numeroInicial, numeroFinal, justificativa);

        ContextoEmpresa contexto = repositorio.buscarContexto(idEmpresa).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT,
                        "Esta empresa ainda não tem configuração fiscal — configure antes de inutilizar numeração."));

        int proximo = repositorio.proximoNumero(idEmpresa, modelo, serie);
        if (numeroFinal >= proximo) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ("O número %d ainda não foi alocado (o próximo a sair nesta série é %d) — não há "
                            + "nota nenhuma para inutilizar aqui.").formatted(numeroFinal, proximo));
        }

        List<Integer> consumidos = repositorio.numerosJaConsumidos(idEmpresa, modelo, serie, numeroInicial, numeroFinal);
        if (!consumidos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ("A faixa inclui número(s) já usado(s) por nota autorizada, cancelada ou denegada "
                            + "(%s) — inutilização só vale para número que nunca virou nota.")
                            .formatted(consumidos));
        }

        if (repositorio.sobrepoeInutilizacaoExistente(idEmpresa, modelo, serie, numeroInicial, numeroFinal)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta faixa (ou parte dela) já foi inutilizada anteriormente.");
        }

        CertificadoParaAssinatura certificado = certificados.carregarAtivoParaAssinatura(idEmpresa);
        KeyStore keystore = abrir(certificado);

        int ano = OffsetDateTime.now(FUSO).getYear() % 100;
        XmlInutilizacaoMontado montado = montador.montar(new PedidoInutilizacao(
                contexto.ambiente(), contexto.uf(), contexto.cnpj(), ano, modelo, serie,
                numeroInicial, numeroFinal, justificativa));
        String xmlAssinado = assinador.assinarInutilizacao(montado.xml(), montado.id(), keystore, certificado.senha());
        validador.validarInutilizacao(xmlAssinado);

        String url = autorizadores.urlDe(contexto.uf(), modelo, contexto.ambiente().codigo(), ServicoSefaz.INUTILIZACAO);
        RespostaSefaz resposta;
        try {
            resposta = transporte.enviar(url, "NFeInutilizacao4", xmlAssinado,
                    certificado.pkcs12(), certificado.senha(), certificado.impressaoDigital());
        } catch (FalhaDeComunicacaoException e) {
            repositorio.registrarTentativa(idEmpresa, modelo, serie, ano, numeroInicial, numeroFinal,
                    justificativa, false, null, null, e.getMessage(), xmlAssinado, idUsuario);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não foi possível confirmar a inutilização junto à SEFAZ. Tente novamente em instantes.");
        }

        // 102 = Inutilização de número homologada (leiauteInutNFe_v4.00.xsd / retInutNFe).
        boolean homologada = "102".equals(resposta.cStat());
        repositorio.registrarTentativa(idEmpresa, modelo, serie, ano, numeroInicial, numeroFinal,
                justificativa, homologada, resposta.protocolo(), resposta.cStat(), resposta.xMotivo(),
                xmlAssinado, idUsuario);

        if (!homologada) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A SEFAZ recusou a inutilização: %s (%s)."
                            .formatted(resposta.xMotivo(), resposta.cStat()));
        }

        return new ResultadoInutilizacao(resposta.protocolo(), ano);
    }

    private static void validarFaixaEJustificativa(int numeroInicial, int numeroFinal, String justificativa) {
        if (numeroInicial < 1 || numeroFinal < numeroInicial) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Faixa de numeração inválida: o número inicial precisa ser maior que zero e o "
                            + "final não pode ser menor que o inicial.");
        }
        String texto = justificativa == null ? "" : justificativa.trim();
        if (texto.length() < 15 || texto.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A justificativa precisa ter entre 15 e 255 caracteres (exigência da SEFAZ).");
        }
    }

    /** Agrupa números soltos em faixas contíguas — {@code [3,4,5,9]} vira {@code [3-5, 9-9]}. */
    private static List<FaixaBuraco> agrupar(List<Integer> numeros) {
        List<FaixaBuraco> faixas = new ArrayList<>();
        int i = 0;
        while (i < numeros.size()) {
            int inicio = numeros.get(i);
            int fim = inicio;
            while (i + 1 < numeros.size() && numeros.get(i + 1) == fim + 1) {
                i++;
                fim = numeros.get(i);
            }
            faixas.add(new FaixaBuraco(inicio, fim));
            i++;
        }
        return faixas;
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem inutilizar numeração fiscal.");
        }
    }

    private static KeyStore abrir(CertificadoParaAssinatura certificado) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(certificado.pkcs12()), certificado.senha().toCharArray());
            return ks;
        } catch (Exception e) {
            throw new AssinadorXmlNfe.AssinaturaInvalidaException(
                    "O certificado digital da empresa não pôde ser aberto para assinar a inutilização: "
                            + e.getMessage(), e);
        }
    }

    public record FaixaBuraco(int numeroInicial, int numeroFinal) {
    }

    public record ResultadoInutilizacao(String protocolo, int ano) {
    }
}
