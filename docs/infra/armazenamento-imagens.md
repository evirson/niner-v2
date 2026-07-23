# Spec: Armazenamento de imagens de produto (object storage)   Status: Aprovada — infra provisionada, código não iniciado
Autor: Evirson (Vetor) · Data: 2026-07-23 · Módulo(s): `comum` (infra) + `catalogo` · Fase: 1 — Núcleo do ERP
Decisão de arquitetura: **ADR-013** (spec §6) · Tabela afetada: `produto_imagem` (V017, §3.3.3)

> **Este documento é o handoff.** A infraestrutura no Google Cloud **já está criada e testada**
> (seção 2). O código **não existe ainda** (seção 5). Quem pegar esta tarefa deve ler as seções
> 2, 3 e 4 antes de escrever qualquer linha — principalmente a **seção 3 (credenciais)**, que é
> o único ponto que não se resolve sozinho pelo repositório.

---

## 1. Problema

`produto_imagem` (V017) já existe no schema e guarda a galeria de fotos do produto
(`indice smallint` controla a ordem; único por produto). A coluna `imagem text` está
comentada como *"URL/chave de object storage"*, mas **nenhum object storage tinha sido
escolhido** e nenhuma decisão registrada. Sem isso, a tela de Produtos (próximo passo do
roadmap — `docs/PROGRESSO.md`) não tem onde colocar as fotos.

Duas restrições moldam a solução:

- **P7/P1 não se aplicam aqui, mas P8 sim:** o caminho do objeto precisa carregar o
  `id_tenant`, ou o isolamento de tenant morre fora do banco (as fotos ficariam num
  espaço de nomes comum e não haveria como apagar/medir por tenant).
- **Marketplaces baixam a imagem pela URL.** Mercado Livre e Shopee buscam o arquivo num
  endereço público e **rebuscam depois** (revalidação, republicação do anúncio). Qualquer
  esquema de URL que expire quebra o anúncio silenciosamente semanas depois.

## 2. Estado provisionado (verificado em 2026-07-23)

Tudo abaixo **já está feito** e foi testado por comando. Não refaça.

| Item | Valor |
|---|---|
| Projeto GCP / Firebase | `niner-erp` (número `1052390802951`) |
| Plano de faturamento | **Blaze** (pay-as-you-go — obrigatório para provisionar bucket desde out/2024) |
| Bucket de produção | `niner-erp.firebasestorage.app` |
| Bucket de desenvolvimento | `niner-erp-dev` |
| Região (ambos) | `southamerica-east1` (São Paulo) — **imutável**, não dá para mudar depois |
| Classe / tipo | `REGIONAL` / `STANDARD` |
| Uniform bucket-level access | Ativo nos dois (sem ACL por objeto) |
| Public access prevention | `inherited` — não bloqueia (projeto sem organização) |
| Soft delete | 7 dias (padrão do Firebase — objeto apagado ainda é cobrado nesse período) |
| Leitura pública | `allUsers` → `roles/storage.objectViewer`, nos dois buckets |
| Conta de serviço da API | `niner-api-storage@niner-erp.iam.gserviceaccount.com` |
| Papel da conta de serviço | `roles/storage.objectAdmin` **apenas nos dois buckets** — nenhuma permissão no projeto |
| Base-URL pública | `https://storage.googleapis.com/<bucket>/<chave>` |

**Testes executados na configuração** (reproduzíveis pela seção 7):
1. Upload + `curl` anônimo, sem token → **HTTP 200** com o conteúdo. A leitura pública funciona.
2. Autenticado como a conta de serviço → gravou e apagou objeto em `gs://niner-erp-dev/`. A chave é válida e tem a permissão certa.

### 2.1 Pendências de infraestrutura (não bloqueiam o código)

- 🔴 **Alerta de orçamento — não criado.** Blaze é pós-pago **sem teto**. Criar em
  Faturamento → *Orçamentos e alertas* no projeto `niner-erp`, com alertas em 50/90/100%.
  **Responsável: Evirson** (precisa de permissão de faturamento).
- 🟡 **Regras do Firebase Storage — não travadas.** Estão no padrão do console. Como a decisão
  é **não usar o SDK do Firebase no navegador** (ver ADR-013), as regras são cinto-e-suspensório;
  o correto é publicar `allow read, write: if false;` para garantir que ninguém escreva pelo
  cliente. Não afeta a leitura pública (que é IAM/GCS, não regra do Firebase) nem a API
  (SDK de servidor ignora regras).

## 3. Credenciais — leia antes de tentar rodar

**A chave privada não está no repositório e nunca deve estar.** `.gitignore` bloqueia
`api/secrets/` e `*-service-account*.json`; `api/secrets/` existe com um `LEIA-ME.txt` e nada mais.
Na máquina onde a infra foi configurada existe `api/secrets/gcs-niner-erp.json` (chave da conta
de serviço, modo `600`) — **esse arquivo não viaja pelo git**.

Quem for continuar tem **duas formas** de obter acesso. A primeira é a recomendada.

### Opção A — credencial pessoal, sem arquivo de chave (recomendada)

Evirson concede ao Google Account do desenvolvedor o papel `Storage Object Admin`
**apenas no bucket de dev**:

```bash
gcloud storage buckets add-iam-policy-binding gs://niner-erp-dev \
  --member=user:EMAIL-DO-DEV@gmail.com \
  --role=roles/storage.objectAdmin
```

O desenvolvedor então roda, na própria máquina:

```bash
gcloud auth application-default login
```

Isso grava a *Application Default Credential* em `~/.config/gcloud/`. A biblioteca
`google-cloud-storage` a encontra sozinha quando `GOOGLE_APPLICATION_CREDENTIALS`
**não** está definida — ou seja, o código roda sem nenhum arquivo de chave no projeto.

Vantagens: nenhuma chave privada trafega, o acesso é revogável por pessoa, e um
desenvolvedor nunca toca o bucket de produção.

**Concessões já feitas por esta opção:**

| Data | Dev | Conta Google | Escopo |
|---|---|---|---|
| 2026-07-23 | Claudio Calixto | `claudiocalixto6969@gmail.com` | `roles/storage.objectAdmin` em `gs://niner-erp-dev` (via console web) |

Falta, do lado do Claudio: `gcloud auth application-default login` na máquina dele com essa
conta, e o teste de escrita da seção 7 contra `gs://niner-erp-dev`.

⚠️ Ressalva: ADC de usuário **não assina URLs V4**. Isso só importaria se um dia
mudássemos para leitura por signed URL — no desenho atual (bucket público) não é usado.

### Opção B — chave de conta de serviço própria

Se preferirem uma chave (necessário para rodar a API **dentro do container**, onde o
`~/.config/gcloud` do host não existe), Evirson gera **uma chave por desenvolvedor** — nunca
compartilhe a mesma:

```bash
gcloud iam service-accounts keys create ~/gcs-niner-dev.json \
  --iam-account=niner-api-storage@niner-erp.iam.gserviceaccount.com
```

Transmita por **cofre de senhas** (1Password, Bitwarden) ou canal cifrado. **Nunca** por
e-mail, WhatsApp, Slack ou commit. No destino: coloque em `api/secrets/`, rode `chmod 600`,
e confirme com `git check-ignore -v api/secrets/<arquivo>.json` que o git a ignora.

### Opção C — SEM credencial: emulador local (2026-07-23, o caminho para desenvolver)

Para **desenvolver a feature de fotos** não precisa de GCS real nenhum: o docker-compose tem
o serviço `fake-gcs` (o mesmo `fsouza/fake-gcs-server` que o `ProdutoImagemCrudTest` usa via
Testcontainers), e a API ganhou a propriedade `niner.storage.host` — preenchida, o cliente
GCS aponta pro emulador com `NoCredentials` e cria o bucket sozinho na primeira gravação.

```bash
docker compose up -d fake-gcs                                  # emulador na porta 4443
cd api && NINER_STORAGE_HOST=http://localhost:4443 ./mvnw spring-boot:run
```

No PowerShell: `$env:NINER_STORAGE_HOST = 'http://localhost:4443'; ./mvnw spring-boot:run`.

Só isso — a `base-url` das URLs públicas herda o host automaticamente, então upload,
exclusão, reordenação e **exibição no navegador** funcionam de ponta a ponta
(validado em 2026-07-23: `POST /api/v1/produtos/2/imagens` → `http://localhost:4443/
niner-erp-dev/tenants/1/produtos/2/<uuid>.webp` → HTTP 200 `image/webp`). As imagens ficam
no volume `fake-gcs-data` e sobrevivem a restart do container. Host **vazio** (default) =
comportamento antigo, GCS real via ADC/chave — nada muda em staging/produção.

Use as Opções A/B apenas quando precisar validar contra o bucket `niner-erp-dev` de verdade
(ex.: conferir política pública/CORS do GCS real antes de um release).

Chaves são rastreáveis e revogáveis: `gcloud iam service-accounts keys list --iam-account=...`
e `keys delete <KEY_ID>`.

### Se a chave vazar

Revogue imediatamente (`keys delete`), gere outra, e verifique os objetos do bucket. A conta
de serviço só tem `objectAdmin` nos dois buckets — o estrago possível é apagar/substituir
imagens de produto, não tocar em banco, faturamento ou qualquer outro recurso do projeto.
Esse escopo mínimo foi deliberado.

## 4. Contrato (o que o código precisa respeitar)

### 4.1 Caminho do objeto

```
tenants/{id_tenant}/produtos/{id_produto}/{uuid}.webp
```

- O `id_tenant` **sempre** vem do `TenantContext`, nunca de parâmetro de request (P8).
- O `{uuid}` é aleatório (`UUID.randomUUID()`), não sequencial. É ele que torna o caminho
  não-enumerável — importante porque o bucket é público.
- Nunca reaproveitar caminho ao substituir uma imagem: gere um UUID novo e apague o antigo
  depois de gravar a linha. Assim uma falha no meio nunca deixa o produto sem foto.

### 4.2 O que vai na coluna `produto_imagem.imagem`

**A chave, não a URL completa.** Ou seja `tenants/12/produtos/840/a3f9….webp`, e não
`https://storage.googleapis.com/…`.

Motivo: trocar de bucket, de região ou de provedor vira mudança de configuração em vez de
migration de dados em produção. A URL pública é montada na saída da API concatenando
`niner.storage.base-url` + a chave. O front nunca monta URL sozinho — recebe pronta
(P4: sem lógica de negócio no frontend).

### 4.3 Fluxo de upload

```
navegador → POST multipart /api/v1/produtos/{id}/imagens → API valida, normaliza, grava no GCS
                                                          → INSERT em produto_imagem → devolve a URL
```

O navegador **não** fala com o Firebase/GCS. Isso é decisão do ADR-013, não detalhe de
implementação: se o browser subisse direto, o isolamento de tenant passaria a depender de
Security Rules do Firebase + uma identidade Firebase paralela ao nosso JWT — dois sistemas
de autenticação para a mesma regra. Além disso a API precisa processar a imagem de qualquer
forma (normalizar formato/tamanho antes de publicar em canal).

### 4.4 Exclusão

`produto_imagem` **não tem `ON DELETE CASCADE`** (convenção do projeto): apagar um `produto`
com imagens vinculadas falharia por FK.

**Comportamento já implementado (`ProdutoService.excluir`, 2026-07-22):** excluir um produto que
tenha variação (`produto_barra`) **ou** imagem (`produto_imagem`) vinculada **inativa** o produto
em vez de apagá-lo — é o padrão de "delete com fallback para desativar" das telas de cadastro
(`CLAUDE.md`). Ou seja, o fluxo de exclusão de produto **não precisa mexer no bucket**: as fotos
continuam válidas porque o produto continua existindo, apenas inativo.

O que precisa de cuidado é a **exclusão de uma imagem individual** (`DELETE .../imagens/{id}`),
na ordem: apagar a linha → apagar o objeto no bucket. Objeto órfão (linha apagada, arquivo não)
custa dinheiro em silêncio; linha órfã (arquivo apagado, linha não) quebra a tela. A ordem acima
erra para o lado barato — e uma rotina de varredura pode limpar órfãos depois, se um dia valer a pena.

## 5. O que falta implementar

Nada disso existe hoje. O módulo `catalogo` **já tem** o CRUD de `produto`
(`ProdutoController`/`Service`/`Dtos`, categorias e NCM — entregue em 2026-07-22, ver
`docs/telas/produto.md`), mas **nenhum código de imagem**: `docs/telas/produto.md` deixou a
galeria explicitamente fora de escopo. As tasks abaixo são a continuação natural daquela tela.

### TASK-A — infra de armazenamento (`comum`)
Interface `ArmazenamentoDeArquivos` (`gravar`, `apagar`, `urlPublica`) + adapter `GcsArmazenamento`.
Dependência: `com.google.cloud:google-cloud-storage` via `libraries-bom` — **não** o
`firebase-admin` inteiro, que traz Auth/Firestore/FCM sem necessidade (Firebase Storage *é* GCS).

Configuração nova em `application.yml`:
```yaml
niner:
  storage:
    bucket:   ${NINER_STORAGE_BUCKET:niner-erp-dev}
    base-url: ${NINER_STORAGE_BASE_URL:https://storage.googleapis.com}
```
Em `docker-compose.yml`, serviço `api`: montar `./api/secrets:/secrets:ro` e definir
`GOOGLE_APPLICATION_CREDENTIALS=/secrets/gcs-niner-erp.json`.

> **Dado** que o tenant 12 está no contexto,
> **quando** `gravar(idProduto=840, bytes)` é chamado,
> **então** o objeto nasce em `tenants/12/produtos/840/<uuid>.webp` e o método devolve essa chave.

> **Dado** um `TenantContext` vazio (worker/rota sem tenant),
> **quando** `gravar` é chamado,
> **então** falha com erro — nunca grava em caminho sem tenant (P8).

### TASK-B — endpoints de imagem do produto
`POST /api/v1/produtos/{id}/imagens` (multipart), `DELETE /api/v1/produtos/{id}/imagens/{idImagem}`,
`PUT /api/v1/produtos/{id}/imagens/ordem` (reordena `indice`). OpenAPI **antes** do código
(convenção do projeto). Erros em Problem Details (RFC 9457).

Validações no servidor (nunca só no front — P4):
- Tipos aceitos: JPEG, PNG, WebP. Rejeite pelo **conteúdo** (magic bytes), não pela extensão nem pelo `Content-Type` do cliente.
- Tamanho máximo de upload: sugerido 10 MB. Ajuste `spring.servlet.multipart.max-file-size`.
- Normalização: converter para WebP e limitar a maior dimensão (sugerido 1600 px). Biblioteca sugerida: Thumbnailator (`net.coobird:thumbnailator`) — ImageIO puro não lê/escreve WebP sem plugin.
- 🔴 **Confirmar antes de fixar números:** dimensão mínima e formatos exigidos por Mercado Livre e Shopee. O ML rejeita imagem abaixo de um mínimo e recomenda quadrada de lado grande; os valores exatos precisam sair da documentação oficial do canal, não de memória. Isso vira validação de *publicação de anúncio* (adapter), não de upload.

> **Dado** um produto com 3 imagens (índices 0,1,2),
> **quando** a imagem de índice 1 é excluída,
> **então** as restantes são renumeradas para 0,1 sem violar `produto_imagem_uk` e o objeto some do bucket.

> **Dado** um arquivo `.exe` renomeado para `.jpg`,
> **quando** enviado ao endpoint,
> **então** responde 422 com Problem Details e nada é gravado no bucket.

> **Dado** um usuário do tenant 12,
> **quando** tenta excluir uma imagem de produto do tenant 7,
> **então** responde 404 (RLS não enxerga a linha) e o objeto do tenant 7 permanece intacto.

### TASK-C — testes
`fake-gcs-server` via Testcontainers, no mesmo padrão já usado para o Postgres
(ver `api/README.md` para o contorno de runtime de container). **Nenhum teste pode
tocar o bucket real** — CI sem credencial deve passar.

### TASK-D — front (`web/`)
Galeria na tela de Produtos: upload, reordenação por arrastar, exclusão, preview. Depende
da tela de Produtos, que também não existe. Segue o padrão de tela de cadastro
(`docs/telas/cliente.md`).

## 6. Riscos e pontos em aberto

- 🔴 **Egress é o custo dominante, não o armazenamento.** Guardar as fotos é barato; servi-las
  a cada visita de e-commerce e a cada refetch de marketplace, não. **Gatilho de revisão do
  ADR-013:** se a saída de dados passar a pesar na fatura, migrar para Cloudflare R2 (egress
  zero, API compatível com S3). Por isso a TASK-A é uma **interface** com adapter, e por isso
  a coluna guarda a chave — a troca deve custar um adapter novo e uma variável de ambiente,
  nunca uma migration de dados.
- 🟡 **Não há contador de bytes por tenant.** `plataforma.uso_tenant` (V009) tem
  `qtd_canais`/`qtd_produtos`/`qtd_usuarios`/`qtd_pedidos_mes`, mas nada de armazenamento.
  Se o plano vier a limitar espaço (R19), é preciso `bytes_armazenados bigint` numa migration
  nova (V028+) e atualizá-lo no mesmo ponto onde o objeto é gravado/apagado. **Decisão de
  produto pendente** — não implemente por conta própria.
- 🟡 **Bucket público.** Foto de produto vai ser pública nos marketplaces de qualquer forma, e
  URL que expira quebra anúncio. Mas isso significa que **nada além de foto de produto pode
  entrar nesses buckets** — documento de cliente, XML de nota, backup, anexo de contrato exigem
  bucket privado e outra decisão.
- 🟡 **Soft delete de 7 dias** significa que objeto apagado continua sendo cobrado por uma
  semana. Irrelevante no volume atual; lembrar se um dia houver reprocessamento em massa.

## 7. Verificação rápida (comandos)

Confirma que o ambiente continua como descrito na seção 2:

```bash
gcloud config set project niner-erp

# região, acesso uniforme, prevenção de acesso público
gcloud storage buckets describe gs://niner-erp.firebasestorage.app

# quem tem acesso (deve listar allUsers→objectViewer e a SA→objectAdmin)
gcloud storage buckets get-iam-policy gs://niner-erp-dev --format=json

# leitura pública ponta a ponta, sem credencial
echo ok > /tmp/t.txt
gcloud storage cp /tmp/t.txt gs://niner-erp-dev/t.txt
curl -s -o /dev/null -w "%{http_code}\n" https://storage.googleapis.com/niner-erp-dev/t.txt  # espera 200
gcloud storage rm gs://niner-erp-dev/t.txt
```

`gcloud` não vem instalado por padrão no macOS: `brew install --cask google-cloud-sdk`,
depois `gcloud auth login`.
