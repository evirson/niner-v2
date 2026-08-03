import { useState } from 'react'

export interface ItemMultiSelect {
  chave: string
  rotulo: string
}

/** Seletor de múltiplos itens genérico (2026-08-04, Relatório de Estoque — Marca/Categorias) —
 *  mesmo comportamento/visual de `EmpresaMultiSelect`, mas parametrizado por chave/rótulo em vez
 *  de amarrado ao tipo `Empresa`. Nenhum selecionado = `rotuloTodos`. */
export default function MultiSelectGenerico({
  itens,
  selecionadas,
  aoAlterar,
  rotuloTodos,
}: {
  itens: ItemMultiSelect[]
  selecionadas: string[]
  aoAlterar: (chaves: string[]) => void
  rotuloTodos: string
}) {
  const [aberto, setAberto] = useState(false)

  const rotulo =
    selecionadas.length === 0
      ? rotuloTodos
      : selecionadas.length === 1
        ? (itens.find((i) => i.chave === selecionadas[0])?.rotulo ?? '1 selecionado')
        : `${selecionadas.length} selecionados`

  const alternar = (chave: string) => {
    aoAlterar(selecionadas.includes(chave) ? selecionadas.filter((s) => s !== chave) : [...selecionadas, chave])
  }

  return (
    <div
      className="empresa-multiselect"
      tabIndex={-1}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setAberto(false)
      }}
    >
      <button type="button" className="btn ghost" onClick={() => setAberto((a) => !a)} aria-haspopup="listbox" aria-expanded={aberto}>
        {rotulo}
      </button>
      {aberto && (
        <div className="empresa-multiselect-lista" role="listbox">
          <button type="button" className="empresa-multiselect-limpar" onClick={() => aoAlterar([])}>
            {rotuloTodos}
          </button>
          {itens.map((item) => (
            <label key={item.chave} className="empresa-multiselect-item">
              <input type="checkbox" checked={selecionadas.includes(item.chave)} onChange={() => alternar(item.chave)} />
              <span>{item.rotulo}</span>
            </label>
          ))}
        </div>
      )}
    </div>
  )
}
