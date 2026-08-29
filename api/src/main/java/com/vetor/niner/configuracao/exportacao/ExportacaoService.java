package com.vetor.niner.configuracao.exportacao;

import com.vetor.niner.configuracao.exportacao.ExportacaoDtos.TabelaExportavel;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Rotina de Exportação de Dados (Configurações) — irmã "de saída" da Rotina de Importação de
 * Dados (docs/telas/importacao-dados.md): em vez de trazer dado de um sistema anterior, exporta
 * o que já está no Niner para planilha Excel (gerada no navegador, mesmo padrão do CRM —
 * `write-excel-file`, ver `web/src/lib/crm.ts`). Só leitura, sem regra de negócio nenhuma —
 * cada tabela é uma única consulta, com os nomes de coluna já em português (viram o cabeçalho
 * da planilha direto, sem mapeamento nenhum no frontend) e datas/booleanos já formatados em SQL
 * (`to_char`/`CASE`) para chegar prontos pra exibir. ADMIN-only, mesmo padrão de
 * {@code ImportacaoService}.
 *
 * <p>Usa {@link JdbcTemplate#queryForList(String)} (não {@code JdbcClient}, convenção do resto
 * do domínio) justamente porque devolve {@code List<Map<String,Object>>} pronto — nenhuma das
 * 9 consultas tem parâmetro (RLS + filtro explícito de {@code id_tenant} já restringem ao
 * tenant atual, mesmo padrão de defesa em profundidade usado nos demais Services), então não
 * há necessidade de bind de parâmetro nem de escrever 9 {@code RowMapper} manuais.
 */
@Service
public class ExportacaoService {

    private final JdbcTemplate jdbcTemplate;

    public ExportacaoService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TabelaExportavel> listarTabelas(Jwt jwt) {
        exigirAdmin(jwt);
        return List.of(
                new TabelaExportavel("empresa", "Empresas"),
                new TabelaExportavel("cliente", "Clientes"),
                new TabelaExportavel("fornecedor", "Fornecedores"),
                new TabelaExportavel("funcionario", "Funcionários"),
                new TabelaExportavel("contas_receber", "Contas a Receber / Recebidas"),
                new TabelaExportavel("contas_pagar", "Contas a Pagar / Pagas"),
                new TabelaExportavel("codigo_barras", "Código de Barras"),
                new TabelaExportavel("plano_contas", "Plano de Contas"),
                new TabelaExportavel("estoque", "Estoque"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> exportar(Jwt jwt, String tabela) {
        exigirAdmin(jwt);
        return switch (tabela) {
            case "empresa" -> jdbcTemplate.queryForList(SQL_EMPRESA);
            case "cliente" -> jdbcTemplate.queryForList(SQL_CLIENTE);
            case "fornecedor" -> jdbcTemplate.queryForList(SQL_FORNECEDOR);
            case "funcionario" -> jdbcTemplate.queryForList(SQL_FUNCIONARIO);
            case "contas_receber" -> jdbcTemplate.queryForList(SQL_CONTAS_RECEBER);
            case "contas_pagar" -> jdbcTemplate.queryForList(SQL_CONTAS_PAGAR);
            case "codigo_barras" -> jdbcTemplate.queryForList(SQL_CODIGO_BARRAS);
            case "plano_contas" -> jdbcTemplate.queryForList(SQL_PLANO_CONTAS);
            case "estoque" -> jdbcTemplate.queryForList(SQL_ESTOQUE);
            default -> throw new IllegalArgumentException("Tabela de exportação desconhecida: \"" + tabela + "\".");
        };
    }

    private static void exigirAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Apenas administradores podem usar a rotina de exportação de dados.");
        }
    }

    private static final String SQL_EMPRESA = """
            SELECT codigo_empresa AS "Código", razao_social AS "Razão Social", nome_fantasia AS "Nome Fantasia",
                   cnpj AS "CNPJ", inscricao_estadual AS "Inscrição Estadual",
                   endereco AS "Endereço", numero AS "Número", complemento AS "Complemento",
                   bairro AS "Bairro", cidade AS "Cidade", estado AS "UF", cep AS "CEP",
                   telefone AS "Telefone", email AS "E-mail",
                   CASE WHEN ativo THEN 'SIM' ELSE 'NAO' END AS "Ativo",
                   to_char(criado_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI') AS "Cadastrado em"
            FROM empresa
            WHERE id_tenant = plataforma.tenant_atual()
            ORDER BY codigo_empresa
            """;

    private static final String SQL_CLIENTE = """
            SELECT c.nome AS "Nome/Razão Social",
                   CASE WHEN c.fisica_juridica THEN 'FISICA' ELSE 'JURIDICA' END AS "Tipo de Pessoa",
                   c.cpf_cnpj AS "CPF/CNPJ", cc.nome_categoria AS "Categoria", c.rg_ie AS "RG/IE",
                   to_char(c.data_nascimento, 'DD/MM/YYYY') AS "Data de Nascimento",
                   c.genero::text AS "Gênero", c.email AS "E-mail", c.telefone AS "Celular",
                   c.whatsapp AS "WhatsApp", c.instagram AS "Instagram", c.facebook AS "Facebook", c.tiktok AS "TikTok",
                   c.endereco AS "Endereço", c.numero AS "Número", c.complemento AS "Complemento",
                   c.bairro AS "Bairro", c.cidade AS "Cidade", c.estado AS "UF", c.cep AS "CEP",
                   c.limite_credito AS "Limite de Crédito",
                   CASE WHEN c.ativo THEN 'SIM' ELSE 'NAO' END AS "Ativo",
                   to_char(c.criado_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI') AS "Cadastrado em"
            FROM cliente c
            JOIN cfg_categoria_cliente cc ON cc.id_categoria_cliente = c.id_categoria_cliente AND cc.id_tenant = c.id_tenant
            WHERE c.id_tenant = plataforma.tenant_atual()
            ORDER BY c.nome
            """;

    private static final String SQL_FORNECEDOR = """
            SELECT f.razao_social AS "Razão Social", f.nome_fantasia AS "Nome Fantasia", f.cnpj AS "CNPJ",
                   f.inscricao_estadual AS "Inscrição Estadual",
                   (pc.id_plano_contas || ' - ' || pc.descricao) AS "Plano de Contas",
                   f.email AS "E-mail", f.telefone AS "Telefone",
                   f.endereco AS "Endereço", f.numero AS "Número", f.bairro AS "Bairro",
                   f.cidade AS "Cidade", f.estado AS "UF", f.cep AS "CEP",
                   CASE WHEN f.ativo THEN 'SIM' ELSE 'NAO' END AS "Ativo",
                   to_char(f.criado_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI') AS "Cadastrado em"
            FROM fornecedor f
            JOIN cfg_plano_contas pc ON pc.id_plano_contas = f.id_plano_contas AND pc.id_tenant = f.id_tenant
            WHERE f.id_tenant = plataforma.tenant_atual()
            ORDER BY f.razao_social
            """;

    private static final String SQL_FUNCIONARIO = """
            SELECT nome AS "Nome", cpf AS "CPF", telefone AS "Celular", cargo AS "Cargo",
                   perc_comissao AS "% Comissão",
                   CASE WHEN ativo THEN 'SIM' ELSE 'NAO' END AS "Ativo",
                   to_char(criado_em AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI') AS "Cadastrado em"
            FROM funcionario
            WHERE id_tenant = plataforma.tenant_atual()
            ORDER BY nome
            """;

    private static final String SQL_CONTAS_RECEBER = """
            SELECT cl.nome AS "Cliente", e.razao_social AS "Empresa", tc.nome_carteira AS "Carteira",
                   cr.numero_parcela AS "Nº Parcela",
                   to_char(cr.data_vencimento AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY') AS "Vencimento",
                   to_char(cr.data_recebimento AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY') AS "Recebimento",
                   cr.valor_receber AS "Valor a Receber", cr.valor_juros AS "Juros",
                   cr.valor_desconto AS "Desconto", cr.valor_recebido AS "Valor Recebido",
                   CASE WHEN cr.documento_recebido THEN 'RECEBIDO' ELSE 'EM ABERTO' END AS "Situação"
            FROM contas_receber cr
            JOIN venda v ON v.id_venda = cr.id_venda AND v.id_tenant = cr.id_tenant
            LEFT JOIN cliente cl ON cl.id_cliente = v.id_cliente AND cl.id_tenant = v.id_tenant
            JOIN empresa e ON e.id_empresa = v.id_empresa AND e.id_tenant = v.id_tenant
            JOIN tipo_carteira tc ON tc.id_carteira = cr.id_carteira AND tc.id_tenant = cr.id_tenant
            WHERE cr.id_tenant = plataforma.tenant_atual()
            ORDER BY cr.data_vencimento
            """;

    private static final String SQL_CONTAS_PAGAR = """
            SELECT f.razao_social AS "Fornecedor", e.razao_social AS "Empresa",
                   (pc.id_plano_contas || ' - ' || pc.descricao) AS "Plano de Contas",
                   cp.nota_fiscal AS "Nota Fiscal", cp.numero_duplicata AS "Duplicata",
                   to_char(cp.data_lancamento AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY') AS "Lançamento",
                   to_char(cp.data_vencimento AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY') AS "Vencimento",
                   to_char(cp.data_pagamento AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY') AS "Pagamento",
                   cp.valor_pagar AS "Valor a Pagar", cp.valor_pago AS "Valor Pago",
                   CASE WHEN cp.documento_pago THEN 'PAGO' ELSE 'EM ABERTO' END AS "Situação"
            FROM contas_pagar cp
            JOIN fornecedor f ON f.id_fornecedor = cp.id_fornecedor AND f.id_tenant = cp.id_tenant
            JOIN empresa e ON e.id_empresa = cp.id_empresa AND e.id_tenant = cp.id_tenant
            JOIN cfg_plano_contas pc ON pc.id_plano_contas = cp.id_plano_contas AND pc.id_tenant = cp.id_tenant
            WHERE cp.id_tenant = plataforma.tenant_atual()
            ORDER BY cp.data_vencimento
            """;

    private static final String SQL_CODIGO_BARRAS = """
            SELECT pb.sku AS "SKU (Código Interno)", pb.ean AS "EAN",
                   p.descricao AS "Produto", p.marca AS "Marca", p.referencia AS "Referência",
                   co.descricao AS "Cor", ta.descricao AS "Tamanho",
                   p.preco_venda AS "Preço de Venda"
            FROM produto_barra pb
            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
            -- Só MERCADORIA: esta planilha existe para levar códigos de barras para outro sistema
            -- ou para conferência de prateleira, e serviço não tem prateleira (V085, bloco S1).
            WHERE pb.id_tenant = plataforma.tenant_atual() AND pb.tipo_item = 'MERCADORIA'
            ORDER BY p.descricao
            """;

    private static final String SQL_PLANO_CONTAS = """
            SELECT id_plano_contas AS "Código", descricao AS "Descrição", descricao_curta AS "Descrição Curta",
                   tipo_movimento::text AS "Tipo de Movimento", natureza::text AS "Natureza", nivel AS "Nível",
                   CASE WHEN inclui_dre THEN 'SIM' ELSE 'NAO' END AS "Compõe DRE", grupo_dre::text AS "Grupo DRE",
                   CASE WHEN inclui_fluxo_caixa THEN 'SIM' ELSE 'NAO' END AS "Compõe Fluxo de Caixa",
                   grupo_dfc::text AS "Grupo DFC",
                   CASE WHEN ativo THEN 'SIM' ELSE 'NAO' END AS "Ativo"
            FROM cfg_plano_contas
            WHERE id_tenant = plataforma.tenant_atual()
            ORDER BY id_plano_contas
            """;

    private static final String SQL_ESTOQUE = """
            SELECT e.razao_social AS "Empresa", p.descricao AS "Produto", p.marca AS "Marca", p.referencia AS "Referência",
                   pb.sku AS "SKU", co.descricao AS "Cor", ta.descricao AS "Tamanho",
                   pe.qtd_estoque AS "Qtd. em Estoque", pe.reservado AS "Reservado", pe.disponivel AS "Disponível",
                   pe.minimo AS "Estoque Mínimo"
            -- ⭐ Serviço não precisa de filtro AQUI: esta consulta parte de `produto_estoque`, e
            -- serviço nunca ganha linha lá (a trigger sai antes do UPSERT, V086). A exclusão é
            -- estrutural, não um predicado que alguém pode esquecer ao mexer — que é o mais
            -- seguro dos dois jeitos.
            FROM produto_estoque pe
            JOIN empresa e ON e.id_empresa = pe.id_empresa AND e.id_tenant = pe.id_tenant
            JOIN produto_barra pb ON pb.id_variacao = pe.id_variacao AND pb.id_tenant = pe.id_tenant
            JOIN produto p ON p.id_produto = pb.id_produto AND p.id_tenant = pb.id_tenant
            LEFT JOIN cfg_cor co ON co.id_cor = pb.id_cor AND co.id_tenant = pb.id_tenant AND co.id_cor <> 1
            LEFT JOIN cfg_tamanho ta ON ta.id_tamanho = pb.id_tamanho AND ta.id_tenant = pb.id_tenant AND ta.id_tamanho <> 1
            WHERE pe.id_tenant = plataforma.tenant_atual()
            ORDER BY e.razao_social, p.descricao
            """;
}
