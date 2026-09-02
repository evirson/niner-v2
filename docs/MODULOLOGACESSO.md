# Log de acesso ao ERP — estudo e especificação

> **O que é.** Registro de **quem entrou no ERP**, para auditoria da **Vetor** — não do lojista.
> Estudo conduzido em 2026-09-01 com o dono do produto; as decisões dele estão marcadas 🔵.
>
> **Estado:** especificado e implementado em 2026-09-01 (V109).

---

## 1. O que o log responde — e o que ele NÃO responde

**Responde:** quem entrou, quando, de que IP, com que sistema operacional, navegador e tipo de
aparelho — e **quem tentou entrar e não conseguiu**.

**Não responde:**

| | Por quê |
|---|---|
| **Quando saiu** | 🔵 decisão dele. Não existe hora exata de saída sem inventá-la: o navegador não avisa quando a pessoa vai embora. Registrar inferência numa trilha de auditoria é pior que não registrar — um dia alguém lê aquele horário como fato |
| **Quanto tempo ficou logado** | consequência do item acima |
| **Marca/modelo do aparelho** | o navegador **deixou de informar**: o Chrome no Android manda `Android 10; K` (modelo substituído por um "K" fixo, *User-Agent Reduction*) e o iPhone manda só `iPhone`. Safari e Firefox não implementam Client Hints. Só viria por `Sec-CH-UA-Model`, que é Chromium, exige opt-in e é dado de alta entropia |
| **MAC address** | **impossível pelo navegador.** O MAC vive na camada 2 e morre no primeiro roteador — o que chegaria ao servidor seria o do último salto. E JavaScript não vê hardware de rede. Só com agente instalado na máquina, que é outro produto |
| **Geolocalização** | 🔵 decisão dele. Por IP daria país (~99%) e estado (~80%), mas cidade acerta entre 50% e 75% e **despenca em 4G/5G, VPN e provedor pequeno** — serviria de contexto, nunca de prova. Por GPS exigiria permissão explícita do usuário a cada acesso |
| **Quem viu qual dado** | é trilha de navegação, outro escopo: volume de milhares de linhas por usuário/dia |

⚠️ **Não há timer de inatividade** (🔵): deslogar a cada 30 min incomoda o operador de balcão, e com
o 2FA ligado cada logoff vira um código por e-mail. O token continua valendo 8 h.

---

## 2. Onde mora, e por que não é preferência

Schema **`plataforma`**, no mesmo banco (`niner_db`), **sem RLS de tenant**. Duas razões
independentes — cada uma bastaria:

**Produto (P9).** O log é do plano de controle: quem lê é a Vetor. Numa tabela de tenant, a RLS
*daria* acesso ao administrador do lojista, e teríamos de lutar contra o próprio isolamento para
escondê-la.

**Técnica.** No momento do login **ainda não existe `TenantContext`** — ele nasce do JWT, que só é
emitido no fim. Tabela com RLS não seria gravável ali. É a mesma razão que já pôs
`plataforma.codigo_login` e `plataforma.recuperacao_senha` no schema; este é o terceiro caso.

⚠️ **Mesmo banco, não um banco separado** (P6 — *uma instância Postgres*): o backup já cobre, a role
`niner_backup` já existe, e um segundo banco significa segundo backup e segunda coisa que um dia
para de receber sem ninguém notar. ⏭️ Se um dia for preciso uma trilha **à prova do próprio
administrador**, a peça certa já existe e não é um banco: a área imutável do MinIO
(`AreaPrivada.FISCAL_XML` recusa sobrescrever e apagar) — o banco serviria para consultar, o
arquivo para provar.

---

## 3. O que é gravado

```sql
plataforma.acesso_login
  id_acesso        bigint identity
  ocorrido_em      timestamptz NOT NULL DEFAULT now()
  id_tenant        smallint             -- NULL quando o e-mail não existe em conta nenhuma
  id_usuario       integer              -- NULL no mesmo caso
  email_informado  text        NOT NULL
  id_empresa       integer              -- a empresa em que entrou
  resultado        text        NOT NULL -- ver enum abaixo
  ip               inet
  ip_confiavel     boolean     NOT NULL
  user_agent       text                 -- BRUTO
  so               text                 -- derivados
  navegador        text
  dispositivo      text                 -- COMPUTADOR | CELULAR | TABLET | DESCONHECIDO
```

**Resultados:** `SUCESSO` · `CREDENCIAL_INVALIDA` · `FORA_DO_HORARIO` · `SEM_EMPRESA` ·
`EMPRESA_INVALIDA` · `CODIGO_2FA_INVALIDO`.

### As decisões de modelagem, e por quê

⭐ **`email_informado` é cópia, não FK.** Auditoria precisa do que valia no momento: o usuário pode
ser renomeado, ter o e-mail trocado ou **ser excluído** — e a linha não pode virar um id órfão. Mesmo
princípio do percentual de comissão congelado na venda (V088).

⭐ **User-Agent bruto E derivados.** O bruto é a **prova**; `so`/`navegador`/`dispositivo` são
**interpretação nossa**, feita por um parser que erra e muda com o tempo. Guardar só o derivado joga
fora a evidência; guardar só o bruto obriga a reprocessar tudo para filtrar por "celular".

⭐ **Falhas na MESMA tabela dos sucessos.** É a **sequência** que denuncia ataque — cinco
`CREDENCIAL_INVALIDA` seguidas às 3h só aparecem se as falhas estiverem lá. Em duas tabelas seria
preciso costurar por horário para ver a história.

⭐ **`ip_confiavel`.** Sem ela, daqui a um ano ninguém sabe se aquele IP é do cliente ou do nginx. O
dado precisa se explicar sozinho.

⚠️ **`CREDENCIAL_INVALIDA` não distingue** e-mail inexistente, senha errada e conta inativa — porque
o **login também não distingue**, de propósito (qualquer diferença vira oráculo para quem adivinha
e-mails). O log não pode ser mais específico que a autenticação: seria um oráculo pela porta dos
fundos, para quem tem acesso ao backoffice.

---

## 4. Como é gravado

### ⛔ Fora da transação do login — inegociável

O `SignupService.login` é `@Transactional`. O registro acontece **no controller, depois do
retorno** — nunca dentro.

Isto não é estilo: em 2026-08-18 a medição do funil **dentro** da transação do signup fez a API
responder **`201` com token válido e a conta inexistente**. No Postgres um comando que falha aborta
a transação inteira, e `try/catch` em Java **não desfaz isso** — o `COMMIT` seguinte vira `ROLLBACK`
e devolve sucesso. Ver `feedback_efeito_secundario_fora_da_transacao`.

### E nunca derruba o login

O `registrar` engole qualquer exceção e apenas loga. **Trilha de auditoria não pode impedir o
lojista de trabalhar** — a escolha é deliberada e está no javadoc.

### Onde o motivo vem de

Os quatro `throw` de recusa do login passaram a lançar `LoginRecusadoException`, que carrega o
`ResultadoAcesso`. ⚠️ **Classificar pela mensagem seria frágil** — é a família da constante literal
que já custou um `cStat 253` neste projeto: o dia em que alguém melhorar o texto, o log passa a
classificar errado, em silêncio.

### ⚠️ O IP: um defeito que o estudo encontrou

`OnboardingController.login` usava `http.getRemoteAddr()` — **atrás do nginx isso é o IP do proxy**.
O mesmo IP errado já ia para `codigo_login.ip_solicitante` e `recuperacao_senha.ip_solicitante`.

A função correta existia dentro do `LimiteRequisicaoFilter` e virou o utilitário `IpDoCliente`:
usa `X-Real-IP` (que o nginx **sobrescreve**, logo o cliente não forja), com o **último** salto do
`X-Forwarded-For` como reserva. ⛔ Nunca o primeiro elemento: o nginx **acrescenta** ao fim e
preserva o que o cliente mandou, então ler o primeiro deixa qualquer um forjar o IP — achado da
auditoria de segurança de 2026-08-27.

⚠️ Fora de produção `confiar-proxy` é `false` e o IP é o `getRemoteAddr()` mesmo — por isso
`ip_confiavel` existe.

---

## 5. Quem vê

**Só a Vetor, pelo backoffice** — tela `/acessos`, ao lado de Painel, Contas, Leads, Configuração e
CSRT. 🔵 **Qualquer papel de staff lê** (mesmo padrão de `TenantAdminController.exigirStaff`).

⚠️ Registrado como decisão consciente: a tela expõe IP e comportamento de **funcionários dos
clientes**, que não são clientes da Vetor. Recomendei restringir a `SUPER_ADMIN` e ele decidiu que
qualquer staff vê.

🔵 **O administrador do tenant NÃO vê.** Se um funcionário pedir os registros ao patrão, o patrão
pede à Vetor.

### O que garante isso

1. **Superfícies separadas** (ADR-007/R18): `/api/v1/**` só aceita `aud=tenant`, `/api/admin/**` só
   `aud=plataforma`, com **dois `JwtDecoder` distintos**. Token trocado de lugar é recusado na porta.
2. **Nenhum endpoint sob `/api/v1` lê esta tabela** — e isso virou **teste**.

⚠️ A tabela morar em `plataforma` é organização, **não tranca**: ela é global, e uma query da
aplicação a alcançaria. O que impede o lojista de ver é a superfície separada **mais** o fato de não
existir a porta. Por isso o teste: se um dia alguém escrever esse endpoint, ele reprova.

---

## 6. Retenção

🔵 **2 anos**, com **expurgo automático diário**, entregue **junto** com a tabela.

⚠️ Piso legal: 6 meses (Marco Civil da Internet, art. 15 — provedor de aplicação).

⛔ O expurgo não é zelo: `plataforma.codigo_login` foi criada **sem nenhum**, cresce para sempre e
hoje guarda hash e IP de contas já excluídas. Repetir isso numa tabela que ganha uma linha por login
de **todos** os tenants seria pior.

---

## 7. Critérios de aceitação

| # | Dado / Quando / Então |
|---|---|
| 1 | **Dado** um usuário válido, **quando** faz login, **então** grava uma linha `SUCESSO` com tenant, usuário, e-mail, empresa, IP e User-Agent |
| 2 | **Dado** uma senha errada, **quando** tenta entrar, **então** grava `CREDENCIAL_INVALIDA` com o e-mail informado e **sem** `id_usuario` |
| 3 | **Dado** um e-mail que não existe, **quando** tenta entrar, **então** grava `CREDENCIAL_INVALIDA` — igual ao caso anterior, sem distinguir |
| 4 | **Dado** o log indisponível (tabela inacessível), **quando** o usuário faz login, **então** ele **entra normalmente** |
| 5 | **Dado** um token de tenant, **quando** chama `/api/admin/acessos`, **então** recebe 401 |
| 6 | **Dado** um staff de qualquer papel, **quando** abre a tela, **então** lê os acessos, com filtros |
| 7 | **Dado** um registro mais velho que a retenção, **quando** o expurgo roda, **então** ele é apagado |

---

## 8. O que ficou declarado e não medido

- O parser de User-Agent é **conservador**: classifica o que reconhece e devolve `DESCONHECIDO` no
  resto, em vez de adivinhar. O bruto está guardado para reprocessar.
- **Não há tela para o lojista** — e, se um dia a LGPD exigir que o controlador (o lojista) atenda
  um funcionário dele, isso volta como decisão de produto.
