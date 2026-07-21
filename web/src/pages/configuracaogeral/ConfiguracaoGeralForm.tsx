import { useEffect, useState, type FocusEvent, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { IconeParametros } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  atualizarConfiguracaoGeral,
  buscarConfiguracaoGeral,
  paraFormulario,
  paraRequisicao,
  type ConfiguracaoGeralFormState,
} from '../../lib/configuracaoGeral'
import { formatarDataHora } from '../../lib/datas'
import { mascararPercentual } from '../../lib/masks'

const CHAVE_TELA = 'configuracao.geral.form'

const VAZIO: ConfiguracaoGeralFormState = {
  percentualDescontoVenda: '',
  jurosCrediarioDias: '0',
  jurosCrediario: '',
  multaCrediarioDias: '0',
  multaCrediario: '',
  cfgUsaVarianteLinha: true,
  cfgUsaVarianteColuna: true,
}

type CampoValidavel = 'percentualDescontoVenda' | 'jurosCrediarioDias' | 'jurosCrediario' | 'multaCrediarioDias' | 'multaCrediario'
type ErrosCampo = Partial<Record<CampoValidavel, string>>

/** Todos os campos são NOT NULL no banco (V023) — validação é só de faixa, nunca de "vazio". */
function validarCampo(chave: CampoValidavel, f: ConfiguracaoGeralFormState): string | undefined {
  if (chave === 'percentualDescontoVenda' || chave === 'jurosCrediario' || chave === 'multaCrediario') {
    const digitos = f[chave].replace(/\D/g, '')
    const valor = digitos ? Number(digitos) / 100 : 0
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
      setToast('Parâmetros salvos.')
    },
    onError: (e: unknown) =>
      setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar os parâmetros.'),
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
          </div>
        </div>
      </div>

      <div className="lista-corpo">
      <form id="form-config-geral" className="card form-secoes form-secoes-larga" onSubmit={submeter} noValidate>
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
                onBlur={aoSairDoCampo('percentualDescontoVenda')}
              />
              {erros.percentualDescontoVenda && <p className="erro-campo">{erros.percentualDescontoVenda}</p>}
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
                  checked={form.cfgUsaVarianteLinha}
                  onChange={(e) => setForm((f) => ({ ...f, cfgUsaVarianteLinha: e.target.checked }))}
                />
                Usa variante em linha (ex.: cor)
              </label>
            </div>
            <div className="col-6">
              <label className="checkbox-linha" style={{ marginTop: 0 }}>
                <input
                  type="checkbox"
                  checked={form.cfgUsaVarianteColuna}
                  onChange={(e) => setForm((f) => ({ ...f, cfgUsaVarianteColuna: e.target.checked }))}
                />
                Usa variante em coluna (ex.: tamanho/voltagem)
              </label>
            </div>
          </div>
        </section>

        <section className="section">
          <p className="section-label">Crediário (Fase 2)</p>
          <p className="muted" style={{ marginTop: -4 }}>
            O módulo de crediário ainda não está implementado — estes valores ficam prontos para quando
            estiver.
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
                onBlur={aoSairDoCampo('jurosCrediario')}
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
                onBlur={aoSairDoCampo('multaCrediario')}
              />
              {erros.multaCrediario && <p className="erro-campo">{erros.multaCrediario}</p>}
            </div>
          </div>
        </section>
      </form>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
