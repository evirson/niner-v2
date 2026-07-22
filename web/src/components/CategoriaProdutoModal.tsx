import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../lib/api'
import { criarCategoriaProduto, listarCategoriasProduto, renomearCategoriaProduto } from '../lib/categoriasProduto'
import { maiusculas } from '../lib/texto'
import Toast from './Toast'

/**
 * Gestão embutida da categoria de produto (item 1 do pedido de Produtos): criar e renomear,
 * sem exclusão nesta versão (categoria em uso é protegida pela própria FK de
 * `produto_categoria`) — mesmo mecanismo do `CategoriaClienteModal`.
 */
export default function CategoriaProdutoModal({
  aoFechar,
  aoCriar,
}: {
  aoFechar: () => void
  aoCriar?: (idCategoria: number, nomeCategoria: string) => void
}) {
  const queryClient = useQueryClient()
  const { data: categorias } = useQuery({ queryKey: ['categorias-produto'], queryFn: listarCategoriasProduto })
  const [novoNome, setNovoNome] = useState('')
  const [edicoes, setEdicoes] = useState<Record<number, string>>({})
  const [toast, setToast] = useState('')

  const criar = useMutation({
    mutationFn: criarCategoriaProduto,
    onSuccess: (categoria) => {
      queryClient.invalidateQueries({ queryKey: ['categorias-produto'] })
      setNovoNome('')
      aoCriar?.(categoria.idCategoria, categoria.nomeCategoria)
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível criar a categoria.'),
  })

  const renomear = useMutation({
    mutationFn: ({ id, nome }: { id: number; nome: string }) => renomearCategoriaProduto(id, nome),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['categorias-produto'] })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível renomear a categoria.'),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Categorias de produto" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Categorias de produto</h2>

        {categorias && categorias.length > 0 && (
          <ul className="lista-categorias">
            {categorias.map((c) => (
              <li key={c.idCategoria}>
                <input
                  value={edicoes[c.idCategoria] ?? c.nomeCategoria}
                  onChange={(e) => setEdicoes((s) => ({ ...s, [c.idCategoria]: maiusculas(e.target.value) }))}
                />
                <button
                  type="button"
                  className="btn ghost"
                  disabled={renomear.isPending}
                  onClick={() => {
                    const nome = (edicoes[c.idCategoria] ?? c.nomeCategoria).trim()
                    if (nome && nome !== c.nomeCategoria) {
                      renomear.mutate({ id: c.idCategoria, nome })
                    }
                  }}
                >
                  Salvar
                </button>
              </li>
            ))}
          </ul>
        )}

        <label htmlFor="nova-categoria-produto">Nova categoria</label>
        <div className="linha-com-botao">
          <input
            id="nova-categoria-produto"
            value={novoNome}
            onChange={(e) => setNovoNome(maiusculas(e.target.value))}
            placeholder="ex.: ELETRÔNICOS, VESTUÁRIO…"
          />
          <button
            type="button"
            className="btn"
            disabled={!novoNome.trim() || criar.isPending}
            onClick={() => criar.mutate(novoNome.trim())}
          >
            Adicionar
          </button>
        </div>

        <div className="ajuda-rodape">
          <button type="button" className="btn ghost" onClick={aoFechar}>
            Fechar
          </button>
        </div>
      </div>

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </div>
  )
}
