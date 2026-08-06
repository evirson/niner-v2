import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError } from '../../lib/api'
import { buscarEtiquetaConfig, listarEtiquetasConfig, type EtiquetaConfig } from '../../lib/etiquetaConfig'

/**
 * Popup obrigatório antes de imprimir (item 5 do pedido, 2026-08-05) — pede qual modelo de
 * etiqueta (`cfg_etiqueta_config`, já criado em Configuração de Etiqueta de Produtos) usar pra
 * emitir o lote selecionado. Só lista configurações ativas.
 */
export default function EscolherModeloModal({
  totalEtiquetas,
  aoFechar,
  aoConfirmar,
}: {
  totalEtiquetas: number
  aoFechar: () => void
  aoConfirmar: (config: EtiquetaConfig) => void
}) {
  const [idConfig, setIdConfig] = useState<number | ''>('')
  const [erro, setErro] = useState('')

  const { data: pagina } = useQuery({
    queryKey: ['etiquetas-config-emissao'],
    queryFn: () => listarEtiquetasConfig({ tamanho: 100 }),
  })
  const configs = (pagina?.itens ?? []).filter((c) => c.ativo)

  const confirmar = useMutation({
    mutationFn: () => {
      if (idConfig === '') throw new ApiError(400, 'Selecione o modelo de etiqueta.')
      return buscarEtiquetaConfig(idConfig as number)
    },
    onSuccess: (config) => aoConfirmar(config),
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível carregar o modelo escolhido.'),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Escolher Modelo de Etiqueta" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Escolher Modelo de Etiqueta</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Serão impressas <strong>{totalEtiquetas}</strong> etiqueta{totalEtiquetas === 1 ? '' : 's'} no modelo escolhido.
        </p>

        <label htmlFor="emissao-modelo">Modelo *</label>
        <select id="emissao-modelo" value={idConfig} onChange={(e) => setIdConfig(e.target.value === '' ? '' : Number(e.target.value))}>
          <option value="">Selecione…</option>
          {configs.map((c) => (
            <option key={c.idConfigEtiqueta} value={c.idConfigEtiqueta}>
              {c.nome}
            </option>
          ))}
        </select>
        {configs.length === 0 && (
          <p className="muted" style={{ marginTop: 8 }}>
            Nenhuma configuração de etiqueta ativa — crie uma em "Configuração de Etiqueta de Produtos" primeiro.
          </p>
        )}

        {erro && <p className="erro-campo">{erro}</p>}
        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Cancelar
          </button>
          <button type="button" className="btn" disabled={confirmar.isPending} onClick={() => confirmar.mutate()}>
            {confirmar.isPending ? 'Carregando…' : 'Imprimir'}
          </button>
        </div>
      </div>
    </div>
  )
}
