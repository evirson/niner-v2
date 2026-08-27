import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeUsuario } from '../../components/Icones'
import Toast from '../../components/Toast'
import { ApiError } from '../../lib/api'
import { permissoesDoUsuario, salvarPermissoes, type Permissao } from '../../lib/permissoes'

type Acao = 'acessar' | 'incluir' | 'alterar' | 'excluir'

const ACOES: { chave: Acao; rotulo: string }[] = [
  { chave: 'acessar', rotulo: 'Acessar' },
  { chave: 'incluir', rotulo: 'Incluir' },
  { chave: 'alterar', rotulo: 'Alterar' },
  { chave: 'excluir', rotulo: 'Excluir' },
]

/**
 * Permissões de um usuário — por tela e por ação (RBAC, V073).
 *
 * Decisões do dono do produto (2026-08-27) que esta tela materializa:
 * - **sem perfis**: a grade é deste usuário, porque *"às vezes para um tenant o estoquista pode
 *   fazer uma coisa, e no outro tenant vai fazer coisas diferentes"*;
 * - **o administrador pode tudo** e não aparece aqui — não há o que configurar para ele;
 * - **usuário novo nasce sem nada**, e o admin libera tela a tela.
 *
 * ⚠️ Desmarcar **Acessar** apaga as outras três da linha: incluir sem poder abrir a tela é
 * combinação que o banco recusa (CHECK da V073) e que na tela só confundiria.
 */
export default function UsuarioPermissoes() {
  const { id } = useParams()
  const idUsuario = Number(id)
  const queryClient = useQueryClient()

  const [grade, setGrade] = useState<Permissao[]>([])
  const [erro, setErro] = useState('')
  const [sucesso, setSucesso] = useState('')
  const [filtro, setFiltro] = useState('')

  const { data, isLoading } = useQuery({
    queryKey: ['permissoes', idUsuario],
    queryFn: () => permissoesDoUsuario(idUsuario),
    enabled: Number.isFinite(idUsuario),
  })

  useEffect(() => {
    if (data) setGrade(data)
  }, [data])

  const salvar = useMutation({
    mutationFn: () => salvarPermissoes(idUsuario, grade),
    onSuccess: (r) => {
      setGrade(r)
      setSucesso('Permissões salvas.')
      // A grade do próprio usuário logado alimenta o menu — se ele mudou a si mesmo por outra
      // aba, o menu precisa reagir (ver feedback do React Query entre telas).
      queryClient.invalidateQueries({ queryKey: ['minhas-permissoes'] })
    },
    onError: (e) => setErro(e instanceof ApiError ? e.message : 'Não foi possível salvar as permissões.'),
  })

  const marcar = (chave: string, acao: Acao, valor: boolean) => {
    setGrade((g) =>
      g.map((p) => {
        if (p.chave !== chave) return p
        if (acao === 'acessar' && !valor) {
          // Tirar o acesso derruba tudo da linha: as outras ações não existem sem ele.
          return { ...p, acessar: false, incluir: false, alterar: false, excluir: false }
        }
        // Marcar qualquer ação implica acessar — a intenção de quem clicou é evidente.
        return { ...p, [acao]: valor, acessar: valor ? true : p.acessar }
      }),
    )
  }

  const marcarGrupo = (grupo: string, valor: boolean) => {
    setGrade((g) =>
      g.map((p) =>
        p.grupo === grupo
          ? valor
            ? { ...p, acessar: true }
            : { ...p, acessar: false, incluir: false, alterar: false, excluir: false }
          : p,
      ),
    )
  }

  const visiveis = useMemo(() => {
    const termo = filtro.trim().toLowerCase()
    if (!termo) return grade
    return grade.filter((p) => p.nome.toLowerCase().includes(termo) || p.grupo.toLowerCase().includes(termo))
  }, [grade, filtro])

  const grupos = useMemo(() => {
    const mapa = new Map<string, Permissao[]>()
    for (const p of visiveis) {
      const lista = mapa.get(p.grupo) ?? []
      lista.push(p)
      mapa.set(p.grupo, lista)
    }
    return [...mapa.entries()]
  }, [visiveis])

  const liberadas = grade.filter((p) => p.acessar).length

  return (
    <div className="tela">
      <header className="tela-header">
        <h1>
          <IconeUsuario size={22} /> Permissões do Usuário
        </h1>
        <div className="acoes-header">
          <AjudaDaTela chaveTela="usuario-permissoes" />
          <BotaoFecharTela />
          <button className="btn" disabled={salvar.isPending} onClick={() => salvar.mutate()}>
            {salvar.isPending ? 'Salvando…' : 'Salvar'}
          </button>
        </div>
      </header>

      <div className="tela-corpo">
        <p className="muted" style={{ marginTop: 0 }}>
          {liberadas === 0
            ? 'Este usuário ainda não acessa nenhuma tela. Marque o que ele pode usar.'
            : `Este usuário acessa ${liberadas} de ${grade.length} telas.`}
        </p>

        <input
          autoFocus
          placeholder="Filtrar por tela ou grupo…"
          value={filtro}
          onChange={(e) => setFiltro(e.target.value)}
          style={{ maxWidth: 360, marginBottom: 12 }}
        />

        {isLoading && <p className="muted">Carregando…</p>}

        {grupos.map(([grupo, telas]) => (
          <section key={grupo} className="section">
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <p className="section-label" style={{ margin: 0 }}>{grupo}</p>
              <button type="button" className="btn ghost" onClick={() => marcarGrupo(grupo, true)}>
                Liberar grupo
              </button>
              <button type="button" className="btn ghost" onClick={() => marcarGrupo(grupo, false)}>
                Bloquear grupo
              </button>
            </div>
            <table className="grid">
              <thead>
                <tr>
                  <th style={{ width: '40%' }}>Tela</th>
                  {ACOES.map((a) => (
                    <th key={a.chave} style={{ textAlign: 'center' }}>{a.rotulo}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {telas.map((p) => (
                  <tr key={p.chave}>
                    <td>{p.nome}</td>
                    {ACOES.map((a) => (
                      <td key={a.chave} style={{ textAlign: 'center' }}>
                        <input
                          type="checkbox"
                          aria-label={`${a.rotulo} em ${p.nome}`}
                          checked={p[a.chave]}
                          // Sem "acessar", as outras três ficam desabilitadas em vez de sumirem:
                          // some deixaria a grade com buracos e faria o admin procurar o que não
                          // está faltando.
                          disabled={a.chave !== 'acessar' && !p.acessar}
                          onChange={(e) => marcar(p.chave, a.chave, e.target.checked)}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        ))}

        {!isLoading && grupos.length === 0 && <p className="muted">Nenhuma tela encontrada para "{filtro}".</p>}
      </div>

      {erro && <Toast mensagem={erro} aoFechar={() => setErro('')} />}
      {sucesso && <Toast mensagem={sucesso} tipo="sucesso" aoFechar={() => setSucesso('')} />}
    </div>
  )
}
