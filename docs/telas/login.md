# Spec: Login sem identificador de conta               Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-27 · Módulo(s): `plataforma.onboarding`, `plataforma.diretorio` · Fase: 1 — Núcleo do ERP

## Problema

A tela de login pedia **três** coisas: identificador da loja, e-mail e senha. O dono do produto
abriu o assunto assim: *"não me sinto confortável com este identificador de loja, acho que o
usuário vai se confundir"*.

O desconforto tem base no código. Esse identificador (`plataforma.tenant.slug`) é **derivado pelo
sistema** no signup (`SignupService.slugUnico`): "Mercado do João" vira `mercado-do-joao` e, se já
existir, `mercado-do-joao-2`. O usuário **nunca o escolheu** — ele só o via num `<code>` do Painel.
Exigir na entrada um dado que o próprio sistema inventou é pedir para a pessoa decorar um detalhe
interno.

E havia um agravante: a **recuperação de senha também o exigia**. Quem esqueceu a senha não vai
lembrar do identificador; e como aquele endpoint responde `204` mesmo para conta inexistente (de
propósito, para não revelar quem é cliente), errar o identificador significava **nenhum e-mail
chegando, sem explicação**.

### Por que o identificador estava ali

`usuario.email` é único apenas **dentro** do tenant (V015: `UNIQUE (id_tenant, lower(email))`), e
`usuario` está sob `FORCE ROW LEVEL SECURITY`. Sem saber o tenant, não há como sequer *procurar* o
e-mail: a consulta devolve zero linhas. O identificador era o que dizia onde procurar.

## Solução

O login passa a pedir **e-mail e senha**. Quem descobre a conta é o **diretório de login**
(`plataforma.diretorio_login`, V071): um índice global `e-mail → (célula, tenant, usuário)`, fora
do RLS porque é consultado por quem ainda não sabe de que tenant é a requisição.

O e-mail **continua podendo se repetir** entre contas — decisão explícita do dono do produto, com
o caso de uso dele: *"hoje o usuário pode ter uma empresa que vende cosméticos, e amanhã ele cria
uma nova empresa que vende sapato, aí seria um tenant diferente, que pode estar até em outra
célula"*. O empate se resolve assim:

1. o diretório devolve **todas** as contas daquele e-mail;
2. a senha é conferida **em cada uma**;
3. casou em **uma** → entra direto, sem pergunta nenhuma (é o que acontece quando as senhas são
   diferentes, que é o caso comum);
4. casou em **mais de uma** → a tela pergunta qual conta.

⚠️ **A lista de contas só é montada depois que a senha bate.** Devolvê-la antes transformaria o
login numa consulta pública de *"este e-mail é cliente de vocês, e de quantas contas?"* — a mesma
preocupação que faz a recuperação de senha responder `204` sempre.

## Fluxo

`POST /api/publico/login`, em até **três** voltas — na prática, uma:

1. `{email, senha}` → o back busca no diretório e confere a senha em cada candidata.
   - **Nenhuma casa** → `401 "Credenciais inválidas."` — a mesma resposta para e-mail inexistente,
     senha errada, conta inativa e escolha inválida. Distinguir qualquer um desses casos daria a
     quem está adivinhando um oráculo de graça.
   - **Uma casa** → segue para a escolha de empresa (abaixo).
   - **Mais de uma** → `200` com `{"token": null, "escolherConta": true, "contas": [...]}`.
2. `{email, senha, idTenant}` (só quando a anterior pediu) — a senha é conferida **de novo**; a
   escolha apenas restringe onde olhar, nunca substitui a autenticação.
3. `{email, senha, idTenant?, idEmpresa}` — a escolha de empresa que já existia desde 2026-07-28
   (`docs/telas/login-empresa.md`), inalterada.

## Contrato de API

**Requisição**
```json
{ "email": "joao@gmail.com", "senha": "…", "idTenant": 7, "idEmpresa": 3 }
```
`idTenant` e `idEmpresa` são opcionais — cada um responde a uma pergunta que a volta anterior
deixou aberta.

**Resposta**
```json
{ "token": "…", "idTenant": 7, "slug": "sapataria-do-joao",
  "escolherConta": false, "contas": [],
  "escolherEmpresa": false, "empresas": [] }
```
Quando falta uma escolha, `token` vem `null` e **exatamente um** dos dois sinalizadores vem ligado.
No caso de `escolherConta`, `idTenant` e `slug` vêm vazios — ainda não há conta escolhida.

## O identificador não morreu

`tenant.slug` continua existindo e sendo gerado no signup: é a identidade técnica da conta
(endereço, backoffice, chave de armazenamento). Ele apenas **saiu da porta de entrada**.

## Como o diretório se mantém

Por **trigger** (`usuario_diretorio_login_tg`), não pelos serviços. Mexem em `usuario` o signup, a
criação, a edição, a exclusão e o caminho que apenas *inativa* o usuário quando há caixa vinculado
— e o que vier depois. É a mesma decisão (e o mesmo motivo) do gatilho de sincronização de estoque
da V067: espalhar a manutenção por serviço garante, matematicamente, que um caminho fique de fora,
e o que ficar de fora vira um login que não acha a conta — ou, pior, um e-mail antigo que continua
abrindo a porta.

O diretório é um **índice reconstruível**, não a verdade: a conta de verdade continua sendo a linha
em `usuario`, dentro da célula. É isso que dispensa transação distribuída quando o catálogo sair
para fora da célula (ver `docs/infra/parque-de-celulas.md`).

⚠️ **`niner_app` só lê o diretório.** A escrita é da trigger, que roda como `niner_owner`. Isso
exigiu um `REVOKE` explícito: a V011 declarou `ALTER DEFAULT PRIVILEGES … GRANT SELECT, INSERT,
UPDATE, DELETE` para toda tabela **nova** do schema `plataforma`, então a permissão de escrita
nasce ligada sem que nada no arquivo da migration diga isso.

## Critérios de aceitação

- **Dado** um usuário com e-mail e senha válidos, **quando** ele envia só e-mail e senha,
  **então** recebe o token — sem informar identificador nenhum.
- **Dado** o mesmo e-mail em duas contas com **senhas diferentes**, **quando** ele entra com uma
  das senhas, **então** entra direto na conta correspondente, sem ver tela de escolha.
- **Dado** o mesmo e-mail em duas contas com a **mesma senha**, **quando** ele entra, **então**
  recebe `escolherConta=true` com as duas contas e **nenhum token**; reenviando com `idTenant`,
  recebe o token daquela conta.
- **Dado** um `idTenant` de uma conta em que a senha **não** bate, **quando** ele envia,
  **então** recebe `401`.
- **Dado** o mesmo e-mail em duas contas, **quando** a senha está **errada**, **então** a resposta
  `401` **não cita** nenhuma das contas.
- **Dado** um usuário **inativo**, **quando** ele entra com a senha certa, **então** recebe `401`
  com a mesma mensagem genérica.
- **Dado** um usuário no diretório, **quando** seu e-mail é trocado, **então** o e-mail antigo sai
  do diretório e deixa de autenticar; **quando** ele é excluído, **então** sua linha some.
- **Dado** o papel `niner_app`, **quando** ele tenta inserir no diretório, **então** o banco nega.

Testes: `api/src/test/java/com/vetor/niner/LoginSemIdentificadorTest.java` (8 casos).

## Recuperação de senha (2026-08-27, no mesmo dia)

A tela de login ganhou **"Esqueci minha senha"**, e o fluxo inteiro passou a existir de ponta a
ponta. Antes deste dia ele estava pela metade: o backend existia, mas **nenhuma tela** — e o link
que o e-mail mandava (`{web-base-url}/redefinir-senha?token=…`) apontava para uma rota que **não
existia no `web/`**. Ninguém tinha notado porque o sistema nunca havia enviado um e-mail: o SMTP
foi configurado nesta mesma manhã.

**O pedido também perdeu o identificador.** Era o pior lugar para exigi-lo — quem esqueceu a senha
não lembra de um valor que o sistema inventou — e, como a resposta é sempre `204`, errá-lo
significava **nenhum e-mail chegando, sem explicação**.

**Um e-mail, um link por conta.** Se o e-mail estiver em mais de uma conta, chega uma única
mensagem com uma linha por conta, cada uma nomeando-a ("Redefinir a senha de **SAPATARIA**").
Sem isso a pessoa receberia dois links idênticos e redefiniria a senha da conta errada.

⚠️ **O caminho `/redefinir-senha` não pode ser renomeado**: ele é o destino de links que vivem 2
horas na caixa de entrada de alguém.

⚠️ Conta **inativa** é pulada em silêncio ao montar o e-mail: mandar um link que não vai
funcionar é pior do que não mandar linha nenhuma para ela.

Telas: `web/src/pages/EsqueciSenha.tsx` (pede o e-mail) e `RedefinirSenha.tsx` (cria a senha).
A tela de pedido responde no **condicional** ("se houver uma conta com esse e-mail") — afirmar que
o e-mail foi enviado seria mentir em metade dos casos, já que a API responde igual para conta
inexistente.

Testes: `RecuperacaoSenhaTest` (6 casos, incluindo o e-mail em duas contas recebendo um link para
cada).

### Critérios de aceitação

- **Dado** um e-mail com conta, **quando** ele pede a redefinição **sem informar identificador**,
  **então** o e-mail sai com o link e o pedido anterior daquele usuário é invalidado.
- **Dado** um e-mail em **duas** contas, **então** chega **um** e-mail com **dois** links, cada um
  nomeando a sua conta.
- **Dado** um e-mail sem conta nenhuma, **então** a resposta é `204` e **nenhum** e-mail sai.
- **Dado** um token já usado, expirado ou inventado, **então** a redefinição responde `400` e a
  tela **mostra a mensagem do servidor**, não uma genérica.

⚠️ **O que não foi exercitado na tela real:** o caminho com token **válido**. Ele está coberto no
teste automatizado (que captura o link do e-mail dublado) e a tela foi verificada com token
inválido; para fechar no navegador basta clicar no link de um e-mail de verdade — o que **troca a
senha da conta**, e por isso não foi feito com a conta de desenvolvimento.
