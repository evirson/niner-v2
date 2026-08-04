import { useEffect, useMemo, useRef, useState, type ChangeEvent, type KeyboardEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { IconeExcluir, IconeFechar, IconePaginaAnterior, IconeProximaPagina, IconeSetaBaixo, IconeSetaCima } from './Icones'
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
 * servidor.
 *
 * Sem `idProduto` (produto novo, ainda não salvo) a galeria opera em modo de preparação: os
 * arquivos ficam só no navegador — estado (`arquivosLocais`) e dono é o `ProdutoForm` — e são
 * enviados um a um, na ordem escolhida, só depois que o produto é criado (2026-07-23, pedido do
 * dono do produto: não faz sentido obrigar salvar o cadastro antes de poder anexar fotos).
 */
export default function GaleriaImagensProduto({
  idProduto,
  imagens,
  arquivosLocais,
  aoMudarArquivosLocais,
  somenteLeitura = false,
  aoAtualizar,
}: {
  idProduto?: number
  imagens: ImagemProduto[]
  arquivosLocais?: File[]
  aoMudarArquivosLocais?: (arquivos: File[]) => void
  somenteLeitura?: boolean
  aoAtualizar: (imagens: ImagemProduto[]) => void
}) {
  const modoPreparacao = idProduto === undefined
  const arquivos = arquivosLocais ?? []

  const [toast, setToast] = useState('')
  const [indiceParaExcluir, setIndiceParaExcluir] = useState<number | null>(null)
  const [indiceAmpliado, setIndiceAmpliado] = useState<number | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const queryClient = useQueryClient()

  // Preview local (modo preparação): gera URLs de blob a partir dos arquivos escolhidos e
  // revoga as antigas sempre que a lista mudar ou o componente desmontar.
  const urlsLocais = useMemo(
    () => (modoPreparacao ? arquivos.map((a) => URL.createObjectURL(a)) : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [modoPreparacao, arquivos],
  )
  useEffect(() => {
    return () => urlsLocais.forEach((u) => URL.revokeObjectURL(u))
  }, [urlsLocais])

  const invalidar = () => queryClient.invalidateQueries({ queryKey: ['produtos'] })

  const enviar = useMutation({
    mutationFn: (arquivo: File) => enviarImagem(idProduto as number, arquivo),
    onSuccess: (novasImagens) => {
      aoAtualizar(novasImagens)
      invalidar()
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível enviar a foto.'),
  })

  const excluir = useMutation({
    mutationFn: (idImagem: number) => excluirImagem(idProduto as number, idImagem),
    onSuccess: (novasImagens) => {
      aoAtualizar(novasImagens)
      invalidar()
      setIndiceParaExcluir(null)
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível excluir a foto.'),
  })

  const reordenar = useMutation({
    mutationFn: (idsImagem: number[]) => reordenarImagens(idProduto as number, idsImagem),
    onSuccess: (novasImagens) => {
      aoAtualizar(novasImagens)
      invalidar()
    },
    onError: (e: unknown) => setToast(e instanceof ApiError ? e.message : 'Não foi possível reordenar as fotos.'),
  })

  const aoEscolherArquivo = (e: ChangeEvent<HTMLInputElement>) => {
    const arquivo = e.target.files?.[0]
    e.target.value = ''
    if (!arquivo) return
    if (modoPreparacao) aoMudarArquivosLocais?.([...arquivos, arquivo])
    else enviar.mutate(arquivo)
  }

  const mover = (indice: number, direcao: -1 | 1) => {
    const alvo = indice + direcao
    if (alvo < 0 || alvo >= (modoPreparacao ? arquivos.length : imagens.length)) return
    if (modoPreparacao) {
      const lista = [...arquivos]
      ;[lista[indice], lista[alvo]] = [lista[alvo], lista[indice]]
      aoMudarArquivosLocais?.(lista)
      return
    }
    const ids = imagens.map((im) => im.idImagem)
    ;[ids[indice], ids[alvo]] = [ids[alvo], ids[indice]]
    reordenar.mutate(ids)
  }

  const confirmarExclusao = () => {
    if (indiceParaExcluir === null) return
    if (modoPreparacao) {
      aoMudarArquivosLocais?.(arquivos.filter((_, i) => i !== indiceParaExcluir))
      setIndiceParaExcluir(null)
    } else {
      excluir.mutate(imagens[indiceParaExcluir].idImagem)
    }
  }

  const navegarLightbox = (direcao: -1 | 1) => {
    setIndiceAmpliado((atual) => {
      if (atual === null) return atual
      const alvo = atual + direcao
      if (alvo < 0 || alvo >= itens.length) return atual
      return alvo
    })
  }

  const aoTeclarNoLightbox = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Escape') setIndiceAmpliado(null)
    else if (e.key === 'ArrowLeft') navegarLightbox(-1)
    else if (e.key === 'ArrowRight') navegarLightbox(1)
  }

  const itens = modoPreparacao
    ? urlsLocais.map((url, i) => ({ chave: `local-${i}`, url }))
    : imagens.map((img) => ({ chave: img.idImagem, url: img.url }))

  const cheia = itens.length >= MAX_IMAGENS_POR_PRODUTO
  const ocupado = enviar.isPending || excluir.isPending || reordenar.isPending

  return (
    <section className="section">
      <p className="section-label">
        Fotos ({itens.length}/{MAX_IMAGENS_POR_PRODUTO})
      </p>
      {modoPreparacao && (
        <p className="muted" style={{ marginTop: 0 }}>
          As fotos escolhidas aqui são enviadas quando você salvar o produto.
        </p>
      )}

      {itens.length > 0 && (
        <div className="galeria-imagens-produto">
          {itens.map((item, i) => (
            <div key={item.chave} className="galeria-imagem-item">
              <img
                src={item.url}
                alt={`Foto ${i + 1} do produto`}
                onClick={() => setIndiceAmpliado(i)}
                style={{ cursor: 'zoom-in' }}
              />
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
                    disabled={i === itens.length - 1 || ocupado}
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
                    onClick={() => setIndiceParaExcluir(i)}
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

      {indiceParaExcluir !== null && (
        <div className="modal-overlay" onClick={() => setIndiceParaExcluir(null)}>
          <div className="modal" role="dialog" aria-label="Confirmar exclusão" onClick={(e) => e.stopPropagation()}>
            <h2 style={{ marginTop: 0 }}>Excluir foto?</h2>
            <p className="muted">Tem certeza que deseja excluir esta foto?</p>
            <div className="ajuda-rodape">
              <button type="button" className="btn ghost" onClick={() => setIndiceParaExcluir(null)}>
                Cancelar
              </button>
              <button type="button" className="btn" disabled={excluir.isPending} onClick={confirmarExclusao}>
                {excluir.isPending ? 'Excluindo…' : 'Excluir'}
              </button>
            </div>
          </div>
        </div>
      )}

      {indiceAmpliado !== null && itens[indiceAmpliado] && (
        <div className="modal-overlay" onClick={() => setIndiceAmpliado(null)}>
          <div
            className="modal modal-lightbox"
            role="dialog"
            aria-label="Foto ampliada"
            tabIndex={-1}
            ref={(el) => el?.focus()}
            onKeyDown={aoTeclarNoLightbox}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="lightbox-topo">
              <span className="muted">
                Foto {indiceAmpliado + 1} de {itens.length}
              </span>
              <button
                type="button"
                className="btn ghost btn-fechar-tela"
                onClick={() => setIndiceAmpliado(null)}
                aria-label="Fechar"
                title="Fechar"
              >
                <IconeFechar />
              </button>
            </div>
            <div className="lightbox-corpo">
              <button
                type="button"
                className="btn ghost lightbox-nav"
                disabled={indiceAmpliado === 0}
                onClick={() => navegarLightbox(-1)}
                aria-label="Foto anterior"
                title="Foto anterior"
              >
                <IconePaginaAnterior />
              </button>
              <img
                src={itens[indiceAmpliado].url}
                alt={`Foto ${indiceAmpliado + 1} do produto (ampliada)`}
                className="lightbox-imagem"
              />
              <button
                type="button"
                className="btn ghost lightbox-nav"
                disabled={indiceAmpliado === itens.length - 1}
                onClick={() => navegarLightbox(1)}
                aria-label="Próxima foto"
                title="Próxima foto"
              >
                <IconeProximaPagina />
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}
