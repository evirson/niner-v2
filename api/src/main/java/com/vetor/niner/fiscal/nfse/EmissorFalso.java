package com.vetor.niner.fiscal.nfse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Emissor de mentira — o <b>padrão</b>, e é de propósito.
 *
 * <h2>Por que o falso é o padrão</h2>
 *
 * <p>Porque a alternativa já custou caro neste repositório. A suíte roda com
 * {@code emite_nfe = false}, e por isso o caminho fiscal da devolução ao fornecedor <b>não tem
 * teste</b> — foi assim que dois defeitos reais passaram por 911 testes verdes, um deles deixando
 * uma NF-e 55 autorizada contra uma saída que não aconteceu. Caminho fiscal sem teste não é
 * caminho testado: é caminho ausente.
 *
 * <p>Com este bean como padrão, toda a máquina da NFS-e — numeração, montagem, assinatura,
 * gravação, máquina de estados, guarda do XML, cancelamento — <b>roda na suíte</b>. O que fica de
 * fora é só o salto de rede, que é exatamente a parte que não se testa com teste automatizado
 * mesmo.
 *
 * <h2>⛔ O que ele NÃO faz, e por que isso importa</h2>
 *
 * <p>Ele <b>não valida o XML</b>. Não confere schema, não confere assinatura, não conhece as
 * regras de negócio do SEFIN. Um XML que passa aqui pode muito bem tomar {@code E1235} lá. Quem
 * prova aceitação é o servidor do governo, e isso foi feito uma vez, em produção, com a NFS-e
 * 7308 — nenhum teste automatizado substitui aquilo.
 *
 * <p>⚠️ A chave que ele devolve é <b>sintaticamente</b> igual à real (50 dígitos, com o município,
 * o CNPJ e o código aleatório nos lugares certos), justamente para que o resto do sistema não
 * ganhe o hábito de aceitar chave malformada. Mas ela não existe em prefeitura nenhuma.
 */
@Component
@ConditionalOnProperty(name = "niner.nfse.emissor", havingValue = "falso", matchIfMissing = true)
public class EmissorFalso implements EmissorDeNfse {

    private final SecureRandom aleatorio = new SecureRandom();
    /** idDps → chave, para o {@link #consultarChavePorDps} devolver o que a emissão gerou. */
    private final Map<String, String> emitidas = new ConcurrentHashMap<>();
    /** Chaves já canceladas, para o segundo cancelamento recusar como o SEFIN recusa (E0840). */
    private final Map<String, Boolean> canceladas = new ConcurrentHashMap<>();

    @Override
    public RespostaSefin emitir(EnvioDps dps) {
        String chave = chaveSintetica(dps.idDps());
        emitidas.put(dps.idDps(), chave);
        long numeroNfse = 1 + aleatorio.nextInt(999_999);

        String corpo = """
                {"tipoAmbiente":%d,"versaoAplicativo":"EmissorFalso","idDps":"NFS%s",\
                "chaveAcesso":"%s","nNFSe":"%d","alertas":null}"""
                .formatted(dps.credencial().ambienteProducao() ? 1 : 2, chave, chave, numeroNfse);

        return RespostaSefin.de(new NfseTransporte.Retorno(201, corpo));
    }

    @Override
    public RespostaSefin registrarEvento(EnvioEvento evento) {
        // ⭐ Reproduz o E0840 do SEFIN real: cancelar duas vezes é recusado. É o que permite ao
        // teste provar que o primeiro cancelamento REGISTROU, e não só que foi aceito.
        if (canceladas.putIfAbsent(evento.chaveAcesso(), true) != null) {
            String corpo = """
                    {"tipoAmbiente":1,"versaoAplicativo":"EmissorFalso",\
                    "erro":[{"codigo":"E0840","descricao":"O Sistema Nacional NFS-e não pode \
                    recepcionar o EVENTO DE CANCELAMENTO DE NFS-e, pois o evento de Cancelamento \
                    de NFS-e já está vinculado à NFS-e indicada no evento enviado."}]}""";
            return RespostaSefin.de(new NfseTransporte.Retorno(400, corpo));
        }
        String corpo = """
                {"tipoAmbiente":1,"versaoAplicativo":"EmissorFalso","eventoXmlGZipB64":"H4sIAAAA"}""";
        return RespostaSefin.de(new NfseTransporte.Retorno(201, corpo));
    }

    @Override
    public String consultarChavePorDps(String idDps, Credencial credencial) {
        return emitidas.get(idDps);
    }

    @Override
    public RespostaSefin testarConexao(Credencial credencial) {
        // A resposta que o SEFIN real dá para chave inexistente — e que é o sinal de SUCESSO.
        String corpo = """
                {"erros":[{"Codigo":"E2401","Descricao":"Chave de acesso não encontrada."}]}""";
        return RespostaSefin.de(new NfseTransporte.Retorno(404, corpo));
    }

    /**
     * Chave com a mesma <b>forma</b> da real (leiaute oficial, 50 dígitos):
     * cMun(7) + ambGer(1) + tpInsc(1) + inscrição(14) + nNFSe(13) + AAMM(4) + aleatório(9) + DV(1).
     */
    private String chaveSintetica(String idDps) {
        String municipio = idDps.substring(3, 10);
        String inscricao = idDps.substring(11, 25);
        LocalDate hoje = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
        String anoMes = "%02d%02d".formatted(hoje.getYear() % 100, hoje.getMonthValue());
        String numero = "%013d".formatted(1 + aleatorio.nextInt(9_999_999));
        String codigo = "%09d".formatted(aleatorio.nextInt(1_000_000_000));
        String semDv = municipio + "2" + "2" + inscricao + numero + anoMes + codigo;
        return semDv + aleatorio.nextInt(10);
    }
}
