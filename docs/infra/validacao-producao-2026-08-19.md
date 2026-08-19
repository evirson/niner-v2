# Validação de produção — Nainer (2026-08-19)

Auditoria item a item do ambiente de produção logo depois do primeiro deploy, feita por um
subagente com acesso de **leitura** ao VPS (`curl`, `docker logs`, `docker exec psql -c SELECT`,
`ls`, `dig`). As escritas foram todas pela API pública/autenticada — que é justamente o que estava
sendo testado.

**Alvo móvel:** o nginx foi alterado às 22:18 UTC no meio da auditoria (correção do CORS
duplicado). Um defeito registrado às 22:07 já não existe mais na forma descrita; o resíduo que
sobrou virou o **D2** abaixo.

---

## Estado dos achados

| # | Defeito | Gravidade | Situação |
|---|---|---|---|
| D1 | Backup nunca rodou (`pg_dump` sem SELECT em sequência) | 🔴 | ✅ corrigido — `V044__backup_grants_sequencias.sql` + `db/bootstrap/00_roles.sh` |
| D2 | 429 da aplicação com `Access-Control-Allow-Origin` duplicado | 🔴 | ✅ corrigido — `map $upstream_http_access_control_allow_origin` |
| D3 | E-mail repetido cria segunda conta e rouba o lead da primeira | 🔴 | ✅ corrigido — `SignupService` (advisory lock + 409) |
| D4 | Bundle do ERP e API publicados anteriores ao commit HEAD | 🟠 | ✅ corrigido — API e web republicados juntos |
| D5 | SMTP em branco: quem esquece a senha fica trancado fora | 🟠 | ⏳ depende do dono do produto (preencher no backoffice) |
| D6 | `og:image` aponta para `og.png`, que dá 404 | 🟠 | ⏳ depende do arquivo 1200×630 |
| D7 | Corpo do 429 em ISO-8859-1 → "Muitas requisies" | 🟡 | ✅ corrigido — `LimiteRequisicaoFilter` |
| D8 | 429 do nginx em HTML, sem `Retry-After` | 🟡 | ✅ corrigido — `error_page 429 /429.json` |
| D9 | Sem HSTS nos 4 hosts | 🟡 | ✅ corrigido |
| D10 | Faixas 10 a 20 com o mesmo preço (R$ 990) | 🟡 | ℹ️ é o `preco_maximo` funcionando; reconferir antes de cobrar |

Ressalvas (passaram, mas incomodam): **R1** erro de validação em inglês e sem dizer o campo;
**R2** `porOrigem` do funil não fecha com os totais; **R3** contrato do beacon é *case-sensitive*
e silencioso; **R4** home com 772 KB, 94% imagem; **R5** canonical das subpáginas aponta para URL
que dá 301; **R6** filtro TRIAL morto no backoffice (✅ removido); **R7** `/entrar` fora do
sitemap mas indexável; **R8** `GET /` da API devolve 403 vazio; **R9** avisos do Hikari não
recorrentes.

---

## D1 — o backup nunca rodou

`plataforma.configuracao_plataforma.backup_ultimo_detalhe` guardava:

```
pg_dump falhou (código 1): pg_dump: error: failed to get data for sequence
"assinatura_id_assinatura_seq"; user may lack SELECT privilege on the sequence
```

A role tinha `BYPASSRLS` (certo) e SELECT nas tabelas, mas **SELECT em 0 de 59 sequências**:

```
 schema     | seqs | backup_pode_ler
 plataforma |   12 |               0
 public     |   47 |               0
```

Reproduzir:

```bash
ssh root@187.77.35.67 'docker exec niner-db psql -U postgres -d niner_db -x \
  -c "SELECT backup_habilitado, backup_ultimo_status, backup_ultimo_detalhe
      FROM plataforma.configuracao_plataforma;"'
```

**Por que importa:** não existia backup nenhum. Com cliente real dentro há XML fiscal com guarda
legal de 5 anos e dado pessoal sob LGPD — perder o disco é perder o negócio do lojista. Não ligar
cobrança antes disto.

**Correção:** `V044` concede `SELECT` em todas as sequências dos dois schemas e deixa
`ALTER DEFAULT PRIVILEGES` valendo para as próximas migrations; o bootstrap passa a fazer o mesmo
para banco novo. `backup_habilitado` continua `false` — é escolha do administrador, na tela de
Configuração do backoffice.

---

## D2 — 429 da aplicação com CORS duplicado

A primeira correção do dia condicionou o cabeçalho ao **status** (`map $status`, só 429/502/503).
Mas a aplicação **também** responde 429 (`LimiteRequisicaoFilter`, 10/min): o Spring manda o
cabeçalho e o nginx somava o dele em cima.

```
req 10 -> 204 | ACAO=1
req 11 -> 429 | ACAO=2   <- o navegador recusa
```

**Por que importa:** cabeçalho duplicado o navegador recusa exatamente como recusa cabeçalho
ausente. O visitante que tenta cadastrar rápido demais recebia um 429 impecável — Problem Details,
`Retry-After: 60` — que o navegador bloqueava, o `fetch` estourava como falha de rede e a landing
voltava a dizer "não foi possível conectar ao servidor", com a API no ar. O caminho de sucesso
estava certo; o de recusa é que mentia.

**Correção:** o critério passa a ser a origem da resposta, não o status.

```nginx
map $upstream_http_access_control_allow_origin $nainer_cors_falta {
    default "";            # a aplicação respondeu e já mandou o dela
    ""      $nainer_cors;  # resposta gerada pelo próprio nginx — o cabeçalho falta
}
```

Reproduzir (não cria dado — slug inexistente):

```bash
for i in $(seq 1 12); do
  curl -s -D- -o /dev/null -X POST -H 'Origin: https://nainer.com.br' \
    -H 'Content-Type: application/json' -d '{"slug":"nao-existe","email":"x@y.com"}' \
    https://api.nainer.com.br/api/publico/recuperar-senha | grep -ci '^access-control-allow-origin'
done
```

---

## D3 — e-mail repetido criava uma segunda loja

`plataforma.tenant` só tinha unique em `slug`:

```
 email_contato                   | contas | slugs
 validacao-1@teste.nainer.com.br |      2 | validacao-um, outra-loja
```

E `AquisicaoService.converter()` faz `ON CONFLICT (email) DO UPDATE SET id_tenant =
EXCLUDED.id_tenant` — o lead **muda de dono**: o da conta 12 passou a apontar para a 13, e a 12
sumiu do funil.

**Por que importa:** o lojista que clica duas vezes fica com duas lojas e os dados divididos, sem
saber. Quando a cobrança ligar, são duas assinaturas no mesmo e-mail. E a atribuição de marketing
perde a primeira conta em silêncio.

**Correção:** `SignupService` toma `pg_advisory_xact_lock(hashtext('signup:' || email))` e recusa
com **409** se já houver conta com aquele e-mail (comparação em `lower()`). O lock é o que fecha a
corrida do clique duplo — duas requisições simultâneas passariam as duas por um `SELECT` sozinho.

*Atenuante que valia registrar:* `recuperar-senha` pede `slug` + e-mail, então o reset nunca ficou
ambíguo; e `usuario_email_uk` já era único por tenant.

---

## D4 — publicado era anterior ao commit

| Artefato | Publicado (UTC) | Commit `803a8e3` |
|---|---|---|
| `web/assets/index-C0Dnixoq.js` | 22:03:49 | 22:08:44 |
| container `niner-api` | ~18:07 | 22:08:44 |

O bundle ainda trazia `trial_expira_em`, `badge-trial` e o passo de onboarding "Conectar um canal
de venda (Mercado Livre, Shopee…)" — promessa de módulo que não existe. A API ainda devolvia
`trial_expira_em` no `/api/v1/eu`, sem o objeto `plano`.

⚠️ **API e web têm que subir juntos:** só o bundle novo contra a API velha deixa `data.plano`
indefinido e a barra de cota some do mesmo jeito.

---

## Dados de teste criados em produção durante a auditoria

Ainda **não removidos** — a limpeza é uma exclusão em banco de produção e espera aval.

| id_tenant | slug | e-mail |
|---|---|---|
| 12 | `validacao-um` | `validacao-1@teste.nainer.com.br` |
| 13 | `outra-loja` | `validacao-1@teste.nainer.com.br` *(a prova do D3)* |
| 14–23 | `rl-1` … `rl-10` | `rl-1@teste.nainer.com.br` … `rl-10@teste.nainer.com.br` |

- Leads `id_lead` 12 e 14–23 (11 linhas, todas `CONVERTIDO`), e-mails `%@teste.nainer.com.br`
- Empresa `id_empresa 24` dentro do **tenant 1**: "FILIAL VALIDACAO DEPLOY LTDA", CNPJ
  `12345678000195`
- `plataforma.visita_site` id 5 (UTM `validacao`/`teste`/`deploy-check`);
  `plataforma.evento_marketing` 2 linhas
- Nada em `recuperacao_senha` (o teste usou slug inexistente de propósito)

Fora da auditoria, também são dados de teste: tenants 24–27 (`t2`, `teste-www`, `teste-apex-2`,
`teste`), leads 44–45, os ~41 leads `x*@t.com` e as 4 linhas de `visita_site` com UUID sequencial.
