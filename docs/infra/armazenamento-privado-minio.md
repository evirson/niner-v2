# Spec: Armazenamento privado (MinIO / S3)   Status: **Infra implementada — sem consumidor ainda**
Autor: Evirson (Vetor) · Data: 2026-08-17
Módulo(s): `comum.armazenamento` (infra) · Decisão de arquitetura: **ADR-014** (spec §6)
Relacionados: `armazenamento-imagens.md` (ADR-013, bucket **público** das fotos de produto) ·
`docs/MODULOFISCAL.md` §11 (arquivamento, DF21) · `isolamento-tenant-rls.md` (P8)

> **O que existe hoje:** o storage está de pé (docker-compose + buckets + credencial + adapter Java
> + testes), mas **nenhuma tela ou serviço grava nele ainda**. Os dois consumidores previstos —
> arquivamento do XML fiscal e foto de cliente — são tarefas próprias, cada uma com sua spec. Ver
> §7.

---

## 1. Por que um segundo armazenamento

O do ADR-013 (GCS) é **público de propósito**: Mercado Livre e Shopee rebuscam a foto do produto
pela URL, então qualquer esquema que expire quebra o anúncio. Isso resolve foto de produto e
**nada mais** — o próprio ADR-013 já registrava que "documento, XML de nota e anexo exigem bucket
privado e outra decisão". Essa decisão é o ADR-014, puxada por duas necessidades ao mesmo tempo:

| Necessidade | Por que não serve o que já existe |
|---|---|
| **XML fiscal autorizado** | Bucket público está fora de questão (é documento fiscal do lojista). Hoje o XML só existe em `documento_fiscal.xml_assinado`, no Postgres: sem WORM, sem versionamento, inflando backup e WAL. O Arquivamento ficou fora do B8 justamente por falta de bucket |
| **Foto de cliente** | Dado pessoal (LGPD). Nunca pode cair num bucket de leitura pública |

E uma terceira força, do histórico do projeto: **custo**. A proposta de usar bucket GCS para o
compartilhamento de comprovantes foi rejeitada pelo dono do produto em 2026-08-07 ("vai ter
custo"). MinIO auto-hospedado tem custo marginal zero em dev e custo previsível (disco de VPS) em
produção — e, por falar S3, mantém a saída aberta para R2/S3/VPS sem migrar dado.

## 2. Os dois buckets (e por que não é um só)

| | `niner-fiscal-dev` | `niner-privado-dev` |
|---|---|---|
| Guarda | XML autorizado, evento, inutilização | Foto de cliente e futuros anexos pessoais |
| Versionamento | Sim (vem junto com o lock) | Sim |
| Object Lock | **GOVERNANCE, 1825 dias** (5 anos) | **Não** |
| Apagar | **Impossível** — recusado pelo código, pela política da credencial e pela retenção do bucket | Permitido |
| Motivo | Guarda legal de 5 anos (arts. 173/174 do CTN) e imutabilidade do XML autorizado (F6) | LGPD: o titular tem direito à exclusão. Guardar sob WORM criaria passivo, não proteção |

Os ciclos de vida são **opostos** — um proíbe apagar, o outro obriga a saber apagar. Por isso são
dois buckets e não dois prefixos: política de retenção no S3 é por bucket.

> ⚠️ **GOVERNANCE, não COMPLIANCE.** GOVERNANCE impede apagar a versão, mas um administrador com
> permissão explícita de bypass ainda consegue intervir. COMPLIANCE não perdoa nem o root — nem um
> engano nosso de provisionamento. Cedo demais para isso; a revisão natural é quando a operação
> estiver rodando com lojista real há alguns meses.

> ⚠️ **Em bucket versionado, apagar não apaga.** Um `DELETE` sem `versionId` cria um *delete
> marker*: o objeto some da listagem e a versão antiga continua guardada (e ocupando disco) para
> sempre. Para foto de produto isso seria detalhe; para **dado pessoal é o oposto do que a LGPD
> pede**. Por isso o bucket privado tem regra de ciclo de vida
> (`--noncurrent-expire-days 30 --expire-delete-marker`): a exclusão vira definitiva em 30 dias, e
> a janela existe para dar tempo de desfazer engano. **Achado durante a verificação desta entrega**
> — não era óbvio, e um `mc rm` "bem-sucedido" esconde o problema.

## 3. Caminho do objeto

```
tenants/{id_tenant}/{área}/{caminho relativo}

tenants/12/fiscal/2026/08/65/41260812345678000190650010000001231000001234.xml
tenants/12/clientes/840/9f3c…webp
```

- O prefixo `tenants/{id_tenant}/` é montado **dentro do adapter**, a partir do `TenantContext`
  (P8) — nunca chega por parâmetro, então nenhum chamador consegue escrever fora da própria loja.
- A **leitura confere o prefixo antes de ir ao bucket**. Isso é defesa em profundidade, não
  redundância: a auditoria de 2026-08-08 (`isolamento-tenant-rls.md`) provou que um SELECT sem
  filtro explícito de `id_tenant` pode devolver linha de outro tenant. Se um dia a chave vier
  errada do banco, o pedido morre com 403 em vez de virar download do XML do vizinho.
- A hierarquia por ano/mês do fiscal é o que faz o ZIP do contador virar **listagem de prefixo**,
  não varredura do bucket inteiro (`MODULOFISCAL.md` §11.2).
- O que vai na coluna do banco é a **chave** (ex.: `documento_fiscal.xml_objeto_bucket`), nunca
  URL — mesma regra do ADR-013, e pelo mesmo motivo: trocar de bucket/provedor é configuração, não
  migration de dados.

## 4. Credencial: a API nunca é root

`infra/minio/bootstrap.sh` cria o usuário `niner_app` com esta política:

| Ação | `niner-fiscal-*` | `niner-privado-*` |
|---|---|---|
| `ListBucket`, `GetObject`, `PutObject` | ✅ | ✅ |
| `DeleteObject` | ❌ | ✅ |
| `BypassGovernanceRetention`, criar bucket, admin | ❌ | ❌ |

Ou seja: **vazamento da chave da API não apaga XML fiscal.** Verificado por comando em 2026-08-17
(`mc` com a credencial da app): grava no fiscal ✅, apaga no fiscal ❌ *Access Denied*, grava/apaga
no privado ✅, cria bucket ❌, leitura anônima ❌.

A conta **root** do MinIO existe só para o bootstrap e para a administração pelo console — a API
nunca a usa, e em produção ela não deve nem estar no ambiente da API.

## 5. Como rodar em dev

```bash
docker compose up -d minio minio-init     # servidor + buckets + credencial (idempotente)
```

- **API S3:** `http://localhost:9300` — é essa que a aplicação consome.
- **Console web:** `http://localhost:9301` — login `niner_root` / `dev_minio_root` (defaults de
  dev do compose). Serve para conferir objeto, versão e retenção com o olho.
- As portas são 9300/9301 porque esta máquina já roda outros MinIO em 9010, 9100 e 9200.

Rodando a API **pelo compose**, o serviço `api` já recebe `http://minio:9000` (nome interno da
rede). Rodando a API **no host** (`./mvnw spring-boot:run`), o default de `application.yml` já é
`http://localhost:9300` — **não precisa exportar nada**, ao contrário do que o `fake-gcs` exige.

Sem o MinIO no ar a API **sobe normalmente** (o bean `S3Client` é `@Lazy`): a falha só aparece na
primeira gravação, como 503 com mensagem apontando para este documento.

O mesmo `bootstrap.sh` provisiona o VPS no dia da migração — só mudam `MINIO_ENDPOINT` e as
credenciais. É por isso que ele não depende de nada do compose além de variáveis de ambiente.

## 6. Contrato do código (`comum.armazenamento`)

```java
String  gravar(AreaPrivada area, String caminhoRelativo, byte[] conteudo, String contentType);
byte[]  ler(AreaPrivada area, String chave);
boolean existe(AreaPrivada area, String chave);
void    apagar(AreaPrivada area, String chave);          // recusado em área imutável
List<String> listar(AreaPrivada area, String prefixoRelativo);
```

`AreaPrivada` decide **bucket, prefixo e mutabilidade de uma vez** — quem chama não tem como
escolher a combinação errada. Hoje: `FISCAL_XML` (imutável) e `CLIENTE_FOTO` (apagável). Área nova
= valor novo no enum, com a decisão de mutabilidade tomada conscientemente ali.

**Não existe `urlPublica()`, e não é esquecimento.** Arquivo privado sai **pela API**,
autenticado; o navegador nunca fala com o bucket. (Se um dia um ZIP grande justificar URL
assinada de validade curta — o caso do download do contador, DF22 —, isso é uma decisão a
registrar, não um método a acrescentar sem discussão.)

Erros: 403 chave fora do tenant · 404 objeto inexistente · 409 tentativa de sobrescrever/apagar
área imutável · 503 MinIO fora do ar ou credencial errada.

**Testes** (`ArmazenamentoPrivadoTest`, 8 casos, MinIO real via Testcontainers — a mesma imagem do
compose, nunca o MinIO de dev): prefixo do tenant, recusa de leitura cruzada, recusa de
sobrescrita e de exclusão no fiscal, **WORM do bucket recusando `DELETE` mesmo por fora do
adapter**, exclusão de verdade no privado, listagem que não atravessa tenant, gravação sem tenant
no contexto, e caminho com `..` recusado.

## 7. O que falta (cada um é uma tarefa própria)

1. **Arquivamento fiscal** — gravar o `nfeProc` (e XML de evento/inutilização) na área
   `FISCAL_XML` ao autorizar, preencher `documento_fiscal.xml_objeto_bucket` + `xml_hash` (as
   colunas existem desde a V035 e continuam vazias), e fazer a tela de Documentos Fiscais ler o
   XML do bucket. Fecha a DF21 e o item "Arquivamento" do `MODULOFISCAL.md` §17.
   📄 **Spec de handoff pronta para outra equipe: `docs/HANDOFF-ARQUIVAMENTO-XML.md`** — contrato,
   critérios de aceitação, armadilhas e definição de pronto.
2. **Foto de cliente** — coluna nova, endpoint de upload/exclusão e a área `CLIENTE_FOTO`. Não
   existe nada disso hoje (nem coluna, nem tela).
3. **ZIP do contador (DF22)** — hoje `comum.arquivocompartilhado` guarda em memória, limitado a 20
   arquivos por tenant; um ZIP de centenas de MB não cabe nesse desenho. Com bucket privado
   disponível, o caminho passa a existir — falta decidir a forma de entrega.

## 8. Riscos e o que ainda não está resolvido

- 🔴 **Backup do MinIO não existe.** Em dev é o volume `minio-data` e ponto — `docker compose
  down -v` leva o XML fiscal junto. **Pré-requisito da migração para VPS**, não tarefa posterior:
  enquanto MinIO e Postgres estiverem na mesma máquina, uma perda de disco leva os dois.
  Caminho natural: `mc mirror` para destino externo + snapshot do volume.
- 🔴 **TLS.** Em dev é HTTP na rede do Docker, o que é aceitável. No VPS, o tráfego API↔MinIO sai
  da máquina e **precisa ser HTTPS** — atrás de proxy reverso com certificado, e o endpoint muda
  para `https://…` na variável de ambiente.
- 🟡 **Sem contador de bytes por tenant.** Mesma lacuna já registrada no ADR-013:
  `plataforma.uso_tenant` não tem `bytes_armazenados`. Se o plano vier a limitar espaço (R19), é
  migration nova — e o XML fiscal cresce para sempre, então essa conta importa mais aqui do que
  nas fotos.
- 🟡 **Retenção não é o mesmo que backup.** WORM impede apagar; não protege contra perder o disco
  inteiro. São dois problemas diferentes e só um está resolvido.
- 🟡 **Credencial de dev no `docker-compose.yml`** (`dev_minio_root` / `dev_minio_app`), no mesmo
  padrão já usado para o Postgres. Em produção: secret manager, e a root fora do ambiente da API.
