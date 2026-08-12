import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import GaugeProgresso from '../../components/GaugeProgresso'
import { IconeCliente, IconeEstoque, IconeFornecedor, IconeProduto, IconeRecebimentoCrediario, type IconeComponente } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarCategorias } from '../../lib/clientes'
import { listarEmpresas } from '../../lib/empresas'
import {
  baixarModeloPlanilha,
  buscarProgressoImportacao,
  contarLinhasPlanilha,
  detectarArquivo,
  processarImportacao,
  type ProgressoImportacao,
  type RelatorioImportacao,
} from '../../lib/importacao'
import { mascararCodigoPlanoContas } from '../../lib/masks'
import { listarPlanosContas } from '../../lib/planoContas'
import { listarTiposCarteira } from '../../lib/tiposCarteira'

export type Tabela = 'cliente' | 'fornecedor' | 'produto' | 'contas_receber' | 'estoque'

/** Rótulo do gauge por etapa reportada pelo backend (2026-08-11) — durante `processar()` a
 *  etapa real pode divergir do `status` da tela (ex.: `status==='validando'` mas o backend ainda
 *  está lendo a planilha inteira antes de validar linha por linha). */
const ROTULO_ETAPA: Record<string, string> = {
  leitura: 'Lendo a planilha…',
  validando: 'Validando…',
  importando: 'Importando…',
}

const COLUNAS_ESTOQUE = ['QUANTIDADE_ESTOQUE_1', 'QUANTIDADE_ESTOQUE_2', 'QUANTIDADE_ESTOQUE_3', 'QUANTIDADE_ESTOQUE_4', 'QUANTIDADE_ESTOQUE_5']

const CONFIG: Record<Tabela, { titulo: string; subtitulo: string; icone: IconeComponente; chaveAjuda: string }> = {
  cliente: {
    titulo: 'Importar Clientes',
    subtitulo: 'Cadastro de clientes a partir de uma planilha Excel (.xlsx ou .xls).',
    icone: IconeCliente,
    chaveAjuda: 'configuracao.importacao.clientes',
  },
  contas_receber: {
    titulo: 'Importar Contas a Receber',
    subtitulo: 'Saldo devedor de crediário, via planilha Excel (.xlsx ou .xls) — o cliente precisa já estar cadastrado.',
    icone: IconeRecebimentoCrediario,
    chaveAjuda: 'configuracao.importacao.contasReceber',
  },
  fornecedor: {
    titulo: 'Importar Fornecedores',
    subtitulo: 'Cadastro de fornecedores a partir de uma planilha Excel (.xlsx ou .xls).',
    icone: IconeFornecedor,
    chaveAjuda: 'configuracao.importacao.fornecedores',
  },
  produto: {
    titulo: 'Importar Produtos',
    subtitulo: 'Catálogo de produtos, com a grade de tamanhos de cada um, via planilha Excel (.xlsx ou .xls).',
    icone: IconeProduto,
    chaveAjuda: 'configuracao.importacao.produtos',
  },
  estoque: {
    titulo: 'Importar Estoque Inicial',
    subtitulo: 'Saldo inicial de estoque, via planilha Excel (.xlsx ou .xls) — o produto precisa já ter sido importado.',
    icone: IconeEstoque,
    chaveAjuda: 'configuracao.importacao.estoque',
  },
}

type StatusImportacao = 'detectando' | 'ocioso' | 'validando' | 'validado' | 'importando' | 'importado' | 'falha'

interface EscolhasDraft {
  categoriaModo: 'existente' | 'nova'
  idCategoriaCliente: number | ''
  novaCategoria: string
  planoModo: 'existente' | 'novo'
  idPlanoContas: string
  novoPlano: { codigo: string; descricao: string; tipoMovimento: string; natureza: string }
  carteiraModo: 'existente' | 'nova'
  idCarteira: number | ''
  novaCarteira: { nomeCarteira: string; prazoPagamento: string; pcMinima: string; pcMaxima: string }
  mapeamentoEmpresas: Record<string, number | ''>
}

function escolhasDraftPadrao(): EscolhasDraft {
  const mapeamentoEmpresas: Record<string, number | ''> = {}
  COLUNAS_ESTOQUE.forEach((c) => {
    mapeamentoEmpresas[c] = ''
  })
  return {
    categoriaModo: 'existente',
    idCategoriaCliente: '',
    novaCategoria: '',
    planoModo: 'existente',
    idPlanoContas: '',
    novoPlano: { codigo: '', descricao: '', tipoMovimento: 'DEBITO', natureza: 'ANALITICA' },
    carteiraModo: 'existente',
    idCarteira: '',
    novaCarteira: { nomeCarteira: '', prazoPagamento: '30', pcMinima: '1', pcMaxima: '12' },
    mapeamentoEmpresas,
  }
}

function montarEscolhas(tabela: Tabela, escolhas: EscolhasDraft): unknown {
  switch (tabela) {
    case 'cliente':
      return escolhas.categoriaModo === 'existente'
        ? { idCategoriaCliente: escolhas.idCategoriaCliente }
        : { novaCategoria: escolhas.novaCategoria }
    case 'fornecedor':
      return escolhas.planoModo === 'existente'
        ? { idPlanoContas: escolhas.idPlanoContas }
        : { novoPlanoContas: escolhas.novoPlano }
    case 'contas_receber':
      return escolhas.carteiraModo === 'existente'
        ? { idCarteira: escolhas.idCarteira }
        : {
            novaCarteira: {
              nomeCarteira: escolhas.novaCarteira.nomeCarteira,
              prazoPagamento: Number(escolhas.novaCarteira.prazoPagamento),
              pcMinima: Number(escolhas.novaCarteira.pcMinima),
              pcMaxima: Number(escolhas.novaCarteira.pcMaxima),
            },
          }
    case 'estoque':
      return { mapeamentoEmpresas: escolhas.mapeamentoEmpresas }
    default:
      return {}
  }
}

function escolhasValidas(tabela: Tabela, escolhas: EscolhasDraft): boolean {
  switch (tabela) {
    case 'cliente':
      return escolhas.categoriaModo === 'existente'
        ? escolhas.idCategoriaCliente !== ''
        : escolhas.novaCategoria.trim() !== ''
    case 'fornecedor':
      return escolhas.planoModo === 'existente'
        ? escolhas.idPlanoContas.trim() !== ''
        : escolhas.novoPlano.codigo.trim() !== '' && escolhas.novoPlano.descricao.trim() !== ''
    case 'contas_receber':
      return escolhas.carteiraModo === 'existente'
        ? escolhas.idCarteira !== ''
        : escolhas.novaCarteira.nomeCarteira.trim() !== ''
    case 'estoque':
      // Nem toda coluna QUANTIDADE_ESTOQUE_N precisa de empresa — um tenant pode ter menos de 5
      // empresas — mas ao menos uma precisa estar mapeada (backend rejeita mapeamento vazio).
      return COLUNAS_ESTOQUE.some((c) => escolhas.mapeamentoEmpresas[c] !== '' && escolhas.mapeamentoEmpresas[c] !== undefined)
    case 'produto':
      return true
    default:
      return false
  }
}

/**
 * Tela dedicada de importação de UMA tabela (docs/telas/importacao-dados.md), reformulada em
 * 2026-08-10 — pedido do dono do produto de trocar a tela única multi-arquivo (com detecção
 * automática) por um botão/tela por tabela dentro do hub "Importação de Dados". Cada instância
 * desta página é parametrizada por `tabela` e cuida de 1 arquivo por vez: baixar modelo → escolher
 * arquivo → escolhas prévias (quando a tabela exigir) → Validar (dry-run) → Importar.
 */
export default function ImportacaoTabelaPage({ tabela }: { tabela: Tabela }) {
  const config = CONFIG[tabela]
  const Icone = config.icone

  const [arquivo, setArquivo] = useState<File | null>(null)
  const [escolhas, setEscolhas] = useState<EscolhasDraft>(escolhasDraftPadrao())
  const [status, setStatus] = useState<StatusImportacao>('ocioso')
  const [relatorio, setRelatorio] = useState<RelatorioImportacao | null>(null)
  const [erroMsg, setErroMsg] = useState<string | null>(null)
  const [toast, setToast] = useState('')
  const [toastTipo, setToastTipo] = useState<TipoToast>('erro')
  // Total de registros contado no navegador ao escolher o arquivo (2026-08-11) — exato e
  // instantâneo, mostrado antes mesmo de clicar em Validar/Importar.
  const [totalRegistros, setTotalRegistros] = useState<number | null>(null)
  // Progresso "ao vivo" (etapa/atual/total) vindo do polling durante leitura/validação/importação.
  const [progresso, setProgresso] = useState<ProgressoImportacao | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const pollRef = useRef<number | null>(null)

  useEffect(() => () => pararPolling(), [])

  function pararPolling() {
    if (pollRef.current !== null) {
      window.clearInterval(pollRef.current)
      pollRef.current = null
    }
  }

  function iniciarPolling(idProgresso: string) {
    pararPolling()
    pollRef.current = window.setInterval(() => {
      buscarProgressoImportacao(idProgresso).then(setProgresso).catch(() => {
        // Falha pontual de polling não deve interromper a operação principal em andamento.
      })
    }, 400)
  }

  const { data: categorias } = useQuery({ queryKey: ['categorias-cliente-importacao'], queryFn: listarCategorias, enabled: tabela === 'cliente' })
  const { data: planos } = useQuery({
    queryKey: ['planos-contas-importacao'],
    queryFn: () => listarPlanosContas({ tamanho: 100 }),
    enabled: tabela === 'fornecedor',
  })
  const { data: carteirasPagina } = useQuery({
    queryKey: ['tipos-carteira-importacao'],
    queryFn: () => listarTiposCarteira({ tamanho: 100 }),
    enabled: tabela === 'contas_receber',
  })
  const { data: empresas } = useQuery({ queryKey: ['empresas-importacao'], queryFn: listarEmpresas, enabled: tabela === 'estoque' })
  const carteirasCrediario = (carteirasPagina?.itens ?? []).filter((c) => c.categoriaCarteira === 'CREDIARIO')

  function mostrarErro(e: unknown) {
    setToastTipo('erro')
    setToast(e instanceof ApiError ? e.message : 'Ocorreu um erro.')
  }

  /** Antes de aceitar a planilha escolhida, confere (via `POST /detectar`, mesma detecção por
   *  similaridade de colunas usada no hub multi-arquivo antigo) se ela bate com a tabela desta
   *  tela — evita, por exemplo, escolher a planilha de Fornecedores na tela de Clientes e só
   *  descobrir o problema depois de clicar em "Validar", com uma lista de erros sem sentido. */
  async function onSelecionarArquivo(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0] ?? null
    e.target.value = ''
    if (!f) return

    setArquivo(null)
    setEscolhas(escolhasDraftPadrao())
    setRelatorio(null)
    setErroMsg(null)
    setTotalRegistros(null)
    setStatus('detectando')
    try {
      const deteccao = await detectarArquivo(f)
      if (deteccao.tabela !== tabela) {
        const tituloDetectado = deteccao.tabela && deteccao.tabela in CONFIG ? CONFIG[deteccao.tabela as Tabela].titulo : null
        setErroMsg(
          tituloDetectado
            ? `Essa planilha parece ser de "${tituloDetectado}", não de "${config.titulo}". Escolha o arquivo correto.`
            : `Não foi possível reconhecer essa planilha como sendo de "${config.titulo}" — confira se as colunas batem com o modelo.`,
        )
        setStatus('ocioso')
        return
      }
      setArquivo(f)
      setStatus('ocioso')
      // Contagem só informativa (mostrada na tela) — falha aqui não deve travar o fluxo.
      contarLinhasPlanilha(f).then(setTotalRegistros).catch(() => setTotalRegistros(null))
    } catch (err) {
      setErroMsg(err instanceof ApiError ? err.message : 'Não foi possível ler a planilha selecionada.')
      setStatus('ocioso')
    }
  }

  function alterarEscolhas(novas: EscolhasDraft) {
    setEscolhas(novas)
    setStatus('ocioso')
    setRelatorio(null)
  }

  async function validar() {
    if (!arquivo) return
    setStatus('validando')
    setErroMsg(null)
    setProgresso(null)
    const idProgresso = crypto.randomUUID()
    iniciarPolling(idProgresso)
    try {
      const rel = await processarImportacao(tabela, arquivo, montarEscolhas(tabela, escolhas), false, idProgresso)
      setRelatorio(rel)
      setStatus('validado')
    } catch (e) {
      setStatus('ocioso')
      setErroMsg(e instanceof ApiError ? e.message : 'Ocorreu um erro.')
    } finally {
      pararPolling()
    }
  }

  async function importar() {
    if (!arquivo) return
    setStatus('importando')
    setProgresso(null)
    const idProgresso = crypto.randomUUID()
    iniciarPolling(idProgresso)
    try {
      const rel = await processarImportacao(tabela, arquivo, montarEscolhas(tabela, escolhas), true, idProgresso)
      // Tudo-ou-nada por arquivo: o backend só grava de verdade quando NENHUMA linha tem erro —
      // `confirmado` pode vir `false` mesmo com `confirmar=true` (achado real de bug, 2026-08-09).
      if (!rel.confirmado) {
        setStatus('falha')
        setRelatorio(rel)
        setErroMsg('Nada foi importado — este arquivo ainda tem linha(s) com erro.')
        return
      }
      setRelatorio(rel)
      setStatus('importado')
      setToastTipo('sucesso')
      setToast('Importação concluída.')
    } catch (e) {
      setStatus('falha')
      mostrarErro(e)
    } finally {
      pararPolling()
    }
  }

  function novaImportacao() {
    setArquivo(null)
    setEscolhas(escolhasDraftPadrao())
    setStatus('ocioso')
    setRelatorio(null)
    setErroMsg(null)
    setTotalRegistros(null)
    setProgresso(null)
  }

  const ocupado = status === 'validando' || status === 'importando'
  const temErroReal = relatorio !== null && relatorio.erros.length > 0
  const podeValidar = arquivo !== null && escolhasValidas(tabela, escolhas) && !ocupado
  const podeImportar = status === 'validado' && !temErroReal

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <Icone size={34} />
            <div>
              <h1>{config.titulo}</h1>
              <p className="muted" style={{ margin: 0 }}>{config.subtitulo}</p>
            </div>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={config.chaveAjuda} />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card" style={{ padding: 24 }}>
          <p className="section-label">1. Localize a planilha</p>
          <p className="muted">
            Preencha os dados a partir do modelo baixado abaixo. Datas no formato dd/mm/aaaa quando
            digitadas como texto; valores decimais aceitam vírgula ou ponto.
          </p>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12, flexWrap: 'wrap' }}>
            <input
              ref={inputRef}
              type="file"
              accept=".xlsx,.xls,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel"
              onChange={onSelecionarArquivo}
              disabled={status === 'importado' || status === 'detectando'}
              style={{ display: 'none' }}
            />
            <button
              type="button"
              className="btn ghost"
              onClick={() => inputRef.current?.click()}
              disabled={status === 'importado' || status === 'detectando'}
            >
              Escolher planilha
            </button>
            <button
              type="button"
              className="btn ghost"
              style={{ fontSize: 12 }}
              onClick={() => baixarModeloPlanilha(tabela).catch(mostrarErro)}
              disabled={status === 'detectando'}
            >
              Baixar planilha modelo
            </button>
          </div>

          {status === 'detectando' && <GaugeProgresso rotulo="Conferindo a planilha…" />}

          {erroMsg && <p style={{ color: 'var(--danger)', marginTop: 12 }}>{erroMsg}</p>}

          {arquivo && (
            <>
              <p className="muted" style={{ marginTop: 16 }}>
                Arquivo selecionado: <strong>{arquivo.name}</strong>
                {totalRegistros !== null && <> — {totalRegistros.toLocaleString('pt-BR')} registro(s)</>}
              </p>

              {tabela === 'cliente' && (
                <div style={{ marginTop: 12 }}>
                  <p className="section-label">2. Categoria aplicada a todos os clientes deste arquivo</p>
                  <label className="radio-linha">
                    <input
                      type="radio"
                      checked={escolhas.categoriaModo === 'existente'}
                      onChange={() => alterarEscolhas({ ...escolhas, categoriaModo: 'existente' })}
                    />
                    Categoria existente
                  </label>
                  {escolhas.categoriaModo === 'existente' && (
                    <select
                      value={escolhas.idCategoriaCliente}
                      onChange={(e) => alterarEscolhas({ ...escolhas, idCategoriaCliente: e.target.value ? Number(e.target.value) : '' })}
                      style={{ marginBottom: 12 }}
                    >
                      <option value="">Selecione…</option>
                      {(categorias ?? []).map((c) => (
                        <option key={c.idCategoriaCliente} value={c.idCategoriaCliente}>{c.nomeCategoria}</option>
                      ))}
                    </select>
                  )}
                  <label className="radio-linha">
                    <input
                      type="radio"
                      checked={escolhas.categoriaModo === 'nova'}
                      onChange={() => alterarEscolhas({ ...escolhas, categoriaModo: 'nova' })}
                    />
                    Nova categoria
                  </label>
                  {escolhas.categoriaModo === 'nova' && (
                    <input
                      placeholder="Nome da nova categoria"
                      value={escolhas.novaCategoria}
                      onChange={(e) => alterarEscolhas({ ...escolhas, novaCategoria: e.target.value.toUpperCase() })}
                    />
                  )}
                </div>
              )}

              {tabela === 'fornecedor' && (
                <div style={{ marginTop: 12 }}>
                  <p className="section-label">2. Plano de contas aplicado a todos os fornecedores deste arquivo</p>
                  <label className="radio-linha">
                    <input
                      type="radio"
                      checked={escolhas.planoModo === 'existente'}
                      onChange={() => alterarEscolhas({ ...escolhas, planoModo: 'existente' })}
                    />
                    Plano de contas existente
                  </label>
                  {escolhas.planoModo === 'existente' && (
                    <select
                      value={escolhas.idPlanoContas}
                      onChange={(e) => alterarEscolhas({ ...escolhas, idPlanoContas: e.target.value })}
                      style={{ marginBottom: 12 }}
                    >
                      <option value="">Selecione…</option>
                      {(planos?.itens ?? []).map((p) => (
                        <option key={p.idPlanoContas} value={p.idPlanoContas}>{p.idPlanoContas} — {p.descricao}</option>
                      ))}
                    </select>
                  )}
                  <label className="radio-linha">
                    <input
                      type="radio"
                      checked={escolhas.planoModo === 'novo'}
                      onChange={() => alterarEscolhas({ ...escolhas, planoModo: 'novo' })}
                    />
                    Novo plano de contas
                  </label>
                  {escolhas.planoModo === 'novo' && (
                    <div className="form-grid">
                      <div className="col-3">
                        <label>Código (9.99.999.999)</label>
                        <input
                          value={escolhas.novoPlano.codigo}
                          onChange={(e) =>
                            alterarEscolhas({ ...escolhas, novoPlano: { ...escolhas.novoPlano, codigo: mascararCodigoPlanoContas(e.target.value) } })
                          }
                        />
                      </div>
                      <div className="col-5">
                        <label>Descrição</label>
                        <input
                          value={escolhas.novoPlano.descricao}
                          onChange={(e) => alterarEscolhas({ ...escolhas, novoPlano: { ...escolhas.novoPlano, descricao: e.target.value.toUpperCase() } })}
                        />
                      </div>
                      <div className="col-2">
                        <label>Tipo</label>
                        <select
                          value={escolhas.novoPlano.tipoMovimento}
                          onChange={(e) => alterarEscolhas({ ...escolhas, novoPlano: { ...escolhas.novoPlano, tipoMovimento: e.target.value } })}
                        >
                          <option value="CREDITO">Crédito</option>
                          <option value="DEBITO">Débito</option>
                          <option value="NEUTRO">Neutro</option>
                        </select>
                      </div>
                      <div className="col-2">
                        <label>Natureza</label>
                        <select
                          value={escolhas.novoPlano.natureza}
                          onChange={(e) => alterarEscolhas({ ...escolhas, novoPlano: { ...escolhas.novoPlano, natureza: e.target.value } })}
                        >
                          <option value="ANALITICA">Analítica</option>
                          <option value="SINTETICA">Sintética</option>
                        </select>
                      </div>
                    </div>
                  )}
                </div>
              )}

              {tabela === 'contas_receber' && (
                <div style={{ marginTop: 12 }}>
                  <p className="section-label">2. Carteira de crediário aplicada a todas as parcelas deste arquivo</p>
                  <label className="radio-linha">
                    <input
                      type="radio"
                      checked={escolhas.carteiraModo === 'existente'}
                      onChange={() => alterarEscolhas({ ...escolhas, carteiraModo: 'existente' })}
                    />
                    Carteira existente
                  </label>
                  {escolhas.carteiraModo === 'existente' && (
                    <select
                      value={escolhas.idCarteira}
                      onChange={(e) => alterarEscolhas({ ...escolhas, idCarteira: e.target.value ? Number(e.target.value) : '' })}
                      style={{ marginBottom: 12 }}
                    >
                      <option value="">Selecione…</option>
                      {carteirasCrediario.map((c) => (
                        <option key={c.idCarteira} value={c.idCarteira}>{c.nomeCarteira}</option>
                      ))}
                    </select>
                  )}
                  <label className="radio-linha">
                    <input
                      type="radio"
                      checked={escolhas.carteiraModo === 'nova'}
                      onChange={() => alterarEscolhas({ ...escolhas, carteiraModo: 'nova' })}
                    />
                    Nova carteira de crediário
                  </label>
                  {escolhas.carteiraModo === 'nova' && (
                    <div className="form-grid">
                      <div className="col-4">
                        <label>Nome</label>
                        <input
                          value={escolhas.novaCarteira.nomeCarteira}
                          onChange={(e) =>
                            alterarEscolhas({ ...escolhas, novaCarteira: { ...escolhas.novaCarteira, nomeCarteira: e.target.value.toUpperCase() } })
                          }
                        />
                      </div>
                      <div className="col-2">
                        <label>Prazo (dias)</label>
                        <input
                          inputMode="numeric"
                          value={escolhas.novaCarteira.prazoPagamento}
                          onChange={(e) =>
                            alterarEscolhas({ ...escolhas, novaCarteira: { ...escolhas.novaCarteira, prazoPagamento: e.target.value.replace(/\D/g, '') } })
                          }
                        />
                      </div>
                      <div className="col-2">
                        <label>Parcelas mín.</label>
                        <input
                          inputMode="numeric"
                          value={escolhas.novaCarteira.pcMinima}
                          onChange={(e) =>
                            alterarEscolhas({ ...escolhas, novaCarteira: { ...escolhas.novaCarteira, pcMinima: e.target.value.replace(/\D/g, '') } })
                          }
                        />
                      </div>
                      <div className="col-2">
                        <label>Parcelas máx.</label>
                        <input
                          inputMode="numeric"
                          value={escolhas.novaCarteira.pcMaxima}
                          onChange={(e) =>
                            alterarEscolhas({ ...escolhas, novaCarteira: { ...escolhas.novaCarteira, pcMaxima: e.target.value.replace(/\D/g, '') } })
                          }
                        />
                      </div>
                    </div>
                  )}
                </div>
              )}

              {tabela === 'estoque' && (
                <div style={{ marginTop: 12 }}>
                  <p className="section-label">2. Diga a qual empresa cada coluna de quantidade corresponde</p>
                  <div className="form-grid">
                    {COLUNAS_ESTOQUE.map((coluna) => {
                      // Uma empresa já escolhida noutra coluna não pode aparecer aqui — cada
                      // QUANTIDADE_ESTOQUE_N é de uma empresa diferente.
                      const usadasEmOutrasColunas = new Set(
                        COLUNAS_ESTOQUE.filter((c) => c !== coluna)
                          .map((c) => escolhas.mapeamentoEmpresas[c])
                          .filter((v) => v !== '' && v !== undefined),
                      )
                      return (
                        <div className="col-4" key={coluna}>
                          <label>{coluna}</label>
                          <select
                            value={escolhas.mapeamentoEmpresas[coluna] ?? ''}
                            onChange={(e) =>
                              alterarEscolhas({
                                ...escolhas,
                                mapeamentoEmpresas: { ...escolhas.mapeamentoEmpresas, [coluna]: e.target.value ? Number(e.target.value) : '' },
                              })
                            }
                          >
                            <option value="">Selecione a empresa…</option>
                            {(empresas ?? [])
                              .filter((emp) => !usadasEmOutrasColunas.has(emp.idEmpresa))
                              .map((emp) => (
                                <option key={emp.idEmpresa} value={emp.idEmpresa}>{emp.nomeFantasia ?? emp.razaoSocial}</option>
                              ))}
                          </select>
                        </div>
                      )
                    })}
                  </div>
                </div>
              )}

              {ocupado && (
                <GaugeProgresso
                  rotulo={(progresso && ROTULO_ETAPA[progresso.etapa]) ?? (status === 'validando' ? 'Validando…' : 'Importando…')}
                  atual={progresso?.atual}
                  total={progresso && progresso.total > 0 ? progresso.total : totalRegistros ?? undefined}
                />
              )}

              {!ocupado && status === 'validado' && relatorio && (
                <p style={{ marginTop: 16, color: temErroReal ? 'var(--danger)' : 'var(--accent)', fontWeight: 600 }}>
                  {temErroReal
                    ? `${relatorio.erros.length} linha(s) com erro`
                    : `OK — ${relatorio.linhasImportadas} linha(s) prontas${relatorio.linhasIgnoradas > 0 ? `, ${relatorio.linhasIgnoradas} já existem` : ''}`}
                </p>
              )}

              {!ocupado && status === 'importado' && relatorio && (
                <p style={{ marginTop: 16, color: 'var(--accent)', fontWeight: 600 }}>
                  Importado — {relatorio.linhasImportadas} linha(s)
                </p>
              )}

              {!ocupado && status === 'falha' && (
                <p style={{ marginTop: 16, color: 'var(--danger)', fontWeight: 600 }}>Falha ao importar</p>
              )}

              {relatorio && relatorio.avisos.length > 0 && (
                <div style={{ marginTop: 12 }}>
                  <p className="card-title">Avisos</p>
                  <ul className="passos">
                    {relatorio.avisos.map((a, i) => <li key={i}>{a}</li>)}
                  </ul>
                </div>
              )}

              {temErroReal && relatorio && (
                <div style={{ marginTop: 12, maxHeight: 320, overflowY: 'auto' }}>
                  <p className="card-title">Linhas com erro</p>
                  <table className="table table-compacta">
                    <thead>
                      <tr>
                        <th>Linha</th>
                        <th>Campo</th>
                        <th>Valor recebido</th>
                        <th>Motivo</th>
                      </tr>
                    </thead>
                    <tbody>
                      {relatorio.erros.map((e, i) => (
                        <tr key={i}>
                          <td>{e.linha}</td>
                          <td>{e.campo ?? '—'}</td>
                          <td>{e.valor ?? '—'}</td>
                          <td>{e.motivo}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </div>
      </div>

      <div className="lista-rodape">
        <div className="wizard-acoes" style={{ margin: '12px 24px' }}>
          {(status === 'ocioso' || status === 'validando') && (
            <button type="button" className="btn" disabled={!podeValidar} onClick={validar}>
              {status === 'validando' ? 'Validando…' : 'Validar'}
            </button>
          )}
          {(status === 'validado' || status === 'importando') && (
            <>
              <button type="button" className="btn ghost" disabled={ocupado} onClick={validar}>Validar de novo</button>
              <button type="button" className="btn" disabled={!podeImportar} onClick={importar}>
                {status === 'importando' ? 'Importando…' : 'Importar'}
              </button>
            </>
          )}
          {(status === 'importado' || status === 'falha') && (
            <button type="button" className="btn" onClick={novaImportacao}>Nova importação</button>
          )}
        </div>
      </div>

      {toast && <Toast mensagem={toast} tipo={toastTipo} aoFechar={() => setToast('')} />}
    </div>
  )
}
