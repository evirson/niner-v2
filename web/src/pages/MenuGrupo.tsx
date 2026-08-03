import { Link, useParams } from 'react-router-dom'
import { IconeVoltar } from '../components/Icones'
import { useEu } from '../lib/eu'
import { acharGrupo, acharPai, eGrupo, filtrarPorPapel, MENU, rotaDoGrupo, type NavGrupo, type NavItem } from '../lib/menu'

function CardDeItem({ item }: { item: NavItem }) {
  const Icone = item.icone
  return (
    <Link className="menu-card" to={item.to}>
      <span className="menu-card-icone">
        <Icone size={26} />
      </span>
      <span className="menu-card-texto">
        <strong className="menu-card-nome">{item.label}</strong>
        <span className="menu-card-descricao">{item.descricao}</span>
      </span>
    </Link>
  )
}

/** Card de um subgrupo (Caixa, Cancelamentos): abre o próximo nível — a página-hub do próprio
 * subgrupo, onde os filhos aparecem como cards. A contagem de telas antecipa o que tem lá
 * dentro, já que o conteúdo não fica visível aqui. */
function CardDeSubgrupo({ grupo }: { grupo: NavGrupo }) {
  const Icone = grupo.icone
  const quantidade = grupo.itens.length
  return (
    <Link className="menu-card" to={rotaDoGrupo(grupo.chave)}>
      <span className="menu-card-icone">
        <Icone size={26} />
      </span>
      <span className="menu-card-texto">
        <strong className="menu-card-nome">{grupo.label}</strong>
        <span className="menu-card-descricao">{grupo.descricao}</span>
        <span className="menu-card-contagem">
          {quantidade} {quantidade === 1 ? 'tela' : 'telas'}
        </span>
      </span>
    </Link>
  )
}

/** Página-hub de um grupo do menu (2026-08-03): a lateral só lista os grupos principais, e é
 * aqui que a área se abre — um card por filho, com ícone, nome e o que a tela faz. Um filho que
 * é subgrupo abre o próximo nível, com seta de retorno para o grupo de cima. */
export default function MenuGrupo() {
  const { grupo: chave } = useParams<{ grupo: string }>()
  const { data: eu } = useEu()
  const isAdmin = eu?.usuario.papel === 'ADMIN'

  const menu = filtrarPorPapel(MENU, isAdmin)
  const grupo = chave ? acharGrupo(menu, chave) : null
  const pai = chave ? acharPai(menu, chave) : null

  if (!grupo) {
    return (
      <div>
        <p className="eyebrow">Menu</p>
        <h1 style={{ marginTop: 4 }}>Área não encontrada</h1>
        <p className="muted">Esta área do menu não existe ou não está disponível para o seu perfil de acesso.</p>
        <Link className="btn ghost" to="/" style={{ marginTop: 16, display: 'inline-flex' }}>
          Voltar ao painel
        </Link>
      </div>
    )
  }

  const Icone = grupo.icone

  return (
    <div>
      <div className="menu-hub-topo">
        {pai && (
          <Link className="menu-hub-voltar" to={rotaDoGrupo(pai.chave)} title={`Voltar para ${pai.label}`} aria-label={`Voltar para ${pai.label}`}>
            <IconeVoltar size={20} />
          </Link>
        )}
        <div className="titulo-tela">
          <Icone size={34} />
          <h1>{grupo.label}</h1>
        </div>
      </div>
      {pai && <p className="eyebrow menu-hub-trilha">{pai.label}</p>}
      <p className="muted menu-hub-descricao">{grupo.descricao}</p>

      {grupo.itens.length === 0 ? (
        <p className="muted" style={{ marginTop: 20 }}>Esta área ainda está em construção.</p>
      ) : (
        <div className="menu-cards">
          {grupo.itens.map((filho) =>
            eGrupo(filho) ? <CardDeSubgrupo key={filho.chave} grupo={filho} /> : <CardDeItem key={filho.to} item={filho} />,
          )}
        </div>
      )}
    </div>
  )
}
