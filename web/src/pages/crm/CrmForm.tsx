import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
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
 * CRM (2026-08-05, docs/telas/crm.md) — primeira implementação real da área, até então só um
 * placeholder no menu. Filtros de Cliente + Filtros de Produtos Comprados (ambos em popup — a
 * tela em si não pode rolar, pedido explícito) → exportação em planilha Excel. Sem grid de
 * pré-visualização de propósito: gerar já busca e já baixa, um botão só, pra não arriscar
 * precisar de scroll numa lista de resultado que pode ter qualquer tamanho.
 */
export default function CrmForm() {
  const [filtrosClientes, setFiltrosClientes] = useState<FiltrosClientesCrm>(FILTROS_CLIENTES_CRM_VAZIO)
  const [filtrosProdutos, setFiltrosProdutos] = useState<FiltrosProdutosCrm>(FILTROS_PRODUTOS_CRM_VAZIO)
  const [colunas, setColunas] = useState<ColunaSaidaCrm[]>(TODAS_AS_COLUNAS_CRM)
  const [filtrosClientesAberto, setFiltrosClientesAberto] = useState(false)
  const [filtrosProdutosAberto, setFiltrosProdutosAberto] = useState(false)
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)

  const { data: opcoes } = useQuery({ queryKey: ['crm-opcoes'], queryFn: buscarOpcoesCrm })

  const alternarColuna = (col: ColunaSaidaCrm) =>
    setColunas((atual) => (atual.includes(col) ? atual.filter((c) => c !== col) : [...atual, col]))

  const gerar = useMutation({
    mutationFn: async () => {
      if (colunas.length === 0) throw new ApiError(400, 'Selecione ao menos um dado do cliente para gerar a planilha.')
      const clientes = await buscarClientesCrm(filtrosClientes, filtrosProdutos)
      if (clientes.length === 0) throw new ApiError(404, 'Nenhum cliente encontrado com esses filtros.')
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
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes">
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

          <section className="section">
            <p className="section-label">Formato da Geração</p>
            <p className="muted" style={{ marginTop: -4 }}>
              Planilha Excel (.xlsx) — uma linha por cliente encontrado.
            </p>
            <div className="ajuda-rodape" style={{ justifyContent: 'flex-start' }}>
              <button type="button" className="btn" disabled={gerar.isPending} onClick={() => gerar.mutate()}>
                {gerar.isPending ? 'Gerando…' : 'Gerar Planilha Excel'}
              </button>
            </div>
          </section>
        </div>
      </div>

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
          variantesLinha={opcoes?.variantesLinha ?? []}
          variantesColuna={opcoes?.variantesColuna ?? []}
          valor={filtrosProdutos}
          aoMudar={setFiltrosProdutos}
          aoFechar={() => setFiltrosProdutosAberto(false)}
        />
      )}

      {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
    </div>
  )
}
