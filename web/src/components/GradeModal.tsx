import CabecalhoModal from '../components/CabecalhoModal'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/api'
import { atualizarGrade, criarGrade, listarGrades, type Grade } from '../lib/grades'
import { criarTamanho, listarTamanhos } from '../lib/tamanhos'
import { maiusculas } from '../lib/texto'
import { IconeExcluir, IconeSetaBaixo, IconeSetaCima } from './Icones'
import Toast from './Toast'
import { fecharAoClicarNoFundo } from '../lib/modais'

const MAX_TAMANHOS = 20

/**
 * Gestão embutida de grade (curva de tamanhos, ex.: "Grade 36-44") — modal "＋ Gerenciar Grades"
 * da tela de Produto (docs/telas/produto.md, 2026-08-08). Sem exclusão nesta versão (grade em
 * uso é protegida pela própria FK de `produto.id_grade`) — mesmo mecanismo de
 * `CategoriaProdutoModal`. O catálogo de tamanhos em si (`cfg_tamanho`) nasce embutido aqui
 * também ("+ Novo tamanho"), já que esta é a única tela que precisa dele nesta rodada.
 */
export default function GradeModal({
  aoFechar,
  aoSelecionar,
}: {
  aoFechar: () => void
  aoSelecionar?: (idGrade: number, descricao: string) => void
}) {
  const queryClient = useQueryClient()
  const { data: grades } = useQuery({ queryKey: ['grades'], queryFn: listarGrades })
  const { data: tamanhos } = useQuery({ queryKey: ['tamanhos'], queryFn: listarTamanhos })

  const [idGradeEditando, setIdGradeEditando] = useState<number | null>(null)
  const [descricao, setDescricao] = useState('')
  const [idsTamanho, setIdsTamanho] = useState<number[]>([])
  const [tamanhoParaAdicionar, setTamanhoParaAdicionar] = useState('')
  const [novoTamanhoNome, setNovoTamanhoNome] = useState('')
  const [toast, setToast] = useState('')

  const tamanhosPorId = new Map((tamanhos ?? []).map((t) => [t.idTamanho, t]))
  const tamanhosDisponiveis = (tamanhos ?? []).filter((t) => !idsTamanho.includes(t.idTamanho))

  const limpar = () => {
    setIdGradeEditando(null)
    setDescricao('')
    setIdsTamanho([])
    setTamanhoParaAdicionar('')
  }

  const editar = (g: Grade) => {
    setIdGradeEditando(g.idGrade)
    setDescricao(g.descricao)
    setIdsTamanho(g.tamanhos.map((t) => t.idTamanho))
  }

  const criarNovoTamanho = useMutation({
    mutationFn: criarTamanho,
    onSuccess: (tamanho) => {
      queryClient.invalidateQueries({ queryKey: ['tamanhos'] })
      setIdsTamanho((s) => (s.length >= MAX_TAMANHOS ? s : [...s, tamanho.idTamanho]))
      setNovoTamanhoNome('')
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível criar o tamanho.'),
  })

  const salvarGrade = useMutation({
    mutationFn: () =>
      idGradeEditando
        ? atualizarGrade(idGradeEditando, descricao, idsTamanho)
        : criarGrade(descricao, idsTamanho),
    onSuccess: (grade) => {
      queryClient.invalidateQueries({ queryKey: ['grades'] })
      limpar()
      aoSelecionar?.(grade.idGrade, grade.descricao)
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível salvar a grade.'),
  })

  const adicionarTamanho = () => {
    const id = Number(tamanhoParaAdicionar)
    if (!id || idsTamanho.includes(id) || idsTamanho.length >= MAX_TAMANHOS) return
    setIdsTamanho((s) => [...s, id])
    setTamanhoParaAdicionar('')
  }

  const removerTamanho = (id: number) => setIdsTamanho((s) => s.filter((t) => t !== id))

  const moverTamanho = (indice: number, direcao: -1 | 1) => {
    const alvo = indice + direcao
    if (alvo < 0 || alvo >= idsTamanho.length) return
    const lista = [...idsTamanho]
    ;[lista[indice], lista[alvo]] = [lista[alvo], lista[indice]]
    setIdsTamanho(lista)
  }

  return (
    <div className="modal-overlay" onClick={fecharAoClicarNoFundo(aoFechar)}>
      <div className="modal" role="dialog" aria-label="Grades de produto" onClick={(e) => e.stopPropagation()}>
        <CabecalhoModal titulo="Grades" aoFechar={aoFechar} />

        {grades && grades.length > 0 && (
          <ul className="lista-categorias">
            {grades.map((g) => (
              <li key={g.idGrade}>
                <button type="button" className="btn ghost" style={{ flex: 1, textAlign: 'left' }} onClick={() => editar(g)}>
                  {g.descricao} — {g.tamanhos.map((t) => t.descricao).join(', ') || 'sem tamanhos'}
                </button>
                {aoSelecionar && (
                  <button
                    type="button"
                    className="btn"
                    onClick={() => {
                      aoSelecionar(g.idGrade, g.descricao)
                      aoFechar()
                    }}
                  >
                    Usar
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}

        <label htmlFor="grade-descricao">{idGradeEditando ? 'Editar grade' : 'Nova grade'}</label>
        <input
          id="grade-descricao"
          value={descricao}
          onChange={(e) => setDescricao(maiusculas(e.target.value))}
          placeholder='ex.: "GRADE 36-44", "GRADE PP-GG"'
        />

        {idsTamanho.length > 0 && (
          <ul className="lista-categorias-produto" style={{ marginTop: 8 }}>
            {idsTamanho.map((id, i) => (
              <li key={id}>
                <span>{tamanhosPorId.get(id)?.descricao ?? id}</span>
                <div className="topbar-acoes">
                  <button type="button" className="btn ghost" tabIndex={-1} disabled={i === 0} onClick={() => moverTamanho(i, -1)} title="Mover para cima">
                    <IconeSetaCima />
                  </button>
                  <button
                    type="button"
                    className="btn ghost"
                    tabIndex={-1}
                    disabled={i === idsTamanho.length - 1}
                    onClick={() => moverTamanho(i, 1)}
                    title="Mover para baixo"
                  >
                    <IconeSetaBaixo />
                  </button>
                  <button type="button" className="btn ghost" tabIndex={-1} onClick={() => removerTamanho(id)} title="Remover">
                    <IconeExcluir />
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}

        <p className="muted" style={{ fontSize: 12 }}>
          {idsTamanho.length}/{MAX_TAMANHOS} tamanhos
        </p>

        <div className="linha-com-botao">
          <select value={tamanhoParaAdicionar} onChange={(e) => setTamanhoParaAdicionar(e.target.value)}>
            <option value="">Selecione um tamanho…</option>
            {tamanhosDisponiveis.map((t) => (
              <option key={t.idTamanho} value={t.idTamanho}>
                {t.descricao}
              </option>
            ))}
          </select>
          <button type="button" className="btn ghost" disabled={!tamanhoParaAdicionar || idsTamanho.length >= MAX_TAMANHOS} onClick={adicionarTamanho}>
            ＋ Adicionar
          </button>
        </div>

        <div className="linha-com-botao" style={{ marginTop: 8 }}>
          <input
            value={novoTamanhoNome}
            onChange={(e) => setNovoTamanhoNome(maiusculas(e.target.value))}
            placeholder='ex.: "36", "M"…'
          />
          <button
            type="button"
            className="btn ghost"
            disabled={!novoTamanhoNome.trim() || idsTamanho.length >= MAX_TAMANHOS || criarNovoTamanho.isPending}
            onClick={() => criarNovoTamanho.mutate(novoTamanhoNome.trim())}
          >
            ＋ Novo tamanho
          </button>
        </div>

        <div className="ajuda-rodape">
          {idGradeEditando && (
            <button type="button" className="btn ghost" onClick={limpar}>
              Cancelar edição
            </button>
          )}
          <button
            type="button"
            className="btn"
            disabled={!descricao.trim() || idsTamanho.length === 0 || salvarGrade.isPending}
            onClick={() => salvarGrade.mutate()}
          >
            {idGradeEditando ? 'Salvar alterações' : 'Criar grade'}
          </button>

        </div>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
