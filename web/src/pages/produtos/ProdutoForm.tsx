import {
  useEffect,
  useState,
  type ChangeEvent,
  type FocusEvent,
  type FormEvent,
  type KeyboardEvent,
} from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import CategoriaProdutoModal from '../../components/CategoriaProdutoModal'
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import GaleriaImagensProduto from '../../components/GaleriaImagensProduto'
import GradeModal from '../../components/GradeModal'
import { IconeEngrenagem, IconeExcluir, IconeLupa, IconeProduto, IconeSetaBaixo, IconeSetaCima } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import LinhaGrid from '../../components/LinhaGrid'
import PesquisaNcmModal from '../../components/PesquisaNcmModal'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarCategoriasProduto } from '../../lib/categoriasProduto'
import { enviarImagem, type ImagemProduto } from '../../lib/produtoImagens'
import { buscarConfiguracaoTela, paraMapa, type ConfiguracaoCampo } from '../../lib/configuracaoTela'
import { buscarUsaCorGrade } from '../../lib/configuracaoGeral'
import { hojeISO } from '../../lib/datas'
import { listarGrades } from '../../lib/grades'
import { useEu } from '../../lib/eu'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import {
  completarMoeda,
  completarPercentual,
  completarPeso,
  dataParaIso,
  dataValida,
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
} from '../../lib/masks'
import { buscarNcm } from '../../lib/ncm'
import { listarOpcoesPerfilFiscal } from '../../lib/perfilFiscal'
import {
  PRODUTO_VAZIO,
  atualizarProduto,
  buscarProduto,
  criarProduto,
  paraFormulario,
  paraRequisicao,
  type ProdutoFormState,
} from '../../lib/produtos'
import { maiusculas } from '../../lib/texto'

const CHAVE_TELA = 'catalogo.produto.form'

/** Campos de texto livre do formulário sujeitos à convenção de maiúsculas do projeto. */
type CampoMaiusculo = 'descricao' | 'marca' | 'referencia'

/** Campos configuráveis pela tela de configuração — mesma lista de `CAMPOS_POR_TELA` no backend. */
type CampoConfiguravel =
  | 'marca'
  | 'referencia'
  | 'codigoNcm'
  | 'pesoBruto'
  | 'pesoLiquido'
  | 'dataInicioOferta'
  | 'dataFinalOferta'
  | 'precoOferta'

type CampoValidavel = 'descricao' | 'precoCusto' | 'percentualVenda' | 'precoVenda' | CampoConfiguravel
type ErrosCampo = Partial<Record<CampoValidavel, string>> & { idGrade?: string }

function campoVisivel(campo: CampoConfiguravel, mapa: Map<string, ConfiguracaoCampo>): boolean {
  return mapa.get(campo)?.visivel ?? true
}
function campoObrigatorio(campo: CampoConfiguravel, mapa: Map<string, ConfiguracaoCampo>): boolean {
  return mapa.get(campo)?.obrigatorio ?? false
}

type ErrosOferta = Record<'dataInicioOferta' | 'dataFinalOferta' | 'precoOferta', string | undefined>

/**
 * Regra da oferta (itens 4-7, pedido do dono do produto): início, final e preço de oferta só
 * são válidos em conjunto — preencheu um, os três viram obrigatórios. Sempre devolve as três
 * chaves (mesmo {@code undefined}) para o merge em `erros` limpar mensagens antigas quando o
 * usuário esvazia os três campos de novo.
 */
function errosOferta(f: ProdutoFormState): ErrosOferta {
  const temInicio = Boolean(f.dataInicioOferta)
  const temFinal = Boolean(f.dataFinalOferta)
  const temPreco = Boolean(f.precoOferta.trim())
  const vazio: ErrosOferta = { dataInicioOferta: undefined, dataFinalOferta: undefined, precoOferta: undefined }
  if (!temInicio && !temFinal && !temPreco) return vazio

  const erros = { ...vazio }
  const obrigatorio = 'Obrigatório para a oferta ser válida.'

  if (!temInicio) {
    erros.dataInicioOferta = obrigatorio
  } else if (!dataValida(f.dataInicioOferta)) {
    erros.dataInicioOferta = 'Data inválida.'
  } else if (dataParaIso(f.dataInicioOferta)! < hojeISO()) {
    erros.dataInicioOferta = 'Data de início da oferta não pode ser no passado.'
  }

  if (!temFinal) {
    erros.dataFinalOferta = obrigatorio
  } else if (!dataValida(f.dataFinalOferta)) {
    erros.dataFinalOferta = 'Data inválida.'
  } else if (temInicio && dataValida(f.dataInicioOferta) && dataParaIso(f.dataFinalOferta)! < dataParaIso(f.dataInicioOferta)!) {
    erros.dataFinalOferta = 'Data final da oferta não pode ser anterior à data de início.'
  }

  if (!temPreco) {
    erros.precoOferta = obrigatorio
  } else if (desmascararMoeda(f.precoOferta) >= desmascararMoeda(f.precoVenda)) {
    erros.precoOferta = 'Preço de oferta deve ser menor que o preço de venda.'
  }

  return erros
}

/** Peso líquido não pode ser maior que o peso bruto (item 2, pedido do dono do produto). */
function erroPesoLiquido(f: ProdutoFormState): string | undefined {
  if (desmascararPeso(f.pesoLiquido) > desmascararPeso(f.pesoBruto)) {
    return 'Peso líquido deve ser menor ou igual ao peso bruto.'
  }
  return undefined
}

function validarCampo(chave: CampoValidavel, f: ProdutoFormState, mapaConfig: Map<string, ConfiguracaoCampo>): string | undefined {
  if (chave === 'descricao') {
    return f.descricao.trim() ? undefined : 'Descrição é obrigatória.'
  }
  if (chave === 'precoCusto') {
    return f.precoCusto.trim() ? undefined : 'Campo obrigatório.'
  }
  if (chave === 'precoVenda') {
    if (!f.precoVenda.trim()) return 'Campo obrigatório.'
    if (desmascararMoeda(f.precoVenda) < desmascararMoeda(f.precoCusto)) return 'Não pode ser menor que o preço de custo.'
    return undefined
  }
  if (chave === 'percentualVenda') {
    return f.percentualVenda.trim() ? undefined : 'Campo obrigatório.'
  }
  const campo = chave as CampoConfiguravel
  if (!campoVisivel(campo, mapaConfig)) return undefined
  const valor = f[campo] as string
  if (!valor.trim()) {
    return campoObrigatorio(campo, mapaConfig) ? 'Campo obrigatório.' : undefined
  }
  return undefined
}

export default function ProdutoForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<ProdutoFormState>(PRODUTO_VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [modalCategoriaAberto, setModalCategoriaAberto] = useState(false)
  const [categoriaParaAdicionar, setCategoriaParaAdicionar] = useState('')
  const [descricaoNcm, setDescricaoNcm] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)
  const [imagens, setImagens] = useState<ImagemProduto[]>([])
  const [arquivosNovaFoto, setArquivosNovaFoto] = useState<File[]>([])
  const [modalGradeAberto, setModalGradeAberto] = useState(false)
  const [modalNcmAberto, setModalNcmAberto] = useState(false)

  /**
   * Busca a descrição do NCM digitado (mesmo estilo do autopreenchimento de CEP). Código que
   * não existe: limpa o campo e avisa, em vez de deixar um código inválido no formulário.
   */
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
      setErros((e) => ({ ...e, codigoNcm: 'Código NCM inválido — não encontrado.' }))
      return
    }
    setDescricaoNcm(ncm.descricaoNcm)
    setErros((e) => ({ ...e, codigoNcm: undefined }))
  }

  const { data: eu } = useEu()
  const ehAdmin = eu?.usuario.papel === 'ADMIN'

  const { data: configuracao } = useQuery({
    queryKey: ['config-tela', CHAVE_TELA],
    queryFn: () => buscarConfiguracaoTela(CHAVE_TELA),
  })
  const mapaConfig = paraMapa(configuracao)

  const { data: usaCorGrade } = useQuery({
    queryKey: ['config-geral', 'usa-cor-grade'],
    queryFn: buscarUsaCorGrade,
  })

  const { data: categorias } = useQuery({
    queryKey: ['categorias-produto'],
    queryFn: listarCategoriasProduto,
  })

  const { data: grades } = useQuery({
    queryKey: ['grades'],
    queryFn: listarGrades,
    enabled: Boolean(usaCorGrade?.cfgUsaCorGrade),
  })

  const { data: perfisFiscais } = useQuery({
    queryKey: ['perfis-fiscais-opcoes'],
    queryFn: listarOpcoesPerfilFiscal,
  })
  const categoriasDisponiveis = (categorias ?? []).filter(
    (c) => !form.categorias.some((fc) => fc.idCategoria === c.idCategoria),
  )

  const { data: produtoExistente } = useQuery({
    queryKey: ['produto', id],
    queryFn: () => buscarProduto(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (produtoExistente) {
      setForm(paraFormulario(produtoExistente))
      setImagens(produtoExistente.imagens)
      buscarDescricaoDoNcm(produtoExistente.codigoNcm ?? '')
    }
  }, [produtoExistente])

  const salvar = useMutation({
    mutationFn: () =>
      editando ? atualizarProduto(Number(id), paraRequisicao(form)) : criarProduto(paraRequisicao(form)),
    onSuccess: async (produtoSalvo) => {
      queryClient.invalidateQueries({ queryKey: ['produtos'] })

      if (!editando && arquivosNovaFoto.length > 0) {
        // ⚠️ Conta o que subiu e NOMEIA o que faltou (achado de auditoria, 2026-08-21). O laço
        // parava na primeira falha e avisava só "houve um problema ao enviar as fotos" — mas a
        // navegação seguinte desmonta o formulário e leva junto `arquivosNovaFoto`, então os
        // arquivos que não subiram somem da memória do app. O usuário caía na tela de edição vendo
        // 2 de 5 fotos, sem saber quantas faltaram nem quais, tendo de lembrar de cabeça quais
        // arquivos do disco eram os que faltavam — e em que ordem, porque a primeira foto é a CAPA
        // que o PDV e a etiqueta usam.
        let enviadas = 0
        try {
          for (const arquivo of arquivosNovaFoto) {
            await enviarImagem(produtoSalvo.idProduto, arquivo)
            enviadas += 1
          }
        } catch (e: unknown) {
          const faltando = arquivosNovaFoto.slice(enviadas).map((a) => a.name).join(', ')
          const motivo = e instanceof ApiError ? e.message : 'falha ao enviar'
          setToast(
            `Produto salvo. Enviadas ${enviadas} de ${arquivosNovaFoto.length} fotos (${motivo}).`
              + ` Reenvie por aqui: ${faltando}`,
          )
          navigate(`/produtos/${produtoSalvo.idProduto}`)
          return
        }
      }
      navigate('/produtos', {
        state: {
          toast: {
            texto: editando ? 'Produto atualizado com sucesso.' : 'Produto cadastrado com sucesso.',
            tipo: 'sucesso',
          },
        },
      })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar o produto.'),
  })

  /** onChange de campo de texto livre — sempre maiúsculas, não importa o teclado. */
  const campo = (chave: CampoMaiusculo) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form, mapaConfig) }))

  /** Reavalia a regra da oferta (itens 4-7) — chamado ao sair de qualquer um dos 3 campos. */
  const revalidarOferta = () => setErros((atual) => ({ ...atual, ...errosOferta(form) }))

  /** Preço de venda não pode ficar abaixo do preço de custo (2026-08-12, regra do projeto
   *  inteiro) — chamado ao sair de custo, % de venda ou do próprio preço de venda, já que
   *  qualquer um dos três pode mudar a relação entre eles. */
  const revalidarPrecoVenda = () => setErros((atual) => ({ ...atual, precoVenda: validarCampo('precoVenda', form, mapaConfig) }))

  /** Reavalia peso líquido ≤ peso bruto — chamado ao sair de qualquer um dos dois campos. */
  const revalidarPeso = () => setErros((atual) => ({ ...atual, pesoLiquido: erroPesoLiquido(form) }))

  /**
   * Preço de venda = custo × (1 + %/100) — recalculado a cada tecla em custo ou % de venda
   * (itens 2/3, pedido do dono do produto). Sem custo (ainda) não há base para calcular, o
   * preço de venda fica como está até o custo ser preenchido.
   */
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

  /**
   * Editar o preço de venda direto recalcula o % de venda a partir do custo (item 3) — só
   * quando há custo informado; sem custo não dá pra derivar um percentual de markup.
   */
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

  const removerCategoria = (idCategoria: number) =>
    setForm((f) => ({ ...f, categorias: f.categorias.filter((c) => c.idCategoria !== idCategoria) }))

  const moverCategoria = (indice: number, direcao: -1 | 1) =>
    setForm((f) => {
      const alvo = indice + direcao
      if (alvo < 0 || alvo >= f.categorias.length) return f
      const lista = [...f.categorias]
      ;[lista[indice], lista[alvo]] = [lista[alvo], lista[indice]]
      return { ...f, categorias: lista }
    })

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const ofertaErros = errosOferta(form)

    const novosErros: ErrosCampo = {
      descricao: validarCampo('descricao', form, mapaConfig),
      precoCusto: validarCampo('precoCusto', form, mapaConfig),
      percentualVenda: validarCampo('percentualVenda', form, mapaConfig),
      precoVenda: validarCampo('precoVenda', form, mapaConfig),
      marca: validarCampo('marca', form, mapaConfig),
      referencia: validarCampo('referencia', form, mapaConfig),
      codigoNcm: validarCampo('codigoNcm', form, mapaConfig),
      pesoBruto: validarCampo('pesoBruto', form, mapaConfig),
      pesoLiquido: erroPesoLiquido(form) ?? validarCampo('pesoLiquido', form, mapaConfig),
      dataInicioOferta: ofertaErros.dataInicioOferta,
      dataFinalOferta: ofertaErros.dataFinalOferta,
      precoOferta: ofertaErros.precoOferta,
      idGrade: usaCorGrade?.cfgUsaCorGrade && !form.idGrade ? 'Grade é obrigatória.' : undefined,
    }
    setErros(novosErros)
    if (Object.values(novosErros).some(Boolean)) {
      setToast('Corrija os campos destacados antes de salvar.')
      return
    }
    salvar.mutate()
  }

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    validarEEnviar()
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeProduto size={34} />
            <h1>Produto</h1>
          </div>
          <div className="topbar-acoes">
            {ehAdmin && (
              <Link
                className="btn ghost ajuda-gatilho"
                to="/produtos/configuracao"
                aria-label="Configurar tela de produto"
                title="Configurar campos desta tela"
              >
                <IconeEngrenagem />
              </Link>
            )}
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
            {!somenteLeitura && (
              <button type="submit" form="form-produto" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form
        id="form-produto"
        className="card form-secoes form-secoes-larga"
        onSubmit={submeter}
        onKeyDown={(e: KeyboardEvent<HTMLFormElement>) => {
          if (!somenteLeitura) aoTeclarEnterNoFormulario(e, () => setConfirmarSalvarAberto(true))
        }}
        noValidate
      >
      <fieldset disabled={somenteLeitura} className="form-fieldset">
        <section className="section">
          <p className="section-label">Identificação</p>

          <div className="identificacao-linha">
            <label className="checkbox-linha" style={{ marginTop: 0 }}>
              <input
                type="checkbox"
                checked={form.ativo}
                onChange={(e) => setForm((f) => ({ ...f, ativo: e.target.checked }))}
              />
              Produto ativo
            </label>
          </div>

          <div className="form-grid">
            <LinhaGrid
              itens={[
                {
                  visivel: true,
                  peso: 8,
                  children: (
                    <>
                      <label htmlFor="descricao">Descrição *</label>
                      <input
                        id="descricao"
                        autoFocus
                        value={form.descricao}
                        onChange={campo('descricao')}
                        onBlur={aoSairDoCampo('descricao')}
                      />
                      {erros.descricao && <p className="erro-campo">{erros.descricao}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('marca', mapaConfig),
                  peso: 4,
                  children: (
                    <>
                      <label htmlFor="marca">Marca{campoObrigatorio('marca', mapaConfig) && ' *'}</label>
                      <input id="marca" value={form.marca} onChange={campo('marca')} onBlur={aoSairDoCampo('marca')} />
                      {erros.marca && <p className="erro-campo">{erros.marca}</p>}
                    </>
                  ),
                },
              ]}
            />

            <LinhaGrid
              itens={[
                {
                  visivel: campoVisivel('referencia', mapaConfig),
                  peso: 4,
                  children: (
                    <>
                      <label htmlFor="referencia">
                        Referência{campoObrigatorio('referencia', mapaConfig) && ' *'}
                      </label>
                      <input
                        id="referencia"
                        value={form.referencia}
                        onChange={campo('referencia')}
                        onBlur={aoSairDoCampo('referencia')}
                      />
                      {erros.referencia && <p className="erro-campo">{erros.referencia}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('codigoNcm', mapaConfig),
                  peso: 2,
                  children: (
                    <>
                      <label htmlFor="codigoNcm">
                        NCM{campoObrigatorio('codigoNcm', mapaConfig) && ' *'}
                      </label>
                      <div className="linha-com-botao">
                        <input
                          id="codigoNcm"
                          placeholder="9999.99.99"
                          value={form.codigoNcm}
                          onChange={(e) => setForm((f) => ({ ...f, codigoNcm: mascararNcm(e.target.value) }))}
                          onBlur={(e) => {
                            aoSairDoCampo('codigoNcm')(e)
                            buscarDescricaoDoNcm(somenteDigitos(e.target.value))
                          }}
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
                      {erros.codigoNcm && <p className="erro-campo">{erros.codigoNcm}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('codigoNcm', mapaConfig),
                  peso: 6,
                  children: (
                    <>
                      <label htmlFor="descricaoNcm">NCM - Nomenclatura Comum do Mercosul</label>
                      <input
                        id="descricaoNcm"
                        className="campo-leitura"
                        readOnly
                        tabIndex={-1}
                        placeholder="Digite um código NCM cadastrado para ver a descrição…"
                        value={descricaoNcm}
                      />
                    </>
                  ),
                },
              ]}
            />
          </div>
        </section>

        <section className="section">
          <p className="section-label">Fiscal</p>
          <p className="muted" style={{ marginTop: -4 }}>
            Usado para calcular os tributos na nota fiscal. Opcional aqui — a tela de Conformidade
            Fiscal aponta quando falta antes de emitir.
          </p>

          <div className="form-grid">
            <div className="col-6">
              <label htmlFor="idPerfilFiscal">Perfil Fiscal</label>
              <select
                id="idPerfilFiscal"
                value={form.idPerfilFiscal ?? ''}
                onChange={(e) => setForm((f) => ({ ...f, idPerfilFiscal: e.target.value ? Number(e.target.value) : null }))}
              >
                <option value="">Não informado</option>
                {(perfisFiscais ?? []).map((p) => (
                  <option key={p.idPerfilFiscal} value={p.idPerfilFiscal}>
                    {p.nome}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Categorias</p>

          {form.categorias.length > 0 && (
            <ul className="lista-categorias-produto">
              {form.categorias.map((c, i) => (
                <li key={c.idCategoria}>
                  <span>{c.nomeCategoria}</span>
                  <div className="topbar-acoes">
                    <button
                      type="button"
                      className="btn ghost"
                      tabIndex={-1}
                      disabled={i === 0}
                      onClick={() => moverCategoria(i, -1)}
                      aria-label={`Mover ${c.nomeCategoria} para cima`}
                      title="Mover para cima"
                    >
                      <IconeSetaCima />
                    </button>
                    <button
                      type="button"
                      className="btn ghost"
                      tabIndex={-1}
                      disabled={i === form.categorias.length - 1}
                      onClick={() => moverCategoria(i, 1)}
                      aria-label={`Mover ${c.nomeCategoria} para baixo`}
                      title="Mover para baixo"
                    >
                      <IconeSetaBaixo />
                    </button>
                    <button
                      type="button"
                      className="btn ghost"
                      tabIndex={-1}
                      onClick={() => removerCategoria(c.idCategoria)}
                      aria-label={`Remover ${c.nomeCategoria}`}
                      title="Remover"
                    >
                      <IconeExcluir />
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}

          <div className="form-grid">
            <div className="col-8">
              <label htmlFor="categoriaParaAdicionar">Adicionar categoria</label>
              <div className="linha-com-botao">
                <select
                  id="categoriaParaAdicionar"
                  value={categoriaParaAdicionar}
                  onChange={(e) => setCategoriaParaAdicionar(e.target.value)}
                >
                  <option value="">Selecione…</option>
                  {categoriasDisponiveis.map((c) => (
                    <option key={c.idCategoria} value={c.idCategoria}>
                      {c.nomeCategoria}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  tabIndex={-1}
                  className="btn ghost"
                  disabled={!categoriaParaAdicionar}
                  onClick={adicionarCategoria}
                >
                  ＋ Adicionar
                </button>
                <button
                  type="button"
                  tabIndex={-1}
                  className="btn ghost"
                  onClick={() => setModalCategoriaAberto(true)}
                >
                  ＋ Gerenciar categorias
                </button>
              </div>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Preços</p>

          <div className="form-grid">
            <LinhaGrid
              itens={[
                {
                  visivel: true,
                  peso: 2,
                  children: (
                    <>
                      <label htmlFor="precoCusto">Preço de Custo (R$) *</label>
                      <input
                        id="precoCusto"
                        inputMode="decimal"
                        placeholder="0,00"
                        value={form.precoCusto}
                        onChange={aoMudarPrecoCusto}
                        onBlur={(e) => {
                          setForm((f) => ({ ...f, precoCusto: completarMoeda(f.precoCusto) }))
                          aoSairDoCampo('precoCusto')(e)
                          revalidarPrecoVenda()
                        }}
                      />
                      {erros.precoCusto && <p className="erro-campo">{erros.precoCusto}</p>}
                    </>
                  ),
                },
                {
                  visivel: true,
                  peso: 2,
                  children: (
                    <>
                      <label htmlFor="percentualVenda">% de Venda *</label>
                      <input
                        id="percentualVenda"
                        inputMode="decimal"
                        placeholder="0,00"
                        value={form.percentualVenda}
                        onChange={aoMudarPercentualVenda}
                        onBlur={(e) => {
                          setForm((f) => ({ ...f, percentualVenda: completarPercentual(f.percentualVenda) }))
                          aoSairDoCampo('percentualVenda')(e)
                          revalidarPrecoVenda()
                        }}
                      />
                      {erros.percentualVenda && <p className="erro-campo">{erros.percentualVenda}</p>}
                    </>
                  ),
                },
                {
                  visivel: true,
                  peso: 2,
                  children: (
                    <>
                      <label htmlFor="precoVenda">Preço de Venda (R$) *</label>
                      <input
                        id="precoVenda"
                        inputMode="decimal"
                        placeholder="0,00"
                        value={form.precoVenda}
                        onChange={aoMudarPrecoVenda}
                        onBlur={(e) => {
                          setForm((f) => ({ ...f, precoVenda: completarMoeda(f.precoVenda) }))
                          aoSairDoCampo('precoVenda')(e)
                          revalidarOferta()
                        }}
                      />
                      {erros.precoVenda && <p className="erro-campo">{erros.precoVenda}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('dataInicioOferta', mapaConfig),
                  peso: 2,
                  children: (
                    <>
                      <label htmlFor="dataInicioOferta">
                        Início da oferta{campoObrigatorio('dataInicioOferta', mapaConfig) && ' *'}
                      </label>
                      <input
                        id="dataInicioOferta"
                        placeholder="dd/mm/aaaa"
                        value={form.dataInicioOferta}
                        onChange={(e) => setForm((f) => ({ ...f, dataInicioOferta: mascararData(e.target.value) }))}
                        onFocus={(e) => e.target.select()}
                        onBlur={revalidarOferta}
                      />
                      {erros.dataInicioOferta && <p className="erro-campo">{erros.dataInicioOferta}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('dataFinalOferta', mapaConfig),
                  peso: 2,
                  children: (
                    <>
                      <label htmlFor="dataFinalOferta">
                        Final da oferta{campoObrigatorio('dataFinalOferta', mapaConfig) && ' *'}
                      </label>
                      <input
                        id="dataFinalOferta"
                        placeholder="dd/mm/aaaa"
                        value={form.dataFinalOferta}
                        onChange={(e) => setForm((f) => ({ ...f, dataFinalOferta: mascararData(e.target.value) }))}
                        onFocus={(e) => e.target.select()}
                        onBlur={revalidarOferta}
                      />
                      {erros.dataFinalOferta && <p className="erro-campo">{erros.dataFinalOferta}</p>}
                    </>
                  ),
                },
                {
                  visivel: campoVisivel('precoOferta', mapaConfig),
                  peso: 2,
                  children: (
                    <>
                      <label htmlFor="precoOferta">
                        Preço de Oferta (R$){campoObrigatorio('precoOferta', mapaConfig) && ' *'}
                      </label>
                      <input
                        id="precoOferta"
                        inputMode="decimal"
                        placeholder="0,00"
                        value={form.precoOferta}
                        onChange={(e) => setForm((f) => ({ ...f, precoOferta: mascararMoeda(e.target.value) }))}
                        onBlur={() => {
                          setForm((f) => (f.precoOferta ? { ...f, precoOferta: completarMoeda(f.precoOferta) } : f))
                          revalidarOferta()
                        }}
                      />
                      {erros.precoOferta && <p className="erro-campo">{erros.precoOferta}</p>}
                    </>
                  ),
                },
              ]}
            />
          </div>
        </section>

        {(campoVisivel('pesoBruto', mapaConfig) || campoVisivel('pesoLiquido', mapaConfig)
          || usaCorGrade?.cfgUsaCorGrade) && (
          <section className="section">
            <p className="section-label">Dimensões e Grade</p>
            {usaCorGrade?.cfgUsaCorGrade && (
              <p className="muted" style={{ marginTop: 0 }}>
                Grade de tamanhos deste produto — a cor de cada variação é definida depois, na
                Entrada de Produtos.
              </p>
            )}

            <div className="form-grid">
              <LinhaGrid
                itens={[
                  {
                    visivel: Boolean(usaCorGrade?.cfgUsaCorGrade),
                    peso: 4,
                    children: (
                      <>
                        <label htmlFor="idGrade">Grade *</label>
                        <div className="linha-com-botao">
                          <select
                            id="idGrade"
                            value={form.idGrade ?? ''}
                            onChange={(e) =>
                              setForm((f) => ({ ...f, idGrade: e.target.value ? Number(e.target.value) : null }))
                            }
                            onBlur={() =>
                              setErros((atual) => ({
                                ...atual,
                                idGrade: usaCorGrade?.cfgUsaCorGrade && !form.idGrade ? 'Grade é obrigatória.' : undefined,
                              }))
                            }
                          >
                            <option value="">Selecione…</option>
                            {form.idGrade && !grades?.some((g) => g.idGrade === form.idGrade) && (
                              <option value={form.idGrade}>{form.descricaoGrade}</option>
                            )}
                            {grades?.map((g) => (
                              <option key={g.idGrade} value={g.idGrade}>
                                {g.descricao}
                              </option>
                            ))}
                          </select>
                          <button type="button" tabIndex={-1} className="btn ghost" onClick={() => setModalGradeAberto(true)}>
                            ＋ Gerenciar Grades
                          </button>
                        </div>
                        {erros.idGrade && <p className="erro-campo">{erros.idGrade}</p>}
                      </>
                    ),
                  },
                  {
                    visivel: campoVisivel('pesoBruto', mapaConfig),
                    peso: 3,
                    children: (
                      <>
                        <label htmlFor="pesoBruto">
                          Peso Bruto (kg){campoObrigatorio('pesoBruto', mapaConfig) && ' *'}
                        </label>
                        <input
                          id="pesoBruto"
                          inputMode="decimal"
                          placeholder="0,000"
                          value={form.pesoBruto}
                          onChange={(e) => setForm((f) => ({ ...f, pesoBruto: mascararPeso(e.target.value) }))}
                          onBlur={(e) => {
                            setForm((f) => ({ ...f, pesoBruto: completarPeso(f.pesoBruto) }))
                            aoSairDoCampo('pesoBruto')(e)
                            revalidarPeso()
                          }}
                        />
                        {erros.pesoBruto && <p className="erro-campo">{erros.pesoBruto}</p>}
                      </>
                    ),
                  },
                  {
                    visivel: campoVisivel('pesoLiquido', mapaConfig),
                    peso: 3,
                    children: (
                      <>
                        <label htmlFor="pesoLiquido">
                          Peso Líquido (kg){campoObrigatorio('pesoLiquido', mapaConfig) && ' *'}
                        </label>
                        <input
                          id="pesoLiquido"
                          inputMode="decimal"
                          placeholder="0,000"
                          value={form.pesoLiquido}
                          onChange={(e) => setForm((f) => ({ ...f, pesoLiquido: mascararPeso(e.target.value) }))}
                          onBlur={(e) => {
                            setForm((f) => ({ ...f, pesoLiquido: completarPeso(f.pesoLiquido) }))
                            aoSairDoCampo('pesoLiquido')(e)
                            revalidarPeso()
                          }}
                        />
                        {erros.pesoLiquido && <p className="erro-campo">{erros.pesoLiquido}</p>}
                      </>
                    ),
                  },
                ]}
              />
            </div>
          </section>
        )}

        <GaleriaImagensProduto
          idProduto={editando ? Number(id) : undefined}
          imagens={imagens}
          arquivosLocais={arquivosNovaFoto}
          aoMudarArquivosLocais={setArquivosNovaFoto}
          somenteLeitura={somenteLeitura}
          aoAtualizar={setImagens}
        />

        <InfoRegistro
          codigo={produtoExistente?.idProduto}
          criadoEm={produtoExistente?.criadoEm}
          atualizadoEm={produtoExistente?.atualizadoEm}
        />
      </fieldset>
      </form>
      </div>

      {modalCategoriaAberto && (
        <CategoriaProdutoModal
          aoFechar={() => setModalCategoriaAberto(false)}
          aoCriar={(idCategoria, nomeCategoria) => {
            setForm((f) => ({
              ...f,
              categorias: [...f.categorias, { idCategoria, nomeCategoria, indice: f.categorias.length }],
            }))
          }}
        />
      )}

      {modalGradeAberto && (
        <GradeModal
          aoFechar={() => setModalGradeAberto(false)}
          aoSelecionar={(idGrade, descricaoGrade) => {
            setForm((f) => ({ ...f, idGrade, descricaoGrade }))
            setErros((e) => ({ ...e, idGrade: undefined }))
          }}
        />
      )}

      {modalNcmAberto && (
        <PesquisaNcmModal
          aoFechar={() => setModalNcmAberto(false)}
          aoSelecionar={(ncm) => {
            setForm((f) => ({ ...f, codigoNcm: mascararNcm(ncm.codigoNcm) }))
            setDescricaoNcm(ncm.descricaoNcm)
            setErros((e) => ({ ...e, codigoNcm: undefined }))
            setModalNcmAberto(false)
          }}
        />
      )}

      {confirmarSalvarAberto && (
        <ConfirmarSalvarModal
          aoConfirmar={() => {
            setConfirmarSalvarAberto(false)
            validarEEnviar()
          }}
          aoCancelar={() => setConfirmarSalvarAberto(false)}
        />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
