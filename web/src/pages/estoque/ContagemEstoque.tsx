import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeExcluir, IconeEstoque } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import { ajustarContagem, listarContagemAtiva, registrarContagem, removerContagem, type LinhaContagem, invalidarBalanco } from '../../lib/estoqueBalanco'
import { completarQuantidade, desmascararQuantidade, formatarQuantidade, mascararQuantidade } from '../../lib/masks'
import { buscarProdutoPorCodigo, interpretarCodigoBarras } from '../../lib/pdv'
import { maiusculas } from '../../lib/texto'

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
  /**
   * Linha aguardando confirmação de exclusão (auditoria 2026-08-21, item 8).
   *
   * ⚠️ A lixeira apagava direto, sem perguntar — em todo o resto do sistema exclusão confirma. E
   * aqui o que se perde é **trabalho de contagem já gravado**: alguém percorreu a prateleira,
   * contou e digitou. Um clique errado numa grade longa refaz tudo aquilo.
   */
  const [linhaParaRemover, setLinhaParaRemover] = useState<LinhaContagem | null>(null)

  const registrar = useMutation({
    mutationFn: ({ idVariacao, qtd }: { idVariacao: number; qtd: number }) => registrarContagem(idVariacao, qtd),
    onSuccess: () => invalidarBalanco(queryClient),
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível registrar a leitura.', tipo: 'erro' }),
  })

  const ajustar = useMutation({
    mutationFn: ({ idVariacao, qtd }: { idVariacao: number; qtd: number }) => ajustarContagem(idVariacao, qtd),
    onSuccess: () => invalidarBalanco(queryClient),
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível ajustar a contagem.', tipo: 'erro' }),
  })

  const remover = useMutation({
    mutationFn: (idVariacao: number) => removerContagem(idVariacao),
    onSuccess: () => invalidarBalanco(queryClient),
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

  /**
   * ⚠️ Campo VAZIO significa "não mexi", nunca "apague a contagem" (achado de auditoria,
   * 2026-08-21).
   *
   * <p>O texto vazio percorria todo o caminho e virava **zero**: `completarQuantidade('')` devolve
   * `''`, `desmascararQuantidade('')` devolve `0`, e o servidor, ao receber zero, **apaga a linha**
   * (`ajustarContagem` faz DELETE e só reinsere com `signum() > 0`). Bastava o operador selecionar
   * o campo (o `onFocus` seleciona tudo), apertar Delete para redigitar e ser interrompido: ao
   * clicar em qualquer outro lugar, a contagem daquele produto sumia da grade — **sem toast, sem
   * confirmação e sem desfazer**. Pior: o produto sumia do balanço inteiro sem sequer aparecer como
   * divergência, porque o cálculo só olha o que foi contado.
   *
   * <p>Zerar de propósito continua possível digitando `0` — aí é intenção declarada, não um campo
   * em branco no meio de uma edição.
   */
  function aoSairQtd(linha: LinhaContagem) {
    const texto = qtdEditando[linha.idVariacao]
    if (texto === undefined) return
    if (!texto.trim()) {
      setQtdEditando((atual) => {
        const proximo = { ...atual }
        delete proximo[linha.idVariacao]
        return proximo
      })
      return
    }
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
                onChange={(e) => setValorBarras(maiusculas(e.target.value))}
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
                      <th>Cor</th>
                      <th>Tamanho</th>
                      <th style={{ textAlign: 'right' }}>Quantidade Contada</th>
                      <th aria-label="Ações" />
                    </tr>
                  </thead>
                  <tbody>
                    {(linhas ?? []).map((linha) => (
                      <tr key={linha.idVariacao}>
                        <td>{linha.descricaoProduto}</td>
                        <td>{linha.variacaoCor ?? '—'}</td>
                        <td>{linha.variacaoTamanho ?? '—'}</td>
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
                            onClick={() => setLinhaParaRemover(linha)}
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

      {/* Confirmação de exclusão — ver o comentário de `linhaParaRemover`. */}
      {linhaParaRemover && (
        <div className="modal-overlay" onClick={() => setLinhaParaRemover(null)}>
          <div className="modal" role="dialog" aria-label="Confirmar remoção" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Remover da contagem?</h2>
            <p>
              <strong>{linhaParaRemover.descricaoProduto}</strong>
              {linhaParaRemover.variacaoCor || linhaParaRemover.variacaoTamanho ? (
                <span className="muted">
                  {' '}· {[linhaParaRemover.variacaoCor, linhaParaRemover.variacaoTamanho].filter(Boolean).join(' · ')}
                </span>
              ) : null}
            </p>
            <p className="muted">
              A quantidade contada deste produto será descartada. Para contá-lo de novo, será preciso lançar
              tudo outra vez.
            </p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setLinhaParaRemover(null)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={remover.isPending}
                onClick={() => {
                  remover.mutate(linhaParaRemover.idVariacao)
                  setLinhaParaRemover(null)
                }}
              >
                {remover.isPending ? 'Removendo…' : 'Remover'}
              </button>
            </div>
          </div>
        </div>
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
