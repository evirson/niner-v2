import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import CabecalhoModal from './CabecalhoModal'
import {
  buscarVariacoesMovimentacao,
  type VariacaoEncontrada,
} from '../lib/relatorioMovimentacaoProdutos'

/**
 * Escolher uma variação do ERP — usado pela tela de vínculo de anúncios (R6).
 *
 * ⚠️ Reusa a busca de variações que o Kardex já expõe em vez de criar um endpoint novo: os dois
 * precisam exatamente do mesmo — achar uma variação por descrição, SKU ou marca. Um segundo
 * endpoint com a mesma consulta seria dois lugares para consertar quando a regra mudar.
 *
 * ⚠️ Fechar é o ✕ do canto (`CabecalhoModal`); o rodapé é da ação, e aqui a ação é clicar na
 * linha — por isso não há botão "Selecionar".
 */
export default function EscolherVariacaoModal({
  titulo,
  aoEscolher,
  aoFechar,
}: {
  titulo: string
  aoEscolher: (v: VariacaoEncontrada) => void
  aoFechar: () => void
}) {
  const [busca, setBusca] = useState('')
  const [buscaAplicada, setBuscaAplicada] = useState('')
  const campo = useRef<HTMLInputElement>(null)

  // Convenção de tela de localização (2026-08-22): o foco começa no campo principal.
  useEffect(() => {
    campo.current?.focus()
  }, [])

  const { data, isFetching } = useQuery({
    queryKey: ['variacoes-busca', buscaAplicada],
    queryFn: () => buscarVariacoesMovimentacao(buscaAplicada),
    enabled: buscaAplicada.length > 0,
  })

  return (
    <div className="lightbox-fundo" role="dialog" aria-modal="true">
      <div className="lightbox-caixa" style={{ maxWidth: 760, display: 'flex', flexDirection: 'column' }}>
        <CabecalhoModal titulo={titulo} aoFechar={aoFechar} />

        <div style={{ padding: 16, display: 'flex', gap: 8, flexShrink: 0 }}>
          <input
            ref={campo}
            type="text"
            className="campo"
            style={{ flex: 1 }}
            placeholder="Descrição, marca ou código de barras"
            value={busca}
            onChange={(e) => setBusca(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                setBuscaAplicada(busca.trim())
              }
            }}
          />
          <button type="button" className="btn" onClick={() => setBuscaAplicada(busca.trim())}>
            Pesquisar
          </button>
        </div>

        {/* ⚠️ `flex:1` + `min-height:0` — sem o min-height a grade empurra o popup para fora da
            janela em telas baixas, em vez de rolar por dentro (lição de 2026-08-25). */}
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '0 16px 16px' }}>
          {buscaAplicada.length === 0 && (
            <p className="muted">Digite algo e pressione Enter para localizar o produto.</p>
          )}
          {buscaAplicada.length > 0 && isFetching && <p className="muted">Procurando…</p>}
          {buscaAplicada.length > 0 && !isFetching && (data ?? []).length === 0 && (
            <p className="muted">Nenhum produto encontrado para “{buscaAplicada}”.</p>
          )}
          {(data ?? []).length > 0 && (
            <table className="tabela">
              <thead>
                <tr>
                  <th>Produto</th>
                  <th>Cor / Tamanho</th>
                  <th>Código de barras</th>
                </tr>
              </thead>
              <tbody>
                {(data ?? []).map((v) => (
                  <tr
                    key={v.idVariacao}
                    style={{ cursor: 'pointer' }}
                    onClick={() => aoEscolher(v)}
                    title="Selecionar este produto"
                  >
                    <td>
                      {v.descricaoProduto}
                      {v.marca ? <span className="muted"> · {v.marca}</span> : null}
                    </td>
                    <td>
                      {v.variacaoCor ?? '—'} / {v.variacaoTamanho ?? '—'}
                    </td>
                    <td className="mono">{v.sku}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  )
}
