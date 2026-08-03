import { useRef, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { useEu } from '../lib/eu'
import { filtrarPorPapel, MENU, rotaDoGrupo, type NavGrupo } from '../lib/menu'
import { IconeMenuHamburguer } from './Icones'

const CHAVE_RECOLHIDO = 'niner_nav_recolhido'

/** Shell do ERP: cabeçalho + navegação lateral (retrátil, 2026-07-28) + área de conteúdo.
 *
 * Desde 2026-08-03 a lateral lista **apenas os grupos principais** — a árvore de sub-itens saiu
 * (pedido do dono do produto). Cada grupo leva à sua página-hub (`MenuGrupo.tsx`), onde os
 * filhos aparecem como cards com ícone, nome e explicação, e um subgrupo vira um card com seus
 * subcards dentro. */
export default function Layout() {
  const { logout } = useAuth()
  const navigate = useNavigate()
  const { data: eu } = useEu()
  const isAdmin = eu?.usuario.papel === 'ADMIN'
  // MENU só tem NavGrupo no topo, e filtrarPorPapel preserva isso — o cast evita o TS2339
  // pré-existente (grupo.chave) que apareceria por filtrarPorPapel devolver NavNode[] genérico.
  const menu = filtrarPorPapel(MENU, isAdmin) as NavGrupo[]

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
          {/* Hambúrguer no topo (2026-08-03, padrão mobile) — antes ficava no rodapé do menu. */}
          <button
            type="button"
            className="app-nav-toggle"
            onClick={alternarRecolhido}
            aria-expanded={!recolhido}
            title={recolhido ? 'Expandir menu' : 'Recolher menu'}
          >
            <IconeMenuHamburguer size={20} />
            {mostrarExpandido && <span>Menu</span>}
          </button>
          {menu.map((grupo) => {
            const Icone = grupo.icone
            return (
              <NavLink
                key={grupo.chave}
                to={rotaDoGrupo(grupo.chave)}
                className={({ isActive }) => (isActive ? 'active' : '')}
                title={grupo.label}
              >
                <Icone size={mostrarExpandido ? 18 : 20} />
                {mostrarExpandido && <span>{grupo.label}</span>}
              </NavLink>
            )
          })}
        </nav>
        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
