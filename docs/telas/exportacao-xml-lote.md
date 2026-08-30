# Spec: Exportação de XML em Lote                      Status: Implementada

> Pedido do dono do produto em 2026-08-26, literal:
>
> *"Vamos Implementar a Rotina de Exportacao de XML em Lote — 1) Filtros: Empresa | inicio e Final
> de Emissao · 2) Fazer o download e compactar todos os arquivos num arquivo ZIP · 3) Nome do
> Arquivo ZIP = Empresa + mes + ano da emissao · 4) usuario escolhe o local onde vai salvar o
> arquivo zip · 5) Exportar NFC-e e NF-e"*
>
> É a tela `fiscal.download` prevista em `docs/MODULOFISCAL.md` §11.2 (a antiga **DF22**) e o
> último item que sobrara em "Implementações Futuras" no menu. Esta spec entrega a **versão
> síncrona** do §11.2 e registra, ponto a ponto, onde ela fica aquém do desenho de lá e por quê.

## Problema

O XML autorizado de cada nota já é arquivado sozinho no bucket privado desde 2026-08-18
(`ArquivamentoXmlService`, ADR-014) e já pode ser visto **um a um** em **Documentos Fiscais**
(ícone de olho → `GET /api/v1/fiscal/documentos/{id}/xml`).

O contador não quer um XML — quer **o mês inteiro**. Hoje o lojista teria de abrir a lista, clicar
nota por nota, copiar o XML da tela e colar num arquivo. Para uma loja que emite 300 NFC-e por mês
isso não é trabalhoso: é impraticável. E é uma obrigação recorrente — todo mês, para sempre, com
guarda legal de 5 anos (§11.1).

## Solução proposta

Uma tela nova em **Configurações → Fiscal**, ao lado de Documentos Fiscais: escolhe empresa e
período de emissão, o sistema diz **quantas notas vai levar** e o lojista baixa **um único `.zip`**
com todos os XMLs, organizado por mês, com um índice em CSV.

ADMIN-only, como todo o módulo fiscal (§12: `fiscal.download` | ADMIN).

## Decisões de escopo (as que custaram análise)

### 1. ⭐ O ZIP é montado no BACKEND, não no navegador

O caminho "navegador baixa N XMLs e zipa" foi descartado por três motivos, em ordem de peso:

- **O navegador não alcança o XML.** O XML fiscal mora no MinIO **privado** (ADR-014), e
  `ArmazenamentoPrivado` **não tem** `urlPublica()` nem URL assinada — de propósito: arquivo
  privado sai pela API, autenticado, e é a API que lê os bytes do bucket. Zipar no front seria **N
  requisições autenticadas** à API, cada uma fazendo exatamente o mesmo `GET` no bucket que o
  backend faria — o mesmo trabalho, N vezes, mais a latência de rede N vezes.
- **P4 — nenhuma lógica de negócio no front.** Decidir *quais* documentos entram (só os que têm XML
  arquivado), *onde* cada um cai na árvore de pastas e *qual* é o nome do arquivo é regra de
  negócio. No front, cada uma dessas regras viraria uma segunda implementação, que diverge no dia
  em que só uma for corrigida.
- **Compressão perto do dado.** XML comprime ~10:1. Zipar no servidor manda pela rede o pacote
  pronto; zipar no navegador manda os XMLs crus.

### 2. ⛔ O ZIP é montado em MEMÓRIA e devolvido como `byte[]` — nunca `StreamingResponseBody`

Parece o contrário do que se recomendaria para um download grande, e a razão é específica deste
projeto: **`TenantContext` é um `ScopedValue` (Java 25), ligado só durante o escopo da
requisição.** O corpo de um `StreamingResponseBody` é escrito **depois** que o controller retorna —
fora desse escopo. `ArmazenamentoPrivado` monta o prefixo `tenants/{id_tenant}/` a partir do
`TenantContext` e o **confere** na leitura (P8), então um streaming callback quebraria com
*"Nenhum tenant no contexto da execução atual"*, ou — pior, se um dia o contexto passar a ser
herdado por engano — leria com o tenant errado.

Consequência: o teto de documentos (decisão 6) **não é enfeite**, é o que mantém essa escolha
segura. A cerca de 10 KB por `nfeProc`, 2.000 documentos são ~20 MB crus e ~2 MB de ZIP.

### 3. ⭐ Só entra documento com XML **arquivado** — e o que ficou de fora é dito, nunca omitido

O filtro real do pacote é `xml_objeto_bucket IS NOT NULL`. Isso já exclui, por construção e sem
lista de situações no código:

| Situação | Entra? | Por quê |
|---|---|---|
| `AUTORIZADO` | ✅ | é a nota |
| `CANCELADO` | ✅ | a nota **existiu** e o cancelamento é um evento sobre ela (decisão 4). O XML continua arquivado — cancelar não apaga |
| `NAO_EMITIDO` | ❌ | parou no bloqueio preventivo (F11): não tem série, número, chave **nem XML** |
| `REJEITADO` / `DENEGADO` | ❌ | não há documento fiscal válido; o XML fica no banco, com valor de suporte, não de escrituração (§11.1) |
| `RASCUNHO` / `VALIDADO` / `ASSINADO` / `TRANSMITINDO` / `CONTINGENCIA` | ❌ | ainda não autorizado — nada a arquivar |

⚠️ **Existe um caso que engana:** nota `AUTORIZADO` cuja gravação no bucket falhou (MinIO fora do
ar no momento da emissão). Ela **tem** valor fiscal e **não** tem XML arquivado. Some do pacote sem
que nada avise — e é exatamente o tipo de silêncio que este projeto já pagou caro. Por isso:

- a **pré-conferência** (`/resumo`) devolve `documentosSemXml` e a tela mostra o aviso em amarelo
  antes de o lojista clicar em baixar;
- o `relatorio.csv` lista **todos** os documentos do período, com a coluna `ARQUIVO NO PACOTE`
  dizendo `(nao arquivado)` nos que ficaram de fora;
- a mensagem diz o que fazer: o `ArquivamentoXmlJob` tenta de novo a cada 10 minutos, então basta
  esperar e repetir a exportação.

#### ⚠️ "Sem XML" tem DOIS significados, com conselhos opostos

Revisado em 2026-08-26 conferindo um pacote real: das 40 notas "sem XML" do período, **36 eram
REJEITADAS, 3 NAO_EMITIDO e só 1 era pendência de arquivamento**. A mensagem única dizia "aguarde o
arquivamento e repita" — para uma nota rejeitada, isso é esperar para sempre por um arquivo que não
vem. Hoje são duas contagens e duas mensagens:

| Contagem | Situações | O que a tela diz |
|---|---|---|
| `documentosPendentesArquivamento` | AUTORIZADO / CANCELADO sem XML | aguarde o job (10 min) e repita |
| `documentosSemValorFiscal` | REJEITADO / DENEGADO / NAO_EMITIDO | não geram XML; não há o que esperar |

As duas saem da **mesma varredura** (`count(*) FILTER (WHERE …)`), sem query extra e sem risco de
discordarem. Preso por `rejeitadaNaoContaComoPendenciaDeArquivamento`.

### 4. Os **eventos de cancelamento** entram junto — não foram pedidos, e sem eles o pacote mente

Uma NFC-e cancelada, sozinha no ZIP, é um XML de nota **autorizada**: nada nele diz que foi
cancelada. Quem prova o cancelamento é o **evento 110111 autorizado**, que já está arquivado no
mesmo bucket (`documento_fiscal_evento.xml_objeto_bucket`). Entregar a nota sem o evento faria o
pacote afirmar uma venda que não existe.

Custo: um `JOIN` a mais e uma subpasta. É o `eventos/` que o §11.2 já previa. Se o dono do produto
não quiser, sai apagando a query e a pasta — nada mais depende disso.

### 5. `relatorio.csv` — o índice que o contador abre primeiro

Previsto no §11.2. `;` como separador e **BOM UTF-8** no início, senão o Excel em pt-BR abre o
arquivo com acento quebrado e tudo numa coluna só. Colunas: `MODELO`, `SERIE`, `NUMERO`, `CHAVE DE
ACESSO`, `EMISSAO`, `SITUACAO`, `VALOR TOTAL`, `DESTINATARIO`, `CPF/CNPJ`, `ARQUIVO NO PACOTE`.

Valor com **vírgula** decimal (`1234,56`), sem separador de milhar — é o que o Excel pt-BR lê como
número. Campo de texto tem `;` e `"` escapados.

### 6. ⭐ Acima de **2.000 documentos** o período é PARTICIONADO, não recusado

> **Revisado em 2026-08-26, no mesmo dia.** A primeira versão respondia **409** mandando reduzir o
> período. O dono do produto pediu o contrário: *"em vez de limitar a 2 mil notas, faça o seguinte:
> se for mais que 2 mil notas, então você mesmo particione o pedido, e vai fazendo de 2 mil em 2 mil
> e gerando os arquivos zip"*. Ele está certo — recusar empurrava para o lojista um trabalho que o
> sistema sabe fazer: quem tem 12.000 notas teria de descobrir sozinho em quantos pedaços cortar e
> repetir a tela seis vezes acertando as datas na mão, sem nenhuma garantia de não deixar buraco
> entre um período e o seguinte.

A pré-conferência devolve `totalPartes`, a tela chama o **mesmo endpoint** uma vez por parte
(`?parte=1`, `?parte=2`, …) e salva um ZIP por parte.

⚠️ **O teto por parte continua existindo, e pelo mesmo motivo da decisão 2:** o ZIP é montado em
memória porque o `TenantContext` é um `ScopedValue` que não sobrevive a um streaming. Particionar
**não afrouxa** esse limite — é ele que torna cada parte segura.

#### ⚠️ `ateIdDocumento`: por que a partição precisa de um teto congelado

Paginar com `LIMIT/OFFSET` puro tem um defeito que **não aparece em teste feito com dados parados**:
se uma nota nova for emitida **entre** a parte 1 e a parte 3 — o caso comum de exportar o mês
corrente durante o expediente —, ela entra no meio da ordenação por data, empurra as demais, e o
`OFFSET` da parte seguinte passa a **pular um documento que ninguém baixou**. Sem erro, sem aviso, e
só descoberto na fiscalização.

Por isso a pré-conferência congela o **maior `id_documento_fiscal` do período naquele instante** e a
tela repassa esse teto em todas as partes. Uma nota emitida depois simplesmente fica de fora do
pacote — e entra na próxima exportação, que é o comportamento correto.

O critério de aceitação correspondente **quebra o filtro de propósito** para provar que é ele o
responsável: `tetoDeIdCongelaOConjuntoEUmaNotaEmitidaDepoisNaoEntra` compara o mesmo pedido com e
sem `ateIdDocumento` (com teto traz 2 notas, sem teto traz 3). Verificado removendo o filtro do SQL
e vendo o teste reprovar.

#### Nome dos arquivos

O sufixo de parte só aparece quando há **mais de uma** — numerar `parte-1-de-1` um arquivo único
seria ruído no nome que o contador arquiva todo mês. Com duas ou mais, o nome carrega **a parte e o
total** (`LOJA_08-2026_parte-2-de-7.zip`), com zero à esquerda para o explorador de arquivos ordenar
certo. Só "parte-2" não responderia a pergunta que importa — *recebi todos?* —, e faltar um pedaço
de um pacote fiscal é o tipo de falha que só aparece meses depois.

#### ⛔ O navegador bloqueia downloads múltiplos automáticos

Medido no Chrome em 2026-08-26: a partir do 2º ou 3º arquivo seguido, o clique **simplesmente não
gera arquivo**. Na primeira vez o navegador pergunta *"Fazer o download de vários arquivos?"*. Por
isso a tela avisa **antes** de começar que virão N arquivos e que é preciso permitir — um "permitir"
negado por engano faria as partes seguintes sumirem sem erro nenhum — e o botão mostra o progresso
(`Baixando… 3 de 7`), para o lojista conferir quantos realmente chegaram.

⚠️ **Com mais de uma parte, o "Salvar como" (decisão 9) é desligado de propósito:**
`showSaveFilePicker()` exige *transient user activation*, que **expira** durante o download da parte
anterior — a partir da segunda parte o diálogo seria recusado e a exportação falharia no meio. Sete
diálogos seguidos também seriam piores que o download direto.

⏭️ Quando **uma parte só** virar limitação real (uma nota gigante, um lote fora do comum), o caminho
continua sendo o desenho assíncrono do §11.2 (DF22), com progresso ao vivo e entrega por URL
assinada.

### 7. Período máximo: **365 dias**, igual à lista de Documentos Fiscais

Mesma constante, mesma mensagem. Duas telas irmãs sobre a mesma tabela com limites diferentes só
confundiriam.

### 8. Nome do ZIP quando o período **cruza meses**

O pedido diz *"Empresa + mes + ano da emissao"*, e o filtro é um **intervalo**, que pode cruzar
mês, e até ano. A regra:

| Período | Nome |
|---|---|
| 01/08/2026 a 31/08/2026 (mesmo mês) | `LOJA_CENTRO_08-2026.zip` |
| 15/07/2026 a 20/09/2026 | `LOJA_CENTRO_07-2026_a_09-2026.zip` |

O mês/ano sai das **datas do filtro**, não das notas encontradas — o nome tem de ser previsível
antes mesmo de saber o que existe no período (é o que a pré-conferência mostra), e um pacote vazio
de agosto ainda é "agosto".

**Sanitização do nome da empresa** (é `nome_fantasia`, caindo para `razao_social` quando vazio):
acento removido (`NFD` + descarte das marcas), maiúsculas, tudo que não for `A-Z0-9` vira `_`,
`_` repetido colapsa, sobra cortada em 40 caracteres. `LOJA DA ESQUINA – MATRIZ (SP)` vira
`LOJA_DA_ESQUINA_MATRIZ_SP`. Sem isso, `/`, `:` ou `"` no nome fantasia produziriam um
`Content-Disposition` inválido ou um arquivo que o Windows recusa salvar.

### 9. ⚠️ Item 4 do pedido — *"usuário escolhe o local onde vai salvar"* — o que o navegador
### realmente entrega

**Nenhum navegador deixa uma página web escrever numa pasta escolhida sem intermediação.** O que
existe são dois caminhos, e a tela usa os dois, nessa ordem:

1. **`showSaveFilePicker()` (File System Access API)** — abre o diálogo **"Salvar como"** de
   verdade: o usuário escolhe a **pasta** e pode mudar o nome do arquivo. É o que o pedido descreve.
   Disponível no **Chrome/Edge/Opera de desktop**, em contexto seguro (HTTPS ou `localhost`).
   **Não existe** no Firefox, no Safari nem em navegador de celular.
2. **Fallback: download normal** (`<a download>` sobre um `blob:`) — o arquivo cai na pasta de
   downloads do navegador com o nome que a API sugeriu. O usuário só escolhe a pasta se tiver
   ligado *"Perguntar onde salvar cada arquivo"* nas configurações do próprio navegador — o que
   está **fora do alcance do produto**.

⛔ **A tela não promete um seletor de pastas.** O texto de apoio diz *"O navegador vai perguntar
onde salvar (ou baixar direto para a pasta de downloads, conforme a configuração dele)"*, e a
`AjudaDaTela` explica a diferença e onde ligar o "perguntar onde salvar" no Chrome.

⚠️ **Cancelar o diálogo não é erro.** `showSaveFilePicker` lança `AbortError` quando o usuário
desiste; tratar isso como falha mostraria um Toast vermelho para quem só mudou de ideia.

### 10. Fuso: o filtro segue o módulo, a pasta segue o bucket

- **O filtro de período** usa `(d.data_emissao AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ? AND ?`
  — exatamente a mesma expressão da lista de Documentos Fiscais. Duas telas sobre a mesma tabela
  não podem discordar sobre quais notas são "de agosto".
- **A pasta `AAAA-MM/` dentro do ZIP** sai do instante local da **UF do emitente**
  (`FusoDaUf.deOuPadrao(uf)`), que é a mesma conta que `ArquivamentoXmlService` faz para montar o
  caminho no bucket. Assim a pasta do ZIP é, por construção, **a mesma pasta do bucket** — e não
  uma segunda convenção que envelhece sozinha.

⚠️ As duas contas divergem em 1 h para AM/MT/MS/RO/RR/AC, então uma nota emitida entre 00:00 e
01:00 do dia 1º pode cair no ZIP do mês pedido e na pasta do mês anterior. É a mesma simplificação
já aceita em `FusoDaUf` e está registrada aqui para não virar "bug" na próxima leitura.

## Estrutura do ZIP

```
LOJA_CENTRO_08-2026.zip
├── relatorio.csv                                       índice (BOM UTF-8, ';')
├── 2026-08/
│   ├── saidas/
│   │   ├── 41260812345678000199650010000001231234567890.xml     nfeProc (XML + protocolo)
│   │   └── 41260812345678000199550010000000451234567891.xml
│   └── eventos/
│       └── 41260812345678000199650010000001231234567890-110111-1.xml
└── 2026-09/
    └── saidas/…
```

- `saidas/` — o **`nfeProc`** (XML assinado + `protNFe`), que é o que o contador precisa; nunca o
  `xml_assinado` cru, que não prova autorização nenhuma.
- `eventos/` — evento **autorizado** (hoje só o 110111, cancelamento), nomeado
  `{chave}-{tipoEvento}-{sequencia}.xml`.
- Não há `entradas/`. Ver Non-goals.

## Fluxo da tela

1. Abre com foco no seletor de **Empresa** (convenção 2026-08-22) e o período pré-preenchido com o
   **mês corrente** (1º dia até hoje) — é o recorte que o lojista pede em 9 de cada 10 vezes.
2. Datas são **texto mascarado** `dd/mm/aaaa` (`mascararData` + `select()` no foco), nunca
   `<input type="date">`.
3. Filtro opcional de **modelo**: Todos (padrão) · NFC-e (65) · NF-e (55). *(Não foi pedido —
   o pedido diz "exportar NFC-e e NF-e", que é o padrão "Todos". O seletor existe porque o contador
   às vezes pede só as 55; sai em uma linha se atrapalhar.)*
4. A cada mudança de filtro válido, a tela consulta o **resumo** e mostra um cartão:
   *"131 notas no período · 128 com XML pronto · 3 ainda não arquivadas · 4 eventos ·
   arquivo: `LOJA_CENTRO_08-2026.zip`"*.
5. **Baixar ZIP** — `GaugeProgresso` em modo simulado enquanto a requisição está em voo (o backend
   não reporta progresso; fingir número seria pior que não mostrar).
6. Toast **verde** no sucesso, **vermelho** no erro. Fechar é o **✕** do canto superior direito
   (`BotaoFecharTela`, `navigate(-1)`).

## Contrato de API

### `GET /api/v1/fiscal/exportacao-xml/resumo`

Pré-conferência. Não toca no bucket — só conta no banco.

```
?idEmpresa=1&dataInicial=2026-08-01&dataFinal=2026-08-31&modelo=65   (modelo opcional)

200 {
  "nomeArquivo": "LOJA_CENTRO_08-2026.zip",
  "totalDocumentos": 131,
  "documentosComXml": 128,
  "documentosSemXml": 3,
  "totalEventos": 4,
  "limiteDocumentos": 2000,
  "totalPartes": 1,
  "ateIdDocumento": 48123
}
```

### `GET /api/v1/fiscal/exportacao-xml`

```
?idEmpresa=1&dataInicial=2026-08-01&dataFinal=2026-08-31&modelo=65   (modelo opcional)
&parte=2&ateIdDocumento=48123                                       (partição; ver decisão 6)

200 application/zip
    Content-Disposition: attachment; filename="LOJA_CENTRO_08-2026_parte-2-de-7.zip"
```

Erros em **Problem Details (RFC 9457)**:

| Status | Quando |
|---|---|
| `400` | data inicial/final ausente, inicial > final, período > 365 dias, modelo diferente de 55/65 |
| `403` | papel `OPERADOR` (a tela e a API são ADMIN-only) |
| `404` | `idEmpresa` que não é do tenant |
| `409` | nenhum XML arquivado no período (**não gera ZIP vazio**) |
| `409` | período acima do teto de 2.000 documentos |
| `503` | MinIO fora do ar (mensagem própria do `ArmazenamentoPrivado`) |

Sob `/api/v1/**` (JWT de tenant, RLS ativo). Toda query traz
`id_tenant = plataforma.tenant_atual()` **escrita no texto do SQL** (P8), inclusive nos `JOIN`.

## Critérios de aceitação (viram testes)

- **Dado** um ADMIN com duas notas autorizadas e arquivadas no período, **quando** baixa o ZIP,
  **então** recebe `application/zip` contendo `2026-XX/saidas/{chave}.xml` para cada uma, com o
  conteúdo **idêntico** ao que está no bucket.
- ⭐ **Dado** um período sem nenhum XML arquivado, **quando** pede o ZIP, **então** recebe **409**
  com mensagem explicando — e **nenhum** arquivo é baixado.
- ⭐ **Dado** uma nota `AUTORIZADO` **sem** `xml_objeto_bucket`, **quando** consulta o resumo,
  **então** ela aparece em `documentosSemXml` (e não em `documentosComXml`); **e quando** baixa o
  ZIP, **então** ela **não** está entre os XMLs, mas **está** no `relatorio.csv` marcada como
  `(nao arquivado)`.
- **Dado** uma nota `NAO_EMITIDO` no período, **então** ela não entra no ZIP (não tem chave nem XML).
- ⭐ **Dado** uma nota **cancelada** com evento 110111 autorizado e arquivado, **quando** baixa o
  ZIP, **então** o pacote traz a nota em `saidas/` **e** o evento em `eventos/`.
- **Dado** o filtro `modelo=55`, **então** só a NF-e entra no ZIP.
- **Dado** um período que cruza dois meses, **então** o nome do arquivo é
  `{EMPRESA}_{MM-AAAA}_a_{MM-AAAA}.zip` e cada nota cai na pasta do **seu** mês.
- **Dado** um nome fantasia com acento e pontuação, **então** o nome do arquivo sai sem acento e
  sem caractere proibido.
- **Dado** um `OPERADOR`, **então** os dois endpoints respondem **403**.
- ⭐ **Dado** um documento arquivado por **outro tenant**, **então** ele não aparece no ZIP nem no
  resumo (RLS + filtro explícito).
- **Dado** data inicial maior que a final, ou período acima de 365 dias, **então** **400**.
- **Dado** um `idEmpresa` de outro tenant, **então** **404**.

## Ajuda da tela (R22 / §3.7.1) — obrigatório

Chave `fiscal.exportacao-xml.tela`, com objetivo, passos e erros comuns — incluindo (a) por que
nota rejeitada/não emitida não entra, (b) o que significa "ainda não arquivada" e o que fazer, e
(c) ⚠️ **o que o navegador realmente faz com "escolher onde salvar"**, com o caminho da opção
*Perguntar onde salvar cada arquivo* no Chrome.

## Impacto no banco

**Nenhum. Sem migration.** Leitura sobre `documento_fiscal`, `documento_fiscal_evento`, `empresa`,
`cliente` e `fornecedor` — todas já existentes, e o índice
`documento_fiscal_empresa_ix (id_tenant, id_empresa, data_emissao)` já cobre o filtro desta tela.

## Impacto nas integrações

Nenhum. Nada é gravado no bucket — só leitura da área `FISCAL_XML`.

## Non-goals desta feature

- ⛔ **Não inclui o XML de ENTRADA (nota do fornecedor).** Ele **está** no mesmo bucket desde a
  V051 (prefixo `entrada/`) e o §11.2 previa uma pasta `entradas/`, mas o pedido é explícito —
  *"Exportar NFC-e e NF-e"*, que são as notas **emitidas pela loja**. Fica como pergunta aberta
  (1), não como esquecimento: ligar é acrescentar uma query e uma pasta.
- **Não gera PDF (DANFE/DANFCE) dentro do ZIP.** O §11.2 previa "com ou sem PDF". O que tem valor
  fiscal é o XML; o PDF é representação, gerada sob demanda na tela de Documentos Fiscais.
- **Não é assíncrono e não tem progresso real.** Ver decisão 6.
- **Não tem papel `CONTADOR`.** O lojista baixa e repassa (§11.3, DF23 — mudança estrutural em
  `identidade`, não detalhe desta tela).
- **Não inclui inutilizações.** Elas são arquivadas (`fiscal_inutilizacao.xml_objeto_bucket`) e não
  têm data de emissão comparável — o recorte natural delas é por faixa de numeração, não por
  período de emissão. Pergunta aberta (2).
- **Não apaga nem regrava nada no bucket.**

⚠️ *Non-goals apodrecem* (memória de mesmo nome): conferir no código antes de repetir.

## Questões abertas (para o dono do produto)

1. **O XML das notas de ENTRADA (compra) deve entrar no pacote?** O contador costuma precisar das
   entradas tanto quanto das saídas, e o §11.2 já previa a pasta `entradas/` dizendo que "o custo
   de incluí-las é quase zero". Ficaram de fora porque o pedido nomeia só NFC-e e NF-e.
2. **Inutilizações entram?** Elas provam por que um número não virou nota. Precisam de um recorte
   próprio (por faixa/data de homologação).
3. **2.000 documentos é um teto razoável para a loja típica?** Se um mês normal passar disso, o
   caminho não é subir o número — é o desenho assíncrono do §11.2 (DF22).
4. **O seletor de modelo (Todos/NFC-e/NF-e) deve ficar?** Não foi pedido; foi acrescentado porque
   o contador às vezes pede só as 55.

---

## ⚠️ 2026-08-29 (auditoria, 2ª leva) — a mensagem do ZIP e o nome dos arquivos

### A correção de 26/08 tinha ficado só no resumo
O `resumir()` já separava *"pendente de arquivamento"* de *"sem valor fiscal"*; o `exportar()` não —
contava `documentos.size()` cru. No caso real que motivou aquela correção (40 documentos: **36
rejeitados**, 3 não emitidos, 1 pendente de verdade), a mensagem do ZIP mandava o lojista **aguardar
o arquivamento de 39 notas que nunca terão XML**. Agora as duas populações são contadas e a mensagem
diz qual é qual.

### Nota CANCELADA com arquivamento pendente nunca mais era arquivada
As duas consultas do job de arquivamento filtravam `situacao = 'AUTORIZADO'`. A nota autorizada cujo
arquivamento falhou (MinIO fora do ar — o caminho quente engole a falha, por desenho) e que é
**cancelada em seguida** deixava de ser AUTORIZADO antes de o job pegá-la: o `nfeProc` **nunca mais**
era arquivado e sumia da guarda legal de 5 anos. ⚠️ E esta tela **contava** essa nota como pendente
(`situacao IN ('AUTORIZADO','CANCELADO')`) — duas rotinas com populações diferentes para o mesmo
conceito, com a tela mandando aguardar um job que a ignorava.

### O nome do ZIP identifica a parte
`baixarTodasAsPartes` passava o **mesmo nome** para todas as partes, e `a.download` sobrescreve o
`Content-Disposition`: o Chrome renomeava para `… (1).zip`, `… (2).zip` — **continuando a numeração
de arquivos de exportações anteriores** que estivessem na pasta. O contador recebia um conjunto em
que não dá para dizer qual parte é qual, nem se falta alguma. Agora sai `-parte-N-de-M`.
