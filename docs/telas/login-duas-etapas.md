# Spec: Login em duas etapas (código por e-mail)        Status: Aprovada — implementada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-27 · Módulo(s): `plataforma.onboarding` · Fase: 1 — Núcleo do ERP

## Problema

Senha é o único fator para entrar no ERP. Quem descobre a senha do administrador de uma conta tem
tudo: catálogo, financeiro, emissão de nota fiscal, cadastro de usuários.

Pedido do dono do produto: *"uma opção no cadastro do usuário que, quando ativada, o login dele vai
ser em duas etapas — o ERP manda um e-mail com um código de autenticação de 4 dígitos, aleatório,
que vale por 10 minutos. Tem que ter a opção também de reenviar o código."*

## Decisões

1. **Por usuário, não por conta.** Numa loja, o dono pode querer a segunda etapa para si e não
   para o caixa, que entra e sai do sistema o dia inteiro no balcão. A marca é
   `usuario.exige_codigo_login` (V079), editável no cadastro do usuário.
2. **Vale para o administrador também** — ao contrário do horário de acesso, que a tela esconde
   dele. Aqui a proteção existe justamente para a conta mais poderosa.
3. **Código de 4 dígitos**, aleatório (`SecureRandom`), incluindo os que começam com zero
   (`0042` é código válido).
4. **10 minutos** de validade, **uso único**, com **reenvio**.

## Onde o código entra no fluxo

O login já podia fazer até duas perguntas depois da senha (qual conta, qual empresa). O código é a
**terceira**, e vem **por último**:

```
e-mail + senha → [qual conta?] → [qual empresa?] → [código de 4 dígitos] → token
```

⚠️ **Pedir o código antes da senha seria pior de duas formas:** entregaria a qualquer um a
informação de que aquele e-mail existe no sistema (o mesmo oráculo que a tela de recuperação de
senha evita respondendo 204 sempre), e mandaria e-mail à toa a cada tentativa de adivinhação.

## Contrato de API

| Endpoint | O que faz |
|---|---|
| `POST /api/publico/login` | primeira etapa; quando a segunda é exigida devolve `exigeCodigo: true`, `desafio` (UUID) e `emailMascarado`, **sem token** |
| `POST /api/publico/login/codigo` | `{desafio, codigo}` → token |
| `POST /api/publico/login/codigo/reenviar` | `{desafio}` → **204 sempre** |

⚠️ **O desafio é opaco e carrega tudo.** Tenant, usuário e empresa ficam gravados na linha de
`plataforma.codigo_login` — nada disso vem do cliente na segunda etapa. Aceitar qualquer um dos
três no corpo da requisição deixaria quem tem um desafio válido entrar em **outra** conta.

⚠️ **A senha não trafega de novo.** A segunda etapa manda só o desafio e o código.

⚠️ **O reenvio responde 204 até para desafio inexistente** — dizer "não existe" transformaria o
endpoint num verificador de identificadores alheios.

## O que sustenta a segurança aqui não é o tamanho do código

Quatro dígitos são **10.000 combinações**: um script acerta em minutos se puder tentar à vontade.
Quem segura são os três limites juntos, e nenhum deles é dispensável:

| Limite | Valor |
|---|---|
| Tentativas erradas por desafio | **5** |
| Validade | **10 minutos** |
| Reenvios por desafio | **3** |

⚠️ Aumentar o código para 6 dígitos **sem** esses limites seria pior que 4 dígitos com eles.

Outras propriedades:
- **Hash, nunca o código** (SHA-256) — um dump vazado não vira acesso, mesmo princípio do token de
  recuperação de senha (V042).
- **Consumo em um comando só** (`UPDATE … WHERE usado_em IS NULL AND expira_em > now() AND
  codigo_hash = ? RETURNING …`) — ler e depois marcar deixaria uma janela em que o mesmo código
  serviria duas vezes.
- **Reenviar gera código novo** e invalida o anterior. Reenviar o mesmo pareceria mais amigável e
  seria pior: dois e-mails com o mesmo número fazem a pessoa usar o que chegou primeiro, que pode
  já estar expirado.
- **Sem `DELETE` para a aplicação** (`REVOKE`): apagar desafio usado apagaria o rastro de quem
  entrou e quando.
- **O horário de acesso é conferido de novo** na segunda etapa — entre a senha e o código passam
  até 10 minutos, e a janela pode ter fechado no meio.
- **Usuário e conta são relidos** na hora de emitir o token, nunca reaproveitados da primeira
  etapa: no meio do caminho ele pode ter sido desativado ou promovido a administrador.

## ⚠️ O defeito que quase entrou — e que só um teste específico pegaria

A primeira versão marcava `concluirLoginComCodigo` e os métodos de `CodigoLoginService` como
`@Transactional`, por hábito. Isso **anula o teto de tentativas inteiro**: o `UPDATE` que soma a
tentativa errada roda, e logo depois a exceção que informa o erro ao usuário provoca o **rollback**
— o incremento é desfeito junto. O contador nunca sai de zero, e as 10.000 combinações voltam a
estar disponíveis.

**Medido**: com a anotação, cinco erros deixavam o banco em `tentativas = 0`. Sem ela, em 5.

Nada na tela mudaria: o erro aparece igual, a mensagem é a mesma, o código continua escrito
dizendo "máximo 5 tentativas". Por isso o teste `tentativasErradasSaoContadasEFechamAPorta`
**confere o número no banco**, não só o status HTTP — e depois exige que o código **certo** seja
recusado por excesso de tentativas.

⚠️ Isso obrigou a leitura sob RLS a sair para um bean próprio (`ContaDoUsuario`): ela **precisa** de
transação (o `SET LOCAL app.id_tenant` só existe dentro de uma) e a conferência **não pode** estar
em nenhuma. Como método anotado chamado de dentro do próprio bean não passa pelo proxy do Spring
— armadilha já conhecida no projeto —, a transação tem de começar em **outro** bean.

## Sem SMTP configurado, ninguém entra

O envio falhando vira **503 com mensagem clara**, e não silêncio. É deliberado: com a segunda etapa
ligada, a senha sozinha já não basta — sem o e-mail o usuário fica **trancado fora**. Deixar a tela
esperando um código que nunca chega seria o pior desfecho.

⚠️ Consequência operacional: **ligar a opção para um usuário depende do SMTP da plataforma estar
funcionando** (Backoffice → Configuração).

## Critérios de aceitação

- **Dado** um usuário com a opção ligada, **quando** ele acerta a senha, **então** não vem token,
  vem `exigeCodigo` + desafio + e-mail mascarado, e um código chega por e-mail.
- **Dado** o código certo, **então** o token é emitido.
- **Dado** o mesmo código reapresentado, **então** 401 — serve uma vez só.
- **Dado** 5 erros, **então** o banco registra 5 tentativas e o código **certo** passa a ser
  recusado com 429.
- **Dado** um reenvio, **então** o código anterior deixa de valer e o novo funciona.
- **Dado** um desafio inventado (ou nem UUID), **então** 401 — e o reenvio devolve 204 sem dizer
  se existe.
- **Dado** um usuário **sem** a opção, **então** o login continua de um passo só.

Testes: `api/src/test/java/com/vetor/niner/LoginEmDuasEtapasTest.java` (6 casos).

## Tela

`web/src/pages/Login.tsx` — o card do código reaproveita a moldura do login. Campo de 4 dígitos,
`autoComplete="one-time-code"` (o celular oferece o código do SMS/e-mail), só números, e **confere
sozinho ao digitar o quarto dígito** — pedir um clique depois disso é um passo que não decide nada.
"Reenviar código" e "Voltar" abaixo.

⚠️ **Voltar limpa a senha**: sair dali é recomeçar o login inteiro. Deixar a senha preenchida na
tela de trás faria a segunda etapa parecer opcional para quem só quer fechar o card.

O checkbox fica no cadastro do usuário, na seção **Segurança**.
