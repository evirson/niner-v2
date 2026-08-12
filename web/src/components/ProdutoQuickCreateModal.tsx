import { useEffect, useState, type ChangeEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/api'
import { listarCategoriasProduto } from '../lib/categoriasProduto'
import { buscarUsaCorGrade } from '../lib/configuracaoGeral'
import { criarCor, listarCores } from '../lib/cores'
import { atualizarGrade, buscarGrade, listarGrades } from '../lib/grades'
import {
  completarMoeda,
  completarPercentual,
  completarPeso,
  desmascararMoeda,
  desmascararPercentual,
  desmascararPeso,
  formatarMoeda,
  formatarPercentual,
  mascararData,
  mascararMoeda,
  mascararNcm,
  mascararPercentual,
  mascararPeso,
  somenteDigitos,
} from '../lib/masks'
import { buscarNcm } from '../lib/ncm'
import { PRODUTO_VAZIO, criarProduto, criarVariacao, paraRequisicao, type Produto, type ProdutoFormState, type VariacaoProduto } from '../lib/produtos'
import { criarTamanho, listarTamanhos } from '../lib/tamanhos'
import { maiusculas } from '../lib/texto'
import CategoriaProdutoModal from './CategoriaProdutoModal'
import GradeModal from './GradeModal'
import { IconeExcluir, IconeLupa, IconeSetaBaixo, IconeSetaCima } from './Icones'
import PesquisaNcmModal from './PesquisaNcmModal'
import Toast from './Toast'

/**
 * Criação rápida de produto embutida (Entrada de Produtos por Compra, 2026-08-11) — quando o
 * item lido/pesquisado não existe ainda no cadastro. Traz TODOS os campos do cadastro completo
 * (2026-08-13, pedido do dono do produto — a versão anterior só tinha descrição/custo/%venda/
 * grade), reaproveitando `paraRequisicao`/`PRODUTO_VAZIO` de `lib/produtos.ts` — o mesmo contrato
 * usado por `ProdutoForm.tsx`. Fica de fora, de propósito: campos obrigatórios configuráveis por
 * tenant (`cfg_tela_campo`), a galeria de fotos (upload encadeado após o produto existir — foge
 * do espírito "rápido" deste modal) e o toggle "produto ativo" (todo produto cadastrado aqui
 * nasce ativo, é a única opção que faz sentido ao receber mercadoria). Validações que o backend
 * já garante sozinho (regra da oferta, peso líquido ≤ bruto) não são replicadas aqui — erro do
 * servidor vira Toast, igual ao resto do modal.
 *
 * <p>Cor/tamanho/EAN vindos de `valorInicial` (fluxo Planilha da Entrada) não aparecem em campo
 * nenhum (2026-08-13, pedido explícito do dono do produto, 2ª rodada — a 1ª versão ainda
 * mostrava um select pré-selecionado ou uma linha de confirmação; ele pediu pra tirar de vez:
 * "você já sabe o que fazer com isso"). Cor/tamanho são resolvidos 100% em silêncio assim que
 * uma grade fica disponível — acham o que já existe por nome ou cadastram na hora (mesma ideia
 * de "＋ Nova cor"/"＋ Novo tamanho", sem o usuário precisar clicar em nada; só volta a mostrar o
 * select manual se a criação automática falhar de verdade, toast avisa o motivo). EAN fica só
 * guardado em memória e é gravado direto em `produto_barra.ean` ao criar a variação.
 */
export default function ProdutoQuickCreateModal({
  aoFechar,
  aoCriar,
  aoCriarComGrade,
  exigirVariacaoAoCriar = true,
  valorInicial,
}: {
  aoFechar: () => void
  aoCriar?: (variacao: VariacaoProduto) => void
  /** Quando `false` e o produto criado usa grade, os campos Cor/Tamanho nem aparecem e nenhuma
   *  variação é criada aqui — só o produto, devolvido via `aoCriarComGrade` (2026-08-16, "＋
   *  Cadastrar Produto" do fluxo Individual da Entrada: a escolha de cor + quantidade por
   *  tamanho já acontece no popup de pesquisa/lançamento logo em seguida — pedir de novo aqui
   *  seria redundante e bloquearia o cadastro por antecipação). Produto SEM grade sempre cria a
   *  variação (idCor/idTamanho nulos) e chama `aoCriar`, independente deste flag — não há nada
   *  a decidir nesse caso. Default `true` preserva o comportamento de sempre (Planilha da
   *  Entrada, que já resolve cor/tamanho — automaticamente ou via os selects manuais). */
  exigirVariacaoAoCriar?: boolean
  /** Só chamado quando `exigirVariacaoAoCriar=false` e o produto criado usa grade — ver acima. */
  aoCriarComGrade?: (produto: Produto) => void
  /** Pré-preenchimento (fluxo Planilha da Entrada, 2026-08-12) — quando a linha da planilha não
   *  achou o produto, a tela já abre o cadastro rápido com o que a planilha trouxe. */
  valorInicial?: { descricao?: string; marca?: string; referencia?: string; precoCusto?: string; ean?: string; cor?: string; tamanho?: string }
}) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<ProdutoFormState>({
    ...PRODUTO_VAZIO,
    descricao: valorInicial?.descricao ?? '',
    marca: valorInicial?.marca ?? '',
    referencia: valorInicial?.referencia ?? '',
    precoCusto: valorInicial?.precoCusto ?? '',
  })
  const [ean, setEan] = useState(valorInicial?.ean ?? '')
  const [idCor, setIdCor] = useState<number | ''>('')
  const [idTamanho, setIdTamanho] = useState<number | ''>('')
  const [novaCorNome, setNovaCorNome] = useState('')
  const [novoTamanhoNome, setNovoTamanhoNome] = useState('')
  const [modalGradeAberto, setModalGradeAberto] = useState(false)
  const [modalCategoriaAberto, setModalCategoriaAberto] = useState(false)
  const [modalNcmAberto, setModalNcmAberto] = useState(false)
  const [categoriaParaAdicionar, setCategoriaParaAdicionar] = useState('')
  const [descricaoNcm, setDescricaoNcm] = useState('')
  const [gradeAutoResolvida, setGradeAutoResolvida] = useState<number | null>(null)
  const [toast, setToast] = useState('')

  const { data: cfgUsaCorGrade } = useQuery({ queryKey: ['usa-cor-grade'], queryFn: buscarUsaCorGrade })
  const usaCorGrade = cfgUsaCorGrade?.cfgUsaCorGrade ?? false

  const { data: grades } = useQuery({ queryKey: ['grades'], queryFn: listarGrades, enabled: usaCorGrade })
  const { data: cores } = useQuery({ queryKey: ['cores'], queryFn: listarCores, enabled: usaCorGrade })
  const { data: tamanhos } = useQuery({ queryKey: ['tamanhos'], queryFn: listarTamanhos, enabled: usaCorGrade })
  const { data: grade } = useQuery({
    queryKey: ['grade', form.idGrade],
    queryFn: () => buscarGrade(form.idGrade!),
    enabled: usaCorGrade && form.idGrade != null,
  })
  const { data: categorias } = useQuery({ queryKey: ['categorias-produto'], queryFn: listarCategoriasProduto })
  const categoriasDisponiveis = (categorias ?? []).filter((c) => !form.categorias.some((fc) => fc.idCategoria === c.idCategoria))

  const campo = (chave: 'marca' | 'referencia') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  const buscarDescricaoDoNcm = async (codigo: string) => {
    const cod = codigo.trim()
    if (!cod) {
      setDescricaoNcm('')
      return
    }
    const ncm = await buscarNcm(cod)
    if (!ncm) {
      setDescricaoNcm('')
      setForm((f) => ({ ...f, codigoNcm: '' }))
      return
    }
    setDescricaoNcm(ncm.descricaoNcm)
  }

  const criarNovaCor = useMutation({
    mutationFn: criarCor,
    onSuccess: (cor) => {
      queryClient.invalidateQueries({ queryKey: ['cores'] })
      setIdCor(cor.idCor)
      setNovaCorNome('')
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível criar a cor.'),
  })

  /** Acha ou cadastra o tamanho no catálogo (`cfg_tamanho`) e garante que ele esteja na grade
   *  sendo usada — mesma operação que "＋ Gerenciar Grades" faria manualmente. Achar por nome no
   *  CATÁLOGO INTEIRO do tenant primeiro (não só nos tamanhos já presentes NESTA grade) é o que
   *  importa aqui: um tamanho pode já existir globalmente (criado por outro produto/grade) sem
   *  ainda pertencer a esta grade específica — tentar `criarTamanho` direto nesse caso batia na
   *  restrição de nome único (`id_tenant, descricao`) e falhava, o que fazia o campo "Tamanho"
   *  cair de volta pro select manual mesmo quando o tamanho já estava cadastrado (2026-08-14,
   *  bug relatado pelo dono do produto — reproduzido com o tamanho "17", que já existia mas não
   *  fazia parte da grade escolhida pra esse produto). */
  const criarNovoTamanho = useMutation({
    mutationFn: async (nome: string) => {
      const existente = (tamanhos ?? []).find((t) => t.descricao === nome)
      const tamanho = existente ?? (await criarTamanho(nome))
      if (!grade!.tamanhos.some((t) => t.idTamanho === tamanho.idTamanho)) {
        await atualizarGrade(grade!.idGrade, grade!.descricao, [...grade!.tamanhos.map((t) => t.idTamanho), tamanho.idTamanho])
      }
      return tamanho
    },
    onSuccess: (tamanho) => {
      queryClient.invalidateQueries({ queryKey: ['grade', form.idGrade] })
      queryClient.invalidateQueries({ queryKey: ['grades'] })
      queryClient.invalidateQueries({ queryKey: ['tamanhos'] })
      setIdTamanho(tamanho.idTamanho)
      setNovoTamanhoNome('')
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível criar o tamanho.'),
  })

  // Auto-resolve cor/tamanho vindos da planilha (2026-08-13): assim que a grade escolhida (ou
  // recém-criada) fica disponível, tenta casar por nome com o que já existe e, se não achar,
  // cadastra na hora — o usuário só precisa agir se algo der errado (toast de erro).
  useEffect(() => {
    if (!usaCorGrade || form.idGrade == null || gradeAutoResolvida === form.idGrade) return
    if (!cores || !grade || !tamanhos) return
    setGradeAutoResolvida(form.idGrade)
    if (valorInicial?.cor) {
      const nome = valorInicial.cor.trim().toUpperCase()
      const achada = cores.find((c) => c.descricao === nome)
      if (achada) setIdCor(achada.idCor)
      else criarNovaCor.mutate(nome)
    }
    if (valorInicial?.tamanho) {
      const nome = valorInicial.tamanho.trim().toUpperCase()
      const achadoNaGrade = grade.tamanhos.find((t) => t.descricao === nome)
      if (achadoNaGrade) setIdTamanho(achadoNaGrade.idTamanho)
      else criarNovoTamanho.mutate(nome)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usaCorGrade, form.idGrade, cores, grade, tamanhos, gradeAutoResolvida])

  const criar = useMutation({
    mutationFn: async () => {
      const produto = await criarProduto(paraRequisicao(form))
      if (!exigirVariacaoAoCriar && produto.idGrade != null) {
        return { semVariacao: true as const, produto }
      }
      const variacao = await criarVariacao(produto.idProduto, {
        idCor: usaCorGrade && form.idGrade != null ? (idCor as number) : null,
        idTamanho: usaCorGrade && form.idGrade != null ? (idTamanho as number) : null,
        ean: ean.trim() ? ean.trim() : null,
      })
      return { semVariacao: false as const, variacao }
    },
    onSuccess: (resultado) => {
      queryClient.invalidateQueries({ queryKey: ['produtos'] })
      if (resultado.semVariacao) {
        aoCriarComGrade?.(resultado.produto)
      } else {
        aoCriar?.(resultado.variacao)
      }
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível criar o produto.'),
  })

  const recalcularPrecoVenda = (custoStr: string, percentualStr: string, vendaAtual: string): string => {
    const custo = desmascararMoeda(custoStr)
    if (custo <= 0) return vendaAtual
    const percentual = desmascararPercentual(percentualStr)
    return formatarMoeda(custo * (1 + percentual / 100))
  }

  const aoMudarPrecoCusto = (e: ChangeEvent<HTMLInputElement>) => {
    const precoCusto = mascararMoeda(e.target.value)
    setForm((f) => ({ ...f, precoCusto, precoVenda: recalcularPrecoVenda(precoCusto, f.percentualVenda, f.precoVenda) }))
  }

  const aoMudarPercentualVenda = (e: ChangeEvent<HTMLInputElement>) => {
    const percentualVenda = mascararPercentual(e.target.value)
    setForm((f) => ({ ...f, percentualVenda, precoVenda: recalcularPrecoVenda(f.precoCusto, percentualVenda, f.precoVenda) }))
  }

  const aoMudarPrecoVenda = (e: ChangeEvent<HTMLInputElement>) => {
    const precoVenda = mascararMoeda(e.target.value)
    setForm((f) => {
      const custo = desmascararMoeda(f.precoCusto)
      if (custo <= 0) return { ...f, precoVenda }
      const venda = desmascararMoeda(precoVenda)
      return { ...f, precoVenda, percentualVenda: formatarPercentual(((venda - custo) / custo) * 100) }
    })
  }

  const adicionarCategoria = () => {
    const idCategoria = Number(categoriaParaAdicionar)
    const categoria = categorias?.find((c) => c.idCategoria === idCategoria)
    if (!categoria) return
    setForm((f) => ({
      ...f,
      categorias: [...f.categorias, { idCategoria: categoria.idCategoria, nomeCategoria: categoria.nomeCategoria, indice: f.categorias.length }],
    }))
    setCategoriaParaAdicionar('')
  }

  const removerCategoria = (idCategoria: number) => setForm((f) => ({ ...f, categorias: f.categorias.filter((c) => c.idCategoria !== idCategoria) }))

  const moverCategoria = (indice: number, direcao: -1 | 1) =>
    setForm((f) => {
      const alvo = indice + direcao
      if (alvo < 0 || alvo >= f.categorias.length) return f
      const lista = [...f.categorias]
      ;[lista[indice], lista[alvo]] = [lista[alvo], lista[indice]]
      return { ...f, categorias: lista }
    })

  /** Quando a cor/tamanho vêm da planilha, o campo nem aparece (2026-08-13, pedido do dono do
   *  produto, 2ª rodada: a 1ª versão trocou o select por uma linha de confirmação, mas ele pediu
   *  pra tirar de vez — "não precisa me mostrar tamanho e nem cor... você já sabe o que fazer com
   *  isso"). Resolvido 100% em silêncio pelo efeito abaixo; só cai de volta pro select manual se
   *  a criação automática falhar de verdade (toast de erro avisa o motivo — aí sim precisa de
   *  uma escolha humana). */
  const corAutomatica = Boolean(valorInicial?.cor) && !criarNovaCor.isError
  const tamanhoAutomatico = Boolean(valorInicial?.tamanho) && !criarNovoTamanho.isError

  /** Cód. de barras do fabricante: quando já vem da planilha, fica só guardado em memória (state
   *  `ean`) e é gravado direto em `produto_barra.ean` ao criar a variação — não tem por que
   *  mostrar esse campo de novo pro usuário (2026-08-13, mesmo pedido: "isso não é necessário...
   *  você já sabe o que fazer com isso"). Sem planilha (cadastro em branco do fluxo Individual),
   *  continua editável — não há nenhum código já conhecido pra guardar. */
  const eanAutomatico = Boolean(valorInicial?.ean)

  const precisaCorTamanho = usaCorGrade && form.idGrade != null && exigirVariacaoAoCriar
  /** Preço de venda não pode ficar abaixo do preço de custo (2026-08-17, regra do projeto
   *  inteiro) — o servidor também recusa (`ProdutoService.validarPrecos`), isto aqui só evita a
   *  ida-e-volta ao servidor pra descobrir o mesmo erro. */
  const precoVendaMenorQueCusto = desmascararMoeda(form.precoVenda) < desmascararMoeda(form.precoCusto)
  const valido =
    form.descricao.trim() &&
    form.precoCusto.trim() &&
    form.percentualVenda.trim() &&
    !precoVendaMenorQueCusto &&
    (!usaCorGrade || form.idGrade != null) &&
    (!precisaCorTamanho || (idCor !== '' && idTamanho !== ''))

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal modal-largo" role="dialog" aria-label="Novo produto" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Novo produto</h2>
        <p className="muted" style={{ marginTop: 4 }}>
          Cadastro rápido, sem sair da entrada — a gestão completa (fotos, ofertas…) fica na tela Produtos.
        </p>

        <div className="form-grid">
          <div className="col-6">
            <label htmlFor="modal-produto-descricao">Descrição *</label>
            <input
              id="modal-produto-descricao"
              autoFocus
              value={form.descricao}
              onChange={(e) => setForm((f) => ({ ...f, descricao: maiusculas(e.target.value) }))}
            />
          </div>
          <div className="col-3">
            <label htmlFor="modal-produto-marca">Marca</label>
            <input id="modal-produto-marca" value={form.marca} onChange={campo('marca')} placeholder="Opcional" />
          </div>
          <div className="col-3">
            <label htmlFor="modal-produto-referencia">Referência</label>
            <input id="modal-produto-referencia" value={form.referencia} onChange={campo('referencia')} placeholder="Opcional" />
          </div>

          <div className="col-3">
            <label htmlFor="modal-produto-ncm">NCM</label>
            <div className="linha-com-botao">
              <input
                id="modal-produto-ncm"
                placeholder="9999.99.99"
                value={form.codigoNcm}
                onChange={(e) => setForm((f) => ({ ...f, codigoNcm: mascararNcm(e.target.value) }))}
                onBlur={(e) => buscarDescricaoDoNcm(somenteDigitos(e.target.value))}
              />
              <button
                type="button"
                tabIndex={-1}
                className="btn ghost"
                onClick={() => setModalNcmAberto(true)}
                aria-label="Pesquisar NCM por nome"
                title="Pesquisar NCM por nome"
              >
                <IconeLupa />
              </button>
            </div>
          </div>
          <div className="col-9">
            <label htmlFor="modal-produto-ncm-descricao">NCM - Nomenclatura Comum do Mercosul</label>
            <input id="modal-produto-ncm-descricao" className="campo-leitura" readOnly tabIndex={-1} value={descricaoNcm} placeholder="Digite um código NCM cadastrado…" />
          </div>
        </div>

        <p className="section-label" style={{ marginTop: 16 }}>
          Categorias
        </p>
        {form.categorias.length > 0 && (
          <ul className="lista-categorias-produto">
            {form.categorias.map((c, i) => (
              <li key={c.idCategoria}>
                <span>{c.nomeCategoria}</span>
                <div className="topbar-acoes">
                  <button type="button" className="btn ghost" tabIndex={-1} disabled={i === 0} onClick={() => moverCategoria(i, -1)} title="Mover para cima">
                    <IconeSetaCima />
                  </button>
                  <button
                    type="button"
                    className="btn ghost"
                    tabIndex={-1}
                    disabled={i === form.categorias.length - 1}
                    onClick={() => moverCategoria(i, 1)}
                    title="Mover para baixo"
                  >
                    <IconeSetaBaixo />
                  </button>
                  <button type="button" className="btn ghost" tabIndex={-1} onClick={() => removerCategoria(c.idCategoria)} title="Remover">
                    <IconeExcluir />
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
        <div className="linha-com-botao">
          <select value={categoriaParaAdicionar} onChange={(e) => setCategoriaParaAdicionar(e.target.value)}>
            <option value="">Selecione…</option>
            {categoriasDisponiveis.map((c) => (
              <option key={c.idCategoria} value={c.idCategoria}>
                {c.nomeCategoria}
              </option>
            ))}
          </select>
          <button type="button" tabIndex={-1} className="btn ghost" disabled={!categoriaParaAdicionar} onClick={adicionarCategoria}>
            ＋ Adicionar
          </button>
          <button type="button" tabIndex={-1} className="btn ghost" onClick={() => setModalCategoriaAberto(true)}>
            ＋ Gerenciar categorias
          </button>
        </div>

        <p className="section-label" style={{ marginTop: 16 }}>
          Preços
        </p>
        <div className="form-grid">
          <div className="col-2">
            <label htmlFor="modal-produto-custo">Preço de Custo *</label>
            <input
              id="modal-produto-custo"
              inputMode="decimal"
              placeholder="0,00"
              value={form.precoCusto}
              onChange={aoMudarPrecoCusto}
              onBlur={() => setForm((f) => ({ ...f, precoCusto: completarMoeda(f.precoCusto) }))}
              onFocus={(e) => e.target.select()}
            />
          </div>
          <div className="col-2">
            <label htmlFor="modal-produto-percentual">% de Venda *</label>
            <input
              id="modal-produto-percentual"
              inputMode="decimal"
              placeholder="0,00"
              value={form.percentualVenda}
              onChange={aoMudarPercentualVenda}
              onBlur={() => setForm((f) => ({ ...f, percentualVenda: completarPercentual(f.percentualVenda) }))}
              onFocus={(e) => e.target.select()}
            />
          </div>
          <div className="col-2">
            <label htmlFor="modal-produto-venda">Preço de Venda *</label>
            <input
              id="modal-produto-venda"
              inputMode="decimal"
              placeholder="0,00"
              value={form.precoVenda}
              onChange={aoMudarPrecoVenda}
              onBlur={() => setForm((f) => ({ ...f, precoVenda: completarMoeda(f.precoVenda) }))}
              onFocus={(e) => e.target.select()}
            />
            {precoVendaMenorQueCusto && <p className="erro-campo">Não pode ser menor que o preço de custo.</p>}
          </div>
          {!eanAutomatico && (
            <div className="col-2">
              <label htmlFor="modal-produto-ean">Cód. Barras do Fabricante</label>
              <input id="modal-produto-ean" value={ean} onChange={(e) => setEan(e.target.value.replace(/\D/g, ''))} placeholder="Opcional" />
            </div>
          )}
        </div>

        <div className="form-grid" style={{ marginTop: 8 }}>
          <div className="col-4">
            <label htmlFor="modal-produto-oferta-inicio">Início da oferta</label>
            <input
              id="modal-produto-oferta-inicio"
              placeholder="dd/mm/aaaa"
              value={form.dataInicioOferta}
              onChange={(e) => setForm((f) => ({ ...f, dataInicioOferta: mascararData(e.target.value) }))}
              onFocus={(e) => e.target.select()}
            />
          </div>
          <div className="col-4">
            <label htmlFor="modal-produto-oferta-final">Final da oferta</label>
            <input
              id="modal-produto-oferta-final"
              placeholder="dd/mm/aaaa"
              value={form.dataFinalOferta}
              onChange={(e) => setForm((f) => ({ ...f, dataFinalOferta: mascararData(e.target.value) }))}
              onFocus={(e) => e.target.select()}
            />
          </div>
          <div className="col-4">
            <label htmlFor="modal-produto-oferta-preco">Preço de Oferta</label>
            <input
              id="modal-produto-oferta-preco"
              inputMode="decimal"
              placeholder="0,00"
              value={form.precoOferta}
              onChange={(e) => setForm((f) => ({ ...f, precoOferta: mascararMoeda(e.target.value) }))}
              onBlur={() => setForm((f) => (f.precoOferta ? { ...f, precoOferta: completarMoeda(f.precoOferta) } : f))}
            />
          </div>
        </div>

        <div className="form-grid" style={{ marginTop: 8 }}>
          <div className="col-3">
            <label htmlFor="modal-produto-peso-bruto">Peso Bruto (kg)</label>
            <input
              id="modal-produto-peso-bruto"
              inputMode="decimal"
              placeholder="0,000"
              value={form.pesoBruto}
              onChange={(e) => setForm((f) => ({ ...f, pesoBruto: mascararPeso(e.target.value) }))}
              onBlur={() => setForm((f) => ({ ...f, pesoBruto: completarPeso(f.pesoBruto) }))}
            />
          </div>
          <div className="col-3">
            <label htmlFor="modal-produto-peso-liquido">Peso Líquido (kg)</label>
            <input
              id="modal-produto-peso-liquido"
              inputMode="decimal"
              placeholder="0,000"
              value={form.pesoLiquido}
              onChange={(e) => setForm((f) => ({ ...f, pesoLiquido: mascararPeso(e.target.value) }))}
              onBlur={() => setForm((f) => ({ ...f, pesoLiquido: completarPeso(f.pesoLiquido) }))}
            />
          </div>
        </div>

        {usaCorGrade && (
          <>
            <label htmlFor="modal-produto-grade" style={{ marginTop: 8 }}>
              Grade *
            </label>
            <div className="linha-com-botao">
              <select
                id="modal-produto-grade"
                value={form.idGrade ?? ''}
                onChange={(e) => {
                  setForm((f) => ({ ...f, idGrade: e.target.value ? Number(e.target.value) : null }))
                  setIdCor('')
                  setIdTamanho('')
                }}
              >
                <option value="">Selecione…</option>
                {(grades ?? []).map((g) => (
                  <option key={g.idGrade} value={g.idGrade}>
                    {g.descricao}
                  </option>
                ))}
              </select>
              <button type="button" className="btn ghost" onClick={() => setModalGradeAberto(true)}>
                ＋ Nova grade
              </button>
            </div>

            {precisaCorTamanho && (!corAutomatica || !tamanhoAutomatico) && (
              <div className="form-grid" style={{ marginTop: 8 }}>
                {!corAutomatica && (
                  <div className="col-6">
                    <label htmlFor="modal-produto-cor">Cor *</label>
                    <select id="modal-produto-cor" value={idCor} onChange={(e) => setIdCor(e.target.value === '' ? '' : Number(e.target.value))}>
                      <option value="">Selecione…</option>
                      {(cores ?? []).map((c) => (
                        <option key={c.idCor} value={c.idCor}>
                          {c.descricao}
                        </option>
                      ))}
                    </select>
                    <div className="linha-com-botao" style={{ marginTop: 4 }}>
                      <input placeholder="+ Nova cor…" value={novaCorNome} onChange={(e) => setNovaCorNome(maiusculas(e.target.value))} />
                      <button
                        type="button"
                        className="btn ghost"
                        disabled={!novaCorNome.trim() || criarNovaCor.isPending}
                        onClick={() => criarNovaCor.mutate(novaCorNome.trim())}
                      >
                        ＋ Nova cor
                      </button>
                    </div>
                  </div>
                )}
                {!tamanhoAutomatico && (
                  <div className="col-6">
                    <label htmlFor="modal-produto-tamanho">Tamanho *</label>
                    <select id="modal-produto-tamanho" value={idTamanho} onChange={(e) => setIdTamanho(e.target.value === '' ? '' : Number(e.target.value))}>
                      <option value="">Selecione…</option>
                      {(grade?.tamanhos ?? []).map((t) => (
                        <option key={t.idTamanho} value={t.idTamanho}>
                          {t.descricao}
                        </option>
                      ))}
                    </select>
                    <div className="linha-com-botao" style={{ marginTop: 4 }}>
                      <input placeholder="+ Novo tamanho…" value={novoTamanhoNome} onChange={(e) => setNovoTamanhoNome(maiusculas(e.target.value))} />
                      <button
                        type="button"
                        className="btn ghost"
                        disabled={!novoTamanhoNome.trim() || criarNovoTamanho.isPending}
                        onClick={() => criarNovoTamanho.mutate(novoTamanhoNome.trim())}
                      >
                        ＋ Novo tamanho
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </>
        )}

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Cancelar
          </button>
          <button type="button" className="btn" disabled={!valido || criar.isPending} onClick={() => criar.mutate()}>
            {criar.isPending ? 'Criando…' : 'Criar'}
          </button>
        </div>
      </div>

      {modalGradeAberto && (
        <GradeModal
          aoFechar={() => setModalGradeAberto(false)}
          aoSelecionar={(idGradeNova) => {
            setForm((f) => ({ ...f, idGrade: idGradeNova }))
            setIdCor('')
            setIdTamanho('')
            setModalGradeAberto(false)
          }}
        />
      )}

      {modalCategoriaAberto && (
        <CategoriaProdutoModal
          aoFechar={() => setModalCategoriaAberto(false)}
          aoCriar={(idCategoria, nomeCategoria) => {
            setForm((f) => ({ ...f, categorias: [...f.categorias, { idCategoria, nomeCategoria, indice: f.categorias.length }] }))
          }}
        />
      )}

      {modalNcmAberto && (
        <PesquisaNcmModal
          aoFechar={() => setModalNcmAberto(false)}
          aoSelecionar={(ncm) => {
            setForm((f) => ({ ...f, codigoNcm: mascararNcm(ncm.codigoNcm) }))
            setDescricaoNcm(ncm.descricaoNcm)
            setModalNcmAberto(false)
          }}
        />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
