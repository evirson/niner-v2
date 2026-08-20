# Spec: Cancelamento de Venda                      Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-30 · Módulo(s): `vendas` (cancelamento) · Fase: 2 — Vendas/Financeiro

> ⚠️ **Migrada em 2026-08-18**: a tela dedicada `/cancelamento-venda` (com a busca própria
> descrita abaixo) saiu do menu — a regra de negócio inteira (RN-01 a RN-06, o resumo do que
> reverte, o bloqueio de crediário recebido) continua valendo tal como está aqui, mas passou a
> viver dentro do popup de detalhe da **Pesquisa de Vendas** (`docs/telas/pesquisa-vendas.md`),
> num botão "Cancelar venda" (ADMIN-only). `CancelamentoVendaModal.tsx` foi reaproveitado sem
> mudança de lógica, só o prop de entrada trocou de `venda` (linha da grade antiga) pra
> `idVenda`. A seção "Busca de vendas" abaixo descreve a tela antiga — hoje quem busca a venda é
> a Pesquisa de Vendas.

## Problema

Uma venda finalizada no PDV não tinha nenhuma forma de ser desfeita — nem `venda` tinha um
conceito de situação (Finalizada/Cancelada), nem existia rotina que revertesse o estoque, o
caixa e as parcelas de `contas_receber` gerados por ela. O pedido original (documento colado
pelo dono do produto, formato de spec de um ERP de referência — "Mitryus") cobre um escopo mais
amplo do que o sistema tem hoje (comissão, documento fiscal NFC-e/NF-e, TEF, permissão granular
por rotina) — as decisões de escopo abaixo fecham essa lacuna antes de implementar.

## Solução proposta

Tela dedicada (`/cancelamento-venda`, menu ADMIN-only) que busca vendas finalizadas por número
ou por intervalo de datas (+ empresa/cliente/vendedor opcionais), mostra o detalhe (itens,
formas de pagamento) num modal de confirmação, e executa o cancelamento numa única transação:
devolve o estoque, apaga os lançamentos de caixa e as parcelas em aberto geradas pela venda.

## Decisões de escopo (confirmadas com o dono do produto via `AskUserQuestion` antes de implementar)

O documento original assume comissão, fiscal e TEF — nenhum dos três existe no sistema hoje
(nenhuma venda gera comissão de verdade, não há emissão fiscal nem integração com maquininha).
Fechado como **non-goals do v1** — o cancelamento reverte só estoque, caixa e contas a receber.

**Regra do caixa (RN-02) simplificada.** O documento original pede o caixa "da data da venda"
aberto. Decisão: exige o **caixa de hoje aberto** (do usuário que está cancelando, na empresa da
venda) — o estorno de caixa em si é uma exclusão física dos lançamentos originais (não um novo
lançamento datado de hoje), então a regra é uma trava de "operação do dia", não uma tentativa de
reconciliar o caixa original.

> ⚠️ **Decisão consciente, revalidada em 2026-08-20 — leia antes de "consertar".**
>
> A justificativa original desta regra era *"não existe rota pra reabrir um caixa já fechado de uma
> data passada"*. **Essa razão morreu em 2026-08-14**, quando `POST /api/v1/caixa/fechamento/{id}/reabrir`
> e o guard `CaixaService.exigirCaixaAbertoParaDesfazer` passaram a existir. Uma auditoria de
> 2026-08-20 levantou a divergência, e **o dono do produto decidiu manter a regra como está**.
>
> **O custo que se aceita, dito com todas as letras:** `CancelamentoVendaService` é o único dos
> quatro `DELETE FROM caixa_detalhe` do sistema que **não** chama o guard. Cancelar uma venda cujo
> caixa já foi fechado apaga o lançamento daquele caixa **sem aviso** — e a conferência gravada em
> `caixa_fechamento_conferencia` passa a afirmar um total que não existe mais. Exemplo: venda de
> 18/08 de R$ 500 no caixa 5, fechado e conferido; cancelada em 20/08, o caixa 5 passa a somar R$ 0
> contra uma conferência que diz R$ 500.
>
> **Por que se aceita:** exigir a reabertura do caixa daquele dia transformaria um cancelamento
> corriqueiro num procedimento de duas etapas com 409 no meio. O sistema ainda está em construção e
> sem cliente real; quando houver conferência de caixa valendo dinheiro de verdade, esta decisão
> merece uma segunda passada.
>
> Isto **não** é a mesma coisa que a regra geral do `CLAUDE.md` ("desfazer dinheiro nunca toca caixa
> fechado em silêncio"): é a **exceção conhecida** dela, e a única. Rotina nova que apague
> `caixa_detalhe` continua obrigada a chamar o guard.

**Permissão (RN-04) simplificada.** O documento pede uma permissão granular
(`VENDA_CANCELAR`) no cadastro de perfis — o sistema só tem dois papéis (ADMIN/OPERADOR), sem
permissão fina por usuário (`usuario_rotina`, adiado de propósito noutra sessão). Decisão:
**tela inteira ADMIN-only** (mesmo padrão de Fechamento de Caixa/Usuários) — RN-01 ("empresa
conforme perfil do usuário", pensada originalmente pra diferenciar operador de administrador)
fica sem efeito prático até existir permissão granular: só ADMIN acessa, e ADMIN sempre pôde
escolher qualquer empresa.

**Bloqueio de "outras formas com baixa efetuada" (seção 4.3 do documento original) não se
aplica.** No niner-v2, uma venda à vista (dinheiro/Pix) já grava o pagamento como recebido no
próprio ato da venda — não existe um passo de "baixa" separado pra essas formas (diferente de um
boleto que é pago depois). Aplicar a regra ao pé da letra bloquearia cancelar quase qualquer
venda à vista. Decisão: **só RN-03 (crediário com parcela recebida) bloqueia definitivamente**;
as demais formas de pagamento (à vista, débito, crédito — quitadas ou em aberto, ver
`docs/telas/pdv.md`) são sempre revertidas junto com a venda.

## User stories

- Como ADMIN, quero localizar uma venda por número ou por período e ver o que ela contém antes
  de decidir cancelar.
- Como ADMIN, quero que o cancelamento devolva o estoque, remova os lançamentos de caixa e as
  parcelas em aberto, tudo de uma vez, sem eu precisar fazer isso manualmente em três telas.
- Como ADMIN, se a venda for de crediário e já tiver parcela recebida, quero ser impedido de
  cancelar e saber exatamente quais parcelas foram recebidas, pra decidir estornar o recebimento
  primeiro.
- Como ADMIN, quero informar o motivo do cancelamento e confirmar explicitamente antes da
  operação acontecer.

## Regras de negócio

### RN-01 — ADMIN-only (ver decisão de escopo acima)

Tela e API inteiras exigem `roles` conter `ADMIN` — `CancelamentoVendaService.exigirAdmin`,
mesmo mecanismo de `ConfiguracaoGeralService`/`UsuarioService`. 403 pra OPERADOR.

### RN-02 — Caixa de hoje aberto (ver decisão de escopo acima)

`CaixaService.caixaAbertoHoje(idEmpresa da venda, idUsuario logado)` — 400 com mensagem
específica se fechado. Checado **depois** de RN-03 (a ordem importa: RN-03 é definitivo e
específico, não faz sentido o operador abrir o caixa só pra descobrir depois que a venda nunca
poderia ser cancelada de qualquer forma).

### RN-03 — Crediário com parcela recebida: bloqueio definitivo

Se qualquer parcela de `contas_receber` desta venda, numa carteira `categoria_carteira =
CREDIARIO`, tem `data_recebimento IS NOT NULL`, o cancelamento é rejeitado (409) — **nenhum
perfil, nenhuma exceção**. A mensagem inclui o detalhe de cada parcela recebida (número,
vencimento, data de recebimento, valor). Checado **antes** de RN-02.

### RN-05 (numeração do documento original pulava o 05) — não se aplica

Seção de permissão de acesso do documento original virou RN-04 acima (ADMIN-only); não há RN-05
distinta no v1.

### RN-06 — Confirmação explícita

O modal de cancelamento sempre mostra o resumo da venda + itens + formas de pagamento antes de
qualquer ação; exige motivo não vazio; o botão de ação é "Sim, Cancelar Venda".

**Layout do popup (revisado 2026-07-31).** Cabeçalho (até a linha Cliente/Vendedor) fixo, sem
scroll; corpo (itens, formas de pagamento, mensagem de bloqueio quando aplicável) rola por
dentro; "Motivo do Cancelamento" + o botão "Sim, Cancelar Venda" fixos no rodapé, também sem
scroll (flexbox `overflow:hidden` no modal + `overflow-y:auto` só no meio, mesmo padrão de
`DetalheVendaModal`). O botão "Não" foi **removido** — fechar é só o "✕" no canto superior
direito do cabeçalho (funciona em qualquer estado: confirmação, já cancelada, ou bloqueada por
crediário recebido). Quando a venda já está cancelada ou bloqueada, o rodapé de ação nem
aparece — só o "✕" fecha o popup.

## O que o cancelamento reverte

Tudo dentro de uma única transação (`CancelamentoVendaService.cancelar`) — se qualquer etapa
falhar, rollback completo:

- **Estoque:** um novo `produto_movimento_mestre` (`tipo_movimento = 'CANCELAMENTO'`, novo
  valor de enum) + um `produto_movimento_detalhe` (`credito_debito = 'C'`) por item vendido — a
  trigger `fn_atualiza_estoque_movimento` (já existente) soma de volta em `produto_estoque`
  sozinha, mesmo mecanismo do PDV/Transferência. O `produto_movimento_mestre` original
  (`tipo_movimento = 'VENDA'`) nunca é apagado nem alterado (P3, ledger imutável) — o
  cancelamento é um novo lançamento, não uma correção do antigo.
- **Caixa:** `DELETE FROM caixa_detalhe WHERE id_venda = ?` — apaga fisicamente os lançamentos
  originais da venda (mesmo padrão já usado por Estorno de Recebimento de Crediário; nunca mexe
  em `caixa_mestre`, que pode ter lançamentos de outras vendas do mesmo dia).
- **Contas a receber:** `DELETE FROM contas_receber_detalhe` (detalhe de cartão, se houver) e
  depois `DELETE FROM contas_receber WHERE id_venda = ?` — remove todas as parcelas geradas pela
  venda (quitadas ou em aberto; RN-03 já garantiu que nenhuma é de crediário recebido).
- **Vale-mercadoria (2026-08-03):** `UPDATE venda_devolucao SET vale_usado = false,
  id_venda_debito = NULL WHERE id_venda_debito = ?` — se a venda tiver resgatado um vale
  (`docs/telas/devolucao-produtos.md`), ele volta a valer pro cliente usar numa venda futura, sem
  deixar rastro de que já tinha sido usado (mesma exclusão física dos itens acima).
- **Comissão, TEF:** fora do v1 (ver decisões de escopo). **Fiscal (NFC-e) não está mais fora** —
  ver "Integração fiscal" abaixo.

`venda` grava `cancelada = true`, `data_cancelamento`, `id_usuario_cancelamento` (pode ser
diferente de quem vendeu — é sempre o ADMIN que cancelou) e `motivo_cancelamento` — funciona como
o próprio registro de auditoria (P3), sem precisar de uma tabela de log separada; o resumo do
que foi revertido é reconstruível a qualquer momento a partir do ledger imutável
(`produto_movimento_mestre` com `tipo_movimento = 'CANCELAMENTO'` e `id_venda`).

## Contrato de API

```
GET  /api/v1/vendas/cancelamento?numeroVenda=&idEmpresa=&idCliente=&dataInicial=&dataFinal=&idFuncionario=&pagina=&limite=&ordenarPor=&direcao=
GET  /api/v1/vendas/cancelamento/{idVenda}          detalhe (itens, pagamentos, bloqueio de crediário)
POST /api/v1/vendas/cancelamento/{idVenda}          { motivo } → executa o cancelamento
```

`numeroVenda`, se informado, ignora os demais filtros de busca (mas ainda respeita RLS/ADMIN).
Sem `numeroVenda`, `dataInicial`/`dataFinal` são obrigatórios (400 se ausentes, se
`dataInicial > dataFinal`, ou se o intervalo passar de 365 dias). Por padrão só vendas ainda não
canceladas aparecem na listagem por período — buscar por número mostra a venda mesmo já
cancelada (pra exibir o aviso "já cancelada em..."). Todos sob `/api/v1/**` (JWT de tenant, RLS —
P8), ADMIN-only. Erros em Problem Details: 400 (filtros inválidos, motivo vazio, caixa
fechado), 403 (OPERADOR), 404 (venda inexistente/de outro tenant), 409 (já cancelada, ou
bloqueio de crediário — RN-03).

## Critérios de aceitação (viram testes)

- Dado um OPERADOR, quando acessa a listagem ou tenta cancelar, então 403.
- Dado uma venda à vista com estoque/caixa/contas a receber gerados, quando cancelada com
  sucesso, então o estoque volta à quantidade original, `caixa_detalhe` e `contas_receber` da
  venda ficam vazios, e `venda` grava `cancelada/data_cancelamento/id_usuario_cancelamento/
  motivo_cancelamento`.
- Dado uma venda já cancelada, quando tenta cancelar de novo, então 409.
- Dado o caixa de hoje fechado, quando tenta cancelar, então 400.
- Dado uma venda de crediário com parcela recebida, quando tenta cancelar, então 409 e **nada**
  é revertido (nem estoque, nem contas a receber) — a validação barra antes de qualquer mudança.
- Dado uma venda de crediário **sem** parcela recebida, quando cancelada, então funciona
  normalmente (a parcela em aberto é simplesmente removida).
- Dado o número de uma venda, quando buscado, então ignora os demais filtros (inclusive um
  intervalo de datas que não bateria).
- Dado uma venda de um tenant, então não aparece nem pode ser cancelada por outro tenant (RLS).
- Dado uma venda que resgatou um vale-mercadoria, quando cancelada, então o vale volta a
  `vale_usado = false`/`id_venda_debito = NULL` e pode ser usado numa venda futura (2026-08-03,
  coberto em `ValeMercadoriaCrudTest`).
- Dado uma venda com NFC-e autorizada, dentro do prazo, quando a SEFAZ aceita o cancelamento,
  então o documento fiscal vira `CANCELADO` e a venda reverte junto (mesma transação); dado a
  SEFAZ recusa (ou falha a comunicação), então 409 e **nada** reverte (2026-08-17, B8).
- Dado uma venda com NFC-e cujo prazo de cancelamento da SEFAZ já passou, quando busca o detalhe,
  então `nfcePrazoCancelamentoExpirado = true` (calculado no servidor); quando tenta cancelar,
  então 409 **sem** chamar a SEFAZ (2026-08-19).

Cobertos por `CancelamentoVendaCrudTest` (10 testes) + 1 teste em `ValeMercadoriaCrudTest`
(reabertura do vale) + `CancelamentoNfceTest` (5 testes — integração fiscal: autorizado, recusado,
prazo vencido bloqueia sem chamar a SEFAZ, prazo exposto no detalhe dentro/fora da janela, venda
sem NFC-e ignora a SEFAZ) + `SefazTransporteTest` (extração do `cStat` certo num evento com dois,
regressão do bug de 2026-08-19). Vendas de teste são geradas pelo endpoint real do PDV (não
inseridas via SQL bruto), pra exercitar o ledger de verdade. Suíte completa do projeto: 775/775
(2026-08-19).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `vendas.cancelamentovenda.lista`** — ver `web/src/components/AjudaDaTela.tsx`.
  `url_video`: `NULL` por ora.

## Impacto no banco

- `venda` ganha `cancelada boolean NOT NULL DEFAULT false`, `data_cancelamento timestamptz`,
  `id_usuario_cancelamento integer` (FK composta pra `usuario`), `motivo_cancelamento text` —
  editado dentro de `V018__vendas.sql` (banco em construção, sem nova migration numerada).
- `tipo_movimento` (ENUM, V013) ganha o valor `CANCELAMENTO`.
- Nenhuma tabela nova — reaproveita `produto_movimento_mestre/detalhe`, `caixa_detalhe`,
  `contas_receber`/`contas_receber_detalhe` já existentes, e (2026-08-03) `venda_devolucao`
  (reabertura de vale-mercadoria — ver `docs/telas/devolucao-produtos.md`).

## Impacto nas integrações

Nenhum — comissão, fiscal e TEF ficam fora do v1 (ver decisões de escopo).

## Non-goals desta feature

- **Comissão, TEF** — nenhum dos dois existe no sistema; fora do v1 por completo (ver decisões de
  escopo). **Documento fiscal deixou de ser non-goal em 2026-08-17 (bloco B8)** — ver seção
  "Integração fiscal" abaixo; esta lista não foi atualizada quando o B8 entrou, ficando
  desalinhada com o código por dois dias até a auditoria de 2026-08-19.
- **Permissão granular por rotina (`VENDA_CANCELAR`)** — tela ADMIN-only por enquanto.
- **Reabrir o caixa de uma data passada** — a regra usa sempre o caixa de hoje. ⚠️ A rota de reabertura **passou a existir** em 2026-08-14; manter isto fora do escopo virou **decisão consciente** em 2026-08-20, com o custo escrito na seção "Decisões de escopo" acima. Não é ponteiro morto.
- **Prazo/limite de dias para cancelar uma venda antiga em geral** — continua não existindo; uma
  venda **sem** NFC-e pode ser buscada e cancelada a qualquer momento, sem limite de idade. O que
  existe desde 2026-08-19 é o prazo específico da **SEFAZ para cancelar a NFC-e** (30 min padrão,
  por UF) — ver "Integração fiscal" abaixo; não é um limite geral do cancelamento de venda.
- **Senha de supervisor no ato do cancelamento** — não pedida (ADMIN já é o papel mais alto).
- **Relatório "Vendas canceladas por período"** — os dados existem (`venda.cancelada` +
  metadados), mas nenhuma tela de relatório foi construída ainda.

## Integração fiscal (bloco B8, 2026-08-17 — ausente desta spec até a auditoria de 2026-08-19)

Quando a venda tem uma NFC-e `AUTORIZADO`, o cancelamento **cancela a nota na SEFAZ antes de
reverter qualquer coisa** (evento 110111) — `CancelamentoVendaService.cancelar` chama
`CancelamentoNfceService.cancelarSeAplicavel` logo depois de RN-02/RN-03 e antes de qualquer
`UPDATE`/`DELETE`. Garantia central: **estoque/caixa e fiscal nunca divergem** — ou os dois
revertem juntos, ou nenhum reverte:

- **Sem NFC-e** (F12 desligado, ou a nota não terminou autorizada) — `cancelarSeAplicavel` devolve
  vazio e o cancelamento segue exatamente o fluxo de sempre, sem tocar na SEFAZ.
- **NFC-e autorizada, dentro do prazo, SEFAZ aceita** — o evento é registrado
  (`documento_fiscal_evento`, `autorizado = true`), o documento vira `CANCELADO`, e só então a
  venda reverte (mesma transação).
- **NFC-e autorizada, SEFAZ recusa, ou falha de comunicação** — 409, **nada** reverte (nem
  `venda.cancelada`, nem estoque, nem caixa); a tentativa fica registrada mesmo assim (P3, F11).
- **Prazo de cancelamento da SEFAZ vencido** (30 min padrão pra NFC-e, `cfg_uf_autorizador.
  prazo_cancelamento_min` por UF — bem menor que os 15 min de tolerância que se imagina; NF-e é
  24h) — 409 **sem sequer chamar a SEFAZ**, apontando a saída certa (nota de devolução, ainda não
  implementada — B9, travado pela DF20). **Desde 2026-08-19** esse prazo aparece na tela **antes**
  do usuário tentar: `GET .../cancelamento/{idVenda}` devolve `nfceDataAutorizacao`/
  `nfcePrazoCancelamentoMinutos`/`nfcePrazoCancelamentoExpiraEm`/`nfcePrazoCancelamentoExpirado`
  (calculado no servidor), e o modal esconde o motivo/botão por completo quando já expirou, com a
  mesma mensagem do 409.

🔴✅ **Bug real corrigido em 2026-08-19** (achado cancelando uma venda real, número 590): a
resposta de `RecepcaoEvento4` tem **dois** `cStat` (128 do lote de evento, 135 do evento em si,
mesma armadilha do bug de emissão de 2026-08-18) — sem escopo pro bloco certo, `SefazTransporte`
lia o do lote e **todo cancelamento saía "recusado" mesmo quando a SEFAZ autorizava de verdade**.
Corrigido em `SefazTransporte.enviar()`, com teste de regressão. Ver `docs/PROGRESSO.md`
(2026-08-19) e `docs/MODULOFISCAL.md` §18 pro relato completo, incluindo o efeito colateral na
UNIQUE de `documento_fiscal_evento` (coluna `tentativa` nova, pra permitir retentativa do mesmo
evento sem perder o rastro de nenhuma tentativa).

## Questões abertas

- Conciliação de cartões (baixar parcelas de débito/crédito quando a operadora liquidar) —
  mencionada pelo dono do produto como próximo passo relacionado, fora desta feature.

## Feature relacionada

`docs/telas/cancelamento-devolucao-produtos.md` (2026-08-10) cancela o vale-mercadoria de uma
**devolução**, não uma venda — acesso ADMIN+OPERADOR (aqui é ADMIN-only) com OPERADOR restrito à
empresa logada. Se a venda cancelada aqui tiver resgatado um vale, ele é reaberto (seção acima);
se em vez disso for a *devolução* que precisa ser desfeita, é a outra tela.

## Métrica de sucesso

Cancelamento de uma venda simples (busca → confirmação → efetivação) em menos de 30 segundos,
com o operador sempre vendo o que será revertido antes de confirmar.
