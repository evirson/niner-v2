import { useState } from 'react'
import type { Empresa } from '../lib/empresas'

/**
 * Seletor de múltiplas empresas (novo no projeto, 2026-07-31 — Relatório de Vendas). Só
 * aparece pra ADMIN nas telas que usam; nenhuma selecionada = "Todas as empresas" (mesmo
 * padrão do combo único já usado em Pesquisa de Vendas/Fechamento de Caixa).
 */
export default function EmpresaMultiSelect({
  empresas,
  selecionadas,
  aoAlterar,
}: {
  empresas: Empresa[]
  selecionadas: number[]
  aoAlterar: (ids: number[]) => void
}) {
  const [aberto, setAberto] = useState(false)

  const rotulo =
    selecionadas.length === 0
      ? 'Todas as empresas'
      : selecionadas.length === 1
        ? (empresas.find((e) => e.idEmpresa === selecionadas[0])?.nomeFantasia ??
          empresas.find((e) => e.idEmpresa === selecionadas[0])?.razaoSocial ??
          '1 empresa')
        : `${selecionadas.length} empresas selecionadas`

  const alternar = (id: number) => {
    aoAlterar(selecionadas.includes(id) ? selecionadas.filter((s) => s !== id) : [...selecionadas, id])
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
            Todas as empresas
          </button>
          {empresas.map((emp) => (
            <label key={emp.idEmpresa} className="empresa-multiselect-item">
              <input type="checkbox" checked={selecionadas.includes(emp.idEmpresa)} onChange={() => alternar(emp.idEmpresa)} />
              <span>{emp.nomeFantasia ?? emp.razaoSocial}</span>
            </label>
          ))}
        </div>
      )}
    </div>
  )
}
