import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { useEu } from '../lib/eu'

interface ItemNav {
  to: string
  label: string
  end?: boolean
}

const NAV: ItemNav[] = [
  { to: '/', label: 'Painel', end: true },
  { to: '/produtos', label: 'Produtos' },
  { to: '/estoque', label: 'Estoque' },
  { to: '/pedidos', label: 'Pedidos' },
  { to: '/canais', label: 'Canais' },
  { to: '/clientes', label: 'Clientes' },
  { to: '/fornecedores', label: 'Fornecedores' },
  { to: '/funcionarios', label: 'Funcionários' },
  { to: '/planos-contas', label: 'Plano de Contas' },
  { to: '/moedas', label: 'Moeda' },
  { to: '/tipos-carteira', label: 'Tipo de Carteira' },
]

/** Só ADMIN vê este item — a rota em si também é protegida por `RequireAdmin` (defesa em profundidade). */
const NAV_ADMIN: ItemNav[] = [{ to: '/configuracoes-gerais', label: 'Parâmetros do Sistema' }]

/** Shell do ERP: cabeçalho + navegação lateral + área de conteúdo. */
export default function Layout() {
  const { logout } = useAuth()
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const itensNav = eu?.usuario.papel === 'ADMIN' ? [...NAV, ...NAV_ADMIN] : NAV

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
        <button className="btn ghost" onClick={sair}>
          Sair
        </button>
      </header>
      <div className="app-body">
        <nav className="app-nav">
          {itensNav.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.end} className={({ isActive }) => (isActive ? 'active' : '')}>
              {n.label}
            </NavLink>
          ))}
        </nav>
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
