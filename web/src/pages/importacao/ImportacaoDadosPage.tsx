import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import GaugeProgresso from '../../components/GaugeProgresso'
import { IconePedidos } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarCategorias } from '../../lib/clientes'
import { listarEmpresas } from '../../lib/empresas'
import { listarPlanosContas } from '../../lib/planoContas'
import { listarTiposCarteira } from '../../lib/tiposCarteira'
import {
  analisarProduto,
  baixarModeloCsv,
  listarTabelasImportacao,
  processarImportacao,
  type AnaliseProduto,
  type RelatorioImportacao,
  type TabelaImportavel,
} from '../../lib/importacao'

const CHAVE_TELA = 'configuracao.importacao'

type Etapa = 'tabela' | 'arquivo' | 'escolhas' | 'previa' | 'concluido'

/**
 * Rotina de Importação de Dados (docs/telas/importacao-dados.md) — carga inicial de cadastros
 * e crediário em aberto a partir de um sistema anterior, via CSV. ADMIN-only (a rota já é
 * protegida por `RequireAdmin`; o backend reforça o mesmo em cada chamada).
 *
 * Fluxo: escolher tabela → baixar modelo/enviar arquivo → resolver escolhas prévias (que variam
 * por tabela: categoria única do arquivo, plano de contas único, carteira de crediário única, ou
 * — só para produto — mapear colunas de estoque para empresa e confirmar o rótulo da variante) →
 * prévia (dry-run, nada é gravado) → confirmar (grava de verdade).
 *
 * Layout (2026-08-06, pedido do dono do produto): mesmo padrão do resto do sistema —
 * `.lista-topo` (título) e `.lista-rodape` (botões da etapa atual) fixos, só `.lista-corpo`
 * rola — importante na prévia, onde a lista de linhas com erro pode ser longa.
 */
export default function ImportacaoDadosPage() {
  const [etapa, setEtapa] = useState<Etapa>('tabela')
  const [tabela, setTabela] = useState<TabelaImportavel | null>(null)
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [toast, setToast] = useState('')
  const [toastTipo, setToastTipo] = useState<TipoToast>('erro')
  const [relatorio, setRelatorio] = useState<RelatorioImportacao | null>(null)

  // cliente
  const [categoriaModo, setCategoriaModo] = useState<'existente' | 'nova'>('existente')
  const [idCategoriaCliente, setIdCategoriaCliente] = useState<number | ''>('')
  const [novaCategoria, setNovaCategoria] = useState('')

  // fornecedor
  const [planoModo, setPlanoModo] = useState<'existente' | 'novo'>('existente')
  const [idPlanoContas, setIdPlanoContas] = useState('')
  const [novoPlano, setNovoPlano] = useState({ codigo: '', descricao: '', tipoMovimento: 'DEBITO', natureza: 'ANALITICA' })

  // contas_receber
  const [carteiraModo, setCarteiraModo] = useState<'existente' | 'nova'>('existente')
  const [idCarteira, setIdCarteira] = useState<number | ''>('')
  const [novaCarteira, setNovaCarteira] = useState({ nomeCarteira: '', prazoPagamento: '30', pcMinima: '1', pcMaxima: '12' })

  // produto
  const [analise, setAnalise] = useState<AnaliseProduto | null>(null)
  const [mapeamentoEmpresas, setMapeamentoEmpresas] = useState<Record<string, number | ''>>({})
  const [rotulos, setRotulos] = useState<Record<string, { linha: string; coluna: string }>>({})

  const { data: tabelas } = useQuery({ queryKey: ['importacao-tabelas'], queryFn: listarTabelasImportacao })
  const { data: categorias } = useQuery({
    queryKey: ['categorias-cliente-importacao'],
    queryFn: listarCategorias,
    enabled: tabela?.chave === 'cliente',
  })
  const { data: planos } = useQuery({
    queryKey: ['planos-contas-importacao'],
    queryFn: () => listarPlanosContas({ tamanho: 100 }),
    enabled: tabela?.chave === 'fornecedor',
  })
  const { data: carteirasPagina } = useQuery({
    queryKey: ['tipos-carteira-importacao'],
    queryFn: () => listarTiposCarteira({ tamanho: 100 }),
    enabled: tabela?.chave === 'contas_receber',
  })
  const { data: empresas } = useQuery({
    queryKey: ['empresas-importacao'],
    queryFn: listarEmpresas,
    enabled: tabela?.chave === 'produto',
  })
  const carteirasCrediario = (carteirasPagina?.itens ?? []).filter((c) => c.categoriaCarteira === 'CREDIARIO')

  function mostrarErro(e: unknown) {
    setToastTipo('erro')
    setToast(e instanceof ApiError ? e.message : 'Ocorreu um erro.')
  }

  const analisarMut = useMutation({
    mutationFn: (f: File) => analisarProduto(f),
    onSuccess: (a) => {
      setAnalise(a)
      const rot: Record<string, { linha: string; coluna: string }> = {}
      a.grupos.forEach((g) => {
        rot[g.chave] = { linha: g.rotuloLinhaSugerido ?? '', coluna: g.rotuloColunaSugerido ?? '' }
      })
      setRotulos(rot)
      const mapa: Record<string, number | ''> = {}
      a.colunasEstoqueComDado.forEach((c) => {
        mapa[c] = ''
      })
      setMapeamentoEmpresas(mapa)
      setEtapa('escolhas')
    },
    onError: mostrarErro,
  })

  const previaMut = useMutation({
    mutationFn: () => processarImportacao(tabela!.chave, arquivo!, montarEscolhas(), false),
    onSuccess: (r) => {
      setRelatorio(r)
      setEtapa('previa')
    },
    onError: mostrarErro,
  })

  const confirmarMut = useMutation({
    mutationFn: () => processarImportacao(tabela!.chave, arquivo!, montarEscolhas(), true),
    onSuccess: (r) => {
      setRelatorio(r)
      // Defesa extra (o botão já fica desabilitado com erro): o backend só confirma de
      // verdade quando não sobrou nenhuma linha com erro — "tudo ou nada" (2026-08-06).
      if (!r.confirmado) {
        setToastTipo('erro')
        setToast('Nada foi importado — corrija as linhas com erro e tente de novo.')
        return
      }
      setEtapa('concluido')
      setToastTipo('sucesso')
      setToast('Importação concluída.')
    },
    onError: mostrarErro,
  })

  function montarEscolhas(): unknown {
    if (!tabela) return {}
    switch (tabela.chave) {
      case 'cliente':
        return categoriaModo === 'existente' ? { idCategoriaCliente } : { novaCategoria }
      case 'fornecedor':
        return planoModo === 'existente' ? { idPlanoContas } : { novoPlanoContas: novoPlano }
      case 'contas_receber':
        return carteiraModo === 'existente'
          ? { idCarteira }
          : {
              novaCarteira: {
                nomeCarteira: novaCarteira.nomeCarteira,
                prazoPagamento: Number(novaCarteira.prazoPagamento),
                pcMinima: Number(novaCarteira.pcMinima),
                pcMaxima: Number(novaCarteira.pcMaxima),
              },
            }
      case 'produto':
        return { mapeamentoEmpresas, rotulos }
      default:
        return {}
    }
  }

  function escolhasValidas(): boolean {
    if (!tabela) return false
    switch (tabela.chave) {
      case 'cliente':
        return categoriaModo === 'existente' ? idCategoriaCliente !== '' : novaCategoria.trim() !== ''
      case 'fornecedor':
        return planoModo === 'existente'
          ? idPlanoContas.trim() !== ''
          : novoPlano.codigo.trim() !== '' && novoPlano.descricao.trim() !== ''
      case 'contas_receber':
        return carteiraModo === 'existente' ? idCarteira !== '' : novaCarteira.nomeCarteira.trim() !== ''
      case 'produto': {
        const semMapeamento = Object.values(mapeamentoEmpresas).some((v) => v === '')
        const semRotulo = (analise?.grupos ?? []).some(
          (g) => (g.usaLinha && !rotulos[g.chave]?.linha.trim()) || (g.usaColuna && !rotulos[g.chave]?.coluna.trim()),
        )
        return !semMapeamento && !semRotulo
      }
      default:
        return true
    }
  }

  function reiniciar() {
    setEtapa('tabela')
    setTabela(null)
    setArquivo(null)
    setRelatorio(null)
    setAnalise(null)
    setCategoriaModo('existente')
    setIdCategoriaCliente('')
    setNovaCategoria('')
    setPlanoModo('existente')
    setIdPlanoContas('')
    setPlanoModo('existente')
    setCarteiraModo('existente')
    setIdCarteira('')
  }

  function escolherTabela(t: TabelaImportavel) {
    setTabela(t)
    setArquivo(null)
    setRelatorio(null)
    setAnalise(null)
    setEtapa('arquivo')
  }

  function avancarParaEscolhas() {
    if (!arquivo) return
    if (tabela?.chave === 'produto') {
      analisarMut.mutate(arquivo)
    } else {
      setEtapa('escolhas')
    }
  }

  const rotuloProcessando = analisarMut.isPending
    ? 'Analisando o arquivo…'
    : previaMut.isPending
      ? 'Processando a prévia…'
      : confirmarMut.isPending
        ? 'Gravando a importação…'
        : null

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePedidos size={34} />
            <div>
              <h1>Importação de Dados</h1>
              {tabela && <p className="muted" style={{ margin: 0 }}>{tabela.titulo}</p>}
            </div>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card" style={{ padding: 24 }}>
          {rotuloProcessando ? (
            <GaugeProgresso rotulo={rotuloProcessando} />
          ) : (
          <>
          {etapa === 'tabela' && (
            <>
              <p className="section-label">1. Escolha a tabela</p>
              <div className="form-grid" style={{ marginTop: 12 }}>
                {(tabelas ?? []).map((t) => (
                  <div className="col-6" key={t.chave}>
                    <button type="button" className="btn ghost wizard-tabela-btn" onClick={() => escolherTabela(t)}>
                      <strong>{t.titulo}</strong>
                      <p className="muted" style={{ margin: '4px 0 0' }}>{t.descricao}</p>
                    </button>
                  </div>
                ))}
              </div>
            </>
          )}

          {etapa === 'arquivo' && tabela && (
            <>
              <p className="section-label">2. Modelo e arquivo</p>
              <p className="muted">
                Baixe o modelo, preencha fora do sistema (delimitador &quot;;&quot;, decimal com vírgula, datas
                dd/mm/aaaa, UTF-8) e envie o arquivo preenchido.
              </p>
              <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12, flexWrap: 'wrap' }}>
                <button type="button" className="btn ghost" onClick={() => baixarModeloCsv(tabela.chave).catch(mostrarErro)}>
                  Baixar modelo .csv
                </button>
                <input
                  type="file"
                  accept=".csv,text/csv"
                  onChange={(e) => setArquivo(e.target.files?.[0] ?? null)}
                />
              </div>
            </>
          )}

          {etapa === 'escolhas' && tabela && (
            <>
              <p className="section-label">3. Escolhas prévias</p>

              {tabela.chave === 'cliente' && (
                <div style={{ marginTop: 12 }}>
                  <p className="muted">Categoria aplicada a todos os clientes deste arquivo.</p>
                  <label className="radio-linha">
                    <input type="radio" checked={categoriaModo === 'existente'} onChange={() => setCategoriaModo('existente')} />
                    Categoria existente
                  </label>
                  {categoriaModo === 'existente' && (
                    <select
                      value={idCategoriaCliente}
                      onChange={(e) => setIdCategoriaCliente(e.target.value ? Number(e.target.value) : '')}
                      style={{ marginBottom: 12 }}
                    >
                      <option value="">Selecione…</option>
                      {(categorias ?? []).map((c) => (
                        <option key={c.idCategoriaCliente} value={c.idCategoriaCliente}>
                          {c.nomeCategoria}
                        </option>
                      ))}
                    </select>
                  )}
                  <label className="radio-linha">
                    <input type="radio" checked={categoriaModo === 'nova'} onChange={() => setCategoriaModo('nova')} />
                    Nova categoria
                  </label>
                  {categoriaModo === 'nova' && (
                    <input
                      placeholder="Nome da nova categoria"
                      value={novaCategoria}
                      onChange={(e) => setNovaCategoria(e.target.value.toUpperCase())}
                    />
                  )}
                </div>
              )}

              {tabela.chave === 'fornecedor' && (
                <div style={{ marginTop: 12 }}>
                  <p className="muted">Plano de contas aplicado a todos os fornecedores deste arquivo.</p>
                  <label className="radio-linha">
                    <input type="radio" checked={planoModo === 'existente'} onChange={() => setPlanoModo('existente')} />
                    Plano de contas existente
                  </label>
                  {planoModo === 'existente' && (
                    <select value={idPlanoContas} onChange={(e) => setIdPlanoContas(e.target.value)} style={{ marginBottom: 12 }}>
                      <option value="">Selecione…</option>
                      {(planos?.itens ?? []).map((p) => (
                        <option key={p.idPlanoContas} value={p.idPlanoContas}>
                          {p.idPlanoContas} — {p.descricao}
                        </option>
                      ))}
                    </select>
                  )}
                  <label className="radio-linha">
                    <input type="radio" checked={planoModo === 'novo'} onChange={() => setPlanoModo('novo')} />
                    Novo plano de contas
                  </label>
                  {planoModo === 'novo' && (
                    <div className="form-grid">
                      <div className="col-3">
                        <label>Código (9.99.999.999)</label>
                        <input
                          value={novoPlano.codigo}
                          onChange={(e) => setNovoPlano((f) => ({ ...f, codigo: e.target.value }))}
                        />
                      </div>
                      <div className="col-5">
                        <label>Descrição</label>
                        <input
                          value={novoPlano.descricao}
                          onChange={(e) => setNovoPlano((f) => ({ ...f, descricao: e.target.value.toUpperCase() }))}
                        />
                      </div>
                      <div className="col-2">
                        <label>Tipo</label>
                        <select
                          value={novoPlano.tipoMovimento}
                          onChange={(e) => setNovoPlano((f) => ({ ...f, tipoMovimento: e.target.value }))}
                        >
                          <option value="CREDITO">Crédito</option>
                          <option value="DEBITO">Débito</option>
                          <option value="NEUTRO">Neutro</option>
                        </select>
                      </div>
                      <div className="col-2">
                        <label>Natureza</label>
                        <select
                          value={novoPlano.natureza}
                          onChange={(e) => setNovoPlano((f) => ({ ...f, natureza: e.target.value }))}
                        >
                          <option value="ANALITICA">Analítica</option>
                          <option value="SINTETICA">Sintética</option>
                        </select>
                      </div>
                    </div>
                  )}
                </div>
              )}

              {tabela.chave === 'contas_receber' && (
                <div style={{ marginTop: 12 }}>
                  <p className="muted">Carteira de crediário aplicada a todas as parcelas deste arquivo.</p>
                  <label className="radio-linha">
                    <input type="radio" checked={carteiraModo === 'existente'} onChange={() => setCarteiraModo('existente')} />
                    Carteira existente
                  </label>
                  {carteiraModo === 'existente' && (
                    <select
                      value={idCarteira}
                      onChange={(e) => setIdCarteira(e.target.value ? Number(e.target.value) : '')}
                      style={{ marginBottom: 12 }}
                    >
                      <option value="">Selecione…</option>
                      {carteirasCrediario.map((c) => (
                        <option key={c.idCarteira} value={c.idCarteira}>
                          {c.nomeCarteira}
                        </option>
                      ))}
                    </select>
                  )}
                  <label className="radio-linha">
                    <input type="radio" checked={carteiraModo === 'nova'} onChange={() => setCarteiraModo('nova')} />
                    Nova carteira de crediário
                  </label>
                  {carteiraModo === 'nova' && (
                    <div className="form-grid">
                      <div className="col-4">
                        <label>Nome</label>
                        <input
                          value={novaCarteira.nomeCarteira}
                          onChange={(e) => setNovaCarteira((f) => ({ ...f, nomeCarteira: e.target.value.toUpperCase() }))}
                        />
                      </div>
                      <div className="col-2">
                        <label>Prazo (dias)</label>
                        <input
                          inputMode="numeric"
                          value={novaCarteira.prazoPagamento}
                          onChange={(e) => setNovaCarteira((f) => ({ ...f, prazoPagamento: e.target.value.replace(/\D/g, '') }))}
                        />
                      </div>
                      <div className="col-2">
                        <label>Parcelas mín.</label>
                        <input
                          inputMode="numeric"
                          value={novaCarteira.pcMinima}
                          onChange={(e) => setNovaCarteira((f) => ({ ...f, pcMinima: e.target.value.replace(/\D/g, '') }))}
                        />
                      </div>
                      <div className="col-2">
                        <label>Parcelas máx.</label>
                        <input
                          inputMode="numeric"
                          value={novaCarteira.pcMaxima}
                          onChange={(e) => setNovaCarteira((f) => ({ ...f, pcMaxima: e.target.value.replace(/\D/g, '') }))}
                        />
                      </div>
                    </div>
                  )}
                </div>
              )}

              {tabela.chave === 'produto' && analise && (
                <div style={{ marginTop: 12 }}>
                  {analise.colunasEstoqueComDado.length > 0 && (
                    <>
                      <p className="muted">
                        O arquivo tem quantidade de estoque nestas colunas — diga a qual empresa cada uma corresponde.
                      </p>
                      <div className="form-grid">
                        {analise.colunasEstoqueComDado.map((coluna) => (
                          <div className="col-4" key={coluna}>
                            <label>{coluna}</label>
                            <select
                              value={mapeamentoEmpresas[coluna] ?? ''}
                              onChange={(e) =>
                                setMapeamentoEmpresas((m) => ({ ...m, [coluna]: e.target.value ? Number(e.target.value) : '' }))
                              }
                            >
                              <option value="">Selecione a empresa…</option>
                              {(empresas ?? []).map((emp) => (
                                <option key={emp.idEmpresa} value={emp.idEmpresa}>
                                  {emp.nomeFantasia ?? emp.razaoSocial}
                                </option>
                              ))}
                            </select>
                          </div>
                        ))}
                      </div>
                    </>
                  )}

                  {analise.grupos.some((g) => g.usaLinha || g.usaColuna) && (
                    <>
                      <p className="muted" style={{ marginTop: 16 }}>
                        Confirme (ou corrija) o rótulo da variante detectado para cada produto com variação.
                      </p>
                      {analise.grupos
                        .filter((g) => g.usaLinha || g.usaColuna)
                        .map((g) => (
                          <div className="form-grid" key={g.chave} style={{ marginBottom: 8 }}>
                            <div className="col-6">
                              <label>{g.descricao} {g.marca && `— ${g.marca}`}</label>
                            </div>
                            {g.usaLinha && (
                              <div className="col-3">
                                <input
                                  placeholder="Rótulo da variante em linha (ex.: Cor)"
                                  value={rotulos[g.chave]?.linha ?? ''}
                                  onChange={(e) =>
                                    setRotulos((r) => ({ ...r, [g.chave]: { ...r[g.chave], linha: e.target.value, coluna: r[g.chave]?.coluna ?? '' } }))
                                  }
                                />
                              </div>
                            )}
                            {g.usaColuna && (
                              <div className="col-3">
                                <input
                                  placeholder="Rótulo da variante em coluna (ex.: Tamanho)"
                                  value={rotulos[g.chave]?.coluna ?? ''}
                                  onChange={(e) =>
                                    setRotulos((r) => ({ ...r, [g.chave]: { ...r[g.chave], coluna: e.target.value, linha: r[g.chave]?.linha ?? '' } }))
                                  }
                                />
                              </div>
                            )}
                          </div>
                        ))}
                    </>
                  )}

                  {analise.colunasEstoqueComDado.length === 0 && !analise.grupos.some((g) => g.usaLinha || g.usaColuna) && (
                    <p className="muted">Nenhuma escolha adicional necessária para este arquivo.</p>
                  )}
                </div>
              )}
            </>
          )}

          {(etapa === 'previa' || etapa === 'concluido') && relatorio && (
            <>
              <p className="section-label">{etapa === 'previa' ? '4. Prévia (nada foi gravado ainda)' : 'Importação concluída'}</p>
              <div className="form-grid" style={{ marginTop: 12 }}>
                <div className="col-3"><strong>{relatorio.totalLinhas}</strong><p className="muted">linhas no arquivo</p></div>
                <div className="col-3">
                  <strong>{relatorio.linhasImportadas}</strong>
                  <p className="muted">{etapa === 'concluido' ? 'importadas' : 'ok para importar'}</p>
                </div>
                <div className="col-3"><strong>{relatorio.linhasIgnoradas}</strong><p className="muted">já existiam (ignoradas)</p></div>
                <div className="col-3"><strong>{relatorio.linhasRejeitadas}</strong><p className="muted">rejeitadas</p></div>
              </div>

              {etapa === 'previa' && relatorio.erros.length > 0 && (
                <p style={{ marginTop: 16, color: 'var(--danger)', fontWeight: 600 }}>
                  {relatorio.erros.length === 1
                    ? 'Corrija a linha com erro listada abaixo'
                    : `Corrija as ${relatorio.erros.length} linhas com erro listadas abaixo`}{' '}
                  e envie o arquivo de novo — nenhum dado será importado enquanto houver erro no arquivo.
                </p>
              )}

              {relatorio.avisos.length > 0 && (
                <div style={{ marginTop: 16 }}>
                  <p className="card-title">Avisos</p>
                  <ul className="passos">
                    {relatorio.avisos.map((a, i) => (
                      <li key={i}>{a}</li>
                    ))}
                  </ul>
                </div>
              )}

              {relatorio.erros.length > 0 && (
                <div style={{ marginTop: 16 }}>
                  <p className="card-title">Linhas rejeitadas</p>
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
          </>
          )}
        </div>
      </div>

      <div className="lista-rodape">
        <div className="wizard-acoes" style={{ margin: '12px 24px' }}>
          {etapa === 'arquivo' && (
            <>
              <button type="button" className="btn ghost" onClick={reiniciar}>
                Voltar
              </button>
              <button
                type="button"
                className="btn"
                disabled={!arquivo || analisarMut.isPending}
                onClick={avancarParaEscolhas}
              >
                {analisarMut.isPending ? 'Analisando…' : 'Continuar'}
              </button>
            </>
          )}

          {etapa === 'escolhas' && (
            <>
              <button type="button" className="btn ghost" onClick={() => setEtapa('arquivo')}>
                Voltar
              </button>
              <button
                type="button"
                className="btn"
                disabled={!escolhasValidas() || previaMut.isPending}
                onClick={() => previaMut.mutate()}
              >
                {previaMut.isPending ? 'Processando…' : 'Ver prévia'}
              </button>
            </>
          )}

          {etapa === 'previa' && relatorio && (
            <>
              <button type="button" className="btn ghost" onClick={() => setEtapa('escolhas')}>
                Voltar
              </button>
              <button
                type="button"
                className="btn"
                disabled={confirmarMut.isPending || relatorio.erros.length > 0 || relatorio.linhasImportadas === 0}
                onClick={() => confirmarMut.mutate()}
              >
                {confirmarMut.isPending ? 'Gravando…' : 'Confirmar importação'}
              </button>
            </>
          )}

          {etapa === 'concluido' && (
            <button type="button" className="btn" onClick={reiniciar}>
              Nova importação
            </button>
          )}
        </div>
      </div>

      {toast && <Toast mensagem={toast} tipo={toastTipo} aoFechar={() => setToast('')} />}
    </div>
  )
}
