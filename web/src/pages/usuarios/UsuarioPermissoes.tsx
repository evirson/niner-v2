import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import AjudaDaTela from '../../components/AjudaDaTela'
import { BotaoFecharTela } from '../../components/BotaoFecharTela'
import { IconeCadeado } from '../../components/Icones'
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
 * - **o administrador pode tudo** e não aparece aqui;
 * - **usuário novo nasce sem nada**, e o admin libera tela a tela.
 *
 * ⚠️ **Uma aba por grupo** (pedido dele ao ver a primeira versão): 63 telas empilhadas numa página
 * só viram rolagem sem fim, e o cabeçalho das colunas some da vista assim que o primeiro grupo
 * passa — que foi exatamente a queixa, *"não sei qual quadro pertence ao que"*. Com abas, o
 * cabeçalho fica sempre acima das linhas que ele está marcando.
 */
export default function UsuarioPermissoes() {
  const { id } = useParams()
  const idUsuario = Number(id)
  const queryClient = useQueryClient()

  const [grade, setGrade] = useState<Permissao[]>([])
  const [erro, setErro] = useState('')
  const [sucesso, setSucesso] = useState('')
  const [filtro, setFiltro] = useState('')
  const [abaAtiva, setAbaAtiva] = useState<string | null>(null)

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
      // A grade do usuário logado alimenta o menu — se ele mexeu na própria por outra aba, o menu
      // precisa reagir (React Query entre telas).
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

  /** Grupos na ordem do catálogo (que é a ordem do menu), com quantas telas já foram liberadas. */
  const grupos = useMemo(() => {
    const mapa = new Map<string, Permissao[]>()
    for (const p of grade) {
      const lista = mapa.get(p.grupo) ?? []
      lista.push(p)
      mapa.set(p.grupo, lista)
    }
    return [...mapa.entries()].map(([nome, telas]) => ({
      nome,
      telas,
      liberadas: telas.filter((t) => t.acessar).length,
    }))
  }, [grade])

  useEffect(() => {
    if (!abaAtiva && grupos.length > 0) setAbaAtiva(grupos[0].nome)
  }, [grupos, abaAtiva])

  const termo = filtro.trim().toLowerCase()

  /**
   * Com filtro digitado, a busca atravessa as abas — procurar "estoque" e receber "nada nesta
   * aba" seria pior do que não ter busca.
   */
  const linhasVisiveis = useMemo(() => {
    if (termo) {
      return grade.filter(
        (p) => p.nome.toLowerCase().includes(termo) || p.grupo.toLowerCase().includes(termo),
      )
    }
    return grupos.find((g) => g.nome === abaAtiva)?.telas ?? []
  }, [grade, grupos, abaAtiva, termo])

  const liberadas = grade.filter((p) => p.acessar).length

  return (
    <div className="lista-tela">
      <div className="lista-topo">
        <div className="topbar-tela">
          <div className="titulo-tela">
            <IconeCadeado size={34} />
            <h1>Permissões do Usuário</h1>
          </div>
          <div className="topbar-acoes">
            <AjudaDaTela chaveTela="usuario-permissoes" />
            <button className="btn" disabled={salvar.isPending} onClick={() => salvar.mutate()}>
              {salvar.isPending ? 'Salvando…' : 'Salvar'}
            </button>
            <BotaoFecharTela />
          </div>
        </div>

        <div className="card filtros-bar">
          <input
            autoFocus
            placeholder="Buscar tela em todos os grupos…"
            value={filtro}
            onChange={(e) => setFiltro(e.target.value)}
            aria-label="Buscar tela"
          />
          <span className="muted">
            {liberadas === 0
              ? 'Este usuário ainda não acessa nenhuma tela.'
              : `Acessa ${liberadas} de ${grade.length} telas.`}
          </span>
        </div>

        {/* Abas dos grupos. O número em cada uma responde à pergunta que o admin faz o tempo todo
            — "onde foi mesmo que eu liberei alguma coisa?" — sem abrir aba por aba. */}
        {!termo && (
          <div className="abas-permissao" role="tablist">
            {grupos.map((g) => (
              <button
                key={g.nome}
                type="button"
                role="tab"
                aria-selected={g.nome === abaAtiva}
                className={`aba-permissao${g.nome === abaAtiva ? ' aba-permissao-ativa' : ''}`}
                onClick={() => setAbaAtiva(g.nome)}
              >
                {g.nome}
                {g.liberadas > 0 && <span className="aba-permissao-contador">{g.liberadas}</span>}
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="lista-corpo">
        <div className="card table-wrap">
          {isLoading ? (
            <p className="muted">Carregando…</p>
          ) : linhasVisiveis.length === 0 ? (
            <p className="muted">Nenhuma tela encontrada{termo ? ` para "${filtro}"` : ''}.</p>
          ) : (
            <>
              {!termo && abaAtiva && (
                <div className="acoes-grupo-permissao">
                  <button type="button" className="btn ghost" onClick={() => marcarGrupo(abaAtiva, true)}>
                    Liberar tudo neste grupo
                  </button>
                  <button type="button" className="btn ghost" onClick={() => marcarGrupo(abaAtiva, false)}>
                    Bloquear tudo neste grupo
                  </button>
                </div>
              )}

              <table className="table table-compacta">
                <thead>
                  <tr>
                    <th>Tela</th>
                    {termo && <th>Grupo</th>}
                    {ACOES.map((a) => (
                      <th key={a.chave} className="col-acao-permissao">
                        {a.rotulo}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {linhasVisiveis.map((p, i) => (
                    <>
                      {/* Separador de subgrupo: sem ele, as 20 telas de Configurações viram uma
                          lista corrida e o admin perde a referência de onde está. */}
                      {!termo && p.subgrupo && p.subgrupo !== linhasVisiveis[i - 1]?.subgrupo && (
                        <tr key={`sub-${p.subgrupo}`} className="linha-subgrupo">
                          <td colSpan={1 + ACOES.length}>{p.subgrupo}</td>
                        </tr>
                      )}
                    <tr key={p.chave}>
                      <td>{p.nome}</td>
                      {termo && <td className="muted">{p.grupo}</td>}
                      {ACOES.map((a) => (
                        <td key={a.chave} className="col-acao-permissao">
                          <input
                            type="checkbox"
                            aria-label={`${a.rotulo} em ${p.nome}`}
                            checked={p[a.chave]}
                            // Sem "acessar", as outras três ficam desabilitadas em vez de sumirem:
                            // sumir deixaria buracos na grade e faria o admin procurar o que não
                            // está faltando.
                            disabled={a.chave !== 'acessar' && !p.acessar}
                            onChange={(e) => marcar(p.chave, a.chave, e.target.checked)}
                          />
                        </td>
                      ))}
                    </tr>
                    </>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </div>
      </div>

      {erro && <Toast mensagem={erro} aoFechar={() => setErro('')} />}
      {sucesso && <Toast mensagem={sucesso} tipo="sucesso" aoFechar={() => setSucesso('')} />}
    </div>
  )
}
