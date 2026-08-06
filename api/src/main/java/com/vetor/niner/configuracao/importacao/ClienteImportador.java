package com.vetor.niner.configuracao.importacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.vetor.niner.cadastros.cliente.CategoriaClienteDtos.CategoriaRequest;
import com.vetor.niner.cadastros.cliente.CategoriaClienteService;
import com.vetor.niner.cadastros.cliente.ClienteDtos.ClienteRequest;
import com.vetor.niner.cadastros.cliente.ClienteDtos.Genero;
import com.vetor.niner.cadastros.cliente.ClienteService;
import com.vetor.niner.cadastros.cliente.Documentos;
import com.vetor.niner.configuracao.importacao.ImportacaoCsv.LinhaCsv;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.LinhaErro;
import com.vetor.niner.configuracao.importacao.ImportacaoDtos.RelatorioImportacao;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Importação de {@code cliente} (docs/telas/importacao-dados.md, seção "1. cliente"). Uma
 * categoria única (existente, escolhida por id, ou nova, criada na hora) vale para todo o
 * arquivo. Dedup por CPF/CNPJ: já existe no tenant → reaproveita (não duplica, não sobrescreve);
 * CPF/CNPJ vazio → sempre cria (reenviar o arquivo duplica esses, aviso fixo da tela).
 */
@Service
public class ClienteImportador implements ImportadorDeTabela {

    private final JdbcClient jdbc;
    private final ClienteService clienteService;
    private final CategoriaClienteService categoriaClienteService;

    public ClienteImportador(JdbcClient jdbc, ClienteService clienteService, CategoriaClienteService categoriaClienteService) {
        this.jdbc = jdbc;
        this.clienteService = clienteService;
        this.categoriaClienteService = categoriaClienteService;
    }

    @Override
    public String chave() {
        return "cliente";
    }

    @Override
    public String titulo() {
        return "Clientes";
    }

    @Override
    public String descricao() {
        return "Cadastro de clientes (pessoa física ou jurídica). Pede uma categoria (existente ou nova) para o arquivo inteiro.";
    }

    @Override
    public byte[] modeloCsv() {
        String cabecalho = "NOME;TIPO_PESSOA;CPF_CNPJ;RG_IE;DATA_NASCIMENTO;GENERO;EMAIL;TELEFONE;"
                + "ENDERECO;NUMERO;COMPLEMENTO;BAIRRO;CIDADE;ESTADO;CEP;LIMITE_CREDITO";
        String exemploPf = "MARIA DA SILVA;FISICA;123.456.789-09;;15/03/1990;FEMININO;maria@email.com;"
                + "(11) 99999-8888;AV PAULISTA;1000;APTO 12;BELA VISTA;SAO PAULO;SP;01310-000;500,00";
        String exemploPj = "COMERCIO DE ROUPAS LTDA;JURIDICA;12.345.678/0001-95;;;;contato@comercio.com;"
                + "(11) 3333-4444;;;;;;;0";
        return (cabecalho + "\r\n" + exemploPf + "\r\n" + exemploPj + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @Transactional
    public RelatorioImportacao processar(List<LinhaCsv> linhas, JsonNode escolhas, boolean confirmar, Jwt jwt) {
        int idCategoria = resolverCategoria(escolhas);

        int importadas = 0, ignoradas = 0, rejeitadas = 0;
        List<LinhaErro> erros = new ArrayList<>();

        for (LinhaCsv linha : linhas) {
            try {
                String cpfCnpjNormalizado = Documentos.somenteAlfanumerico(linha.valor("CPF_CNPJ"));
                if (cpfCnpjNormalizado != null && !cpfCnpjNormalizado.isBlank() && jaExiste(cpfCnpjNormalizado)) {
                    ignoradas++;
                    continue;
                }
                clienteService.criar(montarRequest(linha, idCategoria));
                importadas++;
            } catch (RuntimeException e) {
                rejeitadas++;
                erros.add(new LinhaErro(linha.numeroLinha(), mensagem(e)));
            }
        }

        RelatorioImportacao relatorio = new RelatorioImportacao(
                confirmar, linhas.size(), importadas, ignoradas, rejeitadas, erros, List.of(), List.of());
        if (!confirmar) {
            throw new SimulacaoConcluidaException(relatorio);
        }
        return relatorio;
    }

    /** {@code escolhas}: {@code {"idCategoriaCliente": 3}} OU {@code {"novaCategoria": "VAREJO"}}. */
    private int resolverCategoria(JsonNode escolhas) {
        if (escolhas != null && escolhas.hasNonNull("idCategoriaCliente")) {
            return escolhas.get("idCategoriaCliente").asInt();
        }
        if (escolhas != null && escolhas.hasNonNull("novaCategoria")) {
            String nome = escolhas.get("novaCategoria").asText("").trim();
            if (nome.isBlank()) {
                throw new IllegalArgumentException("Informe o nome da nova categoria de cliente.");
            }
            String nomeNormalizado = nome.toUpperCase(Locale.ROOT);
            Optional<Long> existente = jdbc.sql("""
                            SELECT id_categoria_cliente FROM cfg_categoria_cliente
                            WHERE id_tenant = plataforma.tenant_atual() AND nome_categoria = ?
                            """)
                    .param(nomeNormalizado)
                    .query(Long.class).optional();
            long id = existente.orElseGet(() -> categoriaClienteService.criar(new CategoriaRequest(nome)).idCategoriaCliente());
            return Math.toIntExact(id);
        }
        throw new IllegalArgumentException(
                "Escolha uma categoria de cliente existente ou informe o nome de uma nova antes de importar.");
    }

    private boolean jaExiste(String cpfCnpjNormalizado) {
        return jdbc.sql("""
                        SELECT id_cliente FROM cliente
                        WHERE id_tenant = plataforma.tenant_atual() AND cpf_cnpj = ?
                        """)
                .param(cpfCnpjNormalizado)
                .query(Long.class).optional().isPresent();
    }

    private static ClienteRequest montarRequest(LinhaCsv linha, int idCategoria) {
        String tipoPessoa = linha.valor("TIPO_PESSOA");
        boolean fisicaJuridica = !"JURIDICA".equalsIgnoreCase(tipoPessoa);
        String generoTxt = linha.valor("GENERO");
        Genero genero;
        try {
            genero = generoTxt == null ? null : Genero.valueOf(generoTxt.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Gênero inválido: \"" + generoTxt + "\" (use MASCULINO, FEMININO ou OUTROS).");
        }
        String nome = linha.valor("NOME");
        if (nome == null) {
            throw new IllegalArgumentException("NOME é obrigatório.");
        }
        return new ClienteRequest(
                fisicaJuridica,
                nome,
                idCategoria,
                linha.valor("CPF_CNPJ"),
                linha.valor("RG_IE"),
                ImportacaoCsv.data(linha.valor("DATA_NASCIMENTO")),
                genero,
                linha.valor("EMAIL"),
                linha.valor("TELEFONE"),
                null, null, null, null, // whatsapp/instagram/facebook/tiktok fora do escopo desta importação
                linha.valor("CEP"),
                linha.valor("ENDERECO"),
                linha.valor("NUMERO"),
                linha.valor("COMPLEMENTO"),
                linha.valor("BAIRRO"),
                linha.valor("CIDADE"),
                linha.valor("ESTADO"),
                ImportacaoCsv.decimal(linha.valor("LIMITE_CREDITO")),
                true);
    }

    private static String mensagem(RuntimeException e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? "Erro ao processar a linha (" + e.getClass().getSimpleName() + ")." : msg;
    }
}
