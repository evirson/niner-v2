/**
 * Configuração de Etiqueta de Produtos (2026-08-04, docs/telas/configuracao-etiqueta.md) —
 * layout de impressão da etiqueta de código de barras: dimensões do rolo/etiqueta/bordas
 * ({@code cfg_etiqueta_config}), posição de cada coluna no rolo físico
 * ({@code cfg_etiqueta_coluna}), e os campos impressos com posição x/y livre e estilo
 * (fonte/tamanho/negrito/fundo preto) dentro da própria etiqueta ({@code cfg_etiqueta_campo}).
 * Schema: {@code db/migration/V029__cfg_etiqueta.sql}.
 *
 * <p>Cadastro comum, por tenant — mas um tenant pode ter <b>várias</b> configurações nomeadas
 * (impressoras/rolos diferentes por loja/situação), diferente do singleton de
 * {@link com.vetor.niner.configuracao.geral}. Tela ADMIN-only (grupo "Configurações" do menu,
 * junto de Usuários/Parâmetros do Sistema).
 *
 * <p><b>Coleções filhas</b> ({@code colunas}/{@code campos}) são salvas com o mesmo padrão de
 * {@code ProdutoService.salvarCategorias}: apaga tudo e reinsere dentro da mesma transação do
 * cabeçalho — nunca diff/patch individual. Diferente de {@code produto_categoria} (que referencia
 * uma categoria já cadastrada por id), aqui cada linha da coleção É o dado inteiro (posição,
 * estilo) — não existe catálogo separado por trás.
 *
 * <p><b>Sem fallback de inativar no DELETE:</b> ao contrário de Produto/Plano de Contas, hoje
 * nenhuma outra tabela referencia {@code cfg_etiqueta_config} por FK — a tela de "Emissão de
 * Etiqueta de Produtos" que vai consumir isso ainda não existe (Implementações Futuras). A
 * coluna {@code ativo} existe só como um "desativar sem apagar" editável direto no formulário,
 * não como resultado de uma checagem de dependência (que hoje não existe pra checar).
 *
 * <p><b>{@code fonte_etiqueta} é provisório</b> (ver comentário do tipo em V029) — o conjunto
 * real de fontes depende de uma decisão de tecnologia de impressão ainda em aberto (impressora
 * térmica dedicada ZPL/EPL vs. impressão via navegador).
 */
package com.vetor.niner.configuracao.etiqueta;
