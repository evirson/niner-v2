import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'

const NAV = [
  { to: '/', label: 'Painel', end: true },
  { to: '/produtos', label: 'Produtos' },
  { to: '/estoque', label: 'Estoque' },
  { to: '/pedidos', label: 'Pedidos' },
  { to: '/canais', label: 'Canais' },
]

/** Shell do ERP: cabeçalho + navegação lateral + área de conteúdo. */
export default function Layout() {
  const { logout } = useAuth()
  const navigate = useNavigate()

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
          {NAV.map((n) => (
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
