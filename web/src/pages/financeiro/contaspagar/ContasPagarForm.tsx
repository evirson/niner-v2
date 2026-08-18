import { useEffect, useState, type FocusEvent, type FormEvent, type KeyboardEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import AberturaCaixaModal from '../../../components/AberturaCaixaModal'
import AjudaDaTela from '../../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../../components/BotaoFecharTela'
import ConfirmarSalvarModal from '../../../components/ConfirmarSalvarModal'
import { IconeContasPagar } from '../../../components/Icones'
import InfoRegistro from '../../../components/InfoRegistro'
import Toast from '../../../components/Toast'
import { ApiError } from '../../../lib/api'
import {
  atualizarContaPagar,
  buscarContaPagar,
  contaPagarVazia,
  criarContaPagar,
  paraFormulario,
  paraRequisicao,
  type ContaPagarFormState,
  type OrigemPagamento,
} from '../../../lib/contasPagar'
import { buscarStatusCaixa } from '../../../lib/caixa'
import { listarContasCorrente } from '../../../lib/contaCorrente'
import { hojeISO } from '../../../lib/datas'
import { listarEmpresasPermitidas } from '../../../lib/empresas'
import { buscarFornecedoresEmissao, type FornecedorOpcaoEmissao } from '../../../lib/etiquetaEmissao'
import { aoTeclarEnterNoFormulario } from '../../../lib/formularios'
import { completarMoeda, dataValida, desmascararMoeda, formatarMoeda, isoParaData, mascararData, mascararMoeda } from '../../../lib/masks'
import SeletorPlanoContas from '../../../components/SeletorPlanoContas'
import { maiusculas } from '../../../lib/texto'

type CampoValidavel = 'idFornecedor' | 'idEmpresa' | 'idPlanoContas' | 'dataLancamentoTexto' | 'dataVencimentoTexto' | 'valorPagarTexto'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

function validarCampo(chave: CampoValidavel, f: ContaPagarFormState): string | undefined {
  if (chave === 'idFornecedor') return f.idFornecedor ? undefined : 'Escolha o fornecedor.'
  if (chave === 'idEmpresa') return f.idEmpresa ? undefined : 'Escolha a empresa.'
  if (chave === 'idPlanoContas') return f.idPlanoContas ? undefined : 'Escolha o plano de contas.'
  if (chave === 'dataLancamentoTexto') {
    if (!f.dataLancamentoTexto.trim()) return 'Data de lançamento é obrigatória.'
    return dataValida(f.dataLancamentoTexto) ? undefined : 'Data inválida.'
  }
  if (chave === 'dataVencimentoTexto') {
    if (!f.dataVencimentoTexto.trim()) return 'Data de vencimento é obrigatória.'
    return dataValida(f.dataVencimentoTexto) ? undefined : 'Data inválida.'
  }
  if (chave === 'valorPagarTexto') return desmascararMoeda(f.valorPagarTexto) > 0 ? undefined : 'Valor a pagar deve ser maior que zero.'
  return undefined
}

/**
 * Formulário de Contas a Pagar / Pagas (docs/telas/contas-pagar.md). PK surrogate
 * (`idContaPagar`) — permite editar/excluir uma conta já gravada, mesmo padrão de
 * `ContaCorrenteMovimentoForm`. Não existe ação de "Pagar" separada: preencher Data de
 * Pagamento/Valor Pago/Documento Pago aqui, no Editar, É a baixa (pedido do dono do produto só
 * listou Visualizar/Editar/Excluir).
 */
export default function ContasPagarForm({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const { id } = useParams()
  const editando = Boolean(id)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [form, setForm] = useState<ContaPagarFormState>(() => contaPagarVazia(isoParaData(hojeISO())))
  const [buscaFornecedor, setBuscaFornecedor] = useState('')
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [confirmarSalvarAberto, setConfirmarSalvarAberto] = useState(false)

  const { data: contaExistente } = useQuery({
    queryKey: ['conta-pagar', id],
    queryFn: () => buscarContaPagar(Number(id)),
    enabled: editando,
  })

  const { data: empresas } = useQuery({ queryKey: ['empresas-permitidas'], queryFn: listarEmpresasPermitidas })
  // Baixa em dinheiro exige caixa aberto (ContaPagarService.sincronizarMovimentoDeDinheiro, 400
  // se não houver). Até 2026-08-15 esta tela só mostrava o erro e deixava o operador se virar —
  // sair, abrir o caixa noutra tela e refazer a baixa. Agora oferece a abertura aqui mesmo, igual
  // PDV e Recebimento de Crediário. Diferença deliberada: lá o popup bloqueia a tela inteira na
  // entrada (nada se faz sem caixa); aqui só aparece quando o operador escolhe "Caixa da loja",
  // porque pagar pela conta corrente — ou só editar a conta — não precisa de caixa nenhum.
  const { data: statusCaixa } = useQuery({ queryKey: ['caixa-status'], queryFn: buscarStatusCaixa })
  const precisaAbrirCaixa = !somenteLeitura && form.origemPagamento === 'CAIXA' && statusCaixa != null && !statusCaixa.aberto
  // Contas correntes ativas, para a baixa em banco (2026-08-14). `tamanho: 200` porque o seletor
  // é um <select> simples — o tenant típico tem poucas contas, mas o limite evita o corte
  // silencioso já visto em outras telas (feedback "select truncado por paginação").
  const { data: paginaContasCorrente } = useQuery({
    queryKey: ['contas-corrente-ativas'],
    queryFn: () => listarContasCorrente({ status: 'ATIVOS', pagina: 1, tamanho: 200 }),
  })
  const contasCorrente = paginaContasCorrente?.itens
  const { data: fornecedoresEncontrados } = useQuery({
    queryKey: ['etiqueta-emissao-fornecedores', buscaFornecedor],
    queryFn: () => buscarFornecedoresEmissao(buscaFornecedor),
    enabled: buscaFornecedor.trim().length > 0 && !form.idFornecedor,
  })

  useEffect(() => {
    if (contaExistente) setForm(paraFormulario(contaExistente))
  }, [contaExistente])

  const escolherFornecedor = (f: FornecedorOpcaoEmissao) => {
    setForm((atual) => ({ ...atual, idFornecedor: f.idFornecedor, nomeFornecedorEscolhido: f.razaoSocial }))
    setBuscaFornecedor('')
    setErros((atual) => ({ ...atual, idFornecedor: undefined }))
  }

  const salvar = useMutation({
    mutationFn: () =>
      editando ? atualizarContaPagar(Number(id), paraRequisicao(form)) : criarContaPagar(paraRequisicao(form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contas-pagar'] })
      navigate('/contas-pagar', {
        state: {
          toast: {
            texto: editando ? 'Conta a pagar atualizada com sucesso.' : 'Conta a pagar cadastrada com sucesso.',
            tipo: 'sucesso',
          },
        },
      })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar a conta a pagar.'),
  })

  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))

  const validarEEnviar = () => {
    if (somenteLeitura) return

    const novosErros: ErrosCampo = {
      idFornecedor: validarCampo('idFornecedor', form),
      idEmpresa: validarCampo('idEmpresa', form),
      idPlanoContas: validarCampo('idPlanoContas', form),
      dataLancamentoTexto: validarCampo('dataLancamentoTexto', form),
      dataVencimentoTexto: validarCampo('dataVencimentoTexto', form),
      valorPagarTexto: validarCampo('valorPagarTexto', form),
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
            <IconeContasPagar size={34} />
            <h1>Contas a Pagar / Pagas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="financeiro.contaspagar.form" />
            <BotaoFecharTela />
            {!somenteLeitura && (
              <button type="submit" form="form-contas-pagar" className="btn" disabled={salvar.isPending}>
                {salvar.isPending ? 'Salvando…' : 'Salvar'}
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <form
          id="form-contas-pagar"
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
                  <label htmlFor="cp-fornecedor">Fornecedor *</label>
                  {form.idFornecedor ? (
                    <p className="muted" style={{ marginTop: 8 }}>
                      <strong>{form.nomeFornecedorEscolhido}</strong>{' '}
                      {!somenteLeitura && (
                        <button
                          type="button"
                          className="btn ghost"
                          onClick={() => {
                            setForm((f) => ({ ...f, idFornecedor: '', nomeFornecedorEscolhido: '' }))
                            aoSairDoCampo('idFornecedor')({} as FocusEvent)
                          }}
                        >
                          Trocar
                        </button>
                      )}
                    </p>
                  ) : (
                    <>
                      <input
                        id="cp-fornecedor"
                        autoFocus={!editando}
                        placeholder="Buscar fornecedor…"
                        value={buscaFornecedor}
                        onChange={(e) => setBuscaFornecedor(e.target.value.toUpperCase())}
                        onBlur={aoSairDoCampo('idFornecedor')}
                      />
                      {fornecedoresEncontrados && fornecedoresEncontrados.length > 0 && (
                        <div className="table-wrap" style={{ maxHeight: 140, marginTop: 8 }}>
                          <table className="table table-compacta">
                            <tbody>
                              {fornecedoresEncontrados.map((f) => (
                                <tr key={f.idFornecedor} tabIndex={0} onClick={() => escolherFornecedor(f)}>
                                  <td>{f.razaoSocial}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </>
                  )}
                  {erros.idFornecedor && <p className="erro-campo">{erros.idFornecedor}</p>}
                </div>
                <div className="col-4">
                  <label htmlFor="cp-empresa">Empresa *</label>
                  <select
                    id="cp-empresa"
                    value={form.idEmpresa}
                    onChange={(e) => setForm((f) => ({ ...f, idEmpresa: e.target.value === '' ? '' : Number(e.target.value) }))}
                    onBlur={aoSairDoCampo('idEmpresa')}
                  >
                    <option value="">Selecione…</option>
                    {(empresas ?? []).map((emp) => (
                      <option key={emp.idEmpresa} value={emp.idEmpresa}>
                        {emp.nomeFantasia ?? emp.razaoSocial}
                      </option>
                    ))}
                  </select>
                  {erros.idEmpresa && <p className="erro-campo">{erros.idEmpresa}</p>}
                </div>
              </div>

              <div className="form-grid">
                <div className="col-4">
                  <label htmlFor="cp-plano-contas">Plano de Contas *</label>
                  <SeletorPlanoContas
                    id="cp-plano-contas"
                    value={form.idPlanoContas}
                    onChange={(idPlanoContas) => {
                      setForm((f) => ({ ...f, idPlanoContas }))
                      setErros((atual) => ({ ...atual, idPlanoContas: undefined }))
                    }}
                    onBlur={() => setErros((atual) => ({ ...atual, idPlanoContas: validarCampo('idPlanoContas', form) }))}
                  />
                  {erros.idPlanoContas && <p className="erro-campo">{erros.idPlanoContas}</p>}
                </div>
                <div className="col-4">
                  <label htmlFor="cp-nota-fiscal">Nº Nota Fiscal</label>
                  <input
                    id="cp-nota-fiscal"
                    inputMode="numeric"
                    value={form.notaFiscalTexto}
                    onChange={(e) => setForm((f) => ({ ...f, notaFiscalTexto: e.target.value.replace(/\D/g, '') }))}
                    placeholder="Opcional"
                  />
                </div>
                <div className="col-4">
                  <label htmlFor="cp-duplicata">Duplicata</label>
                  <input
                    id="cp-duplicata"
                    value={form.numeroDuplicata}
                    onChange={(e) => setForm((f) => ({ ...f, numeroDuplicata: maiusculas(e.target.value) }))}
                    placeholder="Opcional"
                  />
                </div>
              </div>
            </section>

            <section className="section">
              <p className="section-label">Datas e Valores</p>

              <div className="form-grid">
                <div className="col-3">
                  <label htmlFor="cp-data-lancamento">Data de Lançamento *</label>
                  <input
                    id="cp-data-lancamento"
                    className="mono"
                    inputMode="numeric"
                    placeholder="dd/mm/aaaa"
                    value={form.dataLancamentoTexto}
                    onChange={(e) => setForm((f) => ({ ...f, dataLancamentoTexto: mascararData(e.target.value) }))}
                    onFocus={(e) => e.target.select()}
                    onBlur={aoSairDoCampo('dataLancamentoTexto')}
                  />
                  {erros.dataLancamentoTexto && <p className="erro-campo">{erros.dataLancamentoTexto}</p>}
                </div>
                <div className="col-3">
                  <label htmlFor="cp-data-vencimento">Data de Vencimento *</label>
                  <input
                    id="cp-data-vencimento"
                    className="mono"
                    inputMode="numeric"
                    placeholder="dd/mm/aaaa"
                    value={form.dataVencimentoTexto}
                    onChange={(e) => setForm((f) => ({ ...f, dataVencimentoTexto: mascararData(e.target.value) }))}
                    onFocus={(e) => e.target.select()}
                    onBlur={aoSairDoCampo('dataVencimentoTexto')}
                  />
                  {erros.dataVencimentoTexto && <p className="erro-campo">{erros.dataVencimentoTexto}</p>}
                </div>
                <div className="col-3">
                  <label htmlFor="cp-valor-pagar">Valor a Pagar *</label>
                  <input
                    id="cp-valor-pagar"
                    className="mono"
                    inputMode="decimal"
                    value={form.valorPagarTexto}
                    onChange={(e) => setForm((f) => ({ ...f, valorPagarTexto: mascararMoeda(e.target.value) }))}
                    onFocus={(e) => e.target.select()}
                    onBlur={(e) => {
                      setForm((f) => ({ ...f, valorPagarTexto: formatarMoeda(desmascararMoeda(completarMoeda(e.target.value))) }))
                      aoSairDoCampo('valorPagarTexto')(e)
                    }}
                  />
                  {erros.valorPagarTexto && <p className="erro-campo">{erros.valorPagarTexto}</p>}
                </div>
              </div>

              <p className="section-label" style={{ marginTop: 16 }}>
                Pagamento
              </p>
              <p className="muted" style={{ marginTop: 0 }}>
                Preencha ao dar baixa nesta conta — não existe uma tela de "Pagar" separada, editar com estes campos já
                registra o pagamento.
              </p>
              <div className="form-grid">
                <div className="col-3">
                  <label htmlFor="cp-data-pagamento">Data de Pagamento</label>
                  <input
                    id="cp-data-pagamento"
                    className="mono"
                    inputMode="numeric"
                    placeholder="dd/mm/aaaa"
                    value={form.dataPagamentoTexto}
                    onChange={(e) => setForm((f) => ({ ...f, dataPagamentoTexto: mascararData(e.target.value) }))}
                    onFocus={(e) => e.target.select()}
                  />
                </div>
                <div className="col-3">
                  <label htmlFor="cp-valor-pago">Valor Pago</label>
                  <input
                    id="cp-valor-pago"
                    className="mono"
                    inputMode="decimal"
                    value={form.valorPagoTexto}
                    onChange={(e) => setForm((f) => ({ ...f, valorPagoTexto: mascararMoeda(e.target.value) }))}
                    onFocus={(e) => e.target.select()}
                    onBlur={(e) =>
                      setForm((f) => ({ ...f, valorPagoTexto: formatarMoeda(desmascararMoeda(completarMoeda(e.target.value))) }))
                    }
                  />
                </div>
                <div className="col-3">
                  <label>Situação</label>
                  <div className="identificacao-linha" style={{ minHeight: 45, alignItems: 'center' }}>
                    <label className="checkbox-linha" style={{ marginTop: 0 }}>
                      <input
                        type="checkbox"
                        checked={form.documentoPago}
                        onChange={(e) => setForm((f) => ({ ...f, documentoPago: e.target.checked }))}
                      />
                      Documento Pago
                    </label>
                  </div>
                </div>
              </div>

              {/* Origem do dinheiro (2026-08-14) — só aparece quando há data de pagamento, porque
                  só aí ela é obrigatória. É o que faz o pagamento virar movimento de caixa ou de
                  conta corrente e alimentar o Fluxo de Caixa (docs/telas/fluxo-caixa.md). */}
              {form.dataPagamentoTexto.trim() !== '' && (
                <div className="form-grid">
                  <div className="col-4">
                    <label htmlFor="cp-origem-pagamento">De onde saiu o dinheiro *</label>
                    <select
                      id="cp-origem-pagamento"
                      value={form.origemPagamento}
                      onChange={(e) =>
                        setForm((f) => ({ ...f, origemPagamento: e.target.value as OrigemPagamento | '' }))
                      }
                    >
                      <option value="">Selecione…</option>
                      <option value="CAIXA">Caixa da loja (dinheiro)</option>
                      <option value="CONTA_CORRENTE">Conta corrente (banco)</option>
                    </select>
                  </div>
                  {form.origemPagamento === 'CONTA_CORRENTE' && (
                    <div className="col-4">
                      <label htmlFor="cp-conta-corrente">Conta corrente *</label>
                      <select
                        id="cp-conta-corrente"
                        value={form.idContaCorrente}
                        onChange={(e) => setForm((f) => ({ ...f, idContaCorrente: e.target.value }))}
                      >
                        <option value="">Selecione…</option>
                        {(contasCorrente ?? []).map((c) => (
                          <option key={c.idContaCorrente} value={c.idContaCorrente}>
                            {c.idContaCorrente} — {c.descricao}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}
                  <div className="col-4">
                    <p className="muted" style={{ fontSize: 12, marginTop: 28 }}>
                      {form.origemPagamento === 'CAIXA'
                        ? 'A saída é lançada no seu caixa aberto — é preciso ter caixa aberto.'
                        : 'A saída é lançada como débito na conta corrente escolhida.'}
                    </p>
                  </div>
                </div>
              )}

              <div className="form-grid">
                <div className="col-12">
                  <label htmlFor="cp-observacoes">Observações</label>
                  <input
                    id="cp-observacoes"
                    value={form.observacoes}
                    onChange={(e) => setForm((f) => ({ ...f, observacoes: maiusculas(e.target.value) }))}
                    placeholder="Opcional"
                  />
                </div>
              </div>
            </section>

            <InfoRegistro
              codigo={contaExistente?.idContaPagar}
              criadoEm={contaExistente?.criadoEm}
              atualizadoEm={contaExistente?.atualizadoEm}
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

      {/* "Voltar" aqui NÃO sai da tela (diferente do PDV/Recebimento de Crediário): só desfaz a
          escolha da origem, devolvendo o operador ao formulário — pagar pela conta corrente
          continua sendo uma saída legítima, e ele pode não querer abrir o caixa agora. */}
      {precisaAbrirCaixa && (
        <AberturaCaixaModal
          statusCaixa={statusCaixa}
          aoAbrir={() => queryClient.invalidateQueries({ queryKey: ['caixa-status'] })}
          aoVoltar={() => setForm((f) => ({ ...f, origemPagamento: '' }))}
        />
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
