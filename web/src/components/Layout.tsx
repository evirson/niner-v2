import { useRef, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { useEu } from '../lib/eu'
import {
  IconeCaixa,
  IconeCanais,
  IconeCancelamentoVenda,
  IconeCliente,
  IconeContaCorrente,
  IconeEstoque,
  IconeFechamentoCaixa,
  IconeFornecedor,
  IconeFuncionario,
  IconeMovimentoContaCorrente,
  IconePainel,
  IconeParametros,
  IconePdv,
  IconePedidos,
  IconePesquisaVendas,
  IconeEstornoRecebimentoCrediario,
  IconePlanoContas,
  IconeProduto,
  IconeRecebimentoCrediario,
  IconeRecolherMenu,
  IconeSetaBaixo,
  IconeSetaCima,
  IconeTipoCarteira,
  IconeUsuario,
} from './Icones'

const CHAVE_RECOLHIDO = 'niner_nav_recolhido'
const CHAVE_GRUPOS_ABERTOS = 'niner_nav_grupos_abertos'

type IconeComponente = (props: { size?: number }) => React.ReactElement

interface NavItem {
  to: string
  label: string
  icone: IconeComponente
  end?: boolean
  adminOnly?: boolean
}

interface NavGrupo {
  chave: string
  label: string
  icone: IconeComponente
  itens: NavNode[]
  adminOnly?: boolean
}

type NavNode = NavItem | NavGrupo

function eGrupo(n: NavNode): n is NavGrupo {
  return 'itens' in n
}

/** Menu reorganizado em grupos (2026-07-31, pedido do dono do produto) — Painel/Pedidos/Canais
 * saem da navegação principal mas continuam acessíveis em "Implementações Futuras" (item 6+8
 * do pedido: remover do destaque do menu sem remover do projeto). */
const MENU: NavGrupo[] = [
  {
    chave: 'frente-loja',
    label: 'Frente de Loja',
    icone: IconePdv,
    itens: [
      { to: '/pdv', label: 'PDV - Vendas', icone: IconePdv },
      { to: '/pesquisa-vendas', label: 'Pesquisa de Vendas', icone: IconePesquisaVendas },
      { to: '/recebimento-crediario', label: 'Recebimento de Crediário', icone: IconeRecebimentoCrediario },
      {
        chave: 'caixa',
        label: 'Caixa',
        icone: IconeCaixa,
        itens: [
          { to: '/abertura-caixa', label: 'Abertura de Caixa', icone: IconeCaixa },
          { to: '/fechamento-caixa', label: 'Fechamento de Caixa', icone: IconeFechamentoCaixa },
        ],
      },
      {
        chave: 'cancelamentos',
        label: 'Cancelamentos',
        icone: IconeCancelamentoVenda,
        itens: [
          { to: '/cancelamento-venda', label: 'Cancelamento de Vendas', icone: IconeCancelamentoVenda, adminOnly: true },
          { to: '/estorno-recebimento-crediario', label: 'Estorno de Crediário', icone: IconeEstornoRecebimentoCrediario },
        ],
      },
    ],
  },
  {
    chave: 'estoque',
    label: 'Estoque',
    icone: IconeEstoque,
    itens: [
      { to: '/produtos', label: 'Produtos', icone: IconeProduto },
      { to: '/estoque', label: 'Transferência de Produtos', icone: IconeEstoque },
    ],
  },
  {
    chave: 'financeiro',
    label: 'Financeiro',
    icone: IconeContaCorrente,
    itens: [
      { to: '/contas-corrente', label: 'Conta Corrente', icone: IconeContaCorrente },
      { to: '/contas-corrente-movimento', label: 'Movimentação de Conta Corrente', icone: IconeMovimentoContaCorrente },
      { to: '/planos-contas', label: 'Plano de Contas', icone: IconePlanoContas },
      { to: '/tipos-carteira', label: 'Tipo de Carteira', icone: IconeTipoCarteira },
    ],
  },
  {
    chave: 'cadastros',
    label: 'Cadastros',
    icone: IconeCliente,
    itens: [
      { to: '/clientes', label: 'Clientes', icone: IconeCliente },
      { to: '/fornecedores', label: 'Fornecedores', icone: IconeFornecedor },
      { to: '/funcionarios', label: 'Funcionários', icone: IconeFuncionario },
    ],
  },
  {
    chave: 'configuracoes',
    label: 'Configurações',
    icone: IconeParametros,
    adminOnly: true,
    itens: [
      { to: '/usuarios', label: 'Usuários', icone: IconeUsuario },
      { to: '/configuracoes-gerais', label: 'Parâmetros do Sistema', icone: IconeParametros },
    ],
  },
  {
    chave: 'relatorios',
    label: 'Relatórios',
    icone: IconePainel,
    itens: [],
  },
  {
    chave: 'implementacoes-futuras',
    label: 'Implementações Futuras',
    icone: IconePedidos,
    itens: [
      { to: '/', label: 'Painel', icone: IconePainel, end: true },
      { to: '/pedidos', label: 'Pedidos', icone: IconePedidos },
      { to: '/canais', label: 'Canais', icone: IconeCanais },
    ],
  },
]

const GRUPOS_ABERTOS_PADRAO: Record<string, boolean> = {
  'frente-loja': true,
  caixa: true,
  cancelamentos: true,
  estoque: true,
  financeiro: true,
  cadastros: true,
  configuracoes: true,
  relatorios: true,
  'implementacoes-futuras': false,
}

function carregarGruposAbertos(): Record<string, boolean> {
  try {
    const salvo = localStorage.getItem(CHAVE_GRUPOS_ABERTOS)
    if (!salvo) return GRUPOS_ABERTOS_PADRAO
    return { ...GRUPOS_ABERTOS_PADRAO, ...JSON.parse(salvo) }
  } catch {
    return GRUPOS_ABERTOS_PADRAO
  }
}

/** Remove itens/grupos exclusivos de ADMIN; grupos que ficam sem nenhum item visível somem
 * inteiros (exceto "Relatórios", vazio por design — placeholder até a tela existir). */
function filtrarPorPapel(nos: NavNode[], isAdmin: boolean): NavNode[] {
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

function achatarFolhas(nos: NavNode[]): NavItem[] {
  const folhas: NavItem[] = []
  for (const n of nos) {
    if (eGrupo(n)) folhas.push(...achatarFolhas(n.itens))
    else folhas.push(n)
  }
  return folhas
}

function NoDeMenu({
  no,
  nivel,
  gruposAbertos,
  alternarGrupo,
}: {
  no: NavNode
  nivel: number
  gruposAbertos: Record<string, boolean>
  alternarGrupo: (chave: string) => void
}) {
  if (!eGrupo(no)) {
    const Icone = no.icone
    return (
      <NavLink to={no.to} end={no.end} className={({ isActive }) => (isActive ? 'active' : '')} style={{ paddingLeft: 12 + nivel * 16 }}>
        <Icone size={18} />
        <span>{no.label}</span>
      </NavLink>
    )
  }

  if (no.itens.length === 0) {
    const Icone = no.icone
    return (
      <span className="app-nav-grupo-vazio" style={{ paddingLeft: 12 + nivel * 16 }} title="Em construção">
        <Icone size={18} />
        <span>{no.label}</span>
      </span>
    )
  }

  const aberto = gruposAbertos[no.chave] ?? true
  const Icone = no.icone
  return (
    <div className="app-nav-grupo">
      <button type="button" className="app-nav-grupo-cabecalho" style={{ paddingLeft: 12 + nivel * 16 }} onClick={() => alternarGrupo(no.chave)}>
        <Icone size={18} />
        <span>{no.label}</span>
        <span className="app-nav-grupo-seta">{aberto ? <IconeSetaCima size={14} /> : <IconeSetaBaixo size={14} />}</span>
      </button>
      {aberto &&
        no.itens.map((filho) => (
          <NoDeMenu
            key={eGrupo(filho) ? filho.chave : filho.to}
            no={filho}
            nivel={nivel + 1}
            gruposAbertos={gruposAbertos}
            alternarGrupo={alternarGrupo}
          />
        ))}
    </div>
  )
}

/** Shell do ERP: cabeçalho + navegação lateral (retrátil e agrupada, 2026-07-28/31) + área de conteúdo. */
export default function Layout() {
  const { logout } = useAuth()
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const isAdmin = eu?.usuario.papel === 'ADMIN'
  const menu = filtrarPorPapel(MENU, isAdmin)

  const [recolhido, setRecolhido] = useState(() => localStorage.getItem(CHAVE_RECOLHIDO) === '1')
  // "Espiada" ao passar o mouse/focar (2026-07-31, pedido do dono do produto): com o menu
  // recolhido, hover ou foco expande temporariamente sem alterar a preferência persistida;
  // saindo do mouse ou do foco (pra fora do <nav>, não entre os próprios links) recolhe de novo.
  const [expandidoPorInteracao, setExpandidoPorInteracao] = useState(false)
  const [gruposAbertos, setGruposAbertos] = useState(carregarGruposAbertos)
  const navRef = useRef<HTMLElement>(null)
  const mostrarExpandido = !recolhido || expandidoPorInteracao

  const alternarRecolhido = () => {
    setRecolhido((atual) => {
      const novo = !atual
      localStorage.setItem(CHAVE_RECOLHIDO, novo ? '1' : '0')
      return novo
    })
  }

  const alternarGrupo = (chave: string) => {
    setGruposAbertos((atual) => {
      const novo = { ...atual, [chave]: !(atual[chave] ?? true) }
      localStorage.setItem(CHAVE_GRUPOS_ABERTOS, JSON.stringify(novo))
      return novo
    })
  }

  const aoSairDoFoco = (e: React.FocusEvent) => {
    if (navRef.current && e.relatedTarget instanceof Node && navRef.current.contains(e.relatedTarget)) return
    setExpandidoPorInteracao(false)
  }

  const sair = () => {
    logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="app">
      <header className="app-header">
        <a className="brand" href="/">
          NI<span>NER</span>
        </a>
        {eu?.empresa && (
          <span className="muted app-empresa-ativa" title="Empresa ativa nesta sessão">
            {eu.empresa.nome}
          </span>
        )}
        <button className="btn ghost" onClick={sair}>
          Sair
        </button>
      </header>
      <div className="app-body">
        <nav
          ref={navRef}
          className={`app-nav${mostrarExpandido ? '' : ' app-nav-recolhido'}`}
          onMouseEnter={() => setExpandidoPorInteracao(true)}
          onMouseLeave={() => setExpandidoPorInteracao(false)}
          onFocus={() => setExpandidoPorInteracao(true)}
          onBlur={aoSairDoFoco}
        >
          {mostrarExpandido
            ? menu.map((grupo) => (
                <NoDeMenu key={grupo.chave} no={grupo} nivel={0} gruposAbertos={gruposAbertos} alternarGrupo={alternarGrupo} />
              ))
            : achatarFolhas(menu).map((item) => {
                const Icone = item.icone
                return (
                  <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => (isActive ? 'active' : '')} title={item.label}>
                    <Icone size={20} />
                  </NavLink>
                )
              })}
          <button
            type="button"
            className="app-nav-toggle"
            onClick={alternarRecolhido}
            title={recolhido ? 'Expandir menu' : 'Recolher menu'}
          >
            <IconeRecolherMenu recolhido={recolhido} />
            {mostrarExpandido && <span>Recolher</span>}
          </button>
        </nav>
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
