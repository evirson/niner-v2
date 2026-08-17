# Spec: Certificado Digital (fiscal_certificado)                    Status: Rascunho
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-17 · Módulo(s): `fiscal.certificado` · Fase: F1 — Fundação (bloco B2)

## Problema

Sem certificado digital A1 não existe assinatura de XML e não existe mTLS com a SEFAZ — nenhuma nota
sai. O certificado é o **segredo de terceiro** mais sensível que o produto vai guardar: quem o obtém
emite nota no CNPJ do lojista (F7).

O schema já existe (`fiscal_certificado` + `fiscal_certificado_uso`, V035), mas não há tela, upload,
nem rotina de validade.

## Solução proposta

Tela de **upload write-only por empresa**. O lojista envia o `.pfx` e a senha; a partir daí a API
**nunca devolve nenhum dos dois** — nem para ADMIN, nem em log, nem em resposta de erro. A tela mostra
apenas metadados extraídos do próprio certificado (CNPJ titular, razão social, validade, impressão
digital) e o estado de vencimento.

**Acesso: somente ADMIN.** Item de menu com `adminOnly`.

**Uma tela de lista + upload**, não o padrão de cadastro completo: não há edição (certificado não se
edita — se mudou, é outro arquivo) e não há exclusão (ver *Histórico é imutável* abaixo).

## Particularidades estruturais

1. **Write-only de verdade.** `POST` recebe multipart (`arquivo` + `senha` + `idEmpresa`). Nenhum
   endpoint devolve o `.pfx` nem a senha. Não existe rota de download. Isso vale para o request
   também: a senha nunca aparece em query string, só no corpo multipart.
2. **A senha não vai em claro para o banco.** `fiscal_certificado.senha_cifrada` guarda o valor
   **cifrado** (AES-256-GCM, `comum.seguranca.SegredoCifrador`), com a chave mestra **fora do
   banco** (`niner.seguranca.chave-segredos`, variável de ambiente/secret manager em produção —
   ver DF21 fechada abaixo). O `.pfx` sobe **tal como recebido** (não recifrado pela aplicação)
   para o bucket fiscal, e `objeto_bucket` guarda a chave do objeto. O banco, sozinho, não abre o
   certificado — falta a chave, que não está lá.
3. **Bucket próprio — NUNCA o de fotos de produto.** Os buckets do ADR-013
   (`niner-erp.firebasestorage.app` / `niner-erp-dev`) são de **leitura pública** de propósito
   (marketplaces rebuscam a imagem por URL). Subir um `.pfx` ali seria vazamento imediato. **DF21
   fechada (2026-08-17): bucket privado dedicado**, `niner.storage.bucket-fiscal` (env
   `NINER_STORAGE_BUCKET_FISCAL`, default dev `niner-fiscal-dev`) — mesmo host/credencial do
   bucket de fotos, nome diferente. Sem rota de leitura pública, sem `urlPublica()` no adapter
   (`fiscal.certificado.CertificadoStorage`).
4. **Metadados são extraídos, nunca digitados.** CNPJ titular, razão social, `valido_de`,
   `valido_ate` e `impressao_digital` (SHA-256) saem do próprio arquivo no momento do upload. O
   lojista não digita nada além da senha — dado digitado diverge do certificado e mente.
5. **Histórico é imutável.** Certificado antigo **nunca é apagado**, só marcado `ativo = false`.
   Uma nota de 2026 foi assinada com um certificado específico, e a auditoria (F9) precisa saber
   qual. Não há `DELETE` — nem na tela, nem na API. `fiscal_certificado_uso` também não tem
   `GRANT DELETE` para `niner_app` (V035), e esse invariante precisa entrar em
   `PrivilegiosNinerAppTest` ([[feedback_testcontainers_nao_usa_niner_app]]).
6. **Um ativo por empresa.** Subir um certificado novo desativa o anterior na mesma transação. Duas
   linhas `ativo = true` para a mesma empresa é estado inválido.

## Validações no upload (todas no servidor, antes de gravar qualquer coisa)

| # | Validação | Falha |
|---|---|---|
| 1 | O arquivo abre como PKCS#12 com a senha informada | 400 "Senha do certificado incorreta ou arquivo inválido" |
| 2 | Tem chave privada (não é só a parte pública) | 400 |
| 3 | `valido_ate` no futuro | 400 "Certificado vencido em {data}" |
| 4 | CNPJ do titular **igual** ao `empresa.cnpj` | 409 "Certificado é de outro CNPJ ({cnpj}); a empresa é {cnpj}" |
| 5 | `impressao_digital` ainda não cadastrada e ativa para esta empresa | 409 "Este certificado já está cadastrado" |

A validação 4 é a que evita o erro mais caro possível: subir o certificado da matriz na filial e
emitir centenas de notas no CNPJ errado. **CNPJ alfanumérico** (IN RFB 2.229/2024) vale aqui — a
comparação usa `somenteAlfanumerico`, nunca um limpador de dígitos, senão um CNPJ com letra passa a
comparar errado ([[project_cnpj_alfanumerico]]).

Ordem importa: nada é gravado e nada sobe para o bucket antes das 5 passarem. Um upload rejeitado não
deixa arquivo órfão.

## Campos da tela

**Lista** (uma linha por certificado da empresa, mais recente primeiro):

| Coluna | Origem |
|---|---|
| Empresa | `empresa.razao_social` |
| CNPJ do titular | `cnpj_titular` (mascarado) |
| Razão social do titular | `razao_social_titular` |
| Válido de / até | `valido_de` / `valido_ate` (dd/mm/aaaa) |
| Situação | badge derivado — ver abaixo |
| Enviado em | `criado_em` |

**Badge de situação**, calculado na hora a partir de `valido_ate` e `ativo`:

| Badge | Condição | Cor |
|---|---|---|
| **Ativo** | `ativo` e faltam > 30 dias | verde |
| **Vence em N dias** | `ativo` e faltam ≤ 30 dias | amarelo |
| **Vence em N dias** | `ativo` e faltam ≤ 7 dias | vermelho |
| **Vencido** | `valido_ate` no passado | vermelho |
| **Substituído** | `ativo = false` | cinza |

**Formulário de upload** (popup): Empresa (`<select>`, default a empresa ativa da sessão), Arquivo
`.pfx` e Senha.

O input de arquivo usa o padrão do projeto — `<input type="file">` escondido + botão próprio
disparando via `ref.click()`, porque o botão nativo renderiza texto do navegador, não do app
([[feedback_input_arquivo_customizado]]).

## Alerta de vencimento

Certificado A1 vale 1 ano. Vencer sem aviso = loja parada sem entender por quê.

- **Na tela:** o badge acima.
- **No PDV:** faltando ≤ 7 dias, um aviso não-bloqueante ao abrir a tela (`Toast` amarelo). Vencido,
  a emissão falha — e a mensagem tem que dizer que é o certificado, não "erro ao emitir".
- **Fora do produto:** 🔴 e-mail/notificação ativa fica para a F6 (depende do backoffice, que não
  existe). No v1 o alerta é só dentro do sistema — registrado como limitação consciente.

## Auditoria de uso (F7)

Todo uso do certificado grava linha em `fiscal_certificado_uso`: `finalidade`
(`ASSINATURA`/`MTLS`/`VALIDACAO`), `id_documento_fiscal` (sem FK de propósito — o log sobrevive a
qualquer coisa), `id_usuario`, `ocorrido_em`.

A tela expõe isso como um **drill-down** ao clicar na linha do certificado: últimos N usos, mesmo
padrão visual de `LancamentosCarteiraModal.tsx` (Fechamento de Caixa). Somente leitura.

## Critérios de aceitação (viram testes)

- Dado um `.pfx` válido com a senha certa, quando o ADMIN envia, então 201 e a lista passa a mostrar
  CNPJ, razão social e validade **extraídos do arquivo**.
- Dado a senha errada, quando envia, então 400 e **nada** é gravado nem sobe para o bucket.
- Dado um certificado vencido, quando envia, então 400.
- Dado um certificado cujo CNPJ titular difere do da empresa, quando envia, então 409.
- Dado um certificado já cadastrado e ativo (mesma impressão digital), quando envia de novo, então
  409.
- Dado uma empresa com certificado ativo, quando envia um segundo, então o primeiro fica
  `ativo = false` e só o novo fica ativo.
- Dado qualquer certificado, quando consulta pela API, então a resposta **não contém** o conteúdo do
  arquivo nem a senha, em nenhum campo.
- Dado um OPERADOR, quando tenta listar ou enviar, então 403.
- Dado um certificado que vence em 5 dias, quando a lista é consultada, então o badge vem vermelho e
  `diasParaVencer = 5`.
- Dado dois tenants distintos, quando um envia um certificado, então o outro não o enxerga
  (isolamento — `id_tenant` explícito, P8/F8).
- Dado um uso registrado, quando o drill-down é aberto, então a linha aparece com finalidade e data.
- Dado `niner_app`, quando tenta `DELETE` em `fiscal_certificado_uso`, então SQLState `42501`
  (privilégio negado) — caso novo em `PrivilegiosNinerAppTest`.

Cobertos por `FiscalCertificadoCrudTest` (novo). O teste usa um `.pfx` **autoassinado gerado no
setup**, nunca um certificado real versionado no repositório.

## Impacto no contrato de API

```
GET    /api/v1/fiscal/certificados?idEmpresa=      lista metadados (ADMIN). NUNCA o arquivo
POST   /api/v1/fiscal/certificados                 multipart: arquivo + senha + idEmpresa (ADMIN)
GET    /api/v1/fiscal/certificados/{id}/usos       drill-down de auditoria (ADMIN)
```

Não existe `GET /{id}/arquivo`, não existe `DELETE`, não existe `PUT`. A ausência é o design.

Toda query filtra `id_tenant` explicitamente no SQL além do RLS (P8/F8) — vazamento cruzado aqui é o
certificado de outro lojista.

## Ajuda da tela (R22 / §3.7.1)

Entrada `fiscal.certificado.tela` em `AjudaDaTela.tsx`: o que é um A1 e onde comprar, por que o CNPJ
tem que bater com o da empresa, por que o sistema nunca devolve o arquivo de volta, e o que fazer
quando o badge fica amarelo.

## Impacto no banco

`fiscal_certificado` e `fiscal_certificado_uso` já existiam (V035) desde antes desta tela. A
única mudança de schema: a coluna nasceu `senha_ref_kms` (pressupondo Secret Manager externo) e
foi renomeada para **`senha_cifrada`** quando a DF21 fechou em AES-GCM local — tabela vazia,
renomeada direto na migration dona (V035), sem migração de dado.

**Infra fora do banco, agora resolvida:** bucket fiscal privado (`niner.storage.bucket-fiscal`,
DF21) e a chave de cifra (`niner.seguranca.chave-segredos`, `comum.seguranca.SegredoCifrador`) —
ambos implementados. Falta só provisionar o bucket e a chave **reais** de produção; em dev/teste
o bucket é criado sozinho contra o emulador/fake-gcs-server, mesmo mecanismo do bucket de fotos.

## Non-goals desta feature

- **Certificado A3** (token/cartão físico) — DF5 fechou em A1 por upload. A3 exigiria driver PKCS#11
  na máquina do lojista, incompatível com SaaS.
- **Renovação automática** — não existe API pública de AC para isso; o lojista compra e sobe.
- **Notificação por e-mail de vencimento** — F6, depende do backoffice.
- **Usar o certificado** (assinar, mTLS) — é o B6. Esta tela só guarda e valida.

## Questões abertas

- ✅ **DF21 — bucket fiscal privado, FECHADA (2026-08-17).** Bucket dedicado
  (`niner.storage.bucket-fiscal`), separado do de fotos, sem leitura pública, sem `urlPublica()`
  no adapter. Ainda em aberto: **versionamento e política de retenção de 5 anos** no bucket real
  de produção (o app não impõe isso — é configuração do bucket GCS em si, fora do código).
- ✅ **Onde fica a senha, FECHADA (2026-08-17).** Opção (b) da recomendação original: AES-256-GCM
  local, chave fora do banco (`comum.seguranca.SegredoCifrador`). A coluna `senha_ref_kms` foi
  renomeada para `senha_cifrada` — não existe Secret Manager externo, e não é preciso.
- 🔴 **Emissão em dev sem certificado real.** O `FiscalCertificadoCrudTest` gera um autoassinado
  (via `keytool`, bundlado no JDK — mesmo achado do B0: nenhuma lib de terceiro necessária), mas
  o teste ao vivo no navegador precisa de um `.pfx` de verdade. Vai em `api/secrets/` (já no
  `.gitignore`), nunca no repositório e nunca colado no chat.
- 🔴 **Convenção de CN do e-CNPJ, não confirmada em fonte primária.** O extrator assume
  `CN=RAZAO SOCIAL:14DIGITOS` (padrão ICP-Brasil documentado no DOC-ICP-04 e usado por libs de
  NF-e da comunidade) e falha explicitamente (400) se não achar 14 dígitos no fim do CN — nunca
  adivinha um CNPJ errado. **Validar contra um certificado A1 real de e-CNPJ antes de produção**
  (o certificado de homologação da MITRYUSCASH, se ainda disponível, serve para isso).

## Métrica de sucesso

Nenhum certificado de lojista aparece em log, dump, resposta de API ou bucket público — e o lojista
nunca descobre que o certificado venceu por uma venda que falhou no caixa.
