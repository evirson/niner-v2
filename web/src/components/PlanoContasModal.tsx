import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/api'
import {
  TIPOS_MOVIMENTO,
  criarPlanoContas,
  type TipoMovimentoConta,
} from '../lib/planoContas'
import { maiusculas } from '../lib/texto'
import Toast from './Toast'

/**
 * Criação rápida de plano de contas embutida (docs/telas/fornecedor.md) — mesmo papel do
 * modal de categoria no cadastro de cliente: `fornecedor.id_plano_contas` é obrigatório sem
 * seed padrão, então o usuário precisa conseguir criar uma conta sem sair da tela de
 * fornecedor. A gestão completa continua na tela própria (/planos-contas).
 */
export default function PlanoContasModal({
  aoFechar,
  aoCriar,
}: {
  aoFechar: () => void
  aoCriar?: (idPlanoContas: string) => void
}) {
  const queryClient = useQueryClient()
  const [codigo, setCodigo] = useState('')
  const [descricao, setDescricao] = useState('')
  const [tipoMovimento, setTipoMovimento] = useState<TipoMovimentoConta | ''>('')
  const [incluiDre, setIncluiDre] = useState(false)
  const [incluiFluxoCaixa, setIncluiFluxoCaixa] = useState(false)
  const [toast, setToast] = useState('')

  const criar = useMutation({
    mutationFn: () =>
      criarPlanoContas({
        codigo: maiusculas(codigo.trim()),
        descricao: maiusculas(descricao.trim()),
        tipoMovimento: tipoMovimento as TipoMovimentoConta,
        incluiDre,
        incluiFluxoCaixa,
      }),
    onSuccess: (plano) => {
      queryClient.invalidateQueries({ queryKey: ['planos-contas'] })
      aoCriar?.(plano.idPlanoContas)
    },
    onError: (e: unknown) =>
      setToast(e instanceof ApiError ? e.message : 'Não foi possível criar o plano de contas.'),
  })

  const valido = codigo.trim() && descricao.trim() && tipoMovimento

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Novo plano de contas" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Novo plano de contas</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Criação rápida — a gestão completa fica na tela Plano de Contas.
        </p>

        <label htmlFor="modal-plano-codigo">Código *</label>
        <input
          id="modal-plano-codigo"
          autoFocus
          placeholder="ex.: 3.1.001"
          value={codigo}
          onChange={(e) => setCodigo(maiusculas(e.target.value))}
        />

        <label htmlFor="modal-plano-descricao">Descrição *</label>
        <input
          id="modal-plano-descricao"
          value={descricao}
          onChange={(e) => setDescricao(maiusculas(e.target.value))}
        />

        <label htmlFor="modal-plano-tipo">Tipo de movimento *</label>
        <select
          id="modal-plano-tipo"
          value={tipoMovimento}
          onChange={(e) => setTipoMovimento(e.target.value as TipoMovimentoConta)}
        >
          <option value="">Selecione…</option>
          {TIPOS_MOVIMENTO.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>

        <label className="checkbox-linha">
          <input type="checkbox" checked={incluiDre} onChange={(e) => setIncluiDre(e.target.checked)} />
          Compõe a DRE
        </label>
        <label className="checkbox-linha">
          <input
            type="checkbox"
            checked={incluiFluxoCaixa}
            onChange={(e) => setIncluiFluxoCaixa(e.target.checked)}
          />
          Compõe o fluxo de caixa
        </label>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Cancelar
          </button>
          <button
            type="button"
            className="btn"
            disabled={!valido || criar.isPending}
            onClick={() => criar.mutate()}
          >
            {criar.isPending ? 'Criando…' : 'Criar'}
          </button>
        </div>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
