import type { ReactElement } from 'react'
import {
  IconeCaixa,
  IconeCanais,
  IconeCancelamentoVenda,
  IconeCliente,
  IconeContaCorrente,
  IconeEstoque,
  IconeEstornoRecebimentoCrediario,
  IconeFechamentoCaixa,
  IconeFornecedor,
  IconeFuncionario,
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
        ],
      },
    ],
  },
  {
    chave: 'estoque',
    label: 'Estoque',
    icone: IconeEstoque,
    descricao: 'Catálogo de produtos e movimentação de quantidades entre empresas.',
    itens: [
      {
        to: '/produtos',
        label: 'Produtos',
        icone: IconeProduto,
        descricao: 'Cadastro do catálogo: categorias, variações, códigos de barra, NCM, preços e fotos.',
      },
      {
        to: '/estoque',
        label: 'Transferência de Produtos',
        icone: IconeEstoque,
        descricao: 'Move quantidades entre empresas, com conferência item a item e histórico da transferência.',
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
