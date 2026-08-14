import { useEffect, useState, type FocusEvent, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeParametros } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  atualizarConfiguracaoGeral,
  buscarConfiguracaoGeral,
  paraFormulario,
  paraRequisicao,
  type ConfiguracaoGeralFormState,
} from '../../lib/configuracaoGeral'
import { formatarDataHora } from '../../lib/datas'
import { aoTeclarEnterNoFormulario } from '../../lib/formularios'
import { completarPercentual, desmascararPercentual, mascararPercentual } from '../../lib/masks'
import SeletorPlanoContas from '../../components/SeletorPlanoContas'

const CHAVE_TELA = 'configuracao.geral.form'

const VAZIO: ConfiguracaoGeralFormState = {
  percentualDescontoVenda: '',
  jurosCrediarioDias: '0',
  jurosCrediario: '',
  multaCrediarioDias: '0',
  multaCrediario: '',
  cfgUsaCorGrade: false,
  cfgPermiteQtdDecimal: true,
  cfgExigeNumeroVendaDevolucao: false,
  cfgRateiaFreteEntrada: false,
  cfgReajustaPrecoEntrada: false,
  cfgConsisteValorContasPagar: true,
  idPlanoContasCompraMercadoria: '',
}

type CampoValidavel = 'percentualDescontoVenda' | 'jurosCrediarioDias' | 'jurosCrediario' | 'multaCrediarioDias' | 'multaCrediario'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

/** Todos os campos são NOT NULL no banco (V023) — validação é só de faixa, nunca de "vazio". */
function validarCampo(chave: CampoValidavel, f: ConfiguracaoGeralFormState): string | undefined {
  if (chave === 'percentualDescontoVenda' || chave === 'jurosCrediario' || chave === 'multaCrediario') {
    const valor = desmascararPercentual(f[chave])
    return valor >= 0 && valor <= 100 ? undefined : 'Informe um percentual entre 0 e 100.'
  }
  const valor = Number(f[chave])
  return Number.isInteger(valor) && valor >= 0 ? undefined : 'Informe um número de dias válido (0 ou mais).'
}

/**
 * Parâmetros do sistema (`cfg_geral`, docs/telas/configuracao-geral.md) — diferente de toda
 * tela de `cadastros`: é um singleton por tenant (sem lista, sem criar/excluir, só
 * ler/atualizar), acessível **somente a ADMIN** (a rota já é protegida por `RequireAdmin`;
 * o backend reforça o mesmo em cada chamada). Sem `InfoRegistro` — não há `criado_em` nem um
 * "código" de registro nesta tabela, só a data de atualização, mostrada abaixo do título.
 */
export default function ConfiguracaoGeralForm() {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<ConfiguracaoGeralFormState>(VAZIO)
  const [erros, setErros] = useState<ErrosCampo>({})
  const [toast, setToast] = useState('')
  const [toastTipo, setToastTipo] = useState<TipoToast>('erro')

  const { data: configuracao } = useQuery({
    queryKey: ['config-geral'],
    queryFn: buscarConfiguracaoGeral,
  })


  useEffect(() => {
    if (configuracao) setForm(paraFormulario(configuracao))
  }, [configuracao])

  const salvar = useMutation({
    mutationFn: () => atualizarConfiguracaoGeral(paraRequisicao(form)),
    onSuccess: (resposta) => {
      queryClient.setQueryData(['config-geral'], resposta)
      // As flags "leves" (abertas a qualquer papel, usadas por outras telas — Produto, PDV,
      // Transferência, Devolução de Produtos etc.) vivem em query keys próprias, separadas de
      // `config-geral`; sem invalidar aqui, uma tela já visitada nesta sessão (navegação por
      // SPA, sem recarregar a página) continua servindo o valor antigo do cache até expirar.
      queryClient.invalidateQueries({ queryKey: ['usa-cor-grade'] })
      queryClient.invalidateQueries({ queryKey: ['desconto-venda'] })
      queryClient.invalidateQueries({ queryKey: ['permite-qtd-decimal'] })
      queryClient.invalidateQueries({ queryKey: ['exige-numero-venda-devolucao'] })
      queryClient.invalidateQueries({ queryKey: ['rateia-frete-entrada'] })
      queryClient.invalidateQueries({ queryKey: ['reajusta-preco-entrada'] })
      queryClient.invalidateQueries({ queryKey: ['consiste-valor-contas-pagar'] })
      setToastTipo('sucesso')
      setToast('Parâmetros salvos.')
    },
    onError: (e: unknown) => {
      setToastTipo('erro')
      setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar os parâmetros.')
    },
  })

  const aoSairDoCampo = (chave: CampoValidavel) => (_e: FocusEvent) =>
    setErros((atual) => ({ ...atual, [chave]: validarCampo(chave, form) }))

  const submeter = (e: FormEvent) => {
    e.preventDefault()
    const novosErros: ErrosCampo = {
      percentualDescontoVenda: validarCampo('percentualDescontoVenda', form),
      jurosCrediarioDias: validarCampo('jurosCrediarioDias', form),
      jurosCrediario: validarCampo('jurosCrediario', form),
      multaCrediarioDias: validarCampo('multaCrediarioDias', form),
      multaCrediario: validarCampo('multaCrediario', form),
    }
    setErros(novosErros)
    if (Object.values(novosErros).some(Boolean)) {
      setToastTipo('erro')
      setToast('Corrija os campos destacados antes de salvar.')
      return
    }
    salvar.mutate()
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeParametros size={34} />
            <div>
              <h1>Parâmetros do Sistema</h1>
              {configuracao && (
                <p className="muted" style={{ margin: 0 }}>
                  Última atualização: {formatarDataHora(configuracao.atualizadoEm)}
                </p>
              )}
            </div>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <button type="submit" form="form-config-geral" className="btn" disabled={salvar.isPending}>
              {salvar.isPending ? 'Salvando…' : 'Salvar'}
            </button>
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form
        id="form-config-geral"
        className="card form-secoes form-secoes-larga"
        onSubmit={submeter}
        onKeyDown={aoTeclarEnterNoFormulario}
        noValidate
      >
        <section className="section">
          <p className="section-label">Vendas</p>

          <div className="form-grid">
            <div className="col-4">
              <label htmlFor="percentualDescontoVenda">Desconto máximo em venda (%) *</label>
              <input
                id="percentualDescontoVenda"
                autoFocus
                value={form.percentualDescontoVenda}
                onChange={(e) =>
                  setForm((f) => ({ ...f, percentualDescontoVenda: mascararPercentual(e.target.value) }))
                }
                onBlur={(e) => {
                  setForm((f) => ({ ...f, percentualDescontoVenda: completarPercentual(f.percentualDescontoVenda) }))
                  aoSairDoCampo('percentualDescontoVenda')(e)
                }}
              />
              {erros.percentualDescontoVenda && <p className="erro-campo">{erros.percentualDescontoVenda}</p>}
            </div>
            <div className="col-6">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={form.cfgExigeNumeroVendaDevolucao}
                  onChange={(e) => setForm((f) => ({ ...f, cfgExigeNumeroVendaDevolucao: e.target.checked }))}
                />
                Exigir número da venda na Devolução de Produtos
              </label>
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Ligado: o operador precisa informar o número da venda de origem antes de gravar a
                devolução, e só pode devolver produtos que fizeram parte dela. Desligado: o campo
                continua opcional (padrão) — sem ele, qualquer produto pode ser devolvido livremente.
              </p>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Catálogo</p>

          <div className="form-grid">
            <div className="col-6">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={form.cfgUsaCorGrade}
                  onChange={(e) => setForm((f) => ({ ...f, cfgUsaCorGrade: e.target.checked }))}
                />
                Usa cor/grade — calçados e confecções
              </label>
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Ligado: o cadastro de Produto ganha o campo Grade (obrigatório) e as variações
                passam a ter cor e tamanho. Desligado: cada produto é seu próprio SKU (a maioria
                dos segmentos — utilidades domésticas, brinquedos, cosméticos, armarinhos, óticas…).
              </p>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Estoque</p>

          <div className="form-grid">
            <div className="col-6">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={form.cfgPermiteQtdDecimal}
                  onChange={(e) => setForm((f) => ({ ...f, cfgPermiteQtdDecimal: e.target.checked }))}
                />
                Permite quantidade decimal para produtos
              </label>
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Ligado: quantidade de produto (venda, transferência, histórico) aceita até 3 casas
                decimais (ex.: 1,500 kg). Desligado: quantidade sempre inteira.
              </p>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Compras</p>
          <p className="muted" style={{ marginTop: -4 }}>
            Usados pela Entrada de Produtos por Compra ao confirmar uma nota.
          </p>

          <div className="form-grid">
            <div className="col-6">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={form.cfgRateiaFreteEntrada}
                  onChange={(e) => setForm((f) => ({ ...f, cfgRateiaFreteEntrada: e.target.checked }))}
                />
                Ratear frete/IPI/ICMS-ST no custo do item
              </label>
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Ligado: o valor de frete/IPI/ICMS-ST da nota é distribuído entre os itens e somado
                ao custo unitário de cada um. Desligado: só o valor do produto vira custo.
              </p>
            </div>
            <div className="col-6">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={form.cfgReajustaPrecoEntrada}
                  onChange={(e) => setForm((f) => ({ ...f, cfgReajustaPrecoEntrada: e.target.checked }))}
                />
                Reajustar preço do produto automaticamente
              </label>
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Ligado: ao confirmar a entrada, o custo e o preço de venda do produto são
                atualizados pelo valor da compra. Desligado: só o movimento de estoque é gravado,
                o preço do produto não muda.
              </p>
            </div>
            <div className="col-6">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={form.cfgConsisteValorContasPagar}
                  onChange={(e) => setForm((f) => ({ ...f, cfgConsisteValorContasPagar: e.target.checked }))}
                />
                Consistir valor das contas a pagar na entrada
              </label>
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Ligado: a soma das duplicatas precisa ser igual ao total dos produtos lançados —
                uma entrada de R$ 1.500,00 só é confirmada com duplicatas somando R$ 1.500,00.
                Desligado: a divergência é permitida (adiantamento, parte à vista, nota
                parcialmente financiada).
              </p>
            </div>
            <div className="col-6">
              <label htmlFor="idPlanoContasCompraMercadoria">Plano de Contas da Compra *</label>
              <SeletorPlanoContas
                id="idPlanoContasCompraMercadoria"
                value={form.idPlanoContasCompraMercadoria}
                onChange={(idPlanoContasCompraMercadoria) => setForm((f) => ({ ...f, idPlanoContasCompraMercadoria }))}
                apenasAnaliticas
              />
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Plano de contas usado nas contas a pagar geradas pela Entrada de Produtos por
                Compra — não é o plano do fornecedor, é uma conta de custo do próprio tenant.
              </p>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Crediário</p>
          <p className="muted" style={{ marginTop: -4 }}>
            Usados pela tela de Recebimento de Crediário para calcular multa e juros automaticamente
            sobre parcelas vencidas.
          </p>

          <div className="form-grid">
            <div className="col-3">
              <label htmlFor="jurosCrediarioDias">Juros após (dias) *</label>
              <input
                id="jurosCrediarioDias"
                inputMode="numeric"
                value={form.jurosCrediarioDias}
                onChange={(e) => setForm((f) => ({ ...f, jurosCrediarioDias: e.target.value.replace(/\D/g, '') }))}
                onBlur={aoSairDoCampo('jurosCrediarioDias')}
              />
              {erros.jurosCrediarioDias && <p className="erro-campo">{erros.jurosCrediarioDias}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="jurosCrediario">Juros (%) *</label>
              <input
                id="jurosCrediario"
                value={form.jurosCrediario}
                onChange={(e) => setForm((f) => ({ ...f, jurosCrediario: mascararPercentual(e.target.value) }))}
                onBlur={(e) => {
                  setForm((f) => ({ ...f, jurosCrediario: completarPercentual(f.jurosCrediario) }))
                  aoSairDoCampo('jurosCrediario')(e)
                }}
              />
              {erros.jurosCrediario && <p className="erro-campo">{erros.jurosCrediario}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="multaCrediarioDias">Multa após (dias) *</label>
              <input
                id="multaCrediarioDias"
                inputMode="numeric"
                value={form.multaCrediarioDias}
                onChange={(e) => setForm((f) => ({ ...f, multaCrediarioDias: e.target.value.replace(/\D/g, '') }))}
                onBlur={aoSairDoCampo('multaCrediarioDias')}
              />
              {erros.multaCrediarioDias && <p className="erro-campo">{erros.multaCrediarioDias}</p>}
            </div>
            <div className="col-3">
              <label htmlFor="multaCrediario">Multa (%) *</label>
              <input
                id="multaCrediario"
                value={form.multaCrediario}
                onChange={(e) => setForm((f) => ({ ...f, multaCrediario: mascararPercentual(e.target.value) }))}
                onBlur={(e) => {
                  setForm((f) => ({ ...f, multaCrediario: completarPercentual(f.multaCrediario) }))
                  aoSairDoCampo('multaCrediario')(e)
                }}
              />
              {erros.multaCrediario && <p className="erro-campo">{erros.multaCrediario}</p>}
            </div>
          </div>
        </section>
      </form>
      </div>

      {toast && <Toast mensagem={toast} tipo={toastTipo} aoFechar={() => setToast('')} />}
    </div>
  )
}
