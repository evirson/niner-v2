import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeConfirmar } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { efetivarBalanco, obterDiferencas } from '../../lib/estoqueBalanco'
import { formatarQuantidade } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'

const FRASE_CONFIRMACAO_EFETIVAR = 'efetiva contagem'

/**
 * Efetivar Balanço de Estoque (2026-08-04) — tela separada de "Verificar Diferenças" (pedido do
 * dono do produto), mas mostra a mesma grid de diferenças pra revisar antes de confirmar. Grava
 * um ajuste de estoque (tipo AJUSTE) por produto com diferença e zera o balanço ativo da empresa
 * logada — sempre com confirmação explícita antes, e só efetiva de fato depois do usuário digitar
 * a frase de confirmação (2026-08-04, mesmo padrão de "Zerar Contagem de Estoque").
 */
export default function EfetivarBalanco() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [confirmando, setConfirmando] = useState(false)
  const [textoConfirmacaoEfetivar, setTextoConfirmacaoEfetivar] = useState('')
  const [toast, setToast] = useState<{ texto: string; tipo: 'erro' | 'sucesso' } | null>(null)

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const { data, isLoading, error } = useQuery({ queryKey: ['balanco-diferencas'], queryFn: obterDiferencas })

  const efetivar = useMutation({
    mutationFn: efetivarBalanco,
    onSuccess: (resposta) => {
      setConfirmando(false)
      setTextoConfirmacaoEfetivar('')
      queryClient.invalidateQueries({ queryKey: ['balanco-contagem'] })
      queryClient.invalidateQueries({ queryKey: ['balanco-diferencas'] })
      queryClient.invalidateQueries({ queryKey: ['balanco-ultima-efetivacao'] })
      setToast({ texto: `Balanço efetivado — ${resposta.totalProdutosAjustados} produto(s) ajustado(s).`, tipo: 'sucesso' })
    },
    onError: (e: unknown) => {
      setConfirmando(false)
      setTextoConfirmacaoEfetivar('')
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível efetivar o balanço.', tipo: 'erro' })
    },
  })

  const linhas = data?.linhas ?? []
  const totalDiferenca = linhas.reduce((s, l) => s + l.diferenca, 0)
  const totalEstoque = linhas.reduce((s, l) => s + l.qtdEstoque, 0)
  const totalContado = linhas.reduce((s, l) => s + l.qtdContada, 0)

  function corDiferenca(valor: number): string | undefined {
    if (valor > 0) return 'var(--sucesso)'
    if (valor < 0) return 'var(--danger)'
    return undefined
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeConfirmar size={34} />
            <h1>Efetivar Balanço de Estoque</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="estoque.efetivar-balanco" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo relatorio-corpo-fixo">
        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : error || !data ? (
          <p className="erro">{error instanceof ApiError ? error.message : 'Não foi possível carregar as diferenças.'}</p>
        ) : (
          <div className="relatorio-conteudo">
            <div className="topbar-acoes" style={{ justifyContent: 'flex-start', marginBottom: 12 }}>
              <button
                type="button"
                className="btn"
                disabled={linhas.length === 0}
                onClick={() => {
                  setTextoConfirmacaoEfetivar('')
                  setConfirmando(true)
                }}
              >
                Efetivar Balanço
              </button>
            </div>

            <p className="section-label" style={{ marginTop: 0 }}>
              Diferenças a Efetivar
            </p>
            <div className="card table-wrap">
              {!data.existeContagemAtiva ? (
                <p className="muted">Não há nenhuma contagem de estoque em andamento nesta empresa — não há nada para efetivar.</p>
              ) : linhas.length === 0 ? (
                <p className="muted">Nenhuma diferença encontrada — não há nada para efetivar.</p>
              ) : (
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Descrição</th>
                      <th>Cor</th>
                      <th>Tamanho</th>
                      <th style={{ textAlign: 'right' }}>Qtd Estoque</th>
                      <th style={{ textAlign: 'right' }}>Qtd Contada</th>
                      <th style={{ textAlign: 'right' }}>Diferença</th>
                    </tr>
                  </thead>
                  <tbody>
                    {linhas.map((linha) => (
                      <tr key={linha.idVariacao}>
                        <td>{linha.descricaoProduto}</td>
                        <td>{linha.variacaoCor ?? '—'}</td>
                        <td>{linha.variacaoTamanho ?? '—'}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>{formatarQuantidade(linha.qtdEstoque, permiteQtdDecimal)}</td>
                        <td className="mono" style={{ textAlign: 'right' }}>{formatarQuantidade(linha.qtdContada, permiteQtdDecimal)}</td>
                        <td className="mono" style={{ textAlign: 'right', color: corDiferenca(linha.diferenca) }}>
                          {linha.diferenca > 0 ? '+' : ''}
                          {formatarQuantidade(linha.diferenca, permiteQtdDecimal)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colSpan={5}>
                        <strong>Total ({linhas.length} produto(s))</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right', color: corDiferenca(totalDiferenca) }}>
                        <strong>
                          {totalDiferenca > 0 ? '+' : ''}
                          {formatarQuantidade(totalDiferenca, permiteQtdDecimal)}
                        </strong>
                      </td>
                    </tr>
                  </tfoot>
                </table>
              )}
            </div>
          </div>
        )}
      </div>

      {confirmando && (
        <div
          className="modal-overlay"
          onClick={() => {
            if (efetivar.isPending) return
            setConfirmando(false)
            setTextoConfirmacaoEfetivar('')
          }}
        >
          <div className="modal" role="dialog" aria-label="Confirmar efetivação de balanço" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Efetivar Balanço de Estoque?</h2>
            <p>
              <strong>{linhas.length} produto(s)</strong> terão o estoque ajustado para bater com a quantidade contada. A
              contagem ativa desta empresa será zerada em seguida.
            </p>
            <p style={{ marginTop: 4 }}>
              <span className="muted">Total contado: </span>
              <strong>{formatarQuantidade(totalContado, permiteQtdDecimal)}</strong>
              <span className="muted"> — Total em estoque: </span>
              <strong>{formatarQuantidade(totalEstoque, permiteQtdDecimal)}</strong>
            </p>
            <p className="muted">Você pode desfazer esta efetivação depois, em "Zerar Contagem de Estoque".</p>
            <p>
              Para confirmar, digite <strong>{FRASE_CONFIRMACAO_EFETIVAR}</strong> abaixo:
            </p>
            <input
              type="text"
              autoFocus
              value={textoConfirmacaoEfetivar}
              onChange={(e) => setTextoConfirmacaoEfetivar(maiusculas(e.target.value))}
              disabled={efetivar.isPending}
              placeholder={FRASE_CONFIRMACAO_EFETIVAR}
            />
            <div className="ajuda-rodape">
              <button
                type="button"
                className="btn ghost"
                disabled={efetivar.isPending}
                onClick={() => {
                  setConfirmando(false)
                  setTextoConfirmacaoEfetivar('')
                }}
              >
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={efetivar.isPending || textoConfirmacaoEfetivar.trim().toLowerCase() !== FRASE_CONFIRMACAO_EFETIVAR}
                onClick={() => efetivar.mutate()}
              >
                {efetivar.isPending ? 'Efetivando…' : 'Sim, efetivar'}
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
