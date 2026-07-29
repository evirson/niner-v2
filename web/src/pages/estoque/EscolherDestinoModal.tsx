import { useEffect, useRef, useState } from 'react'
import type { Empresa } from '../../lib/empresas'

/**
 * Primeiro passo de "Nova Transferência" (2026-07-29) — mostra a empresa de origem (sempre a
 * ativa da sessão) e pede a de destino antes de liberar a tela de produtos.
 */
export default function EscolherDestinoModal({
  nomeOrigem,
  opcoesDestino,
  aoFechar,
  aoConfirmar,
}: {
  nomeOrigem: string
  opcoesDestino: Empresa[]
  aoFechar: () => void
  aoConfirmar: (idEmpresaDestino: number) => void
}) {
  const [idEmpresaDestino, setIdEmpresaDestino] = useState<number | ''>('')
  const campoRef = useRef<HTMLSelectElement>(null)

  useEffect(() => {
    campoRef.current?.focus()
  }, [])

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Nova transferência" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Nova Transferência</h2>
        <div className="form-grid">
          <div className="col-12">
            <label>Empresa de Origem</label>
            <div className="pdv-selecao-valor">
              <span>{nomeOrigem}</span>
            </div>
          </div>
          <div className="col-12">
            <label htmlFor="popup-empresa-destino">Empresa de Destino *</label>
            <select
              id="popup-empresa-destino"
              ref={campoRef}
              value={idEmpresaDestino}
              onChange={(e) => setIdEmpresaDestino(e.target.value ? Number(e.target.value) : '')}
            >
              <option value="">Selecione…</option>
              {opcoesDestino.map((emp) => (
                <option key={emp.idEmpresa} value={emp.idEmpresa}>
                  {emp.nomeFantasia ?? emp.razaoSocial}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Cancelar
          </button>
          <button
            type="button"
            className="btn"
            disabled={idEmpresaDestino === ''}
            onClick={() => aoConfirmar(Number(idEmpresaDestino))}
          >
            Continuar
          </button>
        </div>
      </div>
    </div>
  )
}
