# Spec: Login com escolha de empresa               Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-28 · Módulo(s): `plataforma.onboarding`, `identidade` · Fase: 1 — Núcleo do ERP

## Problema

Com a tela de Usuários (`docs/telas/usuario.md`) um usuário pode ter acesso a mais de uma
empresa do tenant (`usuario_empresa`, N:N). Antes desta feature o login sempre resolvia a
empresa "ativa" pegando a primeira empresa do tenant (`ORDER BY id_empresa LIMIT 1`) — errado
assim que existe mais de uma empresa: tudo que o usuário cadastrasse (venda, funcionário etc.)
cairia sempre na mesma empresa, não importa em qual ele realmente estivesse trabalhando.

## Solução proposta

O login passa a **perguntar em qual empresa o usuário quer entrar**, quando ele tem acesso a
mais de uma. A empresa escolhida vira a **empresa ativa da sessão**, gravada num claim novo do
JWT (`eid`) — todo cadastro que o usuário fizer depois, com coluna `id_empresa`, usa esse
valor. Com uma única empresa, não há pergunta nenhuma: resolve direto, sem fricção.

## Fluxo

`POST /api/publico/login` em até duas voltas:

1. **Primeira chamada** — `{slug, email, senha}` (sem `idEmpresa`). Valida a senha, busca as
   empresas do usuário (`usuario_empresa`). Casos:
   - **Zero empresas**: `401` — "Usuário sem empresa vinculada. Contate o administrador."
     (não deveria acontecer — toda criação de usuário exige ao menos uma — mas é a resposta
     honesta se acontecer, em vez de deixar o login quebrar de outro jeito).
   - **Uma empresa**: resolve direto, emite o token com `eid` = essa empresa.
   - **Mais de uma**: responde `200` com `{"token": null, "escolherEmpresa": true, "empresas":
     [...]}` — sem token.
2. **Segunda chamada** (só quando a primeira pediu escolha) — mesmas credenciais +
   `idEmpresa` escolhido. Valida que a empresa está na lista de acesso do usuário (rejeita
   `401` se não estiver, mesmo que a empresa exista no tenant) e emite o token.

Um `idEmpresa` enviado é **sempre** validado contra a lista de acesso, mesmo quando o usuário
só tem uma empresa — nunca aceito "de graça" por coincidência.

## Tela de login (front)

`web/src/pages/Login.tsx`: formulário normal (loja/e-mail/senha) submete a primeira chamada.
Se a resposta pedir escolha, a tela troca para uma lista de botões (um por empresa) — clicar
reenvia a segunda chamada com o `idEmpresa`. Botão "Voltar" descarta a escolha e volta ao
formulário (sem perder loja/e-mail/senha, que ficam em memória). Sucesso navega pro Painel
como antes.

**Empresa ativa visível no header** (2026-07-28): `Layout.tsx` mostra o nome da empresa ativa
entre a marca e o botão Sair (`GET /api/v1/eu`, campo `empresa`), pra deixar claro em qual
empresa os cadastros desta sessão vão cair.

## Impacto no contrato de API

```
POST /api/publico/login   {slug, email, senha, idEmpresa?}
                           → 200 {token, idTenant, slug, escolherEmpresa, empresas}
                             (token null + escolherEmpresa=true quando falta escolher)
                           → 401 credenciais inválidas / empresa inválida / sem empresa vinculada

GET  /api/v1/eu            ganhou o campo "empresa": {idEmpresa, nome}
```

## Impacto no domínio (id_empresa deixa de ser "a primeira do tenant")

Serviços que gravam `id_empresa` num INSERT passam a usar o claim `eid` do JWT (via
`@AuthenticationPrincipal Jwt jwt`) em vez de `(SELECT id_empresa FROM empresa ... LIMIT 1)`:

- `PdvVendaService.efetivarVenda` (venda + movimentação de estoque).
- `FuncionarioService.criar` (cadastro de funcionário).

Esses dois eram os únicos na data desta spec (2026-07-28). **A auditoria envelheceu:** hoje mais
de vinte serviços resolvem o claim `eid` — entre eles `TransferenciaService`,
`EntradaMercadoriaService`, `BalancoEstoqueService`, `DevolucaoProdutoService`,
`RecebimentoCrediarioService`, `CaixaService`, `EtiquetaEmissaoService`, `FluxoCaixaService`,
`CrmService` e os relatórios (`RelatorioVendasService`, `RelatorioEstoqueService`,
`RelatorioComissoesService`, `RelatorioContasReceberService`,
`RelatorioMovimentacaoProdutosService`, `PesquisaVendaService`), uns gravando `id_empresa`,
outros usando o claim para escopar leitura do OPERADOR. Não trate a lista acima como inventário —
`grep -r '"eid"' api/src/main/java` é a fonte atual.

**A regra continua valendo integralmente:** todo serviço que precisar de `id_empresa` deve
extrair `eid` do JWT, e **nunca** reintroduzir a busca por "a primeira empresa do tenant"
(`SELECT id_empresa FROM empresa ... LIMIT 1`).

## Segurança relacionada (mesmo dia, revisada)

Só existe um ADMIN por tenant, para sempre — criado no signup, nunca transferível, nunca
removível (nem pelo próprio ADMIN, nem por mais ninguém). Ver `docs/telas/usuario.md`,
"Atualização 2026-07-28 (revisão posterior)".

## Critérios de aceitação (viram testes)

- Dado um usuário com uma única empresa, quando faz login, então recebe o token direto, sem
  pedido de escolha.
- Dado um usuário com duas ou mais empresas, quando faz login sem `idEmpresa`, então recebe
  `escolherEmpresa=true` e a lista de empresas, sem token.
- Dado esse mesmo usuário, quando reenvia o login com um `idEmpresa` da lista, então recebe o
  token.
- Dado um `idEmpresa` que não está na lista de acesso do usuário (mesmo existindo no tenant),
  quando envia no login, então a API rejeita com 401.
- Dado um registro criado (venda do PDV, funcionário) por um usuário logado numa empresa X,
  então o registro é gravado com `id_empresa = X`, não com a primeira empresa do tenant.
- Dado `administrador:true` enviado ao criar ou atualizar um usuário, então a API ignora — só
  existe um ADMIN por tenant, criado no signup, nunca alterável por esta tela.

Cobertos por `LoginEmpresaTest` (3 testes) e `UsuarioCrudTest`
(`tentativaDeMudarOProprioNivelEhIgnoradaContinuaAdmin`,
`criarUsuarioIgnoraTentativaDeMarcarComoAdministrador`,
`bancoRejeitaUmSegundoAdministradorNoMesmoTenant`). Suíte completa do projeto: 492/492 verdes (2026-08-14).

## Non-goals desta feature

- Troca de empresa **sem** logout/login de novo (ex.: um seletor no header pra virar de loja
  sem trocar de sessão) — fica pra quando o produto pedir.
- CRUD de empresa (criar/editar) — só leitura (`GET /api/v1/empresas`), como já registrado em
  `docs/telas/usuario.md`.
- Permissão fina por rotina (`usuario_rotina`) — adiada, mesmo non-goal de `usuario.md`.

## Questões abertas

Nenhuma bloqueante.
