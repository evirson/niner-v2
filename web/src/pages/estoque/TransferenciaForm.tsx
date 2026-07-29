import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconeEstoque, IconeExcluir } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { listarEmpresas } from '../../lib/empresas'
import { useEu } from '../../lib/eu'
import { completarPeso, desmascararPeso, formatarPeso, mascararPeso } from '../../lib/masks'
import type { PdvProduto } from '../../lib/pdv'
import { criarTransferencia } from '../../lib/transferencias'
import PesquisaProdutoModal from '../pdv/PesquisaProdutoModal'

const CHAVE_TELA = 'estoque.transferencia.form'

interface ItemLinha {
  idVariacao: number
  descricao: string
  variacao: string | null
  sku: string
  estoqueOrigem: number
  qtdTexto: string
}

function variacaoTexto(p: PdvProduto): string | null {
  if (!p.variacaoLinha && !p.variacaoColuna) return null
  return [p.variacaoLinha, p.variacaoColuna].filter(Boolean).join(' · ')
}

export default function TransferenciaForm() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: eu } = useEu()

  const { data: empresas } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })
  const empresaOrigem = empresas?.find((e) => e.idEmpresa === eu?.empresa.idEmpresa)
  const opcoesDestino = (empresas ?? []).filter((e) => e.ativo && e.idEmpresa !== eu?.empresa.idEmpresa)

  const [idEmpresaDestino, setIdEmpresaDestino] = useState<number | ''>('')
  const [itens, setItens] = useState<ItemLinha[]>([])
  const [observacoes, setObservacoes] = useState('')
  const [mostrarPesquisa, setMostrarPesquisa] = useState(false)
  const [toast, setToast] = useState('')

  const criar = useMutation({
    mutationFn: () =>
      criarTransferencia({
        idEmpresaDestino: Number(idEmpresaDestino),
        itens: itens.map((i) => ({ idVariacao: i.idVariacao, qtd: desmascararPeso(i.qtdTexto) })),
        observacoes: observacoes.trim() || null,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['transferencias'] })
      navigate('/estoque', {
        state: { toast: { texto: 'Transferência realizada com sucesso.', tipo: 'sucesso' } },
      })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível realizar a transferência.'),
  })

  const adicionarProduto = (produto: PdvProduto) => {
    setMostrarPesquisa(false)
    if (itens.some((i) => i.idVariacao === produto.idVariacao)) {
      setToast('Este produto já está na lista.')
      return
    }
    const estoqueNaOrigem = empresaOrigem
      ? (produto.estoquePorEmpresa.find((e) => e.codigoEmpresa === empresaOrigem.codigoEmpresa)?.qtd ?? 0)
      : 0
    setItens((atual) => [
      ...atual,
      {
        idVariacao: produto.idVariacao,
        descricao: produto.descricaoProduto,
        variacao: variacaoTexto(produto),
        sku: produto.sku,
        estoqueOrigem: estoqueNaOrigem,
        qtdTexto: formatarPeso(Math.min(1, estoqueNaOrigem)),
      },
    ])
  }

  const removerItem = (idVariacao: number) => {
    setItens((atual) => atual.filter((i) => i.idVariacao !== idVariacao))
  }

  const alterarQtd = (idVariacao: number, texto: string) => {
    setItens((atual) => atual.map((i) => (i.idVariacao === idVariacao ? { ...i, qtdTexto: mascararPeso(texto) } : i)))
  }

  const aoSairQtd = (idVariacao: number) => {
    setItens((atual) => atual.map((i) => (i.idVariacao === idVariacao ? { ...i, qtdTexto: completarPeso(i.qtdTexto) } : i)))
  }

  const algumItemExcedeEstoque = itens.some((i) => desmascararPeso(i.qtdTexto) > i.estoqueOrigem)
  const algumItemZerado = itens.some((i) => desmascararPeso(i.qtdTexto) <= 0)
  const podeConfirmar = idEmpresaDestino !== '' && itens.length > 0 && !algumItemExcedeEstoque && !algumItemZerado

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEstoque size={34} />
            <h1>Nova Transferência</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <button type="button" className="btn ghost" onClick={() => navigate('/estoque')}>
              Cancelar
            </button>
            <button
              type="button"
              className="btn"
              disabled={!podeConfirmar || criar.isPending}
              onClick={() => criar.mutate()}
            >
              {criar.isPending ? 'Transferindo…' : 'Confirmar Transferência'}
            </button>
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card form-secoes form-secoes-larga">
          <section className="section">
            <p className="section-label">Empresas</p>
            <div className="form-grid">
              <div className="col-6">
                <label>Empresa de Origem</label>
                <div className="pdv-selecao-valor">
                  <span>{eu?.empresa.nome ?? '—'}</span>
                </div>
              </div>
              <div className="col-6">
                <label htmlFor="empresa-destino">Empresa de Destino *</label>
                <select
                  id="empresa-destino"
                  value={idEmpresaDestino}
                  onChange={(e) => setIdEmpresaDestino(e.target.value ? Number(e.target.value) : '')}
                >
                  <option value="">Selecione…</option>
                  {opcoesDestino.map((emp) => (
                    <option key={emp.idEmpresa} value={emp.idEmpresa}>
                      {emp.nomeFantasia ?? emp.razaoSocial}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </section>

          <section className="section">
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <p className="section-label" style={{ margin: 0 }}>
                Produtos
              </p>
              <button type="button" className="btn ghost" onClick={() => setMostrarPesquisa(true)}>
                ＋ Adicionar Produto
              </button>
            </div>

            {itens.length === 0 ? (
              <p className="muted" style={{ marginTop: 8 }}>
                Nenhum produto adicionado ainda.
              </p>
            ) : (
              <div className="table-wrap" style={{ marginTop: 12 }}>
                <table className="table table-compacta">
                  <thead>
                    <tr>
                      <th>Descrição</th>
                      <th>Variação</th>
                      <th style={{ textAlign: 'right' }}>Estoque na Origem</th>
                      <th style={{ textAlign: 'right' }}>Quantidade</th>
                      <th aria-label="Ações" />
                    </tr>
                  </thead>
                  <tbody>
                    {itens.map((item) => {
                      const qtdNumero = desmascararPeso(item.qtdTexto)
                      const excede = qtdNumero > item.estoqueOrigem
                      const zerado = qtdNumero <= 0
                      return (
                        <tr key={item.idVariacao}>
                          <td>{item.descricao}</td>
                          <td>{item.variacao ?? '—'}</td>
                          <td className="mono" style={{ textAlign: 'right' }}>
                            {formatarPeso(item.estoqueOrigem)}
                          </td>
                          <td style={{ textAlign: 'right' }}>
                            <input
                              className="mono"
                              style={{ width: 110, textAlign: 'right' }}
                              value={item.qtdTexto}
                              onChange={(e) => alterarQtd(item.idVariacao, e.target.value)}
                              onBlur={() => aoSairQtd(item.idVariacao)}
                              onFocus={(e) => e.target.select()}
                            />
                            {excede && <p className="erro-campo">Maior que o estoque disponível.</p>}
                            {!excede && zerado && <p className="erro-campo">Informe uma quantidade maior que zero.</p>}
                          </td>
                          <td className="acoes-cell">
                            <button
                              type="button"
                              className="acao-icone acao-excluir"
                              onClick={() => removerItem(item.idVariacao)}
                              aria-label={`Remover ${item.descricao}`}
                              title="Remover"
                            >
                              <IconeExcluir />
                            </button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="section">
            <p className="section-label">Observações</p>
            <textarea
              rows={3}
              value={observacoes}
              onChange={(e) => setObservacoes(e.target.value)}
              placeholder="Opcional"
            />
          </section>
        </div>
      </div>

      {mostrarPesquisa && (
        <PesquisaProdutoModal aoFechar={() => setMostrarPesquisa(false)} aoSelecionar={adicionarProduto} />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
