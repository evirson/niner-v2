package com.vetor.niner.catalogo;

import com.vetor.niner.catalogo.ProdutoImagemDtos.ImagemResponse;
import com.vetor.niner.comum.armazenamento.ArmazenamentoDeArquivos;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Galeria de fotos de produto (docs/infra/armazenamento-imagens.md, ADR-013). Tabela {@code
 * produto_imagem} (V017) sob RLS de tenant. Máximo de <b>6 fotos por produto</b> (regra de
 * produto, 2026-07-23) — checado aqui, não no banco (não há CHECK que conte irmãos sem
 * trigger). Upload sempre normaliza pra WebP redimensionado (maior lado ≤ 1600px), batendo
 * com o contrato do handoff — {@code org.sejda.imageio:webp-imageio} registra o writer WebP
 * via SPI (ImageIO puro não grava WebP sozinho); {@code Thumbnails.outputFormat("webp")} usa
 * esse writer por baixo.
 */
@Service
public class ProdutoImagemService {

    private static final Logger log = LoggerFactory.getLogger(ProdutoImagemService.class);
    private static final int MAX_IMAGENS_POR_PRODUTO = 6;
    private static final int MAIOR_DIMENSAO = 1600;

    private final JdbcClient jdbc;
    private final ArmazenamentoDeArquivos armazenamento;

    public ProdutoImagemService(JdbcClient jdbc, ArmazenamentoDeArquivos armazenamento) {
        this.jdbc = jdbc;
        this.armazenamento = armazenamento;
    }

    @Transactional(readOnly = true)
    public List<ImagemResponse> listar(long idProduto) {
        return jdbc.sql("""
                        SELECT id_produto_imagem, imagem, indice FROM produto_imagem
                        WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?
                        ORDER BY indice
                        """)
                .param(idProduto)
                .query(this::mapear)
                .list();
    }

    @Transactional
    public List<ImagemResponse> adicionar(long idProduto, MultipartFile arquivo) {
        exigirProdutoExistente(idProduto);
        long total = contarImagens(idProduto);
        if (total >= MAX_IMAGENS_POR_PRODUTO) {
            throw new IllegalArgumentException("Produto já tem o máximo de " + MAX_IMAGENS_POR_PRODUTO + " fotos.");
        }

        byte[] normalizado = normalizar(lerBytes(arquivo));
        String chave = armazenamento.gravar(idProduto, normalizado, "webp");
        jdbc.sql("""
                        INSERT INTO produto_imagem (id_tenant, id_produto, indice, imagem)
                        VALUES (plataforma.tenant_atual(), ?, ?, ?)
                        """)
                .params(idProduto, (int) total, chave)
                .update();
        return listar(idProduto);
    }

    /**
     * Ordem do contrato (handoff §4.4): apaga a **linha** antes do **objeto** no bucket — erra
     * pro lado barato (objeto órfão custa dinheiro em silêncio; linha órfã quebraria a tela).
     */
    @Transactional
    public List<ImagemResponse> excluir(long idProduto, long idImagem) {
        String chave = jdbc.sql("""
                        SELECT imagem FROM produto_imagem
                        WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ? AND id_produto_imagem = ?
                        """)
                .params(idProduto, idImagem)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Imagem não encontrada."));

        jdbc.sql("""
                        DELETE FROM produto_imagem
                        WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ? AND id_produto_imagem = ?
                        """)
                .params(idProduto, idImagem)
                .update();
        armazenamento.apagar(chave);
        renumerar(idProduto);
        return listar(idProduto);
    }

    /**
     * Substitui a ordem pela lista completa de IDs recebida (mesmo princípio de "substitui
     * tudo" de {@code produto_categoria.salvarCategorias}, mas aqui é sempre UPDATE — as
     * linhas já existem, só o índice muda). Passa primeiro por índices negativos temporários
     * porque a UNIQUE (id_tenant, id_produto, indice) é checada por statement, não no commit —
     * uma troca direta entre dois índices já existentes colidiria no meio da transação.
     */
    @Transactional
    public List<ImagemResponse> reordenar(long idProduto, List<Long> idsImagem) {
        List<ImagemResponse> atuais = listar(idProduto);
        if (idsImagem.size() != atuais.size()
                || !atuais.stream().map(ImagemResponse::idImagem).sorted().toList()
                        .equals(idsImagem.stream().sorted().toList())) {
            throw new IllegalArgumentException(
                    "A lista precisa conter exatamente as imagens já existentes do produto.");
        }
        for (int i = 0; i < idsImagem.size(); i++) {
            atualizarIndice(idProduto, idsImagem.get(i), -(i + 1));
        }
        for (int i = 0; i < idsImagem.size(); i++) {
            atualizarIndice(idProduto, idsImagem.get(i), i);
        }
        return listar(idProduto);
    }

    private void atualizarIndice(long idProduto, long idImagem, int indice) {
        jdbc.sql("""
                        UPDATE produto_imagem SET indice = ?
                        WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ? AND id_produto_imagem = ?
                        """)
                .params(indice, idProduto, idImagem)
                .update();
    }

    /**
     * Compacta os índices pra 0..n-1 depois de uma exclusão. Processar em ordem ascendente é
     * seguro sem índice temporário: o alvo de cada linha é sempre ≤ seu índice atual e menor
     * que o índice atual de qualquer linha ainda não processada, então nunca colide.
     */
    private void renumerar(long idProduto) {
        List<ImagemResponse> restantes = listar(idProduto);
        for (int i = 0; i < restantes.size(); i++) {
            ImagemResponse img = restantes.get(i);
            if (img.indice() != i) {
                atualizarIndice(idProduto, img.idImagem(), i);
            }
        }
    }

    private void exigirProdutoExistente(long idProduto) {
        boolean existe = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM produto
                                       WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?)
                        """)
                .param(idProduto).query(Boolean.class).single());
        if (!existe) {
            throw new ResponseStatusException(NOT_FOUND, "Produto não encontrado.");
        }
    }

    private long contarImagens(long idProduto) {
        return jdbc.sql("""
                        SELECT count(*) FROM produto_imagem
                        WHERE id_tenant = plataforma.tenant_atual() AND id_produto = ?
                        """)
                .param(idProduto).query(Long.class).single();
    }

    private static byte[] lerBytes(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Valida por magic bytes (nunca pelo Content-Type/extensão enviados pelo cliente) e
     * redimensiona+recodifica — nunca grava o arquivo bruto recebido. */
    private byte[] normalizar(byte[] original) {
        validarFormatoImagem(original);
        try {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(original))
                    .size(MAIOR_DIMENSAO, MAIOR_DIMENSAO)
                    .outputFormat("webp")
                    .outputQuality(0.85)
                    .toOutputStream(saida);
            return saida.toByteArray();
        } catch (IOException e) {
            log.warn("Falha ao normalizar imagem de produto", e);
            throw new IllegalArgumentException("Não foi possível processar o arquivo como imagem.");
        }
    }

    private static void validarFormatoImagem(byte[] b) {
        boolean jpeg = b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        boolean png = b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
        boolean webp = b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        if (!jpeg && !png && !webp) {
            throw new IllegalArgumentException("Arquivo não é uma imagem JPEG, PNG ou WebP válida.");
        }
    }

    private ImagemResponse mapear(ResultSet rs, int rowNum) throws SQLException {
        String chave = rs.getString("imagem");
        return new ImagemResponse(rs.getLong("id_produto_imagem"), armazenamento.urlPublica(chave), rs.getInt("indice"));
    }
}
