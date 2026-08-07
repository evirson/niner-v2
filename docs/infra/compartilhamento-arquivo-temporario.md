# Spec: Compartilhamento de arquivo temporário (comum.arquivocompartilhado)   Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-07 · Módulo(s): `comum.arquivocompartilhado` · Fase: 2 — Crediário/Caixa

## Problema

O dono do produto pediu, como estudo de caso, uma opção pra enviar por WhatsApp o comprovante
(PDF) de uma parcela de crediário recebida, direto da tela de Recebimento de Crediário. Não
existia até então nenhuma integração de mensageria no projeto, nem infraestrutura pra hospedar um
arquivo temporário fora do navegador do operador.

## Opções analisadas (estudo de caso, antes de codificar)

Três caminhos técnicos foram levantados e discutidos com o dono do produto:

1. **Link `wa.me`** — `https://wa.me/55DDDNUMERO?text=...` abre o WhatsApp já com uma mensagem
   pré-preenchida. **Não existe parâmetro de URL pra anexar arquivo** — só dá pra mandar um link
   de texto, nunca o PDF anexado de verdade. Zero custo, zero aprovação, zero backend de
   mensageria novo. **Escolhido.**
2. **WhatsApp Cloud API (Meta), oficial** — envia o PDF de verdade como anexo, mas exige conta
   comercial verificada, número de telefone dedicado por tenant (mesmo dilema de credenciais que
   `canal`/marketplaces já resolve), template de mensagem aprovado pela Meta pra envio proativo
   fora da janela de 24h, e custo por conversa iniciada. Rejeitado por ora — esforço de infra
   bem maior que o pedido justificava pra uma primeira versão.
3. **Automação não-oficial (Baileys/whatsapp-web.js)** — descartado: viola os Termos de Uso do
   WhatsApp, risco real de banimento do número da loja, incompatível com operação 24/7
   multi-tenant (cada tenant precisaria manter uma sessão própria "logada").

Decisões complementares confirmadas com o dono do produto:
- **Destinatário é sempre `cliente.telefone`** (rótulo "Celular" na tela de Cliente) — não o
  campo `cliente.whatsapp` (que é um handle tipo `@usuario`, não um telefone válido).
- **O PDF é gerado no navegador** (reaproveitando o mesmo `jsPDF` que já monta "Salvar PDF"), não
  reimplementado em Java no servidor — evita manter o mesmo layout em duas linguagens (o
  comprovante de crediário já passou por 3 revisões de layout; a papeleta de venda, por 6).
- **Hospedagem sem custo:** nada de bucket novo no GCS (custaria armazenamento/operação/egress,
  e o plano do projeto é Blaze pós-pago sem teto). O PDF fica **em memória, dentro do próprio
  processo da API**, associado a um token aleatório, por um tempo curto (24h por padrão) — nunca
  banco, nunca disco, nunca object storage.

## Solução implementada

### Fluxo ponta a ponta

1. Operador clica "Enviar por WhatsApp" no popup do comprovante → abre `EnviarWhatsAppModal`
   (popup de confirmação, ver seção seguinte) — **nada é gerado/enviado ainda**.
2. Operador confirma (ou corrige) o celular e clica "OK".
3. O navegador gera o mesmo PDF de sempre como `Blob` (não baixa) e sobe via
   `POST /api/v1/arquivos-compartilhados` (multipart, JWT do tenant).
4. A API valida o arquivo por magic bytes (`%PDF-`, nunca por Content-Type/extensão do cliente) e
   tamanho (≤ 5MB), guarda os bytes num `Map` em memória associados a um token UUID, devolve só o
   token.
5. O navegador monta `https://API/api/publico/arquivos-compartilhados/{token}` e um link
   `https://wa.me/55{celular}?text=...` com esse endereço dentro da mensagem, e abre essa URL
   numa nova aba — o WhatsApp Web/app abre já com a conversa e o texto prontos.
6. **Quem de fato envia é o operador**, clicando em "Enviar" na conversa. Não é uma integração
   automática — a API nunca fala com os servidores do WhatsApp.
7. Quando o cliente final clica no link (sem estar logado no sistema, é por isso que o download é
   público), `GET /api/publico/arquivos-compartilhados/{token}` devolve os bytes.

### Popup de confirmação do destinatário (`EnviarWhatsAppModal`, `web/src/components/`)

Pedido explícito, numa segunda rodada: o botão nunca dispara o envio direto. Sempre abre um popup
com o celular pré-preenchido (a partir do cadastro do cliente), mas **editável** — o operador pode
corrigir um número errado ou digitar um novo antes de confirmar. Validação (`celularValido`, 11
dígitos com DDD) acontece só ao clicar "OK", dentro do próprio popup (erro inline, mesmo padrão de
`TesteImpressaoModal.tsx`) — nunca bloqueia a abertura do popup, mesmo que o cliente não tenha
celular cadastrado.

Componente genérico (não amarrado a crediário nem a venda) — reaproveitado pelos dois comprovantes
que hoje têm essa opção (ver "Onde está ligado" abaixo) e pensado pra qualquer tela futura que
precise do mesmo fluxo.

### `ArquivoCompartilhadoService` — cache em memória

```java
record Arquivo(long idTenant, byte[] conteudo, String tipoConteudo, Instant criadoEm, Instant expiraEm)
```

- **Sem banco, sem disco, sem object storage** — só um `ConcurrentHashMap<String, Arquivo>` vivo
  dentro do processo da API. Trade-off aceito de propósito: um restart da API derruba tudo que
  ainda não expirou (aceitável pra conteúdo descartável; nunca usar isto pra algo que precise
  sobreviver a um deploy).
- **Expiração:** `niner.arquivo-compartilhado.expiracao-horas` (padrão 24h). Dois mecanismos
  independentes de limpeza:
  1. **Sob demanda** — `buscar(token)` confere a expiração antes de devolver; se já venceu, apaga
     ali mesmo e devolve vazio (404).
  2. **Varredura agendada** — `@Scheduled(fixedRate = 3_600_000)` (a cada 1h) remove qualquer
     entrada vencida de qualquer tenant, mesmo que ninguém nunca mais tente acessá-la (sem isso,
     um token gerado e esquecido ficaria ocupando memória pra sempre). Pior caso: um arquivo pode
     ficar até ~1h a mais que o prazo configurado, se ninguém tentar abri-lo antes da próxima
     varredura.
- Exigiu `@EnableScheduling` (não existia antes no projeto) e um bean `java.time.Clock`
  injetável (`Clock.systemUTC()`, em `NinerApiApplication`) — nenhum dos dois existia até aqui;
  usado também pra permitir, no futuro, testar expiração com um relógio falso (não feito ainda —
  ver "Gaps conhecidos").

### Limite de 20 arquivos por tenant — achado de segurança, corrigido na mesma sessão

Ao apresentar o desenho pro dono do produto, ele perguntou diretamente se isso poderia consumir
memória demais ou abrir brecha de invasão. Resposta honesta: **sim, havia um buraco real** — nada
limitava quantos uploads um único tenant (ou um usuário comprometido dele) podia empilhar ao mesmo
tempo. Como o cache é **compartilhado entre todos os tenants do processo**, um abuso de um tenant
só derrubaria a API (por `OutOfMemoryError`) **pra todos os tenants daquela instância**, não só pro
autor do abuso — um "vizinho barulhento" clássico.

Corrigido com um teto simples: **no máximo 20 arquivos simultâneos por tenant** —
`ArquivoCompartilhadoService.MAX_ARQUIVOS_POR_TENANT`. Ao chegar o 21º arquivo de um tenant, o mais
antigo **daquele mesmo tenant** (por `criadoEm`, não por acesso) é apagado antes de aceitar o novo.
A contagem e a expulsão olham **só** pro `idTenant` de cada entrada — não existe distinção entre
"é papeleta de venda" ou "é comprovante de crediário": os quatro fluxos que hoje usam este
mecanismo (papeleta de venda, reimpressão de papeleta de venda, comprovante de recebimento de
crediário, reimpressão de recebimento de crediário) **dividem o mesmo balde de 20 vagas por
tenant**, não 20 cada um.

Race aceita de propósito: dois uploads simultâneos do mesmo tenant podem, por uma janela
curtíssima, deixar o total em 21 em vez de 20 — não é um controle de segurança fino, é só um teto
de memória; não vale a complexidade de travar por tenant pra fechar essa janela.

## Contrato de API

```
POST /api/v1/arquivos-compartilhados          multipart, campo "arquivo" — JWT de tenant, qualquer papel
GET  /api/publico/arquivos-compartilhados/{token}   sem autenticação
```

```json
// POST → 201
{ "token": "253aee41-5247-4f29-b377-c56c0df61b61" }
```

`GET` devolve os bytes com `Content-Type: application/pdf` e
`Content-Disposition: inline; filename="comprovante.pdf"`, ou 404 se o token não existir/já tiver
expirado.

## Onde está ligado

- **Comprovante de Pagamento de Crediário** e sua **Reimpressão** (`ComprovanteRecebimentoModal.tsx`,
  mesmo componente pros dois fluxos — ver `docs/telas/comprovante-recebimento-crediario.md`).
- **Papeleta de Venda** e sua **Reimpressão** (`ComprovantePapeletaModal.tsx`, mesmo componente
  pros dois fluxos — ver `docs/telas/papeleta-venda.md`).

Os dois comprovantes ganharam, na mesma sessão, o campo `telefoneCliente` na resposta do
respectivo endpoint de comprovante (`c.telefone`, mesma coluna de sempre) — é o valor usado pra
pré-preencher o popup de confirmação.

## Layout — cabeçalho e rodapé fixos, só a pré-visualização rola

Pedido separado, mesma sessão: nos dois popups de comprovante (e suas reimpressões), o título e a
faixa de botões (Fechar/Enviar por WhatsApp/Salvar PDF/Imprimir) ficam sempre visíveis — só o
`<pre>` da pré-visualização rola por dentro quando o comprovante é longo. Mesmo mecanismo já usado
em `CancelamentoVendaModal.tsx`: o `.modal` vira uma coluna flex com `overflow: hidden`, cabeçalho
e rodapé ganham `flexShrink: 0`, e um `<div>` no meio recebe `overflow-y: auto; flex: 1; min-height: 0`.

## Verificação

- 6 testes (`ArquivoCompartilhadoCrudTest`): upload válido + download público sem autenticação;
  upload sem autenticação rejeitado; arquivo que não é PDF rejeitado por magic bytes; token
  inexistente responde 404; **21º arquivo do mesmo tenant expulsa o mais antigo (e só ele)**;
  **limite de um tenant não afeta outro tenant**.
- Suíte completa do backend (`RecebimentoCrediarioCrudTest`, `PdvCrudTest`, `ValeMercadoriaCrudTest`
  incluídos) verde depois de cada rodada de mudança. `tsc --noEmit` do `web/` limpo.
- Testado manualmente contra o banco de dev real (clientes reais com celular cadastrado).

## Non-goals desta feature

- **Envio automático sem confirmação humana** — o popup de confirmação de celular é obrigatório;
  não existe um caminho que pule direto pro `wa.me` sem essa etapa.
- **Anexo de verdade no WhatsApp** — é sempre um link, nunca o arquivo anexado (limitação do
  `wa.me`, não do sistema — ver "Opções analisadas").
- **Persistência do arquivo além da expiração** — não existe reenvio automático nem histórico de
  "o que já foi compartilhado"; se o link expirar, é preciso gerar de novo.

## Gaps conhecidos (não implementados nesta sessão)

- 🟡 **Sem teto de memória total do cache** (só por tenant) — em teoria, muitos tenants pequenos
  simultâneos ainda somam bastante memória. Não pedido; considerar se o volume de tenants crescer.
- 🟡 **Nenhum log de quem enviou o quê** — se houver abuso do upload como "hospedagem anônima
  temporária" (qualquer coisa que comece com `%PDF-` é aceita, o conteúdo não é validado como PDF
  bem-formado), não há rastro pra investigar depois.
- 🟡 **Expiração não testada com `Clock` mockado** — o projeto não tinha nenhum precedente de
  `Clock`/tempo controlado em teste; a garantia de "expira depois de 24h" fica só na leitura do
  código, não em teste automatizado com o tempo adiantado.

## Métrica de sucesso

Operador consegue enviar o comprovante por WhatsApp em menos de 5 segundos depois de confirmar o
celular, sem nenhuma configuração prévia (número de WhatsApp da Vetor, aprovação de template, etc.).
