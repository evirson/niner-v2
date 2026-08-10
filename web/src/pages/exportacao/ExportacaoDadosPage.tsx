import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import GaugeProgresso from '../../components/GaugeProgresso'
import { IconePedidos } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { ApiError } from '../../lib/api'
import {
  buscarDadosExportacao,
  exportarParaExcel,
  listarTabelasExportacao,
  type TabelaExportavel,
} from '../../lib/exportacao'

/** A exportação é 1 consulta no banco (sem loop linha a linha no backend pra reportar progresso
 *  de verdade, diferente da Importação de Dados) — então o gauge aqui usa o mesmo modo simulado
 *  já usado no CRM (`GaugeProgresso` sem `atual`/`total`), só trocando o rótulo entre as duas
 *  fases reais do processo: buscar os dados e montar o arquivo no navegador. */
type FaseExportacao = 'buscando' | 'gerando'

const CHAVE_TELA = 'configuracao.exportacao'

/**
 * Rotina de Exportação de Dados (Configurações) — irmã "de saída" da Rotina de Importação de
 * Dados: baixa o que já está cadastrado no Niner em planilha Excel. ADMIN-only (rota protegida
 * por `RequireAdmin`; o backend reforça o mesmo). Sem etapas — um clique busca os dados e já
 * gera o download, a planilha é montada no navegador (mesmo padrão do CRM).
 */
export default function ExportacaoDadosPage() {
  const [toast, setToast] = useState('')
  const [toastTipo, setToastTipo] = useState<TipoToast>('erro')
  const [tabelaEmAndamento, setTabelaEmAndamento] = useState<string | null>(null)
  const [fase, setFase] = useState<FaseExportacao>('buscando')

  const { data: tabelas } = useQuery({ queryKey: ['exportacao-tabelas'], queryFn: listarTabelasExportacao })

  const exportarMut = useMutation({
    mutationFn: async (t: TabelaExportavel) => {
      setTabelaEmAndamento(t.chave)
      setFase('buscando')
      const linhas = await buscarDadosExportacao(t.chave)
      if (linhas.length === 0) {
        throw new Error(`Nenhum registro encontrado em "${t.titulo}".`)
      }
      setFase('gerando')
      await exportarParaExcel(t.chave, t.titulo, linhas)
      return linhas.length
    },
    onSuccess: (total, t) => {
      setToastTipo('sucesso')
      setToast(`${total} registro(s) de "${t.titulo}" exportado(s).`)
      setTabelaEmAndamento(null)
    },
    onError: (e: unknown) => {
      setToastTipo('erro')
      setToast(e instanceof ApiError ? e.message : e instanceof Error ? e.message : 'Não foi possível exportar.')
      setTabelaEmAndamento(null)
    },
  })

  const tituloEmAndamento = tabelas?.find((t) => t.chave === tabelaEmAndamento)?.titulo ?? ''

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePedidos size={34} />
            <h1>Exportação de Dados</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela={CHAVE_TELA} />
            <BotaoFecharTela />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card" style={{ padding: 24 }}>
          <p className="section-label">Escolha a tabela</p>
          <p className="muted">Cada clique baixa uma planilha .xlsx com todos os registros já cadastrados.</p>
          <div className="form-grid" style={{ marginTop: 12 }}>
            {(tabelas ?? []).map((t) => (
              <div className="col-4" key={t.chave}>
                <button
                  type="button"
                  className="btn ghost wizard-tabela-btn"
                  disabled={exportarMut.isPending}
                  onClick={() => exportarMut.mutate(t)}
                >
                  <strong>{t.titulo}</strong>
                  <p className="muted" style={{ margin: '4px 0 0' }}>
                    {tabelaEmAndamento === t.chave && exportarMut.isPending
                      ? (fase === 'buscando' ? 'Buscando dados…' : 'Gerando planilha…')
                      : 'Baixar .xlsx'}
                  </p>
                </button>
              </div>
            ))}
          </div>

          {exportarMut.isPending && (
            <div style={{ marginTop: 24 }}>
              <GaugeProgresso
                rotulo={
                  (fase === 'buscando' ? 'Buscando dados de "' : 'Gerando planilha de "') + tituloEmAndamento + '"…'
                }
              />
            </div>
          )}
        </div>
      </div>

      {toast && <Toast mensagem={toast} tipo={toastTipo} aoFechar={() => setToast('')} />}
    </div>
  )
}
