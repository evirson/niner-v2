import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import CorQuickCreateModal from '../../../components/CorQuickCreateModal'
import { IconeFechar } from '../../../components/Icones'
import { ApiError } from '../../../lib/api'
import { buscarPermiteQtdDecimal, buscarUsaCorGrade } from '../../../lib/configuracaoGeral'
import { listarCores } from '../../../lib/cores'
import { buscarProdutosEntrada, type ProdutoOpcaoEntrada } from '../../../lib/entradaMercadoria'
import { buscarGrade } from '../../../lib/grades'
import {
  completarMoeda,
  completarPercentual,
  completarQuantidade,
  desmascararMoeda,
  desmascararPercentual,
  desmascararQuantidade,
  formatarMoeda,
  formatarPercentual,
  mascararMoeda,
  mascararPercentual,
  mascararQuantidade,
} from '../../../lib/masks'
import { criarVariacao } from '../../../lib/produtos'
import { maiusculas } from '../../../lib/texto'

/** Uma variação já resolvida (achada ou criada), pronta pra virar uma linha em `itens` —
 *  `precoCusto`/`precoVenda` vêm à parte (mesmo valor pra todo o lote deste lançamento). */
export interface ItemResolvidoEntrada {
  idVariacao: number
  descricao: string
  variacao: string | null
  qtd: number
}

function custoInicialDoProduto(p: ProdutoOpcaoEntrada): string {
  return p.precoCusto > 0 ? formatarMoeda(p.precoCusto) : ''
}

function precoVendaInicialDoProduto(p: ProdutoOpcaoEntrada): string {
  if (p.precoCusto <= 0) return ''
  return formatarMoeda(p.precoCusto * (1 + p.percentualVenda / 100))
}

function percentualInicialDoProduto(p: ProdutoOpcaoEntrada): string {
  return formatarPercentual(p.percentualVenda)
}

/**
 * Popup de pesquisa + lançamento do fluxo Individual da Entrada de Produtos por Compra
 * (2026-08-12, revisão; cor via popup + %venda editável 2026-08-12) — dois passos:
 * 1. Busca por Nome/Marca/Referência, nível produto (não variação) — sem cor/tamanho/estoque na
 *    grid de resultado (docs pedido do dono do produto).
 * 2. Ao escolher o produto: se ele usa grade, mostra Cor (select + "＋ Nova cor" que abre
 *    `CorQuickCreateModal` e já volta com a cor recém-criada selecionada) + a grade de tamanhos
 *    inteira com um campo de quantidade embaixo de cada tamanho (lança vários tamanhos de uma
 *    vez); sem grade, um único campo de Quantidade. Custo Unitário, % de Venda e Preço de Venda
 *    são todos editáveis e se recalculam entre si (mesma fórmula/mecânica de três campos do
 *    cadastro de Produto — `custo × (1 + %/100)`, ou o inverso quando o operador edita o Preço
 *    de Venda direto); Preço de Venda nunca pode ficar abaixo do Custo Unitário (regra do
 *    projeto inteiro, não só desta tela). Custo/%/Venda valem pra TODAS as variações lançadas
 *    neste passo (um lote = um custo).
 */
export default function PesquisaProdutoEntradaModal({
  aoFechar,
  aoAdicionar,
  produtoInicial,
}: {
  aoFechar: () => void
  aoAdicionar: (itens: ItemResolvidoEntrada[], precoCusto: number, precoVenda: number) => void
  /** Pula direto pro passo 2 (2026-08-12, "＋ Cadastrar Produto" do fluxo Individual) — o produto
   *  acabou de ser criado (com grade), então não faz sentido pedir pra buscar de novo; a tela já
   *  abre pedindo Cor + a grade de tamanhos pra esse produto. */
  produtoInicial?: ProdutoOpcaoEntrada
}) {
  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true
  const { data: cfgUsaCorGrade } = useQuery({ queryKey: ['usa-cor-grade'], queryFn: buscarUsaCorGrade })

  const [nome, setNome] = useState('')
  const [marca, setMarca] = useState('')
  const [referencia, setReferencia] = useState('')
  const [produtoEscolhido, setProdutoEscolhido] = useState<ProdutoOpcaoEntrada | null>(produtoInicial ?? null)

  const { data: resultados, isLoading, isError } = useQuery({
    queryKey: ['entrada-produtos', nome, marca, referencia],
    queryFn: () => buscarProdutosEntrada(nome, marca, referencia),
    enabled: !produtoEscolhido,
  })

  // Parâmetro "Usa Cor/Grade" desligado ignora a grade do produto por completo, mesmo que ele já
  // tenha uma cadastrada de antes (2026-08-13, pedido do dono do produto) — trata como produto
  // simples, um único campo de Quantidade.
  const usaGrade = Boolean(cfgUsaCorGrade?.cfgUsaCorGrade) && produtoEscolhido?.idGrade != null
  const [idCor, setIdCor] = useState<number | ''>('')
  const [modalCorAberto, setModalCorAberto] = useState(false)
  const [qtdsPorTamanho, setQtdsPorTamanho] = useState<Record<number, string>>({})
  const [qtdUnicaTexto, setQtdUnicaTexto] = useState('')
  const [custoTexto, setCustoTexto] = useState(produtoInicial ? custoInicialDoProduto(produtoInicial) : '')
  const [percentualVendaTexto, setPercentualVendaTexto] = useState(produtoInicial ? percentualInicialDoProduto(produtoInicial) : '')
  const [precoVendaTexto, setPrecoVendaTexto] = useState(produtoInicial ? precoVendaInicialDoProduto(produtoInicial) : '')
  const [erro, setErro] = useState('')

  const { data: grade } = useQuery({
    queryKey: ['grade', produtoEscolhido?.idGrade],
    queryFn: () => buscarGrade(produtoEscolhido!.idGrade!),
    enabled: usaGrade,
  })
  const { data: cores } = useQuery({ queryKey: ['cores'], queryFn: listarCores, enabled: usaGrade })

  /** Mesma fórmula/mecânica de três campos do cadastro de Produto (`ProdutoForm.tsx`): mudar
   *  custo ou % recalcula o Preço de Venda; mudar o Preço de Venda direto back-deriva o %. */
  const recalcularPrecoVenda = (custoStr: string, percentualStr: string): string => {
    const custo = desmascararMoeda(custoStr)
    if (custo <= 0) return ''
    const percentual = desmascararPercentual(percentualStr)
    return formatarMoeda(custo * (1 + percentual / 100))
  }

  function escolherProduto(p: ProdutoOpcaoEntrada) {
    setProdutoEscolhido(p)
    setIdCor('')
    setQtdsPorTamanho({})
    setQtdUnicaTexto('')
    setErro('')
    setCustoTexto(custoInicialDoProduto(p))
    setPercentualVendaTexto(percentualInicialDoProduto(p))
    setPrecoVendaTexto(precoVendaInicialDoProduto(p))
  }

  function trocarProduto() {
    setProdutoEscolhido(null)
    setErro('')
  }

  const aoMudarCusto = (texto: string) => {
    const custo = mascararMoeda(texto)
    setCustoTexto(custo)
    setPrecoVendaTexto((atual) => recalcularPrecoVenda(custo, percentualVendaTexto) || atual)
  }

  const aoMudarPercentualVenda = (texto: string) => {
    const percentual = mascararPercentual(texto)
    setPercentualVendaTexto(percentual)
    setPrecoVendaTexto((atual) => recalcularPrecoVenda(custoTexto, percentual) || atual)
  }

  /** Editar o Preço de Venda direto recalcula o % a partir do custo (mesma regra de
   *  `ProdutoForm.tsx`) — só quando há custo informado; sem custo não dá pra derivar um %. */
  const aoMudarPrecoVenda = (texto: string) => {
    const precoVenda = mascararMoeda(texto)
    setPrecoVendaTexto(precoVenda)
    const custo = desmascararMoeda(custoTexto)
    if (custo <= 0) return
    const venda = desmascararMoeda(precoVenda)
    setPercentualVendaTexto(formatarPercentual(((venda - custo) / custo) * 100))
  }

  const alterarQtdTamanho = (idTamanho: number, texto: string) =>
    setQtdsPorTamanho((atual) => ({ ...atual, [idTamanho]: mascararQuantidade(texto, permiteQtdDecimal) }))

  const aoSairQtdTamanho = (idTamanho: number) =>
    setQtdsPorTamanho((atual) =>
      atual[idTamanho] ? { ...atual, [idTamanho]: completarQuantidade(atual[idTamanho], permiteQtdDecimal) } : atual,
    )

  /** Preço de venda nunca pode ficar abaixo do custo — regra do projeto inteiro (2026-08-12),
   *  não só desta tela; ver `docs/PROGRESSO.md`. */
  const custoNumero = desmascararMoeda(custoTexto)
  const precoVendaNumero = desmascararMoeda(precoVendaTexto)
  const vendaMenorQueCusto = custoNumero > 0 && precoVendaNumero > 0 && precoVendaNumero < custoNumero

  const adicionar = useMutation({
    mutationFn: async () => {
      if (!produtoEscolhido) throw new ApiError(400, 'Escolha um produto.')
      const custo = desmascararMoeda(custoTexto)
      if (custo <= 0) throw new ApiError(400, 'Informe o custo unitário.')
      const precoVenda = desmascararMoeda(precoVendaTexto)
      if (precoVenda <= 0) throw new ApiError(400, 'Informe o preço de venda.')
      if (precoVenda < custo) throw new ApiError(400, 'Preço de venda não pode ser menor que o custo.')

      const alvos: { idCor: number | null; idTamanho: number | null; qtd: number }[] = []
      if (usaGrade) {
        if (idCor === '') throw new ApiError(400, 'Informe a cor.')
        for (const t of grade?.tamanhos ?? []) {
          const qtd = desmascararQuantidade(qtdsPorTamanho[t.idTamanho] ?? '', permiteQtdDecimal)
          if (qtd > 0) alvos.push({ idCor: idCor as number, idTamanho: t.idTamanho, qtd })
        }
        if (alvos.length === 0) throw new ApiError(400, 'Informe a quantidade de ao menos um tamanho.')
      } else {
        const qtd = desmascararQuantidade(qtdUnicaTexto, permiteQtdDecimal)
        if (qtd <= 0) throw new ApiError(400, 'Informe a quantidade.')
        alvos.push({ idCor: null, idTamanho: null, qtd })
      }

      const itens: ItemResolvidoEntrada[] = []
      for (const alvo of alvos) {
        const variacao = await criarVariacao(produtoEscolhido.idProduto, { idCor: alvo.idCor, idTamanho: alvo.idTamanho })
        const variacaoTexto = [variacao.variacaoCor, variacao.variacaoTamanho].filter(Boolean).join(' · ') || null
        itens.push({ idVariacao: variacao.idVariacao, descricao: variacao.descricao, variacao: variacaoTexto, qtd: alvo.qtd })
      }
      return { itens, custo, precoVenda }
    },
    onSuccess: ({ itens, custo, precoVenda }) => aoAdicionar(itens, custo, precoVenda),
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível adicionar.'),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Pesquisa de produto" onClick={(e) => e.stopPropagation()}>
        <div className="lightbox-topo" style={{ marginBottom: 12 }}>
          <h2 style={{ margin: 0 }}>Pesquisa de Produto</h2>
          <button type="button" className="btn ghost btn-fechar-tela" onClick={aoFechar} aria-label="Fechar" title="Fechar">
            <IconeFechar />
          </button>
        </div>

        {!produtoEscolhido ? (
          <>
            <p className="muted" style={{ marginTop: 4 }}>
              Filtre por nome, marca e/ou referência e clique numa linha pra escolher o produto.
            </p>

            <div className="form-grid" style={{ marginTop: 10 }}>
              <div className="col-6">
                <label htmlFor="entrada-pesquisa-nome">Nome do Produto</label>
                <input
                  id="entrada-pesquisa-nome"
                  autoFocus
                  placeholder="Ex.: ARROZ, TÊNIS…"
                  value={nome}
                  onChange={(e) => setNome(maiusculas(e.target.value))}
                />
              </div>
              <div className="col-3">
                <label htmlFor="entrada-pesquisa-marca">Marca</label>
                <input id="entrada-pesquisa-marca" value={marca} onChange={(e) => setMarca(maiusculas(e.target.value))} />
              </div>
              <div className="col-3">
                <label htmlFor="entrada-pesquisa-referencia">Referência</label>
                <input id="entrada-pesquisa-referencia" value={referencia} onChange={(e) => setReferencia(maiusculas(e.target.value))} />
              </div>
            </div>

            <div className="table-wrap" style={{ marginTop: 16, maxHeight: 360 }}>
              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>Descrição</th>
                    <th>Marca</th>
                    <th>Referência</th>
                  </tr>
                </thead>
                <tbody>
                  {isLoading ? (
                    <tr>
                      <td colSpan={3}>
                        <p className="muted" style={{ margin: '8px 0' }}>
                          Buscando…
                        </p>
                      </td>
                    </tr>
                  ) : isError ? (
                    <tr>
                      <td colSpan={3}>
                        <p className="erro" style={{ margin: '8px 0' }}>
                          Não foi possível buscar produtos.
                        </p>
                      </td>
                    </tr>
                  ) : !resultados || resultados.length === 0 ? (
                    <tr>
                      <td colSpan={3}>
                        <p className="muted" style={{ margin: '8px 0' }}>
                          Nenhum produto encontrado.
                        </p>
                      </td>
                    </tr>
                  ) : (
                    resultados.map((p) => (
                      <tr
                        key={p.idProduto}
                        tabIndex={0}
                        onClick={() => escolherProduto(p)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            escolherProduto(p)
                          }
                        }}
                      >
                        <td>{p.descricao}</td>
                        <td>{p.marca ?? '—'}</td>
                        <td>{p.referencia ?? '—'}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={aoFechar}>
                Fechar
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="muted" style={{ marginTop: 4 }}>
              <strong>{produtoEscolhido.descricao}</strong>
              {produtoEscolhido.marca ? ` · ${produtoEscolhido.marca}` : ''}
              {produtoEscolhido.referencia ? ` · ${produtoEscolhido.referencia}` : ''}{' '}
              <button type="button" className="btn ghost" onClick={trocarProduto}>
                Trocar
              </button>
            </p>

            {usaGrade ? (
              <>
                <div className="form-grid" style={{ marginTop: 10 }}>
                  <div className="col-6">
                    <label htmlFor="entrada-detalhe-cor">Cor *</label>
                    <div className="linha-com-botao">
                      <select
                        id="entrada-detalhe-cor"
                        value={idCor}
                        onChange={(e) => setIdCor(e.target.value === '' ? '' : Number(e.target.value))}
                      >
                        <option value="">Selecione…</option>
                        {(cores ?? []).map((c) => (
                          <option key={c.idCor} value={c.idCor}>
                            {c.descricao}
                          </option>
                        ))}
                      </select>
                      <button type="button" className="btn ghost" onClick={() => setModalCorAberto(true)}>
                        ＋ Nova cor
                      </button>
                    </div>
                  </div>
                </div>

                <p className="section-label" style={{ margin: '16px 0 4px' }}>
                  Quantidade por Tamanho
                </p>
                <div style={{ display: 'flex', gap: 10, overflowX: 'auto', paddingBottom: 6 }}>
                  {(grade?.tamanhos ?? []).map((t) => (
                    <div key={t.idTamanho} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 58 }}>
                      <label style={{ fontSize: 12 }}>{t.descricao}</label>
                      <input
                        className="mono"
                        style={{ width: 54, textAlign: 'center' }}
                        inputMode={permiteQtdDecimal ? undefined : 'numeric'}
                        placeholder="0"
                        value={qtdsPorTamanho[t.idTamanho] ?? ''}
                        onChange={(e) => alterarQtdTamanho(t.idTamanho, e.target.value)}
                        onBlur={() => aoSairQtdTamanho(t.idTamanho)}
                        onFocus={(e) => e.target.select()}
                      />
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <div className="form-grid" style={{ marginTop: 10 }}>
                <div className="col-4">
                  <label htmlFor="entrada-detalhe-qtd">Quantidade *</label>
                  <input
                    id="entrada-detalhe-qtd"
                    className="mono"
                    inputMode={permiteQtdDecimal ? undefined : 'numeric'}
                    value={qtdUnicaTexto}
                    onChange={(e) => setQtdUnicaTexto(mascararQuantidade(e.target.value, permiteQtdDecimal))}
                    onBlur={() => setQtdUnicaTexto((v) => (v ? completarQuantidade(v, permiteQtdDecimal) : v))}
                    onFocus={(e) => e.target.select()}
                  />
                </div>
              </div>
            )}

            <div className="form-grid" style={{ marginTop: 16 }}>
              <div className="col-4">
                <label htmlFor="entrada-detalhe-custo">Custo Unitário *</label>
                <input
                  id="entrada-detalhe-custo"
                  className="mono"
                  inputMode="decimal"
                  value={custoTexto}
                  onChange={(e) => aoMudarCusto(e.target.value)}
                  onBlur={() => setCustoTexto((v) => completarMoeda(v))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-4">
                <label htmlFor="entrada-detalhe-percentual">% de Venda</label>
                <input
                  id="entrada-detalhe-percentual"
                  className="mono"
                  inputMode="decimal"
                  value={percentualVendaTexto}
                  onChange={(e) => aoMudarPercentualVenda(e.target.value)}
                  onBlur={() => setPercentualVendaTexto((v) => completarPercentual(v))}
                  onFocus={(e) => e.target.select()}
                />
              </div>
              <div className="col-4">
                <label htmlFor="entrada-detalhe-venda">Preço de Venda *</label>
                <input
                  id="entrada-detalhe-venda"
                  className="mono"
                  inputMode="decimal"
                  value={precoVendaTexto}
                  onChange={(e) => aoMudarPrecoVenda(e.target.value)}
                  onBlur={() => setPrecoVendaTexto((v) => completarMoeda(v))}
                  onFocus={(e) => e.target.select()}
                />
                {vendaMenorQueCusto && <p className="erro-campo">Não pode ser menor que o custo.</p>}
              </div>
            </div>

            {erro && <p className="erro-campo">{erro}</p>}

            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={aoFechar}>
                Fechar
              </button>
              <button type="button" className="btn" disabled={adicionar.isPending || vendaMenorQueCusto} onClick={() => adicionar.mutate()}>
                {adicionar.isPending ? 'Adicionando…' : 'Adicionar à Entrada'}
              </button>
            </div>
          </>
        )}
      </div>

      {modalCorAberto && (
        <CorQuickCreateModal
          aoFechar={() => setModalCorAberto(false)}
          aoCriar={(cor) => {
            setIdCor(cor.idCor)
            setModalCorAberto(false)
          }}
        />
      )}
    </div>
  )
}
