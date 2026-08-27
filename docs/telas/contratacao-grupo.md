# Spec: Contratação — mesmo grupo ou grupo separado       Status: Aprovada (parte 2 de 2)
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-27 · Módulo(s): `plataforma.onboarding`, `identidade.empresa`, `site/` · Fase: 1 — Núcleo do ERP

## Problema

O caso, nas palavras dele: *"o usuário master tem 3 empresas conosco, todas no mesmo tenant, e
são do ramo de calçados. Digamos que ele queira contratar mais uma empresa — ele pode colocar no
mesmo tenant, ou pode colocar em outro tenant. Pro usuário não se perder na hora da contratação,
temos que perguntar o ramo de atividade e verificar se o tenant que ele usa está neste ramo; se
não estiver, perguntar se ele quer um novo tenant ou se vai continuar no mesmo — e avisar dos
impactos dos dois lados."*

Até aqui o sistema não perguntava nada: cadastrar de novo com o mesmo e-mail era **recusado** com
um 409 seco (*"Já existe uma conta com este e-mail"*), e o assunto morria ali. Quem quisesse
separar os negócios não tinha caminho.

## Onde isso acontece

⛔ **Fora do ERP.** Decisão dele: *"dentro da aplicação do Nainer ERP não vai ser possível fazer a
contratação, isso vai ser na tela separada que já existe pra contratação"* — e, mais adiante:
*"dentro do ERP não pode ter a opção de incluir mais um CNPJ, tem que ser na tela de contratação,
pois a cobrança será feita por CNPJ"*.

Incluir CNPJ deixou de ser ato operacional e virou **ato comercial**. Hoje isso já é verdade sem
esforço: `POST /api/v1/empresas` existe desde 2026-08-18, mas **nenhuma tela do `web/` o alcança**
— e o javadoc de `EmpresaService.criar` registra que ele não pode ganhar tela lá.

## Fluxo

Tudo no `/assinar` do site.

1. O visitante preenche nome da loja, **ramo**, nome, e-mail e senha.
2. `POST /api/publico/assinar`:
   - e-mail **livre** → conta criada, entra no sistema (como sempre foi);
   - e-mail **já com conta** → `409` com `type: urn:niner:erro:conta-ja-existe`.
3. Nesse 409, a tela troca o formulário por **duas saídas**, cada uma com o que se ganha **e** o
   que se perde:

| | Acrescentar ao grupo que já tenho | Criar um grupo separado |
|---|---|---|
| **Ganha** | relatórios das empresas somados; uma assinatura; as vendas de todas somam o mesmo limite | cada negócio com cadastro limpo, nada se mistura |
| **Perde** | produtos, clientes, fornecedores e plano de contas ficam **juntos** — a lista de uma aparece na tela da outra | **a visão de grupo, para sempre**: não existirá relatório somando empresas dos dois grupos; e são duas assinaturas |
| **Pede senha?** | **sim** | **não** |

⚠️ **A assimetria da senha é a regra central.** Entrar no grupo existente mexe em dados que já são
de alguém — sem a senha, quem soubesse o e-mail acrescentaria um CNPJ na conta alheia. Já o grupo
separado é conta **nova**, que só por acaso usa o mesmo e-mail: nada da antiga é tocado, e a senha
digitada no formulário vira a senha da conta nova.

⚠️ **Consequência boa disso:** quem **esqueceu a senha não fica preso** — ainda consegue abrir um
grupo separado. E, para o outro caminho, a tela oferece o link de *esqueci minha senha* quando a
senha não confere.

4. **Aviso de ramos diferentes** — só aparece ao acrescentar ao grupo, e só quando os dois ramos
   são conhecidos: *"as empresas deste grupo são de Calçados, e esta é de Padaria e confeitaria.
   Juntando, elas vão dividir o mesmo cadastro…"*, com **Juntar assim mesmo** / **Deixa pra lá**.
   Sem ramo cadastrado não há o que comparar — inventar um alerta a partir de "não informado" só
   ensinaria o usuário a ignorar avisos.

⚠️ A confirmação é **na própria página**, não `window.confirm`: o diálogo nativo destoa do produto,
não aceita texto formatado e trava a página inteira enquanto aberto.

## O que o backend precisou

- **`ContaJaExisteException`** + `type` próprio no Problem Details. A tela distingue pelo **tipo**,
  nunca pela mensagem — comparar texto quebraria no dia em que alguém melhorasse a frase.
- **`AssinarRequest.criarGrupoSeparado`** — sem a bandeira, e-mail repetido continua recusado. A
  trava de 2026-08-19 existe porque repetir o cadastro por engano dividia os dados do mesmo
  lojista entre duas contas sem ele perceber; a bandeira é uma escolha consciente, não um "force".
- **`idRamo` na lista de empresas** (`GET /api/v1/empresas`) — é com ele que a tela monta o aviso.

## Critérios de aceitação

- **Dado** um e-mail que já tem conta, **quando** o cadastro é enviado sem bandeira, **então**
  responde `409` com `type: urn:niner:erro:conta-ja-existe` (e **não** revela nome nem quantidade
  de contas — nesse ponto ninguém provou ser dono do e-mail).
- **Dado** o mesmo e-mail e `criarGrupoSeparado: true`, **então** nasce **outro tenant**, mesmo com
  senha diferente da conta antiga; e cada senha passa a entrar na sua própria conta.
- **Dado** login válido, **quando** a empresa é criada pela API, **então** ela nasce **no mesmo
  tenant**, com o ramo escolhido, e o login continua entrando na mesma conta.
- **Dado** um e-mail repetido **com** `criarGrupoSeparado: false`, **então** continua recusado.

Testes: `api/src/test/java/com/vetor/niner/ContratacaoGrupoTest.java` (4 casos).

## Verificado ao vivo (backend) — e o que falta

Os três caminhos foram exercitados por chamada real contra a API de dev: 409 tipado; empresa nova
entrando no mesmo tenant (Calçados + Padaria no tenant 2); e grupo separado nascendo no tenant 3,
com cada senha entrando na sua conta.

⏭️ **A tela em si não foi conferida em navegador.** A animação de entrada do site (`.reveal`,
`IntersectionObserver`) não dispara em aba controlada por automação: o formulário fica invisível e
o clique não chega ao botão — artefato do ambiente de teste, não do código. Basta abrir
`/assinar` num navegador comum e repetir o caminho.

## Fora de escopo (depende de decisão comercial)

Pela regra 4 de 2026-08-27, na contratação o cliente escolhe **plano grátis ou pago** — mas as
faixas pagas **não existem** ainda (nem preço, nem quanto pesa cada CNPJ). Enquanto isso, a
contratação só oferece o gratuito. Ver `docs/PLANO-DE-NEGOCIO.md`.
