package com.vetor.niner.fiscal.nfse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A implementação real: Sefin Nacional, via REST + mTLS.
 *
 * <p>⚠️ Só entra com {@code niner.nfse.emissor=nacional}. O padrão é o {@link EmissorFalso} — ver
 * o javadoc de {@link EmissorDeNfse} para por que o padrão é o falso.
 *
 * <h2>Endereços</h2>
 *
 * <pre>
 *   produção            https://sefin.nfse.gov.br/SefinNacional
 *   produção restrita   https://sefin.producaorestrita.nfse.gov.br/SefinNacional
 * </pre>
 *
 * <p>⛔ <b>O ADN é outro host</b> ({@code adn.nfse.gov.br}) e serve o DANFSe e os parâmetros
 * municipais. O swagger do SEFIN declara em duas rotas <i>"este serviço foi movido"</i>, e chamar
 * {@code /parametros_municipais} nele devolve uma página HTML de 404 do IIS — foi assim que o
 * {@code MAPA.md} do {@code finance-v} ficou com o endereço errado. Quem fala com o ADN é o
 * {@link ParametrosMunicipaisClient}.
 */
@Component
@ConditionalOnProperty(name = "niner.nfse.emissor", havingValue = "nacional")
public class EmissorNacionalNfse implements EmissorDeNfse {

    private static final String SEFIN_PRODUCAO =
            "https://sefin.nfse.gov.br/SefinNacional";
    private static final String SEFIN_RESTRITA =
            "https://sefin.producaorestrita.nfse.gov.br/SefinNacional";

    /** 50 zeros: chave sintaticamente válida e inexistente, para o teste de conexão. */
    private static final String CHAVE_INEXISTENTE = "0".repeat(50);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NfseTransporte transporte;

    public EmissorNacionalNfse(NfseTransporte transporte) {
        this.transporte = transporte;
    }

    @Override
    public RespostaSefin emitir(EnvioDps dps) {
        ObjectNode corpo = JSON.createObjectNode();
        corpo.put("dpsXmlGZipB64", dps.xmlAssinado());
        return RespostaSefin.de(post(base(dps.credencial()) + "/nfse", corpo, dps.credencial()));
    }

    @Override
    public RespostaSefin registrarEvento(EnvioEvento evento) {
        ObjectNode corpo = JSON.createObjectNode();
        // ⚠️ A API aceita a forma curta (pedRegEventoXmlGZipB64) e a longa. Usamos a longa, que é
        // a documentada no swagger e a que o finance-v usa em produção.
        corpo.put("pedidoRegistroEventoXmlGZipB64", evento.xmlAssinado());
        String url = base(evento.credencial()) + "/nfse/" + evento.chaveAcesso() + "/eventos";
        return RespostaSefin.de(post(url, corpo, evento.credencial()));
    }

    @Override
    public String consultarChavePorDps(String idDps, Credencial credencial) {
        var retorno = transporte.get(base(credencial) + "/dps/" + idDps,
                credencial.pkcs12(), credencial.senha(), credencial.impressaoDigital());

        // 404 = a DPS ainda não virou NFS-e. É resposta legítima, não erro.
        if (retorno.status() == 404) {
            return null;
        }
        if (retorno.status() < 200 || retorno.status() >= 300) {
            throw new NfseTransporte.FalhaDeComunicacaoException(
                    "Não foi possível consultar a DPS no SEFIN (HTTP " + retorno.status() + ")",
                    null);
        }
        String corpo = retorno.corpo() == null ? "" : retorno.corpo().trim();
        try {
            var raiz = JSON.readTree(corpo);
            for (String campo : new String[] {"chaveAcesso", "chNFSe", "chAcesso"}) {
                var valor = raiz.get(campo);
                if (valor != null && !valor.isNull() && !valor.asText().isBlank()) {
                    return valor.asText().trim();
                }
            }
            return raiz.isTextual() ? raiz.asText().trim() : null;
        } catch (Exception naoEhJson) {
            // A resposta pode ser a própria chave em texto puro, com ou sem aspas.
            String limpo = corpo.replaceAll("^\"|\"$", "");
            return limpo.isBlank() ? null : limpo;
        }
    }

    @Override
    public RespostaSefin testarConexao(Credencial credencial) {
        var retorno = transporte.get(base(credencial) + "/nfse/" + CHAVE_INEXISTENTE,
                credencial.pkcs12(), credencial.senha(), credencial.impressaoDigital());
        return RespostaSefin.de(retorno);
    }

    private NfseTransporte.Retorno post(String url, ObjectNode corpo, Credencial credencial) {
        String json;
        try {
            json = JSON.writeValueAsString(corpo);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao montar o corpo JSON da NFS-e", e);
        }
        return transporte.postJson(url, json, credencial.pkcs12(), credencial.senha(),
                credencial.impressaoDigital());
    }

    private static String base(Credencial credencial) {
        return credencial.ambienteProducao() ? SEFIN_PRODUCAO : SEFIN_RESTRITA;
    }
}
