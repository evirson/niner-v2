import { useRef, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { useEu } from '../lib/eu'
import { filtrarPorPapel, filtrarPorPermissao, MENU, rotaDoGrupo, type NavGrupo } from '../lib/menu'
import { minhasPermissoes } from '../lib/permissoes'
import { useQuery } from '@tanstack/react-query'
import BuscaDeTelas from './BuscaDeTelas'
import { IconeAlfinete, IconeMenuHamburguer } from './Icones'
import SeletorTema from './SeletorTema'

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
    // RBAC (V073): o menu mostra só o que o usuário pode acessar. Enquanto a grade não chegou,
  // `null` mantém o menu inteiro — menu piscando vazio parece sistema quebrado.
  const { data: permissoes } = useQuery({ queryKey: ['minhas-permissoes'], queryFn: minhasPermissoes, staleTime: 60_000 })
  const permitidas = permissoes
    ? new Set(permissoes.filter((p) => p.acessar).map((p) => p.chave))
    : null
  const menu = filtrarPorPermissao(filtrarPorPapel(MENU, isAdmin), permitidas) as NavGrupo[]

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
          NAI<span>NER</span>
        </a>
        {/* Sempre renderizado, mesmo vazio: é a coluna do meio do grid do cabeçalho, e é ela
            que mantém o nome da loja centralizado na tela independentemente das laterais. */}
        <span className="muted app-empresa-ativa" title="Empresa ativa nesta sessão">
          {eu?.empresa?.nome ?? ''}
        </span>
        <div className="app-header-direita">
          <BuscaDeTelas />
          <SeletorTema />
          <button className="btn ghost" onClick={sair}>
            Sair
          </button>
        </div>
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
          {/* Hambúrguer/alfinete no topo (2026-08-03, padrão mobile) — antes ficava no rodapé.
              O ícone reflete a PREFERÊNCIA salva, não o que está na tela: com o menu no modo
              retrátil, a "espiada" do hover o deixa visualmente igual ao travado em aberto, e
              sem esse contraste o usuário não sabe em qual dos dois está. */}
          <button
            type="button"
            className={`app-nav-toggle${recolhido ? '' : ' app-nav-toggle-fixo'}`}
            onClick={alternarRecolhido}
            aria-pressed={!recolhido}
            aria-label={recolhido ? 'Menu retrátil. Travar em aberto' : 'Menu travado em aberto. Voltar ao modo retrátil'}
            title={recolhido ? 'Menu retrátil — clique para travar em aberto' : 'Menu travado em aberto — clique para voltar ao modo retrátil'}
          >
            {recolhido ? <IconeMenuHamburguer size={20} /> : <IconeAlfinete size={20} />}
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
