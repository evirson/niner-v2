# Spec: Cadastro de Usuários                       Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-07-28 · Módulo(s): `identidade` (usuario, empresa) · Fase: 1 — Núcleo do ERP

## Problema

O tenant precisa gerenciar quem acessa o sistema (nome, e-mail, senha, papel ADMIN/OPERADOR) e
em quais empresas cada usuário pode operar. Hoje as tabelas `usuario`/`usuario_rotina` existem
no banco (V015) e são usadas pelo signup/login (`SignupService`), mas não existe nenhuma tela
de CRUD — o único jeito de criar um segundo usuário é via SQL direto.

## Solução proposta

Nova tela de domínio, construída **replicando o padrão consolidado no cadastro de Funcionário**
(`docs/telas/funcionario.md`) — mesma arquitetura de listagem/formulário, paginação, ordenação,
ícones de ação — mas com duas diferenças deliberadas:

1. **Restrita a `ADMIN`** (mesmo mecanismo de `configuracao.geral` — `exigirAdmin(jwt)` no
   serviço, `RequireAdmin` no front, item de menu só visível pra ADMIN): gerenciar quem acessa
   o sistema, e com qual papel, é sensível o bastante pra não seguir a regra geral de "OPERADOR
   também tem acesso" do resto de `cadastros`.
2. **Seletor de empresas com acesso** (`usuario_empresa`, tabela N:N nova, V015) — ao contrário
   de funcionário/produto, que só têm uma empresa por tenant no v1 e por isso nem mostram
   seletor, aqui o dono do produto pediu explicitamente a seleção múltipla: **um usuário pode
   acessar uma ou várias empresas** (não é 1:1). O ambiente de dev já tem 10 empresas
   cadastradas para o tenant de teste, então o seletor é funcional desde já, mesmo o v1 do
   signup só criando uma empresa por tenant.

## User stories

- Como **ADMIN**, quero cadastrar um usuário novo com nome, e-mail, senha e papel
  (Administrador/Operador), para dar acesso ao sistema a mais alguém da equipe.
- Como **ADMIN**, quero escolher em quais empresas cada usuário pode operar, para restringir
  o acesso de operadores de uma filial aos dados dela.
- Como **ADMIN**, quero inativar um usuário que saiu da empresa em vez de precisar excluir,
  principalmente se ele já tiver caixa(s) associado(s).
- Como **ADMIN**, quero que o sistema me impeça de excluir minha própria conta, para não me
  trancar para fora do sistema sem querer.

## Campos do formulário

Tabela `usuario` (V015) + `usuario_empresa` (N:N, nova nesta feature).

**Foco automático:** ao abrir a tela, o foco vai para o campo **Nome**.

| Campo (banco) | Rótulo na tela | Componente | Obrigatório | Regra |
|---|---|---|---|---|
| `ativo` | Usuário ativo | checkbox | default `true` | Primeiro campo do formulário |
| `nome_usuario` | Nome | texto | **Sim** (NOT NULL no banco) | Normalizado para MAIÚSCULAS |
| `email` | E-mail | texto (`type="email"`) | **Sim** | Formato validado no front e no back (`@Email`); único por tenant (case-insensitive, `usuario_email_uk`) — conflito responde 409 com mensagem amigável |
| `senha_hash` | Senha | senha (`type="password"`) | **Sim ao criar**; opcional ao editar | Mínimo 8 caracteres; nunca volta preenchida do servidor; em branco na edição = mantém a senha atual; sempre gravada como hash (BCrypt via `PasswordEncoder`, nunca texto) |
| `usuario_empresa` (N:N) | Empresas com acesso | lista de checkboxes | **Sim, ao menos uma** | Lista todas as empresas do tenant (`GET /api/v1/empresas`); substituída por completo a cada salvamento (apaga tudo e reinsere, mesma técnica de `produto_categoria`) |
| `controla_horario_acesso` | Controla horário de acesso | checkbox | default `false` | 2026-08-14, ver seção "Horário de acesso por dia da semana" abaixo. Escondido para o próprio ADMIN — a regra nunca se aplica a ele |
| `usuario_horario_acesso` (N:1, 7 linhas) | Tabela Segunda…Domingo × Início/Fim | grade de 7 linhas com 2 campos de hora cada | Condicional: só aparece/é pedida quando `controlaHorarioAcesso` está marcado | Só renderiza quando o checkbox acima está ligado; dia com os dois campos em branco = sem acesso naquele dia |

`id_usuario`, `criado_em` e `atualizado_em` aparecem como **campos informativos somente
leitura** no fim do formulário (seção "Informações do registro", convenção do projeto). A
seção some ao incluir um registro novo.

`usuario.id_empresa` (empresa de "lotação", legado) **não aparece no formulário** — é mantido
em sincronia automaticamente com a primeira empresa selecionada em "Empresas com acesso", só
para a coluna não ficar órfã; não tem função de produto nesta tela.

**`administrador` também não aparece como campo editável** (revisado 2026-07-28 — ver
"Atualização" no fim deste documento): **só existe um ADMIN por tenant, para sempre**, criado
no signup e nunca mais alterado. Esta tela só CRIA/EDITA operadores. Ao visualizar/editar o
próprio admin, o formulário mostra um badge somente-leitura "Administrador" com uma explicação
— nunca um checkbox.

**Sem `cfg_tela_campo` nesta tela** (diferente de cliente/funcionário/fornecedor/produto) —
decisão de produto: como a tela inteira já é ADMIN-only, não faz sentido também tornar campos
obrigatórios configuráveis por tenant.

## Tela de listagem

- **Colunas:** Nome, E-mail, Papel (Administrador/Operador), Status (Ativo/Inativo), Empresas
  (nomes separados por vírgula).
- **Ordenação por coluna:** Nome, E-mail, Papel e Status são clicáveis (ASC/DESC); Empresas não
  ordena (lista, não escalar). Default `nome` ASC, empate por `id_usuario`. Backend:
  `GET /api/v1/usuarios?ordenarPor=&direcao=`, allowlist de colunas.
- **Busca:** por nome OU e-mail (contém).
- **Filtros:** por status (Ativo/Inativo/Todos — default só Ativos).
- **Paginação:** por número de página, tamanho fixo em 50 itens — idêntico ao Funcionário.
- **Ações por linha:** três ícones — verde (olho) visualizar somente-leitura, azul (lápis)
  editar, vermelho (lixeira) excluir. **O ícone de excluir fica desabilitado na própria linha
  do usuário logado** (mesma regra reforçada no backend).
- **Botão de fechar (✕)** no cabeçalho da listagem e do formulário (`BotaoFecharTela`,
  `navigate(-1)` — histórico real, nunca rota fixa; convenção de todo o sistema).

## Exclusão de usuário

- Nunca é possível excluir a própria conta (`id` do path == `sub` do JWT) — `400` com mensagem
  clara, checado **antes** de qualquer outra coisa em `UsuarioService.excluir`.
- Se o usuário tiver caixa(s) associado(s) (`caixa_mestre.id_usuario`, V025 — módulo de caixa
  ainda sem tela própria, mas a FK já existe), inativa em vez de excluir:
  `UPDATE usuario SET ativo = false`, respondendo `200 OK` com
  `{"acao": "inativado", "motivo": "Usuário possui caixa(s) associado(s)."}`.
- Sem vínculo: `DELETE` de verdade — `usuario_empresa` cai em cascata (`ON DELETE CASCADE`,
  diferente de `usuario_rotina`, que fica sem cascade por ainda não ser gravada por código
  nenhum).

## Horário de acesso por dia da semana (2026-08-14)

Pedido de segurança do dono do produto: restringir **quando** um `OPERADOR` pode fazer login e
continuar trabalhando, com uma janela de horário por dia da semana (ex.: segunda a sábado
09:00–19:00, domingo sem acesso). Nunca se aplica ao ADMIN (só existe um por tenant, para
sempre — ver acima). Um checkbox por usuário (`controla_horario_acesso`) liga/desliga a regra
inteira; desligado, nenhum dado de horário é pedido nem aplicado.

**Modelo de dados** — `usuario.controla_horario_acesso boolean` + nova tabela
`usuario_horario_acesso` (`db/migration/V033__usuario_horario_acesso.sql`): PK composta
`(id_tenant, id_usuario, dia_semana)`, `dia_semana smallint 1..7` em **ISO** (1=segunda,
7=domingo — bate com `EXTRACT(ISODOW FROM ...)` do Postgres e `DayOfWeek.getValue()` do Java,
sem tradução manual de índice), `hora_inicio`/`hora_fim time` nulos juntos = sem acesso naquele
dia. Quando o controle está ligado sempre existem as 7 linhas (idioma "apaga tudo e reinsere",
mesma técnica de `usuario_empresa` acima). RLS padrão do projeto (P8).

**Dois pontos de bloqueio, tolerâncias diferentes** — resolve a tensão "bloquear fora do
horário" × "nunca cortar uma venda no meio":
- **Login** (`SignupService.login`) — tolerância **zero**. Não existe "rotina em andamento"
  pra proteger numa sessão que ainda nem começou.
- **Toda chamada autenticada** em `/api/v1/**` (`HorarioAcessoFilter`, registrado logo depois
  do filtro de tenant) — **15 minutos** de tolerância depois do `hora_fim` nominal do dia, pra
  dar tempo de terminar/emitir/imprimir uma venda já iniciada antes do horário fechar.
- Único ponto de verdade da regra: `HorarioAcessoService.podeAcessarAgora(idUsuario,
  toleranciaMinutos)` — uma única query SQL usando `now()` do **servidor** Postgres (nunca o
  relógio do processo Java nem, muito menos, o do cliente — requisito explícito do produto). A
  tolerância soma no domínio de TIMESTAMP (`current_date + hora`), nunca em `time` puro:
  `time + interval` do Postgres embrulha na virada da meia-noite (ex.: 23:50 + 15min vira
  00:05, um limite superior *menor* que o inferior, quebrando a comparação sempre que o fim do
  expediente cai nos últimos 15 minutos do dia) — bug real encontrado na verificação manual,
  coberto por teste de regressão com timestamps fixos.
- Mensagem de erro compartilhada entre os dois pontos: `"Fora do horário de acesso
  permitido."` (`HorarioAcessoService.MENSAGEM_FORA_DA_JANELA`) — o frontend casa por essa
  string exata pra distinguir esse 403 de qualquer outro.

**Aviso visual de contagem regressiva** — `GET /api/v1/eu` expõe
`segundosRestantesTolerancia` (nulo na quase totalidade das chamadas; só vem preenchido nos 15
minutos finais de tolerância, via `HorarioAcessoService.segundosRestantesTolerancia`). O
frontend (`HorarioAcessoGuard.tsx`, montado por `RequireAuth.tsx` em toda rota autenticada, só
ativo pra `OPERADOR`) polla essa chamada a cada 30s e mostra um anel circular no canto superior
esquerdo da tela (`--danger`, mm:ss) contando 15:00 → 00:00 em tempo real (1 tick local por
segundo, resincronizado a cada poll com o valor de verdade do servidor). Ao chegar a zero —
localmente ou por um 403 do backend, o que vier primeiro — mostra um Toast final e entra no
mecanismo de **logoff gracioso**.

**Logoff gracioso ("rotina crítica em andamento")** — contexto React genérico,
`web/src/lib/rotinaCritica.tsx` (`RotinaCriticaProvider`, escopo das rotas autenticadas,
`{ emAndamento, setEmAndamento }`). `HorarioAcessoGuard` só chama `logout()` quando
`emAndamento === false`; se pegar o operador no meio de uma venda, espera. Único consumidor por
enquanto: `Pdv.tsx` — `setEmAndamento(true)` ao abrir a forma de pagamento (`f5EfetivaVenda`),
`setEmAndamento(false)` em dois pontos: cancelamento da forma de pagamento (senão trava `true`
pra sempre se o operador desistir da venda) e no fechamento do comprovante (venda efetivada +
impressa/dispensada). Recebimento de Crediário e Fechamento de Caixa não usam o mecanismo
ainda, mas podem plugar do mesmo jeito quando for a vez deles.

## Critérios de aceitação (viram testes)

- Dado um usuário com nome, e-mail, senha e uma empresa selecionada, quando salvo, então é
  aceito.
- Dado um usuário sem nenhuma empresa selecionada, quando salvo, então a API rejeita.
- Dado um e-mail já cadastrado no mesmo tenant, quando salvo, então a API responde 409 com
  mensagem amigável.
- Dado um e-mail inválido (sem `@`), quando salvo, então a API rejeita.
- Dado uma senha com menos de 8 caracteres, quando salva, então a API rejeita.
- Dado um usuário novo sem senha, quando salvo, então a API rejeita ("senha é obrigatória").
- Dado um usuário existente editado com o campo senha em branco, quando salvo, então a senha
  atual é mantida (login continua funcionando com a senha antiga).
- Dado um usuário `OPERADOR` tentando acessar qualquer endpoint de `/api/v1/usuarios`, então a
  API responde 403.
- Dado um ADMIN tentando excluir a própria conta, então a API rejeita.
- Dado um `administrador:true` enviado ao criar ou atualizar um usuário (2026-07-28), então a
  API ignora — o usuário criado/atualizado nunca vira ADMIN por esta tela.
- Dado uma segunda linha `administrador = true` inserida direto no banco para o mesmo tenant,
  então o índice único `usuario_um_admin_uk` rejeita.
- Dado um usuário com caixa associado, quando o ADMIN tenta excluir, então é inativado (não
  excluído).
- Dado um usuário sem nenhum vínculo, quando excluído, então o registro (e seus
  `usuario_empresa`) deixam de existir.
- Dado um usuário de outro tenant, quando buscado pelo id, então não aparece (RLS).
- Dado um OPERADOR com `controlaHorarioAcesso=true` e a janela de hoje aberta, quando faz
  login, então é aceito; fora da janela, então o login é rejeitado (403, sem tolerância).
- Dado um OPERADOR autenticado cujo `hora_fim` de hoje passou há menos de 15 minutos, quando
  chama qualquer endpoint de `/api/v1/**`, então a chamada é aceita; há mais de 15 minutos,
  então é rejeitada (403, mesma mensagem do login).
- Dado o horário final do dia caindo nos últimos 15 minutos antes da meia-noite, quando a
  tolerância é somada, então o limite continua correto (não embrulha para o dia anterior).
- Dado um ADMIN com `controla_horario_acesso` ligado (nunca setado pela tela, só possível
  manipulando o banco direto), quando acessa fora de qualquer janela, então nunca é bloqueado.
- Dado um usuário com `controlaHorarioAcesso=true`, quando salvo sem os 7 dias da semana (ou
  com algum dia faltando), então a API rejeita.
- Dado um dia com `horaInicio` preenchido e `horaFim` vazio (ou vice-versa), quando salvo,
  então a API rejeita.
- Dado um dia com `horaFim` igual ou anterior a `horaInicio`, quando salvo, então a API
  rejeita.

## Impacto no contrato de API

```
GET    /api/v1/usuarios?nome=&status=&pagina=&limite=&ordenarPor=&direcao=   lista paginada (ADMIN)
POST   /api/v1/usuarios                      cria usuário (ADMIN) — corpo ganha controlaHorarioAcesso
                                              (boolean) e horarios (7 linhas diaSemana/horaInicio/horaFim, 2026-08-14)
GET    /api/v1/usuarios/{id}                 detalhe (ADMIN) — resposta ganha os mesmos dois campos
PUT    /api/v1/usuarios/{id}                 atualiza usuário (ADMIN); sem campo administrador no
                                              corpo — não é possível criar/promover/rebaixar ADMIN por aqui (2026-07-28)
DELETE /api/v1/usuarios/{id}                 exclui; fallback para inativar se houver caixa; nunca a si mesmo (ADMIN)

GET    /api/v1/empresas                      lista de empresas do tenant, sem paginação (qualquer papel)
```

`GET /api/v1/empresas/permitidas` (2026-08-12, `EmpresaController`) também mora no módulo
`identidade` mas pertence à Entrada de Produtos por Compra — ver `docs/telas/entrada-mercadoria.md`.

`GET /api/v1/eu` (qualquer papel autenticado, `EuController`) ganhou o campo
`segundosRestantesTolerancia` (nulo na quase totalidade das chamadas — ver seção "Horário de
acesso" acima).

Todos sob `/api/v1/**` (JWT de tenant, RLS ativo — P8). Erros em Problem Details (RFC 9457).

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `identidade.usuario.lista`** e **`identidade.usuario.form`** — ver
  `web/src/components/AjudaDaTela.tsx`. `url_video`: `NULL` por ora.

## Impacto no banco

Nova tabela `usuario_empresa` (N:N, `id_tenant`/`id_usuario`/`id_empresa`, PK composta,
`ON DELETE CASCADE` no usuário) adicionada **dentro de `V015__identidade_usuario.sql`**
(banco ainda em construção — não gera `V028+`, ver `docs/PROGRESSO.md`). RLS habilitado em
`V024__rls_dominio.sql` (adicionada ao array de tabelas). Índice único parcial
`usuario_um_admin_uk ON usuario (id_tenant) WHERE administrador = true` (2026-07-28) garante
um único ADMIN por tenant no próprio banco, não só na aplicação. `usuario`/`usuario_rotina`
não mudaram de estrutura (além do índice).

`db/migration/V033__usuario_horario_acesso.sql` (2026-08-14): `usuario.controla_horario_acesso
boolean NOT NULL DEFAULT false` + tabela nova `usuario_horario_acesso` — ver seção "Horário de
acesso" acima para o desenho completo (colunas, constraints, RLS).

## Impacto nas integrações

Nenhum.

## Non-goals desta feature

- **Permissão fina por rotina/tela** (`usuario_rotina`, legado) — a tabela existe (V015) mas
  esta feature não grava nem lê nela. Fica para quando o produto definir a granularidade
  (RBAC simples ADMIN/OPERADOR × rotinas finas — spec §3.3.2 já marca isso como pendência 🔴).
  **Anotado explicitamente a pedido do dono do produto (2026-07-28): próxima etapa depois desta
  tela.**
- **CRUD de empresa** — a tela só lista (`GET /api/v1/empresas`) para alimentar o seletor;
  criar/editar/excluir empresa fica para outra feature.
- **Redefinição de senha por e-mail (esqueci minha senha)** — só troca de senha via edição do
  próprio ADMIN por enquanto.
- **Auditoria de quem alterou o quê** (P3) além do `atualizado_em` padrão — sem log de mudança
  de papel/empresas nesta versão.

**Atualização 2026-07-28 (mesmo dia):** "empresa ativa no momento" deixou de ser non-goal — ver
`docs/telas/login-empresa.md`. O login agora pergunta qual empresa o usuário quer acessar
quando ele tem mais de uma (`usuario_empresa`), e essa escolha vira a empresa usada em todo
cadastro com `id_empresa` (venda do PDV, funcionário) feito naquela sessão.

**Atualização 2026-07-28 (mesma data, revisão posterior — pedido direto):** a regra "só outro
administrador pode promover/rebaixar" (`UsuarioService.exigirNaoAlterarPropriaAdministracao`)
foi **removida do projeto**, substituída por uma regra mais simples e definitiva: **só existe
um ADMIN por tenant, para sempre.** Ele nasce no signup e o privilégio nunca pode ser
transferido, concedido de novo ou retirado — nem pelo próprio admin, nem por ninguém, nem por
esta tela (que agora nem aceita o campo `administrador` no corpo da requisição). Garantido em
duas camadas: aplicação (`UsuarioService.criar`/`atualizar` nunca tocam a coluna) e banco
(`usuario_um_admin_uk`, índice único parcial).

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Tempo de cadastro de um usuário novo (nome, e-mail, senha, uma empresa) em menos de 20
segundos.
