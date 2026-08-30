import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeInutilizacao } from '../../components/Icones'
import { useAuth } from '../../lib/auth'
import { listarEmpresasFiscal } from '../../lib/fiscalConfiguracao'
import {
  detectarBuracos,
  inutilizarFaixa,
  listarInutilizacoes,
  type FaixaBuraco,
} from '../../lib/fiscalInutilizacao'
import { ApiError } from '../../lib/api'
import Toast from '../../components/Toast'
import { maiusculas } from '../../lib/texto'

function formatarDataHora(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function rotuloFaixa(f: FaixaBuraco): string {
  return f.numeroInicial === f.numeroFinal ? `${f.numeroInicial}` : `${f.numeroInicial}–${f.numeroFinal}`
}

/**
 * Inutilização de Numeração (§10.4, bloco B8) — a tela detecta os buracos sozinha (número
 * alocado que nunca virou nota nem já foi inutilizado) em vez de exigir conferência manual de
 * sequência. Mesmo desvio de padrão do `FiscalContingenciaPainel.tsx`: seletor de empresa + card
 * de ação, sem paginação nem popup de filtros — o histórico abaixo é só leitura.
 */
/**
 * Compara a faixa digitada ignorando o SEPARADOR.
 *
 * ⛔ `rotuloFaixa` monta a faixa com **en dash** (U+2013), e a comparação era de texto exato: o
 * operador digitava "100-105" com o hífen do teclado e o botão **nunca habilitava**, sem nada
 * explicando por quê (achado de auditoria, 2026-08-29). O placeholder não é selecionável, então a
 * única saída era copiar o texto do parágrafo acima.
 *
 * ⚠️ Pior: faixa de número ÚNICO funcionava (não tem separador), o que tornava a falha
 * intermitente — e isto é a confirmação da ação mais irreversível do sistema, com prazo legal.
 */
/**
 * Normaliza SÓ o separador da faixa — nunca apaga os separadores.
 *
 * <p>⛔ Era `replace(/[^0-9]/g, '')` (2ª auditoria de 2026-08-29). A correção original existia
 * para aceitar o traço que o rótulo usa (en dash "–") quando o operador digita hífen comum, e
 * apagar tudo que não é dígito resolvia — afrouxando demais: **"100105" liberava o botão que queima
 * a faixa 100–105 na SEFAZ**, e também "100/105", "100.105" e "1 0 0 1 0 5". A fricção deliberada da
 * ação mais irreversível do produto (numeração inutilizada não volta) ficou menor do que o
 * necessário para resolver o problema, que era só o traço.
 */
function faixaNormalizada(texto: string): string {
  // Os quatro traços Unicode que aparecem em rótulo (hífen-menos, hífen, traço-de-figura, en, em)
  // viram hífen ASCII; o resto do texto fica como está.
  return texto.replace(/[‐‑‒–—]/g, '-').trim()
}

export default function InutilizacaoNumeracao() {
  const queryClient = useQueryClient()
  const { idEmpresa: idEmpresaSessao } = useAuth()
  const [idEmpresa, setIdEmpresa] = useState<number | null>(null)
  const [modelo, setModelo] = useState(65)
  const [serie, setSerie] = useState(1)
  const [faixaEscolhida, setFaixaEscolhida] = useState<FaixaBuraco | null>(null)
  const [justificativa, setJustificativa] = useState('')
  const [processando, setProcessando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const [sucesso, setSucesso] = useState<string | null>(null)
  /**
   * Confirmação por DIGITAÇÃO da faixa (auditoria 2026-08-21, item 12).
   *
   * ⚠️ Esta é a ação mais irreversível do sistema — queima uma faixa de numeração perante o fisco,
   * e **não existe desfazer** — e era a única sem nenhuma confirmação: um clique e pronto. Pedir
   * para digitar a faixa não é burocracia: força a pessoa a **olhar os números que está queimando**
   * antes de confirmar, que é justamente o erro que um "Tem certeza? Sim/Não" não previne.
   */
  const [confirmacaoAberta, setConfirmacaoAberta] = useState(false)
  const [faixaDigitada, setFaixaDigitada] = useState('')

  const { data: empresas } = useQuery({ queryKey: ['fiscal-empresas'], queryFn: listarEmpresasFiscal })

  useEffect(() => {
    if (idEmpresa === null && idEmpresaSessao !== null) setIdEmpresa(idEmpresaSessao)
    else if (idEmpresa === null && empresas && empresas.length > 0) setIdEmpresa(empresas[0].idEmpresa)
  }, [idEmpresa, idEmpresaSessao, empresas])

  const { data: buracos, isLoading: carregandoBuracos } = useQuery({
    queryKey: ['fiscal-inutilizacao-buracos', idEmpresa, modelo, serie],
    queryFn: () => detectarBuracos(idEmpresa as number, modelo, serie),
    enabled: idEmpresa !== null,
  })

  const { data: historico } = useQuery({
    queryKey: ['fiscal-inutilizacoes', idEmpresa],
    queryFn: () => listarInutilizacoes(idEmpresa as number),
    enabled: idEmpresa !== null,
  })

  function escolherFaixa(f: FaixaBuraco) {
    setFaixaEscolhida(f)
    setJustificativa('')
    setErro(null)
  }

  async function confirmarInutilizacao() {
    if (idEmpresa === null || !faixaEscolhida || justificativa.trim().length < 15) return
    setProcessando(true)
    setErro(null)
    try {
      const resultado = await inutilizarFaixa(
        idEmpresa,
        modelo,
        serie,
        faixaEscolhida.numeroInicial,
        faixaEscolhida.numeroFinal,
        justificativa.trim(),
      )
      setSucesso(`Inutilização homologada pela SEFAZ — protocolo ${resultado.protocolo}.`)
      setFaixaEscolhida(null)
      setJustificativa('')
      queryClient.invalidateQueries({ queryKey: ['fiscal-inutilizacao-buracos', idEmpresa, modelo, serie] })
      queryClient.invalidateQueries({ queryKey: ['fiscal-inutilizacoes', idEmpresa] })
    } catch (e) {
      setErro(e instanceof ApiError ? e.message : 'Não foi possível inutilizar esta faixa.')
    } finally {
      setProcessando(false)
    }
  }

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeInutilizacao size={34} />
            <h1>Inutilização de Numeração</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="fiscal.inutilizacao.tela" />
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <div>
            <label htmlFor="seletor-empresa-inutilizacao">Empresa</label>
            <select
              id="seletor-empresa-inutilizacao"
              autoFocus
              value={idEmpresa ?? ''}
              onChange={(e) => {
                setIdEmpresa(Number(e.target.value))
                setFaixaEscolhida(null)
              }}
            >
              {(empresas ?? []).map((emp) => (
                <option key={emp.idEmpresa} value={emp.idEmpresa}>
                  {emp.razaoSocial}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="seletor-modelo-inutilizacao">Modelo</label>
            <select
              id="seletor-modelo-inutilizacao"
              value={modelo}
              onChange={(e) => {
                setModelo(Number(e.target.value))
                setFaixaEscolhida(null)
              }}
            >
              <option value={65}>65 — NFC-e</option>
              <option value={55}>55 — NF-e</option>
            </select>
          </div>
          <div>
            <label htmlFor="serie-inutilizacao">Série</label>
            <input
              id="serie-inutilizacao"
              type="number"
              min={1}
              value={serie}
              onChange={(e) => {
                setSerie(Number(e.target.value) || 1)
                setFaixaEscolhida(null)
              }}
              style={{ width: 90 }}
            />
          </div>
        </div>
      </div>

      <div className="lista-corpo">
        <div className="card">
          <h2 style={{ marginTop: 0 }}>Buracos detectados</h2>
          {carregandoBuracos ? (
            <p className="muted">Carregando…</p>
          ) : !buracos || buracos.length === 0 ? (
            <p className="muted">Nenhum número queimado sem nota nesta série — nada a inutilizar.</p>
          ) : (
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {buracos.map((f) => (
                <button
                  key={`${f.numeroInicial}-${f.numeroFinal}`}
                  type="button"
                  // ⚠️ Comparação por VALOR, não por identidade de objeto. `buracos` é `data` do React
                  // Query, e comparar com `===` amarra o destaque à referência que veio de uma consulta
                  // específica.
                  // ⚠️ CORREÇÃO DO PRÓPRIO COMENTÁRIO: a primeira versão dizia que um alt-tab apagava o
                  // destaque, e isso NÃO reproduz — o `structuralSharing` do React Query devolve a MESMA
                  // referência quando o refetch traz dado deep-equal. A perda de identidade acontece
                  // quando a lista MUDA (outro terminal inutiliza uma faixa anterior, os índices
                  // deslocam e o `replaceEqualDeep` deixa de reusar as referências à frente) — mais
                  // raro, e real. Um cenário que não reproduz faz a próxima pessoa reverter a correção.
                  className={
                    faixaEscolhida?.numeroInicial === f.numeroInicial && faixaEscolhida?.numeroFinal === f.numeroFinal
                      ? 'btn'
                      : 'btn ghost'
                  }
                  onClick={() => escolherFaixa(f)}
                >
                  {rotuloFaixa(f)}
                </button>
              ))}
            </div>
          )}

          {faixaEscolhida && (
            <div style={{ marginTop: 16, display: 'flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <div style={{ flex: 1, minWidth: 280 }}>
                <label htmlFor="justificativa-inutilizacao">
                  Justificativa da inutilização — faixa {rotuloFaixa(faixaEscolhida)}
                </label>
                <input
                  id="justificativa-inutilizacao"
                  type="text"
                  value={justificativa}
                  onChange={(e) => setJustificativa(maiusculas(e.target.value))}
                  placeholder="Ex.: número perdido por falha de comunicação no caixa"
                  minLength={15}
                  maxLength={255}
                />
              </div>
              <button
                type="button"
                className="btn"
                disabled={processando || justificativa.trim().length < 15}
                onClick={() => {
                  setFaixaDigitada('')
                  setConfirmacaoAberta(true)
                }}
              >
                Inutilizar na SEFAZ
              </button>
            </div>
          )}
        </div>

        <div className="card" style={{ marginTop: 16 }}>
          <h2 style={{ marginTop: 0 }}>Histórico de tentativas</h2>
          {!historico || historico.length === 0 ? (
            <p className="muted">Nenhuma inutilização registrada para esta empresa ainda.</p>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>Data</th>
                    <th>Modelo/Série</th>
                    <th>Faixa</th>
                    <th>Situação</th>
                    <th>Protocolo / motivo</th>
                    <th>Justificativa</th>
                  </tr>
                </thead>
                <tbody>
                  {historico.map((item, i) => (
                    <tr key={i}>
                      <td>{formatarDataHora(item.criadoEm)}</td>
                      <td>
                        {item.modelo}/{item.serie}
                      </td>
                      <td>{rotuloFaixa(item)}</td>
                      <td>
                        <span className={item.autorizado ? 'badge badge-sucesso' : 'badge badge-perigo'}>
                          {item.autorizado ? 'Homologada' : 'Recusada'}
                        </span>
                      </td>
                      <td>{item.autorizado ? item.protocolo : item.motivoSefaz}</td>
                      <td>{item.justificativa}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Confirmação por digitação — ver o comentário de `confirmacaoAberta`. Ação sem desfazer. */}
      {confirmacaoAberta && faixaEscolhida && (
        <div className="modal-overlay">
          <div className="modal" role="dialog" aria-label="Confirmar inutilização de numeração">
            <h2 style={{ marginTop: 0 }}>Inutilizar numeração na SEFAZ</h2>
            <p>
              Esta ação <strong>queima a faixa {rotuloFaixa(faixaEscolhida)}</strong> (modelo {modelo}, série{' '}
              {serie}) perante o fisco. Os números <strong>não poderão mais ser usados</strong>, e{' '}
              <strong>não há como desfazer</strong>.
            </p>
            <div style={{ marginTop: 12 }}>
              <label htmlFor="confirmar-faixa">
                Digite a faixa <strong>{rotuloFaixa(faixaEscolhida)}</strong> para confirmar
              </label>
              <input
                id="confirmar-faixa"
                autoFocus
                value={faixaDigitada}
                onChange={(e) => setFaixaDigitada(e.target.value)}
                placeholder={rotuloFaixa(faixaEscolhida)}
              />
            </div>
            <div className="ajuda-rodape" style={{ marginTop: 16 }}>
              <button type="button" className="btn ghost" onClick={() => setConfirmacaoAberta(false)}>
                Cancelar
              </button>
              <button
                type="button"
                className="btn"
                disabled={processando || faixaNormalizada(faixaDigitada) !== faixaNormalizada(rotuloFaixa(faixaEscolhida))}
                onClick={() => {
                  setConfirmacaoAberta(false)
                  confirmarInutilizacao()
                }}
              >
                Inutilizar
              </button>
            </div>
          </div>
        </div>
      )}

      {erro && <Toast mensagem={erro} tipo="erro" aoFechar={() => setErro(null)} />}
      {sucesso && <Toast mensagem={sucesso} tipo="sucesso" aoFechar={() => setSucesso(null)} />}
    </div>
  )
}
