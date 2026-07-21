import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { IconeEngrenagem } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  buscarConfiguracaoTela,
  salvarConfiguracaoTela,
  type ConfiguracaoCampo,
} from '../../lib/configuracaoTela'

const CHAVE_TELA = 'cadastros.fornecedor.form'

/** Rótulos amigáveis dos campos configuráveis (mesmos nomes usados em FornecedorForm). */
const ROTULOS: Record<string, string> = {
  nomeFantasia: 'Nome Fantasia',
  cnpj: 'CNPJ',
  inscricaoEstadual: 'Inscrição Estadual',
  email: 'E-mail',
  telefone: 'Telefone',
  cep: 'CEP',
  endereco: 'Endereço',
  numero: 'Número',
  bairro: 'Bairro',
  cidade: 'Cidade',
  estado: 'UF',
}

/**
 * Configuração de tela do Fornecedor (ADMIN, docs/telas/configuracao-tela.md): escolhe quais
 * campos aparecem no formulário e quais são obrigatórios. Razão Social e Plano de Contas não
 * aparecem aqui — são estruturalmente obrigatórios (NOT NULL no banco), não configuráveis.
 */
export default function ConfiguracaoTelaFornecedor() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [campos, setCampos] = useState<ConfiguracaoCampo[]>([])
  const [toast, setToast] = useState('')

  const { data, isLoading } = useQuery({
    queryKey: ['config-tela', CHAVE_TELA],
    queryFn: () => buscarConfiguracaoTela(CHAVE_TELA),
  })

  useEffect(() => {
    if (data) setCampos(data)
  }, [data])

  const salvar = useMutation({
    mutationFn: (novaConfig: ConfiguracaoCampo[]) => salvarConfiguracaoTela(CHAVE_TELA, novaConfig),
    onSuccess: (resposta) => {
      queryClient.setQueryData(['config-tela', CHAVE_TELA], resposta)
      queryClient.invalidateQueries({ queryKey: ['config-tela', CHAVE_TELA] })
      navigate('/fornecedores')
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar a configuração.'),
  })

  const alternarVisivel = (campo: string, visivel: boolean) =>
    setCampos((atual) =>
      atual.map((c) => (c.campo === campo ? { ...c, visivel, obrigatorio: visivel ? c.obrigatorio : false } : c)),
    )

  const alternarObrigatorio = (campo: string, obrigatorio: boolean) =>
    setCampos((atual) => atual.map((c) => (c.campo === campo ? { ...c, obrigatorio } : c)))

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEngrenagem size={29} />
            <h1>Configurar tela de Fornecedor</h1>
          </div>
          <div className="topbar-acoes">
            <button type="button" className="btn ghost" onClick={() => navigate('/fornecedores')}>
              Cancelar
            </button>
            <button type="button" className="btn" disabled={salvar.isPending} onClick={() => salvar.mutate(campos)}>
              {salvar.isPending ? 'Salvando…' : 'Salvar'}
            </button>
          </div>
        </div>

        <div className="card">
          <p className="muted" style={{ margin: 0 }}>
            Escolha quais campos aparecem no formulário de fornecedor e quais são obrigatórios. Razão
            Social e Plano de Contas sempre aparecem e são sempre obrigatórios (não configuráveis).
          </p>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : (
            <table className="table table-compacta table-config-campos">
              <thead>
                <tr>
                  <th>Campo</th>
                  <th className="col-checkbox">Visível</th>
                  <th className="col-checkbox">Obrigatório</th>
                </tr>
              </thead>
              <tbody>
                {campos.map((c) => (
                  <tr key={c.campo}>
                    <td>{ROTULOS[c.campo] ?? c.campo}</td>
                    <td className="col-checkbox">
                      <input
                        type="checkbox"
                        aria-label={`Exibir ${ROTULOS[c.campo] ?? c.campo}`}
                        checked={c.visivel}
                        onChange={(e) => alternarVisivel(c.campo, e.target.checked)}
                      />
                    </td>
                    <td className="col-checkbox">
                      <input
                        type="checkbox"
                        aria-label={`Obrigatório ${ROTULOS[c.campo] ?? c.campo}`}
                        checked={c.obrigatorio}
                        disabled={!c.visivel}
                        onChange={(e) => alternarObrigatorio(c.campo, e.target.checked)}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
