# Handoff: Arquivamento do XML fiscal no bucket privado
**Para:** equipe/desenvolvedor externo que vai implementar · **De:** Vetor Sistemas (Evirson)
**Data:** 2026-08-17 · **Status:** infra pronta, **falta o consumidor**
**Bloco no roteiro fiscal:** "Arquivamento" (`docs/MODULOFISCAL.md` §11, §17) · **Fecha:** DF21

---

## 0. Resumo em cinco linhas

O ERP já emite NFC-e de verdade contra a SEFAZ-PR (autoriza, cancela, inutiliza, reprocessa) e
guarda o XML **no Postgres**. Falta levar o XML **autorizado** para o bucket privado — que já
existe, com WORM de 5 anos — e preencher as colunas ponteiro que estão no schema desde a V035 e
seguem vazias. **Nada de infraestrutura precisa ser criado**: o storage, a credencial, o adapter
Java e os testes de storage já estão prontos e verificados. O trabalho é o **consumidor**: montar
o `nfeProc`, gravar, apontar, e ler de lá.

> ⚠️ **Regra de ouro deste projeto (leia antes de codar):** *se uma dúvida surgir durante a
> implementação, a resposta tem que estar na spec; se não estiver, atualize a spec antes de
> escrever código.* Isto vale para vocês também. Este documento é a spec desta tarefa — o que ele
> **não** responder, perguntem em vez de decidir sozinhos, e o que for decidido volta para cá.

---

## 1. O que ler, nesta ordem (e só o que importa de cada um)

| # | Arquivo | O que tirar dele |
|---|---|---|
| 1 | `CLAUDE.md` (raiz) | Convenções do repositório. **Obrigatório.** Português em tudo, `NUMERIC` para dinheiro, filtro explícito de `id_tenant` em toda query |
| 2 | `docs/infra/armazenamento-privado-minio.md` | O storage que vocês vão consumir: buckets, contrato Java, como subir em dev, o que **não** fazer |
| 3 | `docs/MODULOFISCAL.md` §11 (arquivamento) e §17 (roteiro) | Regra de negócio e onde esta tarefa se encaixa |
| 4 | `spec-driven-erp-varejo.md` §1 (Constituição) | P2 (idempotência), P3 (auditabilidade), P8 (isolamento de tenant) — não são recomendações |
| 5 | `docs/infra/isolamento-tenant-rls.md` | **Por que** toda query leva `id_tenant` no texto do SQL, mesmo com RLS ligado. Tem um caso real de vazamento reproduzido |
| 6 | `db/migration/V035__fiscal_documento.sql` | As tabelas e o **trigger de imutabilidade** que vai barrar código errado |

Princípios que esta tarefa toca diretamente:

- **P2 — toda operação é idempotente.** Reprocessar não pode duplicar nem corromper.
- **P3 — auditabilidade.** Nada de mutação silenciosa.
- **P8 — isolamento de tenant é inviolável.** Vale inclusive em job `@Scheduled` (que não tem
  requisição, e portanto não tem tenant, até alguém estabelecer um).
- **F6 (fiscal) — o XML autorizado é imutável.** Não se reescreve; correção é por evento ou
  documento novo.
- **F2 (fiscal) — nenhuma chamada de rede dentro de transação de banco.** Isso inclui o upload
  para o MinIO.

---

## 2. Estado atual — verificado em 2026-08-17, não confiem em memória

### Já existe e funciona

| Item | Onde |
|---|---|
| Emissão, cancelamento (110111), inutilização, contingência, reprocessamento | `api/src/main/java/com/vetor/niner/fiscal/documento/` |
| Object storage privado (MinIO), buckets, credencial de menor privilégio | `docker-compose.yml` + `infra/minio/bootstrap.sh` |
| Adapter Java do storage + 8 testes contra MinIO real | `comum/armazenamento/{ArmazenamentoPrivado,AreaPrivada,S3ArmazenamentoPrivado}.java` · `ArmazenamentoPrivadoTest` |
| Colunas ponteiro no schema | `documento_fiscal.xml_objeto_bucket`/`xml_hash` · `documento_fiscal_evento.xml_objeto_bucket` · `fiscal_inutilizacao.xml_objeto_bucket` |
| Job `@Scheduled` que já resolve "pendência por tenant" | `FiscalContingenciaDrenoJob` — **copiem o padrão dele**, inclusive o `TenantContext.comTenant` |

### Ainda não existe

- **Nada grava no bucket.** `xml_objeto_bucket` e os equivalentes estão `NULL` em 100% das linhas.
- O XML vive só em `documento_fiscal.xml_assinado` (texto, no Postgres).
- `GET /api/v1/fiscal/documentos/{id}/xml` devolve `xml_assinado` (o **assinado**, sem protocolo)
  — ver `DocumentoFiscalConsultaService`, ~linha 120.

### Onde a autorização se conclui hoje (é o gancho da tarefa)

```
EmissaoNfceService.concluir(...)            → repositorio.marcarAutorizado(idDocumento, resposta)
DocumentoFiscalReprocessamentoService       → mesmo caminho, depois de consultar a SEFAZ (F5)
FiscalContingenciaDrenoJob                  → mesmo caminho, quando a SEFAZ volta
CancelamentoNfceService                     → grava documento_fiscal_evento (xml_evento)
FiscalInutilizacaoService                   → grava fiscal_inutilizacao (xml_inutilizacao)
```

`DocumentoFiscalRepositorio.marcarAutorizado` faz o `UPDATE` que grava `protocolo`,
`data_autorizacao`, `status_sefaz` e `motivo_sefaz`.

> **Detalhe que economiza uma hora:** `documento_fiscal.status_sefaz` **não** guarda o `cStat` —
> guarda o **corpo XML inteiro da resposta da SEFAZ** (envelope SOAP incluído), de propósito
> (F9: é a única prova do que a SEFAZ respondeu). É de lá que sai o `<protNFe>` que vocês precisam
> para montar o `nfeProc`. Está documentado em `SefazDtos.RespostaSefaz`.

---

## 3. Escopo

### Entra

1. **Arquivar o `nfeProc`** (XML autorizado + protocolo) no bucket ao autorizar, preenchendo
   `documento_fiscal.xml_objeto_bucket` e `xml_hash`.
2. **Arquivar o XML dos eventos** autorizados (cancelamento 110111) → `documento_fiscal_evento`.
3. **Arquivar o XML das inutilizações** autorizadas → `fiscal_inutilizacao`.
4. **Recuperação:** job `@Scheduled` que arquiva o que ficou para trás (MinIO fora do ar,
   restart no meio, nota autorizada em contingência).
5. **Leitura:** `GET …/documentos/{id}/xml` passa a servir o XML **do bucket** quando ele já foi
   arquivado, com fallback para o banco.
6. **Migration** com o índice parcial da fila (§6.3) e testes (§8).

### Não entra (não comecem, é outra decisão ou outra tarefa)

- ❌ **ZIP do contador** (`MODULOFISCAL.md` §11.2, DF22) — depende de decisão de produto sobre a
  forma de entrega, e o ZIP **não** pertence à área imutável (é artefato descartável, regerável).
- ❌ **Foto de cliente** — outro consumidor do mesmo storage, tarefa separada.
- ❌ **B9 / NF-e de devolução** — travado pela DF20, decisão do dono do produto.
- ❌ **Apagar `xml_assinado` do banco depois de arquivar** — ver §10, questão em aberto (e há um
  trigger que impede).
- ❌ **Mexer na infra do MinIO** (buckets, política, credencial, retenção). Está pronta e
  verificada; se algo parecer faltar, perguntem antes.

---

## 4. Desenho recomendado

### 4.1 Quando gravar no bucket

**Depois** de a transação da autorização estar commitada, **fora** de qualquer transação (F2: o
upload é chamada de rede). Duas passagens:

```
1. caminho quente  → logo após marcarAutorizado(...) commitar, tenta arquivar.
                     Falhou? Loga e SEGUE. A venda não pode quebrar por causa do bucket.
2. rede de segurança → job @Scheduled varre o que está AUTORIZADO com xml_objeto_bucket NULL
                     e arquiva. É ele que cobre MinIO fora do ar, restart e contingência.
```

Por que as duas: a primeira dá arquivamento imediato no caso normal; a segunda é o que garante que
**nada** fique de fora — e é a única que existe para notas autorizadas por caminhos que não passam
pelo PDV (drenagem de contingência, reprocessamento).

> ⚠️ **O arquivamento nunca pode derrubar a emissão.** A venda já está feita e o cupom já está com
> o consumidor; o XML está a salvo no banco. Falha de bucket é problema de infraestrutura a ser
> drenado depois, não erro para o caixa. Mesmo princípio do F3 ("a venda nunca espera a SEFAZ").

### 4.2 Idempotência (P2) — a parte que costuma sair errada

A chave do objeto é **determinística**, derivada da chave de acesso. Rodar duas vezes gera a mesma
chave. E a área `FISCAL_XML` **recusa sobrescrever** (409) — de propósito.

Então o fluxo correto é:

```
se xml_objeto_bucket já está preenchido no banco → não faz nada (já arquivado)
senão:
    monta o nfeProc, calcula o SHA-256
    grava no bucket
        409 (já existe) → lê o objeto, confere o hash:
              igual    → tudo certo, era uma segunda passada; segue para o UPDATE
              diferente → PARA e registra erro. Isso é um bug ou uma chave repetida;
                          nunca "resolva" apagando (não dá) nem gerando outro caminho
    UPDATE documento_fiscal SET xml_objeto_bucket = ?, xml_hash = ? …
```

### 4.3 Onde o código deve morar

Sugestão (não é imposição, mas mantenham o módulo): `fiscal/documento/ArquivamentoXmlService.java`
+ `ArquivamentoXmlJob.java`, consumindo `ArmazenamentoPrivado` por injeção. O acesso a banco entra
em `DocumentoFiscalRepositorio` / `FiscalInutilizacaoRepositorio`, junto com o resto — **não**
abram um repositório novo só para isto.

---

## 5. Como montar o `nfeProc` — leiam com atenção

O que vai para o bucket **não** é o `xml_assinado` puro: é o `nfeProc`, que é o XML assinado
**mais** o protocolo de autorização.

```xml
<nfeProc versao="4.00" xmlns="http://www.portalfiscal.inf.br/nfe">
  <NFe>…</NFe>          <!-- exatamente o conteúdo de documento_fiscal.xml_assinado -->
  <protNFe versao="4.00">…</protNFe>   <!-- extraído de documento_fiscal.status_sefaz -->
</nfeProc>
```

> 🔴 **A assinatura digital quebra se o XML assinado for reserializado.** Montem o `nfeProc` por
> **concatenação de texto**, preservando byte a byte o trecho `<NFe>…</NFe>` que está em
> `xml_assinado`. Não passem esse trecho por DOM/JAXB/pretty-print/normalização de namespace:
> qualquer reordenação de atributo, mudança de aspas ou espaço em branco invalida a assinatura, e
> o XML arquivado deixa de valer como documento fiscal — **sem nenhum erro aparecer na hora**. O
> projeto já tomou essa decisão em outro ponto pelo mesmo motivo (`SefazTransporte` extrai campos
> por regex em vez de DOM, e explica o porquê no javadoc).
>
> Consequência prática: extraiam `<protNFe …>…</protNFe>` do `status_sefaz` também por
> recorte de texto, ignorando prefixo de namespace (a resposta vem embrulhada em SOAP e o prefixo
> **varia por UF** — o `extrair(...)` de `SefazTransporte` mostra o cuidado necessário).

Validem o resultado contra o XSD **`procNFe_v4.00.xsd`**, que já está versionado em
`api/src/main/resources/xsd/` (os 243 XSD oficiais estão todos lá — não baixem nada). O
`ValidadorXsd` já existe, mas hoje só expõe `validarNfe`, `validarEventoCancelamento` e
`validarInutilizacao`: acrescentem a constante do `procNFe` e o método correspondente reusando a
mecânica que já está lá, em vez de escrever um validador novo.

---

## 6. Contrato técnico

### 6.1 Gravar (a API do storage que vocês vão usar)

```java
// injetem a interface, nunca o S3ArmazenamentoPrivado direto
private final ArmazenamentoPrivado armazenamento;

String chave = armazenamento.gravar(
        AreaPrivada.FISCAL_XML,                       // bucket fiscal, imutável
        "%d/%02d/%d/%s.xml".formatted(ano, mes, modelo, chaveAcesso),
        nfeProc.getBytes(StandardCharsets.UTF_8),
        "application/xml");
// devolve: tenants/{id_tenant}/fiscal/2026/08/65/{chave44}.xml
```

- **O prefixo `tenants/{id_tenant}/fiscal/` é montado pelo adapter**, a partir do `TenantContext`.
  Não passem `id_tenant` por parâmetro e não montem caminho na mão — não vai funcionar e não é
  para funcionar (P8).
- `ano`/`mes` saem da **data de emissão** do documento, não de `now()` — senão uma nota drenada da
  contingência no dia 1º cai na pasta do mês errado.
- O que vai na coluna é a **chave devolvida**, nunca uma URL.
- Métodos disponíveis: `gravar`, `ler`, `existe`, `apagar` (recusado na área fiscal), `listar`.
- Não existe `urlPublica()` — arquivo privado sai **pela API**, autenticado. Se acharem que
  precisam de URL assinada, isso é decisão a registrar (DF22), não método a acrescentar.

### 6.2 Colunas a preencher, e o trigger que vigia

`db/migration/V035__fiscal_documento.sql` tem `documento_fiscal_imutavel_tg`, que **rejeita**:

- trocar `chave_acesso`, `numero`/`serie` ou `protocolo` já gravados;
- trocar `xml_objeto_bucket` **ou** `xml_hash` depois que `xml_objeto_bucket` deixou de ser `NULL`;
- trocar `xml_assinado` depois que existe `protocolo`.

Consequências para o código de vocês:

1. **Gravem `xml_objeto_bucket` e `xml_hash` no MESMO `UPDATE`.** Depois do primeiro, os dois
   viram pedra — um segundo `UPDATE` para "corrigir o hash" vai estourar exceção do banco.
2. ⚠️ **`xml_hash` hoje já vem preenchido no INSERT** com o SHA-256 do **XML assinado**
   (`DocumentoFiscalRepositorio.gravarAssinado`). Ao arquivar, ele passa a ser o hash do
   **`nfeProc` gravado no bucket** — que é o que faz sentido como prova do objeto arquivado.
   A troca é permitida porque `xml_objeto_bucket` ainda é `NULL` naquele instante. **Registrem
   essa mudança de significado no comentário da coluna** (migration nova), senão daqui a seis
   meses ninguém sabe hash de quê está ali.
3. Não tentem limpar `xml_assinado` (§10).

### 6.3 A fila de pendentes (sem tabela nova)

```sql
SELECT id_documento_fiscal
  FROM documento_fiscal
 WHERE id_tenant = plataforma.tenant_atual()     -- P8: sempre explícito, mesmo com RLS
   AND situacao = 'AUTORIZADO'
   AND xml_objeto_bucket IS NULL
 ORDER BY data_autorizacao
 LIMIT ?
```

Acrescentem o índice parcial numa **migration nova** (`V036__…`, a última hoje é a V035; siga o
padrão de `db/migration/README.md`):

```sql
CREATE INDEX documento_fiscal_pendente_arquivo_ix
    ON documento_fiscal (id_tenant, data_autorizacao)
 WHERE situacao = 'AUTORIZADO' AND xml_objeto_bucket IS NULL;
```

O job precisa varrer **por tenant** (é global, não tem requisição): mesma estrutura de
`FiscalContingenciaDrenoJob` — descobre os tenants com pendência e entra em
`TenantContext.comTenant(idTenant, () -> …)` antes de tocar em dado de domínio. **Sem isso o RLS
não deixa ver nada** — e, pior, um esquecimento parcial vê o que não devia.

### 6.4 Eventos e inutilizações

Mesmo desenho, outras tabelas:

| Origem | Coluna com o XML | Coluna ponteiro | Caminho sugerido |
|---|---|---|---|
| `documento_fiscal_evento` (cancelamento) | `xml_evento` | `xml_objeto_bucket` | `{ano}/{mes}/{modelo}/{chave}-{tipoEvento}-{sequencia}.xml` |
| `fiscal_inutilizacao` | `xml_inutilizacao` | `xml_objeto_bucket` | `{ano}/{mes}/{modelo}/inut-{serie}-{numInicial}-{numFinal}.xml` |

Só arquivem o que a SEFAZ **autorizou** (`autorizado = true` nas duas tabelas). Tentativa recusada
fica no banco, com valor de suporte e nenhum valor fiscal (é o que `MODULOFISCAL.md` §11.1 já
define).

Essas duas tabelas **não** têm trigger de imutabilidade — o cuidado fica por conta do código.

### 6.5 Leitura

`GET /api/v1/fiscal/documentos/{id}/xml` (`DocumentoFiscalConsultaService`, ~linha 120) passa a:

```
xml_objeto_bucket preenchido → lê do bucket (é o nfeProc, com protocolo — o que o contador quer)
senão                        → devolve xml_assinado, como hoje (nota ainda não arquivada)
```

Mantenham o mesmo DTO (`XmlDocumentoFiscalResponse`) e o mesmo contrato de erro (Problem Details,
RFC 9457). A tela de Documentos Fiscais no `web/` **não deve precisar de alteração** — se
precisar, algo saiu do contrato.

---

## 7. Critérios de aceitação (viram teste automatizado, P5)

> **Dado** uma NFC-e autorizada pela SEFAZ,
> **quando** o arquivamento roda,
> **então** existe no bucket fiscal um objeto em
> `tenants/{id_tenant}/fiscal/{ano}/{mes}/65/{chave}.xml`, o conteúdo é um `nfeProc` válido contra
> o XSD, e `xml_objeto_bucket`/`xml_hash` apontam para ele.

> **Dado** um `nfeProc` já arquivado,
> **quando** o arquivamento roda de novo para o mesmo documento (job, reprocessamento, retry),
> **então** nada é regravado, nenhum erro sobe, e as colunas continuam iguais (P2).

> **Dado** o MinIO fora do ar,
> **quando** uma NFC-e é autorizada,
> **então** a emissão **conclui normalmente** (o caixa não vê erro), o documento fica
> `AUTORIZADO` com `xml_objeto_bucket` nulo, e o job arquiva quando o MinIO volta.

> **Dado** um documento já arquivado,
> **quando** alguém tenta gravar outro XML na mesma chave,
> **então** a operação é recusada (409) e o objeto original permanece intacto (F6).

> **Dado** um documento arquivado,
> **quando** se tenta apagar o objeto pelo `ArmazenamentoPrivado`,
> **então** é recusado — e também é recusado se alguém tentar por fora, pela credencial da API
> (a política do bucket não dá `DeleteObject` no bucket fiscal).

> **Dado** um usuário do tenant A,
> **quando** pede o XML de um documento do tenant B,
> **então** responde 404/403 e **nenhum byte** do tenant B é lido do bucket (P8).

> **Dado** um cancelamento autorizado (evento 110111),
> **quando** o arquivamento roda,
> **então** o XML do evento está no bucket e `documento_fiscal_evento.xml_objeto_bucket` aponta
> para ele — e o mesmo vale para uma inutilização autorizada.

> **Dado** uma nota emitida em contingência e autorizada só quando a SEFAZ voltou,
> **quando** o job de arquivamento roda,
> **então** ela é arquivada na pasta do mês da **emissão**, não do mês da autorização.

> **Dado** um documento arquivado,
> **quando** o front pede `GET …/documentos/{id}/xml`,
> **então** recebe o `nfeProc` (com protocolo), e não o XML apenas assinado.

---

## 8. Testes e como rodar

- Padrão do projeto: **JUnit 5 + Testcontainers com Postgres real** (nada de mock de banco). Para
  o storage, subam o MinIO em container — o `ArmazenamentoPrivadoTest` já mostra exatamente como,
  com a mesma imagem do compose. **Nenhum teste pode tocar o MinIO de desenvolvimento.**
- `cd api && ./mvnw test` — a suíte inteira precisa continuar verde (**675+ testes**), não só os
  novos.
- Se o runtime de container for **Colima**: `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
  antes de rodar, senão o Ryuk falha ao montar o `docker.sock` (está no `api/README.md`).
- Front (se mexerem no `web/`): o type-check é **`cd web && npx tsc -b`** — nunca `tsc --noEmit`,
  que neste repositório checa **zero** arquivos e passa sempre (o `tsconfig.json` é solution-style).

---

## 9. Ambiente de desenvolvimento

```bash
docker compose up -d db && docker compose run --rm flyway   # banco + migrations
docker compose up -d minio minio-init                       # storage privado (9300 API, 9301 console)
cd api && ./mvnw spring-boot:run                            # API na 8080
```

- Rodando a API **no host**, o endpoint do MinIO já vem certo por default (`http://localhost:9300`)
  — **não precisa exportar nada**. Pelo compose, o serviço `api` já recebe `http://minio:9000`.
- Console web do MinIO em `http://localhost:9301` (`niner_root` / `dev_minio_root`, credenciais de
  **dev**) — útil para ver o objeto, a versão e a retenção com o olho.
- Propriedades relevantes (`api/src/main/resources/application.yml`, bloco `niner.storage.privado`):
  `endpoint`, `access-key`, `secret-key`, `bucket-fiscal`, `bucket-privado`.

**Não façam** (cada um destes quebra uma garantia já paga):

| ❌ | Por quê |
|---|---|
| Usar a credencial **root** do MinIO na aplicação | A credencial da API não apaga no bucket fiscal — é isso que faz vazamento de chave não virar perda de XML |
| Criar bucket novo, ou desligar retenção/versionamento | A retenção de 5 anos é obrigação legal do lojista (arts. 173/174 do CTN) |
| Montar o caminho do objeto na mão, com `id_tenant` de parâmetro | P8. O adapter monta e confere; caminho manual é vazamento esperando acontecer |
| Guardar URL em vez de chave na coluna | Trocar bucket/provedor tem que ser configuração, não migration de dados |
| Reserializar o XML assinado (DOM, pretty-print) | Invalida a assinatura **em silêncio** — §5 |
| Commitar qualquer credencial | `api/secrets/` e `*-service-account*.json` estão no `.gitignore`; mantenham assim |

---

## 10. Questões em aberto — decisão do dono do produto, não de vocês

1. **`xml_assinado` continua no banco depois de arquivado?** Hoje sim, e há uma trava: o trigger
   `documento_fiscal_imutavel_tg` **impede alterar `xml_assinado` depois que existe `protocolo`**,
   inclusive para `NULL`. Ou seja, limpar a coluna exigiria mudar o trigger numa migration — não é
   um `UPDATE` a mais. **Recomendação: não limpar no v1** (redundância barata, e o banco é o
   fallback de leitura). Se o volume incomodar, é decisão do Evirson, com migration própria.
2. **Retenção de 5 anos conta a partir de quando?** Hoje o bucket aplica 1825 dias a partir da
   **gravação**. Se a contagem legal correta for outra (a partir do fato gerador, do encerramento
   do exercício…), muda a configuração do bucket — não o código. Ponto para o contador confirmar.
3. **O que fazer com um documento `AUTORIZADO` que nunca arquiva** (fica dias na fila porque o XML
   está corrompido, por exemplo)? Precisa virar alerta em algum lugar — hoje não existe canal de
   alerta operacional no produto. Sugestão: contador na tela de Conformidade Fiscal, mas é
   decisão de produto.

---

## 11. Definição de pronto

- [ ] Critérios de aceitação da §7 cobertos por teste automatizado, todos verdes
- [ ] Suíte completa do backend verde (`./mvnw test`), sem teste desabilitado
- [ ] Migration nova versionada em `db/migration/` (índice parcial + comentário da coluna `xml_hash`)
- [ ] Toda query nova com `id_tenant` **explícito** no texto do SQL (P8) — inclusive dentro de
      `EXISTS`/`JOIN`
- [ ] Job `@Scheduled` estabelecendo `TenantContext` antes de tocar em dado de domínio
- [ ] Nenhuma chamada de rede dentro de transação de banco (F2)
- [ ] Nada de lógica de negócio no frontend (P4)
- [ ] `docs/MODULOFISCAL.md` §11/§17 e `docs/PROGRESSO.md` atualizados com o que foi entregue —
      **incluindo o que ficou de fora e por quê**
- [ ] Este documento atualizado se alguma decisão daqui mudou no caminho

---

## 12. Contato

Dúvida que este documento não responda: **Evirson — evirson@vetorsistemas.com.br**. Perguntem
antes de decidir; decisão tomada no escuro custa mais caro que uma pergunta, e neste domínio o
erro só aparece meses depois, numa fiscalização.
