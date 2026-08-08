import { useMutation, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import GaugeProgresso from '../../components/GaugeProgresso'
import { IconeCliente } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  FILTROS_CLIENTES_CRM_VAZIO,
  FILTROS_PRODUTOS_CRM_VAZIO,
  ROTULO_COLUNA_CRM,
  TODAS_AS_COLUNAS_CRM,
  buscarClientesCrm,
  buscarOpcoesCrm,
  contarFiltrosClientesAtivos,
  contarFiltrosProdutosAtivos,
  exportarClientesCrmExcel,
  formatarCelulaCrm,
  valorOrdenacaoCrm,
  type ClienteCrm,
  type ColunaSaidaCrm,
  type FiltrosClientesCrm,
  type FiltrosProdutosCrm,
} from '../../lib/crm'
import FiltrosClientesModal from './FiltrosClientesModal'
import FiltrosProdutosModal from './FiltrosProdutosModal'

function rotuloContagem(n: number): string {
  return n === 0 ? '' : ` (${n} ativo${n === 1 ? '' : 's'})`
}

/**
 * CRM (2026-08-05, docs/telas/crm.md) — primeira implementação real da área. Ao entrar na tela,
 * um popup obrigatório (2026-08-07, revisão do fluxo — antes os filtros ficavam soltos na
 * própria tela) junta Filtros de Cliente + Filtros de Produtos Comprados (cada um ainda em popup
 * próprio, aninhado) e as colunas de "Dados do Cliente Para Geração". Só ao clicar em **"Localizar
 * Clientes"** o popup fecha, um `GaugeProgresso` indeterminado aparece enquanto a busca roda, e
 * então a grid de resultado é mostrada — a partir daí, **"Alterar Filtros e Colunas"** reabre o
 * mesmo popup (mantendo a seleção atual). **"Gerar Planilha Excel"** fica na linha do título (ao
 * lado do botão de ajuda, mesmo lugar de ações primárias como "＋ Novo cliente" em outras telas)
 * e exporta o que já está carregado, sem buscar de novo. A grid usa o mesmo padrão de cabeçalho fixo + ordenação por
 * coluna do resto do sistema (`.th-ordenavel`), mas 100% client-side: como não há paginação (é
 * sempre o resultado inteiro do filtro, pensado pra exportação), ordenar não precisa de
 * round-trip — só reordena o array já carregado.
 */
export default function CrmForm() {
  const [filtrosClientes, setFiltrosClientes] = useState<FiltrosClientesCrm>(FILTROS_CLIENTES_CRM_VAZIO)
  const [filtrosProdutos, setFiltrosProdutos] = useState<FiltrosProdutosCrm>(FILTROS_PRODUTOS_CRM_VAZIO)
  const [colunas, setColunas] = useState<ColunaSaidaCrm[]>(TODAS_AS_COLUNAS_CRM)
  const [configAberto, setConfigAberto] = useState(true)
  const [filtrosClientesAberto, setFiltrosClientesAberto] = useState(false)
  const [filtrosProdutosAberto, setFiltrosProdutosAberto] = useState(false)
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const [clientes, setClientes] = useState<ClienteCrm[] | null>(null)
  const [ordenarPor, setOrdenarPor] = useState<ColunaSaidaCrm | null>(null)
  const [direcao, setDirecao] = useState<'ASC' | 'DESC'>('ASC')

  const { data: opcoes } = useQuery({ queryKey: ['crm-opcoes'], queryFn: buscarOpcoesCrm })

  const alternarColuna = (col: ColunaSaidaCrm) =>
    setColunas((atual) => (atual.includes(col) ? atual.filter((c) => c !== col) : [...atual, col]))

  const ordenarPorColuna = (col: ColunaSaidaCrm) => {
    if (col === ordenarPor) {
      setDirecao((d) => (d === 'ASC' ? 'DESC' : 'ASC'))
    } else {
      setOrdenarPor(col)
      setDirecao('ASC')
    }
  }

  const clientesOrdenados = useMemo(() => {
    if (!clientes || !ordenarPor) return clientes ?? []
    const copia = [...clientes]
    copia.sort((a, b) => {
      const va = valorOrdenacaoCrm(a, ordenarPor)
      const vb = valorOrdenacaoCrm(b, ordenarPor)
      if (va == null && vb == null) return 0
      if (va == null) return 1
      if (vb == null) return -1
      if (va < vb) return direcao === 'ASC' ? -1 : 1
      if (va > vb) return direcao === 'ASC' ? 1 : -1
      return 0
    })
    return copia
  }, [clientes, ordenarPor, direcao])

  const localizar = useMutation({
    mutationFn: () => buscarClientesCrm(filtrosClientes, filtrosProdutos),
    onSuccess: (lista) => {
      setClientes(lista)
      setOrdenarPor(null)
      setDirecao('ASC')
      if (lista.length === 0) setToast({ texto: 'Nenhum cliente encontrado com esses filtros.', tipo: 'erro' })
    },
    onError: (e: unknown) => {
      setClientes(null)
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível localizar os clientes.', tipo: 'erro' })
    },
  })

  const sairDaConfiguracao = () => {
    setConfigAberto(false)
    setClientes(null)
    localizar.mutate()
  }

  const gerar = useMutation({
    mutationFn: async () => {
      if (colunas.length === 0) throw new ApiError(400, 'Selecione ao menos um dado do cliente para gerar a planilha.')
      if (!clientes || clientes.length === 0) throw new ApiError(400, 'Localize os clientes antes de gerar a planilha.')
      await exportarClientesCrmExcel(clientes, colunas)
      return clientes.length
    },
    onSuccess: (qtd) => setToast({ texto: `Planilha gerada com ${qtd} cliente${qtd === 1 ? '' : 's'}.`, tipo: 'sucesso' }),
    onError: (e: unknown) =>
      setToast({ texto: e instanceof ApiError ? e.message : 'Não foi possível gerar a planilha.', tipo: 'erro' }),
  })

  const qtdFiltrosClientes = contarFiltrosClientesAtivos(filtrosClientes)
  const qtdFiltrosProdutos = contarFiltrosProdutosAtivos(filtrosProdutos)

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCliente size={34} />
            <h1>CRM</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="crm.tela" />
            <button
              type="button"
              className="btn"
              disabled={gerar.isPending || !clientes || clientes.length === 0}
              onClick={() => gerar.mutate()}
            >
              {gerar.isPending ? 'Gerando…' : 'Gerar Planilha Excel'}
            </button>
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        {!configAberto && (
          <div className="card form-secoes">
            <section className="section">
              <p className="section-label">Resultado</p>

              {localizar.isPending ? (
                <GaugeProgresso rotulo="Localizando clientes…" />
              ) : (
                <>
                  <div className="ajuda-rodape" style={{ justifyContent: 'flex-start', marginTop: 0, gap: 16 }}>
                    <button type="button" className="btn ghost" onClick={() => setConfigAberto(true)}>
                      Alterar Filtros e Colunas
                    </button>
                    {clientes && (
                      <span className="muted">
                        <strong>{clientes.length}</strong> cliente{clientes.length === 1 ? '' : 's'} encontrado
                        {clientes.length === 1 ? '' : 's'}
                      </span>
                    )}
                  </div>

                  {clientes && clientes.length > 0 && colunas.length === 0 && (
                    <p className="erro" style={{ marginTop: 12 }}>
                      Nenhuma coluna selecionada em "Dados do Cliente Para Geração" — marque ao menos uma para ver o
                      resultado.
                    </p>
                  )}

                  {clientes && clientes.length > 0 && colunas.length > 0 && (
                    <div className="table-wrap grid-altura-fixa" style={{ marginTop: 12 }}>
                      <table className="table table-compacta">
                        <thead>
                          <tr>
                            {colunas.map((col) => {
                              const ativa = ordenarPor === col
                              return (
                                <th
                                  key={col}
                                  className="th-ordenavel"
                                  onClick={() => ordenarPorColuna(col)}
                                  title="Clique para ordenar"
                                  aria-sort={ativa ? (direcao === 'ASC' ? 'ascending' : 'descending') : 'none'}
                                >
                                  {ROTULO_COLUNA_CRM[col]}
                                  <span className={`th-seta ${ativa ? 'th-seta-ativa' : ''}`}>
                                    {ativa ? (direcao === 'ASC' ? '▲' : '▼') : '⇅'}
                                  </span>
                                </th>
                              )
                            })}
                          </tr>
                        </thead>
                        <tbody>
                          {clientesOrdenados.map((c) => (
                            <tr key={c.idCliente}>
                              {colunas.map((col) => (
                                <td key={col}>{formatarCelulaCrm(c, col)}</td>
                              ))}
                            </tr>
                          ))}
                        </tbody>
                        <tfoot>
                          <tr>
                            <td colSpan={colunas.length}>
                              Total: {clientes.length} cliente{clientes.length === 1 ? '' : 's'}
                            </td>
                          </tr>
                        </tfoot>
                      </table>
                    </div>
                  )}
                </>
              )}
            </section>
          </div>
        )}
      </div>

      {configAberto && (
        <div className="modal-overlay">
          <div className="modal modal-largo" role="dialog" aria-label="Filtros e Dados do Cliente" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Filtros e Dados do Cliente</h2>
            <p className="muted" style={{ marginTop: 4 }}>
              Configure os filtros e as colunas desejadas e clique em "Localizar Clientes".
            </p>

            <section className="section">
              <p className="section-label">Filtros</p>
              <p className="muted" style={{ marginTop: -4 }}>
                Opcionais e combináveis — abrem num popup para não precisar rolar a tela.
              </p>
              <div className="form-grid">
                <div className="col-6">
                  <button type="button" className="btn ghost" style={{ width: '100%' }} onClick={() => setFiltrosClientesAberto(true)}>
                    Filtros de Clientes{rotuloContagem(qtdFiltrosClientes)}
                  </button>
                </div>
                <div className="col-6">
                  <button type="button" className="btn ghost" style={{ width: '100%' }} onClick={() => setFiltrosProdutosAberto(true)}>
                    Filtros de Produtos Comprados{rotuloContagem(qtdFiltrosProdutos)}
                  </button>
                </div>
              </div>
            </section>

            <section className="section">
              <p className="section-label">Dados do Cliente Para Geração</p>
              <p className="muted" style={{ marginTop: -4 }}>
                Também define as colunas mostradas no resultado.
              </p>
              <div className="form-grid">
                {TODAS_AS_COLUNAS_CRM.map((col) => (
                  <div className="col-3" key={col}>
                    <label className="campo-checkbox">
                      <input type="checkbox" checked={colunas.includes(col)} onChange={() => alternarColuna(col)} />
                      {ROTULO_COLUNA_CRM[col]}
                    </label>
                  </div>
                ))}
              </div>
            </section>

            <div className="ajuda-rodape" style={{ justifyContent: 'flex-end' }}>
              <button type="button" className="btn" onClick={sairDaConfiguracao}>
                Localizar Clientes
              </button>
            </div>
          </div>
        </div>
      )}

      {filtrosClientesAberto && (
        <FiltrosClientesModal
          categoriasCliente={opcoes?.categoriasCliente ?? []}
          valor={filtrosClientes}
          aoMudar={setFiltrosClientes}
          aoFechar={() => setFiltrosClientesAberto(false)}
        />
      )}

      {filtrosProdutosAberto && (
        <FiltrosProdutosModal
          categoriasProduto={opcoes?.categoriasProduto ?? []}
          cores={opcoes?.cores ?? []}
          tamanhos={opcoes?.tamanhos ?? []}
          valor={filtrosProdutos}
          aoMudar={setFiltrosProdutos}
          aoFechar={() => setFiltrosProdutosAberto(false)}
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
