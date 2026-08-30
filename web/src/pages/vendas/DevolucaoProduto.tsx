import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeDevolucaoProduto, IconeExcluir, IconeLupa } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { buscarExigeNumeroVendaDevolucao, buscarPermiteQtdDecimal } from '../../lib/configuracaoGeral'
import {
  buscarVendedorDaVenda,
  efetivarDevolucao,
  type DevolucaoEfetivada,
  type ItemVendaOrigem,
  type VendedorDaVenda,
} from '../../lib/devolucaoProduto'
import { useEu } from '../../lib/eu'
import { completarQuantidade, desmascararQuantidade, formatarMoeda, formatarQuantidade, mascararQuantidade } from '../../lib/masks'
import { buscarProdutoPorCodigo, interpretarCodigoBarras, type PdvProduto } from '../../lib/pdv'
import { maiusculas } from '../../lib/texto'
import PesquisaProdutoModal from '../pdv/PesquisaProdutoModal'
import ComprovanteValeModal from './ComprovanteValeModal'
import SelecaoItensVendaModal from './SelecaoItensVendaModal'
import { usePermissaoDaTela } from '../../lib/usePermissaoDaTela'

const CHAVE_TELA = 'vendas.devolucaoproduto.form'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

interface ItemLinha {
  idVariacao: number
  descricao: string
  variacao: string | null
  /** BRUTO — a chave de linha que casa com a venda. */
  precoVenda: number
  /**
   * Desconto por unidade da linha da venda (auditoria 2026-08-29). Sem ele a tela somava o
   * BRUTO e anunciava ao cliente um valor maior do que o vale que ela mesma ia emitir: uma peça
   * de R$ 100 vendida com R$ 10 de desconto aparecia como R$ 100 no rodapé e virava vale de
   * R$ 90. Devolução sem venda de origem não tem desconto a herdar — fica zero.
   */
  descontoUnitario: number
  qtdTexto: string
  /** `true` quando a linha veio da grid de seleção da venda — só então o preço identifica uma
   *  linha real da venda e pode ser mandado ao servidor. Produto lançado por código de barras
   *  (devolução sem venda de origem) carrega o preço do CADASTRO, que não identifica nada. */
  daVenda: boolean
}

/**
 * Identidade de uma linha da grade: variação + preço.
 *
 * ⚠️ A chave era só `idVariacao` até 2026-08-22 (auditoria, item 2). Desde o orçamento, o mesmo
 * produto pode estar duas vezes na mesma venda com preços diferentes — o congelado, que a loja
 * honrou, e o do dia. Com a chave antiga as duas linhas eram tratadas como uma: somavam
 * quantidade, dividiam saldo e o vale saía pela média. É a mesma lição do `ItemLedger.idLinha` do
 * PDV — o SKU deixou de servir como chave quando duas linhas do mesmo produto passaram a existir.
 *
 * O preço entra com 2 casas fixas para não depender de como o número chegou (80 vs 80.0 vs 80.00).
 */
function chaveLinha(idVariacao: number, precoVenda: number): string {
  return `${idVariacao}|${precoVenda.toFixed(2)}`
}

function variacaoTexto(p: PdvProduto): string | null {
  if (!p.variacaoCor && !p.variacaoTamanho) return null
  return [p.variacaoCor, p.variacaoTamanho].filter(Boolean).join(' · ')
}

export default function DevolucaoProduto() {
  // ⛔ RBAC (auditoria 2026-08-29, rodada 5): a varredura das rodadas 1-4 cobriu os CADASTROS
  // e deixou as ROTINAS OPERACIONAIS de fora — que é onde o 403 tardio custa mais caro, porque
  // o operador só descobre depois de montar a operação inteira, com o cliente na frente.
  const acoes = usePermissaoDaTela('devolucao-produto')
  const { data: eu } = useEu()
  const navigate = useNavigate()

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  const { data: cfgExigeVenda, isFetching: buscandoCfgExigeVenda } = useQuery({
    queryKey: ['exige-numero-venda-devolucao'],
    queryFn: buscarExigeNumeroVendaDevolucao,
  })
  const exigeNumeroVenda = cfgExigeVenda?.cfgExigeNumeroVendaDevolucao ?? false

  const [numeroVendaTexto, setNumeroVendaTexto] = useState('')
  const [vendedor, setVendedor] = useState<VendedorDaVenda | null>(null)
  const [buscandoVendedor, setBuscandoVendedor] = useState(false)

  const [itens, setItens] = useState<ItemLinha[]>([])
  const [mostrarPesquisa, setMostrarPesquisa] = useState(false)
  /** Popup de seleção por venda (revisão 2026-08-19) — só existe quando o número da venda é
   *  obrigatório; nesse modo ele SUBSTITUI a leitura por código de barras. Abre sozinho ao entrar
   *  na tela e depois de cada devolução gravada (o fluxo recomeça na venda seguinte). */
  const [mostrarSelecaoVenda, setMostrarSelecaoVenda] = useState(false)
  const [valorBarras, setValorBarras] = useState('')
  const [toast, setToast] = useState<{ texto: string; tipo: 'erro' | 'sucesso' } | null>(null)
  /** Vale gerado pela última devolução gravada — abre o comprovante automaticamente (2026-08-03). */
  const [valeGerado, setValeGerado] = useState<DevolucaoEfetivada | null>(null)
  const campoBarrasRef = useRef<HTMLInputElement>(null)
  const numeroVendaRef = useRef<HTMLInputElement>(null)
  const barrasTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** Evita repetir o foco inicial a cada refetch da config (ex.: volta o foco pro código de
   *  barras enquanto o operador já está digitando em outro campo) — só roda uma vez, assim que
   *  `cfgExigeVenda` chega pela primeira vez. */
  const focoInicialFeito = useRef(false)

  useEffect(() => {
    return () => {
      if (barrasTimeoutRef.current) clearTimeout(barrasTimeoutRef.current)
    }
  }, [])

  /** Ponto de partida da tela, decidido pela configuração. **Revisão 2026-08-19:** com o número
   *  da venda obrigatório, o operador não digita mais nada solto — abre o popup de seleção por
   *  venda (que já pede o número e lista os itens). Sem a obrigatoriedade, o fluxo de sempre
   *  continua: foco no código de barras, leitura livre. Reusado tanto na abertura da tela quanto
   *  depois de gravar uma devolução (o formulário volta ao estado inicial). */
  const iniciarFluxo = () => {
    if (exigeNumeroVenda) {
      setMostrarSelecaoVenda(true)
    } else {
      campoBarrasRef.current?.focus()
    }
  }

  useEffect(() => {
    // `staleTime` padrão é 0 — se a tela já tinha sido visitada nesta sessão (navegação por SPA,
    // não recarga de página), `cfgExigeVenda` chega preenchido com o valor em CACHE na primeira
    // renderização, mesmo que esteja desatualizado (ex.: acabou de mudar em Parâmetros do
    // Sistema), enquanto uma nova busca roda por trás. Esperar `!buscandoCfgExigeVenda` garante
    // que o foco só decide depois que o valor realmente confirmado do servidor chegou.
    if (cfgExigeVenda === undefined || buscandoCfgExigeVenda || focoInicialFeito.current) return
    focoInicialFeito.current = true
    iniciarFluxo()
  }, [cfgExigeVenda, buscandoCfgExigeVenda, exigeNumeroVenda])

  /** Dispara sozinha ao sair do campo (`onBlur`) ou no Enter — sem botão manual (pedido do dono
   *  do produto, 2026-08-11). Além do vendedor, a resposta traz os itens vendidos naquela venda:
   *  a partir daqui a tela só aceita devolver produtos que constam dela (ver `mapaDisponivel`). */
  const buscarVendedor = async () => {
    const numero = Number(numeroVendaTexto.trim())
    if (!numeroVendaTexto.trim() || !Number.isFinite(numero) || numero <= 0) {
      setVendedor(null)
      return
    }
    setBuscandoVendedor(true)
    try {
      const resultado = await buscarVendedorDaVenda(numero)
      setVendedor(resultado)
      if (!resultado.idFuncionario) {
        setToast({ texto: 'Venda encontrada, mas sem vendedor associado.', tipo: 'erro' })
      }
    } catch (e) {
      setVendedor(null)
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível localizar a venda.', tipo: 'erro' })
    } finally {
      setBuscandoVendedor(false)
    }
  }

  const aoDigitarNumeroVenda: React.KeyboardEventHandler<HTMLInputElement> = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      buscarVendedor()
    }
  }

  /** `null` = sem restrição (nenhuma venda informada/resolvida — qualquer produto pode ser
   *  devolvido, como sempre foi). Quando preenchido, mapeia `chaveLinha -> quantidade ainda
   *  disponível pra devolução` daquela venda (já descontando devoluções anteriores dela). */
  const mapaDisponivel = useMemo(() => {
    if (!vendedor) return null
    const mapa = new Map<string, number>()
    vendedor.itens.forEach((it) =>
      mapa.set(chaveLinha(it.idVariacao, it.precoUnitario), (mapa.get(chaveLinha(it.idVariacao, it.precoUnitario)) ?? 0) + it.qtdDisponivelDevolucao),
    )
    return mapa
  }, [vendedor])

  /** Saldo por VARIAÇÃO — usado só para o item lançado por código de barras, que não veio da grid
   *  e portanto não sabe a qual linha pertence. Sem isso, um produto legitimamente vendido seria
   *  recusado com "não faz parte da venda" só por ter chegado por outro caminho. */
  const mapaDisponivelPorVariacao = useMemo(() => {
    if (!vendedor) return null
    const mapa = new Map<number, number>()
    vendedor.itens.forEach((it) => mapa.set(it.idVariacao, (mapa.get(it.idVariacao) ?? 0) + it.qtdDisponivelDevolucao))
    return mapa
  }, [vendedor])

  /** Mensagem de erro do item (ou `null` se estiver tudo certo) — mesma regra usada tanto pra
   *  bloquear na leitura/pesquisa (toast, antes de lançar) quanto pra sinalizar na grade um item
   *  que já estava lá e deixou de ser válido (ex.: trocou o número da venda). */
  const erroDoItem = (item: { idVariacao: number; precoVenda: number; daVenda: boolean }, qtd: number): string | null => {
    if (qtd <= 0) return 'Informe uma quantidade maior que zero.'
    if (!mapaDisponivel || !mapaDisponivelPorVariacao) return null
    const disponivel = item.daVenda
      ? mapaDisponivel.get(chaveLinha(item.idVariacao, item.precoVenda))
      : mapaDisponivelPorVariacao.get(item.idVariacao)
    if (disponivel === undefined) {
      return `Este produto não faz parte da venda nº ${numeroVendaTexto}.`
    }
    if (qtd > disponivel) {
      return `Só é possível devolver ${formatarQuantidade(disponivel, permiteQtdDecimal)} unidade(s) deste produto na venda nº ${numeroVendaTexto}.`
    }
    return null
  }

  const efetivar = useMutation({
    mutationFn: () =>
      efetivarDevolucao({
        numeroVenda: numeroVendaTexto.trim() ? Number(numeroVendaTexto.trim()) : null,
        // `precoUnitario` só vai quando a linha veio da grid da venda — é ele que diz ao servidor
        // QUAL linha está sendo devolvida (item 2). Para item lançado por código de barras o preço
        // é o do cadastro e não identifica linha nenhuma; mandá-lo faria o servidor recusar.
        itens: itens.map((i) => ({
          idVariacao: i.idVariacao,
          qtd: desmascararQuantidade(i.qtdTexto, permiteQtdDecimal),
          ...(i.daVenda ? { precoUnitario: i.precoVenda } : {}),
        })),
      }),
    onSuccess: (resultado) => {
      setToast({ texto: 'Devolução gravada com sucesso.', tipo: 'sucesso' })
      setItens([])
      setNumeroVendaTexto('')
      setVendedor(null)
      setValeGerado(resultado)
    },
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível gravar a devolução.', tipo: 'erro' }),
  })

  /** Popup de seleção confirmado (modo "número da venda obrigatório", 2026-08-19) — troca a grid
   *  inteira pelos itens escolhidos, cada um com a quantidade DISPONÍVEL da venda e o preço que a
   *  venda praticou (`precoUnitario`, não o do cadastro atual — ver `ItemVendaOrigem`). Devolução
   *  parcial de um item se ajusta no campo de quantidade da grid, como sempre. */
  const aoConfirmarSelecao = (vendaSelecionada: VendedorDaVenda, itensSelecionados: ItemVendaOrigem[]) => {
    setNumeroVendaTexto(String(vendaSelecionada.numeroVenda))
    setVendedor(vendaSelecionada)
    setItens(
      itensSelecionados.map((i) => ({
        idVariacao: i.idVariacao,
        descricao: i.descricaoProduto,
        variacao: [i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ') || null,
        precoVenda: i.precoUnitario,
        descontoUnitario: i.descontoUnitario,
        qtdTexto: formatarQuantidade(i.qtdDisponivelDevolucao, permiteQtdDecimal),
        daVenda: true,
      })),
    )
    setMostrarSelecaoVenda(false)
  }

  // Lança um produto na grid — se a variação já está na lista, soma `qtd` na quantidade em vez
  // de duplicar a linha (mesma rotina do PDV/Transferência).
  const lancarProduto = (produto: PdvProduto, qtd: number = 1) => {
    setItens((atual) => {
      // Só agrupa com uma linha que também NÃO veio da venda: somar no item vindo da grid da venda
      // apagaria o preço da linha que o operador escolheu (item 2).
      const existente = atual.find((i) => i.idVariacao === produto.idVariacao && !i.daVenda)
      if (existente) {
        return atual.map((i) =>
          i.idVariacao === produto.idVariacao && !i.daVenda
            ? { ...i, qtdTexto: formatarQuantidade(desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) + qtd, permiteQtdDecimal) }
            : i,
        )
      }
      return [
        ...atual,
        {
          idVariacao: produto.idVariacao,
          descricao: produto.descricaoProduto,
          variacao: variacaoTexto(produto),
          precoVenda: produto.precoVenda,
          // Sem venda de origem não há desconto a herdar — o preço do cadastro é o próprio líquido.
          descontoUnitario: 0,
          qtdTexto: formatarQuantidade(qtd, permiteQtdDecimal),
          daVenda: false,
        },
      ]
    })
  }

  /** Quantidade já lançada na grade pra essa variação (0 se ainda não foi lançada) — soma-se ao
   *  que está sendo lançado agora antes de validar contra `mapaDisponivel`. Conta TODAS as linhas
   *  da variação, inclusive as vindas da venda: o saldo por variação é um só. */
  const qtdJaNaGrade = (idVariacao: number): number =>
    itens
      .filter((i) => i.idVariacao === idVariacao)
      .reduce((soma, i) => soma + desmascararQuantidade(i.qtdTexto, permiteQtdDecimal), 0)

  /** `true` se pode lançar — já mostra o toast de erro e retorna `false` quando não pode, pra
   *  `lerCodigo`/`aoSelecionarNaPesquisa` só chamarem `lancarProduto` no caminho feliz. */
  const validarAntesDeLancar = (produto: PdvProduto, qtd: number): boolean => {
    const erro = erroDoItem(
      { idVariacao: produto.idVariacao, precoVenda: produto.precoVenda, daVenda: false },
      qtdJaNaGrade(produto.idVariacao) + qtd,
    )
    if (erro) {
      setToast({ texto: erro, tipo: 'erro' })
      return false
    }
    return true
  }

  const lerCodigo = async (valorDigitado: string) => {
    const { qtd, codigo } = interpretarCodigoBarras(valorDigitado)
    try {
      const produto = await buscarProdutoPorCodigo(codigo)
      if (!validarAntesDeLancar(produto, qtd)) return
      lancarProduto(produto, qtd)
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

  const aoSelecionarNaPesquisa = (produto: PdvProduto) => {
    setMostrarPesquisa(false)
    if (!validarAntesDeLancar(produto, 1)) return
    lancarProduto(produto)
    setValorBarras(produto.sku)
    if (barrasTimeoutRef.current) clearTimeout(barrasTimeoutRef.current)
    barrasTimeoutRef.current = setTimeout(() => {
      setValorBarras('')
      campoBarrasRef.current?.focus()
    }, 400)
  }

  const removerItem = (chave: string) => {
    setItens((atual) => atual.filter((i) => chaveLinha(i.idVariacao, i.precoVenda) !== chave))
  }

  const alterarQtd = (chave: string, texto: string) => {
    setItens((atual) =>
      atual.map((i) =>
        chaveLinha(i.idVariacao, i.precoVenda) === chave ? { ...i, qtdTexto: mascararQuantidade(texto, permiteQtdDecimal) } : i,
      ),
    )
  }

  const aoSairQtd = (chave: string) => {
    setItens((atual) =>
      atual.map((i) =>
        chaveLinha(i.idVariacao, i.precoVenda) === chave ? { ...i, qtdTexto: completarQuantidade(i.qtdTexto, permiteQtdDecimal) } : i,
      ),
    )
  }

  const algumItemComErro = itens.some((i) => erroDoItem(i, desmascararQuantidade(i.qtdTexto, permiteQtdDecimal)) !== null)
  const faltaNumeroVendaObrigatorio = exigeNumeroVenda && !numeroVendaTexto.trim()
  const podeConfirmar = itens.length > 0 && !algumItemComErro && !faltaNumeroVendaObrigatorio
  const qtdTotal = itens.reduce((soma, i) => soma + desmascararQuantidade(i.qtdTexto, permiteQtdDecimal), 0)
  /**
   * ⚠️ Desconto da linha **arredondado UMA vez**, exatamente como a efetivação grava
   * (`descontoDaLinha`, HALF_UP em `DevolucaoProdutoService`). Multiplicar um unitário já
   * arredondado dava outro centavo: R$ 10 de desconto em 3 unidades vira 3,3333… e a tela
   * prometia R$ 20,01 onde o vale valia R$ 20,00. O servidor manda o unitário com 6 casas
   * justamente para o front conseguir refazer a conta dele.
   */
  const descontoDaLinha = (item: ItemLinha, qtd: number) =>
    Math.round(qtd * item.descontoUnitario * 100) / 100
  const totalDaLinha = (item: ItemLinha, qtd: number) =>
    qtd * item.precoVenda - descontoDaLinha(item, qtd)

  // Subtotal BRUTO, descontos e total LÍQUIDO — as três linhas que o comprovante do vale imprime.
  // A grade mostra as três: uma coluna que não soma ela mesma é o que o operador confere com o
  // cliente na frente (regressão da rodada 1, achada na rodada 3).
  const subtotalBruto = itens.reduce(
    (soma, i) => soma + desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) * i.precoVenda,
    0,
  )
  const descontoTotal = itens.reduce(
    (soma, i) => soma + descontoDaLinha(i, desmascararQuantidade(i.qtdTexto, permiteQtdDecimal)),
    0,
  )
  const valorTotal = subtotalBruto - descontoTotal

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeDevolucaoProduto size={34} />
            <h1>Devolução de Produtos</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
            <button
              type="button"
              className="btn"
              disabled={!podeConfirmar || efetivar.isPending || !acoes.incluir}
              onClick={() => efetivar.mutate()}
            >
              {efetivar.isPending ? 'Gravando…' : 'Gravar Devolução'}
            </button>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes form-secoes-larga">
          {/* Modo "venda obrigatória" (2026-08-19): a venda já foi escolhida no popup — aqui só
              o resumo + o caminho de volta pro popup. O campo digitável e a leitura por código de
              barras não existem nesse modo (o popup os substitui). */}
          {exigeNumeroVenda ? (
            <section className="section">
              <div style={{ display: 'flex', gap: 16, alignItems: 'center', justifyContent: 'space-between' }}>
                <div>
                  <p className="section-label" style={{ margin: 0 }}>
                    Venda de Origem
                  </p>
                  <p className="muted" style={{ marginTop: 4 }}>
                    {vendedor ? (
                      <>
                        Venda nº <strong>{vendedor.numeroVenda}</strong>
                        {vendedor.idFuncionario && (
                          <>
                            {' · '}Vendedor: <strong>{vendedor.nomeFuncionario}</strong>
                          </>
                        )}
                      </>
                    ) : (
                      'Nenhuma venda selecionada.'
                    )}
                  </p>
                </div>
                <button type="button" className="btn ghost" onClick={() => setMostrarSelecaoVenda(true)}>
                  {vendedor ? 'Trocar Venda' : 'Selecionar Venda'}
                </button>
              </div>
            </section>
          ) : (
            <section className="section">
              <p className="section-label" style={{ margin: 0 }}>
                Venda de Origem (opcional)
              </p>
              <p className="muted" style={{ marginTop: 4 }}>
                Informe o número da venda para identificar o vendedor automaticamente — a partir daí só é possível
                devolver produtos que fizeram parte dela, até a quantidade ainda não devolvida. Deixe em branco para
                uma devolução sem vínculo, como antes.
              </p>
              <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', marginTop: 10 }}>
                <div style={{ maxWidth: 220 }}>
                  <label>Número da Venda</label>
                  <input
                    ref={numeroVendaRef}
                    type="text"
                    inputMode="numeric"
                    autoComplete="off"
                    placeholder="Opcional"
                    value={numeroVendaTexto}
                    onChange={(e) => {
                      setNumeroVendaTexto(e.target.value.replace(/\D/g, ''))
                      setVendedor(null)
                    }}
                    onKeyDown={aoDigitarNumeroVenda}
                    onBlur={buscarVendedor}
                  />
                </div>
                {buscandoVendedor && <p className="muted" style={{ margin: 0 }}>Buscando…</p>}
                {!buscandoVendedor && vendedor && (
                  <p className="muted" style={{ margin: 0 }}>
                    {vendedor.idFuncionario
                      ? <>Vendedor: <strong>{vendedor.nomeFuncionario}</strong></>
                      : 'Sem vendedor associado a esta venda.'}
                    {' · '}
                    {vendedor.itens.length > 0
                      ? `${vendedor.itens.length} produto(s) diferentes vendidos, disponíveis pra devolução.`
                      : 'Nenhum item de venda encontrado nesta venda.'}
                  </p>
                )}
              </div>
            </section>
          )}

          <section className="section">
            <p className="section-label" style={{ margin: 0 }}>
              Produtos
            </p>

            {!exigeNumeroVenda && (
              <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', marginTop: 10 }}>
                <div className="pdv-campo-codigo-barras" style={{ flex: 1 }}>
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
                  <p className="pdv-dica">Leia o código de barras do produto devolvido e pressione Enter.</p>
                  <p className="pdv-dica">Dica: "5*código" lança direto com quantidade 5.</p>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                  <div className="pdv-rotulo" style={{ visibility: 'hidden' }}>
                    Pesquisar
                  </div>
                  <button type="button" className="btn ghost" style={{ height: 50, whiteSpace: 'nowrap' }} onClick={() => setMostrarPesquisa(true)}>
                    <IconeLupa /> Pesquisar Produto
                  </button>
                </div>
              </div>
            )}

            {itens.length === 0 ? (
              <p className="muted" style={{ marginTop: 12 }}>
                {exigeNumeroVenda
                  ? 'Nenhum produto selecionado ainda — use "Selecionar Venda" para escolher o que será devolvido.'
                  : 'Nenhum produto adicionado ainda.'}
              </p>
            ) : (
              <div className="table-wrap" style={{ marginTop: 12 }}>
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Descrição</th>
                      <th>Variação</th>
                      <th style={{ textAlign: 'right' }}>Quantidade</th>
                      <th style={{ textAlign: 'right' }}>Valor Unitário</th>
                      <th style={{ textAlign: 'right' }}>Desconto</th>
                      <th style={{ textAlign: 'right' }}>Valor Total</th>
                      <th aria-label="Ações" />
                    </tr>
                  </thead>
                  <tbody>
                    {itens.map((item) => {
                      const qtdNumero = desmascararQuantidade(item.qtdTexto, permiteQtdDecimal)
                      const erro = erroDoItem(item, qtdNumero)
                      const chave = chaveLinha(item.idVariacao, item.precoVenda)
                      // ⚠️ `key` pela CHAVE DA LINHA, não pelo idVariacao (item 2): o mesmo produto
                      // pode estar duas vezes na grade com preços diferentes, e chave repetida faz
                      // o React embaralhar as linhas ao editar a quantidade de uma delas.
                      return (
                        <tr key={chave}>
                          <td>{item.descricao}</td>
                          <td>{item.variacao ?? '—'}</td>
                          <td style={{ textAlign: 'right' }}>
                            <input
                              className="mono"
                              style={{ width: 110, textAlign: 'right' }}
                              inputMode={permiteQtdDecimal ? undefined : 'numeric'}
                              value={item.qtdTexto}
                              onChange={(e) => alterarQtd(chave, e.target.value)}
                              onBlur={() => aoSairQtd(chave)}
                              onFocus={(e) => e.target.select()}
                            />
                            {erro && <p className="erro-campo">{erro}</p>}
                          </td>
                          <td className="mono" style={{ textAlign: 'right' }}>
                            {moeda(item.precoVenda)}
                          </td>
                          <td className="mono" style={{ textAlign: 'right' }}>
                            {descontoDaLinha(item, qtdNumero) > 0 ? `- ${moeda(descontoDaLinha(item, qtdNumero))}` : '—'}
                          </td>
                          <td className="mono" style={{ textAlign: 'right' }}>
                            {moeda(totalDaLinha(item, qtdNumero))}
                          </td>
                          <td className="acoes-cell">
                            <button
                              type="button"
                              className="acao-icone acao-excluir"
                              onClick={() => removerItem(chave)}
                              aria-label={`Remover ${item.descricao}`}
                              title="Remover"
                            >
                              <IconeExcluir />
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td colSpan={2}>
                        <strong>Total</strong>
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        <strong>{formatarQuantidade(qtdTotal, permiteQtdDecimal)}</strong>
                      </td>
                      <td />
                      <td className="mono" style={{ textAlign: 'right' }}>
                        {descontoTotal > 0 ? <strong>- {moeda(descontoTotal)}</strong> : null}
                      </td>
                      <td className="mono" style={{ textAlign: 'right' }}>
                        <strong>{moeda(valorTotal)}</strong>
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

      {mostrarSelecaoVenda && (
        <SelecaoItensVendaModal
          permiteQtdDecimal={permiteQtdDecimal}
          aoConfirmar={aoConfirmarSelecao}
          aoFechar={() => {
            setMostrarSelecaoVenda(false)
            // Sem venda selecionada não há o que fazer nesta tela neste modo — sai, em vez de
            // deixar o operador olhando uma tela vazia sem caminho ([[project_botao_fechar_tela]]).
            if (!vendedor) navigate(-1)
          }}
        />
      )}

      {mostrarPesquisa && (
        <PesquisaProdutoModal aoFechar={() => setMostrarPesquisa(false)} aoSelecionar={aoSelecionarNaPesquisa} />
      )}

      {valeGerado && (
        <ComprovanteValeModal
          devolucao={valeGerado}
          nomeEmpresa={eu?.empresa.nome ?? '—'}
          aoFechar={() => {
            setValeGerado(null)
            iniciarFluxo()
          }}
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
