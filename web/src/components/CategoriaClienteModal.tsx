import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { criarCategoria, listarCategorias, renomearCategoria } from '../lib/clientes'
import { ApiError } from '../lib/api'
import { maiusculas } from '../lib/texto'
import Toast from './Toast'

/**
 * Gestão embutida da categoria de cliente (docs/telas/cliente.md): criar e renomear,
 * sem exclusão nesta versão (categoria em uso é protegida pela própria FK).
 */
export default function CategoriaClienteModal({
  aoFechar,
  aoCriar,
}: {
  aoFechar: () => void
  aoCriar?: (idCategoriaCliente: number) => void
}) {
  const queryClient = useQueryClient()
  const { data: categorias } = useQuery({ queryKey: ['categorias-cliente'], queryFn: listarCategorias })
  const [novoNome, setNovoNome] = useState('')
  const [edicoes, setEdicoes] = useState<Record<number, string>>({})
  const [toast, setToast] = useState('')

  const criar = useMutation({
    mutationFn: criarCategoria,
    onSuccess: (categoria) => {
      queryClient.invalidateQueries({ queryKey: ['categorias-cliente'] })
      setNovoNome('')
      aoCriar?.(categoria.idCategoriaCliente)
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível criar a categoria.'),
  })

  const renomear = useMutation({
    mutationFn: ({ id, nome }: { id: number; nome: string }) => renomearCategoria(id, nome),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['categorias-cliente'] })
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível renomear a categoria.'),
  })

  return (
    <div className="modal-overlay" onClick={aoFechar}>
      <div className="modal" role="dialog" aria-label="Categorias de cliente" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ marginTop: 0 }}>Categorias de cliente</h2>

        {categorias && categorias.length > 0 && (
          <ul className="lista-categorias">
            {categorias.map((c) => (
              <li key={c.idCategoriaCliente}>
                <input
                  value={edicoes[c.idCategoriaCliente] ?? c.nomeCategoria}
                  onChange={(e) =>
                    setEdicoes((s) => ({ ...s, [c.idCategoriaCliente]: maiusculas(e.target.value) }))
                  }
                />
                <button
                  type="button"
                  className="btn ghost"
                  disabled={renomear.isPending}
                  onClick={() => {
                    const nome = (edicoes[c.idCategoriaCliente] ?? c.nomeCategoria).trim()
                    if (nome && nome !== c.nomeCategoria) {
                      renomear.mutate({ id: c.idCategoriaCliente, nome })
                    }
                  }}
                >
                  Salvar
                </button>
              </li>
            ))}
          </ul>
        )}

        <label htmlFor="nova-categoria">Nova categoria</label>
        <div className="linha-com-botao">
          <input
            id="nova-categoria"
            value={novoNome}
            onChange={(e) => setNovoNome(maiusculas(e.target.value))}
            placeholder="ex.: VIP, Atacado…"
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
