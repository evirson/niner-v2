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
  atualizarEtiquetaConfig,
  buscarEtiquetaConfig,
  criarEtiquetaConfig,
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
import EditorEtiquetaCanvas from './EditorEtiquetaCanvas'
import ProdutoExemploModal from './ProdutoExemploModal'
import type { EstadoListaEtiquetaConfig } from './EtiquetaConfigLista'

type CampoValidavel = 'nome' | 'larguraRoloMm' | 'larguraEtiquetaMm' | 'alturaEtiquetaMm'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

const OPCOES_NUMERO_COLUNAS = [1, 2, 3, 4, 5, 6]

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
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)
  const [produtoExemplo, setProdutoExemplo] = useState<ProdutoExemplo | null>(null)
  const [buscaProdutoAberta, setBuscaProdutoAberta] = useState(false)

  const { data: configExistente } = useQuery({
    queryKey: ['etiqueta-config', id],
    queryFn: () => buscarEtiquetaConfig(Number(id)),
    enabled: editando,
  })

  useEffect(() => {
    if (configExistente) setForm(paraFormulario(configExistente))
  }, [configExistente])

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

  type CampoMmChave = 'larguraRoloMm' | 'larguraEtiquetaMm' | 'alturaEtiquetaMm' | 'bordaSuperiorMm' | 'bordaInferiorMm' | 'bordaEsquerdaMm' | 'bordaDireitaMm'

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
              <p className="section-label">Identificação</p>
              <div className="form-grid">
                <div className="col-8">
                  <label htmlFor="nome">Nome *</label>
                  <input id="nome" autoFocus value={form.nome} onChange={campoTexto('nome')} onBlur={aoSairDoCampo('nome')} />
                  {erros.nome && <p className="erro-campo">{erros.nome}</p>}
                </div>
                <div className="col-4">
                  <label className="checkbox-linha" style={{ marginTop: 22 }}>
                    <input type="checkbox" checked={form.ativo} onChange={(e) => setForm((f) => ({ ...f, ativo: e.target.checked }))} />
                    Ativa
                  </label>
                </div>
              </div>
            </section>

            <section className="section">
              <p className="section-label">Rolo e Etiqueta (mm)</p>
              <div className="form-grid">
                <div className="col-3">
                  <label htmlFor="larguraRoloMm">Largura do Rolo *</label>
                  <input id="larguraRoloMm" inputMode="decimal" placeholder="0,00" value={form.larguraRoloMm}
                    onChange={campoMm('larguraRoloMm')} onBlur={aoSairDoCampoMm('larguraRoloMm')} />
                  {erros.larguraRoloMm && <p className="erro-campo">{erros.larguraRoloMm}</p>}
                </div>
                <div className="col-3">
                  <label htmlFor="numeroColunas">Número de Colunas *</label>
                  <select id="numeroColunas" value={form.numeroColunas} onChange={aoMudarNumeroColunas}>
                    {OPCOES_NUMERO_COLUNAS.map((n) => (
                      <option key={n} value={n}>
                        {n}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="col-3">
                  <label htmlFor="larguraEtiquetaMm">Largura da Etiqueta *</label>
                  <input id="larguraEtiquetaMm" inputMode="decimal" placeholder="0,00" value={form.larguraEtiquetaMm}
                    onChange={campoMm('larguraEtiquetaMm')} onBlur={aoSairDoCampoMm('larguraEtiquetaMm')} />
                  {erros.larguraEtiquetaMm && <p className="erro-campo">{erros.larguraEtiquetaMm}</p>}
                </div>
                <div className="col-3">
                  <label htmlFor="alturaEtiquetaMm">Altura da Etiqueta *</label>
                  <input id="alturaEtiquetaMm" inputMode="decimal" placeholder="0,00" value={form.alturaEtiquetaMm}
                    onChange={campoMm('alturaEtiquetaMm')} onBlur={aoSairDoCampoMm('alturaEtiquetaMm')} />
                  {erros.alturaEtiquetaMm && <p className="erro-campo">{erros.alturaEtiquetaMm}</p>}
                </div>
              </div>
            </section>

            <section className="section">
              <p className="section-label">Bordas (mm)</p>
              <div className="form-grid">
                <div className="col-3">
                  <label htmlFor="bordaSuperiorMm">Superior</label>
                  <input id="bordaSuperiorMm" inputMode="decimal" placeholder="0,00" value={form.bordaSuperiorMm}
                    onChange={campoMm('bordaSuperiorMm')} onBlur={aoSairDoCampoMm('bordaSuperiorMm')} />
                </div>
                <div className="col-3">
                  <label htmlFor="bordaInferiorMm">Inferior</label>
                  <input id="bordaInferiorMm" inputMode="decimal" placeholder="0,00" value={form.bordaInferiorMm}
                    onChange={campoMm('bordaInferiorMm')} onBlur={aoSairDoCampoMm('bordaInferiorMm')} />
                </div>
                <div className="col-3">
                  <label htmlFor="bordaEsquerdaMm">Esquerda</label>
                  <input id="bordaEsquerdaMm" inputMode="decimal" placeholder="0,00" value={form.bordaEsquerdaMm}
                    onChange={campoMm('bordaEsquerdaMm')} onBlur={aoSairDoCampoMm('bordaEsquerdaMm')} />
                </div>
                <div className="col-3">
                  <label htmlFor="bordaDireitaMm">Direita</label>
                  <input id="bordaDireitaMm" inputMode="decimal" placeholder="0,00" value={form.bordaDireitaMm}
                    onChange={campoMm('bordaDireitaMm')} onBlur={aoSairDoCampoMm('bordaDireitaMm')} />
                </div>
              </div>
            </section>

            <section className="section">
              <p className="section-label">Posição de Cada Coluna no Rolo (mm)</p>
              <p className="muted" style={{ marginTop: -4 }}>
                Distância do início do rolo até o início de cada etiqueta — valor livre, não é calculado
                automaticamente (rolos com espaçamento irregular existem na prática).
              </p>
              <div className="form-grid">
                {form.colunas.map((coluna) => (
                  <div className="col-3" key={coluna.numeroColuna}>
                    <label htmlFor={`coluna-${coluna.numeroColuna}`}>Coluna {coluna.numeroColuna}</label>
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

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
