import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type ChangeEvent, type FocusEvent, type FormEvent, type KeyboardEvent } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import ConfirmarSalvarModal from '../../components/ConfirmarSalvarModal'
import { IconeEtiqueta } from '../../components/Icones'
import InfoRegistro from '../../components/InfoRegistro'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  ETIQUETA_CONFIG_VAZIA,
  MM_PARA_PX_IMPRESSAO,
  atualizarEtiquetaConfig,
  buscarEtiquetaConfig,
  criarEtiquetaConfig,
  linhasParaImprimir,
  paraFormulario,
  paraRequisicao,
  type CampoEtiquetaPosicionado,
  type ColunaEtiqueta,
  type EtiquetaConfigFormState,
  type ProdutoExemplo,
} from '../../lib/etiquetaConfig'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { completarEtiquetaMm, desmascararEtiquetaMm, formatarEtiquetaMm, mascararEtiquetaMm } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'
import CampoEtiquetaVisual from './CampoEtiquetaVisual'
import EditorEtiquetaCanvas from './EditorEtiquetaCanvas'
import ProdutoExemploModal from './ProdutoExemploModal'
import TesteImpressaoModal from './TesteImpressaoModal'
import type { EstadoListaEtiquetaConfig } from './EtiquetaConfigLista'

type CampoValidavel = 'nome' | 'larguraRoloMm' | 'larguraEtiquetaMm' | 'alturaEtiquetaMm'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

const OPCOES_NUMERO_COLUNAS = [1, 2, 3, 4]

function validarCampo(chave: CampoValidavel, f: EtiquetaConfigFormState): string | undefined {
  if (chave === 'nome') return f.nome.trim() ? undefined : 'Nome é obrigatório.'
  const valor = f[chave]
  if (!valor.trim()) return 'Campo obrigatório.'
  if (desmascararEtiquetaMm(valor) <= 0) return 'Deve ser maior que zero.'
  return undefined
}

/** Sugestão de posição pra uma coluna nova — última coluna + largura da etiqueta (sem "vão"
 * embutido, é só ponto de partida editável, não fórmula obrigatória — ver
 * docs/telas/configuracao-etiqueta.md). */
function proximaPosicaoSugerida(colunasAtuais: ColunaEtiqueta[], larguraEtiquetaMm: number): number {
  if (colunasAtuais.length === 0) return 0
  const ultima = colunasAtuais[colunasAtuais.length - 1]
  return Math.round((ultima.posicaoInicialMm + larguraEtiquetaMm) * 100) / 100
}

/**
 * Configuração de Etiqueta de Produtos (2026-08-04, docs/telas/configuracao-etiqueta.md) —
 * cabeçalho digitável (rolo/etiqueta/bordas) + editor visual de arraste (`EditorEtiquetaCanvas`)
 * pra posicionar os campos impressos. ADMIN-only (grupo Configurações do menu).
 */
export default function EtiquetaConfigForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const listaEstado = (location.state as { listaEstado?: EstadoListaEtiquetaConfig } | null)?.listaEstado

  const [form, setForm] = useState<EtiquetaConfigFormState>(ETIQUETA_CONFIG_VAZIA)
  /**
   * Como as colunas são informadas.
   *
   * <p>⚠️ Até 2026-08-20 a tela pedia a **posição** de cada coluna (horizontal) e nada na vertical
   * — duas formas de pensar a mesma medida física, e o dono do produto tropeçou nisso na primeira
   * leitura: *"o espaço horizontal e o vertical eu tenho que informar, ou só informo a posição
   * onde cada etiqueta começa?"*. Hoje a resposta é uma só: **informa-se sempre o espaço em
   * branco**, nas duas direções, e as posições são calculadas.
   *
   * <p>O modo manual continua existindo porque rolo com espaçamento irregular existe — mas é
   * exceção, e a tela entra nele sozinha ao abrir um modelo cujas posições não seguem um passo
   * constante (senão, abrir um modelo antigo silenciosamente recalcularia as posições dele).
   */
  const [colunasManuais, setColunasManuais] = useState(false)

  /**
   * ⚠️ Trava contra uma armadilha de ORDEM DE EFEITOS: o recálculo automático das posições é
   * declarado depois da dedução, mas os dois rodam no MESMO commit — e o recálculo leria
   * `margemColunas`/`espacoColunas` ainda vazios (= 0) e `colunasManuais` ainda `false`. Num rolo
   * regular isso se auto-corrige no render seguinte; num rolo IRREGULAR, não: as posições já
   * teriam sido sobrescritas antes de o modo manual ligar, estragando em silêncio um modelo que
   * funcionava. Enquanto a dedução não terminar, ninguém recalcula nada.
   */
  const [medidasDeduzidas, setMedidasDeduzidas] = useState(!editando)
  const [margemColunas, setMargemColunas] = useState('')
  const [espacoColunas, setEspacoColunas] = useState('')
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)
  const [produtoExemplo, setProdutoExemplo] = useState<ProdutoExemplo | null>(null)
  const [buscaProdutoAberta, setBuscaProdutoAberta] = useState(false)
  const [testeImpressaoAberto, setTesteImpressaoAberto] = useState(false)
  const [quantidadeImprimir, setQuantidadeImprimir] = useState(0)

  const { data: configExistente } = useQuery({
    queryKey: ['etiqueta-config', id],
    queryFn: () => buscarEtiquetaConfig(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (configExistente) setForm(paraFormulario(configExistente))
  }, [configExistente])

  /**
   * Ao abrir um modelo já salvo, deduz margem e espaço a partir das posições gravadas — e cai no
   * modo manual quando elas NÃO seguem um passo constante.
   *
   * <p>⚠️ Sem essa dedução, abrir um modelo antigo recalcularia as posições dele em silêncio (os
   * campos de margem/espaço nasceriam vazios = 0) e o operador só descobriria imprimindo. O modo
   * manual não é um recurso avançado: é o que impede a tela de estragar um rolo irregular que já
   * estava funcionando.
   */
  useEffect(() => {
    if (!configExistente) return
    const colunas = [...configExistente.colunas].sort((a, b) => a.numeroColuna - b.numeroColuna)
    const largura = configExistente.larguraEtiquetaMm
    const margem = colunas[0]?.posicaoInicialMm ?? 0
    const espaco = colunas.length > 1 ? colunas[1].posicaoInicialMm - colunas[0].posicaoInicialMm - largura : 0
    const regular = colunas.every(
      (c, i) => Math.abs(c.posicaoInicialMm - (margem + i * (largura + espaco))) < 0.01,
    )
    setColunasManuais(!regular || espaco < 0)
    setMargemColunas(formatarEtiquetaMm(margem))
    setEspacoColunas(formatarEtiquetaMm(Math.max(espaco, 0)))
    setMedidasDeduzidas(true)
  }, [configExistente])

  const podeImprimir =
    form.colunas.length > 0 &&
    form.campos.length > 0 &&
    desmascararEtiquetaMm(form.larguraRoloMm) > 0 &&
    desmascararEtiquetaMm(form.larguraEtiquetaMm) > 0 &&
    desmascararEtiquetaMm(form.alturaEtiquetaMm) > 0

  /** Dispara o diálogo de impressão do navegador quando `quantidadeImprimir` vira > 0 — o
   * tamanho da página (`@page`, em mm) precisa ser injetado aqui porque a largura do rolo é
   * configurável por tenant, diferente do 80mm fixo do Comprovante de Crediário/A4 do Fechamento
   * de Caixa (styles.css). Removido de novo em `afterprint` (fecha o diálogo do SO) e no cleanup
   * (usuário troca a quantidade de novo ou sai da tela no meio do caminho). */
  useEffect(() => {
    if (quantidadeImprimir <= 0) return
    const larguraRoloMm = desmascararEtiquetaMm(form.larguraRoloMm) || 0
    const estilo = document.createElement('style')
    estilo.textContent = `@page etiqueta-teste-impressao { size: ${larguraRoloMm}mm auto; margin: 0; }`
    document.head.appendChild(estilo)
    const aoTerminarImpressao = () => setQuantidadeImprimir(0)
    window.addEventListener('afterprint', aoTerminarImpressao)
    const temporizador = window.setTimeout(() => window.print(), 60)
    return () => {
      window.clearTimeout(temporizador)
      window.removeEventListener('afterprint', aoTerminarImpressao)
      estilo.remove()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quantidadeImprimir])

  const salvar = useMutation({
    mutationFn: () =>
      editando
        ? atualizarEtiquetaConfig(Number(id), paraRequisicao(form))
        : criarEtiquetaConfig(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['etiquetas-config'] })
      navigate('/etiqueta-configuracao', {
        state: {
          toast: {
            texto: editando ? 'Configuração de etiqueta atualizada com sucesso.' : 'Configuração de etiqueta cadastrada com sucesso.',
            tipo: 'sucesso',
          },
          listaEstado,
        },
      })
    },
    onError: (e: unknown) =>
      setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar a configuração de etiqueta.'),
  })

  const campoTexto = (chave: 'nome') => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: maiusculas(e.target.value) }))

  type CampoMmChave = 'larguraRoloMm' | 'larguraEtiquetaMm' | 'alturaEtiquetaMm' | 'espacamentoVerticalMm' | 'bordaSuperiorMm' | 'bordaInferiorMm' | 'bordaEsquerdaMm' | 'bordaDireitaMm'

  const campoMm = (chave: CampoMmChave) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [chave]: mascararEtiquetaMm(e.target.value) }))

  /** Completa a máscara ao sair do campo e, pros 3 campos obrigatórios, valida também (mesmo
   * padrão de `aoSairDoCampo`, só que combinado — todo campo de mm precisa do `completar`). */
  const aoSairDoCampoMm = (chave: CampoMmChave) => () => {
    setForm((f) => ({ ...f, [chave]: completarEtiquetaMm(f[chave]) }))
    if (chave === 'larguraRoloMm' || chave === 'larguraEtiquetaMm' || chave === 'alturaEtiquetaMm') {
      setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))
    }
  }

  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))

  const aoMudarNumeroColunas = (e: ChangeEvent<HTMLSelectElement>) => {
    const novoNumero = Number(e.target.value)
    setForm((f) => {
      const larguraEtiquetaAtual = desmascararEtiquetaMm(f.larguraEtiquetaMm)
      let novasColunas = f.colunas
      if (novoNumero > f.colunas.length) {
        novasColunas = [...f.colunas]
        for (let n = f.colunas.length + 1; n <= novoNumero; n++) {
          novasColunas.push({ numeroColuna: n, posicaoInicialMm: proximaPosicaoSugerida(novasColunas, larguraEtiquetaAtual) })
        }
      } else if (novoNumero < f.colunas.length) {
        novasColunas = f.colunas.slice(0, novoNumero)
      }
      return { ...f, numeroColunas: novoNumero, colunas: novasColunas }
    })
  }

  const aoMudarPosicaoColuna = (numeroColuna: number, texto: string) => {
    setForm((f) => ({
      ...f,
      colunas: f.colunas.map((c) => (c.numeroColuna === numeroColuna ? { ...c, posicaoInicialMm: desmascararEtiquetaMm(texto) || 0 } : c)),
    }))
  }

  /**
   * Recalcula a posição de cada coluna a partir de margem + espaço + largura da etiqueta, sempre
   * que qualquer um dos três muda — e só no modo automático.
   *
   * <p>É por isto que o operador não digita posição nenhuma no caso normal: com 3 colunas de
   * 34 mm, margem 3 e espaço 6, as posições saem 3 · 43 · 83. Digitadas à mão, o erro **se
   * acumula** — informar 38 de passo onde o rolo tem 40 põe a terceira coluna 4 mm fora, o
   * bastante para cortar o texto (foi exatamente o que apareceu na etiqueta impressa).
   */
  useEffect(() => {
    if (colunasManuais || !medidasDeduzidas) return
    const margem = desmascararEtiquetaMm(margemColunas) || 0
    const espaco = desmascararEtiquetaMm(espacoColunas) || 0
    const largura = desmascararEtiquetaMm(form.larguraEtiquetaMm) || 0
    setForm((f) => {
      const novas = f.colunas.map((c) => ({
        ...c,
        posicaoInicialMm: Number((margem + (c.numeroColuna - 1) * (largura + espaco)).toFixed(2)),
      }))
      const igual = novas.every((c, i) => c.posicaoInicialMm === f.colunas[i].posicaoInicialMm)
      return igual ? f : { ...f, colunas: novas }
    })
  }, [colunasManuais, medidasDeduzidas, margemColunas, espacoColunas, form.larguraEtiquetaMm, form.numeroColunas])

  const larguraRoloNumero = desmascararEtiquetaMm(form.larguraRoloMm) || 0
  const larguraEtiquetaNumero = desmascararEtiquetaMm(form.larguraEtiquetaMm) || 0

  /** Colunas cujo fim passa da largura do rolo — o excedente simplesmente não é impresso, e isso
   *  é invisível até a etiqueta sair da impressora. */
  const colunasQueEstouram =
    larguraRoloNumero > 0 && larguraEtiquetaNumero > 0
      ? form.colunas
          .filter((c) => c.posicaoInicialMm + larguraEtiquetaNumero > larguraRoloNumero + 0.001)
          .map((c) => c.numeroColuna)
      : []

  /** Colunas que começam antes de a anterior terminar. */
  const colunasSobrepostas = (() => {
    if (larguraEtiquetaNumero <= 0) return []
    const ordenadas = [...form.colunas].sort((a, b) => a.numeroColuna - b.numeroColuna)
    return ordenadas
      .filter((c, i) => i > 0 && c.posicaoInicialMm < ordenadas[i - 1].posicaoInicialMm + larguraEtiquetaNumero - 0.001)
      .map((c) => c.numeroColuna)
  })()

  const aoMudarCampos = (atualizar: (campos: CampoEtiquetaPosicionado[]) => CampoEtiquetaPosicionado[]) =>
    setForm((f) => ({ ...f, campos: atualizar(f.campos) }))

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      nome: validarCampo('nome', form),
      larguraRoloMm: validarCampo('larguraRoloMm', form),
      larguraEtiquetaMm: validarCampo('larguraEtiquetaMm', form),
      alturaEtiquetaMm: validarCampo('alturaEtiquetaMm', form),
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
            <IconeEtiqueta size={34} />
            <h1>Configuração de Etiqueta de Produtos</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="configuracoes.etiquetaconfig.form" />
            <button
              type="button"
              className="btn ghost"
              disabled={!podeImprimir}
              title={podeImprimir ? undefined : 'Preencha o rolo/etiqueta e adicione ao menos 1 campo antes de testar.'}
              onClick={() => setTesteImpressaoAberto(true)}
            >
              Testar Impressão
            </button>
            <BotaoFecharTela onClick={() => navigate('/etiqueta-configuracao', { state: { listaEstado } })} />
            {!somenteLeitura && (
              <button type="submit" form="form-etiqueta-config" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <form
          id="form-etiqueta-config"
          className="card form-secoes form-secoes-larga"
          onSubmit={submeter}
          onKeyDown={(e: KeyboardEvent<HTMLFormElement>) => {
            if (!somenteLeitura) aoTeclarEnterNoFormulario(e, () => setConfirmarSalvarAberto(true))
          }}
          noValidate
        >
          <fieldset disabled={somenteLeitura} className="form-fieldset">
            <section className="section">
              <div className="form-grid">
                <div className="col-2">
                  <label className="checkbox-linha" style={{ marginTop: 22 }}>
                    <input type="checkbox" checked={form.ativo} onChange={(e) => setForm((f) => ({ ...f, ativo: e.target.checked }))} />
                    Ativa
                  </label>
                </div>
                <div className="col-10">
                  <label htmlFor="nome">Nome *</label>
                  <input id="nome" autoFocus value={form.nome} onChange={campoTexto('nome')} onBlur={aoSairDoCampo('nome')} />
                  {erros.nome && <p className="erro-campo">{erros.nome}</p>}
                </div>
              </div>
            </section>

            <section className="section">
              <div className="form-grid etiqueta-config-linha">
                <div className="col-4">
                  <div className="card etiqueta-subcard">
                    <p className="card-title">Rolo e Etiqueta (mm)</p>
                    <label htmlFor="larguraRoloMm">Largura do Rolo *</label>
                    <input id="larguraRoloMm" inputMode="decimal" placeholder="0,00" value={form.larguraRoloMm}
                      onChange={campoMm('larguraRoloMm')} onBlur={aoSairDoCampoMm('larguraRoloMm')} />
                    {erros.larguraRoloMm && <p className="erro-campo">{erros.larguraRoloMm}</p>}

                    <label htmlFor="numeroColunas">Número de Colunas *</label>
                    <select id="numeroColunas" value={form.numeroColunas} onChange={aoMudarNumeroColunas}>
                      {OPCOES_NUMERO_COLUNAS.map((n) => (
                        <option key={n} value={n}>
                          {n}
                        </option>
                      ))}
                    </select>

                    <label htmlFor="larguraEtiquetaMm">Largura da Etiqueta *</label>
                    <input id="larguraEtiquetaMm" inputMode="decimal" placeholder="0,00" value={form.larguraEtiquetaMm}
                      onChange={campoMm('larguraEtiquetaMm')} onBlur={aoSairDoCampoMm('larguraEtiquetaMm')} />
                    {erros.larguraEtiquetaMm && <p className="erro-campo">{erros.larguraEtiquetaMm}</p>}

                    <label htmlFor="alturaEtiquetaMm">Altura da Etiqueta *</label>
                    <input id="alturaEtiquetaMm" inputMode="decimal" placeholder="0,00" value={form.alturaEtiquetaMm}
                      onChange={campoMm('alturaEtiquetaMm')} onBlur={aoSairDoCampoMm('alturaEtiquetaMm')} />
                    {erros.alturaEtiquetaMm && <p className="erro-campo">{erros.alturaEtiquetaMm}</p>}
                  </div>
                </div>

                <div className="col-4">
                  <div className="card etiqueta-subcard">
                    <p className="card-title">Bordas (mm)</p>
                    <label htmlFor="bordaSuperiorMm">Superior</label>
                    <input id="bordaSuperiorMm" inputMode="decimal" placeholder="0,00" value={form.bordaSuperiorMm}
                      onChange={campoMm('bordaSuperiorMm')} onBlur={aoSairDoCampoMm('bordaSuperiorMm')} />

                    <label htmlFor="bordaInferiorMm">Inferior</label>
                    <input id="bordaInferiorMm" inputMode="decimal" placeholder="0,00" value={form.bordaInferiorMm}
                      onChange={campoMm('bordaInferiorMm')} onBlur={aoSairDoCampoMm('bordaInferiorMm')} />

                    <label htmlFor="bordaEsquerdaMm">Esquerda</label>
                    <input id="bordaEsquerdaMm" inputMode="decimal" placeholder="0,00" value={form.bordaEsquerdaMm}
                      onChange={campoMm('bordaEsquerdaMm')} onBlur={aoSairDoCampoMm('bordaEsquerdaMm')} />

                    <label htmlFor="bordaDireitaMm">Direita</label>
                    <input id="bordaDireitaMm" inputMode="decimal" placeholder="0,00" value={form.bordaDireitaMm}
                      onChange={campoMm('bordaDireitaMm')} onBlur={aoSairDoCampoMm('bordaDireitaMm')} />
                  </div>
                </div>

                <div className="col-4">
                  <div className="card etiqueta-subcard">
                    <p className="card-title">Espaçamento entre Etiquetas (mm)</p>
                    <p className="muted etiqueta-subcard-ajuda">
                      Meça no rolo, com a régua, os espaços <strong>em branco</strong> entre as etiquetas —
                      é a mesma medida nas duas direções.
                    </p>

                    <label htmlFor="margemEsquerdaColunas">Margem até a 1ª coluna</label>
                    <input id="margemEsquerdaColunas" inputMode="decimal" placeholder="0,00"
                      disabled={colunasManuais}
                      value={margemColunas}
                      onChange={(e) => setMargemColunas(mascararEtiquetaMm(e.target.value))}
                      onBlur={(e) => setMargemColunas(completarEtiquetaMm(e.target.value))} />

                    {form.numeroColunas > 1 && (
                      <>
                        <label htmlFor="espacoEntreColunas">↔ Espaço entre colunas</label>
                        <input id="espacoEntreColunas" inputMode="decimal" placeholder="0,00"
                          disabled={colunasManuais}
                          value={espacoColunas}
                          onChange={(e) => setEspacoColunas(mascararEtiquetaMm(e.target.value))}
                          onBlur={(e) => setEspacoColunas(completarEtiquetaMm(e.target.value))} />
                      </>
                    )}

                    <label htmlFor="espacamentoVerticalMm">↕ Espaço entre fileiras</label>
                    <input id="espacamentoVerticalMm" inputMode="decimal" placeholder="0,00"
                      value={form.espacamentoVerticalMm}
                      onChange={campoMm('espacamentoVerticalMm')} onBlur={aoSairDoCampoMm('espacamentoVerticalMm')} />
                    <p className="muted etiqueta-subcard-ajuda">
                      <strong>Erro aqui se acumula</strong>: a 1ª etiqueta sai certa e a 4ª sai fora do adesivo.
                    </p>

                    {!colunasManuais && (
                      <p className="muted etiqueta-subcard-ajuda" style={{ marginTop: 8 }}>
                        Cada coluna começa em:{' '}
                        <strong className="mono">
                          {form.colunas.map((c) => formatarEtiquetaMm(c.posicaoInicialMm)).join(' · ')}
                        </strong>{' '}
                        mm
                      </p>
                    )}

                    <label className="checkbox-linha" style={{ marginTop: 8 }}>
                      <input
                        type="checkbox"
                        checked={colunasManuais}
                        onChange={(e) => setColunasManuais(e.target.checked)}
                      />
                      Rolo irregular — digitar cada posição
                    </label>

                    {colunasManuais && form.colunas.map((coluna) => (
                      <div key={coluna.numeroColuna}>
                        <label htmlFor={`coluna-${coluna.numeroColuna}`}>Coluna {coluna.numeroColuna} começa em</label>
                        <input
                          id={`coluna-${coluna.numeroColuna}`}
                          inputMode="decimal"
                          placeholder="0,00"
                          defaultValue={formatarEtiquetaMm(coluna.posicaoInicialMm)}
                          onChange={(e) => aoMudarPosicaoColuna(coluna.numeroColuna, mascararEtiquetaMm(e.target.value))}
                          onBlur={(e) => aoMudarPosicaoColuna(coluna.numeroColuna, completarEtiquetaMm(e.target.value))}
                        />
                      </div>
                    ))}

                    {colunasQueEstouram.length > 0 && (
                      <p className="erro-campo">
                        {colunasQueEstouram.length === 1
                          ? `A coluna ${colunasQueEstouram[0]} passa da largura do rolo`
                          : `As colunas ${colunasQueEstouram.join(', ')} passam da largura do rolo`}{' '}
                        ({formatarEtiquetaMm(larguraRoloNumero)} mm) — o que sobrar é cortado na impressão.
                      </p>
                    )}
                    {colunasSobrepostas.length > 0 && (
                      <p className="erro-campo">
                        A coluna {colunasSobrepostas[0]} começa antes de a anterior terminar — as etiquetas
                        vão se sobrepor.
                      </p>
                    )}
                  </div>
                </div>
              </div>
            </section>

            <section className="section">
              <p className="section-label">Campos da Etiqueta</p>
              <div className="form-grid">
                <div className="col-12">
                  {produtoExemplo ? (
                    <p className="muted">
                      Pré-visualizando com <strong>{produtoExemplo.descricao}</strong> ·{' '}
                      <button type="button" className="btn ghost" onClick={() => setBuscaProdutoAberta(true)}>
                        Trocar produto
                      </button>{' '}
                      <button type="button" className="btn ghost" onClick={() => setProdutoExemplo(null)}>
                        Limpar exemplo
                      </button>
                    </p>
                  ) : (
                    <p className="muted">
                      <button type="button" className="btn ghost" onClick={() => setBuscaProdutoAberta(true)}>
                        Escolher produto de exemplo
                      </button>{' '}
                      pra pré-visualizar a etiqueta com dados reais (opcional — sem escolher, mostra texto genérico).
                    </p>
                  )}
                </div>
                <div className="col-12">
                  <EditorEtiquetaCanvas
                    larguraEtiquetaMm={desmascararEtiquetaMm(form.larguraEtiquetaMm) || 0}
                    alturaEtiquetaMm={desmascararEtiquetaMm(form.alturaEtiquetaMm) || 0}
                    espacamentoVerticalMm={desmascararEtiquetaMm(form.espacamentoVerticalMm) || 0}
                    bordaSuperiorMm={desmascararEtiquetaMm(form.bordaSuperiorMm) || 0}
                    bordaInferiorMm={desmascararEtiquetaMm(form.bordaInferiorMm) || 0}
                    bordaEsquerdaMm={desmascararEtiquetaMm(form.bordaEsquerdaMm) || 0}
                    bordaDireitaMm={desmascararEtiquetaMm(form.bordaDireitaMm) || 0}
                    larguraRoloMm={desmascararEtiquetaMm(form.larguraRoloMm) || 0}
                    colunas={form.colunas}
                    campos={form.campos}
                    aoMudarCampos={aoMudarCampos}
                    produtoExemplo={produtoExemplo}
                  />
                </div>
              </div>
            </section>

            <InfoRegistro
              codigo={configExistente?.idConfigEtiqueta}
              criadoEm={configExistente?.criadoEm}
              atualizadoEm={configExistente?.atualizadoEm}
            />
          </fieldset>
        </form>
      </div>

      {confirmarSalvarAberto && (
        <ConfirmarSalvarModal
          aoConfirmar={() => {
            setConfirmarSalvarAberto(false)
            validarEEnviar()
          }}
          aoCancelar={() => setConfirmarSalvarAberto(false)}
        />
      )}

      {buscaProdutoAberta && (
        <ProdutoExemploModal
          aoFechar={() => setBuscaProdutoAberta(false)}
          aoSelecionar={(p) => {
            setProdutoExemplo(p)
            setBuscaProdutoAberta(false)
          }}
        />
      )}

      {testeImpressaoAberto && (
        <TesteImpressaoModal
          aoFechar={() => setTesteImpressaoAberto(false)}
          aoConfirmar={(quantidade) => {
            setTesteImpressaoAberto(false)
            setQuantidadeImprimir(quantidade)
          }}
        />
      )}

      {/* Fora da tela (só existe pro navegador imprimir) — `.etiqueta-imprimir` (styles.css) fica
          escondida na visualização normal e isolada em `@media print`, mesma técnica do
          Comprovante de Crediário/Fechamento de Caixa. `escalaPxPorMm=MM_PARA_PX_IMPRESSAO`
          reaproveita `CampoEtiquetaVisual` já mapeando mm→px na escala física real (96dpi), não
          no zoom de tela do editor. Cada etiqueta ganha um quadro (`border`, 2026-08-05, só aqui
          no Teste de Impressão) pra servir de guia de corte ao testar em papel comum, antes do
          rolo de etiqueta de verdade — `box-sizing: border-box` (global) garante que a borda fica
          por dentro do tamanho configurado, sem deslocar a coluna seguinte. */}
      {quantidadeImprimir > 0 && (
        <div className="etiqueta-imprimir">
          {linhasParaImprimir(quantidadeImprimir, form.colunas).map((colunasDaLinha, indiceLinha) => (
            <div
              key={indiceLinha}
              style={{
                position: 'relative',
                width: (desmascararEtiquetaMm(form.larguraRoloMm) || 0) * MM_PARA_PX_IMPRESSAO,
                // PASSO vertical da fileira = altura + espaço entre fileiras (V056). A etiqueta em
                // si (abaixo) continua com a altura pura — quem cresce é o passo, não o adesivo.
                height:
                  ((desmascararEtiquetaMm(form.alturaEtiquetaMm) || 0) +
                    (desmascararEtiquetaMm(form.espacamentoVerticalMm) || 0)) *
                  MM_PARA_PX_IMPRESSAO,
              }}
            >
              {colunasDaLinha.map((coluna) => (
                <div
                  key={coluna.numeroColuna}
                  style={{
                    position: 'absolute',
                    left: coluna.posicaoInicialMm * MM_PARA_PX_IMPRESSAO,
                    top: 0,
                    width: (desmascararEtiquetaMm(form.larguraEtiquetaMm) || 0) * MM_PARA_PX_IMPRESSAO,
                    height: (desmascararEtiquetaMm(form.alturaEtiquetaMm) || 0) * MM_PARA_PX_IMPRESSAO,
                    background: '#fff',
                    border: '1px solid #000',
                  }}
                >
                  {form.campos.map((c) => (
                    <CampoEtiquetaVisual
                      key={c.campo}
                      campo={c}
                      escalaPxPorMm={MM_PARA_PX_IMPRESSAO}
                      produtoExemplo={produtoExemplo}
                      nomeEmpresaExemplo="NOME DA LOJA"
                    />
                  ))}
                </div>
              ))}
            </div>
          ))}
        </div>
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
