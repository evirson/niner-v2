import { Link, useParams } from 'react-router-dom'
import { useEu } from '../lib/eu'
import { acharGrupo, eGrupo, filtrarPorPapel, MENU, type NavGrupo, type NavItem } from '../lib/menu'

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

/** Subcard: um filho dentro do card de um subgrupo (Caixa, Cancelamentos). Mesma informação do
 * card normal, em escala menor, para a hierarquia ficar visível sem virar outro nível de
 * navegação — o operador vê a área inteira numa tela só. */
function SubCard({ item }: { item: NavItem }) {
  const Icone = item.icone
  return (
    <Link className="menu-subcard" to={item.to}>
      <span className="menu-subcard-icone">
        <Icone size={20} />
      </span>
      <span className="menu-card-texto">
        <strong className="menu-subcard-nome">{item.label}</strong>
        <span className="menu-subcard-descricao">{item.descricao}</span>
      </span>
    </Link>
  )
}

/** Card de um subgrupo: cabeçalho com ícone/nome/descrição e os filhos como subcards dentro. */
function CardDeSubgrupo({ grupo }: { grupo: NavGrupo }) {
  const Icone = grupo.icone
  return (
    <section className="menu-card menu-card-subgrupo">
      <div className="menu-card-subgrupo-cabecalho">
        <span className="menu-card-icone">
          <Icone size={26} />
        </span>
        <span className="menu-card-texto">
          <strong className="menu-card-nome">{grupo.label}</strong>
          <span className="menu-card-descricao">{grupo.descricao}</span>
        </span>
      </div>
      <div className="menu-subcards">
        {grupo.itens.map((filho) =>
          eGrupo(filho) ? <CardDeSubgrupo key={filho.chave} grupo={filho} /> : <SubCard key={filho.to} item={filho} />,
        )}
      </div>
    </section>
  )
}

/** Página-hub de um grupo do menu (2026-08-03): a lateral só lista os grupos principais, e é
 * aqui que a área se abre — um card por filho, com ícone, nome e o que a tela faz; subgrupos
 * viram card com subcards dentro. */
export default function MenuGrupo() {
  const { grupo: chave } = useParams<{ grupo: string }>()
  const { data: eu } = useEu()
  const isAdmin = eu?.usuario.papel === 'ADMIN'

  const menu = filtrarPorPapel(MENU, isAdmin)
  const grupo = chave ? acharGrupo(menu, chave) : null

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
      <div className="titulo-tela">
        <Icone size={34} />
        <h1>{grupo.label}</h1>
      </div>
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
