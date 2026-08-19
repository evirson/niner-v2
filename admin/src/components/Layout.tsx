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
          <span className="marca-linha">
            <svg className="marca-simbolo" width="26" height="26" viewBox="0 0 32 32" aria-hidden="true">
            <rect width="32" height="32" rx="7" fill="var(--accent)" />
            <text x="15" y="22.5" fontFamily="Georgia, 'Iowan Old Style', serif" fontSize="19"
              fill="var(--accent-contrast)" textAnchor="middle">N</text>
            <rect x="20" y="23" width="7" height="3" rx="1.5" fill="var(--neon)" />
          </svg>
            <b>Nainer</b>
          </span>
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
