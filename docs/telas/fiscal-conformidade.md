# Spec: Conformidade Fiscal                                          Status: Rascunho
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-17 · Módulo(s): `fiscal.conformidade` · Fase: F1 — Fundação (bloco B3)

## Problema

Ligar o fiscal num tenant com 10.000 produtos importados de um sistema antigo é o cenário **real** de
onboarding. Sem uma tela que liste o que falta, o lojista descobre produto a produto, no caixa, com
cliente na frente — que é exatamente o que o **F11** ("bloqueio preventivo, nunca rejeição no caixa")
existe para impedir.

Esta tela é a diferença entre ligar o fiscal numa segunda-feira de manhã e descobrir os problemas ao
longo da semana, no balcão.

## Solução proposta

Uma tela de **diagnóstico somente-leitura**, por empresa: conta as pendências que impedem emitir,
agrupa por categoria, e leva com um clique para a tela que corrige cada uma.

**Acesso: somente ADMIN.** É a tela que se olha antes de ligar os gates de emissão, e o gate é
ADMIN-only. Item de menu com `adminOnly`.

**Nada é corrigido aqui.** A tela não edita produto, cliente nem empresa — ela aponta e navega. Uma
tela que corrigisse em massa seria um caminho excelente para estragar 10.000 produtos de uma vez.

## Particularidade estrutural: não é tela de lista, é painel

Foge de dois pontos do padrão consolidado, de propósito:

1. **Sem popup obrigatório de filtros.** O padrão (Entrada de Produtos, Contas a Pagar, CRM) abre com
   um popup porque há muitos filtros e a consulta é cara. Aqui há **um** parâmetro — a empresa — e ele
   já tem default (a empresa ativa da sessão, claim `eid`). Um popup para um `<select>` seria atrito
   sem ganho. O seletor de empresa fica no topo, como em `fiscal.configuracao`.
2. **Sem paginação no nível superior.** O topo é um painel de contagens por categoria (poucas
   linhas); a paginação existe só **dentro** do drill-down de cada categoria, onde as 10.000 linhas
   de fato estão.

Mantém do padrão: ✕ de fechar via `navigate(-1)`, `AjudaDaTela`, `Toast` para erro, cabeçalhos
ordenáveis e paginação por página dentro do drill-down.

## Estrutura da tela

### Topo — semáforo

Uma linha por categoria, com contagem e severidade:

| Categoria | Bloqueia? | Corrige em |
|---|---|---|
| **Empresa** | 🔴 Bloqueia | `identidade.empresa` / `fiscal.configuracao` / `fiscal.certificado` |
| **Produtos** | 🔴 Bloqueia | `catalogo.produto` |
| **Formas de pagamento** | 🔴 Bloqueia | `financeiro.tipocarteira` |
| **Clientes** | 🟡 Avisa | `cadastros.cliente` |

Acima de tudo, um veredito de uma linha: **"Pronto para emitir"** (verde) ou **"N pendências
bloqueiam a emissão"** (vermelho).

### Drill-down

Clicar numa categoria abre a lista das linhas com problema — grade paginada, com o identificador do
registro, o que falta, e o ícone verde que abre o cadastro dono numa aba/rota nova. Mesmo padrão
visual de `LancamentosCarteiraModal.tsx`.

## As verificações, uma a uma

### Empresa (🔴 bloqueia)

| Verificação | Origem | Por que bloqueia |
|---|---|---|
| Configuração fiscal existe | `fiscal_config_empresa` | Sem CRT não há como calcular nada |
| `crt` entre os atendidos (1, 2, 4) | idem | CRT 3 é Lucro Real/Presumido, fora do escopo do produto (DF37) |
| CNPJ preenchido e válido | `empresa.cnpj` | Grupo `emit` do XML |
| Inscrição Estadual preenchida | `empresa.inscricao_estadual` | idem |
| Código de município IBGE | `empresa.codigo_municipio_ibge` | `cMun` — não há de onde derivar, `cidade` é texto livre |
| CNAE | `empresa.cnae` | Obrigatório quando há IM; recomendado sempre |
| Certificado ativo e não vencido | `fiscal_certificado` | Sem ele não há assinatura nem mTLS |
| CNPJ do certificado = CNPJ da empresa | idem | Emitir no CNPJ errado é o pior erro possível |

### Produtos (🔴 bloqueia)

Conta os produtos **ativos** que não podem ser vendidos com nota:

| Verificação | Coluna | Observação |
|---|---|---|
| Sem NCM | `produto.codigo_ncm` | Nullable de propósito (F12); a exigência é da aplicação |
| Sem unidade comercial | `produto.unidade_comercial` | `uCom` é obrigatório em **todo** item do XML — era o achado bloqueante que o schema resolveu (§6.2) |
| Sem unidade tributável | `produto.unidade_tributavel` | `uTrib`; no varejo quase sempre igual à comercial |
| Sem origem | `produto.origem` | `orig` (0–8), obrigatório no grupo ICMS de todo item |
| Sem perfil fiscal | `produto.id_perfil_fiscal` | Sem perfil o motor não tem regra (F11) |
| Perfil sem regra para o CRT desta empresa | `cfg_perfil_fiscal_regra` | Tem perfil, mas nenhuma regra casa — falha igual |

⚠️ **CEST fica de fora do bloqueio.** É obrigatório quando o produto pertence a segmento sujeito a ST,
mesmo que a operação não seja ST (Convênio 142/2018) — mas o sistema **não tem como saber** quais NCMs
exigem CEST enquanto `cfg_cest` não estiver carregada. Entra como aviso quando a tabela existir; hoje
nem isso, para não gerar um alarme falso em 10.000 produtos.

### Formas de pagamento (🔴 bloqueia)

`detPag` é obrigatório na NFC-e. Conta as `tipo_carteira` ativas sem `codigo_tpag` — e as de categoria
`CARTAO_DEBITO`/`CARTAO_CREDITO` sem `codigo_bandeira`.

Bloqueia porque é a pendência **mais silenciosa** das quatro: são poucas linhas (6 no seed), ninguém
pensa nelas, e a nota é rejeitada na primeira venda no cartão.

### Clientes (🟡 avisa, não bloqueia)

Esta categoria é deliberadamente mais fraca que as outras, e o motivo importa:

- **NFC-e sem identificação do consumidor omite o grupo `dest` inteiro** — a venda anônima no balcão,
  que é a maioria, não depende de nada do cadastro de cliente.
- **`codigo_municipio_ibge`** só é obrigatório no `enderDest` da **NF-e** (a nota de devolução, F5).
  Um cliente sem município não impede NFC-e nenhuma; impede a devolução dele depois dos 30 minutos.
- **`indicador_ie`** é o campo que decide se a NFC-e pode ser emitida (DF13). Regra proposta para o
  nulo: cliente **pessoa física** com `indicador_ie` nulo é tratado como **9 — não contribuinte** (o
  caso do varejo, sem aviso). Cliente **pessoa jurídica** com nulo entra como aviso, porque aí a
  ambiguidade é real — pode ser um revendedor, e para revendedor o v1 **recusa** a emissão (§9.6).

Contar 7.000 clientes sem município IBGE como bloqueio faria a tela nascer vermelha num tenant que
consegue vender perfeitamente. Por isso: avisa.

## Desempenho — a parte que decide se a tela é usável

O tenant real tem 10.000 produtos e 7.000 clientes. A tela **não** pode fazer uma consulta por
verificação.

- **O painel de contagens é UMA query** por categoria, com agregação condicional
  (`count(*) FILTER (WHERE codigo_ncm IS NULL)`, etc.) — seis contagens de produto num só passe da
  tabela, não seis passes.
- **O drill-down é paginado** e só roda quando a categoria é aberta.
- Nenhuma contagem carrega linhas para o Java só para medir tamanho.

Se mesmo assim demorar, o padrão de progresso ao vivo já existe (`GaugeProgresso.tsx`) — mas **não**
nasce com ele: adicionar gauge a uma consulta que responde em 200ms é ruído.

## Critérios de aceitação (viram testes)

- Dado um tenant recém-assinado sem configuração fiscal, quando consulta a conformidade, então a
  categoria Empresa acusa "configuração fiscal inexistente" e o veredito é vermelho.
- Dado uma empresa totalmente configurada, com certificado válido, sem produtos, então o veredito é
  verde.
- Dado 3 produtos ativos sem NCM e 2 sem unidade comercial, quando consulta, então a categoria
  Produtos conta **5 pendências** distribuídas nas duas verificações.
- Dado um produto **inativo** sem NCM, quando consulta, então ele **não** é contado.
- Dado um produto com perfil que não tem regra para o CRT da empresa, quando consulta, então ele
  aparece na verificação "perfil sem regra para o CRT".
- Dado uma `tipo_carteira` de crédito sem `codigo_bandeira`, quando consulta, então conta como
  pendência bloqueante.
- Dado um cliente PF sem `indicador_ie`, quando consulta, então **não** é contado.
- Dado um cliente PJ sem `indicador_ie`, quando consulta, então é contado como **aviso**, e o
  veredito **continua verde** se não houver bloqueio.
- Dado um certificado vencido, quando consulta, então a categoria Empresa acusa e bloqueia.
- Dado um OPERADOR, quando tenta consultar, então 403.
- Dado duas empresas do mesmo tenant com configurações diferentes, quando consulta cada uma, então os
  vereditos são independentes.
- Dado dois tenants distintos, quando um tem pendências, então o outro não as enxerga (isolamento —
  `id_tenant` explícito, P8/F8).

Cobertos por `ConformidadeFiscalCrudTest` (novo).

## Impacto no contrato de API

```
GET /api/v1/fiscal/conformidade/{idEmpresa}                    painel de contagens (ADMIN)
GET /api/v1/fiscal/conformidade/{idEmpresa}/{categoria}         drill-down paginado (ADMIN)
```

`categoria` ∈ `empresa` | `produtos` | `pagamentos` | `clientes`. O drill-down aceita
`pagina`/`limite`/`ordenarPor`/`direcao`, mesmo contrato do resto do sistema.

Toda query filtra `id_tenant` explicitamente no SQL além do RLS (P8/F8) — inclusive os `JOIN`s
produto↔perfil e produto↔`cfg_perfil_fiscal_regra`, com `t1.id_tenant = t2.id_tenant` na cláusula
`ON` ([[feedback_isolamento_tenant_explicito]]).

Colunas numéricas nullable lidas com `rs.getLong()` + `rs.wasNull()`, nunca
`rs.getObject(col, Long.class)` — o driver do Postgres não converte `integer` para `Long` de forma
confiável nesse projeto, e o sintoma é um 409 enganoso vindo do handler de
`DataIntegrityViolationException` (achado em `CrmService`, 2026-08-05).

## Ajuda da tela (R22 / §3.7.1)

Entrada `fiscal.conformidade.tela` em `AjudaDaTela.tsx`: para que serve rodar esta tela **antes** de
ligar o fiscal, o que significa bloqueia × avisa, e por que cliente sem município IBGE não impede
vender (mas impede devolver depois dos 30 minutos).

## Impacto no banco

**Nenhuma migration nova.** Todas as colunas verificadas já existem: fiscais de `produto`,
`cliente.indicador_ie`/`codigo_municipio_ibge` e `empresa.codigo_municipio_ibge`/`cnae` entraram nas
migrations donas (V014/V016/V017) junto com o schema fiscal; `tipo_carteira.codigo_tpag`/
`codigo_bandeira` em V025.

Se a tela ficar lenta com 10.000 produtos, o passo seguinte é um índice parcial
(`WHERE codigo_ncm IS NULL AND ativo`), **não** uma tabela-resumo. Medir antes.

## Non-goals desta feature

- **Corrigir em massa** — a tela aponta e navega; não edita. Correção em lote de 10.000 produtos é a
  Rotina de Importação de Dados, que já existe e já sabe fazer isso com dry-run.
- **Verificar tabelas nacionais** (NCM válido na Receita, CEST × NCM) — depende de `cfg_cest`, que
  ainda não foi carregada.
- **Rodar sozinha / agendada** — sem job, sem e-mail. O ADMIN abre quando quer.
- **Bloquear a emissão** — quem bloqueia é o gate de `fiscal.configuracao` e o próprio PDV. Esta tela
  só informa.

## Questões abertas

- 🔴 **`indicador_ie` nulo em cliente PF: assumir 9 (não contribuinte) ou exigir?** Recomendação
  acima é assumir, porque é o caso do varejo e exigir travaria a venda anônima. Decisão do dono do
  produto.
- 🔴 **A Importação de Dados precisa aceitar as colunas fiscais** (NCM, unidade, origem, perfil) — sem
  isso, "corrigir 10.000 produtos" vira digitação manual e esta tela só mostra um problema
  insolúvel. Está previsto na F1 (§12), mas é trabalho de outra tela
  (`docs/telas/importacao-dados.md`) e precisa entrar no mesmo bloco.

## Métrica de sucesso

Um lojista com 10.000 produtos importados consegue, numa tarde, sair de "N pendências bloqueiam" para
"Pronto para emitir" — e a primeira NFC-e do dia seguinte é autorizada de primeira.
