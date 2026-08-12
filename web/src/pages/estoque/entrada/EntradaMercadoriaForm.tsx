import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../../components/BotaoFecharTela'
import { IconeEstoque, IconeExcluir, IconeFechar, IconeLupa } from '../../../components/Icones'
import FornecedorQuickCreateModal from '../../../components/FornecedorQuickCreateModal'
import ProdutoQuickCreateModal from '../../../components/ProdutoQuickCreateModal'
import Toast from '../../../components/Toast'
import { ApiError } from '../../../lib/api'
import { buscarPermiteQtdDecimal, buscarRateiaFreteEntrada } from '../../../lib/configuracaoGeral'
import { criarCor, listarCores } from '../../../lib/cores'
import { listarEmpresasPermitidas, type Empresa } from '../../../lib/empresas'
import {
  baixarModeloPlanilhaEntrada,
  efetivarEntrada,
  previewPlanilhaEntrada,
  previewXmlEntrada,
  type EntradaEfetivadaResponse,
  type FornecedorXmlPreview,
  type ItemPlanilhaPreviewResponse,
  type ProdutoOpcaoEntrada,
} from '../../../lib/entradaMercadoria'
import { useEu } from '../../../lib/eu'
import { buscarFornecedoresEmissao, type FornecedorOpcaoEmissao } from '../../../lib/etiquetaEmissao'
import { buscarGrade } from '../../../lib/grades'
import {
  completarMoeda,
  completarQuantidade,
  dataParaIso,
  desmascararMoeda,
  desmascararQuantidade,
  formatarMoeda,
  formatarQuantidade,
  isoParaData,
  mascararCpfCnpj,
  mascararData,
  mascararMoeda,
  mascararQuantidade,
} from '../../../lib/masks'
import type { PdvProduto } from '../../../lib/pdv'
import { criarVariacao, type Produto, type VariacaoProduto } from '../../../lib/produtos'
import { maiusculas } from '../../../lib/texto'
import PesquisaProdutoModal from '../../pdv/PesquisaProdutoModal'
import PesquisaProdutoEntradaModal, { type ItemResolvidoEntrada } from './PesquisaProdutoEntradaModal'

function moeda(v: number): string {
  return `R$ ${formatarMoeda(v)}`
}

type ModoEntrada = 'PLANILHA' | 'INDIVIDUAL' | 'XML'

interface ItemLinha {
  idVariacao: number
  descricao: string
  variacao: string | null
  qtdTexto: string
  custoTexto: string
  /** Preço de venda do lote (2026-08-15, popup de pesquisa com grade de tamanhos) — só vem
   *  preenchido quando o item nasceu daquele fluxo (custo + %venda do produto sugeriram o
   *  valor); demais fluxos (Planilha, Cadastrar Produto) deixam vazio e o comportamento de
   *  sempre continua (backend usa o `preco_venda` atual do produto como snapshot). */
  precoVendaTexto: string
  /** `cProd` do XML (2026-08-18) — presente só quando o item nasceu do fluxo XML; alimenta o
   *  aprendizado de `produto_fornecedor` na confirmação. */
  codigoFornecedor?: string | null
}

interface ParcelaLinha {
  numeroDuplicata: string
  dataVencimentoTexto: string
  valorTexto: string
}

/** dd/mm/aaaa de hoje (relógio do navegador — não existe endpoint de "hora do servidor" no
 *  projeto; mesmo padrão de qualquer outro default de data na aplicação). */
function hojeBr(): string {
  const d = new Date()
  const dd = String(d.getDate()).padStart(2, '0')
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  return `${dd}/${mm}/${d.getFullYear()}`
}

const ROTULO_MODO: Record<ModoEntrada, string> = {
  PLANILHA: 'Planilha Excel',
  INDIVIDUAL: 'Individual',
  XML: 'XML',
}

function variacaoTexto(p: { variacaoCor: string | null; variacaoTamanho: string | null }): string | null {
  if (!p.variacaoCor && !p.variacaoTamanho) return null
  return [p.variacaoCor, p.variacaoTamanho].filter(Boolean).join(' · ')
}

/** Uma linha da planilha que não casou sozinha — produto não encontrado (pesquisar/cadastrar)
 *  ou produto encontrado mas cor/tamanho não bateram (resolve direto na linha, sem modal). */
function LinhaPendentePlanilha({
  linha,
  permiteQtdDecimal,
  onResolvido,
  onIgnorar,
  onPesquisar,
  onCadastrar,
}: {
  linha: ItemPlanilhaPreviewResponse
  permiteQtdDecimal: boolean
  onResolvido: (v: VariacaoProduto) => void
  onIgnorar: () => void
  onPesquisar: () => void
  onCadastrar: () => void
}) {
  const queryClient = useQueryClient()
  const temProduto = linha.idProdutoEncontrado != null
  const { data: cores } = useQuery({ queryKey: ['cores'], queryFn: listarCores, enabled: temProduto })
  const { data: grade } = useQuery({
    queryKey: ['grade', linha.idGradeEncontrada],
    queryFn: () => buscarGrade(linha.idGradeEncontrada!),
    enabled: temProduto && linha.idGradeEncontrada != null,
  })
  const [idCor, setIdCor] = useState<number | ''>('')
  const [idTamanho, setIdTamanho] = useState<number | ''>('')
  const [novaCorNome, setNovaCorNome] = useState('')
  const [erro, setErro] = useState('')

  // Pré-seleciona o palpite (`linha.cor`/`linha.tamanho`) nos selects QUANDO ele bate com uma
  // opção que já existe no cadastro — o operador ainda vê o select, ainda pode trocar, e nada é
  // gravado até clicar "Confirmar" (mesma exigência de sempre: nunca resolve sozinho). Sem isso,
  // o sistema já sabia o tamanho (mostrado na coluna Cor/Tamanho) mas obrigava reescolher a
  // mesma coisa do zero — pedido do dono do produto, 2026-08-19: "voce ja identificou o
  // tamanho, pq esta pedindo pra cadastrar o tamanho". Não afeta a Planilha na prática: lá, uma
  // pendência só chega em `temProduto` quando cor/tamanho NÃO bateram com confiança — se
  // batessem, já teria resolvido sozinha antes de virar pendência.
  useEffect(() => {
    if (idCor === '' && linha.cor && cores) {
      const bate = cores.find((c) => c.descricao.toUpperCase() === linha.cor!.toUpperCase())
      if (bate) setIdCor(bate.idCor)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cores, linha.cor])

  useEffect(() => {
    if (idTamanho === '' && linha.tamanho && grade) {
      const bate = grade.tamanhos.find((t) => t.descricao.toUpperCase() === linha.tamanho!.toUpperCase())
      if (bate) setIdTamanho(bate.idTamanho)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [grade, linha.tamanho])

  const criarNovaCor = useMutation({
    mutationFn: criarCor,
    onSuccess: (cor) => {
      queryClient.invalidateQueries({ queryKey: ['cores'] })
      setIdCor(cor.idCor)
      setNovaCorNome('')
    },
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível criar a cor.'),
  })

  const confirmar = useMutation({
    mutationFn: () =>
      criarVariacao(linha.idProdutoEncontrado!, {
        idCor: idCor === '' ? null : idCor,
        idTamanho: idTamanho === '' ? null : idTamanho,
        ean: linha.codigoBarrasFabricante,
      }),
    onSuccess: (v) => onResolvido(v),
    onError: (e: unknown) => setErro(e instanceof ApiError ? e.message : 'Não foi possível vincular esta linha.'),
  })

  return (
    <tr>
      <td className="mono">{linha.numeroLinha}</td>
      <td>
        {linha.nomeProduto ?? '—'}
        {linha.marca ? ` · ${linha.marca}` : ''}
      </td>
      <td>{[linha.cor, linha.tamanho].filter(Boolean).join(' · ') || '—'}</td>
      <td className="mono" style={{ textAlign: 'right' }}>
        {formatarQuantidade(linha.qtd ?? 0, permiteQtdDecimal)}
      </td>
      <td>
        <p className="erro-campo" style={{ margin: 0 }}>
          {linha.motivoPendencia}
        </p>
        {erro && <p className="erro-campo">{erro}</p>}
        {temProduto ? (
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 6 }}>
            <div>
              <label style={{ fontSize: 12 }}>Cor</label>
              <select value={idCor} onChange={(e) => setIdCor(e.target.value === '' ? '' : Number(e.target.value))}>
                <option value="">Selecione…</option>
                {(cores ?? []).map((c) => (
                  <option key={c.idCor} value={c.idCor}>
                    {c.descricao}
                  </option>
                ))}
              </select>
            </div>
            <div className="linha-com-botao">
              <input
                placeholder="+ Nova cor…"
                value={novaCorNome}
                onChange={(e) => setNovaCorNome(maiusculas(e.target.value))}
                style={{ width: 100 }}
              />
              <button
                type="button"
                className="btn ghost"
                disabled={!novaCorNome.trim() || criarNovaCor.isPending}
                onClick={() => criarNovaCor.mutate(novaCorNome.trim())}
              >
                ＋
              </button>
            </div>
            <div>
              <label style={{ fontSize: 12 }}>Tamanho</label>
              <select value={idTamanho} onChange={(e) => setIdTamanho(e.target.value === '' ? '' : Number(e.target.value))}>
                <option value="">Selecione…</option>
                {(grade?.tamanhos ?? []).map((t) => (
                  <option key={t.idTamanho} value={t.idTamanho}>
                    {t.descricao}
                  </option>
                ))}
              </select>
            </div>
            <button type="button" className="btn" disabled={idCor === '' || idTamanho === '' || confirmar.isPending} onClick={() => confirmar.mutate()}>
              {confirmar.isPending ? 'Vinculando…' : 'Confirmar'}
            </button>
            <button type="button" className="btn ghost" onClick={onIgnorar}>
              Ignorar linha
            </button>
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 8, marginTop: 6, flexWrap: 'wrap' }}>
            <button type="button" className="btn ghost" onClick={onPesquisar}>
              <IconeLupa /> Pesquisar
            </button>
            <button type="button" className="btn ghost" onClick={onCadastrar}>
              ＋ Cadastrar
            </button>
            <button type="button" className="btn ghost" onClick={onIgnorar}>
              Ignorar linha
            </button>
          </div>
        )}
      </td>
    </tr>
  )
}

/** Entrada de Produtos por Compra (docs/telas/entrada-mercadoria.md) — 3 fluxos convergindo na
 *  mesma confirmação: Planilha Excel e Individual já funcionam; XML fica pra depois. */
export default function EntradaMercadoriaForm() {
  const navigate = useNavigate()
  const { data: eu } = useEu()

  // null = ainda não escolhido — a tela abre com um popup obrigatório perguntando o fluxo
  // antes de mostrar qualquer campo (pedido do dono do produto, 2026-08-12).
  const [modo, setModo] = useState<ModoEntrada | null>(null)

  const { data: cfgQtdDecimal } = useQuery({ queryKey: ['permite-qtd-decimal'], queryFn: buscarPermiteQtdDecimal })
  const permiteQtdDecimal = cfgQtdDecimal?.cfgPermiteQtdDecimal ?? true
  const { data: cfgRateia } = useQuery({ queryKey: ['rateia-frete-entrada'], queryFn: buscarRateiaFreteEntrada })
  const rateiaFrete = cfgRateia?.cfgRateiaFreteEntrada ?? false

  const [buscaFornecedor, setBuscaFornecedor] = useState('')
  const [fornecedorEscolhido, setFornecedorEscolhido] = useState<FornecedorOpcaoEmissao | null>(null)
  const [modalFornecedorAberto, setModalFornecedorAberto] = useState(false)
  const [notaFiscalTexto, setNotaFiscalTexto] = useState('')
  // Vem com a data de hoje por padrão — o usuário pode mudar pra trás, nunca pra frente
  // (pedido do dono do produto, 2026-08-12; ver `dataEntradaInvalida` abaixo).
  const [dataEntradaTexto, setDataEntradaTexto] = useState(hojeBr())
  const fornecedorInputRef = useRef<HTMLInputElement>(null)
  const primeiraDuplicataRef = useRef<HTMLInputElement>(null)
  const [numeroParcelasTexto, setNumeroParcelasTexto] = useState('')
  /** Estrutura de 3 abas (1. Dados Gerais / 2. Produtos / 3. Financeiro) — originalmente só do
   *  fluxo Planilha (2026-08-12), estendida ao Individual (2026-08-15) e ao XML (2026-08-18) —
   *  os 3 fluxos convergem na mesma confirmação e compartilham a mesma organização. */
  const temAbas = modo === 'PLANILHA' || modo === 'INDIVIDUAL' || modo === 'XML'
  const [aba, setAba] = useState<'GERAL' | 'PRODUTOS' | 'FINANCEIRO'>('GERAL')
  /** Sub-abas da aba "2. Produtos" do Planilha (2026-08-13): "Localizados" (itens já lançados) e
   *  "Não Localizados" (pendências da planilha) — cada uma com título e totalização fixos, só a
   *  grid de dados rola (`grid-altura-fixa`). Resolver uma pendência já move a linha pra
   *  "Localizados" sozinho (o dado sai de `pendentes` e entra em `itens`); não força a troca de
   *  sub-aba, pra não atrapalhar quem está resolvendo várias pendências em sequência. O
   *  Individual não tem pendências (não importa planilha), então não usa esta sub-aba. */
  const [abaProdutos, setAbaProdutos] = useState<'LOCALIZADOS' | 'NAO_LOCALIZADOS'>('LOCALIZADOS')

  const { data: empresasPermitidas } = useQuery({ queryKey: ['empresas-permitidas'], queryFn: listarEmpresasPermitidas })
  const [idEmpresaEscolhida, setIdEmpresaEscolhida] = useState<number | ''>('')
  useEffect(() => {
    if (idEmpresaEscolhida !== '' || !empresasPermitidas || empresasPermitidas.length === 0) return
    const padrao = eu?.empresa?.idEmpresa
    const existe = padrao != null && empresasPermitidas.some((e) => e.idEmpresa === padrao)
    setIdEmpresaEscolhida(existe ? (padrao as number) : empresasPermitidas[0].idEmpresa)
  }, [empresasPermitidas, eu, idEmpresaEscolhida])

  // Foco automático no Fornecedor: assim que um fluxo é escolhido, e de novo toda vez que o
  // usuário volta pra aba "1. Dados Gerais" (pedido do dono do produto, 2026-08-12; estendido
  // ao Individual em 2026-08-15 junto com a mesma estrutura de abas).
  useEffect(() => {
    if (modo === null) return
    if (temAbas && aba !== 'GERAL') return
    if (fornecedorEscolhido) return
    fornecedorInputRef.current?.focus()
  }, [modo, temAbas, aba, fornecedorEscolhido])

  const [itens, setItens] = useState<ItemLinha[]>([])
  const [valorRateioTexto, setValorRateioTexto] = useState('')
  const [parcelas, setParcelas] = useState<ParcelaLinha[]>([])

  const [mostrarPesquisa, setMostrarPesquisa] = useState(false)
  /** Popup de pesquisa (Nome/Marca/Referência) da aba "2. Produtos" do fluxo Individual
   *  (2026-08-15) — distinto de `mostrarPesquisa`, que resolve pendências do fluxo Planilha. */
  const [mostrarPesquisaEntrada, setMostrarPesquisaEntrada] = useState(false)
  /** Preenchido só quando "＋ Cadastrar Produto" acaba de criar um produto COM grade
   *  (2026-08-16) — pula a busca do popup de pesquisa e já abre direto no passo de Cor +
   *  quantidade por tamanho pra esse produto recém-criado. */
  const [produtoInicialPesquisa, setProdutoInicialPesquisa] = useState<ProdutoOpcaoEntrada | undefined>(undefined)
  const [modalProdutoAberto, setModalProdutoAberto] = useState(false)
  const [toast, setToast] = useState<{ texto: string; tipo: 'erro' | 'sucesso' } | null>(null)
  const [entradaGravada, setEntradaGravada] = useState<EntradaEfetivadaResponse | null>(null)

  const [arquivoPlanilha, setArquivoPlanilha] = useState<File | null>(null)
  const [pendentes, setPendentes] = useState<ItemPlanilhaPreviewResponse[]>([])
  const [pendenteEmResolucao, setPendenteEmResolucao] = useState<number | null>(null)
  const inputArquivoRef = useRef<HTMLInputElement>(null)

  /** Fluxo XML (Fase 3, 2026-08-18) — `xmlBruto`/`chaveNfeXml`/`serieNotaXml` seguem pro
   *  payload de confirmação sem alteração nenhuma (auditoria P3, idempotência P2).
   *  `fornecedorXmlSemCadastro` só fica preenchido quando o CNPJ do emitente não bate com
   *  nenhum fornecedor já cadastrado — oferece cadastro rápido pré-preenchido com o que o XML
   *  trouxe. `chaveJaImportada` bloqueia a confirmação cedo, antes mesmo de tentar (o servidor
   *  também valida de novo, defesa em profundidade). */
  const [arquivoXml, setArquivoXml] = useState<File | null>(null)
  const inputArquivoXmlRef = useRef<HTMLInputElement>(null)
  const [xmlBruto, setXmlBruto] = useState<string | null>(null)
  const [chaveNfeXml, setChaveNfeXml] = useState<string | null>(null)
  const [serieNotaXml, setSerieNotaXml] = useState<number | null>(null)
  const [chaveJaImportada, setChaveJaImportada] = useState(false)
  const [fornecedorXmlSemCadastro, setFornecedorXmlSemCadastro] = useState<FornecedorXmlPreview | null>(null)
  /** O XML já trouxe as duplicatas prontas (`cobr/dup`) — nesse caso "Nº de Parcelas" nem
   *  aparece (2026-08-19, pedido do dono do produto: "não preciso definir... já está descrito
   *  no XML"). Só quando o XML NÃO tem parcelamento descrito é que a tela volta a oferecer o
   *  campo, pro operador escolher se quer dividir mesmo assim. */
  const [xmlTemDuplicatas, setXmlTemDuplicatas] = useState(false)

  const { data: fornecedores } = useQuery({
    queryKey: ['etiqueta-emissao-fornecedores', buscaFornecedor],
    queryFn: () => buscarFornecedoresEmissao(buscaFornecedor),
    enabled: buscaFornecedor.trim().length > 0 && !fornecedorEscolhido,
  })

  const lancarProduto = (idVariacao: number, descricao: string, variacao: string | null, qtd: number = 1) => {
    setItens((atual) => {
      const existente = atual.find((i) => i.idVariacao === idVariacao)
      if (existente) {
        return atual.map((i) =>
          i.idVariacao === idVariacao
            ? { ...i, qtdTexto: formatarQuantidade(desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) + qtd, permiteQtdDecimal) }
            : i,
        )
      }
      return [...atual, { idVariacao, descricao, variacao, qtdTexto: formatarQuantidade(qtd, permiteQtdDecimal), custoTexto: '', precoVendaTexto: '' }]
    })
  }

  /** Lança um item já com o custo definido (vindo da planilha ou do XML, que trazem custo por
   *  linha — diferente do fluxo Individual, onde o custo é digitado depois). `codigoFornecedor`
   *  (2026-08-18) só vem preenchido no fluxo XML — segue até a confirmação pra alimentar o
   *  aprendizado de `produto_fornecedor`. */
  const lancarItemComCusto = (
    idVariacao: number,
    descricao: string,
    variacao: string | null,
    qtd: number,
    custo: number,
    codigoFornecedor?: string | null,
  ) => {
    setItens((atual) => {
      const existente = atual.find((i) => i.idVariacao === idVariacao)
      if (existente) {
        return atual.map((i) =>
          i.idVariacao === idVariacao
            ? {
                ...i,
                qtdTexto: formatarQuantidade(desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) + qtd, permiteQtdDecimal),
                custoTexto: formatarMoeda(custo),
                codigoFornecedor: codigoFornecedor ?? i.codigoFornecedor,
              }
            : i,
        )
      }
      return [
        ...atual,
        {
          idVariacao,
          descricao,
          variacao,
          qtdTexto: formatarQuantidade(qtd, permiteQtdDecimal),
          custoTexto: formatarMoeda(custo),
          precoVendaTexto: '',
          codigoFornecedor,
        },
      ]
    })
  }

  /** Lança um lote de variações já resolvidas (achadas ou criadas) vindo do popup de pesquisa
   *  com grade de tamanhos (2026-08-15) — todas compartilham o mesmo custo/preço de venda
   *  digitados uma única vez para o lote inteiro. */
  const lancarItensComCustoEPreco = (novosItens: { idVariacao: number; descricao: string; variacao: string | null; qtd: number }[], custo: number, precoVenda: number) => {
    setItens((atual) => {
      let resultado = atual
      for (const novo of novosItens) {
        const existente = resultado.find((i) => i.idVariacao === novo.idVariacao)
        resultado = existente
          ? resultado.map((i) =>
              i.idVariacao === novo.idVariacao
                ? {
                    ...i,
                    qtdTexto: formatarQuantidade(desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) + novo.qtd, permiteQtdDecimal),
                    custoTexto: formatarMoeda(custo),
                    precoVendaTexto: formatarMoeda(precoVenda),
                  }
                : i,
            )
          : [
              ...resultado,
              {
                idVariacao: novo.idVariacao,
                descricao: novo.descricao,
                variacao: novo.variacao,
                qtdTexto: formatarQuantidade(novo.qtd, permiteQtdDecimal),
                custoTexto: formatarMoeda(custo),
                precoVendaTexto: formatarMoeda(precoVenda),
              },
            ]
      }
      return resultado
    })
  }

  const removerPendente = (numeroLinha: number) => setPendentes((atual) => atual.filter((p) => p.numeroLinha !== numeroLinha))

  const aoSelecionarNaPesquisa = (produto: PdvProduto) => {
    setMostrarPesquisa(false)
    if (pendenteEmResolucao != null) {
      const linha = pendentes.find((p) => p.numeroLinha === pendenteEmResolucao)
      if (linha) {
        lancarItemComCusto(
          produto.idVariacao,
          produto.descricaoProduto,
          variacaoTexto(produto),
          linha.qtd ?? 1,
          linha.custoUnitario ?? 0,
          linha.codigoFornecedor,
        )
        removerPendente(pendenteEmResolucao)
      }
      setPendenteEmResolucao(null)
      return
    }
    lancarProduto(produto.idVariacao, produto.descricaoProduto, variacaoTexto(produto))
  }

  /** Popup Nome/Marca/Referência + grade de tamanhos da aba "2. Produtos" do fluxo Individual
   *  (2026-08-15) — o popup já resolve/cria a(s) variação(ões) e devolve prontas, com custo/
   *  preço de venda do lote inteiro (um custo digitado vale pra todos os tamanhos lançados). */
  const aoAdicionarDaPesquisaEntrada = (itens: ItemResolvidoEntrada[], custo: number, precoVenda: number) => {
    setMostrarPesquisaEntrada(false)
    setProdutoInicialPesquisa(undefined)
    lancarItensComCustoEPreco(itens, custo, precoVenda)
  }

  const aoCriarProduto = (variacao: VariacaoProduto) => {
    setModalProdutoAberto(false)
    if (pendenteEmResolucao != null) {
      const linha = pendentes.find((p) => p.numeroLinha === pendenteEmResolucao)
      if (linha) {
        lancarItemComCusto(
          variacao.idVariacao,
          variacao.descricao,
          variacaoTexto(variacao),
          linha.qtd ?? 1,
          linha.custoUnitario ?? 0,
          linha.codigoFornecedor,
        )
        removerPendente(pendenteEmResolucao)
      }
      setPendenteEmResolucao(null)
      setToast({ texto: 'Produto cadastrado e adicionado à entrada.', tipo: 'sucesso' })
      return
    }
    lancarProduto(variacao.idVariacao, variacao.descricao, variacaoTexto(variacao))
    setToast({ texto: 'Produto cadastrado e adicionado à entrada.', tipo: 'sucesso' })
  }

  const normalizarNomeProduto = (nome: string) => nome.trim().toUpperCase().replace(/\s+/g, ' ')

  /** "＋ Cadastrar Produto" criou um produto COM grade, sem variação nenhuma ainda. Dois
   *  contextos bem diferentes usam este mesmo callback:
   *  - Individual (2026-08-16, `pendenteEmResolucao == null`): cor/tamanho/quantidade não fazem
   *    sentido pedir naquele modal — é decidido no popup de pesquisa, que já abre direto no
   *    passo 2 pra esse produto.
   *  - Pendência do XML (2026-08-19, `pendenteEmResolucao != null` e `modo === 'XML'`, único
   *    fluxo que hoje chega aqui com pendência): a linha que estava sendo resolvida — e
   *    QUALQUER OUTRA pendência ainda sem produto cujo nome (já sem cor/tamanho, ver
   *    `EntradaXmlService`) bata com o produto recém-criado — passa a ter `idProdutoEncontrado`
   *    preenchido, sem precisar cadastrar de novo. A própria grid "Não Localizados" já sabe
   *    pedir cor/tamanho quando isso acontece (`LinhaPendentePlanilha`, branch `temProduto`) —
   *    pedido do dono do produto: "verificar nos outros que ainda estao indefinidos, se nao é o
   *    mesmo produto, verifica isso pelo nome, ai so pede pra definir, cor e tamanho". */
  const aoCriarProdutoComGrade = (produto: Produto) => {
    setModalProdutoAberto(false)

    if (pendenteEmResolucao != null) {
      const nomeAlvo = normalizarNomeProduto(produto.descricao)
      let qtdAtualizadas = 0
      setPendentes((atual) =>
        atual.map((p) => {
          // A linha que disparou o cadastro sempre entra, mesmo que o operador tenha editado a
          // descrição dentro do modal (o nome já não bate mais com `p.nomeProduto`); as demais
          // só entram por nome igual (produto que ela mesma não cadastrou, mas é a mesma peça).
          const éLinhaAtual = p.numeroLinha === pendenteEmResolucao
          const mesmoNome = p.idProdutoEncontrado == null && p.nomeProduto != null && normalizarNomeProduto(p.nomeProduto) === nomeAlvo
          if (éLinhaAtual || mesmoNome) {
            qtdAtualizadas++
            return { ...p, idProdutoEncontrado: produto.idProduto, idGradeEncontrada: produto.idGrade }
          }
          return p
        }),
      )
      setPendenteEmResolucao(null)
      setToast({
        texto:
          qtdAtualizadas > 1
            ? `Produto cadastrado — defina cor e tamanho nas ${qtdAtualizadas} linhas encontradas com este nome.`
            : 'Produto cadastrado — defina a cor e o tamanho abaixo.',
        tipo: 'sucesso',
      })
      return
    }

    setProdutoInicialPesquisa({
      idProduto: produto.idProduto,
      descricao: produto.descricao,
      marca: produto.marca,
      referencia: produto.referencia,
      idGrade: produto.idGrade,
      precoCusto: produto.precoCusto,
      percentualVenda: produto.percentualVenda,
    })
    setMostrarPesquisaEntrada(true)
    setToast({ texto: 'Produto cadastrado — escolha a cor e as quantidades.', tipo: 'sucesso' })
  }

  const removerItem = (idVariacao: number) => setItens((atual) => atual.filter((i) => i.idVariacao !== idVariacao))

  const alterarQtd = (idVariacao: number, texto: string) =>
    setItens((atual) => atual.map((i) => (i.idVariacao === idVariacao ? { ...i, qtdTexto: mascararQuantidade(texto, permiteQtdDecimal) } : i)))

  const aoSairQtd = (idVariacao: number) =>
    setItens((atual) => atual.map((i) => (i.idVariacao === idVariacao ? { ...i, qtdTexto: completarQuantidade(i.qtdTexto, permiteQtdDecimal) } : i)))

  const alterarCusto = (idVariacao: number, texto: string) =>
    setItens((atual) => atual.map((i) => (i.idVariacao === idVariacao ? { ...i, custoTexto: mascararMoeda(texto) } : i)))

  const aoSairCusto = (idVariacao: number) =>
    setItens((atual) => atual.map((i) => (i.idVariacao === idVariacao ? { ...i, custoTexto: completarMoeda(i.custoTexto) } : i)))

  const removerParcela = (indice: number) => setParcelas((atual) => atual.filter((_, i) => i !== indice))
  const alterarParcela = (indice: number, campo: keyof ParcelaLinha, valor: string) =>
    setParcelas((atual) => atual.map((p, i) => (i === indice ? { ...p, [campo]: valor } : p)))

  const qtdTotal = itens.reduce((soma, i) => soma + desmascararQuantidade(i.qtdTexto, permiteQtdDecimal), 0)
  const valorTotal = itens.reduce(
    (soma, i) => soma + desmascararQuantidade(i.qtdTexto, permiteQtdDecimal) * desmascararMoeda(i.custoTexto || '0'),
    0,
  )

  /** Assim que o Nº de Parcelas (aba "Dados Gerais"/Identificação) e o valor total dos produtos
   *  estiverem os dois prontos — nessa ordem ou na outra, tanto faz — e ainda não há nenhuma
   *  parcela lançada, já divide o total em N parcelas iguais e joga o valor de cada uma
   *  (2026-08-13, pedido do dono do produto): resto do arredondamento fecha na PRIMEIRA parcela
   *  (não na última — mudou de ideia em relação à 1ª versão desta função), Nº Duplicata/Data
   *  ficam em branco pro usuário preencher. Sem botão "＋ Adicionar Parcela": o Nº de Parcelas já
   *  é a única fonte da contagem. Não mexe em parcelas já existentes (o usuário pode ter
   *  editado). */
  useEffect(() => {
    const n = Number(numeroParcelasTexto)
    if (!n || n < 1 || n > 12 || parcelas.length > 0 || valorTotal <= 0) return
    const valorBase = Math.floor((valorTotal / n) * 100) / 100
    const novas: ParcelaLinha[] = Array.from({ length: n }, (_, i) => ({
      numeroDuplicata: '',
      dataVencimentoTexto: '',
      valorTexto: formatarMoeda(i === 0 ? Number((valorTotal - valorBase * (n - 1)).toFixed(2)) : valorBase),
    }))
    setParcelas(novas)
  }, [numeroParcelasTexto, valorTotal, parcelas.length])

  // Foco automático na 1ª duplicata ao entrar na aba "3. Financeiro" (pedido do dono do
  // produto, 2026-08-13; vale pros dois fluxos desde 2026-08-15) — as parcelas já chegam com
  // Nº/valor calculados (ver efeito acima), falta só a data/duplicata de cada uma, então o
  // cursor já pousa pronto pra digitar.
  useEffect(() => {
    if (!temAbas || aba !== 'FINANCEIRO') return
    primeiraDuplicataRef.current?.focus()
    // parcelas.length entra na dependência pra tentar de novo quando as linhas nascem depois
    // (efeito de auto-split roda em outro ciclo — na 1ª renderização da aba, a ref ainda não existe).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [temAbas, aba, parcelas.length])

  const processarPlanilha = useMutation({
    mutationFn: () => previewPlanilhaEntrada(arquivoPlanilha!),
    onSuccess: (linhas) => {
      const resolvidas = linhas.filter((l) => l.resolvido)
      const novasPendentes = linhas.filter((l) => !l.resolvido)
      resolvidas.forEach((l) => {
        lancarItemComCusto(
          l.idVariacao!,
          l.descricaoProduto ?? l.nomeProduto ?? '—',
          l.variacaoCor || l.variacaoTamanho ? [l.variacaoCor, l.variacaoTamanho].filter(Boolean).join(' · ') : null,
          l.qtd ?? 0,
          l.custoUnitario ?? 0,
        )
      })
      setPendentes((atual) => [...atual, ...novasPendentes])
      setAbaProdutos(novasPendentes.length > 0 ? 'NAO_LOCALIZADOS' : 'LOCALIZADOS')
      setArquivoPlanilha(null)
      if (inputArquivoRef.current) inputArquivoRef.current.value = ''
      setToast(
        novasPendentes.length === 0
          ? { texto: `${resolvidas.length} produto(s) processado(s) com sucesso.`, tipo: 'sucesso' }
          : { texto: `${resolvidas.length} resolvido(s), ${novasPendentes.length} pendente(s) — resolva abaixo antes de confirmar.`, tipo: 'erro' },
      )
    },
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível processar a planilha.', tipo: 'erro' }),
  })

  /** Fluxo XML (Fase 3, 2026-08-18) — mesmo shape de item que a Planilha (`resolvido`/
   *  pendência), então reaproveita a mesma lógica de separar Localizados/Não Localizados; a
   *  diferença fica só no que preenche além dos itens: fornecedor (auto-seleciona se achou pelo
   *  CNPJ, senão oferece cadastro rápido pré-preenchido), Nº Nota Fiscal, e as parcelas (vêm
   *  prontas do `cobr/dup` do XML, não do "Nº de Parcelas" dividido igualmente). */
  const processarXml = useMutation({
    mutationFn: () => previewXmlEntrada(arquivoXml!),
    onSuccess: (preview) => {
      setXmlBruto(preview.xmlBruto)
      setChaveNfeXml(preview.chaveNfe)
      setSerieNotaXml(preview.serieNota)
      setChaveJaImportada(preview.chaveJaImportada)
      if (preview.notaFiscal != null) setNotaFiscalTexto(String(preview.notaFiscal))
      if (preview.dataEmissao) setDataEntradaTexto(isoParaData(preview.dataEmissao))

      if (preview.fornecedor.idFornecedor != null) {
        setFornecedorEscolhido({ idFornecedor: preview.fornecedor.idFornecedor, razaoSocial: preview.fornecedor.razaoSocial ?? '' })
        setFornecedorXmlSemCadastro(null)
      } else {
        // CNPJ do emitente não bate com nenhum fornecedor cadastrado — já abre o cadastro
        // rápido preenchido com os dados do XML (2026-08-19, pedido do dono do produto: "já
        // trazer a tela de cadastro do fornecedor"), sem exigir um clique extra em "Cadastrar
        // agora". O operador ainda pode fechar e escolher um fornecedor existente na busca.
        setFornecedorXmlSemCadastro(preview.fornecedor)
        setModalFornecedorAberto(true)
      }

      // Empresa destinatária no XML — CNPJ do `dest` casado contra o cadastro de empresas do
      // tenant (2026-08-19); sem match, mantém o padrão de sempre (empresa da sessão) e o
      // operador escolhe manualmente no select, que continua disponível.
      if (preview.idEmpresaEncontrada != null) setIdEmpresaEscolhida(preview.idEmpresaEncontrada)

      setXmlTemDuplicatas(preview.duplicatas.length > 0)
      if (preview.duplicatas.length > 0) {
        setParcelas(
          preview.duplicatas.map((d) => ({
            numeroDuplicata: d.numeroDuplicata ?? '',
            dataVencimentoTexto: isoParaData(d.dataVencimento),
            valorTexto: formatarMoeda(d.valor),
          })),
        )
      }

      const resolvidas = preview.itens.filter((l) => l.resolvido)
      const novasPendentes = preview.itens.filter((l) => !l.resolvido)
      resolvidas.forEach((l) => {
        lancarItemComCusto(
          l.idVariacao!,
          l.descricaoProduto ?? l.nomeProduto ?? '—',
          l.variacaoCor || l.variacaoTamanho ? [l.variacaoCor, l.variacaoTamanho].filter(Boolean).join(' · ') : null,
          l.qtd ?? 0,
          l.custoUnitario ?? 0,
          l.codigoFornecedor,
        )
      })
      setPendentes((atual) => [...atual, ...novasPendentes])
      setAbaProdutos(novasPendentes.length > 0 ? 'NAO_LOCALIZADOS' : 'LOCALIZADOS')
      setArquivoXml(null)
      if (inputArquivoXmlRef.current) inputArquivoXmlRef.current.value = ''
      setToast(
        preview.chaveJaImportada
          ? { texto: 'Esta nota fiscal já foi importada antes — não é possível confirmar de novo.', tipo: 'erro' }
          : novasPendentes.length === 0
            ? { texto: `${resolvidas.length} produto(s) processado(s) com sucesso.`, tipo: 'sucesso' }
            : { texto: `${resolvidas.length} resolvido(s), ${novasPendentes.length} pendente(s) — resolva abaixo antes de confirmar.`, tipo: 'erro' },
      )
    },
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível processar o XML.', tipo: 'erro' }),
  })

  const baixarModelo = useMutation({
    mutationFn: baixarModeloPlanilhaEntrada,
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível baixar o modelo.', tipo: 'erro' }),
  })

  const algumCustoZerado = itens.some((i) => desmascararMoeda(i.custoTexto || '0') <= 0)
  const algumaParcelaIncompleta = parcelas.some((p) => !p.dataVencimentoTexto.trim() || desmascararMoeda(p.valorTexto || '0') <= 0)
  // "Pode mudar pra trás, nunca pra frente" (2026-08-12) — vale pros dois fluxos com abas
  // (Planilha e, desde 2026-08-15, Individual), onde o campo é exibido; comparação de string
  // "aaaa-mm-dd" já ordena cronologicamente.
  const dataEntradaIso = dataEntradaTexto.trim() ? dataParaIso(dataEntradaTexto) : null
  const dataEntradaInvalida = temAbas && dataEntradaIso != null && dataEntradaIso > dataParaIso(hojeBr())!

  /** Total das parcelas precisa bater com o total dos produtos lançados (2026-08-14, pedido do
   *  dono do produto) — comparado em centavos pra não sofrer de imprecisão de ponto flutuante.
   *  Só entra em jogo quando existe alguma parcela (Contas a Pagar continua opcional: nenhuma
   *  parcela lançada não bloqueia nada). Vale pros dois fluxos (Individual e Planilha), já que
   *  os dois compartilham a mesma seção de Contas a Pagar. */
  const valorParcelasTotal = parcelas.reduce((soma, p) => soma + desmascararMoeda(p.valorTexto || '0'), 0)
  const totalParcelasNaoBate = parcelas.length > 0 && Math.round(valorParcelasTotal * 100) !== Math.round(valorTotal * 100)

  /** Vencimento de nenhuma parcela pode ser anterior à Data da Entrada informada (2026-08-14,
   *  pedido do dono do produto) — vale pros dois fluxos com abas, onde esse campo existe. */
  const parcelaComVencimentoAntesDaEntrada =
    temAbas &&
    dataEntradaIso != null &&
    parcelas.some((p) => {
      const vencimentoIso = p.dataVencimentoTexto.trim() ? dataParaIso(p.dataVencimentoTexto) : null
      return vencimentoIso != null && vencimentoIso < dataEntradaIso
    })

  const podeConfirmar =
    Boolean(fornecedorEscolhido) &&
    itens.length > 0 &&
    pendentes.length === 0 &&
    !algumCustoZerado &&
    !algumaParcelaIncompleta &&
    !dataEntradaInvalida &&
    !totalParcelasNaoBate &&
    !parcelaComVencimentoAntesDaEntrada &&
    !chaveJaImportada

  const efetivar = useMutation({
    mutationFn: () =>
      efetivarEntrada({
        idFornecedor: fornecedorEscolhido!.idFornecedor,
        idEmpresa: idEmpresaEscolhida === '' ? null : idEmpresaEscolhida,
        notaFiscal: notaFiscalTexto.trim() ? Number(notaFiscalTexto.trim()) : null,
        // Os três fluxos com abas mostram/usam este campo (Individual e XML passaram a pedir/
        // preencher Data da Entrada em 2026-08-15/18, igual ao Planilha).
        dataMovimento: temAbas ? dataEntradaIso : null,
        chaveNfe: modo === 'XML' ? chaveNfeXml : null,
        serieNota: modo === 'XML' ? serieNotaXml : null,
        xmlBruto: modo === 'XML' ? xmlBruto : null,
        valorRateio: valorRateioTexto.trim() ? desmascararMoeda(valorRateioTexto) : null,
        itens: itens.map((i) => ({
          idVariacao: i.idVariacao,
          qtd: desmascararQuantidade(i.qtdTexto, permiteQtdDecimal),
          precoCusto: desmascararMoeda(i.custoTexto || '0'),
          precoVenda: i.precoVendaTexto ? desmascararMoeda(i.precoVendaTexto) : undefined,
          codigoFornecedor: i.codigoFornecedor,
        })),
        contasPagar:
          parcelas.length > 0
            ? parcelas.map((p) => ({
                numeroDuplicata: p.numeroDuplicata.trim() || null,
                dataVencimento: p.dataVencimentoTexto.split('/').reverse().join('-'),
                valor: desmascararMoeda(p.valorTexto || '0'),
              }))
            : null,
      }),
    onSuccess: (resultado) => {
      setToast({ texto: 'Entrada confirmada com sucesso.', tipo: 'sucesso' })
      setEntradaGravada(resultado)
    },
    onError: (e: unknown) => setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível confirmar a entrada.', tipo: 'erro' }),
  })

  /** {@code alturaFixa} liga `grid-altura-fixa` (só as linhas rolam) e tira o rodapé de total de
   *  DENTRO da tabela — usado pela sub-aba "Localizados" do fluxo Planilha (2026-08-13), onde o
   *  total mora no rodapé fixo da TELA (ver `<div className="lista-rodape">` mais abaixo), não
   *  duplicado dentro da grid. O Individual continua com a grid soltando a página inteira e o
   *  total no rodapé da própria tabela, como sempre. */
  const renderGradeItens = (alturaFixa: boolean) => (
    <>
      {itens.length === 0 ? (
        <p className="muted" style={{ marginTop: 12 }}>
          Nenhum produto localizado ainda.
        </p>
      ) : (
        <div className={`table-wrap${alturaFixa ? ' grid-altura-fixa' : ''}`} style={{ marginTop: 12 }}>
          <table className="table table-compacta">
            <thead>
              <tr>
                <th>Descrição</th>
                <th>Variação</th>
                <th style={{ textAlign: 'right' }}>Quantidade</th>
                <th style={{ textAlign: 'right' }}>Preço de Custo</th>
                <th style={{ textAlign: 'right' }}>Valor Total</th>
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {itens.map((item) => {
                const qtdNumero = desmascararQuantidade(item.qtdTexto, permiteQtdDecimal)
                const custoNumero = desmascararMoeda(item.custoTexto || '0')
                return (
                  <tr key={item.idVariacao}>
                    <td>{item.descricao}</td>
                    <td>{item.variacao ?? '—'}</td>
                    <td style={{ textAlign: 'right' }}>
                      <input
                        className="mono"
                        style={{ width: 100, textAlign: 'right' }}
                        inputMode={permiteQtdDecimal ? undefined : 'numeric'}
                        value={item.qtdTexto}
                        onChange={(e) => alterarQtd(item.idVariacao, e.target.value)}
                        onBlur={() => aoSairQtd(item.idVariacao)}
                        onFocus={(e) => e.target.select()}
                      />
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <input
                        className="mono"
                        style={{ width: 110, textAlign: 'right' }}
                        inputMode="decimal"
                        value={item.custoTexto}
                        onChange={(e) => alterarCusto(item.idVariacao, e.target.value)}
                        onBlur={() => aoSairCusto(item.idVariacao)}
                        onFocus={(e) => e.target.select()}
                      />
                      {custoNumero <= 0 && <p className="erro-campo">Informe o custo.</p>}
                    </td>
                    <td className="mono" style={{ textAlign: 'right' }}>
                      {moeda(qtdNumero * custoNumero)}
                    </td>
                    <td className="acoes-cell">
                      <button
                        type="button"
                        className="acao-icone acao-excluir"
                        onClick={() => removerItem(item.idVariacao)}
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
            {!alturaFixa && (
              <tfoot>
                <tr>
                  <td>
                    <strong>Total</strong>
                  </td>
                  <td />
                  <td className="mono" style={{ textAlign: 'right' }}>
                    <strong>{formatarQuantidade(qtdTotal, permiteQtdDecimal)}</strong>
                  </td>
                  <td />
                  <td className="mono" style={{ textAlign: 'right' }}>
                    <strong>{moeda(valorTotal)}</strong>
                  </td>
                  <td />
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      )}
    </>
  )

  /** Fornecedor + empresa + nota fiscal + Data da Entrada + nº de parcelas (+ rateio, quando
   *  ligado) — a aba "1. Dados Gerais" dos três fluxos com abas. No XML (2026-08-19), Nº Nota
   *  Fiscal e Data da Entrada viram texto (o XML já traz os dois, sem necessidade de digitar
   *  nem de editar) e Nº de Parcelas some quando o XML já trouxe as duplicatas prontas
   *  (`cobr/dup`) — só reaparece se a nota não tiver parcelamento descrito, pra deixar o
   *  operador escolher se quer dividir mesmo assim. */
  const renderCamposIdentificacao = () => (
    <div className="form-grid" style={{ marginTop: 10 }}>
      <div className="col-6">
        <label htmlFor="entrada-fornecedor">Fornecedor *</label>
        {fornecedorEscolhido ? (
          <p className="muted">
            <strong>{fornecedorEscolhido.razaoSocial}</strong>{' '}
            <button
              type="button"
              className="btn ghost"
              onClick={() => {
                setFornecedorEscolhido(null)
                setBuscaFornecedor('')
              }}
            >
              Trocar
            </button>
          </p>
        ) : (
          <>
            <div className="linha-com-botao">
              <input
                id="entrada-fornecedor"
                ref={fornecedorInputRef}
                placeholder="Buscar fornecedor…"
                value={buscaFornecedor}
                onChange={(e) => setBuscaFornecedor(e.target.value.toUpperCase())}
              />
              <button type="button" className="btn ghost" onClick={() => setModalFornecedorAberto(true)}>
                ＋ Novo
              </button>
            </div>
            {fornecedores && fornecedores.length > 0 && (
              <div className="table-wrap" style={{ maxHeight: 140, marginTop: 8 }}>
                <table className="table table-compacta">
                  <tbody>
                    {fornecedores.map((f) => (
                      <tr key={f.idFornecedor} tabIndex={0} onClick={() => setFornecedorEscolhido(f)}>
                        <td>{f.razaoSocial}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </div>
      {empresasPermitidas && empresasPermitidas.length > 1 && (
        <div className="col-3">
          <label htmlFor="entrada-empresa">Empresa *</label>
          <select
            id="entrada-empresa"
            value={idEmpresaEscolhida}
            onChange={(e) => setIdEmpresaEscolhida(e.target.value === '' ? '' : Number(e.target.value))}
          >
            {empresasPermitidas.map((emp: Empresa) => (
              <option key={emp.idEmpresa} value={emp.idEmpresa}>
                {emp.nomeFantasia ?? emp.razaoSocial}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="col-3">
        <label htmlFor="entrada-nota">Nº Nota Fiscal</label>
        {modo === 'XML' ? (
          <p className="muted" style={{ marginTop: 8 }}>
            {notaFiscalTexto || '—'}
          </p>
        ) : (
          <input
            id="entrada-nota"
            inputMode="numeric"
            value={notaFiscalTexto}
            onChange={(e) => setNotaFiscalTexto(e.target.value.replace(/\D/g, ''))}
            placeholder="Opcional"
          />
        )}
      </div>
      <div className="col-3">
        <label htmlFor="entrada-data-entrada">Data da Entrada</label>
        {modo === 'XML' ? (
          <p className="muted" style={{ marginTop: 8 }}>
            {dataEntradaTexto || '—'}
          </p>
        ) : (
          <input
            id="entrada-data-entrada"
            placeholder="dd/mm/aaaa"
            value={dataEntradaTexto}
            onChange={(e) => setDataEntradaTexto(mascararData(e.target.value))}
            onFocus={(e) => e.target.select()}
          />
        )}
        {dataEntradaInvalida && <p className="erro-campo">Não é possível informar uma data futura.</p>}
      </div>
      {!(modo === 'XML' && xmlTemDuplicatas) && (
        <div className="col-3">
          <label htmlFor="entrada-parcelas">Nº de Parcelas</label>
          <input
            id="entrada-parcelas"
            inputMode="numeric"
            value={numeroParcelasTexto}
            onChange={(e) => setNumeroParcelasTexto(e.target.value.replace(/\D/g, '').slice(0, 2))}
            placeholder="Opcional"
          />
        </div>
      )}
      {rateiaFrete && (
        <div className="col-3">
          <label htmlFor="entrada-rateio">Frete/Acréscimo a Ratear</label>
          <input
            id="entrada-rateio"
            inputMode="decimal"
            value={valorRateioTexto}
            onChange={(e) => setValorRateioTexto(mascararMoeda(e.target.value))}
            onBlur={() => setValorRateioTexto((v) => (v ? completarMoeda(v) : v))}
            placeholder="Opcional"
          />
        </div>
      )}
    </div>
  )

  /** Conteúdo de "Contas a Pagar" — comum ao Individual (seção normal) e à aba "Financeiro" do
   *  Planilha. "Nº Parcela" é só a posição na lista (1, 2, 3…), nunca editável — distinto de
   *  "Nº Duplicata" (livre, o número que o fornecedor deu à parcela). Sem botão "＋ Adicionar
   *  Parcela" (2026-08-13, removido a pedido do dono do produto): o Nº de Parcelas já informado
   *  em "Dados Gerais"/Identificação é a única fonte da contagem de linhas (ver o efeito que
   *  gera `parcelas` a partir dele, com valor já dividido). */
  const renderContasPagar = () => (
    <>
      <p className="muted" style={{ marginTop: 4 }}>
        Lance as parcelas/duplicatas desta compra, se quiser já gerar as contas a pagar.
      </p>

      {parcelas.length > 0 && (
        <div className="table-wrap" style={{ marginTop: 10 }}>
          <table className="table table-compacta">
            <thead>
              <tr>
                <th style={{ textAlign: 'right' }}>Nº Parcela</th>
                <th>Nº Duplicata</th>
                <th>Data Vencimento</th>
                <th style={{ textAlign: 'right' }}>Valor a Pagar</th>
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {parcelas.map((p, i) => {
                const vencimentoIso = p.dataVencimentoTexto.trim() ? dataParaIso(p.dataVencimentoTexto) : null
                const vencimentoAntesDaEntrada =
                  temAbas && dataEntradaIso != null && vencimentoIso != null && vencimentoIso < dataEntradaIso
                return (
                <tr key={i}>
                  <td className="mono" style={{ textAlign: 'right' }}>
                    {i + 1}
                  </td>
                  <td>
                    <input
                      ref={i === 0 ? primeiraDuplicataRef : undefined}
                      value={p.numeroDuplicata}
                      onChange={(e) => alterarParcela(i, 'numeroDuplicata', maiusculas(e.target.value))}
                      placeholder="Opcional"
                    />
                  </td>
                  <td>
                    <input
                      placeholder="dd/mm/aaaa"
                      value={p.dataVencimentoTexto}
                      onChange={(e) => alterarParcela(i, 'dataVencimentoTexto', mascararData(e.target.value))}
                      onFocus={(e) => e.target.select()}
                    />
                    {vencimentoAntesDaEntrada && <p className="erro-campo">Não pode ser antes da Data da Entrada.</p>}
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <input
                      className="mono"
                      style={{ textAlign: 'right' }}
                      inputMode="decimal"
                      value={p.valorTexto}
                      onChange={(e) => alterarParcela(i, 'valorTexto', mascararMoeda(e.target.value))}
                      onBlur={() => alterarParcela(i, 'valorTexto', completarMoeda(p.valorTexto))}
                      onFocus={(e) => e.target.select()}
                    />
                  </td>
                  <td className="acoes-cell">
                    <button type="button" className="acao-icone acao-excluir" onClick={() => removerParcela(i)} aria-label="Remover parcela" title="Remover">
                      <IconeExcluir />
                    </button>
                  </td>
                </tr>
                )
              })}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={3}>
                  <strong>Total das parcelas</strong>
                </td>
                <td className="mono" style={{ textAlign: 'right' }}>
                  <strong>{moeda(valorParcelasTotal)}</strong>
                </td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>
      )}

      {totalParcelasNaoBate && (
        <p className="erro-campo">
          O total das parcelas ({moeda(valorParcelasTotal)}) precisa bater com o total dos produtos lançados ({moeda(valorTotal)}).
        </p>
      )}
    </>
  )

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>{modo ? `Entrada de Produtos - Por ${ROTULO_MODO[modo]}` : 'Nova Entrada de Produtos'}</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="estoque.entrada.form" />
            <BotaoFecharTela onClick={() => navigate('/entrada-produtos-compra')} />
            {!entradaGravada && modo !== null && (
              <button type="button" className="btn" disabled={!podeConfirmar || efetivar.isPending} onClick={() => efetivar.mutate()}>
                {efetivar.isPending ? 'Confirmando…' : 'Confirmar Entrada'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes form-secoes-larga">
          {entradaGravada ? (
            <section className="section">
              <p className="section-label" style={{ margin: 0 }}>
                Entrada confirmada
              </p>
              <p className="muted" style={{ marginTop: 8 }}>
                Nota fiscal {entradaGravada.notaFiscal ?? '—'} de <strong>{entradaGravada.nomeFornecedor}</strong> —{' '}
                {entradaGravada.itens.length} item(ns), total {moeda(entradaGravada.valorTotal)}.
              </p>
              <div className="ajuda-rodape" style={{ justifyContent: 'flex-start' }}>
                <Link
                  className="btn ghost"
                  to={`/etiqueta-emissao?idFornecedor=${entradaGravada.idFornecedor}&notaFiscal=${entradaGravada.notaFiscal ?? ''}`}
                >
                  Emitir Etiquetas desta Nota
                </Link>
                <button type="button" className="btn" onClick={() => navigate('/entrada-produtos-compra')}>
                  Ver Listagem
                </button>
                <button
                  type="button"
                  className="btn ghost"
                  onClick={() => {
                    setEntradaGravada(null)
                    setModo(null)
                    setItens([])
                    setPendentes([])
                    setParcelas([])
                    setValorRateioTexto('')
                    setNotaFiscalTexto('')
                    setDataEntradaTexto(hojeBr())
                    setAba('GERAL')
                    setAbaProdutos('LOCALIZADOS')
                    setNumeroParcelasTexto('')
                    setFornecedorEscolhido(null)
                    setBuscaFornecedor('')
                    setArquivoXml(null)
                    setXmlBruto(null)
                    setChaveNfeXml(null)
                    setSerieNotaXml(null)
                    setChaveJaImportada(false)
                    setFornecedorXmlSemCadastro(null)
                  }}
                >
                  Nova Entrada
                </button>
              </div>
            </section>
          ) : modo === null ? (
            <p className="muted">Escolha o fluxo de entrada para continuar.</p>
          ) : (
            <>
              {temAbas && (
                <>
                  <section className="section">
                    <div className="editor-etiqueta-segmentado">
                      <button type="button" className={`btn ghost ${aba === 'GERAL' ? 'ativa' : ''}`} onClick={() => setAba('GERAL')}>
                        1. Dados Gerais
                      </button>
                      <button type="button" className={`btn ghost ${aba === 'PRODUTOS' ? 'ativa' : ''}`} onClick={() => setAba('PRODUTOS')}>
                        2. Produtos {itens.length > 0 && `(${itens.length})`}
                      </button>
                      <button
                        type="button"
                        className={`btn ghost ${aba === 'FINANCEIRO' ? 'ativa' : ''}`}
                        onClick={() => setAba('FINANCEIRO')}
                      >
                        3. Financeiro {parcelas.length > 0 && `(${parcelas.length})`}
                      </button>
                    </div>
                  </section>

                  {aba === 'GERAL' && (
                    <section className="section">
                      <p className="section-label" style={{ margin: 0 }}>
                        Dados Gerais
                      </p>

                      {modo === 'XML' && (
                        <>
                          <p className="section-label" style={{ margin: '8px 0 0' }}>
                            Arquivo da Nota Fiscal (XML)
                          </p>
                          <p className="muted" style={{ marginTop: 4 }}>
                            Envie o XML da NF-e (modelo 55) primeiro — fornecedor, empresa, Nº da nota, data e parcelas são
                            identificados automaticamente a partir dele.
                          </p>
                          <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 10, flexWrap: 'wrap' }}>
                            <input
                              ref={inputArquivoXmlRef}
                              type="file"
                              accept=".xml"
                              style={{ display: 'none' }}
                              onChange={(e) => setArquivoXml(e.target.files?.[0] ?? null)}
                            />
                            <button type="button" className="btn ghost" onClick={() => inputArquivoXmlRef.current?.click()}>
                              Escolher Arquivo…
                            </button>
                            <span className="muted">
                              {arquivoXml?.name ?? (chaveNfeXml ? `Nota ${notaFiscalTexto || '—'} processada.` : 'Nenhum arquivo selecionado.')}
                            </span>
                            <button
                              type="button"
                              className="btn"
                              disabled={!arquivoXml || processarXml.isPending}
                              onClick={() => {
                                processarXml.mutate(undefined, { onSuccess: () => setAba('PRODUTOS') })
                              }}
                            >
                              {processarXml.isPending ? 'Processando…' : 'Processar XML'}
                            </button>
                          </div>
                          {chaveJaImportada && (
                            <p className="erro-campo" style={{ marginTop: 8 }}>
                              Esta nota fiscal já foi importada antes (chave {chaveNfeXml}) — não é possível confirmar de novo.
                            </p>
                          )}
                        </>
                      )}

                      {(modo !== 'XML' || chaveNfeXml) && (
                        <>
                          {modo === 'XML' && (
                            <p className="section-label" style={{ margin: '20px 0 0' }}>
                              Dados da Nota
                            </p>
                          )}
                          {renderCamposIdentificacao()}

                          {fornecedorXmlSemCadastro && !fornecedorEscolhido && (
                            <p className="muted" style={{ marginTop: 8 }}>
                              Fornecedor do XML — <strong>{fornecedorXmlSemCadastro.razaoSocial}</strong>
                              {fornecedorXmlSemCadastro.cnpj ? ` (CNPJ ${fornecedorXmlSemCadastro.cnpj})` : ''} — não está cadastrado.{' '}
                              <button type="button" className="btn ghost" onClick={() => setModalFornecedorAberto(true)}>
                                ＋ Cadastrar agora
                              </button>
                            </p>
                          )}
                        </>
                      )}

                      {modo === 'PLANILHA' && (
                        <>
                          <p className="section-label" style={{ margin: '20px 0 0' }}>
                            Arquivo da Planilha
                          </p>
                          <p className="muted" style={{ marginTop: 4 }}>
                            Colunas esperadas: NOME DO PRODUTO, MARCA, REFERENCIA, COR, TAMANHO, CODIGO BARRAS FABRICANTE, QTD, CUSTO UNITARIO.
                          </p>
                          <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 10, flexWrap: 'wrap' }}>
                            <input
                              ref={inputArquivoRef}
                              type="file"
                              accept=".xlsx,.xls"
                              style={{ display: 'none' }}
                              onChange={(e) => setArquivoPlanilha(e.target.files?.[0] ?? null)}
                            />
                            <button type="button" className="btn ghost" onClick={() => inputArquivoRef.current?.click()}>
                              Escolher Arquivo…
                            </button>
                            <span className="muted">{arquivoPlanilha?.name ?? 'Nenhum arquivo selecionado.'}</span>
                            <button
                              type="button"
                              className="btn"
                              disabled={!arquivoPlanilha || processarPlanilha.isPending}
                              onClick={() => {
                                processarPlanilha.mutate(undefined, { onSuccess: () => setAba('PRODUTOS') })
                              }}
                            >
                              {processarPlanilha.isPending ? 'Processando…' : 'Processar Planilha'}
                            </button>
                            <button type="button" className="btn ghost" disabled={baixarModelo.isPending} onClick={() => baixarModelo.mutate()}>
                              Baixar Modelo
                            </button>
                          </div>
                        </>
                      )}
                    </section>
                  )}

                  {aba === 'PRODUTOS' && (
                    <section className="section">
                      {modo === 'PLANILHA' || modo === 'XML' ? (
                        <>
                          <div className="editor-etiqueta-segmentado" style={{ marginBottom: 12 }}>
                            <button
                              type="button"
                              className={`btn ghost ${abaProdutos === 'LOCALIZADOS' ? 'ativa' : ''}`}
                              onClick={() => setAbaProdutos('LOCALIZADOS')}
                            >
                              Localizados {itens.length > 0 && `(${itens.length})`}
                            </button>
                            <button
                              type="button"
                              className={`btn ghost ${abaProdutos === 'NAO_LOCALIZADOS' ? 'ativa' : ''}`}
                              onClick={() => setAbaProdutos('NAO_LOCALIZADOS')}
                            >
                              Não Localizados {pendentes.length > 0 && `(${pendentes.length})`}
                            </button>
                          </div>

                          {abaProdutos === 'LOCALIZADOS' ? (
                            <>
                              <p className="section-label" style={{ margin: 0 }}>
                                Localizados
                              </p>
                              {renderGradeItens(true)}
                            </>
                          ) : (
                            <>
                              <p className="section-label" style={{ margin: 0 }}>
                                Não Localizados
                              </p>
                              {pendentes.length === 0 ? (
                                <p className="muted" style={{ marginTop: 12 }}>
                                  Nenhuma pendência — todos os itens foram localizados.
                                </p>
                              ) : (
                                <div className="table-wrap grid-altura-fixa" style={{ marginTop: 12 }}>
                                  <table className="table table-compacta">
                                    <thead>
                                      <tr>
                                        <th>Linha</th>
                                        <th>Produto</th>
                                        <th>Cor/Tamanho</th>
                                        <th style={{ textAlign: 'right' }}>Qtd</th>
                                        <th>Resolução</th>
                                      </tr>
                                    </thead>
                                    <tbody>
                                      {pendentes.map((linha) => (
                                        <LinhaPendentePlanilha
                                          key={linha.numeroLinha}
                                          linha={linha}
                                          permiteQtdDecimal={permiteQtdDecimal}
                                          onIgnorar={() => removerPendente(linha.numeroLinha)}
                                          onPesquisar={() => {
                                            setPendenteEmResolucao(linha.numeroLinha)
                                            setMostrarPesquisa(true)
                                          }}
                                          onCadastrar={() => {
                                            setPendenteEmResolucao(linha.numeroLinha)
                                            setModalProdutoAberto(true)
                                          }}
                                          onResolvido={(v) => {
                                            lancarItemComCusto(
                                              v.idVariacao,
                                              v.descricao,
                                              variacaoTexto(v),
                                              linha.qtd ?? 1,
                                              linha.custoUnitario ?? 0,
                                              linha.codigoFornecedor,
                                            )
                                            removerPendente(linha.numeroLinha)
                                          }}
                                        />
                                      ))}
                                    </tbody>
                                  </table>
                                </div>
                              )}
                            </>
                          )}
                        </>
                      ) : (
                        <>
                          <p className="section-label" style={{ margin: 0 }}>
                            Produtos
                          </p>
                          <div style={{ display: 'flex', gap: 12, marginTop: 10 }}>
                            <button
                              type="button"
                              className="btn ghost"
                              onClick={() => {
                                setPendenteEmResolucao(null)
                                setProdutoInicialPesquisa(undefined)
                                setMostrarPesquisaEntrada(true)
                              }}
                            >
                              <IconeLupa /> Pesquisar Produto
                            </button>
                            <button
                              type="button"
                              className="btn ghost"
                              onClick={() => {
                                setPendenteEmResolucao(null)
                                setModalProdutoAberto(true)
                              }}
                            >
                              ＋ Cadastrar Produto
                            </button>
                          </div>
                          {renderGradeItens(false)}
                        </>
                      )}
                    </section>
                  )}

                  {aba === 'FINANCEIRO' && (
                    <section className="section">
                      <p className="section-label" style={{ margin: 0 }}>
                        Financeiro
                      </p>
                      {renderContasPagar()}
                    </section>
                  )}
                </>
              )}
            </>
          )}
        </div>
      </div>

      {(modo === 'PLANILHA' || modo === 'XML') && aba === 'PRODUTOS' && abaProdutos === 'LOCALIZADOS' && !entradaGravada && (
        <div className="lista-rodape">
          <div className="card" style={{ display: 'flex', justifyContent: 'flex-end', gap: 32, padding: '14px 24px' }}>
            <span>
              Quantidade: <strong className="mono">{formatarQuantidade(qtdTotal, permiteQtdDecimal)}</strong>
            </span>
            <span>
              Valor Total: <strong className="mono">{moeda(valorTotal)}</strong>
            </span>
          </div>
        </div>
      )}

      {modo === null && !entradaGravada && (
        <div className="modal-overlay">
          <div className="modal" role="dialog" aria-label="Origem dos Dados Para Entrada">
            <div className="lightbox-topo" style={{ marginBottom: 12 }}>
              <h2 style={{ margin: 0 }}>Origem dos Dados Para Entrada</h2>
              <button
                type="button"
                className="btn ghost btn-fechar-tela"
                onClick={() => navigate('/entrada-produtos-compra')}
                aria-label="Fechar"
                title="Fechar"
              >
                <IconeFechar />
              </button>
            </div>
            <p className="muted">Escolha como você quer lançar os produtos desta compra.</p>
            <div className="ajuda-rodape" style={{ justifyContent: 'center', flexWrap: 'wrap', gap: 12, marginTop: 20 }}>
              <button type="button" className="btn" onClick={() => setModo('PLANILHA')}>
                Planilha Excel
              </button>
              <button type="button" className="btn" onClick={() => setModo('INDIVIDUAL')}>
                Individual
              </button>
              <button type="button" className="btn" onClick={() => setModo('XML')}>
                Por XML
              </button>
            </div>
          </div>
        </div>
      )}

      {mostrarPesquisa && (
        <PesquisaProdutoModal
          aoFechar={() => {
            setMostrarPesquisa(false)
            setPendenteEmResolucao(null)
          }}
          aoSelecionar={aoSelecionarNaPesquisa}
        />
      )}
      {mostrarPesquisaEntrada && (
        <PesquisaProdutoEntradaModal
          aoFechar={() => {
            setMostrarPesquisaEntrada(false)
            setProdutoInicialPesquisa(undefined)
          }}
          aoAdicionar={aoAdicionarDaPesquisaEntrada}
          produtoInicial={produtoInicialPesquisa}
        />
      )}
      {modalProdutoAberto && (
        <ProdutoQuickCreateModal
          aoFechar={() => {
            setModalProdutoAberto(false)
            setPendenteEmResolucao(null)
          }}
          aoCriar={aoCriarProduto}
          aoCriarComGrade={aoCriarProdutoComGrade}
          // XML nunca exige cor/tamanho no cadastro rápido, mesmo resolvendo uma pendência
          // (2026-08-19, pedido do dono do produto: "quando chamar pra cadastrar o produto, nao
          // precisa pedir cor e tamanho la") — a variação é definida depois, direto na linha da
          // grid "Não Localizados" (ver `aoCriarProdutoComGrade`). Planilha continua exigindo:
          // lá a confiança é maior porque foi o próprio lojista quem preencheu cor/tamanho.
          exigirVariacaoAoCriar={pendenteEmResolucao != null && modo !== 'XML'}
          valorInicial={
            pendenteEmResolucao != null
              ? (() => {
                  const linha = pendentes.find((p) => p.numeroLinha === pendenteEmResolucao)
                  return linha
                    ? {
                        descricao: linha.nomeProduto ?? '',
                        marca: linha.marca ?? '',
                        referencia: linha.referencia ?? '',
                        precoCusto: linha.custoUnitario != null ? formatarMoeda(linha.custoUnitario) : '',
                        ean: linha.codigoBarrasFabricante ?? '',
                        // XML nunca cadastra cor/tamanho sozinho (2026-08-18, pedido do dono do
                        // produto — confiança menor que a Planilha, que o próprio lojista
                        // preencheu): omitir aqui impede o auto-resolve/auto-cadastro silencioso
                        // do modal, forçando os selects manuais (ou "＋ Nova cor"/"＋ Novo
                        // tamanho", ação explícita) mesmo quando o texto do XML já "bateria".
                        cor: modo === 'XML' ? undefined : (linha.cor ?? undefined),
                        tamanho: modo === 'XML' ? undefined : (linha.tamanho ?? undefined),
                      }
                    : undefined
                })()
              : undefined
          }
        />
      )}
      {modalFornecedorAberto && (
        <FornecedorQuickCreateModal
          aoFechar={() => setModalFornecedorAberto(false)}
          aoCriar={(fornecedor) => {
            setFornecedorEscolhido({ idFornecedor: fornecedor.idFornecedor, razaoSocial: fornecedor.razaoSocial })
            setFornecedorXmlSemCadastro(null)
            setModalFornecedorAberto(false)
          }}
          valorInicial={
            modo === 'XML' && fornecedorXmlSemCadastro
              ? {
                  razaoSocial: fornecedorXmlSemCadastro.razaoSocial ?? '',
                  nomeFantasia: fornecedorXmlSemCadastro.nomeFantasia ?? '',
                  cnpj: fornecedorXmlSemCadastro.cnpj ? mascararCpfCnpj(fornecedorXmlSemCadastro.cnpj, false) : '',
                  inscricaoEstadual: fornecedorXmlSemCadastro.inscricaoEstadual ?? '',
                  endereco: fornecedorXmlSemCadastro.endereco ?? '',
                  numero: fornecedorXmlSemCadastro.numero ?? '',
                  bairro: fornecedorXmlSemCadastro.bairro ?? '',
                  cidade: fornecedorXmlSemCadastro.cidade ?? '',
                  estado: fornecedorXmlSemCadastro.estado ?? '',
                  cep: fornecedorXmlSemCadastro.cep ?? '',
                  telefone: fornecedorXmlSemCadastro.telefone ?? '',
                }
              : undefined
          }
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
