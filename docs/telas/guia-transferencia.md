# Spec: Guia de Transferência de Produtos              Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-06 · Módulo(s): `estoque` (transferencia) · Fase: 2

## Problema

`docs/telas/transferencia-estoque.md` grava a transferência entre empresas, mas não entregava
nenhum documento impresso pra acompanhar a mercadoria em trânsito entre as lojas — quem recebe
não tinha como conferir contra uma lista impressa, nem o motorista/transportador tinha um
comprovante formal da movimentação.

## Solução proposta

Botão **"Imprimir Guia"** na tela de detalhe de uma transferência já feita
(`TransferenciaDetalhe.tsx`), abrindo um popup de pré-visualização com **Imprimir**/**Salvar
PDF** — mesmo mecanismo do Fechamento de Caixa (`FechamentoCaixaPreviewModal.tsx`), não o do
comprovante de 80mm: **folha A4**, porque a lista de itens pode ser longa (não cabe numa bobina
térmica sem espremer) e o documento é pensado pra ser arquivado/anexado à mercadoria, não jogado
fora depois de conferido.

## Decisões (confirmadas com o dono do produto)

1. **A4, não bobina térmica de 80mm** — decisão explícita entre as duas opções (a alternativa 80mm
   foi descartada porque a lista de produtos de uma transferência pode ter dezenas de linhas,
   diferente de uma venda de balcão).
2. **Linhas de assinatura no rodapé** ("Conferido por (Origem)" / "Recebido por (Destino)") — não
   existe em nenhum outro comprovante do projeto; específico da guia de transferência porque o
   documento precisa da assinatura física de quem confere a saída e de quem confere a chegada.
3. **Sem dado novo no backend** — `Transferencia` (resposta de `GET /api/v1/estoque/transferencias/{id}`,
   já existente) já trazia tudo: empresas origem/destino, usuário, data, observações e itens
   (SKU, descrição, variação, quantidade). A feature é 100% frontend.

## Regras de negócio

### Fonte única de verdade pro layout

`web/src/lib/transferenciaImpressao.ts#montarLinhasGuiaTransferencia()` monta a guia como um
array de linhas de texto monoespaçado (96 colunas, mesma largura do Fechamento de Caixa) —
reusado idêntico pela pré-visualização, pela impressão (`window.print()`) e pelo PDF (`jsPDF`,
fonte courier). Página nomeada própria (`@page guia-transferencia-a4`) em vez de reaproveitar
`fechamento-a4`, só por independência entre as duas features (mesmas dimensões, A4/15mm).

### Colunas da tabela de itens

`CÓDIGO(15) DESCRIÇÃO(46) VARIAÇÃO(16) QTD(10)` — larguras ajustadas depois do primeiro teste com
dado real (descrição de produto real truncava demais em 38 colunas; VARIAÇÃO raramente passa de
15-20 caracteres, ex. "CAFE COM LEITE · 33").

## Correção relacionada: botão X reabrindo o popup de nova transferência

Bug reportado no mesmo dia, mesma tela: clicar no X (fechar) da tela de Transferência de Produtos
às vezes reabria o popup "Nova Transferência" em vez de fechar. Causa raiz: o popup
`EscolherDestinoModal` (primeiro passo de "Nova Transferência", pede a empresa de destino) fechava
com `navigate('/estoque')` — que **empilha** uma entrada nova no histórico do navegador em vez de
voltar. Sequência do bug: usuário abre "Nova Transferência" (push `/estoque/nova`) → cancela o
popup de destino (`navigate('/estoque')`, push de novo — agora há um `/estoque/nova` "fantasma"
no meio do histórico) → na tela de lista, clica X (`BotaoFecharTela`, que usa `navigate(-1)` por
padrão) → volta pro `/estoque/nova` fantasma → `TransferenciaForm` remonta do zero,
`idEmpresaDestino` volta a `''` → `EscolherDestinoModal` aparece de novo. Corrigido trocando
`navigate('/estoque')` por `navigate(-1)` — mesma convenção que `BotaoFecharTela` já usa em todo
o sistema (histórico real, não rota fixa; ver `docs/PROGRESSO.md`, 2026-08-04, "Botão de fechar
(✕) em toda tela").

## Contrato de API

Nenhum novo — reaproveita `GET /api/v1/estoque/transferencias/{id}` (`TransferenciaController`,
já existente).

## Critérios de aceitação (viram testes)

- Dado uma transferência já efetivada, quando abre a guia, então a pré-visualização mostra
  origem, destino, data, usuário, todos os itens (com SKU/descrição/variação/quantidade) e as
  duas linhas de assinatura no rodapé.
- Dado a tela de Transferência de Produtos (lista ou "Nova Transferência" cancelada), quando
  clica no X, então a tela fecha (volta pra tela anterior de verdade), sem reabrir o popup de
  nova transferência.

Sem testes automatizados ainda (typecheck limpo + teste manual com transferência real, não suíte
JUnit — feature 100% frontend, sem lógica de backend nova pra testar).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

`estoque.transferencia.lista` (reaproveitada — é a mesma chave usada pela tela de detalhe)
ganhou um passo novo mencionando o botão "Imprimir Guia".

## Impacto no banco

Nenhum.

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Numeração fiscal / DANFE** — "documento sem valor fiscal", só controle interno entre lojas do
  mesmo tenant.
- **Envio automático por e-mail/WhatsApp** — só impressão/PDF local.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Guia pronta pra imprimir em menos de 2 segundos a partir da tela de detalhe da transferência.
