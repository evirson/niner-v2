import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import EmpresaMultiSelect from '../../components/EmpresaMultiSelect'
import SeletorPlanoContas from '../../components/SeletorPlanoContas'
import type { Empresa } from '../../lib/empresas'
import {
  buscarFornecedoresEmissao,
  LIMITE_BUSCA_EMISSAO,
  type FornecedorOpcaoEmissao,
} from '../../lib/etiquetaEmissao'
import { mascararData } from '../../lib/masks'
import type { SituacaoConta } from '../../lib/relatorioContasPagar'

export interface FiltrosTextoContasPagar {
  dataLancamentoInicial: string
  dataLancamentoFinal: string
  dataVencimentoInicial: string
  dataVencimentoFinal: string
  dataPagamentoInicial: string
  dataPagamentoFinal: string
  idsEmpresa: number[]
  idFornecedor: number | null
  nomeFornecedor: string
  idPlanoContas: string
  situacao: SituacaoConta | ''
}

export const FILTROS_VAZIOS: FiltrosTextoContasPagar = {
  dataLancamentoInicial: '',
  dataLancamentoFinal: '',
  dataVencimentoInicial: '',
  dataVencimentoFinal: '',
  dataPagamentoInicial: '',
  dataPagamentoFinal: '',
  idsEmpresa: [],
  idFornecedor: null,
  nomeFornecedor: '',
  idPlanoContas: '',
  situacao: '',
}

export const OPCOES_SITUACAO: Array<{ chave: SituacaoConta | ''; rotulo: string }> = [
  { chave: '', rotulo: 'Todas' },
  { chave: 'ABERTA', rotulo: 'Em Aberto' },
  { chave: 'PAGA', rotulo: 'Pagas' },
]

/**
 * Um par de campos de data (dd/mm/aaaa), controlado como texto.
 *
 * ⚠️ Nunca `<input type="date">`: o widget nativo não consegue "selecionar tudo e sobrescrever ao
 * digitar" em navegador nenhum, e isso é requisito de produto aqui.
 */
function CampoPeriodo({
  rotulo,
  inicial,
  final,
  aoAlterarInicial,
  aoAlterarFinal,
  autoFocarInicial,
}: {
  rotulo: string
  inicial: string
  final: string
  aoAlterarInicial: (v: string) => void
  aoAlterarFinal: (v: string) => void
  autoFocarInicial?: boolean
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label className="muted" style={{ fontSize: 12 }}>
        {rotulo}
      </label>
      <div style={{ display: 'flex', gap: 6 }}>
        <input
          className="mono"
          placeholder="dd/mm/aaaa"
          value={inicial}
          autoFocus={autoFocarInicial}
          onFocus={(e) => e.target.select()}
          onChange={(e) => aoAlterarInicial(mascararData(e.target.value))}
          aria-label={`${rotulo} — início`}
        />
        <input
          className="mono"
          placeholder="dd/mm/aaaa"
          value={final}
          onFocus={(e) => e.target.select()}
          onChange={(e) => aoAlterarFinal(mascararData(e.target.value))}
          aria-label={`${rotulo} — fim`}
        />
      </div>
    </div>
  )
}

/**
 * Popup **obrigatório** de filtros do Relatório de Contas a Pagar / Pagas.
 *
 * Abre sozinho quando a tela é aberta — nada é buscado antes de o usuário escolher os filtros. Na
 * 1ª vez a única saída é "Voltar" (sai da tela); depois de gerar ao menos uma vez, "Cancelar"
 * fecha sem alterar o relatório já exibido. Mesmo padrão dos outros relatórios.
 */
export default function FiltrosContasPagarModal({
  valores,
  aoMudar,
  ehAdmin,
  empresas,
  podeGerar,
  primeiraVez,
  aoGerar,
  aoFechar,
}: {
  valores: FiltrosTextoContasPagar
  aoMudar: (parcial: Partial<FiltrosTextoContasPagar>) => void
  ehAdmin: boolean
  empresas: Empresa[]
  podeGerar: boolean
  primeiraVez: boolean
  aoGerar: () => void
  aoFechar: () => void
}) {
  const [buscaFornecedor, setBuscaFornecedor] = useState('')

  // ⚠️ Fornecedor por BUSCA, não por `<select>`: a lista é paginada, e um select carregado com uma
  // página só esconderia todos os fornecedores fora dela — o filtro pareceria não ter o que o
  // lojista procura. Mesmo mecanismo já usado na tela de Contas a Pagar.
  const { data: encontrados } = useQuery({
    queryKey: ['contas-pagar-relatorio-fornecedores', buscaFornecedor],
    queryFn: () => buscarFornecedoresEmissao(buscaFornecedor),
    enabled: buscaFornecedor.trim().length > 0 && valores.idFornecedor === null,
  })

  return (
    <div className="modal-overlay" onClick={primeiraVez ? undefined : aoFechar}>
      <div
        className="modal modal-medio"
        role="dialog"
        aria-label="Filtros do relatório"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginTop: 0 }}>Filtros do Relatório</h2>
        <p className="muted" style={{ marginTop: -4 }}>
          Informe ao menos um período completo (início e fim) — Lançamento, Vencimento ou Pagamento.
        </p>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <CampoPeriodo
            rotulo="Período de Lançamento"
            inicial={valores.dataLancamentoInicial}
            final={valores.dataLancamentoFinal}
            aoAlterarInicial={(v) => aoMudar({ dataLancamentoInicial: v })}
            aoAlterarFinal={(v) => aoMudar({ dataLancamentoFinal: v })}
            autoFocarInicial
          />
          <CampoPeriodo
            rotulo="Período de Vencimento"
            inicial={valores.dataVencimentoInicial}
            final={valores.dataVencimentoFinal}
            aoAlterarInicial={(v) => aoMudar({ dataVencimentoInicial: v })}
            aoAlterarFinal={(v) => aoMudar({ dataVencimentoFinal: v })}
          />
          <CampoPeriodo
            rotulo="Período de Pagamento"
            inicial={valores.dataPagamentoInicial}
            final={valores.dataPagamentoFinal}
            aoAlterarInicial={(v) => aoMudar({ dataPagamentoInicial: v })}
            aoAlterarFinal={(v) => aoMudar({ dataPagamentoFinal: v })}
          />

          {ehAdmin && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <label className="muted" style={{ fontSize: 12 }}>
                Empresas
              </label>
              <EmpresaMultiSelect
                empresas={empresas}
                selecionadas={valores.idsEmpresa}
                aoAlterar={(ids) => aoMudar({ idsEmpresa: ids })}
              />
            </div>
          )}

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Fornecedor
            </label>
            {valores.idFornecedor === null ? (
              <>
                <input
                  value={buscaFornecedor}
                  placeholder="Digite parte do nome…"
                  onChange={(e) => setBuscaFornecedor(e.target.value)}
                  aria-label="Buscar fornecedor"
                />
                {(encontrados ?? []).length > 0 && (
                  <div className="lista-sugestoes">
                    {(encontrados ?? []).map((f: FornecedorOpcaoEmissao) => (
                      <button
                        type="button"
                        key={f.idFornecedor}
                        className="btn ghost"
                        style={{ justifyContent: 'flex-start', width: '100%' }}
                        onClick={() => {
                          aoMudar({ idFornecedor: f.idFornecedor, nomeFornecedor: f.razaoSocial })
                          setBuscaFornecedor('')
                        }}
                      >
                        {f.razaoSocial}
                      </button>
                    ))}
                    {/* ⚠️ A busca corta em N: sem este aviso, o lojista conclui que o fornecedor
                        não existe quando ele só ficou fora dos primeiros resultados. */}
                    {(encontrados ?? []).length >= LIMITE_BUSCA_EMISSAO && (
                      <p className="muted" style={{ margin: 4 }}>
                        Mostrando os {LIMITE_BUSCA_EMISSAO} primeiros — refine a busca.
                      </p>
                    )}
                  </div>
                )}
              </>
            ) : (
              <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                <span className="badge">{valores.nomeFornecedor}</span>
                <button
                  type="button"
                  className="btn ghost"
                  onClick={() => aoMudar({ idFornecedor: null, nomeFornecedor: '' })}
                >
                  Trocar
                </button>
              </div>
            )}
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }} htmlFor="filtro-plano-contas">
              Plano de Contas
            </label>
            <SeletorPlanoContas
              id="filtro-plano-contas"
              value={valores.idPlanoContas}
              onChange={(codigo) => aoMudar({ idPlanoContas: codigo })}
              ariaLabel="Plano de contas"
            />
            {/* ⭐ Explica o casamento por prefixo: sem isto, escolher uma conta "de grupo" pareceria
                não funcionar — lançamento nunca cai em conta sintética. */}
            <p className="muted" style={{ fontSize: 12, margin: 0 }}>
              Escolhendo uma conta de grupo (terminada em 000), o relatório traz todas as contas
              dentro dela.
            </p>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label className="muted" style={{ fontSize: 12 }}>
              Situação
            </label>
            <select
              value={valores.situacao}
              onChange={(e) => aoMudar({ situacao: e.target.value as SituacaoConta | '' })}
              aria-label="Situação da conta"
            >
              {OPCOES_SITUACAO.map((o) => (
                <option key={o.chave} value={o.chave}>
                  {o.rotulo}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            {primeiraVez ? 'Voltar' : 'Cancelar'}
          </button>
          <button type="button" className="btn" disabled={!podeGerar} onClick={aoGerar}>
            Gerar Relatório
          </button>
        </div>
      </div>
    </div>
  )
}
