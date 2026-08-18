import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeEditar, IconeEmpresa } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { listarEmpresas } from '../../lib/empresas'

/**
 * Lista de empresas do tenant (2026-08-19) — sem paginação/busca (o v1 tem no máximo poucas
 * dezenas, mesmo critério de `EmpresaService.listar`), sem criar/excluir (a criação de empresa
 * não tem tela própria ainda; esta tela existe pra editar identificação/endereço/dados fiscais
 * do que já existe). Nasceu pra fechar uma lacuna real: a Conformidade Fiscal apontava CNPJ/
 * Inscrição Estadual/código de município IBGE/CNAE faltando, mas não havia onde preencher.
 */
export default function EmpresaLista() {
  const location = useLocation()
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(
    (location.state as { toast?: { texto: string; tipo: TipoToast } } | null)?.toast ?? null,
  )

  const { data: empresas, isLoading } = useQuery({ queryKey: ['empresas'], queryFn: listarEmpresas })

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeEmpresa size={34} />
            <h1>Empresas</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="identidade.empresa.lista" />
            <BotaoFecharTela />
          </div>
        </div>

        {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : !empresas || empresas.length === 0 ? (
            <p className="muted">Nenhuma empresa cadastrada.</p>
          ) : (
            <table className="table table-compacta">
              <thead>
                <tr>
                  <th>Código</th>
                  <th>Razão Social</th>
                  <th>Nome Fantasia</th>
                  <th>Status</th>
                  <th aria-label="Ações" />
                </tr>
              </thead>
              <tbody>
                {empresas.map((e) => (
                  <tr key={e.idEmpresa}>
                    <td>{e.codigoEmpresa}</td>
                    <td>{e.razaoSocial}</td>
                    <td>{e.nomeFantasia ?? '—'}</td>
                    <td>{e.ativo ? 'Ativa' : 'Inativa'}</td>
                    <td className="acoes-cell">
                      <Link
                        className="acao-icone acao-editar"
                        to={`/empresas/${e.idEmpresa}`}
                        aria-label={`Editar ${e.razaoSocial}`}
                        title="Editar"
                      >
                        <IconeEditar />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  )
}
