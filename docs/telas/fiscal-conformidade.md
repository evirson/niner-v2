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
| CNPJ preenchido e válido | `empresa.cnpj` | Grupo `emit` do XML |
| Inscrição Estadual preenchida | `empresa.inscricao_estadual` | idem |
| Código de município IBGE | `empresa.codigo_municipio_ibge` | `cMun` — não há de onde derivar, `cidade` é texto livre |
| CNAE | `empresa.cnae` | Obrigatório quando há IM; recomendado sempre |
| Certificado ativo e não vencido | `fiscal_certificado` | Sem ele não há assinatura nem mTLS |
| CNPJ do certificado = CNPJ da empresa | idem | Emitir no CNPJ errado é o pior erro possível |

> ⚠️ **Implementado (2026-08-17): "`crt` entre os atendidos" saiu da lista.** Desde a DF37,
> `fiscal_config_empresa.crt` tem `CHECK (crt IN (1, 2, 4))` — o banco recusa gravar CRT 3, então
> esta verificação nunca teria pendência pra encontrar. Checagem redundante removida, não
> esquecida.

### Produtos (🔴 bloqueia)

Conta os produtos **ativos** que não podem ser vendidos com nota:

| Verificação | Coluna | Observação |
|---|---|---|
| Sem NCM | `produto.codigo_ncm` | Nullable de propósito (F12); a exigência é da aplicação |
| Sem perfil fiscal | `produto.id_perfil_fiscal` | Sem perfil o motor não tem regra (F11) |
| Perfil sem regra para o CRT desta empresa | `cfg_perfil_fiscal_regra` | Tem perfil, mas nenhuma regra casa — falha igual |

> ⚠️ **Implementado (2026-08-17): três verificações do rascunho saíram da lista, por divergirem
> do schema real.** `unidade_comercial` (`NOT NULL DEFAULT 'UN'`), `origem_mercadoria` — não
> `produto.origem`, o nome no rascunho estava errado (`NOT NULL DEFAULT 0`) — e
> `unidade_tributavel` (`NULL` é o estado **correto** de "igual à comercial", documentado na
> própria coluna, V017) nunca ficam ausentes: são colunas com *default*, não colunas opcionais.
> Não há como distinguir "o lojista escolheu UN/nacional de propósito" de "ficou no default sem
> revisar" — e os dois defaults são exatamente os valores mais comuns e corretos no varejo.
> Bloquear aqui seria alarme falso em quase todo produto do catálogo, o oposto do que a spec já
> pede para CEST ("não gerar alarme falso em 10.000 produtos"). Ver
> `ConformidadeFiscalService`, javadoc da classe.

⚠️ **CEST fica de fora do bloqueio.** É obrigatório quando o produto pertence a segmento sujeito a ST,
mesmo que a operação não seja ST (Convênio 142/2018) — mas o sistema **não tem como saber** quais NCMs
exigem CEST enquanto `cfg_cest` não estiver carregada. Entra como aviso quando a tabela existir; hoje
nem isso, para não gerar um alarme falso em 10.000 produtos.

### Formas de pagamento (🔴 bloqueia)

`detPag` é obrigatório na NFC-e. Conta as `tipo_carteira` ativas sem `codigo_tpag` — e as de categoria
`CARTAO_DEBITO`/`CARTAO_CREDITO` sem `codigo_bandeira`.

Bloqueia porque é a pendência **mais silenciosa** das quatro: são poucas linhas (6 no seed), ninguém
pensa nelas, e a nota é rejeitada na primeira venda no cartão.

> ⚠️ **Consequência real, verificada em 2026-08-17: hoje TODO tenant nasce bloqueado aqui.**
> `codigo_tpag`/`codigo_bandeira` não têm tela própria ainda (não fazem parte do B2/B3) — as 6
> carteiras que o signup semeia nascem sempre sem `codigo_tpag`. Até `financeiro.tipocarteira`
> ganhar esses campos, este painel nunca mostra "Pronto para emitir" pra ninguém, mesmo com
> Empresa/Produtos/Clientes impecáveis. Não é bug do painel — é o painel fazendo o trabalho de
> apontar exatamente essa lacuna. Registrado como pré-requisito, não corrigido aqui.

### Clientes (🟡 avisa, não bloqueia)

Esta categoria é deliberadamente mais fraca que as outras, e o motivo importa:

- **NFC-e sem identificação do consumidor omite o grupo `dest` inteiro** — a venda anônima no balcão,
  que é a maioria, não depende de nada do cadastro de cliente.
- **`codigo_municipio_ibge`** só é obrigatório no `enderDest` da **NF-e** (a nota de devolução, F5).
  Um cliente sem município não impede NFC-e nenhuma; impede a devolução dele depois dos 30 minutos.
- **`indicador_ie`** é o campo que decide se a NFC-e pode ser emitida (DF13).

Contar 7.000 clientes sem município IBGE como bloqueio faria a tela nascer vermelha num tenant que
consegue vender perfeitamente. Por isso: avisa.

> ⚠️ **Implementado (2026-08-17): a "regra para o nulo" já estava decidida no schema, mas só
> pela metade.** `cliente.indicador_ie` é `NOT NULL DEFAULT 9` (V016) — não existe
> `indicador_ie` nulo pra checar; a parte "PF tratado como 9, sem aviso" já é como o cadastro
> nasce. A parte "PJ com nulo é ambíguo, vira aviso" não tem como ser implementada como
> descrita, porque o schema não distingue "PJ que É não-contribuinte de verdade" de "PJ que
> nunca foi revisado" — os dois têm `indicador_ie = 9`. A checagem que sobrou, e que a Service
> implementa, é a aproximação possível: **PJ com `indicador_ie = 9` entra como aviso**, sabendo
> que vai soar falso positivo pra todo PJ revisado e confirmado como não-contribuinte (não há
> como evitar sem um terceiro estado no schema, ex. um flag "revisado"). Severidade "avisa"
> exatamente por causa disso — nunca bloqueia.

## Desempenho — a parte que decide se a tela é usável

O tenant real tem 10.000 produtos e 7.000 clientes. A tela **não** pode fazer uma consulta por
verificação.

- **O painel de contagens é UMA query** por categoria — um `count(*)` com `WHERE` compondo todas
  as verificações da categoria em `OR`, um só passe da tabela.
- **O drill-down é paginado** e só roda quando a categoria é aberta.
- Nenhuma contagem carrega linhas para o Java só para medir tamanho.

> ⚠️ **Implementado (2026-08-17): a contagem é por REGISTRO, não por verificação.** O rascunho
> original contava "3 produtos sem NCM + 2 sem unidade = 5 pendências" — soma por checagem. A
> implementação conta produtos DISTINTOS com pelo menos um problema, porque isso mantém o número
> do painel sempre igual ao número de linhas do drill-down daquela categoria (a mesma pergunta
> respondida duas vezes de dois jeitos é uma fonte clássica de "por que os dois números não
> batem?"). Um produto sem NCM e sem perfil ao mesmo tempo conta **1**, não 2 — e a `problema`
> de cada linha do drill-down lista todos os motivos daquele registro, separados por vírgula.

Se mesmo assim demorar, o padrão de progresso ao vivo já existe (`GaugeProgresso.tsx`) — mas **não**
nasce com ele: adicionar gauge a uma consulta que responde em 200ms é ruído.

## Critérios de aceitação (viram testes)

- Dado um tenant recém-assinado sem configuração fiscal, quando consulta a conformidade, então a
  categoria Empresa acusa "configuração fiscal inexistente" e o veredito é vermelho.
- Dado uma empresa totalmente configurada, com certificado válido, sem produtos, então o veredito é
  verde.
- Dado 2 produtos ativos sem NCM, quando consulta, então a categoria Produtos conta **2
  pendências**, uma por produto.
- Dado um produto **inativo** sem NCM, quando consulta, então ele **não** é contado.
- Dado um produto com perfil que não tem regra para o CRT da empresa, quando consulta, então ele
  aparece na verificação "perfil sem regra para o CRT".
- Dado uma `tipo_carteira` de crédito sem `codigo_bandeira`, quando consulta, então conta como
  pendência bloqueante.
- Dado um cliente PF (sempre `indicador_ie = 9` por default), quando consulta, então **não** é
  contado.
- Dado um cliente PJ (também `indicador_ie = 9` por default, nunca revisado), quando consulta,
  então é contado como **aviso**, e o veredito **continua verde** se não houver bloqueio.
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

`categoria` ∈ `empresa` | `produtos` | `pagamentos` | `clientes`, **minúscula na URL** — o
controller converte pra maiúscula antes de casar com o enum Java (`@PathVariable String` +
`valueOf` manual, não `@PathVariable CategoriaConformidade` cru: o binder padrão do Spring é
case-sensitive e rejeitaria "produtos" contra o enum `PRODUTOS`). Categoria inválida → 400. O
drill-down aceita `pagina`/`limite`; `ordenarPor`/`direcao` **não foram implementados** — cada
categoria tem uma ordenação fixa (nome/descrição) porque nenhuma delas tem coluna alternativa que
faça sentido ordenar.

Toda query filtra `id_tenant` explicitamente no SQL além do RLS (P8/F8). A categoria Empresa
resolve todas as pendências numa lista em memória (nunca mais que ~6 itens) em vez de paginar no
banco — não há ganho em paginar uma lista desse tamanho, e simplifica o código.

## Ajuda da tela (R22 / §3.7.1)

Entrada `fiscal.conformidade.tela` em `AjudaDaTela.tsx`: para que serve rodar esta tela **antes** de
ligar o fiscal, o que significa bloqueia × avisa, e por que cliente sem município IBGE não impede
vender (mas impede devolver depois dos 30 minutos).

## Impacto no banco

**Nenhuma migration nova**, confirmado. Todas as colunas verificadas já existem: fiscais de
`produto` (`codigo_ncm`, `id_perfil_fiscal`), `cliente.indicador_ie`/`codigo_municipio_ibge` e
`empresa.codigo_municipio_ibge`/`cnae` entraram nas migrations donas (V014/V016/V017) junto com o
schema fiscal; `tipo_carteira.codigo_tpag`/`codigo_bandeira` em V025.

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

- ✅ **`indicador_ie` nulo em cliente PF, FECHADA por construção do schema (V016).** Já é
  `NOT NULL DEFAULT 9` — não existe estado nulo pra decidir. Ver a nota na seção Clientes acima
  para o que isso muda na checagem de PJ.
- 🔴 **A Importação de Dados precisa aceitar as colunas fiscais** (NCM, perfil) — sem isso,
  "corrigir 10.000 produtos" vira digitação manual e esta tela só mostra um problema insolúvel.
  Está previsto na F1 (§12), mas é trabalho de outra tela (`docs/telas/importacao-dados.md`) e
  precisa entrar no mesmo bloco.
- 🔴 **`tipo_carteira` precisa ganhar `codigo_tpag`/`codigo_bandeira` na tela** — sem isso, TODO
  tenant fica preso em "N pendências bloqueiam" na categoria Formas de pagamento para sempre
  (ver nota na seção Formas de pagamento). É o bloqueador mais urgente pra esta tela ter alguma
  utilidade prática.

## Métrica de sucesso

Um lojista com 10.000 produtos importados consegue, numa tarde, sair de "N pendências bloqueiam" para
"Pronto para emitir" — e a primeira NFC-e do dia seguinte é autorizada de primeira.
