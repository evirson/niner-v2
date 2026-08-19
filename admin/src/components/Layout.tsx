import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../lib/auth'

const ITENS = [
  { para: '/', rotulo: 'Painel', fim: true },
  { para: '/contas', rotulo: 'Contas' },
  { para: '/leads', rotulo: 'Leads' },
  { para: '/configuracao', rotulo: 'Configuração' },
]

export default function Layout() {
  const { sessao, sair } = useAuth()

  return (
    <div className="layout">
      <nav className="lateral" aria-label="Navegação do backoffice">
        <div className="marca">
          <b>Niner</b>
          <small>Plataforma · Vetor</small>
        </div>
        {ITENS.map((i) => (
          <NavLink key={i.para} to={i.para} end={i.fim} className={({ isActive }) => (isActive ? 'ativo' : '')}>
            {i.rotulo}
          </NavLink>
        ))}
        <div className="rodape">
          <div>{sessao?.nome}</div>
          <div className="muted">{sessao?.papel}</div>
          <button type="button" className="btn btn-secundario" style={{ marginTop: 10 }} onClick={sair}>
            Sair
          </button>
        </div>
      </nav>
      <main className="conteudo">
        <Outlet />
      </main>
    </div>
  )
}
