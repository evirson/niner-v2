# DNS de produção — `niner.com.br` (registro.br)

Documento de **pedido**: o que precisa ser configurado no registro.br para o Niner ir ao ar no
VPS. A parte de baixo é um texto pronto para enviar a quem administra o domínio.

## O que a arquitetura exige

Quatro nomes apontando para o mesmo VPS — o produto tem três fronts e **uma** API (as três
superfícies `/api/publico`, `/api/v1` e `/api/admin` são caminhos, não hosts; ADR-007):

| Nome | Serve |
|---|---|
| `niner.com.br` (apex) | site público (landing/SEO/cadastro) |
| `www.niner.com.br` | redireciona 301 para o apex |
| `app.niner.com.br` | ERP do lojista (`web/`) |
| `admin.niner.com.br` | backoffice da Vetor (`admin/`, ainda a construir) |
| `api.niner.com.br` | API (inclui o webhook do Mercado Pago) |

**Postgres, MinIO e pgAdmin não recebem nome** — ficam fechados na rede interna do VPS.

## Duas opções, e qual escolher

**Opção A — zona no próprio registro.br (mais simples, um pedido só).** O administrador cria os
registros da tabela abaixo e não precisa ser acionado de novo. O TLS será emitido por
**HTTP-01** (Let's Encrypt valida pela porta 80 de cada host), que **não exige registro TXT** —
por isso não pedimos wildcard.

**Opção B — delegar o DNS (ex.: Cloudflare).** O administrador troca apenas os *nameservers* no
registro.br, uma vez; daí em diante a equipe do produto gerencia tudo (inclusive TXT para
certificado wildcard, CDN e proteção). Melhor se a expectativa é mexer em DNS com frequência.

**✅ Decidido (2026-08-18): Opção A** — o Evirson configura direto no registro.br. A Opção B fica
registrada só como caminho futuro, se um dia aparecer necessidade de wildcard/CDN.

### Como fazer no painel do registro.br

1. Entrar em `registro.br` → **Painel** → clicar no domínio `niner.com.br`.
2. A edição de zona só aparece quando o domínio usa **os servidores DNS do próprio registro.br**
   (`a.auto.dns.br` / `b.auto.dns.br`). Se estiver apontado para DNS de terceiro (ex.: o da
   hospedagem antiga), é preciso trocar para os do registro.br antes — e essa troca **leva o
   e-mail e o site atuais junto**, então confira o que existe hoje antes de mexer.
3. Na edição da zona, cada linha é *nome + tipo + valor*. O **nome do apex fica em branco** (é o
   próprio domínio); os demais são só o rótulo: `www`, `app`, `admin`, `api` — **sem** digitar
   `.niner.com.br`.
4. O TTL no editor do registro.br costuma ser **global da zona**, não por registro; se for o
   caso, deixe o padrão — os 300 segundos da tabela são só uma preferência para a migração.
5. Salvar/publicar as alterações. A publicação costuma valer em minutos; até uma hora é normal.
6. Conferir de qualquer máquina:
   `dig +short niner.com.br app.niner.com.br admin.niner.com.br api.niner.com.br`
   — os quatro têm que devolver o **mesmo IP**. Só depois disso vale emitir os certificados
   (a validação HTTP-01 falha enquanto o nome não resolver).

## Registros (Opção A)

`⟨IP_DO_VPS⟩` é a única coisa a substituir.

| Tipo | Nome | Valor | TTL |
|---|---|---|---|
| A | `@` (apex) | `⟨IP_DO_VPS⟩` | 300 |
| A | `www` | `⟨IP_DO_VPS⟩` | 300 |
| A | `app` | `⟨IP_DO_VPS⟩` | 300 |
| A | `admin` | `⟨IP_DO_VPS⟩` | 300 |
| A | `api` | `⟨IP_DO_VPS⟩` | 300 |
| CAA | `@` | `0 issue "letsencrypt.org"` | 3600 |
| AAAA | (os mesmos nomes) | endereço IPv6, **se** o VPS tiver | 300 |

TTL 300 (5 min) durante a migração; depois de estabilizar, subir para 3600.

O **CAA** é opcional, mas barato: declara que só a Let's Encrypt pode emitir certificado para o
domínio, e fecha a porta para emissão indevida por outra autoridade.

## E-mail (só quando o provedor de envio estiver escolhido)

O produto vai disparar e-mail transacional (confirmação de cadastro, aviso de cota, cobrança).
Isso **não** é domínio novo — são registros no mesmo domínio, e sem eles a mensagem cai em spam:

- **SPF** (TXT no apex), **DKIM** (TXT em `<seletor>._domainkey`) e **DMARC** (TXT em `_dmarc`),
  com os valores que o provedor de envio fornecer;
- **MX** só se a Vetor for receber e-mail neste domínio.

⚠️ Se já existe e-mail funcionando em `niner.com.br`, **não remover o MX nem o SPF atuais**.

## Homologação (quando existir)

Mesmo padrão, com um nível a mais: `hml.niner.com.br`, `app.hml.niner.com.br`,
`api.hml.niner.com.br`. Relevante para o fiscal, que tem ambiente de homologação próprio na SEFAZ.

## Depois que o DNS propagar (do nosso lado)

1. `niner.cors.origins` (application.yml) passa a listar os três fronts em HTTPS — sem isso a API
   recusa o navegador;
2. `config.js` de cada front aponta para `https://api.niner.com.br` (é runtime: edita no servidor,
   sem rebuild — spec §3.1);
3. `NINER_MP_NOTIFICATION_URL=https://api.niner.com.br/api/publico/webhooks/mercadopago`;
4. `app.` e `admin.` saem do índice de busca (`noindex`), e só o apex tem sitemap — app indexado
   dilui o SEO da landing, que é o canal de aquisição.

---

## Texto para enviar ao administrador do domínio

> Olá! Preciso publicar o sistema **Niner** em um servidor próprio e vou precisar de alguns
> registros no DNS de **niner.com.br**, no painel do registro.br.
>
> São cinco registros do tipo **A**, todos apontando para o **mesmo IP** do servidor
> (`⟨IP_DO_VPS⟩`):
>
> | Tipo | Nome | Valor | TTL |
> |---|---|---|---|
> | A | `@` | `⟨IP_DO_VPS⟩` | 300 |
> | A | `www` | `⟨IP_DO_VPS⟩` | 300 |
> | A | `app` | `⟨IP_DO_VPS⟩` | 300 |
> | A | `admin` | `⟨IP_DO_VPS⟩` | 300 |
> | A | `api` | `⟨IP_DO_VPS⟩` | 300 |
>
> Se possível, incluir também um registro **CAA** no domínio raiz com o valor
> `0 issue "letsencrypt.org"` (TTL 3600) — ele autoriza a emissão dos certificados HTTPS.
>
> Duas observações importantes:
>
> 1. **Não é preciso mexer em e-mail.** Se já houver registros **MX**, **SPF** ou **DKIM**
>    configurados, por favor mantenha todos como estão — só estou pedindo a inclusão dos
>    registros acima. Pode me confirmar se hoje existe e-mail configurado neste domínio?
> 2. **O TTL 300** é temporário, para a migração; depois de tudo no ar, pode voltar para 3600.
>
> Se o domínio já tiver um site publicado no endereço principal, me avise **antes** — nesse caso
> o registro do `@` (raiz) substitui o destino atual, e a gente combina o melhor momento.
>
> Assim que estiver aplicado, me avise que eu confirmo a propagação e emito os certificados.
> Qualquer dúvida, estou à disposição.
