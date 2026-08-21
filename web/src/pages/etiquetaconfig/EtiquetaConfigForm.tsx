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
  alturaFolhaMm,
  atualizarEtiquetaConfig,
  buscarEtiquetaConfig,
  criarEtiquetaConfig,
  larguraOcupadaPelasColunas,
  linhasParaImprimir,
  passoVertical,
  posicoesDasColunas,
  xDaColuna,
  yDaFileira,
  paraFormulario,
  paraRequisicao,
  type CampoEtiquetaPosicionado,
  type EtiquetaConfigFormState,
  type ProdutoExemplo,
} from '../../lib/etiquetaConfig'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { completarEtiquetaMm, desmascararEtiquetaMm, formatarEtiquetaMm, mascararEtiquetaMm } from '../../lib/masks'
import { maiusculas } from '../../lib/texto'
import CampoEtiquetaVisual from './CampoEtiquetaVisual'
import EditorEtiquetaCanvas from './EditorEtiquetaCanvas'
import ProdutoExemploModal from './ProdutoExemploModal'
import ReguaCalibragem, { ALTURA_CALIBRAGEM_MM } from './ReguaCalibragem'
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

  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)
  const [produtoExemplo, setProdutoExemplo] = useState<ProdutoExemplo | null>(null)
  const [buscaProdutoAberta, setBuscaProdutoAberta] = useState(false)
  const [testeImpressaoAberto, setTesteImpressaoAberto] = useState(false)
  const [quantidadeImprimir, setQuantidadeImprimir] = useState(0)
  const [reguaImprimir, setReguaImprimir] = useState(false)

  const { data: configExistente } = useQuery({
    queryKey: ['etiqueta-config', id],
    queryFn: () => buscarEtiquetaConfig(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (configExistente) setForm(paraFormulario(configExistente))
  }, [configExistente])

  const podeImprimir =
    form.numeroColunas > 0 &&
    form.campos.length > 0 &&
    desmascararEtiquetaMm(form.larguraRoloMm) > 0 &&
    desmascararEtiquetaMm(form.larguraEtiquetaMm) > 0 &&
    desmascararEtiquetaMm(form.alturaEtiquetaMm) > 0

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

  type CampoMmChave =
    | 'larguraRoloMm'
    | 'larguraEtiquetaMm'
    | 'alturaEtiquetaMm'
    | 'margemEsquerdaMm'
    | 'espacamentoHorizontalMm'
    | 'espacamentoVerticalMm'

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

  const aoMudarNumeroColunas = (e: ChangeEvent<HTMLSelectElement>) =>
    setForm((f) => ({ ...f, numeroColunas: Number(e.target.value) }))

  /**
   * A geometria inteira do rolo, em número — 7 valores dos quais sai a posição de **toda**
   * etiqueta da folha (V057, proposta do dono do produto).
   *
   * <p>⚠️ Antes, a posição de cada coluna era um campo digitado e guardado. Era ali que o erro
   * entrava: 3 colunas de 34 mm em 3/41/79 (passo 38) num rolo de passo 40 faziam o texto sair
   * progressivamente cortado, e a tela mostrava fielmente o número errado que estava gravado.
   * Derivando das medidas físicas — as que se tiram com a régua — esse erro deixou de ser
   * representável, e junto com ele foram embora o modo manual, a dedução na abertura e a trava
   * de ordem de efeitos que ela exigia.
   */
  const geometria = {
    larguraRoloMm: desmascararEtiquetaMm(form.larguraRoloMm) || 0,
    numeroColunas: form.numeroColunas,
    larguraEtiquetaMm: desmascararEtiquetaMm(form.larguraEtiquetaMm) || 0,
    alturaEtiquetaMm: desmascararEtiquetaMm(form.alturaEtiquetaMm) || 0,
    margemEsquerdaMm: desmascararEtiquetaMm(form.margemEsquerdaMm) || 0,
    espacamentoHorizontalMm: desmascararEtiquetaMm(form.espacamentoHorizontalMm) || 0,
    espacamentoVerticalMm: desmascararEtiquetaMm(form.espacamentoVerticalMm) || 0,
  }

  const posicoesColunas = posicoesDasColunas(geometria)
  const larguraOcupada = larguraOcupadaPelasColunas(geometria)
  /** As colunas não cabem no rolo — o que passa é simplesmente cortado, e isso é invisível até a
   *  etiqueta sair da impressora. O servidor recusa também (defesa em profundidade). */
  const colunasNaoCabem =
    geometria.larguraRoloMm > 0 && geometria.larguraEtiquetaMm > 0 &&
    larguraOcupada > geometria.larguraRoloMm + 0.001

  /** As fileiras do Teste de Impressão e a altura exata que elas ocupam — o recuo da calibragem
   *  entra aqui porque a primeira fileira precisa começar depois das réguas. */
  const fileirasTeste = linhasParaImprimir(quantidadeImprimir, form.numeroColunas)
  const recuoCalibragemMm = reguaImprimir ? ALTURA_CALIBRAGEM_MM : 0
  const alturaFolhaTesteMm = recuoCalibragemMm + alturaFolhaMm(geometria, fileirasTeste.length)

  /**
   * Dispara o diálogo de impressão do navegador quando `quantidadeImprimir` vira > 0 — o tamanho
   * da página (`@page`, em mm) precisa ser injetado aqui porque a largura do rolo é configurável
   * por tenant, diferente do 80mm fixo do Comprovante de Crediário/A4 do Fechamento de Caixa
   * (styles.css). Removido de novo em `afterprint` (fecha o diálogo do SO) e no cleanup (usuário
   * troca a quantidade de novo ou sai da tela no meio do caminho).
   *
   * <p>⚠️ A altura sai calculada, **não `auto`** (2026-08-21). Com `auto`, quem decide onde a
   * folha termina é o driver, e o que passar do corte vai para a página seguinte: a fileira
   * atravessada pela quebra sai partida no meio do adesivo, sem nenhum aviso na tela. Declarando
   * a altura exata do trabalho, a folha é uma só — que é o que um rolo contínuo é de verdade.
   */
  useEffect(() => {
    if (quantidadeImprimir <= 0) return
    const estilo = document.createElement('style')
    estilo.textContent =
      `@page etiqueta-teste-impressao { size: ${geometria.larguraRoloMm}mm ${alturaFolhaTesteMm}mm; margin: 0; }`
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
                    {/* ⚠️ O rótulo mudou em 2026-08-21 e o motivo é caro: aqui vai a largura que a
                        IMPRESSORA alcança, não a do papel. Uma Argox OS-2140 aceita rolo de 110 mm
                        mas o cabeçote só imprime 104 — declarando 110, o driver encolheu a página
                        inteira em 7%, e o desalinhamento resultante cresce a cada coluna, o que
                        parece erro de espaçamento e manda o diagnóstico para o lado errado. A
                        coluna do banco continua `largura_rolo_mm` (migration aplicada é imutável);
                        só o nome na tela conta a verdade. */}
                    <label htmlFor="larguraRoloMm">Largura de Impressão *</label>
                    <input id="larguraRoloMm" inputMode="decimal" placeholder="0,00" value={form.larguraRoloMm}
                      onChange={campoMm('larguraRoloMm')} onBlur={aoSairDoCampoMm('larguraRoloMm')} />
                    {erros.larguraRoloMm && <p className="erro-campo">{erros.larguraRoloMm}</p>}
                    <p className="etiqueta-subcard-ajuda muted">
                      A largura que a <strong>impressora consegue imprimir</strong> — não a do papel. Muita
                      térmica aceita rolo mais largo do que o cabeçote alcança. Declarar mais faz o driver{' '}
                      <strong>encolher a página inteira</strong>, e o desalinhamento cresce a cada coluna. Se a
                      régua de calibragem sair menor que o anunciado, é este número que está grande demais.
                    </p>

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
                    <p className="card-title">Espaçamento entre Etiquetas (mm)</p>
                    <p className="muted etiqueta-subcard-ajuda">
                      Meça no rolo, com a régua, os espaços <strong>em branco</strong>. Com estes três
                      valores o sistema calcula sozinho onde cada etiqueta começa.
                    </p>

                    <label htmlFor="margemEsquerdaMm">Margem até a 1ª coluna</label>
                    <input id="margemEsquerdaMm" inputMode="decimal" placeholder="0,00"
                      value={form.margemEsquerdaMm}
                      onChange={campoMm('margemEsquerdaMm')} onBlur={aoSairDoCampoMm('margemEsquerdaMm')} />

                    {form.numeroColunas > 1 && (
                      <>
                        <label htmlFor="espacamentoHorizontalMm">↔ Espaço entre colunas</label>
                        <input id="espacamentoHorizontalMm" inputMode="decimal" placeholder="0,00"
                          value={form.espacamentoHorizontalMm}
                          onChange={campoMm('espacamentoHorizontalMm')}
                          onBlur={aoSairDoCampoMm('espacamentoHorizontalMm')} />
                      </>
                    )}

                    <label htmlFor="espacamentoVerticalMm">↕ Espaço entre fileiras</label>
                    <input id="espacamentoVerticalMm" inputMode="decimal" placeholder="0,00"
                      value={form.espacamentoVerticalMm}
                      onChange={campoMm('espacamentoVerticalMm')} onBlur={aoSairDoCampoMm('espacamentoVerticalMm')} />
                    <p className="muted etiqueta-subcard-ajuda">
                      <strong>Erro aqui se acumula</strong>: a 1ª etiqueta sai certa e a 4ª sai fora do adesivo.
                    </p>

                    <p className="muted etiqueta-subcard-ajuda" style={{ marginTop: 8 }}>
                      Cada etiqueta começa em:{' '}
                      <strong className="mono">{posicoesColunas.map(formatarEtiquetaMm).join(' · ')}</strong> mm
                      {' '}· ocupam <strong className="mono">{formatarEtiquetaMm(larguraOcupada)}</strong> dos{' '}
                      <strong className="mono">{formatarEtiquetaMm(geometria.larguraRoloMm)}</strong> mm do rolo.
                    </p>

                    {colunasNaoCabem && (
                      <p className="erro-campo">
                        As {form.numeroColunas} colunas ocupam {formatarEtiquetaMm(larguraOcupada)} mm e não
                        cabem num rolo de {formatarEtiquetaMm(geometria.larguraRoloMm)} mm — o que passar é
                        cortado na impressão. Revise a margem, a largura da etiqueta ou o espaço entre colunas.
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
                    espacamentoVerticalMm={geometria.espacamentoVerticalMm}
                    larguraRoloMm={geometria.larguraRoloMm}
                    posicoesColunas={posicoesColunas}
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
          aoConfirmar={(quantidade, comRegua) => {
            setTesteImpressaoAberto(false)
            setReguaImprimir(comRegua)
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
        <div
          className="etiqueta-imprimir"
          style={{
            // Mesmo `position: absolute` que `.etiqueta-imprimir` já aplica em @media print — aqui
            // explícito porque as fileiras agora se posicionam contra ESTE bloco, e um container
            // estático as jogaria contra a página.
            position: 'absolute',
            top: 0,
            left: 0,
            width: geometria.larguraRoloMm * MM_PARA_PX_IMPRESSAO,
            height: alturaFolhaTesteMm * MM_PARA_PX_IMPRESSAO,
          }}
        >
          {reguaImprimir && (
            <ReguaCalibragem
              escalaPxPorMm={MM_PARA_PX_IMPRESSAO}
              larguraRoloMm={geometria.larguraRoloMm}
              margemEsquerdaMm={geometria.margemEsquerdaMm}
            />
          )}
          {fileirasTeste.map((colunasDaLinha, indiceLinha) => (
            <div
              key={indiceLinha}
              style={{
                // ⚠️ Fileira POSICIONADA, não empilhada (2026-08-21). Empilhando blocos de altura
                // fracionária (33,5 mm = 126,614 px), o arredondamento de cada fileira se soma e a
                // última sai fora do adesivo. Derivada do índice, como o x das colunas na V057.
                position: 'absolute',
                left: 0,
                top: (recuoCalibragemMm + yDaFileira(geometria, indiceLinha)) * MM_PARA_PX_IMPRESSAO,
                width: geometria.larguraRoloMm * MM_PARA_PX_IMPRESSAO,
                // PASSO vertical da fileira = altura + espaço entre fileiras (V056). A etiqueta em
                // si (abaixo) continua com a altura pura — quem cresce é o passo, não o adesivo.
                height: passoVertical(geometria) * MM_PARA_PX_IMPRESSAO,
              }}
            >
              {colunasDaLinha.map((indiceColuna) => (
                <div
                  key={indiceColuna}
                  style={{
                    position: 'absolute',
                    // x derivado (V057): margem + i x (largura + espaco). Nenhuma posicao guardada.
                    left: xDaColuna(geometria, indiceColuna) * MM_PARA_PX_IMPRESSAO,
                    top: 0,
                    width: geometria.larguraEtiquetaMm * MM_PARA_PX_IMPRESSAO,
                    height: geometria.alturaEtiquetaMm * MM_PARA_PX_IMPRESSAO,
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
