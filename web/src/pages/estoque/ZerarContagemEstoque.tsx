import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeEstoque } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { desfazerUltimaEfetivacao, listarContagemAtiva, obterUltimaEfetivacao, zerarContagem } from '../../lib/estoqueBalanco'
import { formatarQuantidade } from '../../lib/masks'

const CHAVE_TELA = 'estoque.zerar-contagem'
const FRASE_CONFIRMACAO_ZERAR = 'zerar estoque'

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR')
}

/**
 * Zerar Contagem de Estoque + Desfazer Última Efetivação (2026-08-04) — duas ações raras e de
 * alto impacto sobre o mesmo balanço, por isso agrupadas na mesma tela (pedido do dono do
 * produto): zerar apaga o trabalho de contagem em andamento; desfazer reverte a última
 * efetivação (estoque volta ao estado anterior, a contagem daquela efetivação volta pro balanço
 * ativo). As duas exigem confirmação explícita — sem clique acidental — e agem sempre só sobre a
 * empresa em que o usuário está logado.
 */
export default function ZerarContagemEstoque() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [toast, setToast] = useState<{ texto: string; tipo: 'erro' | 'sucesso' } | null>(null)
  const [confirmandoZerar, setConfirmandoZerar] = useState(false)
  const [confirmandoDesfazer, setConfirmandoDesfazer] = useState(false)
  const [textoConfirmacaoZerar, setTextoConfirmacaoZerar] = useState('')

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const { data: linhas, isLoading } = useQuery({ queryKey: ['balanco-contagem'], queryFn: listarContagemAtiva })
  const { data: ultimaEfetivacao, isLoading: carregandoUltima } = useQuery({
    queryKey: ['balanco-ultima-efetivacao'],
    queryFn: obterUltimaEfetivacao,
  })

  const zerar = useMutation({
    mutationFn: zerarContagem,
    onSuccess: () => {
      setConfirmandoZerar(false)
      setTextoConfirmacaoZerar('')
      queryClient.invalidateQueries({ queryKey: ['balanco-contagem'] })
      setToast({ texto: 'Contagem de estoque zerada.', tipo: 'sucesso' })
    },
    onError: (e: unknown) => {
      setConfirmandoZerar(false)
      setTextoConfirmacaoZerar('')
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível zerar a contagem.', tipo: 'erro' })
    },
  })

  const desfazer = useMutation({
    mutationFn: desfazerUltimaEfetivacao,
    onSuccess: () => {
      setConfirmandoDesfazer(false)
      queryClient.invalidateQueries({ queryKey: ['balanco-contagem'] })
      queryClient.invalidateQueries({ queryKey: ['balanco-ultima-efetivacao'] })
      setToast({ texto: 'Última efetivação de balanço desfeita.', tipo: 'sucesso' })
    },
    onError: (e: unknown) => {
      setConfirmandoDesfazer(false)
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível desfazer a efetivação.', tipo: 'erro' })
    },
  })

  const totalProdutos = (linhas ?? []).length
  const totalQuantidade = (linhas ?? []).reduce((s, l) => s + l.qtdContada, 0)

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>Zerar Contagem de Estoque</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes">
          <section className="section">
            <p className="section-label" style={{ margin: 0 }}>
              Contagem Ativa
            </p>
            {isLoading ? (
              <p className="muted">Carregando…</p>
            ) : (
              <>
                <p style={{ marginTop: 8 }}>
                  <span className="muted">Produtos contados: </span>
                  <strong>{totalProdutos}</strong>
                </p>
                <p style={{ marginTop: 4 }}>
                  <span className="muted">Quantidade total: </span>
                  <strong>{formatarQuantidade(totalQuantidade, permiteQtdDecimal)}</strong>
                </p>
                <button
                  type="button"
                  className="btn"
                  style={{ marginTop: 16 }}
                  disabled={totalProdutos === 0}
                  onClick={() => {
                    setTextoConfirmacaoZerar('')
                    setConfirmandoZerar(true)
                  }}
                >
                  Zerar Contagem de Estoque
                </button>
                {totalProdutos === 0 && (
                  <p className="muted" style={{ marginTop: 8 }}>
                    Não há nenhum produto contado nesta empresa no momento.
                  </p>
                )}
              </>
            )}
          </section>

          <section className="section">
            <p className="section-label" style={{ margin: 0 }}>
              Desfazer Última Efetivação
            </p>
            {carregandoUltima ? (
              <p className="muted">Carregando…</p>
            ) : ultimaEfetivacao?.existe ? (
              <>
                <p style={{ marginTop: 8 }}>
                  <span className="muted">Efetivada em: </span>
                  <strong>{ultimaEfetivacao.dataEfetivacao ? formatarDataHora(ultimaEfetivacao.dataEfetivacao) : '—'}</strong>
                </p>
                <p style={{ marginTop: 4 }}>
                  <span className="muted">Produtos ajustados: </span>
                  <strong>{ultimaEfetivacao.totalProdutos}</strong>
                </p>
                <button type="button" className="btn ghost" style={{ marginTop: 16 }} onClick={() => setConfirmandoDesfazer(true)}>
                  Desfazer Última Efetivação
                </button>
              </>
            ) : (
              <p className="muted" style={{ marginTop: 8 }}>
                Não há nenhuma efetivação de balanço para desfazer.
              </p>
            )}
          </section>
        </div>
      </div>

      {confirmandoZerar && (
        <div
          className="modal-overlay"
          onClick={() => {
            if (zerar.isPending) return
            setConfirmandoZerar(false)
            setTextoConfirmacaoZerar('')
          }}
        >
          <div className="modal" role="dialog" aria-label="Confirmar zerar contagem" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Zerar Contagem de Estoque?</h2>
            <p>
              Você está prestes a apagar <strong>{totalProdutos} produto(s)</strong> contado(s), totalizando{' '}
              <strong>{formatarQuantidade(totalQuantidade, permiteQtdDecimal)}</strong> unidades.
            </p>
            <p className="erro">
              Esta ação não pode ser desfeita. Se a contagem foi zerada por engano, todo o trabalho de leitura já feito
              será perdido e a loja precisará ser recontada do zero.
            </p>
            <p>
              Para confirmar, digite <strong>{FRASE_CONFIRMACAO_ZERAR}</strong> abaixo:
            </p>
            <input
              type="text"
              autoFocus
              value={textoConfirmacaoZerar}
              onChange={(e) => setTextoConfirmacaoZerar(e.target.value)}
              disabled={zerar.isPending}
              placeholder={FRASE_CONFIRMACAO_ZERAR}
            />
            <div className="ajuda-rodape">
              <button
                type="button"
                className="btn ghost"
                disabled={zerar.isPending}
                onClick={() => {
                  setConfirmandoZerar(false)
                  setTextoConfirmacaoZerar('')
                }}
              >
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={zerar.isPending || textoConfirmacaoZerar.trim().toLowerCase() !== FRASE_CONFIRMACAO_ZERAR}
                onClick={() => zerar.mutate()}
              >
                {zerar.isPending ? 'Zerando…' : 'Sim, zerar contagem'}
              </button>
            </div>
          </div>
        </div>
      )}

      {confirmandoDesfazer && ultimaEfetivacao?.existe && (
        <div className="modal-overlay" onClick={() => !desfazer.isPending && setConfirmandoDesfazer(false)}>
          <div className="modal" role="dialog" aria-label="Confirmar desfazer efetivação" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Desfazer Última Efetivação?</h2>
            <p>
              A efetivação de <strong>{ultimaEfetivacao.dataEfetivacao ? formatarDataHora(ultimaEfetivacao.dataEfetivacao) : '—'}</strong>{' '}
              ({ultimaEfetivacao.totalProdutos} produto(s) ajustado(s)) será desfeita: o estoque volta ao valor de antes
              dela e a contagem daquela efetivação volta pro balanço ativo, pronta pra corrigir e efetivar de novo.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" disabled={desfazer.isPending} onClick={() => setConfirmandoDesfazer(false)}>
                Cancelar
              </button>
              <button type="button" className="btn" disabled={desfazer.isPending} onClick={() => desfazer.mutate()}>
                {desfazer.isPending ? 'Desfazendo…' : 'Sim, desfazer'}
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
