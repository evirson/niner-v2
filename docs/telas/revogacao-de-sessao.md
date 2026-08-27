# Spec: Revogação de sessão                              Status: Aprovada — implementada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-27 · Módulo(s): `identidade.usuario` · Fase: 1 — Núcleo do ERP

## Problema

O JWT vale **8 horas**, não tem `jti` e não havia lista de revogação. Consequência medida pela
auditoria de segurança: **desativar, excluir ou trocar a senha de alguém não o tirava do sistema**.
Um funcionário demitido continuava operando o resto do expediente — vendendo, cancelando,
imprimindo nota — com o token que já estava na mão dele.

Pedido do dono do produto: *"se a senha for trocada, ou o usuário desativado, tem que efetuar o
logoff do respectivo usuário o mais breve possível — algo que não deixe o sistema lento e não
consuma muitos recursos do servidor"*.

## Solução: uma coluna, zero consulta nova

`usuario.sessao_valida_desde timestamptz` (V080). **Token emitido antes desse instante é
recusado.** Três eventos a fazem avançar:

| Evento | Onde |
|---|---|
| Troca de senha pelo cadastro de usuários | `UsuarioService.atualizar` |
| Redefinição pelo "Esqueci minha senha" | `RecuperacaoSenhaService` |
| Desativação (`ativo = false`), inclusive a inativação que substitui a exclusão | `UsuarioService.atualizar` / `.excluir` |

Exclusão de verdade não precisa de coluna nenhuma: a linha some, e sessão sem usuário é sessão
morta.

⭐ **Por que isso não custa nada.** Já existia **uma** consulta por requisição autenticada — a do
horário de acesso, desde 2026-08-11. As perguntas novas ("o usuário está ativo?", "o token ainda
vale?") entraram na **mesma linha do mesmo `SELECT`, pela mesma PK**. Nenhuma consulta nova, nenhum
cache para invalidar, nenhuma tabela de sessões, nenhum Redis. O logoff acontece na **requisição
seguinte** — que é o "mais breve possível" possível sem WebSocket.

⚠️ **Guardar o instante, e não um contador**, é o que permite comparar direto com o `iat` que já
vem assinado dentro do token. Sem estado de sessão no servidor (P6).

## ⚠️ O defeito que o caso negativo pegou

O `iat` do JWT é um **NumericDate: segundos inteiros**. Ele é sempre *anterior* ao instante real da
emissão — 10:00:05.400 vira 10:00:05. Comparando cru contra a coluna, um usuário **recém-criado**
(`sessao_valida_desde` = 10:00:05.100, o `DEFAULT now()` da V080) já nascia com a sessão
"revogada": o token que o signup acabava de devolver era recusado na primeira requisição.

Quem pegou isso foi o teste **negativo** — *"editar o nome não pode derrubar ninguém"* —, não os
três positivos. A correção é comparar **truncando ao segundo**, o que dá no máximo 1 segundo de
sobrevida a um token revogado (irrelevante) e elimina o falso positivo por completo.

⭐ Vale a lição: uma suíte só com casos positivos teria aprovado um mecanismo que **expulsava todo
mundo**.

## ⚠️ O staff não passa por aqui

Token de backoffice (`/api/admin`) **não tem `tid`**, não passa por `TenantContext`, e a consulta
de sessão responderia "usuário não existe" para ele. Sem o desvio explícito, esta mudança
**derrubaria o backoffice inteiro** — o filtro só age sobre token de tenant.

## Critérios de aceitação

- **Dado** um operador logado, **quando** o admin troca a senha dele, **então** a requisição
  seguinte dele responde **401**.
- **Idem** ao desativar e ao excluir.
- **Dado** uma edição que só muda o nome, **então** a sessão continua valendo.
- **Dado** um token de staff, **então** o backoffice continua funcionando.

Testes: `api/src/test/java/com/vetor/niner/RevogacaoDeSessaoTest.java` (4 casos).

## O que ficou de fora, e por quê

- **Revogar por dispositivo** ("encerrar sessão neste aparelho") exigiria `jti` e uma tabela de
  sessões — a granularidade é o **usuário**, que é o que o pedido descreve.
- **Encerrar a sessão de quem troca a própria senha** acontece como efeito: ele volta ao login. É o
  comportamento esperado de qualquer sistema, e o custo de evitá-lo (comparar quem pediu) não se
  justifica.
- O 401 leva a mensagem *"Sua sessão foi encerrada. Entre novamente."* — e é **401, não 403**, de
  propósito: o front já trata 401 mandando para o login, que é exatamente o que precisa acontecer.
