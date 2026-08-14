import type { ReactElement } from 'react'
import {
  IconeCaixa,
  IconeCanais,
  IconeCancelamentoVenda,
  IconeCliente,
  IconeConfirmar,
  IconeContaCorrente,
  IconeContasPagar,
  IconeDevolucaoProduto,
  IconeEstoque,
  IconeEstornoRecebimentoCrediario,
  IconeEtiqueta,
  IconeFechamentoCaixa,
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
        to: '/pesquisa-vendas',
        label: 'Pesquisa de Vendas',
        icone: IconePesquisaVendas,
        descricao: 'Localiza vendas por período, número, cliente ou vendedor e abre o detalhe de cada item.',
      },
      {
        to: '/recebimento-crediario',
        label: 'Recebimento de Crediário',
        icone: IconeRecebimentoCrediario,
        descricao: 'Baixa as parcelas em aberto do cliente, aplicando juros e multa por atraso.',
      },
      {
        to: '/devolucao-produto',
        label: 'Devolução de Produtos',
        icone: IconeDevolucaoProduto,
        descricao: 'Devolve produtos ao estoque lendo o código de barras, com o vendedor da venda opcionalmente identificado.',
      },
      {
        chave: 'caixa',
        label: 'Caixa',
        icone: IconeCaixa,
        descricao: 'Abertura e fechamento da sessão de caixa do dia.',
        itens: [
          {
            to: '/abertura-caixa',
            label: 'Abertura de Caixa',
            icone: IconeCaixa,
            descricao: 'Abre a sessão informando o saldo inicial em dinheiro. Exigida antes de vender ou receber.',
          },
          {
            to: '/fechamento-caixa',
            label: 'Fechamento de Caixa',
            icone: IconeFechamentoCaixa,
            descricao: 'Confere o movimento por carteira "às cegas", aponta a diferença e encerra a sessão.',
          },
        ],
      },
      {
        chave: 'cancelamentos',
        label: 'Cancelamentos',
        icone: IconeCancelamentoVenda,
        descricao: 'Desfaz operações lançadas por engano, com rastro de auditoria.',
        itens: [
          {
            to: '/cancelamento-venda',
            label: 'Cancelamento de Vendas',
            icone: IconeCancelamentoVenda,
            descricao: 'Cancela uma venda registrada, devolvendo o estoque e revertendo os lançamentos financeiros.',
            adminOnly: true,
          },
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
      {
        chave: 'reimpressoes',
        label: 'Reimpressões',
        icone: IconePdv,
        descricao: 'Localiza uma venda ou um recebimento já efetivado e reimprime a papeleta.',
        itens: [
          {
            to: '/reimpressao-papeleta-venda',
            label: 'Reimpressão de Papeleta de Venda',
            icone: IconePdv,
            descricao: 'Localiza uma venda por número, período ou cliente e reimprime a papeleta.',
          },
          {
            to: '/reimpressao-recebimento-crediario',
            label: 'Reimpressão de Recebimento de Crediário',
            icone: IconeRecebimentoCrediario,
            descricao: 'Localiza um recebimento por cliente e período e reimprime a papeleta.',
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
        to: '/usuarios',
        label: 'Usuários',
        icone: IconeUsuario,
        descricao: 'Quem acessa o ERP, com papel (administrador ou operador) e as empresas permitidas.',
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
    ],
  },
  {
    chave: 'relatorios',
    label: 'Relatórios',
    icone: IconeRelatorio,
    descricao: 'Números da operação, prontos para conferir na tela ou levar em PDF.',
    itens: [
      {
        to: '/relatorio-vendas',
        label: 'Relatório de Vendas',
        icone: IconeRelatorio,
        descricao: 'Vendas do período com totais por forma de pagamento e geração de PDF com filtros no cabeçalho.',
      },
      {
        to: '/relatorio-comissoes',
        label: 'Relatório de Comissões',
        icone: IconeRelatorio,
        descricao: 'Venda, devolução e comissão por funcionário no período, subtotal por empresa.',
      },
      {
        to: '/relatorio-contas-receber',
        label: 'Contas a Receber / Recebidas',
        icone: IconeRelatorio,
        descricao: 'Parcelas de cartão e crediário por período de venda, vencimento ou recebimento, com valor bruto e líquido.',
      },
      {
        to: '/relatorio-estoque',
        label: 'Relatório de Estoque',
        icone: IconeRelatorio,
        descricao: 'Inventário, sintético ou analítico por empresa, marca e categoria, com custo e quantidade.',
      },
      {
        to: '/relatorio-movimentacao-produtos',
        label: 'Movimentação de Produtos',
        icone: IconeRelatorio,
        descricao: 'Kardex do estoque: analítico, ficha por produto com saldo corrido, ou totais por tipo de movimento.',
      },
      {
        to: '/fluxo-caixa',
        label: 'Fluxo de Caixa',
        icone: IconeRelatorio,
        descricao: 'Entradas e saídas de dinheiro por atividade, e a projeção do saldo a partir das contas a receber e a pagar.',
      },
      {
        to: '/relatorio-dre',
        label: 'DRE — Demonstração do Resultado',
        icone: IconeRelatorio,
        descricao: 'Lucro do período em regime de competência ou de caixa, com CMV, margem e comparação com o período anterior.',
        adminOnly: true,
      },
      {
        to: '/crm',
        label: 'CRM',
        icone: IconeCliente,
        descricao: 'Filtre clientes por perfil e histórico de compras e exporte a lista para planilha Excel.',
      },
      {
        to: '/etiqueta-emissao',
        label: 'Emissão de Etiqueta de Produtos',
        icone: IconeEtiqueta,
        descricao: 'Selecione produtos (individual, por entrada ou por estoque) e imprima etiquetas em lote.',
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
        to: '/canais',
        label: 'Canais',
        icone: IconeCanais,
        descricao: 'Conexão com Mercado Livre, Shopee e demais canais de venda. Em construção.',
      },
      {
        to: '/bi-dashboard',
        label: 'BI Dashboard',
        icone: IconePainel,
        descricao: 'Painel gerencial com indicadores consolidados do negócio. Em construção.',
      },
      {
        to: '/relatorio-contas-pagar',
        label: 'Relatório de Contas a Pagar / Pagas',
        icone: IconeRelatorio,
        descricao: 'Situação das contas a pagar da loja, em aberto e quitadas. Em construção.',
      },
      {
        to: '/relatorio-movimentacao-bancaria',
        label: 'Relatório de Movimentação Bancária',
        icone: IconeContaCorrente,
        descricao: 'Extrato consolidado das contas correntes da loja. Em construção.',
      },
      // DRE e Fluxo de Caixa saíram de "Implementações Futuras" em 2026-08-24: as duas telas
      // existem e estão em Relatórios (/relatorio-dre e /fluxo-caixa). O item /fluxo-caixa
      // daqui apontava para a MESMA rota da tela pronta, então aparecia duas vezes no menu.
      {
        to: '/lucratividade',
        label: 'Lucratividade',
        icone: IconeRelatorio,
        descricao: 'Margem por produto, categoria ou período, cruzando preço de venda e custo. Em construção.',
      },
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
      {
        chave: 'modulo-fiscal',
        label: 'Módulo Fiscal',
        icone: IconePlanoContas,
        descricao: 'Emissão e cancelamento de documentos fiscais eletrônicos. Em construção.',
        itens: [
          {
            to: '/nfce',
            label: 'Nota Fiscal - NFC-e',
            icone: IconePlanoContas,
            descricao: 'Emissão de Nota Fiscal de Consumidor Eletrônica para as vendas do PDV. Em construção.',
          },
          {
            to: '/nfe',
            label: 'Nota Fiscal - NF-e',
            icone: IconePlanoContas,
            descricao: 'Emissão de Nota Fiscal Eletrônica para vendas e transferências entre empresas. Em construção.',
          },
          {
            to: '/cancelamento-documento-fiscal',
            label: 'Cancelamento de Documento Fiscal',
            icone: IconeCancelamentoVenda,
            descricao: 'Cancela uma NFC-e ou NF-e já emitida, dentro do prazo permitido pela SEFAZ. Em construção.',
          },
          {
            to: '/exportacao-xml-fiscal',
            label: 'Exportação de XML',
            icone: IconePlanoContas,
            descricao: 'Baixa o XML das notas fiscais emitidas (NFC-e/NF-e) para contabilidade ou SEFAZ. Em construção.',
          },
        ],
      },
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
  if (normalizar(tela.trilha.join(' ')).includes(termo)) return 3
  if (normalizar(tela.item.descricao).includes(termo)) return 4
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
