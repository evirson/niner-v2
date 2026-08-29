import CabecalhoModal from '../../components/CabecalhoModal'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeEmpresa, IconePainel } from '../../components/Icones'
import Toast, { type TipoToast } from '../../components/Toast'
import { mascararCpfCnpj } from '../../lib/masks'
import {
  buscarMinhaConta,
  consultarFatura,
  criarEmpresa,
  iniciarPagamento,
  listarFaixas,
  rotuloCompetencia,
  type Ciclo,
  type MinhaConta as MinhaContaDados,
  type NovaEmpresaForm,
  type PagamentoPix,
  type SituacaoUso,
} from '../../lib/minhaConta'
import { maiusculas } from '../../lib/texto'

/**
 * Minha Conta (ADR-015, docs/telas/painel-assinatura.md) — plano, cota de vendas do mês,
 * histórico e os CNPJs do tenant. ADMIN-only (a rota já está sob `RequireAdmin`; o servidor
 * checa de novo).
 *
 * <p>É a primeira tela do ERP que fala de <b>assinatura</b>: aqui é o que a loja paga à Vetor,
 * nunca o caixa da loja (Anexo A do plano de negócio). O vocabulário da tela segue essa
 * fronteira — "plano", "cota", "faixa"; nada de "conta a pagar".
 */
export default function MinhaConta() {
  const qc = useQueryClient()
  const [toast, setToast] = useState<{ texto: string; tipo: TipoToast } | null>(null)
  const [modalAberto, setModalAberto] = useState(false)
  const [modalPagamento, setModalPagamento] = useState(false)

  const { data, isLoading } = useQuery({ queryKey: ['minha-conta'], queryFn: buscarMinhaConta })

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconePainel size={34} />
            <h1>Minha Conta</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="plataforma.minha-conta" />
            <BotaoFecharTela />
          </div>
        </div>
        {toast && <Toast mensagem={toast.texto} tipo={toast.tipo} aoFechar={() => setToast(null)} />}
      </div>

      <div className="lista-corpo">
        {isLoading || !data ? (
          <p className="muted">Carregando…</p>
        ) : (
          <div className="minha-conta">
            <BlocoPlano dados={data} />
            <BlocoUso dados={data} aoAssinar={() => setModalPagamento(true)} />
            <BlocoHistorico dados={data} />
            <BlocoEmpresas dados={data} aoAdicionar={() => setModalAberto(true)} />
          </div>
        )}
      </div>

      {modalPagamento && (
        <ModalPagamento
          recomendada={data?.faixaRecomendada?.nome ?? null}
          aoFechar={() => setModalPagamento(false)}
          aoPagar={() => {
            setModalPagamento(false)
            setToast({ texto: 'Pagamento confirmado. Sua faixa já está valendo.', tipo: 'sucesso' })
            qc.invalidateQueries({ queryKey: ['minha-conta'] })
          }}
        />
      )}

      {modalAberto && (
        <ModalNovaEmpresa
          aoFechar={() => setModalAberto(false)}
          aoCriar={(mensagem) => {
            setModalAberto(false)
            setToast({ texto: mensagem, tipo: 'sucesso' })
            qc.invalidateQueries({ queryKey: ['minha-conta'] })
            qc.invalidateQueries({ queryKey: ['empresas'] })
          }}
        />
      )}
    </div>
  )
}

const REAL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

function BlocoPlano({ dados }: { dados: MinhaContaDados }) {
  const { plano } = dados
  return (
    <section className="card secao-conta">
      <div className="section-label">Plano atual</div>
      <div className="plano-linha">
        <strong className="plano-nome">{plano.nome}</strong>
        {plano.gratuito ? (
          <span className="plano-preco">R$ 0 · sem prazo de validade</span>
        ) : (
          <span className="plano-preco">
            {REAL.format(plano.precoMensal)}/mês · {REAL.format(plano.precoAnual)}/ano
          </span>
        )}
      </div>
      <p className="muted">
        {plano.gratuito
          ? 'Sem data de expiração — o que limita é o volume de vendas do mês. Todas as funções estão liberadas, inclusive a emissão fiscal, e CNPJs, usuários e produtos são ilimitados.'
          : 'Todas as funções liberadas. O que a faixa define é quantas vendas por mês estão incluídas.'}
      </p>
    </section>
  )
}

const TEXTO_SITUACAO: Record<SituacaoUso, string> = {
  NORMAL: 'Dentro da cota do mês.',
  ATENCAO: 'Você está chegando ao limite do mês.',
  TOLERANCIA: 'Limite do mês atingido — você está usando a folga de cortesia.',
  BLOQUEADO: 'Limite e folga esgotados: novas vendas estão bloqueadas até a virada do mês ou a assinatura de uma faixa.',
}

function BlocoUso({ dados, aoAssinar }: { dados: MinhaContaDados; aoAssinar: () => void }) {
  const { uso, faixaRecomendada } = dados
  const limite = uso.limite
  const percentual = limite ? Math.min(100, Math.round((uso.qtdVendas / limite) * 100)) : 0
  const zeraEm = new Date(uso.zeraEm + 'T00:00:00').toLocaleDateString('pt-BR')

  return (
    <section className={`card secao-conta uso-${uso.situacao.toLowerCase()}`}>
      <div className="section-label">Uso deste mês</div>

      {limite === null ? (
        <p className="uso-numero">{uso.qtdVendas} vendas · faixa sem limite</p>
      ) : (
        <>
          <p className="uso-numero">
            <strong>{uso.qtdVendas}</strong> de {limite} vendas
            {uso.situacao === 'TOLERANCIA' || uso.situacao === 'BLOQUEADO' ? (
              <span className="uso-detalhe"> · folga restante: {uso.toleranciaRestante} de {uso.tolerancia}</span>
            ) : (
              <span className="uso-detalhe"> · faltam {uso.restantes}</span>
            )}
          </p>
          <div className="barra-uso" role="img" aria-label={`${percentual}% da cota usada`}>
            <div className="barra-uso-preenchida" style={{ width: `${percentual}%` }} />
          </div>
        </>
      )}

      <p className="muted">{TEXTO_SITUACAO[uso.situacao]}</p>
      <p className="muted">
        O contador zera em {zeraEm} e soma as vendas de <b>todos os CNPJs</b> da conta.
      </p>

      {faixaRecomendada && uso.situacao !== 'NORMAL' && (
        <div className="faixa-sugerida">
          <p>
            Faixa indicada para o seu volume: <b>{faixaRecomendada.nome}</b> —{' '}
            {REAL.format(faixaRecomendada.precoMensal)}/mês (ou {REAL.format(faixaRecomendada.precoAnual)}/ano, 15% de
            desconto).
          </p>
          <button type="button" className="btn btn-primary" onClick={aoAssinar}>
            Assinar esta faixa
          </button>
        </div>
      )}
      {uso.situacao === 'NORMAL' && (
        <p className="muted">
          <button type="button" className="btn-link" onClick={aoAssinar}>
            Quero assinar uma faixa maior
          </button>
        </p>
      )}
    </section>
  )
}

function BlocoHistorico({ dados }: { dados: MinhaContaDados }) {
  const { historico } = dados
  if (historico.length === 0) {
    return (
      <section className="card secao-conta">
        <div className="section-label">Histórico</div>
        <p className="muted">Ainda não há mês fechado para comparar. O histórico aparece a partir da primeira virada de mês.</p>
      </section>
    )
  }
  const pico = Math.max(...historico.map((h) => h.qtdVendas), 1)
  return (
    <section className="card secao-conta">
      <div className="section-label">Histórico (últimos 12 meses)</div>
      <div className="historico-barras">
        {historico.map((h) => (
          <div key={h.competencia} className="historico-coluna" title={`${h.qtdVendas} vendas`}>
            <div className="historico-barra" style={{ height: `${Math.max(4, (h.qtdVendas / pico) * 100)}%` }} />
            <span className="historico-valor">{h.qtdVendas}</span>
            <span className="historico-mes">{rotuloCompetencia(h.competencia)}</span>
          </div>
        ))}
      </div>
    </section>
  )
}

function BlocoEmpresas({ dados, aoAdicionar }: { dados: MinhaContaDados; aoAdicionar: () => void }) {
  return (
    <section className="card secao-conta">
      <div className="secao-conta-cabecalho">
        <div className="section-label">Empresas (CNPJs)</div>
        <button type="button" className="btn btn-primary" onClick={aoAdicionar}>
          <IconeEmpresa size={18} /> Adicionar empresa
        </button>
      </div>
      <table className="table table-compacta">
        <thead>
          <tr>
            <th>Código</th>
            <th>Razão Social</th>
            <th>CNPJ</th>
            <th>Cidade/UF</th>
            <th>Tipo</th>
          </tr>
        </thead>
        <tbody>
          {dados.empresas.map((e) => (
            <tr key={e.idEmpresa}>
              <td>{String(e.codigoEmpresa).padStart(2, '0')}</td>
              <td>
                <Link to={`/empresas/${e.idEmpresa}`}>{e.nomeFantasia || e.razaoSocial}</Link>
              </td>
              <td className="mono">{e.cnpj ? mascararCpfCnpj(e.cnpj, false) : '—'}</td>
              <td>{e.cidade ? `${e.cidade}/${e.estado ?? ''}` : '—'}</td>
              <td>{e.matriz ? 'Matriz' : 'Filial'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">
        A cota de vendas é da conta inteira — incluir CNPJ não custa nada e não divide o limite.
      </p>
    </section>
  )
}

function ModalNovaEmpresa({ aoFechar, aoCriar }: { aoFechar: () => void; aoCriar: (mensagem: string) => void }) {
  const [form, setForm] = useState<NovaEmpresaForm>({ razaoSocial: '', nomeFantasia: '', cnpj: '' })
  const [erro, setErro] = useState<string | null>(null)

  const mutacao = useMutation({
    mutationFn: () => criarEmpresa(form),
    onSuccess: () =>
      aoCriar(
        'Empresa criada. Complete os dados fiscais em Dados da Empresa — ela fica disponível para operar no próximo login.',
      ),
    onError: (e: Error) => setErro(e.message),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Adicionar empresa" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Adicionar empresa</h2>
        <p className="muted">
          Só a razão social é obrigatória. Endereço, Inscrição Estadual, CNAE e o resto da ficha
          você preenche depois em <b>Dados da Empresa</b>.
        </p>

        <div className="field">
          <label htmlFor="razaoSocial">Razão Social *</label>
          <input
            id="razaoSocial"
            autoFocus
            value={form.razaoSocial}
            onChange={(e) => setForm({ ...form, razaoSocial: maiusculas(e.target.value) })}
          />
        </div>
        <div className="field">
          <label htmlFor="nomeFantasia">Nome Fantasia</label>
          <input
            id="nomeFantasia"
            value={form.nomeFantasia}
            onChange={(e) => setForm({ ...form, nomeFantasia: maiusculas(e.target.value) })}
          />
        </div>
        <div className="field">
          <label htmlFor="cnpj">CNPJ</label>
          <input
            id="cnpj"
            className="mono"
            value={form.cnpj}
            onChange={(e) => setForm({ ...form, cnpj: mascararCpfCnpj(e.target.value, false) })}
          />
        </div>

        {/* ⚠️ Toast, não banner inline (auditoria 2026-08-29). A convenção do projeto é erro em
            vermelho pelo `Toast.tsx`; aqui a mensagem — inclusive a do gateway, como o 503 de
            cobrança desligada — saía num parágrafo acima do rodapé, sem `role="alert"`. */}
        {erro && <Toast mensagem={erro} tipo="erro" aoFechar={() => setErro(null)} />}

        <div className="footer-bar">
          <button type="button" className="btn btn-secondary" onClick={aoFechar}>
            Cancelar
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!form.razaoSocial.trim() || mutacao.isPending}
            onClick={() => {
              setErro(null)
              mutacao.mutate()
            }}
          >
            {mutacao.isPending ? 'Criando…' : 'Criar empresa'}
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * Pagamento da faixa por PIX (ADR-016). A tela mostra o código e fica consultando a fatura —
 * quem promove a assinatura é o worker do backend, depois que o Mercado Pago confirma. Enquanto
 * isso, nada muda de plano: é de propósito, e a mensagem diz isso ao lojista.
 */
function ModalPagamento({
  recomendada,
  aoFechar,
  aoPagar,
}: {
  recomendada: string | null
  aoFechar: () => void
  aoPagar: () => void
}) {
  const [ciclo, setCiclo] = useState<Ciclo>('MENSAL')
  const [idPlano, setIdPlano] = useState<number | null>(null)
  const [pix, setPix] = useState<PagamentoPix | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [copiado, setCopiado] = useState(false)

  const { data: faixas } = useQuery({ queryKey: ['faixas-plano'], queryFn: listarFaixas })

  useEffect(() => {
    if (idPlano === null && faixas && faixas.length > 0) {
      const sugerida = recomendada ? faixas.find((f) => f.nome === recomendada) : undefined
      setIdPlano((sugerida ?? faixas[0]).idPlano)
    }
  }, [faixas, idPlano, recomendada])

  const gerar = useMutation({
    mutationFn: () => iniciarPagamento(idPlano!, ciclo),
    onSuccess: (p) => {
      setErro(null)
      setPix(p)
    },
    onError: (e: Error) => setErro(e.message),
  })

  // Consulta a fatura enquanto o PIX está na tela. Intervalo curto porque o lojista está
  // olhando: PIX cai em segundos e ficar "esperando" sem retorno parece defeito.
  useEffect(() => {
    if (!pix) return
    const id = setInterval(async () => {
      try {
        const s = await consultarFatura(pix.idFatura)
        if (s.situacao === 'PAGA') {
          clearInterval(id)
          aoPagar()
        }
      } catch {
        /* rede instável não pode derrubar a tela de pagamento */
      }
    }, 4000)
    return () => clearInterval(id)
  }, [pix, aoPagar])

  const faixaEscolhida = faixas?.find((f) => f.idPlano === idPlano)
  const valor = faixaEscolhida ? (ciclo === 'ANUAL' ? faixaEscolhida.precoAnual : faixaEscolhida.precoMensal) : 0

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Assinar faixa" onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo="Assinar uma faixa" aoFechar={aoFechar} />

        {!pix ? (
          <>
            <p className="muted">
              Escolha a faixa pelo seu volume de vendas. As funções são as mesmas em todas — o que muda é quanto
              cabe no mês.
            </p>

            <div className="field">
              <label htmlFor="faixa">Faixa</label>
              <select id="faixa" value={idPlano ?? ''} onChange={(e) => setIdPlano(Number(e.target.value))}>
                {(faixas ?? []).map((f) => (
                  <option key={f.idPlano} value={f.idPlano}>
                    {f.nome} — {REAL.format(f.precoMensal)}/mês
                  </option>
                ))}
              </select>
            </div>

            <div className="field">
              <label>Ciclo</label>
              <div className="ciclo-opcoes">
                <button
                  type="button"
                  className={`btn ${ciclo === 'MENSAL' ? 'btn-primary' : 'btn-secondary'}`}
                  onClick={() => setCiclo('MENSAL')}
                >
                  Mensal
                </button>
                <button
                  type="button"
                  className={`btn ${ciclo === 'ANUAL' ? 'btn-primary' : 'btn-secondary'}`}
                  onClick={() => setCiclo('ANUAL')}
                >
                  Anual (15% de desconto)
                </button>
              </div>
            </div>

            <p className="valor-cobranca">
              Total a pagar agora: <b>{REAL.format(valor)}</b>
            </p>
            {/* ⚠️ Toast, não banner inline (auditoria 2026-08-29). A convenção do projeto é erro em
                vermelho pelo `Toast.tsx`; aqui a mensagem — inclusive a do gateway, como o 503 de
                cobrança desligada — saía num parágrafo acima do rodapé, sem `role="alert"`. */}
            {erro && <Toast mensagem={erro} tipo="erro" aoFechar={() => setErro(null)} />}

            <div className="footer-bar">
              <button type="button" className="btn btn-secondary" onClick={aoFechar}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={!idPlano || gerar.isPending}
                onClick={() => gerar.mutate()}
              >
                {gerar.isPending ? 'Gerando PIX…' : 'Gerar PIX'}
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="muted">
              Pague o PIX de <b>{REAL.format(pix.valor)}</b> ({pix.plano}). Assim que o pagamento cair, sua faixa
              passa a valer sozinha — pode deixar esta tela aberta.
            </p>
            {pix.qrCodeBase64 && (
              <img
                className="pix-qr"
                src={`data:image/png;base64,${pix.qrCodeBase64}`}
                alt="QR Code do PIX para pagar a assinatura"
                width={220}
                height={220}
              />
            )}
            <div className="field">
              <label htmlFor="copiaecola">PIX copia e cola</label>
              <textarea id="copiaecola" className="mono" readOnly rows={3} value={pix.copiaECola} />
            </div>
            <div className="footer-bar">

              <button
                type="button"
                className="btn btn-primary"
                onClick={() => {
                  navigator.clipboard?.writeText(pix.copiaECola)
                  setCopiado(true)
                }}
              >
                {copiado ? 'Copiado!' : 'Copiar código'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
