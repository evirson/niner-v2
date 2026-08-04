import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeExcluir, IconeEstoque } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { ajustarContagem, listarContagemAtiva, registrarContagem, removerContagem, type LinhaContagem } from '../../lib/estoqueBalanco'
import { completarQuantidade, desmascararQuantidade, formatarQuantidade, mascararQuantidade } from '../../lib/masks'
import { buscarProdutoPorCodigo, interpretarCodigoBarras } from '../../lib/pdv'

const CHAVE_TELA = 'estoque.contagem'
const QTD_MAXIMA_POR_LEITURA = 1000

/**
 * Contagem de Estoque (2026-08-04) — tela simples de leitura de código de barras: sem pesquisa
 * de produto, só o campo de código (mesma sintaxe "qtd*código" do PDV/Transferência). Cada
 * leitura já grava no servidor na hora (POST /contagem, soma leituras da mesma variação) — a
 * grid abaixo é sempre a lista ativa vinda do backend (React Query), não um rascunho local.
 * Empresa é sempre a que o usuário está logado (claim `eid` do JWT, resolvida no backend — não
 * existe seletor aqui). Cada linha pode ser corrigida (campo de quantidade editável) ou removida
 * inteira sem afetar as demais.
 */
export default function ContagemEstoque() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [valorBarras, setValorBarras] = useState('')
  const [toast, setToast] = useState<{ texto: string; tipo: 'erro' | 'sucesso' } | null>(null)
  const [qtdEditando, setQtdEditando] = useState<Record<number, string>>({})
  const campoBarrasRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    campoBarrasRef.current?.focus()
  }, [])

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const { data: linhas, isLoading } = useQuery({ queryKey: ['balanco-contagem'], queryFn: listarContagemAtiva })

  const registrar = useMutation({
    mutationFn: ({ idVariacao, qtd }: { idVariacao: number; qtd: number }) => registrarContagem(idVariacao, qtd),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['balanco-contagem'] }),
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível registrar a leitura.', tipo: 'erro' }),
  })

  const ajustar = useMutation({
    mutationFn: ({ idVariacao, qtd }: { idVariacao: number; qtd: number }) => ajustarContagem(idVariacao, qtd),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['balanco-contagem'] }),
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível ajustar a contagem.', tipo: 'erro' }),
  })

  const remover = useMutation({
    mutationFn: (idVariacao: number) => removerContagem(idVariacao),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['balanco-contagem'] }),
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível remover a linha.', tipo: 'erro' }),
  })

  const lerCodigo = async (valorDigitado: string) => {
    const { qtd, codigo } = interpretarCodigoBarras(valorDigitado)
    if (qtd > QTD_MAXIMA_POR_LEITURA) {
      setToast({ texto: `Quantidade máxima por leitura é ${QTD_MAXIMA_POR_LEITURA} unidades.`, tipo: 'erro' })
      return
    }
    try {
      const produto = await buscarProdutoPorCodigo(codigo)
      await registrar.mutateAsync({ idVariacao: produto.idVariacao, qtd })
    } catch (e) {
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível ler o código de barras.', tipo: 'erro' })
    }
  }

  const aoDigitarBarras: React.KeyboardEventHandler<HTMLInputElement> = (e) => {
    if (e.key === 'Enter' && valorBarras.trim()) {
      e.preventDefault()
      const valor = valorBarras.trim()
      setValorBarras('')
      lerCodigo(valor)
    }
  }

  function textoEditando(linha: LinhaContagem): string {
    return qtdEditando[linha.idVariacao] ?? formatarQuantidade(linha.qtdContada, permiteQtdDecimal)
  }

  function aoMudarQtd(idVariacao: number, texto: string) {
    setQtdEditando((atual) => ({ ...atual, [idVariacao]: mascararQuantidade(texto, permiteQtdDecimal) }))
  }

  function aoSairQtd(linha: LinhaContagem) {
    const texto = qtdEditando[linha.idVariacao]
    if (texto === undefined) return
    const completo = completarQuantidade(texto, permiteQtdDecimal)
    setQtdEditando((atual) => {
      const proximo = { ...atual }
      delete proximo[linha.idVariacao]
      return proximo
    })
    const novaQtd = desmascararQuantidade(completo, permiteQtdDecimal)
    if (novaQtd === linha.qtdContada) return
    ajustar.mutate({ idVariacao: linha.idVariacao, qtd: novaQtd })
  }

  const totalContado = (linhas ?? []).reduce((s, l) => s + l.qtdContada, 0)

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>Contagem de Estoque</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes form-secoes-larga">
          <section className="section">
            <p className="section-label" style={{ margin: 0 }}>
              Leitura
            </p>
            <div className="pdv-campo-codigo-barras" style={{ marginTop: 10, maxWidth: 420 }}>
              <div className="pdv-rotulo">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6}>
                  <path d="M4 5v14M8 5v14M11 5v14M13 5v14M17 5v14M20 5v14" />
                </svg>
                Código de Barras
              </div>
              <input
                ref={campoBarrasRef}
                type="text"
                placeholder="Aguardando leitura…"
                autoComplete="off"
                inputMode="numeric"
                value={valorBarras}
                onChange={(e) => setValorBarras(e.target.value)}
                onKeyDown={aoDigitarBarras}
              />
              <p className="pdv-dica">Leia o código de barras do produto e pressione Enter.</p>
              <p className="pdv-dica">Dica: "5*código" soma 5 unidades de uma vez (máximo 1000 por leitura).</p>
            </div>

            <p className="section-label" style={{ marginTop: 24 }}>
              Produtos Contados
            </p>
            {isLoading ? (
              <p className="muted">Carregando…</p>
            ) : (linhas ?? []).length === 0 ? (
              <p className="muted" style={{ marginTop: 8 }}>
                Nenhum produto contado ainda.
              </p>
            ) : (
              <div className="table-wrap" style={{ marginTop: 8 }}>
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Descrição</th>
                      <th>Variação Linha</th>
                      <th>Variação Coluna</th>
                      <th style={{ textAlign: 'right' }}>Quantidade Contada</th>
                      <th aria-label="Ações" />
                    </tr>
                  </thead>
                  <tbody>
                    {(linhas ?? []).map((linha) => (
                      <tr key={linha.idVariacao}>
                        <td>{linha.descricaoProduto}</td>
                        <td>{linha.variacaoLinha ?? '—'}</td>
                        <td>{linha.variacaoColuna ?? '—'}</td>
                        <td style={{ textAlign: 'right' }}>
                          <input
                            className="mono"
                            style={{ width: 110, textAlign: 'right' }}
                            inputMode={permiteQtdDecimal ? undefined : 'numeric'}
                            value={textoEditando(linha)}
                            onChange={(e) => aoMudarQtd(linha.idVariacao, e.target.value)}
                            onFocus={(e) => e.target.select()}
                            onBlur={() => aoSairQtd(linha)}
                          />
                        </td>
                        <td className="acoes-cell">
                          <button
                            type="button"
                            className="acao-icone acao-excluir"
                            onClick={() => remover.mutate(linha.idVariacao)}
                            aria-label={`Remover ${linha.descricaoProduto}`}
                            title="Remover"
                          >
                            <IconeExcluir />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colSpan={3}>
                        <strong>Total ({(linhas ?? []).length} produtos)</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        <strong>{formatarQuantidade(totalContado, permiteQtdDecimal)}</strong>
                      </td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
          </section>
        </div>
      </div>

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
