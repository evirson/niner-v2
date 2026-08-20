import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { dataHora } from '../lib/api'
import { useAuth } from '../lib/auth'
import { excluirCsrt, listarCsrt, salvarCsrt, type CsrtUf } from '../lib/plataforma'

/** As 27 unidades da federação — 26 estados e o Distrito Federal. */
const UFS = [
  'AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG',
  'PA', 'PB', 'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO',
]

const AMBIENTES = [
  { valor: 2, rotulo: 'Homologação' },
  { valor: 1, rotulo: 'Produção' },
]

function chave(uf: string, ambiente: number) {
  return `${uf}-${ambiente}`
}

/**
 * CSRT do responsável técnico por UF (NT 2018.005).
 *
 * O código é emitido pela SEFAZ de **cada** unidade da federação para o CNPJ de quem desenvolve o
 * software emissor. Enquanto o produto emitia só no Paraná, ele cabia numa variável de ambiente;
 * atendendo os 27 entes, virou cadastro — e cadastro que precisa mudar sem deploy, porque um CSRT
 * novo chega no dia em que entra um lojista de um estado novo.
 *
 * Segredo entra e não sai: a listagem devolve o identificador (que é público, vai no XML em claro)
 * e a data, nunca o código. Campo em branco numa UF já cadastrada **mantém** o que está gravado.
 */
export default function CsrtPorUf() {
  const { ehSuperAdmin } = useAuth()
  const qc = useQueryClient()
  const { data: cadastrados } = useQuery({ queryKey: ['csrt'], queryFn: listarCsrt })

  const [uf, setUf] = useState('PR')
  const [ambiente, setAmbiente] = useState(2)
  const [idCsrt, setIdCsrt] = useState('01')
  const [codigo, setCodigo] = useState('')
  const [observacao, setObservacao] = useState('')
  const [aviso, setAviso] = useState<string | null>(null)
  const [erro, setErro] = useState<string | null>(null)

  const existente = cadastrados?.find((c) => c.uf === uf && c.ambiente === ambiente)
  const chaveSelecionada = chave(uf, ambiente)
  const chaveJaPreenchida = useRef<string | null>(null)

  /**
   * Repõe o formulário com o que está gravado na UF/ambiente selecionados.
   *
   * <p>⚠️ Sem isto, `idCsrt` ficava no default `'01'` e a observação em branco mesmo para uma UF
   * já cadastrada — e como o backend grava `id_csrt`/`observacao` sem a convenção de "em branco
   * mantém" (que vale só para o código), trocar o CSRT de uma UF que usava o identificador `02`
   * o rebaixava para `01` em silêncio. O `idCSRT` vai em claro no `infRespTec` e precisa bater com
   * o cadastro da SEFAZ: seria toda NF-e daquela UF voltando `cStat 974`, com a tela dizendo
   * "salvo".
   *
   * <p>Preenche **uma vez por seleção**, não a cada chegada de dado: um refetch de janela (o React
   * Query refaz a busca ao voltar o foco) não pode apagar o que o usuário está digitando enquanto
   * copia o código do e-mail da SEFAZ.
   */
  useEffect(() => {
    if (!cadastrados || chaveJaPreenchida.current === chaveSelecionada) return
    chaveJaPreenchida.current = chaveSelecionada
    setIdCsrt(existente?.idCsrt ?? '01')
    setObservacao(existente?.observacao ?? '')
    setCodigo('')
  }, [cadastrados, chaveSelecionada, existente])

  const salvar = useMutation({
    mutationFn: () => salvarCsrt(uf, ambiente, { idCsrt, csrt: codigo, observacao }),
    onSuccess: () => {
      setErro(null)
      // ⚠️ A mensagem tem de distinguir "troquei o código" de "mantive o código". O campo é
      // `type="password"`: uma colagem que não pegou é invisível, e um "salvo" genérico faria o
      // administrador dar a tarefa por concluída com o código antigo ainda gravado.
      const onde = `${uf} (${ambiente === 1 ? 'produção' : 'homologação'})`
      setAviso(codigo.trim()
        ? `CSRT de ${onde} trocado.`
        : `Identificador e observação de ${onde} atualizados — o código gravado foi mantido.`)
      setCodigo('')
      qc.invalidateQueries({ queryKey: ['csrt'] })
    },
    onError: (e: Error) => { setAviso(null); setErro(e.message) },
  })

  const remover = useMutation({
    mutationFn: (c: CsrtUf) => excluirCsrt(c.uf, c.ambiente),
    onSuccess: () => {
      setErro(null)
      setAviso('CSRT removido.')
      chaveJaPreenchida.current = null // some a linha: o formulário tem de voltar ao default
      qc.invalidateQueries({ queryKey: ['csrt'] })
    },
    onError: (e: Error) => { setAviso(null); setErro(e.message) },
  })

  const confirmarRemocao = (c: CsrtUf) => {
    // Remover não é cosmético: a UF que exige CSRT para de autorizar na hora.
    const texto = `Remover o CSRT de ${c.uf} (${c.ambiente === 1 ? 'produção' : 'homologação'})?\n\n`
      + 'Enquanto não houver outro cadastrado, a NF-e modelo 55 dessa UF deixa de ser emitida.'
    if (window.confirm(texto)) remover.mutate(c)
  }

  return (
    <>
      <div className="topo-pagina">
        <h1>CSRT por UF</h1>
        <span className="muted">Código de Segurança do Responsável Técnico · NT 2018.005</span>
      </div>

      <p className="card">
        O CSRT identifica quem <b>desenvolve o software emissor</b> e é obtido no portal da SEFAZ de{' '}
        <b>cada</b> UF, para o CNPJ do responsável técnico. Sem ele, a NF-e modelo 55 volta com{' '}
        <code>cStat 975</code>; com um código que não bate com o cadastro da SEFAZ, com{' '}
        <code>cStat 974</code>. A NFC-e do dia a dia só exige onde a UF determinar.
      </p>

      {!ehSuperAdmin && (
        <p className="card" style={{ borderColor: 'var(--warning)' }}>
          Seu papel enxerga esta tela, mas <b>só SUPER_ADMIN altera</b>.
        </p>
      )}

      <section className="card secao">
        <h2>Cadastrar ou trocar</h2>
        <div className="linha">
          <div className="campo">
            <label htmlFor="csrt-uf">UF</label>
            <select id="csrt-uf" value={uf} disabled={!ehSuperAdmin} onChange={(e) => setUf(e.target.value)}>
              {UFS.map((u) => <option key={u} value={u}>{u}</option>)}
            </select>
          </div>
          <div className="campo">
            <label htmlFor="csrt-ambiente">Ambiente</label>
            <select id="csrt-ambiente" value={ambiente} disabled={!ehSuperAdmin}
              onChange={(e) => setAmbiente(Number(e.target.value))}>
              {AMBIENTES.map((a) => <option key={a.valor} value={a.valor}>{a.rotulo}</option>)}
            </select>
          </div>
          <div className="campo">
            <label htmlFor="csrt-id">Identificador (idCSRT)</label>
            <input id="csrt-id" value={idCsrt} disabled={!ehSuperAdmin} inputMode="numeric" maxLength={2}
              onChange={(e) => setIdCsrt(e.target.value.replace(/\D/g, '').slice(0, 2))}
              placeholder="01" />
          </div>
          <div className="campo">
            <label htmlFor="csrt-codigo">
              CSRT {existente && <span className="tag tag-ok">configurado</span>}
            </label>
            <input id="csrt-codigo" type="password" value={codigo} disabled={!ehSuperAdmin}
              placeholder={existente ? 'em branco mantém o código atual' : 'código fornecido pela SEFAZ'}
              onChange={(e) => setCodigo(e.target.value)} />
          </div>
          <div className="campo">
            <label htmlFor="csrt-obs">Observação</label>
            <input id="csrt-obs" value={observacao} disabled={!ehSuperAdmin} maxLength={500}
              placeholder="data de emissão, protocolo do portal…"
              onChange={(e) => setObservacao(e.target.value)} />
          </div>
        </div>
        {ehSuperAdmin && (
          <div className="acoes" style={{ marginTop: 14 }}>
            <button className="btn btn-primario" disabled={salvar.isPending} onClick={() => salvar.mutate()}>
              {salvar.isPending ? 'Salvando…' : existente ? 'Trocar CSRT' : 'Cadastrar CSRT'}
            </button>
          </div>
        )}
      </section>

      {aviso && <p className="card" style={{ marginTop: 16, borderColor: 'var(--accent)' }}>{aviso}</p>}
      {erro && <p className="erro" style={{ marginTop: 16 }}>{erro}</p>}

      <section className="card secao">
        <h2>Cadastrados</h2>
        {!cadastrados?.length ? (
          <p className="muted">
            Nenhuma UF cadastrada. Enquanto isso, vale o código da variável de ambiente — e só para
            a UF declarada nela.
          </p>
        ) : (
          <table className="tabela">
            <thead>
              <tr>
                <th>UF</th><th>Ambiente</th><th>idCSRT</th><th>Observação</th><th>Atualizado</th><th></th>
              </tr>
            </thead>
            <tbody>
              {cadastrados.map((c) => (
                <tr key={chave(c.uf, c.ambiente)}>
                  <td><b>{c.uf}</b></td>
                  <td>{c.ambiente === 1 ? 'Produção' : 'Homologação'}</td>
                  <td>{c.idCsrt}</td>
                  <td className="muted">{c.observacao ?? '—'}</td>
                  <td className="muted">{dataHora(c.atualizadoEm)}</td>
                  <td>
                    {ehSuperAdmin && (
                      <button className="btn btn-secundario" onClick={() => confirmarRemocao(c)}>
                        Remover
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  )
}
