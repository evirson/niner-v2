import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/api'
import {
  TIPOS_MOVIMENTO,
  criarPlanoContas,
  type TipoMovimentoConta,
} from '../lib/planoContas'
import { codigoPlanoContasValido, mascararCodigoPlanoContas } from '../lib/masks'
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
        // Criação rápida sempre gera uma conta analítica (aceita lançamento direto) — é
        // exatamente o que quem está criando na hora precisa (ex.: plano de contas do
        // fornecedor); sintética (agrupadora) só faz sentido na gestão completa da tela própria.
        natureza: 'ANALITICA',
        incluiDre,
        incluiFluxoCaixa,
        // Campos que a revisão do plano de contas (2026-08-13) tornou parte do contrato e que a
        // criação rápida não coleta: vão nulos e o servidor aplica o padrão. `grupoDre`/`grupoDfc`
        // nulos são coerentes com o backend, que força `NAO_APLICA` quando a flag correspondente
        // é falsa — quem precisa classificar usa a tela própria do Plano de Contas.
        descricaoCurta: null,
        grupoDre: null,
        grupoDfc: null,
        observacao: null,
      }),
    onSuccess: (plano) => {
      queryClient.invalidateQueries({ queryKey: ['planos-contas'] })
      aoCriar?.(plano.idPlanoContas)
    },
    onError: (e: unknown) =>
      setToast(e instanceof ApiError ? e.message : 'Não foi possível criar o plano de contas.'),
  })

  // ⚠️ `codigoPlanoContasValido` (achado de auditoria, 2026-08-21): antes bastava não estar
  // vazio, e o servidor recusava com 400 depois de o operador preencher tudo — no meio do cadastro
  // de um fornecedor, que é o motivo de este modal existir. As duas funções já existiam; só a tela
  // cheia as usava.
  const valido = codigoPlanoContasValido(codigo) && descricao.trim() && tipoMovimento

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
          /* ⚠️ O exemplo antigo era `3.1.001` — que o servidor RECUSA: a máscara é 9.99.999, com
             DOIS dígitos no grupo do meio. A única pista que a tela dava estava errada, e o
             operador não tem como deduzir de "9.99.999" que são 1+2+3 dígitos. */
          placeholder="ex.: 3.01.001"
          value={codigo}
          onChange={(e) => setCodigo(mascararCodigoPlanoContas(e.target.value))}
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
