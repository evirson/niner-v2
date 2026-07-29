import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { useEu } from '../lib/eu'
import {
  IconeCanais,
  IconeCliente,
  IconeEstoque,
  IconeFornecedor,
  IconeFuncionario,
  IconePainel,
  IconeParametros,
  IconePdv,
  IconePedidos,
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
  { to: '/pdv', label: 'PDV', icone: IconePdv },
  { to: '/produtos', label: 'Produtos', icone: IconeProduto },
  { to: '/estoque', label: 'Estoque', icone: IconeEstoque },
  { to: '/pedidos', label: 'Pedidos', icone: IconePedidos },
  { to: '/canais', label: 'Canais', icone: IconeCanais },
  { to: '/clientes', label: 'Clientes', icone: IconeCliente },
  { to: '/fornecedores', label: 'Fornecedores', icone: IconeFornecedor },
  { to: '/funcionarios', label: 'Funcionários', icone: IconeFuncionario },
  { to: '/planos-contas', label: 'Plano de Contas', icone: IconePlanoContas },
  { to: '/tipos-carteira', label: 'Tipo de Carteira', icone: IconeTipoCarteira },
  { to: '/recebimento-crediario', label: 'Recebimento de Crediário', icone: IconeRecebimentoCrediario },
  { to: '/estorno-recebimento-crediario', label: 'Estorno de Crediário', icone: IconeEstornoRecebimentoCrediario },
]

/** Só ADMIN vê este item — a rota em si também é protegida por `RequireAdmin` (defesa em profundidade). */
const NAV_ADMIN: ItemNav[] = [
  { to: '/usuarios', label: 'Usuários', icone: IconeUsuario },
  { to: '/configuracoes-gerais', label: 'Parâmetros do Sistema', icone: IconeParametros },
]

/** Shell do ERP: cabeçalho + navegação lateral (retrátil, 2026-07-28) + área de conteúdo. */
export default function Layout() {
  const { logout } = useAuth()
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const itensNav = eu?.usuario.papel === 'ADMIN' ? [...NAV, ...NAV_ADMIN] : NAV

  const [recolhido, setRecolhido] = useState(() => localStorage.getItem(CHAVE_RECOLHIDO) === '1')

  const alternarRecolhido = () => {
    setRecolhido((atual) => {
      const novo = !atual
      localStorage.setItem(CHAVE_RECOLHIDO, novo ? '1' : '0')
      return novo
    })
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
        <nav className={`app-nav${recolhido ? ' app-nav-recolhido' : ''}`}>
          {itensNav.map((n) => {
            const Icone = n.icone
            return (
              <NavLink
                key={n.to}
                to={n.to}
                end={n.end}
                className={({ isActive }) => (isActive ? 'active' : '')}
                title={recolhido ? n.label : undefined}
              >
                <Icone size={20} />
                {!recolhido && <span>{n.label}</span>}
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
            {!recolhido && <span>Recolher</span>}
          </button>
        </nav>
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
