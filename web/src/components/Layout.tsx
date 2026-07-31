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
  IconeTipoCarteira,
  IconeUsuario,
} from './Icones'

const CHAVE_RECOLHIDO = 'niner_nav_recolhido'

interface ItemNav {
  to: string
  label: string
  icone: (props: { size?: number }) => React.ReactElement
  end?: boolean
}

const NAV: ItemNav[] = [
  { to: '/', label: 'Painel', icone: IconePainel, end: true },
  { to: '/abertura-caixa', label: 'Abertura de Caixa', icone: IconeCaixa },
  { to: '/fechamento-caixa', label: 'Fechamento de Caixa', icone: IconeFechamentoCaixa },
  { to: '/pdv', label: 'PDV', icone: IconePdv },
  { to: '/pesquisa-vendas', label: 'Pesquisa de Vendas', icone: IconePesquisaVendas },
  { to: '/produtos', label: 'Produtos', icone: IconeProduto },
  { to: '/estoque', label: 'Transferência de Produtos', icone: IconeEstoque },
  { to: '/pedidos', label: 'Pedidos', icone: IconePedidos },
  { to: '/canais', label: 'Canais', icone: IconeCanais },
  { to: '/clientes', label: 'Clientes', icone: IconeCliente },
  { to: '/fornecedores', label: 'Fornecedores', icone: IconeFornecedor },
  { to: '/funcionarios', label: 'Funcionários', icone: IconeFuncionario },
  { to: '/planos-contas', label: 'Plano de Contas', icone: IconePlanoContas },
  { to: '/tipos-carteira', label: 'Tipo de Carteira', icone: IconeTipoCarteira },
  { to: '/contas-corrente', label: 'Conta Corrente', icone: IconeContaCorrente },
  { to: '/contas-corrente-movimento', label: 'Movimentação de Conta Corrente', icone: IconeMovimentoContaCorrente },
  { to: '/recebimento-crediario', label: 'Recebimento de Crediário', icone: IconeRecebimentoCrediario },
  { to: '/estorno-recebimento-crediario', label: 'Estorno de Crediário', icone: IconeEstornoRecebimentoCrediario },
]

/** Só ADMIN vê este item — a rota em si também é protegida por `RequireAdmin` (defesa em profundidade). */
const NAV_ADMIN: ItemNav[] = [
  { to: '/usuarios', label: 'Usuários', icone: IconeUsuario },
  { to: '/configuracoes-gerais', label: 'Parâmetros do Sistema', icone: IconeParametros },
  { to: '/cancelamento-venda', label: 'Cancelamento de Venda', icone: IconeCancelamentoVenda },
]

/** Shell do ERP: cabeçalho + navegação lateral (retrátil, 2026-07-28) + área de conteúdo. */
export default function Layout() {
  const { logout } = useAuth()
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const itensNav = eu?.usuario.papel === 'ADMIN' ? [...NAV, ...NAV_ADMIN] : NAV

  const [recolhido, setRecolhido] = useState(() => localStorage.getItem(CHAVE_RECOLHIDO) === '1')
  // "Espiada" ao passar o mouse/focar (2026-07-31, pedido do dono do produto): com o menu
  // recolhido, hover ou foco expande temporariamente sem alterar a preferência persistida;
  // saindo do mouse ou do foco (pra fora do <nav>, não entre os próprios links) recolhe de novo.
  const [expandidoPorInteracao, setExpandidoPorInteracao] = useState(false)
  const navRef = useRef<HTMLElement>(null)
  const mostrarExpandido = !recolhido || expandidoPorInteracao

  const alternarRecolhido = () => {
    setRecolhido((atual) => {
      const novo = !atual
      localStorage.setItem(CHAVE_RECOLHIDO, novo ? '1' : '0')
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
          {itensNav.map((n) => {
            const Icone = n.icone
            return (
              <NavLink
                key={n.to}
                to={n.to}
                end={n.end}
                className={({ isActive }) => (isActive ? 'active' : '')}
                title={mostrarExpandido ? undefined : n.label}
              >
                <Icone size={20} />
                {mostrarExpandido && <span>{n.label}</span>}
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
