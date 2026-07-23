import { useRef, useState, type ChangeEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { IconeExcluir, IconeSetaBaixo, IconeSetaCima } from './Icones'
import { ApiError } from '../lib/api'
import {
  MAX_IMAGENS_POR_PRODUTO,
  enviarImagem,
  excluirImagem,
  reordenarImagens,
  type ImagemProduto,
} from '../lib/produtoImagens'
import Toast from './Toast'

/**
 * Galeria de fotos do produto (docs/infra/armazenamento-imagens.md, ADR-013) — máximo de
 * {@link MAX_IMAGENS_POR_PRODUTO} fotos (regra de produto, 2026-07-23), reforçado também no
 * servidor. Só existe depois que o produto já foi salvo (precisa de `idProduto` de verdade
 * pra ter onde gravar o arquivo) — a tela avisa isso enquanto o produto ainda não foi criado.
 */
export default function GaleriaImagensProduto({
  idProduto,
  imagens,
  somenteLeitura = false,
  aoAtualizar,
}: {
  idProduto: number
  imagens: ImagemProduto[]
  somenteLeitura?: boolean
  aoAtualizar: (imagens: ImagemProduto[]) => void
}) {
  const [toast, setToast] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const queryClient = useQueryClient()

  const invalidar = () => queryClient.invalidateQueries({ queryKey: ['produtos'] })

  const enviar = useMutation({
    mutationFn: (arquivo: File) => enviarImagem(idProduto, arquivo),
    onSuccess: (novasImagens) => {
      aoAtualizar(novasImagens)
      invalidar()
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível enviar a foto.'),
  })

  const excluir = useMutation({
    mutationFn: (idImagem: number) => excluirImagem(idProduto, idImagem),
    onSuccess: (novasImagens) => {
      aoAtualizar(novasImagens)
      invalidar()
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível excluir a foto.'),
  })

  const reordenar = useMutation({
    mutationFn: (idsImagem: number[]) => reordenarImagens(idProduto, idsImagem),
    onSuccess: (novasImagens) => {
      aoAtualizar(novasImagens)
      invalidar()
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível reordenar as fotos.'),
  })

  const aoEscolherArquivo = (e: ChangeEvent<HTMLInputElement>) => {
    const arquivo = e.target.files?.[0]
    e.target.value = ''
    if (arquivo) enviar.mutate(arquivo)
  }

  const mover = (indice: number, direcao: -1 | 1) => {
    const alvo = indice + direcao
    if (alvo < 0 || alvo >= imagens.length) return
    const ids = imagens.map((im) => im.idImagem)
    ;[ids[indice], ids[alvo]] = [ids[alvo], ids[indice]]
    reordenar.mutate(ids)
  }

  const cheia = imagens.length >= MAX_IMAGENS_POR_PRODUTO
  const ocupado = enviar.isPending || excluir.isPending || reordenar.isPending

  return (
    <section className="section">
      <p className="section-label">
        Fotos ({imagens.length}/{MAX_IMAGENS_POR_PRODUTO})
      </p>

      {imagens.length > 0 && (
        <div className="galeria-imagens-produto">
          {imagens.map((img, i) => (
            <div key={img.idImagem} className="galeria-imagem-item">
              <img src={img.url} alt={`Foto ${i + 1} do produto`} />
              {!somenteLeitura && (
                <div className="galeria-imagem-acoes">
                  <button
                    type="button"
                    className="btn ghost"
                    tabIndex={-1}
                    disabled={i === 0 || ocupado}
                    onClick={() => mover(i, -1)}
                    aria-label={`Mover foto ${i + 1} para trás`}
                    title="Mover para trás"
                  >
                    <IconeSetaCima />
                  </button>
                  <button
                    type="button"
                    className="btn ghost"
                    tabIndex={-1}
                    disabled={i === imagens.length - 1 || ocupado}
                    onClick={() => mover(i, 1)}
                    aria-label={`Mover foto ${i + 1} para frente`}
                    title="Mover para frente"
                  >
                    <IconeSetaBaixo />
                  </button>
                  <button
                    type="button"
                    className="btn ghost"
                    tabIndex={-1}
                    disabled={ocupado}
                    onClick={() => excluir.mutate(img.idImagem)}
                    aria-label={`Excluir foto ${i + 1}`}
                    title="Excluir"
                  >
                    <IconeExcluir />
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {!somenteLeitura && (
        <>
          <input
            ref={inputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            style={{ display: 'none' }}
            onChange={aoEscolherArquivo}
          />
          <button
            type="button"
            className="btn ghost"
            style={{ marginTop: 12 }}
            disabled={cheia || ocupado}
            onClick={() => inputRef.current?.click()}
            title={cheia ? `Máximo de ${MAX_IMAGENS_POR_PRODUTO} fotos atingido` : undefined}
          >
            {enviar.isPending ? 'Enviando…' : '＋ Adicionar foto'}
          </button>
        </>
      )}

      {toast && <Toast mensagem={toast} aoFechar={() => setToast('')} />}
    </section>
  )
}
