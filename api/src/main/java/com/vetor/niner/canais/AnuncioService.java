package com.vetor.niner.canais;

import com.vetor.niner.canais.AnuncioDtos.AnuncioParaVincular;
import com.vetor.niner.canais.AnuncioDtos.LinhaParaVincular;
import com.vetor.niner.canais.AnuncioDtos.VariacaoDoErp;
import com.vetor.niner.canais.AnuncioDtos.VinculoGravado;
import com.vetor.niner.canais.AnuncioDtos.VinculoRequest;
import com.vetor.niner.canais.AnuncioRepositorio.DadosDoCanal;
import com.vetor.niner.canais.AnuncioRepositorio.Vinculo;
import com.vetor.niner.canais.CanalDeVenda.AnuncioDoCanal;
import com.vetor.niner.canais.CanalDeVenda.CanalIndisponivelException;
import com.vetor.niner.canais.CanalDeVenda.VariacaoDoCanal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vínculo anúncio ↔ variação do ERP (R6, bloco M2) — o de-para sem o qual nada mais anda.
 *
 * <h2>⚠️ Uma chamada a terceiro no meio de uma requisição — e o cuidado que ela exige</h2>
 *
 * Esta é a <b>única</b> parte da integração que fala com o marketplace dentro de uma requisição de
 * usuário, e é legítimo: o lojista está olhando a tela e precisa ver os anúncios <i>dele</i>, que
 * só o canal sabe quais são. O javadoc de {@link CanalDeVenda} proíbe isso para <b>escrita</b>
 * (que vai pelo outbox, P2); leitura para montar a tela é o caso de uso previsto de
 * {@code listarAnuncios}.
 *
 * <p>⛔ <b>A classe inteira não tem {@code @Transactional}</b>, e isso é decisão, não esquecimento:
 * a chamada HTTP ao ML acontece no meio dela, e segurar uma transação (e uma conexão do pool)
 * esperando um terceiro é o defeito apontado na auditoria de 2026-08-21 em
 * {@code CobrancaService.iniciarPagamento}. Quem transaciona é {@link AnuncioRepositorio}, um
 * bean à parte — <b>tem</b> que ser à parte, porque {@code @Transactional} não vale em
 * auto-invocação.
 *
 * <h2>⭐ A sugestão automática por SKU</h2>
 *
 * O ML guarda um campo livre de SKU do vendedor ({@code seller_custom_field}). Quando ele bate com
 * o SKU (ou o EAN) do ERP, a linha já vem com o vínculo sugerido. Não é enfeite: vincular 600
 * anúncios a dedo é uma tarde perdida, e errar a linha é caro — o saldo publicado passa a ser de
 * outro produto.
 *
 * <p>⚠️ <b>Sugerir, nunca vincular sozinho.</b> Quem confirma é o lojista. Um vínculo criado sem
 * ninguém olhar, por coincidência de texto, publicaria saldo errado sem nada na tela dizendo de
 * onde aquilo veio.
 */
@Service
public class AnuncioService {

    private final AnuncioRepositorio repositorio;
    private final CredenciaisCanalRepositorio credenciais;
    private final List<CanalDeVenda> adapters;

    public AnuncioService(AnuncioRepositorio repositorio, CredenciaisCanalRepositorio credenciais,
                          List<CanalDeVenda> adapters) {
        this.repositorio = repositorio;
        this.credenciais = credenciais;
        this.adapters = adapters;
    }

    // -------------------------------------------------------------------- listar para vincular

    /**
     * Os anúncios da conta conectada, cada um já com o vínculo atual (se houver) e a sugestão por
     * SKU (se casar).
     */
    public AnuncioParaVincular listar(Jwt jwt, long idCanal, int pagina, int limite) {
        exigirAdmin(jwt);

        DadosDoCanal canal = repositorio.buscarCanal(idCanal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Canal não encontrado."));
        CredenciaisCanal cred = credenciais.carregar(idCanal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Este canal não está conectado. Use \"Conectar\" na tela de Canais de Venda "
                                + "antes de vincular anúncios."));

        // ⛔ Fora de transação — ver o javadoc da classe.
        List<AnuncioDoCanal> doCanal;
        try {
            doCanal = adapterDe(canal.tipo()).listarAnuncios(cred, pagina, limite);
        } catch (CanalIndisponivelException e) {
            // ⚠️ 502, não 500: a falha é do terceiro, e a mensagem tem de dizer isso — senão o
            // lojista procura problema no ERP, que é o único lugar onde ele não está.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "O Mercado Livre não respondeu agora. Tente novamente em alguns minutos.");
        }

        Map<String, Vinculo> vinculos = repositorio.vinculosDoCanal(idCanal);
        List<LinhaParaVincular> linhas = new ArrayList<>();

        for (AnuncioDoCanal anuncio : doCanal) {
            if (anuncio.temVariacoes()) {
                for (VariacaoDoCanal v : anuncio.variacoes()) {
                    linhas.add(montar(anuncio, v.idExterno(), v.descricao(), v.sku(), v.preco(),
                            v.quantidadeDisponivel(), vinculos));
                }
            } else {
                linhas.add(montar(anuncio, null, null, anuncio.sku(), anuncio.preco(),
                        anuncio.quantidadeDisponivel(), vinculos));
            }
        }
        aplicarSugestoes(linhas);
        return new AnuncioParaVincular(idCanal, canal.nome(), pagina, linhas);
    }

    private static LinhaParaVincular montar(AnuncioDoCanal anuncio, String idExternoVariacao,
                                            String descricaoVariacao, String sku, BigDecimal preco,
                                            int quantidade, Map<String, Vinculo> vinculos) {
        Vinculo atual = vinculos.get(AnuncioRepositorio.chave(anuncio.idExterno(), idExternoVariacao));
        return new LinhaParaVincular(
                anuncio.idExterno(), idExternoVariacao, anuncio.titulo(), descricaoVariacao,
                sku, preco, quantidade, anuncio.status(),
                atual == null ? null : atual.idAnuncio(),
                atual == null ? null : atual.idVariacao(),
                atual == null ? null : atual.descricaoVariacao(),
                null, null);
    }

    /** Preenche a sugestão das linhas <b>ainda não vinculadas</b>. */
    private void aplicarSugestoes(List<LinhaParaVincular> linhas) {
        List<String> skus = linhas.stream()
                .filter(l -> l.idVariacao() == null && l.sku() != null && !l.sku().isBlank())
                .map(l -> AnuncioRepositorio.normalizar(l.sku()))
                .distinct()
                .toList();

        Map<String, VariacaoDoErp> porSku = new HashMap<>();
        for (VariacaoDoErp v : repositorio.buscarPorSkus(skus)) {
            porSku.put(AnuncioRepositorio.normalizar(v.sku()), v);
        }
        if (porSku.isEmpty()) {
            return;
        }

        for (int i = 0; i < linhas.size(); i++) {
            LinhaParaVincular l = linhas.get(i);
            if (l.idVariacao() != null || l.sku() == null || l.sku().isBlank()) {
                continue;
            }
            VariacaoDoErp sugerida = porSku.get(AnuncioRepositorio.normalizar(l.sku()));
            if (sugerida != null) {
                linhas.set(i, l.comSugestao(sugerida.idVariacao(), sugerida.descricao()));
            }
        }
    }

    // -------------------------------------------------------------------------------- vincular

    /**
     * Cria o vínculo e já calcula o preço que este canal publica.
     *
     * <p>⚠️ O preço deriva de {@code produto.preco_venda}, <b>nunca de {@code preco_oferta}</b> —
     * decisão do dono do produto (§8.6): promoção de fim de semana no balcão não derruba o preço
     * do anúncio. E nasce com {@code preco_manual = false}, acompanhando o reajuste da loja até o
     * dia em que o lojista o digitar (§8.4).
     */
    public void vincular(Jwt jwt, long idCanal, VinculoRequest req) {
        exigirAdmin(jwt);
        DadosDoCanal canal = repositorio.buscarCanal(idCanal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Canal não encontrado."));

        BigDecimal precoDeVendaDaLoja = repositorio.precoDeVendaDaVariacao(req.idVariacao())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado."));

        BigDecimal preco = PrecoDoCanal.derivar(precoDeVendaDaLoja, canal.percPreco());

        try {
            repositorio.inserir(idCanal, req.idVariacao(), req.idExterno(),
                    vazioViraNulo(req.idExternoVariacao()), preco);
        } catch (DuplicateKeyException e) {
            // ⚠️ Duas restrições diferentes da V066 caem aqui, e a mensagem tem de dizer QUAL —
            // "registro duplicado" mandaria o lojista procurar no lugar errado.
            throw new ResponseStatusException(HttpStatus.CONFLICT, motivoDoConflito(idCanal, req));
        }
    }

    private String motivoDoConflito(long idCanal, VinculoRequest req) {
        if (repositorio.variacaoDoErpJaVinculada(idCanal, req.idVariacao())) {
            return "Este produto já está vinculado a outro anúncio deste canal. "
                    + "Um produto só pode alimentar um anúncio por canal — senão o mesmo saldo "
                    + "seria publicado duas vezes e a loja prometeria o dobro do que tem.";
        }
        return "Este anúncio já está vinculado a outro produto. Desvincule antes de trocar.";
    }

    /**
     * Os vínculos já gravados — <b>sem</b> falar com o marketplace.
     *
     * <p>⭐ É o que mantém a tela útil quando o ML está fora do ar: o lojista continua vendo e
     * podendo desfazer o que vinculou. Uma tela que só sabe se mostrar consultando o terceiro fica
     * inútil exatamente no dia em que ele falha.
     */
    public List<VinculoGravado> listarVinculos(Jwt jwt, long idCanal) {
        exigirAdmin(jwt);
        repositorio.buscarCanal(idCanal)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Canal não encontrado."));
        return repositorio.listarVinculos(idCanal);
    }

    public void desvincular(Jwt jwt, long idCanal, long idAnuncio) {
        exigirAdmin(jwt);
        if (repositorio.excluir(idCanal, idAnuncio) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vínculo não encontrado.");
        }
    }

    // ------------------------------------------------------------------------------ auxiliares

    /**
     * ⚠️ String vazia vinda do front não é o mesmo que ausência. Gravar {@code ""} em
     * {@code id_externo_variacao} faria a UNIQUE tratá-la como uma variação de verdade, e o
     * anúncio simples poderia ser vinculado duas vezes — uma com {@code NULL} e outra com
     * {@code ""}, publicando o saldo de dois produtos no mesmo lugar.
     */
    private static String vazioViraNulo(String valor) {
        return valor == null || valor.isBlank() ? null : valor;
    }

    private CanalDeVenda adapterDe(String tipo) {
        return adapters.stream()
                .filter(a -> a.tipo().name().equals(tipo))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                        "A integração com este canal ainda não existe."));
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem vincular anúncios.");
        }
    }
}
