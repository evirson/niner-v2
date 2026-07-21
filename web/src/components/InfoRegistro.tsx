import { formatarDataHora } from '../lib/datas'

interface Props {
  /**
   * Chave primária do registro — numérica nas tabelas com surrogate (`id_cliente`,
   * `id_funcionario`) ou texto quando a PK é de negócio (`id_plano_contas`, ex. "3.1.001").
   */
  codigo: number | string | undefined
  criadoEm: string | undefined
  atualizadoEm: string | undefined
}

/**
 * Campos informativos de auditoria — **somente leitura**, gerados pelo banco: código do
 * registro, data de cadastro (`criado_em`) e data da última alteração (`atualizado_em`).
 *
 * <p>Convenção do projeto (2026-07-21): toda tela de cadastro cuja tabela tenha esses campos
 * (praticamente todo o domínio — 14 migrations os declaram) deve exibi-los no fim do
 * formulário, com este componente. Some ao **incluir** um registro novo (ainda não existem
 * valores) e nunca é editável, nem no modo de edição.
 */
export default function InfoRegistro({ codigo, criadoEm, atualizadoEm }: Props) {
  if (!codigo) return null

  return (
    <section className="section">
      <p className="section-label">Informações do registro</p>

      <div className="form-grid">
        <div className="col-4">
          <label htmlFor="info-codigo">Código</label>
          <input id="info-codigo" className="campo-leitura" readOnly tabIndex={-1} value={codigo} />
        </div>
        <div className="col-4">
          <label htmlFor="info-criado-em">Cadastrado em</label>
          <input
            id="info-criado-em"
            className="campo-leitura"
            readOnly
            tabIndex={-1}
            value={formatarDataHora(criadoEm)}
          />
        </div>
        <div className="col-4">
          <label htmlFor="info-atualizado-em">Última alteração</label>
          <input
            id="info-atualizado-em"
            className="campo-leitura"
            readOnly
            tabIndex={-1}
            value={formatarDataHora(atualizadoEm)}
          />
        </div>
      </div>
    </section>
  )
}
