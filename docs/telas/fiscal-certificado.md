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
2. **O certificado fica no BANCO do cliente, cifrado — não em bucket.** ⚠️ **DF21 revisada em
   2026-08-17** (decisão do dono do produto), invertendo o desenho anterior: o `.pfx` inteiro vai
   para `fiscal_certificado.arquivo_cifrado` (`bytea`), e a senha para `senha_cifrada` — os dois
   cifrados com AES-256-GCM (`comum.seguranca.SegredoCifrador`), chave mestra **fora do banco**
   (`niner.seguranca.chave-segredos`). Ganhos: o certificado entra no mesmo backup/restore do
   resto do tenant, e o RLS (P8) o isola por tenant sem depender de política de bucket bem
   configurada.
3. **Por que cifrar o `.pfx`, se ele já é protegido por senha.** O PKCS12 é um container cifrado,
   mas a senha é escolhida pelo lojista ou pela AC — quase sempre curta e sujeita a **força bruta
   offline**, sem limite de tentativas, por quem tiver o arquivo. Um dump do banco entregaria
   exatamente isso. Cifrado com a chave mestra, o dump sozinho não abre nem o arquivo nem a senha.
   O teste `arquivoDoCertificadoFicaCifradoNoBancoNuncaEmClaro` verifica que o conteúdo gravado
   **não** começa com a assinatura DER de um PKCS12 (`0x30 0x82`) — é o que separa "cifrado" de
   "só gravado com outro nome".
4. **O bucket privado continua existindo — para os XML.** Os buckets do ADR-013
   (`niner-erp.firebasestorage.app` / `niner-erp-dev`) são de **leitura pública** de propósito
   (marketplaces rebuscam a imagem por URL) e nunca serviriam para dado fiscal. O
   `niner.storage.privado.bucket-fiscal` (env `NINER_STORAGE_BUCKET_FISCAL`) é dos **XML
   autorizados**, que têm guarda legal de 5 anos e precisam de versionamento/retenção —
   requisitos que o bucket dá e o banco não. ✅ **Provisionado em 2026-08-17** (ADR-014): MinIO
   auto-hospedado, com Object Lock de 5 anos, `docs/infra/armazenamento-privado-minio.md`.
5. **Metadados são extraídos, nunca digitados.** CNPJ titular, razão social, `valido_de`,
   `valido_ate` e `impressao_digital` (SHA-256) saem do próprio arquivo no momento do upload. O
   lojista não digita nada além da senha — dado digitado diverge do certificado e mente.
6. **Histórico é imutável.** Certificado antigo **nunca é apagado**, só marcado `ativo = false`.
   Uma nota de 2026 foi assinada com um certificado específico, e a auditoria (F9) precisa saber
   qual. Não há `DELETE` — nem na tela, nem na API. `fiscal_certificado_uso` também não tem
   `GRANT DELETE` para `niner_app` (V035), invariante coberto em `PrivilegiosNinerAppTest`.
7. **Um ativo por empresa.** Subir um certificado novo desativa o anterior na mesma transação. Duas
   linhas `ativo = true` para a mesma empresa é estado inválido.
8. **Existe um caminho de leitura — e ele não é da web.**
   `FiscalCertificadoService.carregarAtivoParaAssinatura(idEmpresa)` devolve o `.pfx` decifrado
   para o módulo de assinatura/mTLS (B6). É `public` para o Java, **não** para a API: nenhum
   Controller o chama, e o "write-only" da tela continua valendo. Ele também recusa certificado
   vencido, com mensagem que diz ser o certificado — não um "erro ao emitir" genérico.

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

Ordem importa: nada é gravado antes das 5 passarem. Um upload rejeitado não deixa nem linha no
banco nem arquivo órfão.

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
- Dado a senha errada, quando envia, então 400 e **nada** é gravado.
- Dado um upload válido, quando consulta o banco, então `arquivo_cifrado` **não** é o `.pfx`
  original (nem começa com a assinatura DER `0x30 0x82` de um PKCS12) e `senha_cifrada` não
  contém a senha.
- Dado um certificado gravado, quando o módulo de assinatura o carrega, então o `.pfx` decifrado
  abre como PKCS12 com a senha decifrada — o ciclo completo, que é o que o B6 usa.
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

`fiscal_certificado` e `fiscal_certificado_uso` já existiam (V035) desde antes desta tela. Duas
mudanças de schema, ambas na migration dona (V035) e com a tabela **vazia**, sem migração de dado:

| Nasceu como | Virou | Por quê |
|---|---|---|
| `senha_ref_kms text` | `senha_cifrada text` | Pressupunha um Secret Manager externo que o projeto não tem; AES-GCM local resolve |
| `objeto_bucket text` | `arquivo_cifrado bytea` | DF21 revisada: o `.pfx` fica no banco, cifrado, em vez de em bucket |

**Infra fora do banco:** só a chave de cifra (`niner.seguranca.chave-segredos`) — que em dev tem
default e em produção precisa vir de variável de ambiente/secret manager, **nunca commitada**.
O bucket deixou de ser dependência desta tela (passou a ser dos XML, §11.1).

## Non-goals desta feature

- **Certificado A3** (token/cartão físico) — DF5 fechou em A1 por upload. A3 exigiria driver PKCS#11
  na máquina do lojista, incompatível com SaaS.
- **Renovação automática** — não existe API pública de AC para isso; o lojista compra e sobe.
- **Notificação por e-mail de vencimento** — F6, depende do backoffice.
- **Usar o certificado** (assinar, mTLS) — é o B6. Esta tela só guarda e valida.

## Questões abertas

- ✅ **DF21 — REVISADA em 2026-08-17 (decisão do dono do produto): certificado e XML foram
  separados.** O `.pfx` fica **cifrado no banco** do cliente; o bucket privado
  (`niner.storage.privado.bucket-fiscal`) passou a ser só dos **XML autorizados**. ✅ **O bucket
  foi provisionado em 2026-08-17 (ADR-014):** MinIO auto-hospedado no compose (e VPS quando o
  volume justificar), com Object Lock GOVERNANCE de 1825 dias e versionamento — o
  `infra/minio/bootstrap.sh` configura isso, então retenção deixou de ser passo manual de console.
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
