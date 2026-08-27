import type { ReactElement } from 'react'
import { chaveDaRota } from './permissoes'
import {
  IconeCanais,
  IconeCancelamentoVenda,
  IconeCertificado,
  IconeCliente,
  IconeConfirmar,
  IconeConformidade,
  IconeContingencia,
  IconeDocumentoFiscal,
  IconeInutilizacao,
  IconeContaCorrente,
  IconeContasPagar,
  IconeDevolucaoProduto,
  IconeEmpresa,
  IconeEstoque,
  IconeEstornoRecebimentoCrediario,
  IconeEtiqueta,
  IconeFechamentoCaixa,
  IconeFiscal,
  IconeFornecedor,
  IconeFuncionario,
  IconeLimpar,
  IconeMovimentoContaCorrente,
  IconePainel,
  IconeParametros,
  IconePdv,
  IconePedidos,
  IconePesquisaVendas,
  IconePlanoContas,
  IconeProduto,
  IconeRecebimentoCrediario,
  IconeRelatorio,
  IconeTipoCarteira,
  IconeUsuario,
} from '../components/Icones'

export type IconeComponente = (props: { size?: number }) => ReactElement

export interface NavItem {
  to: string
  label: string
  icone: IconeComponente
  /** Frase curta que explica o que a tela faz — aparece no card da página-hub do grupo. */
  descricao: string
  /**
   * Termos que também devem achar esta tela na busca do topo, além do rótulo e da descrição.
   *
   * ⚠️ Existe por causa de um efeito colateral concreto: ao dividir Relatórios em subgrupos
   * (2026-08-26), os rótulos encurtaram — "Relatório de Vendas" virou "Vendas" dentro de
   * Faturamento. Sem sinônimo, quem digitasse o nome antigo receberia **nada**, e uma tela que
   * some da busca logo depois de uma reorganização de menu parece uma tela que foi removida.
   * Serve também para o nome que o lojista usa e não está no rótulo (ex.: "kardex").
   */
  sinonimos?: string[]
  end?: boolean
  adminOnly?: boolean
}

export interface NavGrupo {
  chave: string
  label: string
  icone: IconeComponente
  descricao: string
  itens: NavNode[]
  adminOnly?: boolean
}

export type NavNode = NavItem | NavGrupo

export function eGrupo(n: NavNode): n is NavGrupo {
  return 'itens' in n
}

/** Rota da página-hub de um grupo (cards dos filhos). Prefixo `/menu/` para não colidir com as
 * rotas das telas — `estoque`, por exemplo, já é a Transferência de Produtos. */
export function rotaDoGrupo(chave: string): string {
  return `/menu/${chave}`
}

/** Menu reorganizado em grupos (2026-07-31, pedido do dono do produto) — Painel/Pedidos/Canais
 * saem da navegação principal mas continuam acessíveis em "Implementações Futuras" (item 6+8
 * do pedido: remover do destaque do menu sem remover do projeto).
 *
 * `descricao` (2026-08-03) alimenta os cards da página-hub de cada grupo (MenuGrupo.tsx): o
 * mesmo texto descreve o item no card e, no grupo, resume o que a área cobre. */
export const MENU: NavGrupo[] = [
  {
    chave: 'frente-loja',
    label: 'Frente de Loja',
    icone: IconePdv,
    descricao: 'Operação diária do balcão: vender, consultar, receber e controlar o caixa.',
    itens: [
      {
        to: '/pdv',
        label: 'PDV - Vendas',
        icone: IconePdv,
        descricao: 'Venda no balcão com leitura de código de barras, desconto, formas de pagamento e comprovante.',
      },
      {
        // ⚠️ Fica em Frente de Loja, e NÃO no grupo de Canais de Venda (que é ADMIN-only): quem
        // separa e embala é o operador. Exigir ADMIN aqui obrigaria o dono a despachar tudo, ou a
        // dar acesso de administrador a quem só precisa de uma lista de itens.
        to: '/expedicao',
        label: 'Fila de Expedição',
        icone: IconeCanais,
        descricao:
          'Pedidos de marketplace já pagos que ainda não saíram: separar, conferir os itens e confirmar o despacho.',
      },
      {
        to: '/orcamentos',
        label: 'Orçamentos',
        icone: IconePdv,
        descricao: 'Emite orçamento com validade para o cliente que ainda não fechou, imprime, envia por WhatsApp e transforma em venda no PDV.',
      },
      {
        to: '/pesquisa-vendas',
        label: 'Pesquisa de Vendas',
        icone: IconePesquisaVendas,
        descricao: 'Localiza vendas por período, número, cliente ou vendedor; vê o detalhe, reimprime a papeleta e cancela (ADMIN).',
      },
      {
        to: '/recebimento-crediario',
        label: 'Recebimento de Crediário',
        icone: IconeRecebimentoCrediario,
        descricao: 'Baixa as parcelas em aberto do cliente, aplicando juros e multa por atraso.',
      },
      {
        // Saiu do grupo "Reimpressões" (2026-08-19, pedido do dono do produto) — era o único
        // item que sobrava lá desde que Reimpressão de Papeleta de Venda saiu do menu em
        // 2026-08-18; virar item direto de Frente de Loja em vez de manter um grupo de 1 item.
        to: '/reimpressao-recebimento-crediario',
        label: 'Reimpressão de Recebimento de Crediário',
        icone: IconeRecebimentoCrediario,
        descricao: 'Localiza um recebimento por cliente e período e reimprime a papeleta.',
      },
      {
        to: '/devolucao-produto',
        label: 'Devolução de Produtos',
        icone: IconeDevolucaoProduto,
        descricao: 'Devolve produtos ao estoque lendo o código de barras, com o vendedor da venda opcionalmente identificado.',
      },
      {
        // Saiu do grupo "Caixa" (2026-08-19, pedido do dono do produto): Abertura de Caixa não é
        // mais uma tela — ela já acontece embutida no início da venda/recebimento
        // (AberturaCaixaModal.tsx) quando ainda não há caixa aberto. Sobrando só um item,
        // o grupo virou item direto de Frente de Loja, mesmo padrão de "Reimpressões" acima.
        to: '/fechamento-caixa',
        label: 'Fechamento de Caixa',
        icone: IconeFechamentoCaixa,
        descricao: 'Escolhe um caixa aberto, confere o movimento por carteira e encerra a sessão.',
      },
      {
        chave: 'cancelamentos',
        label: 'Cancelamentos',
        icone: IconeCancelamentoVenda,
        descricao: 'Desfaz operações lançadas por engano, com rastro de auditoria.',
        itens: [
          {
            to: '/estorno-recebimento-crediario',
            label: 'Estorno de Crediário',
            icone: IconeEstornoRecebimentoCrediario,
            descricao: 'Desfaz um recebimento de parcela lançado por engano e reabre o título do cliente.',
          },
          {
            to: '/cancelamento-devolucao-produtos',
            label: 'Cancelamento de Devolução de Produtos',
            icone: IconeCancelamentoVenda,
            descricao: 'Cancela um vale-mercadoria gerado por devolução, revertendo o estoque — só se o vale ainda não foi usado.',
          },
        ],
      },
    ],
  },
  {
    chave: 'estoque',
    label: 'Estoque',
    icone: IconeEstoque,
    descricao: 'Movimentação de quantidades entre empresas e conferência de estoque físico.',
    itens: [
      {
        to: '/entrada-produtos-compra',
        label: 'Entrada de Produtos por Compra',
        icone: IconeEstoque,
        descricao: 'Recebe mercadoria de fornecedor (XML de NF-e, lançamento manual ou planilha), gerando movimento de estoque tipo COMPRA.',
      },
      {
        to: '/estoque/devolucao-compra',
        label: 'Devolução de Produtos Comprados',
        icone: IconeEstoque,
        descricao: 'Devolve mercadoria ao fornecedor a partir da entrada que a trouxe, baixando o estoque e emitindo a NF-e de saída.',
      },
      {
        to: '/estoque',
        label: 'Transferência de Produtos',
        icone: IconeEstoque,
        descricao: 'Move quantidades entre empresas, com conferência item a item e histórico da transferência.',
      },
      {
        chave: 'contagem-estoque',
        label: 'Contagem de Estoque',
        icone: IconeEstoque,
        descricao: 'Confere o estoque físico da loja: conta, revisa diferenças e efetiva o ajuste no sistema.',
        itens: [
          {
            to: '/estoque/contagem',
            label: 'Contagem de Estoque',
            icone: IconeEstoque,
            descricao: 'Lê o código de barras do produto e acumula a quantidade contada, sempre na empresa logada.',
          },
          {
            to: '/estoque/diferencas',
            label: 'Diferenças de Estoque',
            icone: IconeRelatorio,
            descricao: 'Compara a contagem ativa com o estoque do sistema e mostra só as diferenças.',
          },
          {
            to: '/estoque/efetivar-balanco',
            label: 'Efetivar Balanço',
            icone: IconeConfirmar,
            descricao: 'Grava as diferenças como ajuste de estoque e zera a contagem ativa da empresa.',
          },
          {
            to: '/estoque/zerar-contagem',
            label: 'Zerar Contagem de Estoque',
            icone: IconeLimpar,
            descricao: 'Apaga a contagem em andamento ou desfaz a última efetivação de balanço.',
          },
        ],
      },
    ],
  },
  {
    chave: 'financeiro',
    label: 'Financeiro',
    icone: IconeContaCorrente,
    descricao: 'Contas da loja, lançamentos e a estrutura gerencial que classifica o dinheiro.',
    itens: [
      {
        to: '/contas-corrente',
        label: 'Conta Corrente',
        icone: IconeContaCorrente,
        descricao: 'Cadastro das contas bancárias e caixas da loja, com banco, agência e saldo atual.',
      },
      {
        to: '/contas-corrente-movimento',
        label: 'Movimentação de Conta Corrente',
        icone: IconeMovimentoContaCorrente,
        descricao: 'Lançamentos de entrada, saída e transferência entre contas, classificados no plano de contas.',
      },
      {
        to: '/planos-contas',
        label: 'Plano de Contas',
        icone: IconePlanoContas,
        descricao: 'Estrutura gerencial de receitas e despesas que alimenta o DRE e o fluxo de caixa.',
      },
      {
        to: '/tipos-carteira',
        label: 'Tipo de Carteira',
        icone: IconeTipoCarteira,
        descricao: 'Formas de recebimento (dinheiro, PIX, cartão) com prazo, faixa de parcelas e taxa administrativa.',
      },
      {
        to: '/contas-pagar',
        label: 'Contas a Pagar / Pagas',
        icone: IconeContasPagar,
        descricao: 'Duplicatas de fornecedor a pagar, com vencimento, pagamento e classificação no plano de contas.',
      },
    ],
  },
  {
    chave: 'cadastros',
    label: 'Cadastros',
    icone: IconeCliente,
    descricao: 'As pessoas com quem a loja se relaciona.',
    itens: [
      {
        to: '/clientes',
        label: 'Clientes',
        icone: IconeCliente,
        descricao: 'Dados, contatos, endereço e limite de crédito, com o histórico de compras do cliente.',
      },
      {
        to: '/fornecedores',
        label: 'Fornecedores',
        icone: IconeFornecedor,
        descricao: 'Quem abastece a loja, vinculado a uma conta do plano de contas para classificar a compra.',
      },
      {
        to: '/funcionarios',
        label: 'Funcionários',
        icone: IconeFuncionario,
        descricao: 'Equipe da loja — quem aparece como vendedor na venda e nos relatórios por vendedor.',
      },
      {
        to: '/produtos',
        label: 'Produtos',
        icone: IconeProduto,
        descricao: 'Cadastro do catálogo: categorias, variações, códigos de barra, NCM, preços e fotos.',
      },
    ],
  },
  {
    chave: 'configuracoes',
    label: 'Configurações',
    icone: IconeParametros,
    descricao: 'Ajustes da conta e do comportamento do sistema. Só para administradores.',
    adminOnly: true,
    itens: [
      {
        to: '/canais',
        label: 'Canais de Venda',
        icone: IconeCanais,
        descricao: 'Conexão com marketplaces, regra de preço por canal e saúde da sincronização.',
      },
      {
        to: '/minha-conta',
        label: 'Minha Conta',
        icone: IconePainel,
        descricao:
          'Plano, quantas vendas do mês já foram usadas, histórico e os CNPJs da conta. É o que a loja paga à Vetor — não confundir com o caixa da loja.',
      },
      {
        to: '/usuarios',
        label: 'Usuários',
        icone: IconeUsuario,
        descricao: 'Quem acessa o ERP, com papel (administrador ou operador) e as empresas permitidas.',
      },
      {
        to: '/empresas',
        label: 'Empresas',
        icone: IconeEmpresa,
        descricao: 'Identificação, endereço e dados fiscais (CNPJ, Inscrição Estadual, CNAE) de cada empresa do tenant.',
      },
      {
        to: '/configuracoes-gerais',
        label: 'Parâmetros do Sistema',
        icone: IconeParametros,
        descricao: 'Desconto máximo na venda, juros e multa do crediário e os nomes das variações do produto.',
      },
      {
        to: '/etiqueta-configuracao',
        label: 'Configuração de Etiqueta de Produtos',
        icone: IconeEtiqueta,
        descricao: 'Layout de impressão da etiqueta de código de barras dos produtos: rolo, colunas e campos.',
      },
      {
        chave: 'importacao-dados',
        label: 'Importação de Dados',
        icone: IconePedidos,
        descricao: 'Carga inicial de clientes, fornecedores, produtos, crediário em aberto e estoque, uma tabela por vez.',
        itens: [
          {
            to: '/importacao-dados/clientes',
            label: 'Clientes',
            icone: IconeCliente,
            descricao: 'Importa clientes a partir de uma planilha Excel (.xlsx ou .xls).',
          },
          {
            to: '/importacao-dados/contas-receber',
            label: 'Contas a Receber',
            icone: IconeRecebimentoCrediario,
            descricao: 'Importa o saldo devedor de crediário a partir de uma planilha Excel (.xlsx ou .xls) — o cliente precisa já estar cadastrado.',
          },
          {
            to: '/importacao-dados/fornecedores',
            label: 'Fornecedores',
            icone: IconeFornecedor,
            descricao: 'Importa fornecedores a partir de uma planilha Excel (.xlsx ou .xls).',
          },
          {
            to: '/importacao-dados/produtos',
            label: 'Produtos',
            icone: IconeProduto,
            descricao: 'Importa o catálogo, com a grade de tamanhos de cada produto, a partir de uma planilha Excel (.xlsx ou .xls).',
          },
          {
            to: '/importacao-dados/estoque',
            label: 'Estoque Inicial',
            icone: IconeEstoque,
            descricao: 'Importa o saldo inicial de estoque a partir de uma planilha Excel (.xlsx ou .xls) — o produto precisa já ter sido importado.',
          },
        ],
      },
      {
        to: '/exportacao-dados',
        label: 'Exportação de Dados',
        icone: IconePedidos,
        descricao: 'Baixe em planilha Excel empresas, clientes, fornecedores, funcionários, financeiro, estoque e código de barras.',
      },
      {
        chave: 'fiscal',
        label: 'Fiscal',
        icone: IconeFiscal,
        descricao: 'Configuração de emissão, regras de tributação e o certificado digital de cada empresa.',
        itens: [
          {
            to: '/fiscal/configuracao',
            label: 'Configuração Fiscal',
            icone: IconeFiscal,
            descricao: 'Regime tributário, ambiente, numeração e credenciamento de cada empresa junto à SEFAZ.',
          },
          {
            to: '/fiscal/perfis',
            label: 'Perfis Fiscais',
            icone: IconeFiscal,
            descricao: 'Regras de CFOP, ICMS, PIS/COFINS e IBS/CBS que os produtos usam na nota fiscal.',
          },
          {
            to: '/fiscal/certificados',
            label: 'Certificado Digital',
            icone: IconeCertificado,
            descricao: 'Upload do certificado A1 de cada empresa, com validade e histórico de uso.',
          },
          {
            to: '/fiscal/conformidade',
            label: 'Conformidade Fiscal',
            icone: IconeConformidade,
            descricao: 'O que falta antes de ligar o fiscal: empresa, produtos, formas de pagamento e clientes.',
          },
          {
            to: '/fiscal/contingencia',
            label: 'Contingência Fiscal',
            icone: IconeContingencia,
            descricao: 'Estado da contingência offline da NFC-e, notas aguardando transmissão, entrar/sair manualmente.',
          },
          {
            to: '/fiscal/documentos',
            label: 'Documentos Fiscais',
            icone: IconeDocumentoFiscal,
            descricao: 'Lista de NFC-e/NF-e emitidas, XML, consulta de situação na SEFAZ.',
          },
          {
            to: '/fiscal/exportacao-xml',
            label: 'Exportação de XML em Lote',
            icone: IconeDocumentoFiscal,
            descricao: 'Baixa num ZIP o XML de todas as NFC-e/NF-e de um período, para entregar ao contador.',
          },
          {
            to: '/fiscal/inutilizacao',
            label: 'Inutilização de Numeração',
            icone: IconeInutilizacao,
            descricao: 'Buracos na numeração detectados sozinhos — faixa + justificativa, homologação na SEFAZ.',
          },
        ],
      },
    ],
  },
  {
    chave: 'relatorios',
    label: 'Relatórios',
    icone: IconeRelatorio,
    descricao: 'Números da operação, prontos para conferir na tela ou levar em PDF.',
    // Dividido em subgrupos por assunto em 2026-08-26, a pedido do dono do produto — a lista
    // corrida de 11 itens tinha deixado de caber de uma olhada.
    //
    // ⚠️ As chaves dos subgrupos são prefixadas com `relatorios-` de propósito: a rota da
    // página-hub é `/menu/:chave` **global** e `acharGrupo` procura por chave na árvore inteira.
    // Um subgrupo chamado `estoque` ou `financeiro` colidiria com os grupos de topo de mesmo nome
    // — `/menu/estoque` acharia o primeiro na ordem e o subgrupo ficaria inalcançável, sem erro
    // nenhum aparecendo. O `label` é que fica curto; a chave é que precisa ser única.
    itens: [
      {
        chave: 'relatorios-faturamento',
        label: 'Faturamento',
        icone: IconeRelatorio,
        descricao: 'O que a loja vendeu no período e quanto disso virou comissão.',
        itens: [
          {
            to: '/relatorio-vendas',
            label: 'Vendas',
            icone: IconeRelatorio,
            descricao: 'Vendas do período com totais por forma de pagamento e geração de PDF com filtros no cabeçalho.',
            sinonimos: ['Relatório de Vendas'],
          },
          {
            to: '/relatorio-comissoes',
            label: 'Comissões',
            icone: IconeRelatorio,
            descricao: 'Venda, devolução e comissão por funcionário no período, subtotal por empresa.',
            sinonimos: ['Relatório de Comissões'],
          },
        ],
      },
      {
        chave: 'relatorios-estoque',
        label: 'Estoque',
        icone: IconeEstoque,
        descricao: 'O que existe em estoque, como ele se moveu e as etiquetas dos produtos.',
        itens: [
          {
            to: '/relatorio-estoque',
            label: 'Posição de Estoque',
            icone: IconeRelatorio,
            descricao: 'Inventário, sintético ou analítico por empresa, marca e categoria, com custo e quantidade.',
            sinonimos: ['Relatório de Estoque', 'Inventário'],
          },
          {
            to: '/relatorio-movimentacao-produtos',
            label: 'Movimentação de Produtos',
            icone: IconeRelatorio,
            descricao: 'Kardex do estoque: analítico, ficha por produto com saldo corrido, ou totais por tipo de movimento.',
            sinonimos: ['Relatório de Movimentação de Produtos', 'Kardex'],
          },
          {
            to: '/etiqueta-emissao',
            label: 'Etiquetas de Produtos',
            icone: IconeEtiqueta,
            descricao: 'Selecione produtos (individual, por entrada ou por estoque) e imprima etiquetas em lote.',
            sinonimos: ['Emissão de Etiqueta de Produtos'],
          },
        ],
      },
      {
        chave: 'relatorios-financeiro',
        label: 'Financeiro',
        icone: IconeContaCorrente,
        descricao: 'Dinheiro a entrar, dinheiro a sair e a projeção do saldo.',
        itens: [
          {
            to: '/relatorio-contas-receber',
            label: 'Contas a Receber / Recebidas',
            icone: IconeRelatorio,
            descricao: 'Parcelas de cartão e crediário por período de venda, vencimento ou recebimento, com valor bruto e líquido.',
          },
          {
            to: '/relatorio-contas-pagar',
            label: 'Contas a Pagar / Pagas',
            icone: IconeRelatorio,
            descricao: 'Duplicatas por fornecedor e plano de contas, com vencido, a vencer e pago no período — inclui compra de mercadoria.',
          },
          {
            to: '/fluxo-caixa',
            label: 'Fluxo de Caixa',
            icone: IconeRelatorio,
            descricao: 'Entradas e saídas de dinheiro por atividade, e a projeção do saldo a partir das contas a receber e a pagar.',
          },
        ],
      },
      {
        // ⚠️ Os dois itens são `adminOnly`, e o subgrupo NÃO precisa ser marcado: `filtrarPorPapel`
        // é recursivo e descarta grupo que ficou sem filhos — para um OPERADOR, "Resultados"
        // simplesmente não aparece.
        chave: 'relatorios-resultados',
        label: 'Resultados',
        icone: IconeRelatorio,
        descricao: 'Se a loja deu lucro no período, e de onde ele veio.',
        itens: [
          {
            to: '/relatorio-dre',
            label: 'DRE — Demonstração do Resultado',
            icone: IconeRelatorio,
            descricao: 'Lucro do período em regime de competência ou de caixa, com CMV, margem e comparação com o período anterior.',
            adminOnly: true,
          },
          {
            to: '/lucratividade',
            label: 'Lucratividade',
            icone: IconeRelatorio,
            descricao: 'Venda, custo do vendido, lucro bruto, contas pagas por plano de contas e lucro líquido do período.',
            sinonimos: ['Relatório de Lucratividade'],
            adminOnly: true,
          },
        ],
      },
      {
        // Fica como item solto ao lado dos quatro subgrupos, como pedido — não tem irmão que
        // justifique um subgrupo de um item só (mesmo raciocínio que dissolveu "Reimpressões").
        to: '/crm',
        label: 'CRM',
        icone: IconeCliente,
        descricao: 'Filtre clientes por perfil e histórico de compras e exporte a lista para planilha Excel.',
      },
    ],
  },
  {
    chave: 'implementacoes-futuras',
    label: 'Implementações Futuras',
    icone: IconePedidos,
    descricao: 'Áreas já previstas no projeto, ainda em construção.',
    itens: [
      {
        to: '/',
        label: 'Painel',
        icone: IconePainel,
        descricao: 'Resumo da conta, situação da assinatura e próximos passos da implantação.',
        end: true,
      },
      {
        to: '/pedidos',
        label: 'Pedidos',
        icone: IconePedidos,
        descricao: 'Fila única com os pedidos importados dos marketplaces. Em construção.',
      },
      {
        to: '/bi-dashboard',
        label: 'BI Dashboard',
        icone: IconePainel,
        descricao: 'Painel gerencial com indicadores consolidados do negócio. Em construção.',
      },
      {
        to: '/relatorio-movimentacao-bancaria',
        label: 'Relatório de Movimentação Bancária',
        icone: IconeContaCorrente,
        descricao: 'Extrato consolidado das contas correntes da loja. Em construção.',
      },
      // DRE, Fluxo de Caixa (2026-08-14) e Lucratividade (2026-08-25) saíram de "Implementações
      // Futuras": as três telas existem e estão em Relatórios. ⚠️ Item que vira tela tem de SAIR
      // daqui — quando o /fluxo-caixa ficou pronto, o placeholder continuou apontando para a mesma
      // rota e o menu passou a mostrar o item duas vezes.
      {
        to: '/integracao-marketplace',
        label: 'Integração com Marketplace',
        icone: IconeCanais,
        descricao: 'Publicação de anúncios e sincronização automática de estoque/preço nos canais. Em construção.',
      },
      {
        to: '/cobranca-crediario-atraso',
        label: 'Cobrança de Crediário em Atraso',
        icone: IconeRecebimentoCrediario,
        descricao: 'Régua de cobrança das parcelas de crediário vencidas e não pagas. Em construção.',
      },
      // ⚠️ O subgrupo "Módulo Fiscal" saiu daqui em 2026-08-25. Ele prometia quatro telas, e
      // TRÊS delas já existem — em outro lugar: NFC-e e NF-e são emitidas pelo próprio PDV, e o
      // cancelamento fica em Documentos Fiscais. Item de menu que oferece "Em construção" para
      // função pronta é pior que item ausente: manda o lojista procurar onde não está.
      // A última que sobrava, "Exportação de XML em Lote", foi implementada em 2026-08-26 e saiu
      // daqui junto com a rota placeholder /exportacao-xml-fiscal — agora é
      // Configurações → Fiscal → Exportação de XML em Lote (/fiscal/exportacao-xml).
    ],
  },
]

/** Remove itens/grupos exclusivos de ADMIN; grupos que ficam sem nenhum item visível somem
 * inteiros. */
export function filtrarPorPapel(nos: NavNode[], isAdmin: boolean): NavNode[] {
  const resultado: NavNode[] = []
  for (const n of nos) {
    if (eGrupo(n)) {
      if (n.adminOnly && !isAdmin) continue
      if (n.itens.length === 0) {
        resultado.push(n)
        continue
      }
      const filhos = filtrarPorPapel(n.itens, isAdmin)
      if (filhos.length === 0) continue
      resultado.push({ ...n, itens: filhos })
    } else {
      if (n.adminOnly && !isAdmin) continue
      resultado.push(n)
    }
  }
  return resultado
}


/**
 * Remove do menu as telas que o usuário não pode ACESSAR (RBAC, V073).
 *
 * ⚠️ Complementa `filtrarPorPapel`, não o substitui: aquele trata do que é exclusivo do
 * administrador por natureza (Configurações, DRE), este trata do que o administrador concedeu a
 * cada usuário. Uma tela precisa passar pelos dois.
 *
 * ⚠️ `chavesPermitidas` vazio significa "ainda não carregou" e devolve o menu inteiro — o
 * contrário faria o menu piscar vazio a cada abertura, e um menu que aparece vazio por meio
 * segundo parece sistema quebrado. Quem carrega decide quando aplicar.
 */
export function filtrarPorPermissao(
  nos: NavNode[],
  chavesPermitidas: Set<string> | null,
  chavesCatalogadas?: Set<string>,
): NavNode[] {
  if (!chavesPermitidas) return nos
  const resultado: NavNode[] = []
  for (const n of nos) {
    if (eGrupo(n)) {
      const filhos = filtrarPorPermissao(n.itens, chavesPermitidas, chavesCatalogadas)
      if (filhos.length === 0 && n.itens.length > 0) continue
      resultado.push({ ...n, itens: filhos })
    } else {
      const chave = chaveDaRota(n.to)
      // ⚠️ Tela FORA do catálogo não é controlada por permissão e continua aparecendo — hoje
      // são as de "Implementações Futuras", que saíram da grade a pedido dele ("quando o ERP
      // for concluído este item não vai existir") mas devem seguir no menu. Tratar ausência
      // como "proibida" faria qualquer tela nova sumir do menu no dia em que fosse criada,
      // antes de alguém pensar em catalogá-la — e sumir sem erro é o pior tipo de bug.
      const naoCatalogada = chavesCatalogadas ? !chavesCatalogadas.has(chave) : false
      if (naoCatalogada || chavesPermitidas.has(chave)) resultado.push(n)
    }
  }
  return resultado
}

/** Uma tela indexada para a busca do cabeçalho, com o caminho de grupos até ela. */
export interface TelaBuscavel {
  item: NavItem
  /** Ex.: `['Frente de Loja', 'Caixa']` — vira a trilha mostrada no resultado. */
  trilha: string[]
}

/** Achata a árvore em telas navegáveis, guardando o caminho de grupos de cada uma. Espera
 * receber o menu **já filtrado por papel** — quem chama é que sabe se o usuário é ADMIN. */
export function listarTelas(nos: NavNode[], trilha: string[] = []): TelaBuscavel[] {
  const telas: TelaBuscavel[] = []
  for (const n of nos) {
    if (eGrupo(n)) telas.push(...listarTelas(n.itens, [...trilha, n.label]))
    else telas.push({ item: n, trilha })
  }
  return telas
}

/** Minúsculas e sem acento — "crediario" tem que achar "Crediário". */
export function normalizar(texto: string): string {
  return texto
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim()
}

/** Pontua uma tela contra o termo já normalizado: quanto menor, mais relevante; `null` = não
 * bate. Começar com o termo vale mais que contê-lo no meio, e bater no nome da tela vale mais
 * que bater na trilha ou na descrição — quem digita "caixa" quer as telas de Caixa antes de
 * "Conta Corrente… fluxo de caixa". */
export function pontuarTela(tela: TelaBuscavel, termo: string): number | null {
  const nome = normalizar(tela.item.label)
  if (nome.startsWith(termo)) return 0
  // Cada palavra do nome conta: "vendas" acha "Pesquisa de Vendas" antes de quem só contém.
  if (nome.split(/\s+/).some((p) => p.startsWith(termo))) return 1
  if (nome.includes(termo)) return 2
  // Sinônimo vale quase como o nome — quem digita o rótulo ANTIGO da tela está tentando acertar
  // o nome dela, não tropeçando na descrição de outra.
  if ((tela.item.sinonimos ?? []).some((s) => normalizar(s).includes(termo))) return 3
  if (normalizar(tela.trilha.join(' ')).includes(termo)) return 4
  if (normalizar(tela.item.descricao).includes(termo)) return 5
  return null
}

/** Telas que casam com o termo, da mais relevante para a menos. Termo vazio devolve nada. */
export function buscarTelas(telas: TelaBuscavel[], termo: string, limite = 8): TelaBuscavel[] {
  const t = normalizar(termo)
  if (!t) return []
  return telas
    .map((tela) => ({ tela, ponto: pontuarTela(tela, t) }))
    .filter((r): r is { tela: TelaBuscavel; ponto: number } => r.ponto !== null)
    .sort((a, b) => a.ponto - b.ponto || a.tela.item.label.localeCompare(b.tela.item.label, 'pt-BR'))
    .slice(0, limite)
    .map((r) => r.tela)
}

/** Procura um grupo pela chave em qualquer nível (a página-hub aceita subgrupos como `caixa`). */
export function acharGrupo(nos: NavNode[], chave: string): NavGrupo | null {
  for (const n of nos) {
    if (!eGrupo(n)) continue
    if (n.chave === chave) return n
    const achado = acharGrupo(n.itens, chave)
    if (achado) return achado
  }
  return null
}

/** Grupo que contém `chave`, ou null se ela é de primeiro nível. Alimenta a seta de retorno da
 * página-hub: de `/menu/caixa` volta-se para `/menu/frente-loja`, não para o painel. */
export function acharPai(nos: NavNode[], chave: string, pai: NavGrupo | null = null): NavGrupo | null {
  for (const n of nos) {
    if (!eGrupo(n)) continue
    if (n.chave === chave) return pai
    const achado = acharPai(n.itens, chave, n)
    if (achado) return achado
  }
  return null
}
