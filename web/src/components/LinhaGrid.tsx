import type { ReactNode } from 'react'
import { distribuirSpans } from '../lib/grid'

interface ItemLinha {
  /** `false` some da linha (campo oculto pela configuração de tela) sem deixar vão vazio. */
  visivel: boolean
  /** Peso relativo — mesma escala dos `col-N` (ex.: 3, 6, 9). */
  peso: number
  children: ReactNode
}

/**
 * Uma "linha" de campos dentro de um `.form-grid` (12 colunas, §3.7) que se reajusta quando
 * algum item está oculto: os que sobram crescem proporcionalmente para preencher a linha,
 * em vez de deixar espaço vazio (docs/telas/cliente.md).
 */
export default function LinhaGrid({ itens }: { itens: ItemLinha[] }) {
  const visiveis = itens.filter((i) => i.visivel)
  if (visiveis.length === 0) return null

  const spans = distribuirSpans(visiveis.map((i) => i.peso))

  return (
    <>
      {visiveis.map((item, i) => (
        <div key={i} style={{ gridColumn: `span ${spans[i]}` }}>
          {item.children}
        </div>
      ))}
    </>
  )
}
