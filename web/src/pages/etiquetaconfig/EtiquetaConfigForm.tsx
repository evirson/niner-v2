import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type ChangeEvent, type FocusEvent, type FormEvent, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
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
  larguraOcupadaPelasColunas,
  linhasParaImprimir,
  passoVertical,
  posicoesDasColunas,
  xDaColuna,
  paraFormulario,
  paraRequisicao,
  type CampoEtiquetaPosicionado,
  type EtiquetaConfig,
  type EtiquetaConfigFormState,
  type ProdutoExemplo,
} from '../../lib/etiquetaConfig'
import { useEu } from '../../lib/eu'
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
  /**
   * A configuração que o Teste de Impressão vai imprimir — **como o servidor a devolveu**, nunca o
   * formulário em edição (2026-08-21, decisão do dono do produto: *"sempre seguir as medidas que
   * estão no banco"*).
   *
   * <p>Antes o teste imprimia direto do `form`, o que permitia calibrar sem salvar mas admitia a
   * pior divergência possível numa tela de calibragem: o papel saindo com uma medida e o cadastro
   * guardando outra. Como o papel é a única evidência que temos, um teste que não corresponde ao
   * que ficou gravado não prova nada. Agora o botão grava primeiro e imprime o retorno.
   */
  const [configImpressao, setConfigImpressao] = useState<EtiquetaConfig | null>(null)
  /** Id de uma configuração NOVA criada pelo próprio Teste de Impressão. Sem guardá-lo, o
   *  "Salvar" seguinte criaria uma segunda linha em vez de atualizar a que o teste acabou de
   *  gravar — duplicata silenciosa. */
  const [idCriadoNoTeste, setIdCriadoNoTeste] = useState<number | null>(null)

  // Nome IMPRESSO no campo Nome da Empresa — o nome real da empresa da sessão (2026-08-21).
  // ⚠️ NÃO é `empresa.cfg_nome_etiqueta`: aquela coluna guarda um modelo com marcadores, do ERP
  // legado. Ver o comentário em EuController.
  const { data: eu } = useEu()
  const nomeEmpresaImpressa = eu?.empresa.nomeEtiqueta || 'NOME DA EMPRESA'

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

  /** Id a gravar: o da URL ou, se a configuração nasceu do próprio Teste de Impressão, o que ele
   *  criou. `null` = ainda não existe no banco, então é INSERT. */
  const idParaGravar = id ? Number(id) : idCriadoNoTeste

  const gravar = () =>
    idParaGravar != null
      ? atualizarEtiquetaConfig(idParaGravar, paraRequisicao(form))
      : criarEtiquetaConfig(paraRequisicao(form))

  const salvar = useMutation({
    mutationFn: gravar,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['etiquetas-config'] })
      // ⚠️ A Emissão tem chave PRÓPRIA e precisa ser invalidada junto (2026-08-21): sem isto ela
      // lista os modelos pelo cache antigo depois de a geometria mudar aqui. Mesma armadilha que
      // já mordeu em outras telas — salvar precisa invalidar TODAS as chaves derivadas do dado,
      // não só a da própria tela.
      queryClient.invalidateQueries({ queryKey: ['etiquetas-config-emissao'] })
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

  /**
   * Grava a configuração e imprime **o que o servidor devolveu** — nunca o formulário.
   *
   * <p>Diferente de `salvar`, não navega de volta para a lista: quem está calibrando precisa
   * continuar na tela para o próximo ajuste. O `setForm` com o retorno mantém a tela igual ao
   * banco (o servidor normaliza o nome para maiúsculas, por exemplo), e o toast confirma a
   * gravação, que aqui é efeito e não intenção do usuário.
   */
  const salvarEImprimir = useMutation({
    mutationFn: (_opcoes: { quantidade: number; comRegua: boolean }) => gravar(),
    onSuccess: (config, opcoes) => {
      if (idParaGravar == null) setIdCriadoNoTeste(config.idConfigEtiqueta)
      setForm(paraFormulario(config))
      queryClient.invalidateQueries({ queryKey: ['etiquetas-config'] })
      queryClient.invalidateQueries({ queryKey: ['etiquetas-config-emissao'] })
      setConfigImpressao(config)
      setReguaImprimir(opcoes.comRegua)
      setQuantidadeImprimir(opcoes.quantidade)
      // Deixa explícito que o teste gravou — a gravação aqui é efeito, e efeito silencioso vira
      // surpresa na próxima vez que a tela abrir com valores que o usuário não lembra de ter salvo.
      setToast('Configuração salva. O teste imprime sempre a versão gravada.')
    },
    onError: (e: unknown) =>
      setToast(
        e instanceof ApiError
          ? e.message
          : 'Não foi possível salvar a configuração — o teste imprime sempre a versão gravada, então nada foi impresso.',
      ),
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

  /**
   * Geometria que o Teste de Impressão usa — **a do banco**, não a do formulário.
   *
   * <p>`configImpressao` é o retorno do servidor; `geometria` (acima, derivada do form) continua
   * servindo o editor e as prévias de tela, onde ver o efeito antes de gravar é justamente o que
   * se quer. No papel, não: papel que não corresponde ao cadastro não prova nada.
   */
  const geometriaImpressao = configImpressao ?? geometria
  /** Os campos posicionados como estão gravados — mesmo motivo da geometria acima. */
  const camposImpressao = configImpressao?.campos ?? form.campos

  /** As fileiras do Teste de Impressão — uma por página impressa (ver `alturaPaginaMm`). */
  const fileirasTeste = linhasParaImprimir(quantidadeImprimir, geometriaImpressao.numeroColunas)

  /**
   * Altura da página impressa, em mm.
   *
   * <p>⚠️ É o **passo do rolo**, não a folha inteira (2026-08-21, tarde — depois da Argox
   * OS-2140). Cada fileira sai numa página própria, e é o sensor de gap da impressora que encaixa
   * cada página no começo de um adesivo. Mandar uma folha longa entrega a origem vertical ao
   * driver: com papel de 152,4 mm configurado, ele fatiava o trabalho em pedaços que não eram
   * múltiplos do passo, saíam 5 fileiras em branco e a primeira etiqueta já nascia 3 mm fora.
   *
   * <p>No modo régua a página é a régua inteira, e só ela é impressa — os 132 mm de calibragem
   * não convivem com uma página do tamanho de uma etiqueta.
   */
  const alturaPaginaMm = reguaImprimir ? ALTURA_CALIBRAGEM_MM : passoVertical(geometriaImpressao)

  /**
   * Dispara o diálogo de impressão do navegador quando `quantidadeImprimir` vira > 0 — o tamanho
   * da página (`@page`, em mm) precisa ser injetado aqui porque a largura do rolo é configurável
   * por tenant, diferente do 80mm fixo do Comprovante de Crediário/A4 do Fechamento de Caixa
   * (styles.css). Removido de novo em `afterprint` (fecha o diálogo do SO) e no cleanup (usuário
   * troca a quantidade de novo ou sai da tela no meio do caminho).
   *
   * <p>A classe no `<body>` liga a regra que esconde o resto da tela com `display: none`; sem ela
   * o espaço dos elementos apenas invisíveis empurraria a primeira etiqueta para fora do adesivo
   * (ver `.etiqueta-rolo-imprimir` em styles.css).
   */
  useEffect(() => {
    if (quantidadeImprimir <= 0) return
    const estilo = document.createElement('style')
    estilo.textContent =
      `@page etiqueta-teste-impressao { size: ${geometriaImpressao.larguraRoloMm}mm ${alturaPaginaMm}mm; margin: 0; }` +
      `.etiqueta-rolo-imprimir { page: etiqueta-teste-impressao; }`
    document.head.appendChild(estilo)
    // No <html> TAMBÉM: é lá que mora o `overflow: hidden` do shell, e sem destravá-lo o navegador
    // corta tudo depois da primeira página em vez de paginar (ver styles.css).
    document.documentElement.classList.add('imprimindo-etiquetas')
    document.body.classList.add('imprimindo-etiquetas')
    const aoTerminarImpressao = () => setQuantidadeImprimir(0)
    window.addEventListener('afterprint', aoTerminarImpressao)
    const temporizador = window.setTimeout(() => window.print(), 60)
    return () => {
      window.clearTimeout(temporizador)
      window.removeEventListener('afterprint', aoTerminarImpressao)
      document.documentElement.classList.remove('imprimindo-etiquetas')
      document.body.classList.remove('imprimindo-etiquetas')
      estilo.remove()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quantidadeImprimir])


  const aoMudarCampos = (atualizar: (campos: CampoEtiquetaPosicionado[]) => CampoEtiquetaPosicionado[]) =>
    setForm((f) => ({ ...f, campos: atualizar(f.campos) }))

  /** Valida os campos obrigatórios e destaca o que estiver errado. `true` = pode gravar. */
  const formularioValido = (): boolean => {
    const novosErros: ErrosCampo = {
      nome: validarCampo('nome', form),
      larguraRoloMm: validarCampo('larguraRoloMm', form),
      larguraEtiquetaMm: validarCampo('larguraEtiquetaMm', form),
      alturaEtiquetaMm: validarCampo('alturaEtiquetaMm', form),
    }
    setErros(novosErros)
    if (Object.values(novosErros).some(Boolean)) {
      setToast('Corrija os campos destacados antes de salvar.')
      return false
    }
    return true
  }

  const validarEEnviar = () => {
    if (somenteLeitura) return
    if (!formularioValido()) return
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

      {/* `etiqueta-config-corpo` (2026-08-21): a tela inteira deixa de rolar — quem rola é a área
          do canvas, por dentro dela. Assim aumentar o zoom do editor não empurra o formulário
          para fora da janela. Ver styles.css. */}
      <div className="lista-corpo etiqueta-config-corpo">
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
            {/* Nome mora na COLUNA ESQUERDA (2026-08-21), com a mesma largura dos campos de medida
                logo abaixo — antes atravessava a tela inteira e empurrava o editor uma linha para
                baixo. "Ativa" subiu para a linha do rótulo: a caixa sozinha custava a altura de um
                campo inteiro, e essa altura agora é do desenho da etiqueta. */}
            <section className="section secao-etiqueta-nome">
              <div className="form-grid">
                <div className="col-12">
                  <div className="etiqueta-nome-cabecalho">
                    <label htmlFor="nome">Nome *</label>
                    <label className="checkbox-linha">
                      <input type="checkbox" checked={form.ativo} onChange={(e) => setForm((f) => ({ ...f, ativo: e.target.checked }))} />
                      Ativa
                    </label>
                  </div>
                  <input id="nome" autoFocus value={form.nome} onChange={campoTexto('nome')} onBlur={aoSairDoCampo('nome')} />
                  {erros.nome && <p className="erro-campo">{erros.nome}</p>}
                </div>
              </div>
            </section>

            <section className="section secao-etiqueta-medidas">
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

            <section className="section secao-editor-etiqueta">
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
          numeroColunas={form.numeroColunas}
          aoFechar={() => setTesteImpressaoAberto(false)}
          // Grava ANTES de imprimir e imprime o retorno do servidor: o papel tem de corresponder
          // ao que ficou no banco. `quantidadeImprimir` é ligado no `onSuccess` da mutação, então
          // uma gravação recusada não chega a imprimir nada.
          aoConfirmar={(quantidade, comRegua) => {
            setTesteImpressaoAberto(false)
            // Em modo visualização não há o que gravar, e o que está carregado JÁ é o banco.
            if (somenteLeitura) {
              if (!configExistente) return
              setConfigImpressao(configExistente)
              setReguaImprimir(comRegua)
              setQuantidadeImprimir(quantidade)
              return
            }
            if (!formularioValido()) return
            salvarEImprimir.mutate({ quantidade, comRegua })
          }}
        />
      )}

      {/* Fora da tela (só existe pro navegador imprimir) — `.etiqueta-rolo-imprimir` (styles.css)
          fica escondida na visualização normal e isolada em `@media print`.
          ⚠️ Vai por PORTAL para o `<body>` (2026-08-21, tarde): o bloco precisa ficar no fluxo
          normal para poder paginar (uma fileira por página), e ficar no fluxo só funciona se o
          resto da árvore sumir com `display: none` em vez de apenas `visibility: hidden`.
          `escalaPxPorMm=MM_PARA_PX_IMPRESSAO`
          reaproveita `CampoEtiquetaVisual` já mapeando mm→px na escala física real (96dpi), não
          no zoom de tela do editor. Cada etiqueta ganha um quadro (`border`, 2026-08-05, só aqui
          no Teste de Impressão) pra servir de guia de corte ao testar em papel comum, antes do
          rolo de etiqueta de verdade — `box-sizing: border-box` (global) garante que a borda fica
          por dentro do tamanho configurado, sem deslocar a coluna seguinte. */}
      {quantidadeImprimir > 0 && createPortal(
        <div
          className="etiqueta-rolo-imprimir"
          style={{ width: geometriaImpressao.larguraRoloMm * MM_PARA_PX_IMPRESSAO }}
        >
          {reguaImprimir ? (
            <div style={{ position: 'relative', height: ALTURA_CALIBRAGEM_MM * MM_PARA_PX_IMPRESSAO }}>
              <ReguaCalibragem
                escalaPxPorMm={MM_PARA_PX_IMPRESSAO}
                larguraRoloMm={geometriaImpressao.larguraRoloMm}
                margemEsquerdaMm={geometriaImpressao.margemEsquerdaMm}
              />
            </div>
          ) : fileirasTeste.map((colunasDaLinha, indiceLinha) => (
            <div
              key={indiceLinha}
              style={{
                // Uma fileira = UMA PÁGINA (2026-08-21, tarde). `position: relative` porque as
                // etiquetas se posicionam contra ela; fluxo normal porque quebra de página só
                // acontece no fluxo — um bloco absoluto não pagina.
                position: 'relative',
                width: geometriaImpressao.larguraRoloMm * MM_PARA_PX_IMPRESSAO,
                // ⚠️ A altura do bloco é a da ETIQUETA, não a do passo, embora a página valha o
                // passo. Um bloco tão alto quanto a página é o caso limite da paginação: um
                // arredondamento de sub-pixel o empurra para a página seguinte e nasce uma página
                // em branco entre cada etiqueta. O branco do gap quem dá é o `@page`.
                height: geometriaImpressao.alturaEtiquetaMm * MM_PARA_PX_IMPRESSAO,
                // A última não quebra: quebrar depois dela cospe uma etiqueta em branco no fim.
                breakAfter: indiceLinha === fileirasTeste.length - 1 ? 'auto' : 'page',
                breakInside: 'avoid',
              }}
            >
              {colunasDaLinha.map((indiceColuna) => (
                <div
                  key={indiceColuna}
                  style={{
                    position: 'absolute',
                    // x derivado (V057): margem + i x (largura + espaco). Nenhuma posicao guardada.
                    left: xDaColuna(geometriaImpressao, indiceColuna) * MM_PARA_PX_IMPRESSAO,
                    top: 0,
                    width: geometriaImpressao.larguraEtiquetaMm * MM_PARA_PX_IMPRESSAO,
                    height: geometriaImpressao.alturaEtiquetaMm * MM_PARA_PX_IMPRESSAO,
                    background: '#fff',
                    border: '1px solid #000',
                  }}
                >
                  {camposImpressao.map((c) => (
                    <CampoEtiquetaVisual
                      key={c.campo}
                      campo={c}
                      escalaPxPorMm={MM_PARA_PX_IMPRESSAO}
                      produtoExemplo={produtoExemplo}
                      nomeEmpresaExemplo={nomeEmpresaImpressa}
                    />
                  ))}
                </div>
              ))}
            </div>
          ))}
        </div>,
        document.body,
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
