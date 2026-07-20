import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  buscarConfiguracaoTela,
  salvarConfiguracaoTela,
  type ConfiguracaoCampo,
} from '../../lib/configuracaoTela'

const CHAVE_TELA = 'cadastros.cliente.form'

/** Rótulos amigáveis dos campos configuráveis (mesmos nomes usados em ClienteForm). */
const ROTULOS: Record<string, string> = {
  cpfCnpj: 'CPF/CNPJ',
  rgIe: 'RG/Inscrição Estadual',
  email: 'E-mail',
  telefone: 'Celular',
  whatsapp: 'Id. WhatsApp',
  instagram: 'Instagram',
  facebook: 'Facebook',
  tiktok: 'TikTok',
  cep: 'CEP',
  endereco: 'Endereço',
  numero: 'Número',
  complemento: 'Complemento',
  bairro: 'Bairro',
  cidade: 'Cidade',
  estado: 'UF',
  limiteCredito: 'Limite de crédito',
}

/**
 * Configuração de tela do Cliente (ADMIN, docs/telas/configuracao-tela.md): escolhe quais
 * campos aparecem no formulário e quais são obrigatórios. Nome/Categoria não aparecem aqui —
 * são estruturalmente obrigatórios (NOT NULL no banco), não configuráveis.
 */
export default function ConfiguracaoTelaCliente() {
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
      navigate('/clientes')
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
    <div>
      <div className="topbar-tela">
        <div>
          <p className="eyebrow">Cadastros</p>
          <h1 style={{ marginTop: 4 }}>Configurar tela de Cliente</h1>
        </div>
      </div>

      <div className="card" style={{ marginTop: 16 }}>
        <p className="muted" style={{ marginTop: 0 }}>
          Escolha quais campos aparecem no formulário de cliente e quais são obrigatórios. Nome e
          Categoria sempre aparecem e são sempre obrigatórios (não configuráveis).
        </p>

        {isLoading ? (
          <p className="muted">Carregando…</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Campo</th>
                <th>Visível</th>
                <th>Obrigatório</th>
              </tr>
            </thead>
            <tbody>
              {campos.map((c) => (
                <tr key={c.campo}>
                  <td>{ROTULOS[c.campo] ?? c.campo}</td>
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`Exibir ${ROTULOS[c.campo] ?? c.campo}`}
                      checked={c.visivel}
                      onChange={(e) => alternarVisivel(c.campo, e.target.checked)}
                    />
                  </td>
                  <td>
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

        <div className="footer-bar">
          <button type="button" className="btn ghost" onClick={() => navigate('/clientes')}>
            Cancelar
          </button>
          <button type="button" className="btn" disabled={salvar.isPending} onClick={() => salvar.mutate(campos)}>
            {salvar.isPending ? 'Salvando…' : 'Salvar'}
          </button>
        </div>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
