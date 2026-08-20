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
  cfgPermiteEstoqueNegativo: true,
  cfgDiasValidadeOrcamento: '15',
  cfgExigeNumeroVendaDevolucao: false,
  cfgRateiaFreteEntrada: false,
  cfgReajustaPrecoEntrada: false,
  cfgConsisteValorContasPagar: true,
  idPlanoContasCompraMercadoria: '',
  cfgEmiteFiscalAposVenda: true,
}

type CampoValidavel = 'percentualDescontoVenda' | 'jurosCrediarioDias' | 'jurosCrediario' | 'multaCrediarioDias' | 'multaCrediario'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

type Aba = 'vendas' | 'estoque' | 'compras' | 'crediario'

const ABAS: Array<{ chave: Aba; rotulo: string }> = [
  { chave: 'vendas', rotulo: 'Vendas' },
  { chave: 'estoque', rotulo: 'Estoque' },
  { chave: 'compras', rotulo: 'Compras' },
  { chave: 'crediario', rotulo: 'Crediário' },
]

/** Em qual aba mora cada campo validado (2026-08-19, reorganização em abas) — usado só para
 *  levar o usuário até o erro quando ele está numa aba diferente da que falhou ao salvar. */
const ABA_DO_CAMPO: Record<CampoValidavel, Aba> = {
  percentualDescontoVenda: 'vendas',
  jurosCrediarioDias: 'crediario',
  jurosCrediario: 'crediario',
  multaCrediarioDias: 'crediario',
  multaCrediario: 'crediario',
}

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
  const [aba, setAba] = useState<Aba>('vendas')

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
      // ⚠️ A chave tem de ser a MESMA que o PDV usa em FormaPagamentoModal. Até 2026-08-20 aqui
      // estava 'desconto-venda' — que não casa por prefixo com 'pdv-desconto-venda' e portanto não
      // atingia query nenhuma: o teto de desconto continuava o antigo no PDV até um F5.
      queryClient.invalidateQueries({ queryKey: ['pdv-desconto-venda'] })
      queryClient.invalidateQueries({ queryKey: ['permite-qtd-decimal'] })
      queryClient.invalidateQueries({ queryKey: ['exige-numero-venda-devolucao'] })
      queryClient.invalidateQueries({ queryKey: ['rateia-frete-entrada'] })
      queryClient.invalidateQueries({ queryKey: ['reajusta-preco-entrada'] })
      queryClient.invalidateQueries({ queryKey: ['consiste-valor-contas-pagar'] })
      queryClient.invalidateQueries({ queryKey: ['emite-fiscal-apos-venda'] })
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
    const primeiroCampoComErro = (Object.keys(novosErros) as CampoValidavel[]).find((c) => novosErros[c])
    if (primeiroCampoComErro) {
      setAba(ABA_DO_CAMPO[primeiroCampoComErro])
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
        <div className="abas-nav" role="tablist">
          {ABAS.map((a) => (
            <button
              key={a.chave}
              type="button"
              role="tab"
              aria-selected={aba === a.chave}
              className={`aba-botao ${aba === a.chave ? 'ativa' : ''}`}
              onClick={() => setAba(a.chave)}
            >
              {a.rotulo}
            </button>
          ))}
        </div>

        {aba === 'vendas' && (
        <>
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
                <label htmlFor="cfgDiasValidadeOrcamento">Validade padrão do orçamento (dias)</label>
                <input
                  id="cfgDiasValidadeOrcamento"
                  inputMode="numeric"
                  value={form.cfgDiasValidadeOrcamento}
                  onChange={(e) =>
                    setForm((f) => ({
                      ...f,
                      cfgDiasValidadeOrcamento: e.target.value.replace(/\D/g, '').slice(0, 3),
                    }))
                  }
                  onFocus={(e) => e.target.select()}
                />
                <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                  Quantos dias somar a hoje para <strong>sugerir</strong> a validade ao emitir um
                  orçamento — o vendedor pode mudar caso a caso. Passando da validade o orçamento
                  vence sozinho e não vira mais venda.
                </p>
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
          <p className="section-label">Fiscal</p>

          <div className="form-grid">CAMPO
            <div className="col-6">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={form.cfgEmiteFiscalAposVenda}
                  onChange={(e) => setForm((f) => ({ ...f, cfgEmiteFiscalAposVenda: e.target.checked }))}
                />
                Emitir NFC-e/NF-e automaticamente após a venda
              </label>
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Ligado: assim que a venda é confirmada, o popup pergunta se o CPF do cliente entra
                na nota e, ao responder, emite na hora — o cupom vira DANFCE sozinho quando a
                SEFAZ autoriza. Desligado: o popup mostra direto a papeleta de venda, com um botão
                para o operador emitir a nota fiscal quando quiser (mesma pergunta de CPF).
              </p>
            </div>
          </div>
        </section>
        </>
        )}

        {aba === 'estoque' && (
        <>
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

            <div className="col-6">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={form.cfgPermiteEstoqueNegativo}
                  onChange={(e) => setForm((f) => ({ ...f, cfgPermiteEstoqueNegativo: e.target.checked }))}
                />
                Permite quantidade de estoque negativo
              </label>
              <p className="muted" style={{ fontSize: 12, marginTop: 4 }}>
                Ligado (padrão): o saldo pode ficar negativo e a venda nunca trava por cadastro
                desatualizado — a maioria das lojas não faz gestão de estoque. Desligue para
                controlar: aí nenhuma rotina tira mais do que existe (venda, transferência,
                devolução ao fornecedor, cancelamento de entrada, ajuste de balanço), e a operação
                para com "estoque insuficiente", dizendo qual produto e quanto há. O saldo é
                contado <strong>por empresa</strong>.
              </p>
            </div>
          </div>
        </section>
        </>
        )}

        {aba === 'compras' && (
        <>
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
        </>
        )}

        {aba === 'crediario' && (
        <>
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
        </>
        )}
      </form>
      </div>

      {toast && <Toast mensagem={toast} tipo={toastTipo} aoFechar={() => setToast('')} />}
    </div>
  )
}
