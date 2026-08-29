import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AberturaCaixaModal from '../../components/AberturaCaixaModal'
import AjudaDaTela from '../../components/AjudaDaTela'
import CabecalhoModal from '../../components/CabecalhoModal'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeAjustar, IconeConfirmar, IconeLimpar, IconeLupa, IconePdv } from '../../components/Icones'
import { ApiError } from '../../lib/api'
import { buscarStatusCaixa } from '../../lib/caixa'
import { buscarPermiteQtdDecimal, buscarUsaServicos } from '../../lib/configuracaoGeral'
import { formatarMoeda, formatarQuantidade } from '../../lib/masks'
import {
  buscarProdutoPorCodigo,
  interpretarCodigoBarras,
  type ItemLedger,
  type PdvProduto,
  type VendaEfetivada,
} from '../../lib/pdv'
import type { Orcamento } from '../../lib/orcamento'
import type { OrdemServico } from '../../lib/ordensServico'
import { useRotinaCritica } from '../../lib/rotinaCritica'
import { maiusculas } from '../../lib/texto'
import AlteraQuantidadeModal from './AlteraQuantidadeModal'
import ComprovantePapeletaModal from './ComprovantePapeletaModal'
import FormaPagamentoModal from './FormaPagamentoModal'
import PesquisaProdutoModal from './PesquisaProdutoModal'
import PuxarOrcamentoModal from './PuxarOrcamentoModal'
import PuxarOrdemServicoModal from './PuxarOrdemServicoModal'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

function totalLinha(item: ItemLedger): number {
  return item.qtd * item.precoUnit
}

function variacaoTexto(produto: PdvProduto): string | null {
  if (!produto.variacaoCor && !produto.variacaoTamanho) return null
  return [produto.variacaoCor, produto.variacaoTamanho].filter(Boolean).join(' · ')
}

/**
 * PDV (Frente de Caixa, docs/telas/pdv.md) — busca/leitura de produto e efetivação de venda
 * de verdade (API `com.vetor.niner.vendas`, 2026-07-28): grava venda + baixa de estoque real
 * (trigger existente, P1) + parcela(s) em contas_receber a partir da forma de pagamento
 * escolhida no F5. v1 sem cliente/vendedor/multi-empresa/desconto — ver "Escopo desta versão"
 * em docs/telas/pdv.md.
 */
export default function Pdv() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { setEmAndamento } = useRotinaCritica()
  const { data: statusCaixa } = useQuery({ queryKey: ['caixa-status'], queryFn: buscarStatusCaixa })
  /** Módulo de serviços (S1) — decide se o F5 oferece Ordem de Serviço além do orçamento. */
  const { data: usaServicos } = useQuery({
    queryKey: ['config-geral', 'usa-servicos'],
    queryFn: buscarUsaServicos,
  })
  const caixaFechado = statusCaixa !== undefined && !statusCaixa.aberto
  const [ledger, setLedger] = useState<ItemLedger[]>([])
  /** Linha destacada no ledger — id da LINHA, não SKU (2026-08-21): o mesmo produto pode ocupar
   *  duas linhas com preços diferentes. */
  const [selecionado, setSelecionado] = useState<number | null>(null)
  const [valorBarras, setValorBarras] = useState('')
  const [flashMsg, setFlashMsg] = useState<string | null>(null)
  const [teclaAtiva, setTeclaAtiva] = useState<string | null>(null)
  const [mostrarPesquisa, setMostrarPesquisa] = useState(false)
  const [mostrarAlteraQtd, setMostrarAlteraQtd] = useState(false)
  const [mostrarFormaPagamento, setMostrarFormaPagamento] = useState(false)
  const [idVendaPapeleta, setIdVendaPapeleta] = useState<number | null>(null)
  /**
   * Orçamento puxado para esta venda (V058) e o cliente/vendedor que vieram junto.
   *
   * <p>⚠️ O `idOrcamento` viaja até `efetivarVenda`: é ele que faz o servidor usar o preço
   * congelado e recusar quantidade maior que a orçada. Limpar a venda limpa isto também — senão
   * a venda seguinte carimbaria um orçamento que não tem nada a ver com ela.
   */
  const [orcamentoPuxado, setOrcamentoPuxado] = useState<Orcamento | null>(null)
  const [mostrarPuxarOrcamento, setMostrarPuxarOrcamento] = useState(false)
  /**
   * Ordem de Serviço puxada para esta venda (V087, S4).
   *
   * <p>⛔ Estado **separado** do orçamento, e nunca os dois ao mesmo tempo — o servidor recusa a
   * venda que chega com ambos. São documentos diferentes: o orçamento é proposta, a OS é trabalho
   * feito. Limpar a venda limpa isto também, pela mesma razão do orçamento.
   */
  const [osPuxada, setOsPuxada] = useState<OrdemServico | null>(null)
  const [mostrarPuxarOs, setMostrarPuxarOs] = useState(false)
  /** Só existe com o módulo de serviços ligado: é o passo que pergunta "orçamento ou OS?". */
  const [mostrarEscolhaDocumento, setMostrarEscolhaDocumento] = useState(false)
  /** Gerador de id de linha do ledger. Só precisa ser único DENTRO da venda em andamento — o
   *  ledger é estado de tela e morre no F4/na efetivação. */
  const proximaLinhaRef = useRef(1)
  const campoBarrasRef = useRef<HTMLInputElement>(null)
  const linhasLedgerRef = useRef<Array<HTMLDivElement | null>>([])
  const flashTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const ativaTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const barrasTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const algumModalAberto =
    mostrarPesquisa || mostrarAlteraQtd || mostrarFormaPagamento || mostrarPuxarOrcamento ||
    mostrarPuxarOs || mostrarEscolhaDocumento || idVendaPapeleta !== null || caixaFechado

  useEffect(() => {
    campoBarrasRef.current?.focus()
    return () => {
      if (flashTimeoutRef.current) clearTimeout(flashTimeoutRef.current)
      if (ativaTimeoutRef.current) clearTimeout(ativaTimeoutRef.current)
      if (barrasTimeoutRef.current) clearTimeout(barrasTimeoutRef.current)
    }
  }, [])

  const itemSelecionado = ledger.find((i) => i.idLinha === selecionado) ?? null

  const mostrarFlash = (texto: string) => {
    setFlashMsg(texto)
    if (flashTimeoutRef.current) clearTimeout(flashTimeoutRef.current)
    flashTimeoutRef.current = setTimeout(() => setFlashMsg(null), 1600)
  }

  const piscarTecla = (id: string) => {
    setTeclaAtiva(id)
    if (ativaTimeoutRef.current) clearTimeout(ativaTimeoutRef.current)
    ativaTimeoutRef.current = setTimeout(() => setTeclaAtiva(null), 160)
  }

  const selecionarLinha = (idLinha: number) => {
    setSelecionado(idLinha)
  }

  /** Navegação por ↑/↓ nos Produtos Vendidos (2026-07-28) — move a seleção e o foco de teclado. */
  const navegarLedger = (direcao: 1 | -1) => {
    if (ledger.length === 0) return
    const indiceAtual = ledger.findIndex((i) => i.idLinha === selecionado)
    const novoIndice = Math.min(Math.max(indiceAtual + direcao, 0), ledger.length - 1)
    selecionarLinha(ledger[novoIndice].idLinha)
    linhasLedgerRef.current[novoIndice]?.focus()
  }

  /**
   * `qtd` é 1 por padrão (leitura simples) — "5*código" no campo de barras já manda a
   * quantidade digitada (ver {@link interpretarCodigoBarras}), somada se o item já estiver na venda.
   *
   * <p>⚠️ **Junta pelo PREÇO, não só pelo produto** (2026-08-21, decisão do dono do produto). Se o
   * item já está na venda pelo preço congelado de um orçamento e o preço de hoje é outro, a nova
   * unidade vai para uma **linha separada, com o preço de hoje**: a loja honra o que orçou, mas o
   * que o cliente resolveu levar a mais na hora não foi orçado e não tem por que sair mais barato
   * (nem mais caro). Preço igual junta numa linha só, porque aí não há diferença nenhuma a mostrar.
   */
  const lancarProduto = (produto: PdvProduto, qtd: number = 1) => {
    // Mesma linha = mesmo produto E mesmo preço. O id novo é reservado antes para a seleção poder
    // ser aplicada fora do updater — chamar `setState` de dentro dele roda duas vezes em StrictMode.
    const existenteAgora = ledger.find((i) => i.codigo === produto.sku && i.precoUnit === produto.precoVenda)
    const idLinha = existenteAgora ? existenteAgora.idLinha : proximaLinhaRef.current++
    setLedger((atual) => {
      const existente = atual.find((i) => i.codigo === produto.sku && i.precoUnit === produto.precoVenda)
      if (existente) {
        return atual.map((i) => (i.idLinha === existente.idLinha ? { ...i, qtd: i.qtd + qtd } : i))
      }
      return [
        ...atual,
        {
          idLinha,
          idVariacao: produto.idVariacao,
          codigo: produto.sku,
          descricao: produto.descricaoProduto,
          variacao: variacaoTexto(produto),
          qtd,
          precoUnit: produto.precoVenda,
          urlImagem: produto.urlImagem,
          // Linha nascida do balcão nunca é coberta pelo orçamento — nem quando o produto está nele.
          qtdOrcada: 0,
        },
      ]
    })
    selecionarLinha(idLinha)
  }

  const lerCodigo = async (valorDigitado: string) => {
    const { qtd, codigo } = interpretarCodigoBarras(valorDigitado)
    try {
      const produto = await buscarProdutoPorCodigo(codigo)
      lancarProduto(produto, qtd)
    } catch (e) {
      mostrarFlash(e instanceof ApiError ? e.message : 'Não foi possível ler o código de barras.')
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

  const f2Pesquisa = () => {
    piscarTecla('f2')
    setMostrarPesquisa(true)
  }

  const aoSelecionarNaPesquisa = (produto: PdvProduto) => {
    setMostrarPesquisa(false)
    // Preenche o código de barras automaticamente (como se tivesse sido lido) antes de
    // lançar o item, pra deixar visível de onde a linha veio.
    setValorBarras(produto.sku)
    lancarProduto(produto)
    if (barrasTimeoutRef.current) clearTimeout(barrasTimeoutRef.current)
    barrasTimeoutRef.current = setTimeout(() => {
      setValorBarras('')
      campoBarrasRef.current?.focus()
    }, 400)
  }

  const f3AlteraQtd = () => {
    piscarTecla('f3')
    setMostrarAlteraQtd(true)
  }

  /**
   * ⚠️ Numa linha coberta por orçamento, a quantidade só pode DIMINUIR (2026-08-21, achado em
   * auditoria).
   *
   * <p>O preço da linha é o **congelado**, e o total da tela é `qtd × precoUnit`. Deixar o F3
   * aumentar aplicava o preço congelado também às unidades a mais — mas o servidor, desde hoje,
   * cobra o congelado só até `qtdOrcada` e o resto pelo preço de hoje. A tela pedia R$ 50 e o
   * servidor exigia R$ 65, e a venda travava com uma mensagem sobre **pagamento que não fecha** —
   * mandando o operador mexer nas formas de pagamento, que não tinham nada de errado.
   *
   * <p>Para levar mais do que foi orçado, o operador **bipa o produto de novo**: `lancarProduto`
   * abre uma linha separada com o preço de hoje, que é exatamente a regra pedida ("a loja honra o
   * orçamento; o que passar disso é venda comum"). E isso conversa com a regra do orçamento, que
   * sempre foi "só dá para diminuir".
   */
  const aoAlterarQtd = (idLinha: number, novaQtd: number) => {
    setLedger((atual) =>
      atual.map((i) => {
        if (i.idLinha !== idLinha) return i
        const teto = i.qtdOrcada > 0 ? Math.min(novaQtd, i.qtdOrcada) : novaQtd
        return { ...i, qtd: teto }
      }),
    )
    const linha = ledger.find((i) => i.idLinha === idLinha)
    if (linha && linha.qtdOrcada > 0 && novaQtd > linha.qtdOrcada) {
      // ⚠️ Nomeia o documento CERTO: numa venda vinda de OS, falar em "orçamento" manda o
      // operador procurar um documento que ele não abriu (mesmo cuidado do selo do pagamento).
      const documento = linha.origemDocumento === 'OS' ? 'a OS' : 'o orçamento'
      mostrarFlash(`${documento === 'a OS' ? 'A OS' : 'O orçamento'} cobre ${linha.qtdOrcada}. Para levar mais, leia o produto de novo — sai pelo preço de hoje.`)
    }
  }

  const aoRemoverItem = (idLinha: number) => {
    // Se não sobra nenhuma linha coberta pelo orçamento, a venda deixou de cumpri-lo — e mandar o
    // `idOrcamento` assim mesmo faz o servidor recusar (mesma armadilha do F4, acima). Calculado
    // FORA do updater: `setState` dentro dele roda duas vezes em StrictMode.
    const restante = ledger.filter((i) => i.idLinha !== idLinha)
    if (!restante.some((i) => i.qtdOrcada > 0)) {
      setOrcamentoPuxado(null)
      setOsPuxada(null)
    }
    setLedger((atual) => atual.filter((i) => i.idLinha !== idLinha))
    setSelecionado((atual) => (atual === idLinha ? null : atual))
  }

  const f4LimpaTela = () => {
    piscarTecla('f4')
    setLedger([])
    setSelecionado(null)
    // ⚠️ Limpar o orçamento JUNTO (2026-08-21, achado em auditoria). Sem isto, o F4 esvaziava o
    // ledger mas deixava o `idOrcamento` grudado na tela: o operador puxava um orçamento, o
    // cliente desistia, ele limpava, bipava outros produtos — e a venda era recusada com 400
    // ("não trouxe nenhum item marcado como do orçamento"), sem saída pela tela a não ser
    // recarregar a página. Antes da guarda no servidor era pior e silencioso: a venda saía a preço
    // de cadastro e queimava um orçamento que nada tinha a ver com ela.
    setOrcamentoPuxado(null)
    setOsPuxada(null)
    setValorBarras('')
    setMostrarPesquisa(false)
    setMostrarAlteraQtd(false)
    setMostrarFormaPagamento(false)
    campoBarrasRef.current?.focus()
  }

  const f6EfetivaVenda = () => {
    if (ledger.length === 0) {
      mostrarFlash('Nenhum item na venda.')
      return
    }
    // Rotina crítica (docs/telas/usuario.md, 2026-08-11): do início da forma de pagamento até
    // fechar o comprovante — o logoff automático por fim de horário de acesso espera terminar.
    setEmAndamento(true)
    setMostrarFormaPagamento(true)
  }

  /**
   * F5 puxa um documento anterior para a venda. ⚠️ Só com a venda vazia: puxar por cima de itens
   * já lançados misturaria preço fechado com preço de cadastro sem o operador perceber.
   *
   * <p>Com o módulo de serviços LIGADO existem dois documentos possíveis (orçamento e ordem de
   * serviço) e o F5 pergunta qual. Desligado — que é a loja típica deste ERP — ele vai direto ao
   * orçamento, exatamente como antes: quem nunca vai abrir uma OS não paga um clique por ela.
   */
  const f5BuscarDocumento = () => {
    if (ledger.length > 0) {
      mostrarFlash('Limpe a tela (F4) antes de puxar um documento.')
      return
    }
    // `piscarTecla`, não `setTeclaAtiva`: sem o timeout que a apaga, a F5 ficava destacada para
    // sempre depois do primeiro uso.
    piscarTecla('f5')
    if (usaServicos?.cfgUsaServicos) setMostrarEscolhaDocumento(true)
    else setMostrarPuxarOrcamento(true)
  }

  /**
   * O desconto do documento, na PROPORÇÃO do que está sendo levado.
   *
   * <p>⛔ Passar o valor cheio estava errado (achado de auditoria, 2026-08-29): o popup do
   * orçamento deixa reduzir a quantidade item a item, então um orçamento de R$ 1.000 com R$ 100 de
   * desconto do qual o cliente leva só um item de R$ 200 abria o PDV com R$ 100 de desconto sobre
   * R$ 200 — <b>50%</b>, e passava se o teto da loja fosse ≥ 50%.
   *
   * <p>A conta é a única honesta: quem leva 20% do documento leva 20% do desconto. Documento
   * levado inteiro devolve o valor cheio, que é o caso normal.
   *
   * <p>⚠️ Arredonda para BAIXO (`floor` de centavos): entre dar um centavo a mais e um a menos de
   * desconto, o produto erra para o lado de não prometer o que não combinou.
   */
  const descontoProporcionalDoDocumento = (): number => {
    const desconto = osPuxada?.valorDesconto ?? orcamentoPuxado?.valorDesconto ?? 0
    if (desconto <= 0) return 0
    const subtotalDoDocumento = osPuxada
      ? osPuxada.totalServicos + osPuxada.totalPecas
      : (orcamentoPuxado?.subtotal ?? 0)
    if (subtotalDoDocumento <= 0) return 0
    const levado = ledger.reduce((soma, i) => soma + totalLinha(i), 0)
    if (levado >= subtotalDoDocumento) return desconto
    return Math.floor((desconto * levado * 100) / subtotalDoDocumento) / 100
  }

  const aoVendaEfetivada = (resultado: VendaEfetivada) => {
    setMostrarFormaPagamento(false)
    mostrarFlash(`Venda #${resultado.idVenda} efetivada — ${moeda(resultado.valorLiquido)}.`)
    setLedger([])
    setSelecionado(null)
    // ⚠️ Limpar o orçamento junto: sem isto, a PRÓXIMA venda carimbaria um orçamento que não tem
    // nada a ver com ela — e o servidor recusaria (já vendido), com o operador sem entender.
    setOrcamentoPuxado(null)
    setOsPuxada(null)
    setIdVendaPapeleta(resultado.idVenda)
  }

  useEffect(() => {
    const aoTeclar = (e: KeyboardEvent) => {
      if (algumModalAberto) return
      // F2/F3/F4/F5 e ↑/↓ têm que disparar as funções desta tela mesmo com o foco no campo de
      // código de barras (onde o foco fica por padrão) — nenhuma delas insere caractere, então
      // não há conflito com a digitação. Fundamental: `preventDefault` tem que rodar ANTES de
      // qualquer `return` baseado em foco, senão o atalho nativo do navegador vence (F5 recarrega
      // a página — perderia a venda em andamento — F3 abre a busca do navegador, etc.).
      if (e.key === 'ArrowDown') { e.preventDefault(); navegarLedger(1); return }
      if (e.key === 'ArrowUp') { e.preventDefault(); navegarLedger(-1); return }
      if (e.key === 'F2') { e.preventDefault(); f2Pesquisa(); return }
      if (e.key === 'F3') { e.preventDefault(); f3AlteraQtd(); return }
      if (e.key === 'F4') { e.preventDefault(); f4LimpaTela(); return }
      // ⚠️ F5 e F6 TROCARAM em 2026-08-21 (pedido do dono do produto): F5 busca orçamento, F6
      // efetiva a venda. A ordem segue a do balcão — primeiro se puxa o que o cliente já tinha
      // orçado, depois se fecha.
      if (e.key === 'F5') { e.preventDefault(); f5BuscarDocumento(); return }
      if (e.key === 'F6') { e.preventDefault(); f6EfetivaVenda(); return }
    }
    document.addEventListener('keydown', aoTeclar)
    return () => document.removeEventListener('keydown', aoTeclar)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selecionado, ledger, algumModalAberto])

  const qtdTotal = ledger.reduce((soma, i) => soma + i.qtd, 0)
  const valorTotal = ledger.reduce((soma, i) => soma + totalLinha(i), 0)
  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePdv size={34} />
            <h1>PDV — Frente de Caixa</h1>
          </div>
          {/* O aviso do orçamento fica NA LINHA DO TÍTULO, CENTRALIZADO (2026-08-21, pedido do dono
              do produto). Antes morava perto do rodapé, junto das teclas — longe dos olhos de quem
              está lançando item, que é justamente quando "preço travado" e "cliente fixo" mudam o
              que o operador pode fazer. Irmão do título (não filho) porque centralizar exige que
              ele ocupe o espaço entre o título e as ações; dentro de `.titulo-tela` ele só ficaria
              colado no h1, à esquerda. */}
          {orcamentoPuxado && (
            <span className="pdv-selo-orcamento">
              Venda a partir do <strong>orçamento nº {orcamentoPuxado.idOrcamento}</strong> — preços
              travados, cliente e vendedor <strong>fixos</strong>.
            </span>
          )}
          {osPuxada && (
            <span className="pdv-selo-orcamento">
              Venda a partir da <strong>OS nº {osPuxada.idOrdemServico}</strong> ({osPuxada.objetoServico})
              — preços travados, cliente e vendedor <strong>fixos</strong>.
            </span>
          )}
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="pdv.tela" />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo" style={{ display: 'flex', flexDirection: 'column' }}>
        <div className="pdv-frame">
          <div className="pdv-barra-descricao">
            {itemSelecionado ? (
              <>
                {itemSelecionado.descricao}
                {itemSelecionado.variacao && <span className="pdv-variacao"> — {itemSelecionado.variacao}</span>}
              </>
            ) : (
              'Aguardando leitura do código de barras…'
            )}
          </div>

          <div className="pdv-corpo">
            <div className="pdv-coluna-esq">
              <div className="pdv-linha-produto">
                <div className="pdv-foto-produto">
                  {itemSelecionado?.urlImagem ? (
                    <img src={itemSelecionado.urlImagem} alt={itemSelecionado.descricao} />
                  ) : (
                    <>
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5}>
                        <rect x="3" y="5" width="18" height="14" rx="2" />
                        <circle cx="8.5" cy="10" r="1.5" />
                        <path d="M21 15l-5-4-4 3.5-2-1.5-4 3" />
                      </svg>
                      <span className="pdv-rotulo">{itemSelecionado ? itemSelecionado.descricao : 'Foto do produto'}</span>
                    </>
                  )}
                </div>
                <div className="pdv-stats-produto">
                  <div className="pdv-stat-box">
                    <span className="pdv-rotulo">Quantidade</span>
                    <span className="pdv-valor">
                      {itemSelecionado ? formatarQuantidade(itemSelecionado.qtd, permiteQtdDecimal) : '—'}
                    </span>
                  </div>
                  <div className="pdv-stat-box">
                    <span className="pdv-rotulo">Valor Unitário</span>
                    <span className="pdv-valor">{itemSelecionado ? moeda(itemSelecionado.precoUnit) : '—'}</span>
                  </div>
                  <div className="pdv-stat-box">
                    <span className="pdv-rotulo">Total Produto</span>
                    <span className="pdv-valor">{itemSelecionado ? moeda(totalLinha(itemSelecionado)) : '—'}</span>
                  </div>
                </div>
              </div>

              <div className="pdv-campo-codigo-barras">
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
                <p className="pdv-dica">Dica: "5*código" lança direto com quantidade 5.</p>
              </div>

              <div className="pdv-linha-fkeys">
                <div className={`pdv-tecla${teclaAtiva === 'f2' ? ' pdv-ativa' : ''}`} onClick={f2Pesquisa}>
                  <IconeLupa />
                  <span>
                    <span className="pdv-kbd">F2</span> Pesquisa Produto
                  </span>
                </div>
                <div className={`pdv-tecla${teclaAtiva === 'f3' ? ' pdv-ativa' : ''}`} onClick={f3AlteraQtd}>
                  <IconeAjustar />
                  <span>
                    <span className="pdv-kbd">F3</span> Altera Quantidade
                  </span>
                </div>
                <div className={`pdv-tecla${teclaAtiva === 'f4' ? ' pdv-ativa' : ''}`} onClick={f4LimpaTela}>
                  <IconeLimpar />
                  <span>
                    <span className="pdv-kbd">F4</span> Limpa Tela
                  </span>
                </div>
              </div>

              {/* Buscar Orçamento e Efetiva Venda lado a lado (2026-08-21, pedido do dono do
                  produto), na ordem em que o balcão acontece: primeiro se puxa o que o cliente já
                  tinha orçado, depois se fecha a venda. */}
              <div className="pdv-linha-fechamento">
                <div
                  className={`pdv-tecla${teclaAtiva === 'f5' ? ' pdv-ativa' : ''}`}
                  onClick={f5BuscarDocumento}
                >
                  <IconeLupa />
                  <span>
                    <span className="pdv-kbd">F5</span>{' '}
                    {usaServicos?.cfgUsaServicos ? 'Buscar Orçamento / OS' : 'Buscar Orçamento'}
                  </span>
                </div>
                <button type="button" className="pdv-tecla-venda" onClick={f6EfetivaVenda}>
                  <IconeConfirmar size={24} />
                  <span className="pdv-kbd">F6</span>Efetiva Venda
                </button>
              </div>
            </div>

            <div className="pdv-coluna-dir">
              <div className="pdv-ledger-cabecalho">
                <span>Código Barras</span>
                <span>Descrição do Produto</span>
                <span className="pdv-num">Qtd</span>
                <span className="pdv-num">Vlr Unitário</span>
                <span className="pdv-num">Vlr Total</span>
              </div>
              <div className="pdv-ledger-corpo">
                {ledger.length === 0 ? (
                  <div className="pdv-ledger-vazio">Nenhum item lançado — leia um código de barras pra começar a venda.</div>
                ) : (
                  ledger.map((item, indice) => (
                    <div
                      key={item.idLinha}
                      ref={(el) => {
                        linhasLedgerRef.current[indice] = el
                      }}
                      className={`pdv-ledger-linha${item.idLinha === selecionado ? ' pdv-selecionada' : ''}`}
                      tabIndex={0}
                      onClick={() => selecionarLinha(item.idLinha)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault()
                          selecionarLinha(item.idLinha)
                        }
                      }}
                    >
                      <span className="pdv-cod">{item.codigo}</span>
                      <span>
                        {item.descricao}
                        {item.variacao && <span style={{ color: 'var(--ink-muted)', fontWeight: 400 }}> — {item.variacao}</span>}
                      </span>
                      <span className="pdv-num">{formatarQuantidade(item.qtd, permiteQtdDecimal)}</span>
                      <span className="pdv-num">{moeda(item.precoUnit)}</span>
                      <span className="pdv-num">{moeda(totalLinha(item))}</span>
                    </div>
                  ))
                )}
              </div>
              <div className="pdv-ledger-rodape">
                <div className="pdv-caixa-total">
                  <span className="pdv-rotulo">Qtd Itens</span>
                  <span className="pdv-valor">{formatarQuantidade(qtdTotal, permiteQtdDecimal)}</span>
                </div>
                <div className="pdv-caixa-total pdv-total">
                  <span className="pdv-rotulo">Valor Total da Venda</span>
                  <span className="pdv-valor">{moeda(valorTotal)}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {mostrarPesquisa && (
        <PesquisaProdutoModal aoFechar={() => setMostrarPesquisa(false)} aoSelecionar={aoSelecionarNaPesquisa} />
      )}
      {mostrarAlteraQtd && (
        <AlteraQuantidadeModal
          itens={ledger}
          aoFechar={() => setMostrarAlteraQtd(false)}
          aoAlterarQtd={aoAlterarQtd}
          aoRemover={aoRemoverItem}
        />
      )}
      {mostrarPuxarOrcamento && (
        <PuxarOrcamentoModal
          aoFechar={() => setMostrarPuxarOrcamento(false)}
          aoConfirmar={(orcamento, levando) => {
            // ⚠️ O ledger recebe o preço CONGELADO do orçamento, não o do cadastro — e o servidor
            // relê esse preço do banco na efetivação, então a tela aqui é só o que o operador vê.
            setLedger(
              orcamento.itens
                .filter((i) => (levando[i.idVariacao] ?? 0) > 0)
                .map((i) => ({
                  idLinha: proximaLinhaRef.current++,
                  idVariacao: i.idVariacao,
                  codigo: i.sku,
                  descricao: i.descricao,
                  variacao: [i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ') || null,
                  qtd: levando[i.idVariacao] ?? 0,
                  precoUnit: i.precoVenda,
                  urlImagem: null,
                  // Toda esta linha é coberta pelo orçamento — o teto do que sai com preço congelado.
                  qtdOrcada: levando[i.idVariacao] ?? 0,
                  origemDocumento: 'ORCAMENTO' as const,
                })),
            )
            setOrcamentoPuxado(orcamento)
            setSelecionado(null)
            setMostrarPuxarOrcamento(false)
            mostrarFlash(`Orçamento nº ${orcamento.idOrcamento} carregado — ${orcamento.nomeCliente}.`)
          }}
        />
      )}
      {/* Passo "de onde vem esta venda?" — só existe com o módulo de serviços ligado. Dois botões
          grandes, sem lista: a lista é do popup seguinte, que sabe filtrar por situação. */}
      {mostrarEscolhaDocumento && (
        <div className="modal-overlay" onClick={() => setMostrarEscolhaDocumento(false)}>
          <div
            className="modal"
            role="dialog"
            aria-label="Puxar documento para a venda"
            onClick={(e) => e.stopPropagation()}
          >
            <CabecalhoModal titulo="Puxar para a venda" aoFechar={() => setMostrarEscolhaDocumento(false)} />
            <p className="muted" style={{ marginTop: 0 }}>
              De onde vem esta venda?
            </p>
            <div style={{ display: 'flex', gap: 12 }}>
              <button
                type="button"
                className="btn"
                style={{ flex: 1 }}
                onClick={() => {
                  setMostrarEscolhaDocumento(false)
                  setMostrarPuxarOrcamento(true)
                }}
              >
                Orçamento
              </button>
              <button
                type="button"
                className="btn"
                style={{ flex: 1 }}
                onClick={() => {
                  setMostrarEscolhaDocumento(false)
                  setMostrarPuxarOs(true)
                }}
              >
                Ordem de Serviço
              </button>
            </div>
          </div>
        </div>
      )}
      {mostrarPuxarOs && (
        <PuxarOrdemServicoModal
          aoFechar={() => setMostrarPuxarOs(false)}
          aoConfirmar={(os) => {
            // ⚠️ A OS entra INTEIRA — não há campo de quantidade no popup. O trabalho já foi feito
            // e a peça já foi aplicada; "levar metade da mão de obra" não existe. Quem quiser
            // cobrar diferente cancela a OS e abre outra.
            setLedger(
              os.itens.map((i) => ({
                idLinha: proximaLinhaRef.current++,
                idVariacao: i.idVariacao,
                codigo: i.sku,
                descricao: i.descricaoProduto,
                variacao:
                  [i.variacaoCor, i.variacaoTamanho].filter(Boolean).join(' · ') ||
                  (i.tipoItem === 'SERVICO' ? 'Serviço' : null),
                qtd: i.qtdProduto,
                precoUnit: i.precoVenda,
                urlImagem: null,
                // Toda a linha é coberta pela OS — é o teto do que sai com o preço fechado nela.
                qtdOrcada: i.qtdProduto,
                origemDocumento: 'OS' as const,
              })),
            )
            setOsPuxada(os)
            setSelecionado(null)
            setMostrarPuxarOs(false)
            mostrarFlash(`OS nº ${os.idOrdemServico} carregada — ${os.nomeCliente}.`)
          }}
        />
      )}
      {mostrarFormaPagamento && (
        <FormaPagamentoModal
          itens={ledger}
          valorTotal={valorTotal}
          idOrcamento={orcamentoPuxado?.idOrcamento ?? null}
          idOrdemServico={osPuxada?.idOrdemServico ?? null}
          descontoInicial={descontoProporcionalDoDocumento()}
          clienteInicial={
            orcamentoPuxado
              ? { idCliente: orcamentoPuxado.idCliente, nome: orcamentoPuxado.nomeCliente,
                  cpfCnpj: orcamentoPuxado.documentoCliente, telefone: orcamentoPuxado.telefoneCliente }
              : osPuxada
                // A OS não guarda documento nem telefone do cliente — quem precisar deles (nota
                // fiscal, crediário) usa o cadastro, que é onde eles moram de verdade.
                ? { idCliente: osPuxada.idCliente, nome: osPuxada.nomeCliente, cpfCnpj: null, telefone: null }
                : null
          }
          vendedorInicial={
            orcamentoPuxado
              ? { idFuncionario: orcamentoPuxado.idFuncionario, nome: orcamentoPuxado.nomeFuncionario }
              : osPuxada
                ? { idFuncionario: osPuxada.idFuncionario, nome: osPuxada.nomeFuncionario }
                : null
          }
          aoFechar={() => {
            // Cancelou sem efetivar — libera a rotina crítica; a venda nunca chegou a existir.
            setMostrarFormaPagamento(false)
            setEmAndamento(false)
          }}
          aoEfetivada={aoVendaEfetivada}
        />
      )}
      {idVendaPapeleta !== null && (
        <ComprovantePapeletaModal
          idVenda={idVendaPapeleta}
          aoFechar={() => {
            setIdVendaPapeleta(null)
            campoBarrasRef.current?.focus()
            // Só libera a rotina crítica aqui: venda efetivada e comprovante já fechado
            // (impresso/enviado ou dispensado) — não antes.
            setEmAndamento(false)
          }}
        />
      )}

      {flashMsg && <div className="pdv-flash pdv-show">{flashMsg}</div>}

      {caixaFechado && (
        <AberturaCaixaModal
          statusCaixa={statusCaixa}
          aoAbrir={() => queryClient.invalidateQueries({ queryKey: ['caixa-status'] })}
          aoVoltar={() => navigate('/')}
        />
      )}
    </div>
  )
}
