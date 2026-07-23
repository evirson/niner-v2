# Progresso do Projeto — niner-v2

Registro cronológico das decisões e entregas. Atualizar a cada marco relevante.
**Última atualização:** 2026-07-23

---

## Estado atual

Projeto **spec-driven** em fase de fundação, com **sete telas de cadastro completas e
ponta a ponta**: Clientes (`cadastros.cliente`), Funcionários (`cadastros.funcionario`), Plano
de Contas (`cadastros.planocontas`), Fornecedores (`cadastros.fornecedor`), **Produtos**
(`catalogo.produto`, 2026-07-22 — primeiro corte vertical do núcleo do catálogo, com categoria
N:N ordenada, NCM como referência global e regras de precificação/oferta) e, do novo módulo
**`financeiro`** (2026-07-23), **Moeda** e **Tipo de Carteira** (`financeiro.moeda`/
`financeiro.tipocarteira` — crediário/cartão antecipados da Fase 2, Q5/ADR-010/ADR-012; Tipo
de Carteira gerencia embutido o N:N com moeda), mais a primeira tela de **configuração de
sistema** — Parâmetros do Sistema (`configuracao.geral`, 2026-07-21, singleton por tenant,
ADMIN-only, fora do padrão de cadastro). A **API (Spring Boot 4 / Java 25)** sobe com 3
superfícies + infra de contexto de tenant; o schema completo (control-plane + domínio do
lojista, V001–V027) está criado, revisado e com RLS validado. Falta a camada de domínio dos
demais módulos (variação/estoque/pedido) e o app `admin/`.
**Convenções de UI novas (2026-07-22), valem para todo campo do sistema daqui pra frente:**
campo decimal (moeda/percentual/peso) com digitação natural (inteiro primeiro, vírgula abre
decimais, completa só no `onBlur`) e campo de data como texto mascarado `dd/mm/aaaa` (não
`<input type="date">` — ver §3.7 da spec).

| Artefato | Situação |
|---|---|
| `spec-driven-erp-varejo.md` | **v2.0 — pivô SaaS multi-tenant** (Constituição P1–P9 + PRD R1–R21 + plano técnico + control-plane + migrations) |
| `docs/PLANO-DE-NEGOCIO.md` | **Novo** — plano de negócio (planos/preços, trial, funil, métricas SaaS, roadmap, decisões D1–D10) |
| `docs/padroes/` | Mockup de referência de UI (golden file, §3.7) — `TELA.rar` descompactado e removido |
| `docs/infra/armazenamento-imagens.md` | Object storage das fotos de produto (ADR-013). Infra no GCP **provisionada e testada**; **código Java implementado em 2026-07-23** (`comum.armazenamento` + `catalogo.ProdutoImagemService`, ver linha do tempo) — só falta credencial real (Opção A, ADC pessoal do Claudio) pra upload funcionar de ponta a ponta fora dos testes |
| `db/*.txt` | Schema **legado (Firebird)** versionado como referência (31 tabelas + generators, procedures, triggers) |
| `CLAUDE.md` | Guia do repositório — atualizado para o SaaS multi-tenant (P8/P9, plataforma, `id_tenant`+RLS) |
| `docker-compose.yml` | Infra local de dev: `db` (postgres:18, `niner_db`) + `flyway` (profile `migrate`) + **`api`** (Spring Boot, porta 8080, conecta como `niner_app`); **V001–V026 aplicadas e validadas em banco real** (control-plane + domínio do lojista + financeiro parcial + RLS) — banco **recriado do zero em 2026-07-16** (volume `niner_pgdata` apagado e refeito) |
| `db/migration/V013–V024` | Domínio do lojista (identidade, cadastros, catálogo com `sku`+`ean`, estoque com `reservado`/`disponivel`, vendas, canais, pedidos, integração/outbox, cfg_geral) + **RLS de domínio** (`FORCE` + política por `id_tenant`). **Gate P8 verde** (teste de isolamento cross-tenant automatizado). **Revisado em 2026-07-16** (ver linha do tempo): tipos padronizados (`id_tenant SMALLINT`, demais PKs `INTEGER`), sem `ON DELETE CASCADE`, ledger de estoque imutável via `REVOKE`, e-mail case-insensitive, fix de bootstrap (`GRANT CREATE ON SCHEMA public`) |
| `db/migration/V025` | **`financeiro` parcial (revisão de Q5/ADR-010, ADR-012):** crediário (`tipo_carteira`, `moeda`, `moeda_detalhe`, `contas_receber`/`contas_receber_detalhe`) + caixa (`caixa_mestre`/`caixa_detalhe`). RLS próprio no arquivo (V024 já tinha rodado). Seed de `moeda` por tenant implementado no `SignupService`. **Aplicada e validada em banco real em 2026-07-16** (RLS `ENABLE`+`FORCE` confirmado nas 7 tabelas; moedas semeadas no signup conferidas via `psql`). |
| `db/migration/V026` | **`contas_pagar`** (mais uma revisão de Q5/ADR-010/ADR-012): PK `id_conta_pagar` (renomeada de `localizador`), `nota_fiscal integer` nullable sem `DEFAULT 0` (padronização que também corrigiu `produto_movimento_mestre.nota_fiscal`, V019, de `text` para `integer`). RLS próprio no arquivo. Só `conta_corrente*` segue fora do v1. **Aplicada e validada em banco real em 2026-07-16** (schema/FKs/RLS conferidos via `psql`) |
| `db/migration/V027` | **`cfg_tela_campo`** (novo, 2026-07-21) — configuração por tenant de campos visíveis/obrigatórios por tela (`chave_tela`), reutilizável para qualquer tela futura. RLS próprio no arquivo. **Migration aditiva** — aplicada sem recriar o banco (`docker compose run --rm flyway`, só essa migration rodou). |
| `api/` | Spring Boot 4.0.7 / Java 25 (Maven). 3 superfícies com `SecurityFilterChain` separados; `TenantContext` (`ScopedValue`) + `TenantAwareTransactionManager`; **auth JWT HS256** (login/signup emitem, `/api/v1` valida `aud=tenant`); **trial self-service** (`POST /api/publico/assinar` → tenant+configs+moedas+ADMIN+assinatura TRIAL + token), `POST /api/publico/login`, `GET /api/v1/eu`. **Módulo `cadastros.cliente` (2026-07-20/21):** CRUD completo de `GET/POST/PUT/DELETE /api/v1/clientes` + `GET/POST/PUT /api/v1/categorias-cliente`, validação de CPF/CNPJ (dígito verificador + duplicidade — **CNPJ alfanumérico desde 2026-07-21**, ver linha do tempo), normalização de texto para maiúsculas, celular/WhatsApp (11 dígitos + 3º=9), nascimento opcional (só não pode ser futuro), exclusão com fallback para inativar quando há venda associada. **Listagem ordenada por `nome` (ou pela coluna pedida), paginação por número de página** (2026-07-21, era por `id_cliente`/cursor) — `GET /api/v1/clientes?pagina=&limite=&ordenarPor=&direcao=` com `LIMIT/OFFSET` + contagem total + `ORDER BY` dinâmico (allowlist de colunas). **Validação de servidor reforçada (2026-07-21):** além do dígito verificador de CPF/CNPJ, agora também formato de e-mail/celular/WhatsApp/CEP e a obrigatoriedade configurável por tenant (`cfg_tela_campo`) — antes só o frontend checava isso. **Módulo `cadastros.funcionario` (novo, 2026-07-21):** CRUD completo de `GET/POST/PUT/DELETE /api/v1/funcionarios` replicando o padrão do cliente (paginação por página, ordenação com allowlist, validação de CPF/celular/% comissão, obrigatoriedade configurável, exclusão com fallback para inativar quando há movimentação de estoque vinculada). Reaproveita `Documentos` (tornado público) para o dígito verificador do CPF; **CPF não é único** aqui (decisão de V016/§3.3.9, oposto do cliente). **Módulo `cadastros.planocontas` (novo, 2026-07-21):** CRUD de `GET/POST/PUT/DELETE /api/v1/planos-contas` — PK de negócio `text` (código contábil digitado pelo usuário, imutável após criar), exclusão **sem** fallback de inativar (tabela sem `ativo` — 409 com vínculo em fornecedor/contas_pagar), `tipoMovimento` validado contra o ENUM acentuado (CRÉDITO/DÉBITO/NEUTRO), busca única código-ou-descrição; sem registro em `cfg_tela_campo` (todos os campos NOT NULL, nada configurável). **Módulo `cadastros.fornecedor` (novo, 2026-07-21):** CRUD de `GET/POST/PUT/DELETE /api/v1/fornecedores` — mesmo padrão de Cliente/Funcionário (obrigatoriedade configurável, exclusão com fallback para inativar quando há movimentação de estoque ou conta a pagar vinculada); CNPJ sempre 14 caracteres (pessoa jurídica — CPF é rejeitado), telefone aceita fixo ou celular (10–11 dígitos, mais frouxo que a regra do cliente); `idPlanoContas` inexistente vira 400 amigável em vez de 500; listagem com filtro por plano de contas. Sem alteração de schema (tabela completa desde V016/V024). **Módulo `configuracao.geral` (novo, 2026-07-21):** `GET/PUT /api/v1/config-geral` sobre o singleton `cfg_geral` (V023, semeado no signup) — sem POST/DELETE; **somente ADMIN, inclusive na leitura** (diferente de `comum.telaconfig`, onde qualquer papel lê); validação só de faixa (percentuais 0–100, dias ≥ 0), já que a tabela inteira é NOT NULL. **Módulo `comum.telaconfig` (novo, 2026-07-21):** `GET/PUT /api/v1/config-tela/{chaveTela}` — quais campos aparecem/são obrigatórios por tenant, reutilizável entre telas (já com 3 telas registradas: `cadastros.cliente.form`, `cadastros.funcionario.form` e `cadastros.fornecedor.form`); só ADMIN grava (403 para OPERADOR, checado a partir do claim `roles` do JWT); leitura filtrada por `id_tenant` explicitamente (defesa em profundidade, além do RLS). **69 testes verdes** (Testcontainers: `ClienteCrudTest` 17, `FornecedorCrudTest` 12, `FuncionarioCrudTest` 10, `PlanoContasCrudTest` 8, `ConfiguracaoGeralTest` 6, `ConfiguracaoTelaTest` 5, + suíte anterior) + fluxo **verificado ao vivo**. Persistência: **Spring Data JDBC**. **Módulo `catalogo.produto` (novo, 2026-07-22 — docs/telas/produto.md):** CRUD de `GET/POST/PUT/DELETE /api/v1/produtos` — categorias N:N com ordenação (`produto_categoria.indice` derivado da posição da lista recebida, substituição total a cada save), `nomeVarianteLinha`/`nomeVarianteColuna` forçados a `null` quando a flag correspondente de `cfg_geral` está desligada, regra da oferta tudo-ou-nada (início/final/preço, com data no passado/ordem/preço `< venda` validados), peso líquido ≤ peso bruto, exclusão com fallback para inativar quando há variação/imagem vinculada. **Módulo `catalogo` auxiliar:** `GET/POST/PUT /api/v1/categorias-produto` (mesmo padrão de categoria de cliente); `GET /api/v1/ncm/{codigo}` (consulta de `cfg_produto_ncm`, tabela global sem RLS, 404 amigável); `GET /api/v1/config-geral/flags-variante` (aberto a qualquer papel, diferente do resto de `cfg_geral`). **Gerador de EAN-13 interno (novo, 2026-07-22):** função SQL `gerar_ean13_interno()` + tabela de controle `cfg_ean_gerador` (GLOBAL, sem `id_tenant`/RLS — sequencial único compartilhado por todos os tenants desta instância de banco; `id_banco` distingue instâncias diferentes se houver *sharding* no futuro); vai popular `produto_barra.sku` **sempre** (nunca digitado) quando o serviço de variação for construído — hoje só a rotina existe, nada chama automaticamente ainda (decisão de acionamento — Java explícito vs. trigger — adiada a pedido do dono do produto). **91 testes verdes** no total (`ProdutoCrudTest` 20 + `EanGeradorTest` 2, novos). Falta: domínio de variação/SKU (`produto_barra`), imagens (`produto_imagem`) e estoque/pedido. **Módulo `financeiro` (novo, 2026-07-23):** `MoedaController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/moedas`, sem `ativo`, exclusão bloqueia com 409 se vinculada a `moeda_detalhe`/`caixa_detalhe`; `percDesconto`/`percAcrescimo` opcionais — nunca os dois com valor positivo ao mesmo tempo, checagem por valor > 0 não por presença) e `TipoCarteiraController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/tipos-carteira`, gerencia embutido o N:N `moeda_detalhe` sem índice — apaga e reinsere a cada save; `taxaAdministradora` opcional, prazo/taxa aceitam 0). Filtro `id_tenant` explícito em toda consulta dos dois serviços (ambiente de teste conecta como superusuário, RLS não filtra sozinho ali). **113 testes verdes** no total (`MoedaCrudTest` 11 + `TipoCarteiraCrudTest` 11, novos). **Módulo `comum.armazenamento` + galeria de fotos de produto (novo, 2026-07-23 — ADR-013):** `ArmazenamentoDeArquivos` (interface) + `GcsArmazenamento` (adapter GCS, cliente `Storage` `@Lazy` — API sobe sem credencial de GCS configurada); `ProdutoImagemController/Service` (`POST/DELETE/PUT /api/v1/produtos/{id}/imagens...`) valida por **magic bytes** (nunca extensão/Content-Type do cliente), normaliza pra **WebP de verdade** (`org.sejda.imageio:webp-imageio`, redimensiona ≤1600px), aplica o **máximo de 6 fotos por produto** (regra de produto), exclusão renumera índices, reordenação; falha de storage sem credencial vira 503 com mensagem clara. **119 testes verdes** no total (`ProdutoImagemCrudTest` 6, novos, contra `fake-gcs-server` via Testcontainers — nenhum teste toca o bucket real) |
| `site/` | Site público (Astro/SSG, ADR-011). **Home institucional "matadora"** (posicionamento concorrente do Bling): hero com painel animado + demo de sincronização, faixa de stats com contadores, contraste problema→solução, 3 passos, 6 recursos, canais (ML/Shopee/Amazon/balcão), planos (preços via `/api/publico/planos`), FAQ e CTA — tudo em CSS/SVG puro com **scroll-reveal** e **prefers-reduced-motion** (sem novas deps). Sistema visual em `src/styles/site.css` portado do golden `nainer_institucional`. `/assinar` (form → `POST /api/publico/assinar` → auto-login → `/bem-vindo`) e `/bem-vindo` mantidos. **Trial 60 dias** em toda a copy. Tema claro/escuro persistido. **Build SSG ok**; hero/reveal/contadores verificados via Playwright |
| `web/` | ERP do lojista (React 19 + Vite + TS). Auth JWT (login slug+email+senha; **handoff SSO** do site via `#token=`), shell (nav Painel/Produtos/Estoque/Pedidos/Canais/**Clientes** + Sair), **Painel** real (`GET /api/v1/eu` via TanStack Query). **Tela de Clientes completa** (2026-07-20/21): ícone de identificação (pessoa) à esquerda do título, listagem com busca em maiúsculas/filtro/**paginação fixa em 50 itens, sem seletor** (janela deslizante de páginas com primeira/anterior/próxima/última, estilo inspirado no sistema legado)/**ordenação por coluna** (cabeçalho em destaque, ícone "⇅"/"▲"/"▼" em cada uma), **três ícones de ação por linha** (visualizar verde/editar azul/excluir vermelho, sem texto — visualizar abre `/clientes/:id/visualizar` em modo somente-leitura), grid mais compacta, formulário com cabeçalho enxuto ("Cliente" + Cancelar/Salvar no topo, topo fixo/só o corpo rola) e grid de 12 colunas (§3.7) largura total (`.app-main` 1600px), máscaras com validação de dígito verificador/formato/duplicidade (inclusive **CNPJ alfanumérico** e **limite de crédito em moeda**), validação por campo (blur + submit, replicada no backend — 2026-07-21), pop-up de erro vermelho (`Toast.tsx`), autopreenchimento de endereço via ViaCEP, modal embutido de categoria, exclusão com confirmação em modal próprio, `AjudaDaTela` (R22), convenções de **maiúsculas sempre** e **foco automático**. **Tela de configuração de campos** (`ConfiguracaoTelaCliente.tsx`, `/clientes/configuracao`, só `ADMIN` — `RequireAdmin.tsx`): cabeçalho enxuto com Cancelar/Salvar no topo (fixo, 2026-07-21), acessível pelo ícone ⚙ ao lado da ajuda; o formulário de Cliente lê essa config (`lib/configuracaoTela.ts`) e ajusta visibilidade/obrigatoriedade dos campos em tempo real. **Tela de Funcionários completa (nova, 2026-07-21):** `pages/funcionarios/` (lista + formulário + configuração de tela), replicando integralmente o padrão do cliente — ícone próprio (maleta), ordenação por coluna, paginação fixa em 50, três ícones de ação, modo somente-leitura, máscara de percentual para "% Comissão". **Tela de Plano de Contas completa (nova, 2026-07-21):** `pages/planocontas/` (lista + formulário; sem tela de configuração — nada configurável), código contábil como PK de negócio nas rotas (`/planos-contas/3.1.001`), Código bloqueado na edição, sem filtro de status (tabela sem `ativo`), ícone próprio (prancheta). **Tela de Fornecedores completa (nova, 2026-07-21):** `pages/fornecedores/` (lista + formulário + configuração de tela), com um mecanismo novo — `PlanoContasModal.tsx`, criação rápida de plano de contas embutida no formulário (botão "＋ Novo" ao lado do select de Plano de Contas, mesmo papel do modal de categoria do cliente); filtro por plano de contas na listagem; ícone próprio (caminhão). **Parâmetros do Sistema (nova, 2026-07-21):** `pages/configuracaogeral/ConfiguracaoGeralForm.tsx` — primeira tela **fora** do padrão de cadastro: sem listagem/paginação/busca/modo somente-leitura/`InfoRegistro` (a tabela `cfg_geral` é singleton por tenant, sem `criado_em`); item de menu ("Parâmetros do Sistema") e a própria rota só aparecem/funcionam para ADMIN; ícone próprio (`IconeParametros`), deliberadamente diferente da engrenagem (⚙) usada como atalho de "configurar campos" em cada cadastro. **Campos informativos de auditoria** (`InfoRegistro.tsx` + `lib/datas.ts`, 2026-07-21): Código/Cadastrado em/Última alteração, somente leitura, no fim de todo formulário de cadastro (em Cliente, Funcionário, Plano de Contas e Fornecedor; o `codigo` aceita PK numérica ou texto). Demais áreas (Produtos/Estoque/Pedidos/Canais) ainda placeholder. **Shell do ERP com altura travada no viewport** (2026-07-21, convenção nova — `Layout.tsx`/`styles.css`): menu lateral e cabeçalho fixos, sem scroll de página inteira; `html`/`body`/`#root` com `overflow: hidden` (2026-07-21 — sem isso, qualquer 1px de folga faz o documento inteiro rolar) e `.app-main`/`.table-wrap` com altura própria fazendo o scroll de verdade, para o cabeçalho `position: sticky` das tabelas grudar no lugar certo; a tela de Clientes usa `.lista-tela`/`.lista-topo`/`.lista-corpo`/`.lista-rodape` para travar também a barra de filtros e o rodapé de paginação, deixando só a tabela com scroll próprio. Barra de rolagem no padrão de cores do tema (claro/escuro). Design tokens §3.7. **Build ok**; fluxo **e2e verificado no navegador**. **Tela de Produtos completa (nova, 2026-07-22 — `pages/produtos/`):** lista + formulário + configuração de tela, mesmo padrão; categorias com setas ▲/▼ de reordenação + modal de gestão embutida (`CategoriaProdutoModal.tsx`); seção "Dimensões e Variantes" só mostra nome de variante quando a flag correspondente de Parâmetros do Sistema está ligada; NCM com máscara `9999.99.99` e busca automática de descrição ao lado; preço de venda/% de venda recalculados ao vivo um a partir do outro; layout final: "Produto ativo" em linha própria, Descrição+Marca juntos, Referência+NCM+descrição do NCM juntos, os 6 campos de Preços numa única linha, Peso Bruto/Líquido+variantes numa única linha. **Convenções novas que valem pro sistema inteiro (2026-07-22):** campo decimal (moeda/percentual/peso) com digitação natural (`lib/masks.ts#mascararMoeda/mascararPercentual/mascararPeso` + `completar*` no `onBlur`) substituindo a antiga leitura de dígitos da direita; campo de data como texto mascarado `dd/mm/aaaa` (`mascararData`, `onFocus` com `.select()`) substituindo `<input type="date">` em Cliente (nascimento) e Produto (início/final de oferta) — o nativo não permite "selecionar tudo e sobrescrever ao digitar" em nenhum navegador. Ícone próprio (caixa/pacote). **Confirmação antes de salvar via Enter (2026-07-23, `ConfirmarSalvarModal.tsx` + `lib/formularios.ts`):** nas telas de cadastro, Enter num campo de texto abre "Salvar dados?" em vez de submeter direto; clique no botão "Salvar" continua instantâneo. **Telas de Moeda e Tipo de Carteira completas (novas, 2026-07-23 — `pages/moeda/`, `pages/tipocarteira/`):** mesmo padrão de cadastro; Tipo de Carteira gerencia embutido o checklist de moedas (`moeda_detalhe`, sem reordenar) com criação rápida de moeda (`MoedaModal.tsx`, mesmo papel do `PlanoContasModal`) e ícones por moeda no checklist (`IconeDaMoeda`, heurística por palavra-chave — cartão/PIX/genérico); % Desconto/% Acréscimo de Moeda são mutuamente exclusivos (digitar valor > 0 num limpa o outro); Taxa Administradora de Tipo de Carteira é opcional. Ícones próprios (cifrão/carteira) e itens no menu. **Galeria de fotos em Produtos (nova, 2026-07-23 — ADR-013, `GaleriaImagensProduto.tsx`):** seção "Fotos (N/6)" no formulário — miniaturas com setas de reordenar + lixeira de excluir, botão "＋ Adicionar foto" (desabilita ao atingir 6), oculta no modo somente-leitura, aviso "salve o produto primeiro" em produto novo (upload precisa de `idProduto` de verdade). Upload é `multipart/form-data` — `lib/api.ts` ganhou `apiUpload()` (nunca define `Content-Type` manualmente, o navegador precisa gerar o boundary sozinho). |
| `admin/` | Ainda não criado (backoffice React 19 + Vite) |

**Stack alvo:** Java 25 + Spring Boot 4.x · PostgreSQL 18 (Docker, banco **`niner_db`**) · React 19 + Vite (3 apps) · Flyway · JWT. **SaaS multi-tenant** (banco único + `id_tenant` + Postgres RLS).

---

## Linha do tempo

### 2026-07-23 — Modo dev SEM credencial para fotos de produto (fake-gcs no compose, Opção C do handoff)

O Claudio ficou travado na credencial do GCS (Opção A concedida, mas o ADC não destravou na
máquina dele) justamente na reta final da galeria de fotos. Solução: eliminar a credencial do
caminho de desenvolvimento. O docker-compose ganhou o serviço **`fake-gcs`**
(`fsouza/fake-gcs-server:1.49`, porta 4443, backend filesystem + volume `fake-gcs-data` —
imagens sobrevivem a restart), e a API a propriedade **`niner.storage.host`**
(`NINER_STORAGE_HOST`): preenchida, `ArmazenamentoConfig` constrói o cliente com
`NoCredentials` apontando pro emulador e cria o bucket sozinho; vazia (default), tudo como
antes (GCS real via ADC/chave — staging/prod intactos). A `base-url` herda o host
automaticamente (`${NINER_STORAGE_BASE_URL:${NINER_STORAGE_HOST:...}}`), então a URL pública
que a galeria exibe já sai do emulador. Receita completa na **Opção C** de
`docs/infra/armazenamento-imagens.md` §3 (2 comandos). Validado de ponta a ponta em banco
real: login → `POST /api/v1/produtos/2/imagens` (multipart) → objeto criado no emulador →
`GET http://localhost:4443/niner-erp-dev/tenants/1/produtos/2/<uuid>.webp` → **HTTP 200
`image/webp`**. O `ProdutoImagemCrudTest` já usava o mesmo emulador — o modo dev só
reaproveita em runtime o que o teste provou.

### 2026-07-23 — Galeria de fotos de produto implementada (ADR-013) + setup de credencial GCS em andamento

Pedido do dono do produto: começar a implementação do handoff de object storage
(`docs/infra/armazenamento-imagens.md`), com a manutenção de fotos ficando **dentro da tela de
Produtos** (não uma tela separada) e **máximo de 6 fotos por produto**.

1. **Backend — `comum.armazenamento` (novo):** `ArmazenamentoDeArquivos` (interface,
   `gravar`/`apagar`/`urlPublica`) + `GcsArmazenamento` (adapter de verdade,
   `com.google.cloud:google-cloud-storage` via `libraries-bom`). O cliente `Storage` é
   injetado como bean **`@Lazy`** (`ArmazenamentoConfig`) — só autentica no primeiro uso real,
   então a API sobe normalmente mesmo sem credencial de GCS configurada (era o caso aqui: sem
   `gcloud`/ADC na máquina até este ponto da sessão).
2. **Backend — `catalogo.ProdutoImagemController/Service/Dtos` (novo):**
   `POST/DELETE/PUT /api/v1/produtos/{id}/imagens...`. Valida o arquivo por **magic bytes**
   (JPEG/PNG/WebP — nunca pela extensão ou `Content-Type` que o cliente manda), normaliza
   redimensionando pro maior lado ≤ 1600px e recodificando pra **WebP de verdade** — bate com
   o contrato original do handoff (chave `.webp`). Precisou de `org.sejda.imageio:webp-imageio`
   (registra o writer WebP via SPI; ImageIO puro não grava WebP sozinho) além do
   `net.coobird:thumbnailator` — **testado e funcional dentro do container Linux** (achado
   relevante: a lib usa uma biblioteca nativa via JNI, cogitei que pudesse não funcionar fora
   do Windows, mas funcionou de primeira). **Máximo de 6 fotos por produto** (regra de produto,
   checada na aplicação — não dá pra expressar "contar irmãos" num `CHECK` sem trigger).
   Exclusão de uma foto renumera os índices restantes pra 0..n-1 (prova matemática de que dá
   pra fazer isso num único passe ascendente, sem índice temporário, no comentário do código);
   reordenação usa índices negativos temporários primeiro (a `UNIQUE` é checada por statement,
   não no commit — uma troca direta colidiria no meio da transação).
3. **Erro de storage vira mensagem clara:** sem credencial de GCS, uma tentativa de gravar
   lançava `StorageException` cru, que o Spring Security (`ExceptionTranslationFilter`)
   traduzia num 403 sem corpo — enganoso (parecia problema de autenticação da API, não do
   GCS). Agora `GcsArmazenamento` traduz pra **503 com mensagem explícita** apontando pro
   handoff (`docs/infra/armazenamento-imagens.md §3`).
4. **Testes — `fake-gcs-server` via Testcontainers (`FakeGcsConfiguration`, novo):** bean
   `Storage` de teste (`@Primary`, nome de bean diferente do `@Lazy` de produção — mesmo nome
   nos dois seria *bean definition override*, bloqueado por padrão, não resolvido só por
   `@Primary`) apontando pro container fake, bucket criado automaticamente. **6 testes novos**
   (`ProdutoImagemCrudTest`): upload com URL pública, 7ª foto rejeitada, arquivo inválido
   rejeitado (magic bytes), exclusão renumera, reordenação, isolamento de tenant. **119 testes
   verdes** no total. Nenhum teste toca o bucket real.
5. **Frontend — `GaleriaImagensProduto.tsx` (novo) embutido em `ProdutoForm.tsx`:** seção
   "Fotos (N/6)" com miniaturas + setas de reordenar + lixeira, "＋ Adicionar foto" desabilitado
   ao atingir 6 ou em modo somente-leitura, aviso "salve o produto primeiro" em produto novo.
   `lib/api.ts` ganhou `apiUpload()` (upload `multipart/form-data` — não pode forçar
   `Content-Type: application/json` como o `api()` normal faz, o navegador precisa gerar o
   boundary sozinho).
6. **Setup de credencial real (em andamento, não concluído nesta sessão):** o Evirson já
   tinha concedido ao Google do Claudio (`claudiocalixto6969@gmail.com`) `roles/storage.objectAdmin`
   no bucket `niner-erp-dev` (Opção A do handoff, ver commit `aeb6b6e`). Confirmado com o
   Evirson (repassado pelo dono do produto): Opção A cobre tudo **desde que a API rode fora do
   Docker** (`./mvnw spring-boot:run`) — dentro do container não existe `~/.config/gcloud` do
   host, aí sim precisaria da Opção B (chave de conta de serviço). Nesta sessão:
   `winget install Google.CloudSDK` instalado com sucesso; `gcloud auth application-default
   login` precisou de duas correções (sessão de terminal antiga não via o PATH novo — precisa
   caminho completo pro executável; o script `gcloud` tentava usar o `python` de um atalho
   quebrado da Microsoft Store — precisa `CLOUDSDK_PYTHON` apontando pro Python que vem junto
   do SDK, `platform/bundledpython/python.exe`). Login relatado como concluído, mas o arquivo
   `application_default_credentials.json` **não apareceu** em
   `%APPDATA%\gcloud\` — troubleshooting pausado no meio, próximo passo é repetir o login com
   `--no-launch-browser` (fluxo copiar link/colar código) pra ver a saída completa do comando.
7. **Docker/config prontos pra quando a credencial existir:** `docker-compose.yml` já monta
   `./api/secrets:/secrets:ro` e define `GOOGLE_APPLICATION_CREDENTIALS=/secrets/gcs-niner-erp.json`
   (Opção B); `api/secrets/LEIA-ME.txt` criado localmente (gitignored) explicando as duas
   opções. `application.yml` ganhou `niner.storage.bucket/base-url` (default `niner-erp-dev`).

### 2026-07-23 — Spec de Entrada de Mercadorias iniciada (`docs/telas/entrada-mercadoria.md`, RASCUNHO)

Início do módulo `estoque` pela porta de entrada: spec da tela de **entrada de mercadorias**
(importação de XML NF-e modelo 55 + lançamento manual + fluxo de planilha modelo), escrita a
quatro mãos e **pausada no meio da discussão** — o rascunho está completo estruturalmente
(tabelas V019/V026 mapeadas, mapeamento XML→banco, contrato de API preliminar, critérios de
aceitação), com as decisões já fechadas e as pendências registradas num bloco "Registro da
discussão" no topo do próprio arquivo. Destaques do que já foi decidido: os três fluxos
convergem para a mesma conferência/confirmação (1 mestre `COMPRA` + N detalhes `C`; saldo por
trigger de V019, nunca via Java); XML idempotente pela chave de acesso (novas colunas
`chave_nfe`/`serie` no mestre + tabela `entrada_xml` para o payload bruto — aprovadas, viram
V028+); duplicatas do XML geram `contas_pagar`; rateio de frete/IPI/ST e reajuste de
custo/preço serão configuráveis; correção de entrada confirmada permite edição direta **e**
estorno, ambos com auditoria de UPDATE/DELETE (P3); haverá vínculo produto×fornecedor (match
por `cProd` + conversão de unidade) e tabela de ligação usuário↔funcionário. Pendências da
retomada (desenho físico das tabelas novas, onde mora a configuração, política de divergência
de `vNF`, detalhamento da planilha) listadas no mesmo bloco. Nenhuma linha de código ainda —
spec-driven (golden rule).

Operacional do dia: ambiente local religado (conflito de porta 5432 com `finance-v-db`
resolvido parando o outro container; `flyway repair` + `migrate` até V027 — checksums
V006–V010 divergiam por edições antigas; `npm install` no `web/`). Banco estava vazio: criada
loja de teste via `POST /api/publico/assinar` — slug `loja-teste`, `teste@teste.com` /
`teste1234` (tenant 1, trial até 2026-09-21).

### 2026-07-23 — Sexta e sétima telas de domínio: Moeda e Tipo de Carteira (módulo `financeiro`)

Pedido do dono do produto: telas de manutenção para `tipo_carteira`/`moeda`/`moeda_detalhe`
(V025, crediário/cartão antecipados da Fase 2 — Q5/ADR-010/ADR-012), que tinham schema desde
2026-07-16 mas nenhuma tela. As três tabelas têm relacionamento entre si (`moeda_detalhe` é
N:N entre `moeda` e `tipo_carteira`) — a primeira decisão foi **como distribuir isso em telas**.

1. **Desenho da tela discutido antes de codar (`AskUserQuestion` + ida e volta):** a proposta
   inicial (Claude) era 2 telas com o N:N embutido em Moeda (espelhando `produto_categoria` em
   Produto). O dono do produto sugeriu o oposto: **uma tela só**, com tudo dentro de Tipo de
   Carteira (inclusive criar Moeda ali na hora). Acordo final: **2 telas, mas o N:N mora em
   Tipo de Carteira** — Moeda precisa de tela própria porque tem campos editáveis
   (`% desconto`/`% acréscimo`) e já nasce com 7 linhas semeadas no signup (não dá pra só
   criar via modal, tem que dar pra editar depois); Tipo de Carteira nasce vazio e é o lado que
   mais cresce (cada combinação de prazo/parcela/taxa é um registro novo), então faz mais
   sentido ele "puxar" quais moedas usa, com **criação rápida de moeda embutida**
   (`MoedaModal.tsx`) se a que falta ainda não existir — mesmo papel do `PlanoContasModal` no
   Fornecedor.
2. **Backend — módulo novo `financeiro`** (pacote existia vazio desde a criação do domínio):
   `MoedaController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/moedas`) e
   `TipoCarteiraController/Service/Dtos` (`GET/POST/PUT/DELETE /api/v1/tipos-carteira`, este
   último gerenciando `moeda_detalhe` embutido — apaga tudo e reinsere a cada save, sem índice
   porque a relação não tem ordem, diferente de `produto_categoria`). Sem coluna `ativo` em
   nenhuma das duas tabelas → exclusão sem fallback de inativar (409 com vínculo).
3. **Schema:** `criado_em`/`atualizado_em` adicionados em `tipo_carteira`/`moeda` (V025, não
   tinham) — mesma convenção de auditoria do resto do domínio.
4. **Bug de isolamento de tenant achado só em teste:** o ambiente de teste (Testcontainers)
   conecta como **superusuário**, que ignora RLS mesmo com `FORCE` — os dois serviços novos
   precisaram de filtro `id_tenant = plataforma.tenant_atual()` explícito em toda consulta
   (mesmo motivo já documentado em `PlanoContasService`), senão um teste que buscava "a moeda
   PIX do meu tenant" podia pegar a de outro tenant e quebrar a FK composta de `moeda_detalhe`.
5. **Frontend — `pages/moeda/` e `pages/tipocarteira/`:** mesmo padrão de cadastro (paginação,
   ordenação, 3 ícones de ação, `InfoRegistro`, confirmação de Enter); ícones próprios (cifrão
   para Moeda, carteira/wallet para Tipo de Carteira); checklist de moedas (checkbox, sem
   reordenar) dentro do formulário de Tipo de Carteira.
6. **91 testes verdes → 109** (`MoedaCrudTest` + `TipoCarteiraCrudTest`, 18 novos); `tsc -b`
   limpo; testado ao vivo (criar tipo de carteira com moedas + criação rápida de moeda embutida;
   exclusão de moeda vinculada bloqueada com 409; dados de teste limpos depois).

### 2026-07-23 — Correções de regra de negócio em Moeda/Tipo de Carteira (percentuais e taxa opcionais)

Rodada de correções pedida logo depois da entrega acima, com o dono do produto já testando a
tela:

1. **`moeda.perc_desconto`/`perc_acrescimo` e `tipo_carteira.taxa_administradora` perderam o
   `NOT NULL`** (mantém `DEFAULT 0`) — aplicado em V025 (schema ainda em construção, edição no
   próprio arquivo) + DDL manual no banco local + `flyway repair`, mesmo procedimento já
   normalizado nesta sessão.
2. **Desconto e acréscimo nunca coexistem *de verdade*** — mas a checagem é por **valor
   positivo**, não por presença: 0/0 é o estado neutro normal de toda moeda semeada no signup
   e não pode ser rejeitado; só bloqueia (400) quando os dois têm, ao mesmo tempo, valor > 0.
   No formulário, digitar um valor > 0 num campo limpa o outro automaticamente (mesmo
   comportamento no `MoedaModal.tsx` de criação rápida) — a validação de servidor é a mesma
   regra, defesa em profundidade.
3. **Percentuais (moeda) só precisam ser ≥ 0** — removido o teto de 100 que a versão anterior
   tinha (não pedido, era suposição minha); `taxa_administradora` também só valida ≥ 0 quando
   preenchida.
4. **`taxaAdministradora` virou campo opcional** no formulário de Tipo de Carteira (sem
   asterisco) — nem todo tipo de carteira cobra taxa; prazo de pagamento e taxa aceitam 0
   (já funcionava, confirmado com teste dedicado).
5. **Ícones por moeda no checklist** de Tipo de Carteira (`IconeDaMoeda`, heurística por
   palavra-chave no nome — `IconeFormaCartao` para "CART*", `IconeFormaPix` para "PIX", ícone
   genérico (`IconeMoeda`) para o resto, já que o nome da moeda é texto livre).
6. **113 testes verdes** no total (ajustados: um teste antigo assumia que `perc:150` seria
   rejeitado — agora é aceito; outro assumia `percDesconto`+`percAcrescimo` positivos juntos
   seria aceito — agora é rejeitado); `tsc -b` limpo; testado ao vivo (exclusividade mútua
   limpando o campo oposto, taxa/prazo em branco/zero salvando corretamente, ícones aparecendo
   no checklist).

### 2026-07-23 — Confirmação antes de salvar ao pressionar Enter, nas 5 telas de cadastro

Bug relatado pelo dono do produto durante o teste manual geral: na tela de Fornecedor, com o
foco num campo de texto (ex.: CEP), pressionar Enter salvava a tela direto — o Enter caía no
`<form onSubmit>` nativo do navegador e acionava o mesmo caminho do botão "Salvar", sem
nenhuma confirmação. Pedido: Enter deve pedir confirmação antes de salvar, em todas as telas
de cadastro (não só Fornecedor).

1. **`web/src/components/ConfirmarSalvarModal.tsx` (novo):** modal reutilizável — "Salvar
   dados? Deseja salvar os dados deste cadastro?" com Cancelar/Salvar — mesmo estilo visual
   dos modais de confirmação de exclusão já existentes (`.modal-overlay`/`.modal`).
2. **`web/src/lib/formularios.ts` (novo):** `aoTeclarEnterNoFormulario(e, aoConfirmar)` —
   intercepta Enter (`preventDefault`) **só quando o alvo é um `<input>` de texto** (exclui
   `checkbox`/`radio`/`button`/`submit` e, por não ser `<input>`, `<select>` também fica de
   fora) — Tab/Enter em checkbox, rádio, select e botões continua 100% nativo, sem passar pelo
   modal. Só esse caminho abre a confirmação; **clicar direto no botão "Salvar" continua
   instantâneo, sem confirmação nenhuma** (o pedido era especificamente sobre o Enter).
3. **As 5 telas de cadastro** (`ClienteForm`, `FuncionarioForm`, `PlanoContasForm`,
   `FornecedorForm`, `ProdutoForm`) ganharam o mesmo padrão: a função de submit foi dividida
   em `validarEEnviar()` (validação + `salvar.mutate()`, sem depender de `FormEvent`) e
   `submeter(e)` (só chama `e.preventDefault()` + `validarEEnviar()`, ligado ao `onSubmit` do
   `<form>` — usado pelo clique no botão "Salvar"); o `<form>` ganhou `onKeyDown` chamando
   `aoTeclarEnterNoFormulario`, que abre o modal em vez de submeter; confirmar no modal chama
   `validarEEnviar()` (mesma validação de sempre, inclusive toast de campo obrigatório se
   faltar algo). **Parâmetros do Sistema (`configuracao.geral`) e Login não foram alterados**
   — o pedido era sobre "telas de cadastro" e ambos já são explicitamente fora desse padrão.
4. **Verificação:** `tsc -b` limpo. Testado ao vivo no navegador (Fornecedor e Cliente): Enter
   no CEP abre "Salvar dados?"; Cancelar fecha sem validar nem salvar; Salvar no modal roda a
   validação normal (toast/erros se faltar campo obrigatório); clique direto no botão "Salvar"
   do topo continua sem pedir confirmação.

### 2026-07-23 — Acesso de dev ao bucket de imagens concedido ao Claudio (Opção A do handoff)

Primeira concessão de acesso pela **Opção A** de `docs/infra/armazenamento-imagens.md` §3
(ADC pessoal, sem arquivo de chave): a conta Google do Claudio Calixto
(`claudiocalixto6969@gmail.com`) recebeu `roles/storage.objectAdmin` **apenas em
`gs://niner-erp-dev`** — produção intocada, nenhuma chave privada trafegou. Feito pelo
console web (aba Permissões do bucket), já que a máquina Windows atual não tem `gcloud`.
Falta o lado dele: `gcloud auth application-default login` + teste de escrita da §7 do
handoff. Registro da concessão adicionado à própria §3 do handoff.

### 2026-07-23 — Banco recriado do zero para teste manual geral + carga da tabela oficial de NCM

Pedido do dono do produto: teste geral e manual de tudo que já foi construído até aqui.

1. **Banco recriado do zero:** volume `niner_pgdata` apagado e recriado, V001–V027 reaplicadas
   via Flyway (schema limpo, sem dado nenhum). API/web/site subidos.
2. **Tenant de teste criado** via `POST /api/publico/assinar` (fluxo público de verdade, não
   seed manual) para já ter credenciais prontas: loja `loja-teste-manual`, e-mail
   `teste@niner.dev`, papel ADMIN.
3. **Carga da tabela oficial de NCM (Receita Federal)** em `cfg_produto_ncm`, a partir de
   `C:\FIX\TABELA_NCM.csv` fornecido pelo dono do produto — substitui a massa de ~51 códigos de
   exemplo carregada em 2026-07-22 (`db/scripts/seed_cfg_produto_ncm.sql`) por uma base real de
   **10.442 códigos** (8 dígitos + descrição + alíquota IBPT). Duas particularidades do arquivo:
   Latin-1 (não UTF-8) e descrições com `;` embutido (não é um CSV de 3 colunas simples — algumas
   linhas têm até 8 campos brutos). Tratado com `awk` (recompôs a descrição a partir do 2º até o
   penúltimo campo, `codigo`=1º e `aliquota`=último, com aspas/CSV quoting) e carregado via
   `COPY ... WITH (ENCODING 'LATIN1')` numa tabela de staging temporária, depois
   `INSERT ... ON CONFLICT (codigo_ncm) DO UPDATE` em `cfg_produto_ncm` — upsert, não substitui a
   tabela inteira: os poucos códigos de exemplo que não constam na base oficial permaneceram.
   `cfg_produto_ncm` foi de 51 para **10.455 linhas**. **Só carga de dados** — nenhuma migration
   alterada, mesma tabela desde V017 (2026-07-22).
4. **Verificação:** acentuação conferida pós-carga (`REPRODUÇÃO`, `SUCEDÂNEOS`, `À` — sem
   caracteres corrompidos); `GET /api/v1/ncm/{codigo}` já reflete a base real.

### 2026-07-23 — Object storage das imagens de produto: infra provisionada (ADR-013)

Sessão de **infraestrutura, não de código**. `produto_imagem` (V017) existia desde 2026-07-16
com `imagem text` comentada como "URL/chave de object storage", mas o provedor nunca tinha
sido escolhido — a tela de Produtos (próximo item do roadmap) não teria onde pôr as fotos.
Escolhido **Firebase Storage/GCS**; infra criada e testada; **nenhuma linha de Java escrita**.

1. **ADR-013 registrado** (spec §6) + nota em §3.3.3. Decisões, com o porquê:
   - **Leitura pública** dos buckets. Não é descuido: ML/Shopee **rebuscam** a imagem por URL
     (revalidação, republicação), e signed URL V4 dura no máximo 7 dias — o anúncio quebraria
     em silêncio semanas depois. Consequência assumida: **só foto de produto entra nesses
     buckets**; documento/XML/anexo exigem bucket privado e outra decisão.
   - **Upload sempre pela API** (multipart), nunca navegador→bucket. Upload direto exigiria
     identidade Firebase paralela ao nosso JWT + Security Rules — dois sistemas de auth para a
     mesma regra, contra P8/P4.
   - **A coluna guarda a chave, não a URL** (`tenants/{id_tenant}/produtos/{id_produto}/{uuid}.webp`),
     com `id_tenant` sempre do `TenantContext` (P8) e `{uuid}` aleatório (bucket público ⇒
     caminho não pode ser enumerável). Trocar de provedor vira config, não migration de dados.

2. **Infra criada por `gcloud` e verificada:** projeto `niner-erp` (Blaze), buckets
   `niner-erp.firebasestorage.app` (prod) e `niner-erp-dev` (dev), ambos `southamerica-east1`
   (**região é imutável**), uniform bucket-level access, `allUsers`→`objectViewer`; conta de
   serviço `niner-api-storage@niner-erp.iam.gserviceaccount.com` com `objectAdmin`
   **apenas nos dois buckets**, nada no projeto. Testado: leitura anônima por `curl` → HTTP 200;
   escrita/exclusão autenticado como a conta de serviço → ok.

3. **Chave privada fora do git:** `.gitignore` ganhou `api/secrets/` e `*-service-account*.json`;
   a chave vive em `api/secrets/gcs-niner-erp.json` (modo `600`) só na máquina do Evirson.
   `docs/infra/armazenamento-imagens.md` §3 documenta as duas formas de o próximo dev obter
   acesso — a recomendada é **ADC pessoal** (`gcloud auth application-default login` + papel no
   bucket de dev), **sem arquivo de chave nenhum**.

4. **`docs/infra/armazenamento-imagens.md` criado — é o handoff.** Estado provisionado,
   credenciais, contrato (caminho, o que vai na coluna, fluxo de upload, ordem de exclusão),
   TASK-A a TASK-D com critérios `Dado/Quando/Então`, riscos e comandos de verificação.

5. **Pendências deixadas explícitas:** 🔴 **alerta de orçamento não criado** (Blaze é pós-pago
   **sem teto** — responsável: Evirson, exige permissão de faturamento); 🟡 regras do Firebase
   Storage não travadas em `if false` (cinto-e-suspensório, já que o SDK cliente não é usado);
   🟡 `uso_tenant` não conta bytes por tenant (V028+ se o plano vier a limitar espaço — R19,
   decisão de produto pendente); 🟡 dimensões/formatos exigidos por ML e Shopee **a confirmar
   na doc oficial do canal** antes de virar validação.

6. **Gatilho de revisão do ADR-013:** egress é o custo dominante deste caso de uso (servir a
   foto, não guardá-la). Se pesar na fatura, migrar para **Cloudflare R2** (egress zero) — por
   isso a TASK-A é uma interface `ArmazenamentoDeArquivos` com adapter, e por isso a coluna
   guarda a chave.

### 2026-07-22 — Gerador de código de barras interno (EAN-13) para `produto_barra.sku`

Pedido do dono do produto: `sku` (o identificador interno de cada variação, impresso como
código de barras na loja) deixa de ser texto livre e passa a ser **sempre** um EAN-13 gerado
pelo sistema, estrutura `FIIISSSSSSSSD` — F=9 fixo (código de circulação restrita, não é GS1
de verdade), III=`id_banco` (3 dígitos), SSSSSSSS=sequencial (8 dígitos), D=dígito verificador
(algoritmo EAN-13/GTIN padrão, peso 1/3 alternado).

1. **Esclarecimento importante sobre "banco":** na conversa inicial, "Id Banco" parecia
   remeter a algo por tenant (herança do legado Firebird, onde cada loja tinha seu **próprio
   arquivo** de banco). Confirmado com o dono do produto: aqui "banco" é a **instância** de
   banco de dados (hoje só existe uma, `niner_db`) — se um dia a Vetor fizer *sharding* (uma
   segunda instância de Postgres para outro grupo de tenants), essa segunda instância nasce com
   `id_banco = 2`. Dentro de uma mesma instância, o sequencial é **um contador único
   compartilhado por todos os tenants** (não por tenant) — decisão confirmada explicitamente.
2. **`db/migration/V017__catalogo.sql`:** nova tabela `cfg_ean_gerador(id_banco smallint,
   proximo_sequencial bigint)` — **GLOBAL, sem `id_tenant`/RLS** (terceira exceção do domínio,
   mesmo motivo de `cfg_produto_ncm`: não é dado de tenant). Singleton de verdade (1 linha,
   semeada com `id_banco=1, proximo_sequencial=1`). Nova função
   `gerar_ean13_interno() RETURNS text`, `SECURITY DEFINER` (roda como `niner_owner`, dono da
   tabela) — `UPDATE ... RETURNING` atômico incrementa o contador sem precisar de lock
   explícito; `niner_app` só ganha `GRANT EXECUTE` na função, **nenhum** grant na tabela (só dá
   pra gerar código pela rotina, não manipular o contador direto).
3. **`produto_barra.sku`:** comentário atualizado — sempre gerado por `gerar_ean13_interno()`,
   nunca digitado pelo usuário (reforça a intenção que já estava no comentário original,
   "imprimível como código de barras").
4. **Decisão adiada (a pedido do dono do produto):** hoje nenhum código Java chama a função —
   a tela/serviço de variação (`produto_barra`) ainda não existe. Quando for construída, o
   plano registrado é o `ProdutoBarraService.criar()` chamar a função explicitamente antes do
   `INSERT` (mesmo estilo de `plataforma.tenant_atual()` usado nos demais módulos — derivado
   explícito no Java, não escondido em `DEFAULT`/`TRIGGER` da coluna). Um gatilho `BEFORE
   INSERT` reforçando isso no nível do banco (defesa em profundidade) foi cogitado e **adiado**
   — decidir quando a tela existir.
5. **Verificação:** novo `EanGeradorTest` (2 testes: formato de 13 dígitos + dígito
   verificador válido; chamadas sucessivas geram códigos diferentes e sequenciais) — **91/91
   testes verdes**. Testado também manualmente contra o banco local: 3 códigos gerados com
   dígito verificador conferido à mão; `niner_app` consegue chamar a função mas recebe
   "permission denied" ao tentar `SELECT` na tabela direto (confirma o isolamento de
   privilégio). Aplicado no banco local sem recriar (DDL manual + `flyway repair`, mesmo
   procedimento já usado para `produto_categoria.indice`/`cfg_produto_ncm`).

### 2026-07-22 — Campo de data: texto mascarado `dd/mm/aaaa` em todo o sistema (não `<input type="date">`)

Pedido do dono do produto: ao focar um `<input type="date">`, o foco fica "separado" em
segmentos (dia → mês → ano) e não dá pra selecionar o campo inteiro e sobrescrever ao digitar.
Confirmado com o dono do produto (`AskUserQuestion`) que essa é uma limitação real do HTML (não
existe API pra isso em nenhum navegador) — a única forma de ter o comportamento pedido é trocar
por um campo de texto mascarado, perdendo o calendário nativo.

1. **`web/src/lib/masks.ts`:** `mascararData` (`dd/mm/aaaa`, mesmo `aplicarMascara` já usado
   por CEP/telefone/NCM), `dataValida` (rejeita datas de calendário impossíveis, ex.:
   "31/02/2026"), `dataParaIso`/`isoParaData` (conversão de/para o formato que a API espera).
2. **`web/src/lib/datas.ts`:** novo `hojeISO()` compartilhado (fuso local, evita o desvio de
   `toISOString()`, que é UTC) — usado por Cliente e Produto; corrige de brinde um bug latente
   no "hoje" da validação de nascimento do Cliente, que usava UTC.
3. **Três campos trocados** (`ClienteForm.tsx` nascimento; `ProdutoForm.tsx` início/final da
   oferta): viram `<input>` de texto normal com `placeholder="dd/mm/aaaa"`,
   `onFocus={(e) => e.target.select()}` (seleciona tudo, como qualquer campo de texto) e
   `onChange` aplicando `mascararData`. `lib/clientes.ts`/`lib/produtos.ts` convertem
   `dd/mm/aaaa` ↔ ISO na borda (payload da API / preenchimento a partir da resposta).
   Validações de data (`errosOferta`, nascimento) passam a checar `dataValida` antes de comparar.
4. **Verificação:** 89/89 testes do backend inalterados (o formato enviado à API continua o
   mesmo ISO de sempre — só mudou a representação em tela). Testado ao vivo: digitação contínua
   sem pular entre segmentos; clicar num campo já preenchido seleciona tudo e sobrescreve.

### 2026-07-22 — Peso bruto/líquido: digitação natural (3 casas) + peso líquido ≤ peso bruto

Dois pedidos do dono do produto: os campos de peso são `numeric(14,3)` (3 casas, diferente de
moeda/percentual que têm 2) e precisavam da mesma digitação natural; e peso líquido não pode
ser maior que peso bruto.

1. **`web/src/lib/masks.ts`:** as funções de máscara decimal (`mascararMoeda`/`mascararPercentual`,
   ver entrada abaixo) ganharam um parâmetro `casas` — `mascararPeso`/`completarPeso`/
   `desmascararPeso` reusam a mesma lógica com `casas=3`. Novo `formatarPeso` (3 casas) para
   pré-popular o campo ao editar um produto existente.
2. **`ProdutoForm.tsx`:** peso bruto/líquido usam a máscara nova (antes eram texto livre sem
   máscara nenhuma); nova validação `erroPesoLiquido` (ao sair de qualquer um dos dois campos)
   + reforço no backend (`ProdutoService.validar`, 400 "Peso líquido deve ser menor ou igual ao
   peso bruto.").
3. **Verificação:** 2 novos testes em `ProdutoCrudTest` (peso líquido > bruto rejeitado; igual
   aceito) — **89/89 testes verdes**. Testado ao vivo: `1` → `1,000` ao sair do campo; `1,5` >
   `1,000` mostra o erro; `0,8` ≤ `1,000` aceita.

### 2026-07-22 — Campos decimais: digitação natural em todo o sistema + preço de venda/% calculados ao vivo + regra da oferta

Sete pedidos do dono do produto numa única rodada — o maior deles é uma mudança de convenção
que afeta **todo campo monetário/percentual do sistema**, não só Produto.

1. **Digitação natural (item 1, `web/src/lib/masks.ts`):** convenção antiga lia os dígitos
   sempre da direita como centavos (tipo caixa eletrônico — digitar "150000" virava
   "1.500,00"). Nova convenção: o inteiro é digitado normalmente da esquerda pra direita; a
   vírgula abre até 2 casas decimais; sem vírgula nenhuma, o campo só ganha ",00" ao **sair do
   campo** (`completarMoeda`/`completarPercentual`), nunca a cada tecla (isso impediria
   continuar digitando o inteiro). Mesmas funções (`mascararMoeda`/`mascararPercentual`/
   `desmascararMoeda`/`desmascararPercentual`), só a implementação interna mudou — todo
   consumidor já existente (limite de crédito do Cliente, % comissão do Funcionário, 3
   percentuais dos Parâmetros do Sistema, 4 campos de Produto) ganhou o comportamento novo sem
   precisar trocar de função, só adicionando a chamada de `completar*` no `onBlur` de cada um
   (`ClienteForm.tsx`, `FuncionarioForm.tsx`, `ConfiguracaoGeralForm.tsx` — este último também
   ganhou a correção de `validarCampo` pra usar `desmascararPercentual` em vez de um cálculo de
   dígitos própria, que ficaria errado com o formato novo).
2. **Preço de venda automático (item 2, `ProdutoForm.tsx`):** editar Preço de Custo ou % de
   Venda recalcula `Preço de Venda = Custo × (1 + %/100)` a cada tecla (só quando há custo > 0).
3. **% de venda automático (item 3):** editar o Preço de Venda direto recalcula
   `% = ((Venda − Custo) / Custo) × 100` (só quando há custo informado).
4. **Regra da oferta — tudo ou nada (itens 4-7):** início, final e preço de oferta só valem
   juntos — preencheu um, os três viram obrigatórios; início não pode ser no passado; final não
   pode ser antes do início; preço de oferta tem que ser menor que o preço de venda (não `<=`).
   Nova função `errosOferta` (frontend, ao vivo por campo) e `ProdutoService.validarOferta`
   (backend, 400 com mensagem específica por regra) — mesma regra dos dois lados.
5. **Verificação:** 6 novos testes em `ProdutoCrudTest` (oferta incompleta, início no passado,
   final antes do início, preço de oferta ≥ venda, oferta válida com os 3 campos, mais o já
   existente de datas) — **89/89 testes verdes** (antes de somar os de peso). Testado ao vivo
   no navegador: `150` → `150,00`; custo 150 + 50% → venda 225,00; venda editada pra 180 → %
   recalculado pra 20; cada regra da oferta reproduzida e corrigida uma a uma.

### 2026-07-22 — Layout do formulário de Produto: reorganização pedida pelo dono do produto

Cinco ajustes de leiaute no `ProdutoForm.tsx`, todos usando o `LinhaGrid` existente (que já
redistribui a largura quando um campo configurável está oculto):

1. NCM inválido (código digitado que não existe em `cfg_produto_ncm`) agora **limpa o campo e
   avisa** ("Código NCM inválido — não encontrado."), em vez de só deixar a descrição em branco
   silenciosamente.
2. "Produto ativo" e "Descrição" viram uma linha só, com Descrição alinhada à direita (mesma
   borda das linhas de baixo).
3. Seção Categorias movida para logo abaixo de Identificação (antes vinha depois de Preços).
4. Preço de Custo, % de Venda, Preço de Venda, Início/Final da oferta e Preço de Oferta —
   6 campos numa linha só.
5. Nome da Variante em Linha/Coluna + Peso Bruto/Líquido — 4 campos numa linha só (as seções
   "Dimensões" e "Variantes", antes separadas, viraram uma só: "Dimensões e Variantes").

### 2026-07-22 — Consulta de NCM no formulário de produto (descrição ao lado do código)

Pedido do dono do produto: rótulo do campo por extenso e busca automática da descrição
enquanto o usuário digita o código.

1. **Backend — `GET /api/v1/ncm/{codigo}`** (novo `NcmController`/`NcmService`, módulo
   `catalogo`): só leitura, sem POST/PUT/DELETE (mesma decisão de `cfg_produto_ncm` não ter
   tela — script cuida da carga). Consulta direta por `codigo_ncm` (sem `tenant_atual()`: a
   tabela é global). 404 amigável quando o código não existe.
2. **Frontend — `ProdutoForm.tsx`:** rótulo do campo virou "NCM - Nomenclatura Comum do
   Mercosul" (também na tela de configuração de campos e nas mensagens de obrigatoriedade do
   backend). Ao sair do campo código (`onBlur`, mesmo estilo do autopreenchimento de CEP em
   Cliente/Fornecedor), busca a descrição via `lib/ncm.ts#buscarNcm` e mostra num campo
   somente-leitura ao lado (`peso: 4` código + `peso: 8` descrição, mesma linha do
   `form-grid`); 404 não vira erro/toast, só limpa a descrição. Também busca ao abrir um
   produto existente para edição.
3. **Verificação:** 14 testes em `ProdutoCrudTest` (2 novos: NCM existente devolve descrição,
   NCM inexistente devolve 404) + suíte completa verde. Testado ao vivo (container
   reconstruído): NCM cadastrado devolve descrição, NCM inexistente devolve 404.

### 2026-07-22 — `cfg_produto_ncm`: tabela global de referência de NCM

Pedido do dono do produto: uma tabela que guarde código NCM + descrição para o campo
`produto.codigo_ncm` (até então só texto livre, sem validação nem lookup).

1. **`db/migration/V017__catalogo.sql`** — nova tabela `cfg_produto_ncm(codigo_ncm PK,
   descricao_ncm NOT NULL, aliquota_ibpt NUMERIC(10,2))`. Diferente de toda outra tabela do
   domínio: **sem `id_tenant` e sem RLS** — decisão explícita do dono do produto ("tabela de
   uso geral, igual para todos os tenants"), mesma exceção de `plataforma.*` (P9), só que fora
   daquele schema. Por não ter `id_tenant`, o guarda-corpo de P8 (V024) nem a enxerga — não
   precisa de tratamento especial ali.
2. **Sem tela de manutenção** (decisão explícita): a tabela é carregada/atualizada por script.
   `niner_owner` (dono da tabela) recebeu `GRANT SELECT, INSERT, UPDATE, DELETE` explícito —
   redundante em produção (ele já é dono), mas necessário no ambiente de teste (Testcontainers),
   onde quem roda as migrations é o superusuário do container, não `niner_owner` de verdade.
   `niner_app` só tem `GRANT SELECT` — a aplicação nunca escreve nessa tabela.
3. **Vínculo com produto:** `produto.codigo_ncm` (V017) ganhou
   `REFERENCES cfg_produto_ncm (codigo_ncm)` — FK simples (não composta, diferente do resto do
   domínio: o alvo não tem `id_tenant`), continua `nullable` (produto pode não ter NCM ainda).
4. **Efeito colateral corrigido:** o `catch (DataIntegrityViolationException)` de
   `ProdutoService.criar/atualizar` tratava toda violação de FK como "categoria não existe" —
   com a nova FK de NCM isso ficaria enganoso. Novo método `erroDeVinculo` inspeciona o nome da
   constraint na causa raiz (mesmo princípio de `ClienteService.duplicidade`) e devolve "NCM
   informado não existe." quando é o caso — 400, não 500.
5. **Migration já aplicada neste ambiente local** (25h no ar): como editar uma migration já
   rodada quebra o checksum do Flyway, apliquei o `CREATE TABLE`/`GRANT`/`ALTER TABLE ADD
   CONSTRAINT` equivalente direto no Postgres local e rodei `flyway repair` para realinhar o
   checksum (mesmo procedimento já usado para o `produto_categoria.indice`, nesta mesma
   sessão) — sem recriar o banco, sem perder os tenants de teste já criados.
6. **Verificação:** 12 testes em `ProdutoCrudTest` (incluindo NCM válido e inexistente,
   inserido/consultado direto via SQL já que não há endpoint de escrita) + suíte completa —
   **81/81 testes verdes**. Testado também ao vivo contra o servidor real (container
   reconstruído): NCM cadastrado é aceito e devolvido no produto; NCM inexistente vira 400 com
   `"NCM informado não existe."`.

### 2026-07-22 — Quinta tela de domínio: Produtos (`catalogo.produto`) — primeiro corte vertical do catálogo

Primeira tela do módulo `catalogo` (docs/telas/produto.md). Maior entrega de uma vez até aqui:
CRUD completo de produto + duas particularidades estruturais que nenhuma tela anterior tinha
(N:N com ordenação; campo controlado por configuração de **outra** tela).

1. **Backend — módulo `catalogo` (novo):** `ProdutoController/Service/Dtos`
   (`GET/POST/PUT/DELETE /api/v1/produtos`, mesmo padrão de paginação/ordenação/exclusão com
   fallback do resto do domínio) + `CategoriaProdutoController/Service/Dtos`
   (`GET/POST/PUT /api/v1/categorias-produto`, criar/listar/renomear, sem exclusão — mesmo
   desenho da categoria de cliente).
2. **Categorias N:N ordenadas:** o request de produto recebe `categorias` como lista de
   `idCategoria` **na ordem escolhida pelo usuário**; o servidor deriva o `indice`
   (`produto_categoria.indice`, adicionado nesta mesma sessão — ver entrada abaixo) da posição
   na lista e substitui todas as linhas a cada save (apaga + reinsere, não faz *diff*).
   Categoria duplicada ou inexistente na lista → 400, não 500.
3. **Nome de variante controlado por `cfg_geral`:** `nomeVarianteLinha`/`nomeVarianteColuna` só
   persistem se a respectiva flag (`cfg_usa_variante_linha`/`cfg_usa_variante_coluna`,
   Parâmetros do Sistema) estiver ligada — o servidor força `null` quando desligada, mesmo que
   o cliente envie valor. Flags expostas via `ConfiguracaoGeralService.flagsVariante()`
   (método novo, sem checagem de ADMIN — diferente do resto de `cfg_geral`).
4. **Frontend — `pages/produtos/`** (lista + formulário + configuração de tela): categorias com
   setas de reordenação + botões "＋ Adicionar"/"＋ Gerenciar categorias"
   (`CategoriaProdutoModal.tsx`); seção "Variantes" condicional às flags; ícone próprio.
5. **Verificação:** 12 testes em `ProdutoCrudTest` (novo) + suíte completa — **80/80 testes
   verdes** neste ponto. Testado ao vivo: categoria criada e vinculada ao produto, ordem
   preservada.

### 2026-07-22 — `produto_categoria.indice`: ordenação da categoria dentro do produto

Campo novo pedido pelo dono do produto. Tabela `produto_categoria` ainda sem dados em
qualquer ambiente, então a coluna entrou **direto na migration V017** (que já cria a tabela),
sem `V028` nova — mesma convenção usada até aqui enquanto o banco não sobe em lugar real (ver
`docs/migration/README.md`).

1. **`db/migration/V017__catalogo.sql`:** `produto_categoria` ganhou
   `indice SMALLINT NOT NULL DEFAULT 0` + `CONSTRAINT produto_categoria_indice_uk UNIQUE
   (id_tenant, id_produto, indice)` — mesmo padrão já usado em `produto_imagem.indice` (V017,
   2026-07-16) para a galeria de imagens. `COMMENT ON COLUMN` documentando o propósito.
2. **Não houve rebuild do banco** (regra combinada: só editar + documentar; o dono do produto
   pede o rebuild/teste explicitamente quando quiser).
3. **Spec atualizada** (`spec-driven-erp-varejo.md` §3.3.3): `produto_categoria` no bloco de
   modelo de dados + nota explicando a ordenação, ao lado da nota já existente sobre
   `produto_imagem`. `db/migration/README.md` (linha da V017) também atualizado.

### 2026-07-21 — Parâmetros do Sistema (`cfg_geral`): primeira tela fora do padrão de cadastro

Pedido do dono do produto. Diferente de toda tela anterior (Cliente/Funcionário/Fornecedor/
Plano de Contas), `cfg_geral` é um **singleton por tenant** (semeado com valores padrão no
signup) — a tela é só leitura/atualização, sem listagem, criação, exclusão, paginação,
`InfoRegistro` ou modo somente-leitura. Ver `docs/telas/configuracao-geral.md` (spec completa,
com a lista de particularidades estruturais).

1. **Decisão de produto perguntada e confirmada antes de implementar:** acesso **somente
   ADMIN** (leitura e escrita — mais restrito que os cadastros, onde OPERADOR também opera) e
   os 4 campos de crediário (Fase 2/Q5, ainda sem módulo de crediário) aparecem editáveis
   desde já, com aviso na tela, em vez de ficarem ocultos.

2. **Backend — módulo novo `configuracao.geral`** (`GET/PUT /api/v1/config-geral`, sem
   POST/DELETE). Ambos os endpoints verificam ADMIN a partir do claim `roles` do JWT (mesmo
   mecanismo de `ConfiguracaoTelaService`), inclusive o `GET` — diferente da Configuração de
   Tela, onde a leitura é liberada a qualquer papel. Validação de faixa (percentuais 0–100,
   dias ≥ 0) — não há "campo obrigatório configurável" aqui, porque a tabela inteira é
   `NOT NULL` desde V023.

3. **Frontend — `pages/configuracaogeral/ConfiguracaoGeralForm.tsx`**: formulário único
   (seções Vendas/Catálogo/Crediário), sem `InfoRegistro` (a tabela não tem `criado_em` nem um
   código de registro — só "Última atualização" abaixo do título), ícone próprio
   (`IconeParametros`) deliberadamente diferente da engrenagem usada como botão "configurar
   esta tela" em cada cadastro (para não confundir as duas ideias), e item de menu
   ("Parâmetros do Sistema") que só aparece para ADMIN — a rota em si também é protegida por
   `RequireAdmin`, defesa em profundidade.

4. **Verificação:** **6 testes novos** (`ConfiguracaoGeralTest`, incluindo bloqueio de
   OPERADOR em leitura e escrita, e isolamento entre tenants) — suíte completa em **69/69
   verdes**; `tsc -b` limpo; testado ao vivo (editar desconto e uma variante, salvar,
   recarregar a página para confirmar persistência, depois restaurar os valores padrão).

### 2026-07-21 — Quarta tela de domínio: Fornecedores (com criação rápida de plano de contas embutida)

Pedido do dono do produto, na sequência natural do Plano de Contas: `fornecedor.id_plano_contas`
é `NOT NULL` desde V016, então esta tela precisava resolver a criação/escolha do plano de
contas sem forçar o usuário a sair da tela. Ver `docs/telas/fornecedor.md` (spec completa).

1. **Backend — módulo novo `cadastros.fornecedor`** (`GET/POST/PUT/DELETE
   /api/v1/fornecedores`), no padrão consolidado (paginação por página, ordenação com
   allowlist, obrigatoriedade configurável por tenant, exclusão com fallback para inativar).
   Sem mudança de schema — `fornecedor` já existia por completo desde V016/V024. Duas regras
   específicas de fornecedor (pessoa jurídica, não física): CNPJ sempre com 14 caracteres
   (CPF de 11 dígitos é rejeitado) e **telefone aceita fixo ou celular** (10–11 dígitos),
   mais frouxo que a regra de celular-obrigatório do cliente — nova função `telefoneValido`
   em `masks.ts` ao lado da `celularValido` existente. `idPlanoContas` inexistente vira 400
   amigável (a `DataIntegrityViolationException` da FK é traduzida), não 500. O JOIN da
   listagem com `cfg_plano_contas` inclui `id_tenant` na condição — a PK do plano é
   composta, então o mesmo código existe em tenants diferentes; **o mesmo motivo exigiu
   revisitar `PlanoContasService`**, que tinha o mesmo risco de vazamento cross-tenant em
   ambiente sem RLS (SELECT/UPDATE/DELETE agora filtram `id_tenant = plataforma.tenant_atual()`
   explicitamente — pego pela suíte completa, que só falha com >1 tenant usando o mesmo
   código de plano).

2. **Frontend — `web/src/pages/fornecedores/`** (lista + formulário + configuração de tela)
   no padrão de Cliente/Funcionário, com um mecanismo novo: **`PlanoContasModal.tsx`**,
   criação rápida de plano de contas embutida no formulário de fornecedor (botão "＋ Novo" ao
   lado do select de Plano de Contas) — mesmo papel do modal de categoria do cliente. A
   listagem ganhou filtro por plano de contas (select, não texto livre). Ícone próprio
   (caminhão) e item "Fornecedores" no menu, entre Clientes e Funcionários.

3. **Verificação:** **12 testes novos** (`FornecedorCrudTest`, incluindo o caso de plano de
   contas inexistente, CNPJ alfanumérico, CPF rejeitado no campo CNPJ, telefone curto e
   exclusão com fallback) — suíte completa em **63/63 verdes**; `tsc -b` limpo; testado ao
   vivo criando um fornecedor completo pelo formulário (com o modal de plano de contas) e,
   via API direta (mais confiável que repetir 10 vezes o formulário no navegador), mais **10
   fornecedores** de dados variados (diferentes estados/regiões, com e sem campos opcionais)
   — todos conferidos na listagem e reabrindo um deles para edição (select de plano de contas
   populado corretamente).

### 2026-07-21 — Terceira tela de domínio: Plano de Contas (+ `criado_em`/`atualizado_em` em `cfg_plano_contas`)

Pedido do dono do produto em duas partes: adicionar os campos de auditoria que faltavam em
`cfg_plano_contas` (a tabela nasceu em V016 sem eles) e construir a tela de cadastro. Ver
`docs/telas/plano-contas.md` (spec completa). É a tela que destrava o cadastro de
**Fornecedor** (`fornecedor.id_plano_contas` é `NOT NULL` sem seed — antes desta tela era
impossível criar um fornecedor).

1. **Schema:** `criado_em`/`atualizado_em` (`timestamptz NOT NULL DEFAULT now()`) adicionados
   **na própria V016** (banco em construção — convenção do projeto, não migration nova);
   `db/migration/README.md` atualizado. **Banco recriado do zero** (V001–V027) e massa de
   teste restaurada na sequência: os 2 tenants assinados na mesma ordem (para
   `loja-teste-manual` continuar sendo o tenant 2, que os seeds referenciam), 110 clientes,
   funcionária Maria e o cliente com CNPJ alfanumérico recriados via seed/API.

2. **Backend — módulo novo `cadastros.planocontas`** (`GET/POST/PUT/DELETE
   /api/v1/planos-contas`), no padrão consolidado, com duas adaptações estruturais que vêm do
   schema (não são escolhas): a **PK é a chave de negócio** (`id_plano_contas text`, o código
   contábil ex. "3.1.001" — digitado pelo usuário ao criar, único por tenant, **imutável**
   depois: o PUT usa o código do path e ignora o do corpo, provado por teste); e **não existe
   `ativo`**, então a exclusão não tem fallback de inativar — com vínculo em
   `fornecedor`/`contas_pagar`, responde **409** e nada muda. `tipoMovimento` validado contra
   os valores exatos do ENUM `tipo_movimento_conta` (**com acentos**: CRÉDITO/DÉBITO/NEUTRO)
   — DTO usa `String` + allowlist em vez de enum Java com identificadores acentuados. Busca
   única (`busca`) cobre código OU descrição.

3. **Sem configuração de campos para esta tela** (sem chave em `CAMPOS_POR_TELA`, sem ⚙):
   todos os campos são NOT NULL — estruturalmente obrigatórios não são configuráveis
   (docs/telas/configuracao-tela.md), não sobra nada para configurar.

4. **Frontend — `web/src/pages/planocontas/`** (lista + formulário; sem tela de configuração)
   + `lib/planoContas.ts` + ícone novo `IconePlanoContas` (prancheta) + item "Plano de Contas"
   no menu + ajuda R22. Rotas usam o próprio código (`/planos-contas/3.1.001` — pontos são
   URL-safe; `encodeURIComponent` por segurança). Na edição o Código aparece bloqueado
   (`.campo-leitura`) e o foco automático pula direto para a Descrição. `InfoRegistro` passou
   a aceitar **código texto** (prop `codigo: number | string`) — primeira tela cuja PK não é
   numérica.

5. **Verificação:** **8 testes novos** (`PlanoContasCrudTest`) — suíte completa em **51/51
   verdes** (inclui: código imutável no PUT, 409 na exclusão com fornecedor vinculado, tipo
   sem acento rejeitado); `tsc -b` limpo; testado ao vivo no navegador (criar "3.1.001 —
   RECEITA DE VENDAS — CRÉDITO — DRE" → aparece na listagem; editar com código bloqueado e
   Informações do registro preenchidas). Nota: durante o teste ao vivo, a extensão **Dark
   Reader** do navegador reestilizou a página numa navegação (cores apagadas) — não é defeito
   do app; as variáveis CSS do design system estavam intactas.

### 2026-07-21 — Campos informativos de auditoria (código/cadastrado em/última alteração), somente leitura, em toda tela de cadastro

Pedido do dono do produto logo depois da tela de Funcionário: os campos gerados pelo banco
que até então só existiam na API (e estavam explicitamente documentados como "não aparecem no
formulário") passam a ser **exibidos** nas telas, como informação — mas nunca editáveis. Vira
**convenção do projeto**, não um detalhe de duas telas: `criado_em`/`atualizado_em` são
declarados em **14 migrations**, ou seja, praticamente todo o domínio vai reutilizar isso.

1. **Componente único (`web/src/components/InfoRegistro.tsx`)**, em vez de repetir o bloco em
   cada formulário: recebe `codigo`/`criadoEm`/`atualizadoEm` e renderiza a seção
   "Informações do registro" com os três campos. Toda tela de cadastro futura cuja tabela
   tenha esses campos só precisa importar e passar os valores.

2. **Somente leitura de verdade:** `readOnly` + `tabIndex={-1}` (ficam fora da navegação por
   Tab, para não atrapalhar quem preenche o formulário no teclado) + classe nova
   `.campo-leitura` (fundo transparente, texto apagado) para diferenciar visualmente de um
   campo de entrada. Não são editáveis nem no modo de edição — só o banco os escreve.

3. **Somem ao incluir** um registro novo (`if (!codigo) return null`) — não faz sentido
   mostrar código/datas de algo que ainda não existe.

4. **Formatação de data em `lib/datas.ts`** (`formatarDataHora`): ISO 8601/`timestamptz` da
   API vira padrão brasileiro (`21/07/2026, 13:51`). Arquivo novo, genérico — primeiro
   utilitário de data do projeto.

5. **Aplicado em Cliente e Funcionário.** Verificação ao vivo: no cliente editado antes nesta
   sessão, "Cadastrado em" (08:44) e "Última alteração" (11:00) aparecem **diferentes**, o que
   confirma que os dois timestamps são reais e distintos (não um espelhando o outro); ao abrir
   "novo funcionário" a seção não aparece, como esperado.

### 2026-07-21 — Segunda tela de domínio: Funcionários (CRUD ponta a ponta, replicando o padrão de Cliente)

Primeira tela construída **inteiramente sobre o padrão consolidado em `cadastros.cliente`** —
o dono do produto pediu explicitamente para fazê-la sozinho, sem perguntas, como teste de que
o padrão está de fato estabelecido e reproduzível. Ver `docs/telas/funcionario.md` (spec
completa, escrita depois da implementação a pedido do dono do produto).

1. **Backend — módulo novo `cadastros.funcionario`:** `FuncionarioController`/`Service`/`Dtos`
   sobre a tabela `funcionario` (já existia desde V016, **sem migration nova**). Mesmo
   desenho do cliente: `GET/POST/PUT/DELETE /api/v1/funcionarios`, paginação por número de
   página + contagem total, `ORDER BY` dinâmico com allowlist de colunas, validação de
   servidor (CPF com dígito verificador, celular 11 dígitos + 3º=9, % comissão entre 0 e 100)
   e obrigatoriedade configurável por tenant (`cfg_tela_campo`, chave nova
   `cadastros.funcionario.form` registrada em `ConfiguracaoTelaService`). Exclusão com
   fallback para inativar quando há vínculo — aqui o vínculo é com
   `produto_movimento_detalhe.id_funcionario` (ledger de estoque, V019), não com `venda`.

2. **`Documentos` virou público** (era package-private em `cadastros.cliente`) para o módulo
   de funcionário reaproveitar a validação de CPF em vez de duplicar o algoritmo do dígito
   verificador. Funcionário é sempre pessoa física — a parte de CNPJ alfanumérico não se
   aplica.

3. **Diferenças deliberadas em relação ao cliente**, todas vindas do schema mais enxuto de
   `funcionario` (não são simplificações arbitrárias): sem categoria (não existe
   `cfg_categoria_funcionario`), sem CNPJ/tipo de pessoa, sem endereço/redes sociais/e-mail, e
   **CPF não é único por tenant** — decisão já registrada em V016/§3.3.9 ("o CPF deixou de ser
   único"), o oposto do cliente. Há um teste explícito provando que dois funcionários podem
   ter o mesmo CPF. `id_empresa` não aparece no formulário: é preenchido automaticamente com a
   única empresa do tenant (Q6, 1:1 no v1).

4. **Frontend — `web/src/pages/funcionarios/`:** `FuncionarioLista`/`FuncionarioForm`/
   `ConfiguracaoTelaFuncionario` + `lib/funcionarios.ts`, replicando tudo que a tela de
   cliente consolidou: shell de altura travada, cabeçalho e rodapé fixos, ordenação por
   coluna com cabeçalho em destaque, paginação em janela deslizante fixa em 50 itens, três
   ícones de ação (visualizar verde/editar azul/excluir vermelho), modo somente-leitura via
   `<fieldset disabled>`, grid compacta, maiúsculas sempre, validação por campo (blur +
   submit), ajuda contextual (R22). Ícone novo `IconeFuncionario` (maleta) à esquerda do
   título; item "Funcionários" no menu lateral.

5. **Máscara de percentual reaproveitável** (`mascararPercentual`/`formatarPercentual`/
   `desmascararPercentual` em `lib/masks.ts`) para o campo "% Comissão" — mesma técnica da
   máscara de moeda já existente (dígitos digitados contam da direita para a esquerda como
   centésimos: digitar "550" vira "5,50").

6. **Layout do formulário ajustado depois do primeiro teste visual** (dois pedidos do dono do
   produto): CPF e Celular passaram a dividir a mesma linha (6+6 colunas), e o Nome passou a
   ocupar a linha inteira (12 colunas, era 8) — deixando as três linhas do formulário
   simétricas. A seção "Contato" separada foi absorvida pela "Identificação" (com só um campo
   de contato não se justificava).

7. **Verificação:** **10 testes novos** (`FuncionarioCrudTest`) — suíte completa em **43/43
   verdes**; `tsc -b` sem erros; API recompilada. Testado ao vivo no navegador: criar
   funcionário com CPF/celular/cargo/comissão mascarados corretamente → salvou e apareceu na
   listagem; modo visualizar com campos desabilitados; ordenação por coluna; e a configuração
   de tela ponta a ponta (marcar "Cargo" como obrigatório fez o campo ganhar `*` e bloquear o
   submit vazio).

### 2026-07-21 — CPF/CNPJ: suporte ao CNPJ alfanumérico (Receita Federal, a partir de julho/2026) — convenção para toda tabela com campo CNPJ

Bug reportado pelo dono do produto: o campo CPF/CNPJ não aceitava letras quando a pessoa era
jurídica. Investigação (pesquisa na internet, ver fontes abaixo) confirmou que **não era bug do
sistema** — é uma mudança real e recente da Receita Federal: a partir de julho/2026 (Instrução
Normativa RFB 2.229/2024), o CNPJ passa a ser **alfanumérico**. Vira **convenção do projeto**,
registrada aqui para qualquer tabela futura que tenha campo de CNPJ (fornecedor, empresa/tenant
etc. — ver `docs/telas/cliente.md` para os detalhes de implementação).

1. **O que mudou no CNPJ:** 14 caracteres, como sempre. As 12 primeiras posições (raiz+ordem)
   agora podem ser dígitos `0-9` **ou** letras `A-Z` maiúsculas; os 2 dígitos verificadores
   finais (posições 13-14) continuam **sempre numéricos**. CNPJs só-numéricos emitidos antes da
   mudança continuam válidos (o cálculo é o mesmo, só a tabela de valor por caractere ficou mais
   ampla). **CPF não muda** — continua só numérico, 11 dígitos, sem alteração nenhuma.

2. **Algoritmo do dígito verificador — confirmado com exemplo oficial antes de implementar.**
   Valor de cada caractere = código ASCII menos 48 (dígitos '0'-'9' viram 0-9 — o próprio valor,
   já que '0' é ASCII 48 — e letras 'A'-'Z' viram 17-42). Pesos e módulo 11 **não mudaram**: 1º
   dígito verificador com pesos `[5,4,3,2,9,8,7,6,5,4,3,2]` sobre os 12 primeiros caracteres; 2º
   com pesos `[6,5,4,3,2,9,8,7,6,5,4,3,2]` sobre os 12 + o 1º DV; resto da divisão por 11 vira
   dígito por `resto < 2 ? 0 : 11 - resto`. Verificado manualmente com o exemplo oficial
   `12.ABC.345/01DE-35` (soma ponderada bate com os dígitos 3 e 5) antes de escrever qualquer
   código — inclusive uma verificação em Node standalone reproduzindo o exemplo.

3. **Frontend (`web/src/lib/masks.ts`):** nova função `somenteAlfanumerico()` (maiúsculas,
   mantém `0-9A-Z`, ao contrário de `somenteDigitos()` que descartava letras como se fossem
   máscara). `mascararCpfCnpj()` usa `somenteAlfanumerico` só para CNPJ (pessoa jurídica); CPF
   continua com `somenteDigitos`, sem mudança. `cnpjValido()`/`documentoValido()` passaram a
   usar `charCodeAt(0) - 48` em vez de `Number()` puro (que dava `NaN` para letras), e exigem
   que as posições 13-14 sejam dígitos. `lib/clientes.ts` (`paraRequisicao`) e `ClienteForm.tsx`
   (checagem de duplicidade ao sair do campo) também pararam de usar `somenteDigitos` no CNPJ —
   senão as letras seriam descartadas antes de chegar na API.

4. **Backend (`Documentos.java`/`ClienteService.java`):** mesma lógica em Java. Achado
   interessante: a função `digitos()` já fazia `c - '0'` — que **por coincidência já era** a
   fórmula "ASCII menos 48" —, então o cálculo do dígito verificador não precisou mudar; só
   faltava não descartar as letras antes (`somenteDigitos` → `somenteAlfanumerico` nos dois
   pontos que persistiam/filtravam `cpfCnpj`: INSERT/UPDATE em `adicionarCamposComuns` e o
   filtro de busca em `listar`, ambos agora condicionais por `fisicaJuridica`). Coluna
   `cliente.cpf_cnpj` já era `text` no banco — **sem migration necessária**.

5. **2 testes novos** (`ClienteCrudTest`): CNPJ alfanumérico válido é aceito e fica armazenado
   em maiúsculas; CNPJ alfanumérico com dígito verificador errado é rejeitado. **33/33 testes
   verdes** na suíte completa.

6. **Verificação ao vivo:** criado cliente PJ com CNPJ `12.ABC.345/01DE-35` — máscara aceitou as
   letras e formatou corretamente; salvou com sucesso; aparece na listagem com o CNPJ correto;
   tentar cadastrar o mesmo CNPJ noutro cliente acusa "CNPJ já cadastrado para outro cliente"
   (confirma que a checagem de duplicidade também passou a considerar as letras).

**Fontes consultadas:**
[Receita Federal — CNPJ alfanumérico](https://www.gov.br/receitafederal/pt-br/centrais-de-conteudo/publicacoes/perguntas-e-respostas/cnpj/cnpj-alfanumerico.pdf) ·
[Serpro — cálculo dos DVs](https://www.serpro.gov.br/menu/noticias/videos/calculodvcnpjalfanaumerico.pdf) ·
[Cobol Dicas — módulo 11 do CNPJ alfanumérico](https://coboldicas.com.br/blog/o-calculo-do-modulo-11-para-o-novo-cnpj-alfanumerico) ·
[TOTVS Espaço Legislação](https://espacolegislacao.totvs.com/cnpj-alfanumerico/) ·
[GitHub — FRACerqueira/CnpjAlfaNumerico](https://github.com/FRACerqueira/CnpjAlfaNumerico)

### 2026-07-21 — Ícone de identificação da tela (pessoa/engrenagem), à esquerda do título, 20% maior

Pedido do dono do produto: fixar visualmente a função de cada tela com um ícone à esquerda do
título — mesma ideia do `AjudaDaTela`/ícone de configuração, mas para a própria identidade da
tela.

1. **Ícone novo `IconeCliente`** (Heroicons outline "user", `components/Icones.tsx`) à esquerda
   do título em `ClienteLista.tsx` ("Clientes") e `ClienteForm.tsx` ("Cliente"). A tela de
   Configuração de campos reaproveita o `IconeEngrenagem` já existente, à esquerda de
   "Configurar tela de Cliente" — mesmo ícone do botão ⚙, mas maior e sem o círculo de fundo do
   botão.
2. **Classe nova `.titulo-tela`** (`styles.css`): `display:flex; align-items:center; gap:10px`,
   ícone na cor `--accent`.
3. **20% maiores** (pedido em seguida, mesmo dia): tamanho explícito passado via prop —
   `IconeCliente` de 28px (default) para 34px; `IconeEngrenagem` nesse contexto de 24px
   (default) para 29px. Não afeta os outros usos do `IconeEngrenagem` (botão ⚙ circular), que
   força 26px via CSS própria (`.ajuda-gatilho svg`), independente da prop.
4. **Verificação:** `tsc -b` sem erros; testado ao vivo nas 3 telas (Clientes, Cliente,
   Configurar tela de Cliente) — ícone visível e maior, inclusive com o formulário rolado
   (confirma que o cabeçalho continua fixo).

### 2026-07-21 — Cabeçalho fixo da grade: correção definitiva de dois bugs reais (não só CSS solto)

Dois bugs encontrados pelo dono do produto ao usar a tela de verdade (não apareciam nos meus
testes anteriores porque eu não tinha reproduzido as condições exatas) — a causa raiz de ambos
era arquitetural, não um ajuste cosmético de CSS.

1. **"Ainda rolo com o botão do mouse, e o título não fica fixo."** Causa: `html`/`body` não
   tinham `overflow: hidden`. Se `.app` (que usa `height: 100vh`) ficasse **1px mais alto** que
   o viewport real por qualquer motivo (zoom do navegador, escala do Windows, arredondamento),
   o **documento inteiro** passava a rolar — inclusive o menu lateral e qualquer cabeçalho
   "fixo" — porque esse scroll do documento não passa pelos containers internos
   (`.app-main`/`.lista-corpo`) que fazem o scroll controlado. Reproduzido de propósito
   (`document.body.style.zoom = '2'` via DevTools) antes de aplicar o fix, para confirmar a
   causa antes de mexer. Fix: `html, body, #root { height: 100%; overflow: hidden; }`.

2. **"O título CAMPO/VISÍVEL/OBRIGATÓRIO some ao rolar a grade de Clientes."** Causa distinta:
   `.table-wrap` tinha `overflow-x: auto` — que sozinho já força o navegador a tratar
   `overflow-y` como `auto` também (regra do próprio CSS: se um eixo não é `visible` e o outro
   é, o outro vira `auto`), criando um **contexto de rolagem próprio** nesse elemento. Como
   `.table-wrap` não tinha altura definida, esse contexto nunca chegava a rolar de verdade — quem
   rolava era o `.lista-corpo` por fora — mas o cabeçalho `position: sticky` da tabela gruda no
   contexto de rolagem mais próximo, que era o `.table-wrap` errado, não o `.lista-corpo`. Fix:
   `.table-wrap` ganhou `height: 100%` (passa a ser ele mesmo o contexto que realmente rola,
   dentro do espaço que o `.lista-corpo` já reserva). De quebra, a regra `position: sticky` do
   cabeçalho (`.table th`) — que só existia especificamente para a tabela de Configuração de
   Tela (`.table-config-campos thead th`) — foi generalizada para **toda** tabela do projeto.

3. **Verificação:** reproduzido o cenário de zoom via DevTools antes e depois do fix (documento
   parou de ter overflow); testado ao vivo rolando a grade de Clientes com a janela do navegador
   redimensionada para forçar overflow real — cabeçalho `NOME/RAZÃO SOCIAL...` permanece fixo no
   topo da tabela enquanto as linhas passam por baixo.

### 2026-07-21 — Barra de rolagem no padrão de cores da tela

Ajuste de polimento visual em todo o projeto (`web/src/styles.css`), pedido pelo dono do
produto ao notar que a barra de rolagem nativa do navegador (cinza claro) destoava do tema
escuro do ERP. Nova regra global (`*`, `scrollbar-width`/`scrollbar-color` para Firefox +
`::-webkit-scrollbar*` para Chrome/Edge) usa os mesmos tokens de cor do design system
(`--surface-2` na trilha, `--line-strong` na alça, `--ink-muted` no hover) — acompanha sozinha
o tema claro/escuro, sem regra separada por tema. Sem mudança de HTML/JSX, só CSS. Testado ao
vivo na listagem e no formulário de Clientes: barra discreta, na mesma paleta escura do resto
da tela.

### 2026-07-21 — Clientes: paginação fixa em 50, ordenação por coluna, ícone de visualizar, validação de backend reforçada, grid mais compacta

Rodada grande de 6 pedidos do dono do produto sobre a listagem/formulário de Clientes — a
mais substancial desde o CRUD original, porque o item 4 revelou (e corrigiu) uma lacuna real
de validação e um bug de isolamento entre tenants no ambiente de teste.

1. **Itens por página fixado em 50, sem seletor.** O seletor 10/20/50 (introduzido horas
   antes, ver entrada abaixo) saiu de novo — `ClienteLista.tsx` agora sempre pede
   `tamanho: 50` (constante `TAMANHO_PAGINA`), sem escolha na tela.

2. **Ordenação por coluna, com cabeçalho em destaque.** Cada cabeçalho da grade
   (Nome/Razão Social, CPF/CNPJ, Categoria, Celular, Cidade/UF, Status) agora ordena
   ASC/DESC ao ser clicado — segunda revisão do dono do produto pediu para deixar isso mais
   óbvio, então o cabeçalho ganhou fundo destacado (`--surface-2` + borda inferior mais
   grossa) e **todo** cabeçalho mostra um ícone "⇅" (não só o ordenado no momento, que vira
   "▲"/"▼" na cor de destaque) — sinaliza que a coluna é clicável antes mesmo do primeiro
   clique. Backend: `ClienteService.listar` ganhou `ordenarPor`/`direcao`, com uma
   allowlist de colunas (`COLUNAS_ORDENAVEIS`) mapeando a chave da API para a expressão SQL
   — nunca concatena o parâmetro do cliente direto na query. Desempate sempre por
   `id_cliente` (mesma direção) para a paginação continuar estável.

3. **Ícone verde "visualizar" antes de editar/excluir.** Terceiro ícone na grade
   (`.acao-visualizar`, novo token de cor `--sucesso`) leva a uma nova rota,
   `/clientes/:id/visualizar`, que reaproveita o `ClienteForm` inteiro com uma prop nova
   `somenteLeitura` — todo o formulário vira `<fieldset disabled>` (desabilita todo campo/
   botão descendente de uma vez, sem repetir em cada input) e o botão Salvar some do
   cabeçalho (só "Voltar" fica). Nenhum componente novo de "modo leitura" — o mesmo
   formulário serve para criar/editar/visualizar.

4. **Validação de servidor reforçada — só o frontend validava antes.** Auditoria pedida pelo
   dono do produto ("as validações do botão salvar estão só no frontend?") confirmou que
   sim: `ClienteService.validar` só checava gênero (PF), data de nascimento e dígito
   verificador de CPF/CNPJ — formato de e-mail, celular/WhatsApp (11 dígitos + 3º=9), CEP (8
   dígitos) e a obrigatoriedade configurável por tenant (`cfg_tela_campo`) só existiam no
   `ClienteForm.tsx`. Corrigido: `ClienteService` passou a injetar `ConfiguracaoTelaService`
   e replicar as mesmas regras no servidor (defesa em profundidade — a API nunca deve confiar
   só no frontend). **Bug real encontrado no caminho:** `ConfiguracaoTelaService.listar`
   filtrava só por `chave_tela`, dependendo inteiramente do RLS para isolar por tenant — no
   ambiente de teste (datasource conecta como superusuário, sem RLS) isso vazava
   configuração **entre tenants diferentes**, faria (e fez, nos testes novos) um campo
   marcado obrigatório por um tenant "vazar" como obrigatório para todos os outros. Corrigido
   com filtro explícito `AND id_tenant = plataforma.tenant_atual()` — defesa em profundidade
   consistente com o padrão já usado nos `INSERT` do módulo. **5 testes novos** em
   `ClienteCrudTest` (e-mail/celular/CEP inválidos rejeitados, campo obrigatório configurado
   é exigido pela API, ordenação por coluna/direção) — **31/31 testes verdes** na suíte
   completa.

5. **Grid de dados mais compacta.** Nova classe `.table-compacta` reduz o padding vertical
   das linhas (12px → 6px) — só aplicada na listagem de Clientes por ora.

6. **Formulário de cadastro com topo fixo.** Mesmo padrão já usado na listagem e na tela de
   configuração (ver entradas abaixo): `ClienteForm.tsx` passou a usar
   `.lista-tela`/`.lista-topo`/`.lista-corpo` — título "Cliente" + Cancelar/Salvar não rolam,
   só o corpo do formulário.

7. **Verificação:** `mvn test` 31/31 verde (suíte completa, Testcontainers); `tsc -b` sem
   erros; API recompilada e reiniciada. Testado ao vivo no navegador: cabeçalhos de coluna
   com fundo destacado e ícone "⇅"/"▲"/"▼"; clique em "Cidade/UF" ordena ASC depois DESC;
   ícone verde abre `/clientes/:id/visualizar` com todos os campos desabilitados e sem
   Salvar; edição normal de um cliente com CPF válido salva com sucesso (confirma que a nova
   validação de backend não quebrou o caminho feliz); grade visivelmente mais densa. Aprovado
   pelo dono do produto.

### 2026-07-21 — Configuração de tela: cabeçalho enxuto (Cancelar/Salvar no topo) + topo fixo

Mesmo tratamento já aplicado ao formulário de Cliente (ver duas entradas abaixo), agora
em `ConfiguracaoTelaCliente.tsx`: removido o "CADASTROS" acima do título (ficou só
"Configurar tela de Cliente"); botões **Cancelar**/**Salvar** subiram do rodapé para o
cabeçalho, ao lado do título; tela passou a usar `.lista-tela`/`.lista-topo`/`.lista-corpo` —
cabeçalho fixo, só a tabela de campos configuráveis rola. Sem mudança de backend. Testado ao
vivo: cabeçalho fixo confirmado rolando a lista de ~16 campos configuráveis; "Cancelar"
navega de volta para `/clientes` corretamente.

### 2026-07-21 — Clientes: paginação em janela deslizante (estilo grid legado) + ações da linha viram ícones

Duas mudanças visuais na listagem de Clientes, pedidas com uma captura de tela do sistema
legado (Firebird/Delphi, tela `P119 - Cadastro de Produtos`) como referência de estilo —
primeira vez que um mockup do sistema antigo é usado como golden file de um componente novo
(não só o `docs/padroes/` já existente).

1. **Paginação virou janela deslizante com primeira/anterior/próxima/última.** Antes: só
   "1 2 3 4 5 … 9 10" (5 primeiras fixas + últimas 2). Agora: até 7 números **centrados na
   página atual** (`JANELA_PAGINACAO = 7` em `ClienteLista.tsx`, função `paginasVisiveis`
   recalculada a cada mudança de página — sem reticências, a janela desliza sozinha) mais
   4 botões de ícone nas pontas — **primeira página** (`«`), **anterior** (`‹`), **próxima**
   (`›`) e **última página** (`»`) —, cada um desabilitado quando não faz sentido (ex.:
   "primeira"/"anterior" cinza na página 1). Ícones novos em `components/Icones.tsx`
   (`IconePrimeiraPagina`/`IconePaginaAnterior`/`IconeProximaPagina`/`IconeUltimaPagina`,
   Heroicons `chevron-double-left`/`chevron-left`/`chevron-right`/`chevron-double-right`).

2. **Ações da linha (Editar/Excluir) viram ícones coloridos, não mais texto.** Mesma
   referência do sistema legado tinha três símbolos (👁 verde/✏ azul/🗑 vermelho); o dono do
   produto pediu só os dois que fazem sentido aqui — **não existe modo "só visualizar"**
   separado de editar nesta tela, então o verde (observar) ficou de fora. Botões quadrados de
   32×32, ícone branco: **azul** (`.acao-editar`, token novo `--info`) para editar, **vermelho**
   (`.acao-excluir`, reaproveita `--danger`) para excluir — com `aria-label`/`title`
   descritivos (`"Editar {nome}"`/`"Excluir {nome}"`) já que o texto visível some. Ícones novos
   `IconeEditar` (pencil-square) e `IconeExcluir` (trash), mesmo padrão Heroicons outline dos
   demais ícones do projeto.

3. **Verificação:** `tsc -b` sem erros. Testado ao vivo no navegador com 92 clientes: ícones
   azul/vermelho aparecem nítidos na tabela; clique em "última página" pulou direto pra
   página 10 (janela reajustou para mostrar 4–10, "próxima"/"última" desabilitadas); clique em
   "primeira página" voltou pra página 1 (janela 1–7, "primeira"/"anterior" desabilitadas).
   Aprovado pelo dono do produto.

### 2026-07-21 — Cliente: cabeçalho do formulário enxuto (Cancelar/Salvar no topo), identificação numa linha, limite de crédito com máscara de moeda

Três ajustes pontuais pedidos pelo dono do produto no formulário de cliente (`ClienteForm.tsx`),
sem mudança de backend/migration — só frontend.

1. **Cabeçalho enxuto com ações no topo.** O título deixou de variar entre "Novo cliente"/
   "Editar cliente" (com "CADASTROS" acima) — agora é só **"Cliente"**. Os botões
   **Cancelar**/**Salvar**, que ficavam num `footer-bar` no fim da página (exigindo rolar até
   o final para salvar), subiram para a faixa superior, ao lado dos ícones de ajuda/
   configuração. O botão Salvar (`type="submit"`) é renderizado fora da árvore do `<form>`
   (o cabeçalho vem antes do formulário no JSX) — associado via atributo HTML padrão
   `form="form-cliente"`, então continua disparando a validação e o submit normalmente.

2. **Linha de identificação compacta.** O checkbox "Cliente ativo" e os rádios "Pessoa
   Física"/"Pessoa Jurídica", antes em duas linhas separadas, passaram a ficar lado a lado
   numa única linha (`.identificacao-linha`, novo em `web/src/styles.css`).

3. **Limite de crédito com máscara de moeda.** O campo aceitava texto livre (só convertia
   vírgula para ponto no envio); agora mascara a digitação como dinheiro — mesma convenção de
   caixa eletrônico/app de banco: os dígitos digitados são sempre os centavos, contados da
   direita para a esquerda (digitar "150000" mostra "1.500,00" em tempo real). Três funções
   novas em `web/src/lib/masks.ts`: `mascararMoeda` (aplicada no `onChange`),
   `desmascararMoeda` (desfaz para enviar à API) e `formatarMoeda` (formata o número vindo da
   API ao abrir o formulário de edição — `lib/clientes.ts:paraFormulario`).

4. **Verificação:** `tsc -b` sem erros. Testado ao vivo no navegador (cliente existente com
   `limite_credito = 3006.79`): cabeçalho mostra só "Cliente" com Cancelar/Salvar junto dos
   ícones; "Cliente ativo"/"Pessoa Física"/"Pessoa Jurídica" na mesma linha; campo de limite
   de crédito abre já formatado "3.006,79"; digitar "150000" vira "1.500,00" em tempo real;
   clique em Salvar (fora da árvore do `<form>`) disparou a validação normalmente (acusou CPF
   inválido de um registro de teste com CPF fictício, confirmando que o `form="form-cliente"`
   funciona). Aprovado pelo dono do produto.

### 2026-07-21 — Clientes: paginação numerada (1 2 3 … 9 10) + shell fixo (menu/topo/rodapé sem scroll)

Pedido do dono do produto depois de ver a listagem com 109 clientes de teste: a tela crescia
indefinidamente ("Carregar mais" ia empilhando linhas), o menu lateral/cabeçalho rolavam junto
com o conteúdo, e faltava pular direto para uma página qualquer. Duas rodadas no mesmo dia — a
primeira trocou o scroll infinito por "← Anterior/Próxima →"; o dono do produto pediu em
seguida uma grade numerada de páginas, o que forçou trocar o mecanismo de paginação por baixo.
Vira **convenção nova do shell do ERP**, não só da tela de Clientes.

1. **Backend — paginação por número de página, não mais por cursor.** `ClienteService.listar`
   trocou o cursor opaco (`WHERE (nome, id) > (?, ?)`) por **`LIMIT`/`OFFSET`** com contagem
   total (`SELECT count(*)` com os mesmos filtros) — necessário porque a navegação numerada
   pedida ("1 2 3 4 5 … 9 10") exige saber quantas páginas existem no total e permitir pular
   direto para qualquer uma, o que um cursor opaco não oferece. `ORDER BY nome, id_cliente`
   mantido (empate resolvido pelo id). `PaginaClientes` passou a devolver
   `{itens, pagina, tamanhoPagina, totalItens, totalPaginas}` em vez de `{itens, proximoCursor}`;
   `GET /api/v1/clientes` trocou o parâmetro `cursor` por `pagina` (1-based). Volume de
   clientes por tenant é pequeno o bastante para `OFFSET` não pesar — decisão registrada no
   código, não vira ADR. Sem migration — só ordenação/paginação, schema não mudou. **10 testes
   de `ClienteCrudTest` seguem verdes.**

2. **Frontend — grade de páginas numeradas.** `ClienteLista.tsx` trocou o histórico de cursores
   por um estado simples `pagina` (número, 1-based) — permite pular direto para qualquer página
   sem precisar visitar as intermediárias. Nova função `paginasVisiveis()` monta a navegação
   **"1 2 3 4 5 … (penúltima) (última) →"** (sem reticências quando cabem todas as páginas, até
   7). Trocar nome/categoria/status/tamanho de página volta automaticamente para a página 1.
   Seletor **itens por página** (10/20/50, **padrão 10**). `lib/clientes.ts` acompanhou os tipos
   (`cursor` → `pagina`; `proximoCursor` → `pagina`/`tamanhoPagina`/`totalItens`/`totalPaginas`).

3. **Busca por nome em maiúsculas.** O campo "Buscar por nome…" passou a normalizar o texto
   digitado em tempo real (`maiusculas()`, mesma função usada nos formulários) — consistente
   com a convenção de maiúsculas sempre (§3.7) e com o fato de os nomes já serem salvos em
   maiúsculas no banco (busca por `ILIKE`, então a caixa em si não afeta o resultado, mas a UI
   fica consistente com o resto da tela).

4. **Rodapé da paginação fixo, só a tabela rola.** A grade de páginas saiu de dentro de
   `.lista-corpo` (que rola) e virou uma terceira faixa, `.lista-rodape`
   (`flex-shrink: 0`, mesmo tratamento de `.lista-topo`) — agora a tela de Clientes tem três
   faixas: topo fixo (título+filtros), meio rolável (só a tabela) e rodapé fixo (contagem de
   clientes + paginação numerada).

5. **Shell do ERP com altura travada no viewport (`web/src/styles.css`).** Antes, `.app` só
   tinha `min-height: 100vh` — qualquer tela com conteúdo alto (ex.: tabela de clientes)
   crescia o `<body>` inteiro, arrastando o menu lateral (`.app-nav`) e o cabeçalho
   (`.app-header`) para fora da viewport ao rolar. Agora `.app` usa `height: 100vh` +
   `overflow: hidden`, e é `.app-main` quem ganha `overflow-y: auto` — o menu lateral e o
   cabeçalho superior do ERP nunca mais rolam, em **qualquer tela**, não só Clientes.

6. **Cabeçalho da listagem mais baixo.** Removido o texto "CADASTROS" (eyebrow) acima do
   título — ficou só **"Clientes"** — reduzindo a altura da faixa superior. A tela usa três
   classes novas (`styles.css`): `.lista-tela` (coluna, altura 100% do `.app-main`),
   `.lista-topo` e `.lista-rodape` (fixos, `flex-shrink: 0`) e `.lista-corpo` (só a tabela,
   `overflow-y: auto`). Convenção pensada para ser reaproveitada nas próximas telas de listagem
   (Fornecedor/Funcionário/Produtos).

7. **Verificação:** `mvn compile` limpo + `ClienteCrudTest` 10/10 verde; `tsc -b` sem erros; API
   recompilada e reiniciada (`docker compose build api && docker compose up -d api`) —
   `/actuator/health` OK. Testado ao vivo no navegador com 92 clientes ativos de teste: busca
   "carlos" vira "CARLOS" ao digitar e filtra corretamente; clique direto na página 9 (sem
   passar pelas intermediárias) mostra o bloco alfabético certo (TATIANE → UBIRAJARA); rodapé
   com contagem ("92 clientes") e paginação continua visível e no lugar ao rolar a tabela; "→"
   na última página (10) fica desabilitado. Aprovado pelo dono do produto.

### 2026-07-21 — Ícones maiores (ajuda/configuração) + grid que se reajusta ao ocultar campo

Dois ajustes de polimento visual, só frontend (sem mudança de backend/migration):

1. **Ícones reais (Heroicons, MIT) no lugar de texto/emoji.** `web/src/components/Icones.tsx`
   (novo) exporta `IconeAjuda` e `IconeEngrenagem` com o SVG original de
   `cog-6-tooth`/`question-mark-circle` (baixado do repositório oficial
   `tailwindlabs/heroicons`). Substituem o `?` de texto (`AjudaDaTela.tsx`) e o `⚙` de texto
   (`ClienteForm.tsx`/`ClienteLista.tsx`). O botão `.ajuda-gatilho` (`styles.css`) cresceu de
   34×34px para **46×46px**, ganhou fundo (`--surface-2`), borda e estado de hover/active
   mais visível — pedido explícito do usuário por ícones "maiores e mais visíveis".

2. **Grid se reajusta quando um campo fica oculto.** Antes, ocultar um campo pela tela de
   configuração (`/clientes/configuracao`) deixava um vão vazio na linha (ex.: ocultar RG
   fazia CPF/Nascimento/Gênero ficarem alinhados à esquerda com um buraco de 3 colunas à
   direita). Agora cada linha configurável do formulário usa o componente novo
   `LinhaGrid.tsx` + função `distribuirSpans()` (`lib/grid.ts`): os pesos relativos dos
   campos visíveis (mesma escala dos antigos `col-N`) são redistribuídos para somar sempre
   **12 colunas exatas**, via método dos maiores restos (evita erro de arredondamento). As
   6 linhas do formulário que têm campo configurável passaram a usar `LinhaGrid`:
   CPF/RG/Nascimento/Gênero · E-mail/Celular/Id. WhatsApp · Instagram/Facebook/TikTok ·
   CEP/Endereço · Número/Complemento/Bairro · Cidade/UF.

3. **Verificação:** `tsc -b` sem erros; testado ao vivo — com RG, Id. WhatsApp e Instagram
   já ocultos (configuração salva de teste anterior), os campos restantes de cada linha
   cresceram para preencher o espaço (ex.: CPF/Nascimento/Gênero cada um a 1/3 da linha em
   vez de 1/4 com vão vazio), e os ícones de ajuda/configuração aparecem maiores no canto
   superior direito com fundo circular.

### 2026-07-21 — Configuração de tela (campos visíveis/obrigatórios), reutilizável entre telas

Nova capacidade **transversal** (não é só do Cliente): o lojista (ADMIN) passa a poder
escolher, por tenant, quais campos aparecem e quais são obrigatórios em cada tela do
produto — pedido explicitamente como algo a reaproveitar nas "próximas telas" que vão ser
desenvolvidas. Ver `docs/telas/configuracao-tela.md` (spec completa).

1. **Migration nova (`V027__cfg_tela_campo.sql`)** — não mexe em nenhuma migration já
   aplicada; o dev **não recriou o banco**, só rodou `docker compose run --rm flyway` (que
   aplicou apenas a V027 em cima do schema já existente). Tabela `cfg_tela_campo`
   (`id_tenant, chave_tela, campo` → `visivel`, `obrigatorio`), RLS próprio no arquivo,
   `CHECK` impedindo campo obrigatório e oculto ao mesmo tempo.

2. **Backend — módulo novo `comum.telaconfig`** (não é `cadastros.cliente`, é
   propositalmente genérico): `ConfiguracaoTelaController`/`Service` —
   `GET/PUT /api/v1/config-tela/{chaveTela}`. O registro de quais campos são configuráveis
   em cada tela é um mapa estático no serviço (`CAMPOS_POR_TELA`); a primeira entrada é
   `cadastros.cliente.form`, com os 16 campos "de negócio" do formulário (CPF/CNPJ até
   Limite de crédito — Nome/Categoria são `NOT NULL` no banco e não entram; Data de
   nascimento/Gênero têm regra própria já fechada e também não entram). Reaproveita a
   convenção `chave_tela` já usada pelo catálogo de ajuda (R22). **Só ADMIN grava** — `403`
   para `OPERADOR`, checado a partir do claim `roles` do JWT (primeira vez que uma
   autorização por papel é de fato aplicada no projeto; R8 tinha essa intenção mas nunca
   tinha sido implementada em nenhum endpoint até agora). Qualquer usuário do tenant **lê**
   (o formulário precisa saber como se renderizar não importa o papel). **5 testes novos**
   (`ConfiguracaoTelaTest`): default sem configuração, ADMIN salva e o GET reflete, OPERADOR
   é rejeitado, campo não configurável é rejeitado, obrigatório+oculto é rejeitado.

3. **Frontend:** `ConfiguracaoTelaCliente.tsx` (`/clientes/configuracao`) — tabela com
   checkbox Visível/Obrigatório por campo (Obrigatório desabilita se Visível estiver
   desmarcado, refletindo a regra do banco). `RequireAdmin.tsx` (novo, mesmo padrão de
   `RequireAuth.tsx`) protege a rota — `OPERADOR` que acessar a URL direto volta pro Painel.
   Ícone **⚙** ao lado do `?` de ajuda (`ClienteForm` e `ClienteLista`), visível só para
   `ADMIN` (checado via `useEu()`, hook novo compartilhado — `Dashboard.tsx` também
   refatorado pra usá-lo, em vez de duplicar a query). `ClienteForm` passou a ler a
   configuração (`lib/configuracaoTela.ts`) e aplicar visibilidade/obrigatoriedade em tempo
   real: campo oculto some do formulário (e da validação); campo obrigatório ganha `*` no
   rótulo e passa a bloquear submit se vazio — em cima da validação por campo já existente
   (blur + submit) da rodada anterior.

4. **Verificação:** 26 testes de backend verdes; testado ao vivo no navegador — ocultar
   Instagram fez o campo sumir do formulário, marcar E-mail como obrigatório fez aparecer
   `*` no rótulo e bloquear o campo vazio (mensagem "Campo obrigatório." no blur e no
   submit).

### 2026-07-21 — Cliente: validações de UX (foco, tab, CEP, pop-up, campo a campo) + regras de negócio (nascimento, celular)

Duas rodadas de refinamento pedidas pelo dono do produto depois de testar a tela manualmente
(ver rodada de 2026-07-20 abaixo), sem mudança de escopo — só a tela de Cliente ficando mais
madura. Nenhum código novo de módulo; tudo em `cadastros.cliente` e `web/`.

**Rodada 1 — UX de formulário:**
1. Foco automático agora vai para o campo **Nome** (não mais o checkbox "Cliente ativo",
   que continua sendo o primeiro campo visualmente, só não recebe o foco).
2. Botão "＋ Nova categoria" saiu da ordem de tabulação (`tabIndex={-1}`) — com a categoria
   já escolhida, Tab vai direto para o CPF/CNPJ.
3. CEP: mensagem "CEP inválido." tanto para formato incompleto (menos de 8 dígitos, ao sair
   do campo) quanto para CEP não encontrado no ViaCEP (verificado assim que completa 8
   dígitos, sem esperar o blur).
4. **Pop-up de erro** (`web/src/components/Toast.tsx`, canto superior direito, fecha sozinho
   ou no clique) substitui a mensagem que ficava no rodapé da página — usado no formulário de
   cliente e no modal de categoria. Depois pedido para ficar **vermelho sólido com letras
   brancas** (antes era neutro com borda vermelha).
5. **Validação por campo**, não mais uma mensagem genérica só no rodapé: cada campo valida
   ao sair dele (`onBlur`) e de novo no submit; erro aparece embaixo do campo específico.
   `noValidate` no `<form>` para a validação customizada substituir a nativa do HTML5.

**Rodada 2 — regras de negócio + layout:**
6. **Data de nascimento**: não pode ser hoje nem no futuro (quando preenchida) — validado no
   front e no back. **Depois revisto: deixou de ser obrigatória** mesmo para pessoa física
   (só o gênero continua obrigatório) — a constraint `cliente_dados_pessoais_ck` (V016) foi
   **editada** (banco ainda em construção) para exigir só `genero IS NOT NULL`; banco
   recriado do zero para aplicar. Backend (`ClienteService.validar`) e frontend acompanham.
7. **Celular/WhatsApp**: exigem 11 dígitos com o 3º dígito = 9 (padrão de celular BR) quando
   preenchidos. Rótulo "Telefone" virou **"Celular"**; rótulo "WhatsApp" virou **"Id.
   WhatsApp"**, com máscara própria (`mascararIdWhatsapp`, prefixo `@` + dígitos, mesma
   convenção visual de Instagram/Facebook/TikTok) em vez do formato `(00) 00000-0000`.
8. **CPF/CNPJ duplicado**: ao sair do campo, verifica se já existe outro cliente com aquele
   documento (reaproveita `GET /api/v1/clientes?cpfCnpj=...&status=TODOS`, sem endpoint
   novo) e avisa; na edição, ignora o próprio registro.
9. **Layout horizontal**: `.app-main` (largura útil do conteúdo, todas as telas) foi de
   `900px` para `1600px` — o formulário de cliente reaproveita o espaço juntando Nome +
   Categoria numa linha e CPF + RG + Data de nascimento + Gênero na linha seguinte, em vez de
   empilhado.

**Verificação:** 21 testes de backend verdes (2 novos: nascimento no futuro rejeitado;
pessoa física sem nascimento mas com gênero aceita). Todo o resto (foco, tab, CEP, pop-up,
validação por campo, celular, WhatsApp, CPF duplicado, layout) testado ao vivo no navegador.

### 2026-07-20 — Primeira tela de domínio: Clientes (CRUD ponta a ponta) + convenções de UI

Primeira feature depois da fundação (Fase 0/1): CRUD completo de cliente, do spec à
implementação, testado ao vivo no navegador. Também vira o campo de prova de duas convenções
novas que passam a valer para **todas as telas futuras**.

1. **Spec da feature (`docs/telas/cliente.md`)** — sessão de perguntas e respostas com o dono
   do produto sobre a tabela `cliente`/`cfg_categoria_cliente` (V016): rótulos, máscaras,
   obrigatoriedade, exclusão (inativar quando há venda associada, decisão explícita), gestão
   de categoria embutida (modal criar+renomear), validação de dígito verificador de
   CPF/CNPJ, autopreenchimento de endereço por CEP, permissão (ADMIN e OPERADOR, sem
   restrição de R8).

2. **Backend — módulo `cadastros.cliente` (novo pacote).** `ClienteController`/`ClienteService`
   (`/api/v1/clientes`: listagem paginada por cursor com filtros nome/cpfCnpj/categoria/status,
   criar, buscar, atualizar, excluir) e `CategoriaClienteController`/`CategoriaClienteService`
   (`/api/v1/categorias-cliente`: criar, listar, renomear). `Documentos` valida CPF/CNPJ
   (algoritmo do dígito verificador). Exclusão **verifica vínculo com `venda` antes de agir**
   (não tenta `DELETE` e captura a violação de FK depois — no Postgres isso aborta o resto da
   transação; teria exigido `SAVEPOINT`). `ConflitoDadosException` (409) e handler de
   `IllegalArgumentException` (400) novos no `GlobalExceptionHandler`. **8 testes novos**
   (`ClienteCrudTest`), suíte completa em 19 testes verdes.

3. **Frontend — tela de Clientes (`web/`).** `ClienteLista`/`ClienteForm`,
   `CategoriaClienteModal`, `AjudaDaTela` (R22 — primeiro componente de ajuda contextual do
   projeto, conteúdo embutido como fallback estático até existir o catálogo `ajuda_tela` da
   API), `lib/clientes.ts`, `lib/masks.ts` (máscaras + validação de documento), `lib/viacep.ts`.
   Rotas `/clientes`, `/clientes/novo`, `/clientes/:id`.

4. **Bugs encontrados testando ao vivo no navegador (não em teste automatizado):**
   - `paraFormulario()` não reaplicava as máscaras (CPF/telefone/CEP apareciam crus ao abrir
     "Editar") — corrigido.
   - Uso de `confirm()`/`alert()` nativos do navegador para excluir cliente — trocado por um
     modal de confirmação e um banner in-app (mais consistente com o design system e evita
     diálogos que travam automação de navegador e são uma UX inferior num SPA).
   - Datasource de teste (Testcontainers `@ServiceConnection`) conecta como o superusuário do
     container, não como `niner_app` — uma listagem sem filtro via MockMvc via cross-tenant;
     ajustado o teste para filtrar por nome (o gate de isolamento real continua sendo
     `RlsIsolamentoTest`, que conecta como `niner_app` de propósito).

5. **Cinco ajustes pedidos depois do primeiro teste ao vivo — duas viram convenção do
   projeto, não só desta tela:**
   - **`cliente.complemento`** (texto, nullable, entre `numero` e `bairro`) — coluna nova.
     Banco ainda em construção: a coluna entrou **na própria migration V016** (não uma V027
     nova), exigindo recriar o banco de dev do zero (`docker volume rm niner_pgdata` +
     `flyway migrate`) para aplicar a mudança a uma migration já rodada.
   - **Texto sempre em MAIÚSCULAS, projeto todo** (nova regra em §3.7 da spec): todo campo de
     texto livre é normalizado no `onChange` do frontend (não importa o estado do teclado do
     usuário) e reforçado no backend (`toUpperCase(Locale.ROOT)`) como defesa em profundidade
     — inclusive valores vindos do autopreenchimento de CEP (ViaCEP retorna em
     capitalizado/minúsculo). Única exceção: e-mail, que mantém a caixa digitada.
   - **`Cliente ativo` virou o primeiro campo do formulário**, antes até do tipo de pessoa, e
     passou a aparecer também na criação (antes só no editar).
   - **Grid de 12 colunas do design system (§3.7) finalmente implementado** (documentado desde
     a fundação, nunca usado): campos pequenos (CPF, RG, número, complemento, UF, limite de
     crédito, telefone/WhatsApp) dividem linha com outros em vez de ocupar a largura toda.
   - **Foco automático** no primeiro campo do formulário ao abrir (`autoFocus`), tanto ao
     incluir quanto ao editar — também virou regra do design system (§3.7), não só desta tela.

6. **Verificação:** banco recriado (V001–V026, agora com `complemento`); **19 testes verdes**;
   fluxo completo testado ao vivo no navegador (Chrome, via automação) — signup → login
   (handoff) → criar categoria (criar/renomear) → criar cliente PF com CPF mascarado/validado,
   gênero/nascimento condicionais, CEP autopreenchendo endereço em maiúsculas → listagem com
   filtro de status → excluir cliente com venda associada → inativa e mostra aviso → cliente
   PJ com CNPJ inválido → erro inline correto → painel de Ajuda da Tela → grid compacto,
   `Cliente ativo` como primeiro campo com foco automático, `Complemento` entre Número e
   Bairro, tudo digitado em maiúsculas em tempo real.

7. **Documentação sincronizada:** `docs/telas/cliente.md` (campos/tamanhos/ordem atualizados;
   corrigida uma imprecisão — a exclusão sempre responde `200 OK` em JSON, nunca varia para
   `204`), `spec-driven-erp-varejo.md` (§3.3.9 `cliente.complemento`; §3.7 convenções de
   maiúsculas e foco automático), `db/migration/README.md` (V016).

### 2026-07-16 — Banco recriado do zero: V001–V026 aplicadas, bug real encontrado e corrigido

Dono do produto pediu para recriar a base de dados (primeira vez que isso roda desde as
revisões de schema desta sessão — V014–V026 nunca tinham sido testadas contra um Postgres
real do zero, só revisadas estaticamente).

1. **Recriação:** `docker compose stop api db` + `rm -f` dos containers + `docker volume rm
   niner_pgdata` + `docker compose up -d db` (saudável) + `docker compose run --rm flyway`.
   **26 migrations aplicadas com sucesso** (V001–V026), incluindo os dois guarda-corpos de RLS
   (V024 e V025) sem exceção — nenhuma tabela de tenant ficou sem RLS.
2. **Bug real encontrado no smoke test do signup:** `POST /api/publico/assinar` retornava
   erro ao inserir em `empresa` — `null value in column "codigo_empresa"` (constraint
   `NOT NULL` sem `DEFAULT`, adicionada a `empresa` mais cedo nesta mesma sessão, V014). O
   `SignupService.assinar()` só inserida `id_tenant`/`razao_social`, nunca tinha sido
   atualizado para as colunas novas. Investigação revelou que `cfg_nome_etiqueta` (também
   `NOT NULL` sem `DEFAULT`, mesma tabela) tinha o mesmo problema, só não aparecia ainda
   porque o Postgres erra na primeira coluna `NOT NULL` vazia que encontra (`codigo_empresa`
   vem antes na ordem de colunas).
3. **Fix em `SignupService.java`:** o `INSERT INTO empresa` passou a enviar
   `codigo_empresa = 1` (primeira empresa do tenant, Q6 — 1:1 no v1) e
   `cfg_nome_etiqueta = '{sku}\n{descricao}\n{preco_venda}'` (modelo padrão de etiqueta,
   o lojista personaliza depois — não existe tela para isso ainda). Imagem da API
   reconstruída (`docker compose build api`) e o smoke test repetido com sucesso
   (`201 Created`, tenant novo, token emitido).
4. **Verificação em banco real (via `psql` como `niner_owner`):**
   - As 7 linhas de `moeda` foram semeadas para o tenant de teste — mas só apareceram depois
     de `set_config('app.id_tenant', ...)` na sessão do `psql`: confirma que o `FORCE ROW
     LEVEL SECURITY` das tabelas novas (V025/V026) protege até o **dono** das tabelas, não só
     `niner_app` (P8 funcionando como desenhado).
   - RLS `ENABLE`+`FORCE` confirmado nas 8 tabelas novas (`tipo_carteira`, `moeda`,
     `moeda_detalhe`, `contas_receber`, `contas_receber_detalhe`, `caixa_mestre`,
     `caixa_detalhe`, `contas_pagar`).
   - `\d contas_pagar` confirmou `id_conta_pagar` como PK, `nota_fiscal integer` nullable,
     FKs compostas `(id_tenant, id_x)` para `empresa`/`fornecedor`/`cfg_plano_contas`.
   - `produto_movimento_mestre.nota_fiscal` confirmado como `integer` (padronização do V026
     aplicada de fato).
5. **Pendência do dia resolvida: suíte `./mvnw test` rodada sem JDK no host.** Sem
   `JAVA_HOME`/Java instalados no host, rodei a suíte dentro de um container com a mesma
   imagem do estágio de build do `Dockerfile` (`maven:3.9-eclipse-temurin-25`), montando o
   **repo inteiro** (não só `api/`) + o socket do Docker. Três problemas apareceram e foram
   contornados (documentados em `api/README.md`, seção Testes):
   - **Ryuk não conectava de volta** ao container do Maven (`--network host` não funciona
     como esperado no Docker Desktop) → `TESTCONTAINERS_RYUK_DISABLED=true`.
   - **JDBC apontava pro gateway interno do Docker** (`172.17.0.1`), inalcançável de dentro
     de outro container → `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` resolveu.
   - **`../db/migration` não existia** dentro do container porque só `api/` estava montada
     (o Flyway de teste usa esse caminho relativo) → montei o repo inteiro e rodei com
     `-w /workspace/api`.
   - **Resultado: 11 testes verdes** (`NinerApiApplicationTests` 1, `OnboardingTrialTest` 3,
     `RlsIsolamentoTest` 1, `SuperficiesPingTest` 6) — incluindo o gate P8 e o fluxo de
     signup completo (que já exercita o fix de `codigo_empresa`/`cfg_nome_etiqueta` do item
     acima e o insert de `moeda`). Containers de teste órfãos (Ryuk desabilitado) conferidos
     depois — nenhum ficou para trás.

### 2026-07-16 — V026: contas_pagar antecipada (mais uma revisão de Q5/ADR-010 — ADR-012)

Mesmo padrão do V025: dono do produto pediu análise antes de criar. DDL colado era, de novo,
o legado Firebird (`db/050_CONTAS_PAGAR.txt`) quase sem conversão.

1. **Achados corrigidos** (mesma lista de sempre): sem `id_tenant`; FKs simples e para
   `EMPRESAS`/`FORNECEDORES` (nomes errados — são `empresa`/`fornecedor`); `FLOAT` em
   `valor_pagar`/`valor_pago` (P7); `VARCHAR(1)` S/N em `documento_pago` (virou `boolean`);
   `TIMESTAMP` em vez de `TIMESTAMPTZ`; `GENERATED BY DEFAULT` em vez de `GENERATED ALWAYS AS
   IDENTITY`; `ID_PLANO_CONTAS VARCHAR(13)` precisando virar FK composta pro par
   `(id_tenant, id_plano_contas)` de `cfg_plano_contas` (V016).
2. **`nota_fiscal INTEGER DEFAULT 0`** — flagueado como inconsistente com
   `produto_movimento_mestre.nota_fiscal` (V019), que já era `text` nullable. Dono do produto
   decidiu **manter `integer`** (não `text`) — e pediu para **padronizar em todo o schema**:
   `produto_movimento_mestre.nota_fiscal` mudou de `text` para `integer` (só essa outra
   ocorrência existia). Ambas nullable, sem `DEFAULT 0` (valor mágico removido).
3. **PK renomeada:** campo `localizador` (nome herdado do legado, usado em `caixa_detalhe`/V025)
   virou **`id_conta_pagar`** nesta tabela — pedido explícito do dono do produto, quebrando de
   propósito a consistência de nome com `caixa_detalhe`.
4. **`documento_pago` ganhou `DEFAULT false`** — mesmo tratamento já dado a
   `contas_receber.documento_recebido` (V025), confirmado pelo dono do produto.
5. **Migration `V026__financeiro_contas_pagar.sql` criada** (arquivo novo, não dentro de V025)
   — `contas_pagar` com `id_tenant`, FKs compostas, RLS próprio no arquivo (mesmo motivo de
   V025: o guarda-corpo de V024 já tinha rodado antes desta tabela existir). Legado
   `db/050_CONTAS_PAGAR.txt` removido (já migrado). Documentação sincronizada:
   `db/migration/README.md`, `spec-driven-erp-varejo.md` (§2.7 Q5, §3.3.7, §3.5.1, ADR-012) e
   este arquivo. **Banco não recriado/testado nesta rodada** (convenção da sessão). Com V026,
   só `conta_corrente`/`conta_corrente_movimento` seguem fora do v1 (§3.3.7).

### 2026-07-16 — V025: crediário + caixa antecipados da Fase 2 (revisão de Q5/ADR-010 — ADR-012)

O dono do produto pediu 8 tabelas do legado financeiro (`tipo_carteira`, `moeda`,
`moeda_detalhe`, `contas_receber`/`_detalhe`, `caixa_mestre`/`_detalhe`) e pediu análise antes
de criar (não recriar/testar banco ainda — convenção da sessão).

1. **Revisão de escopo confirmada:** Q5/ADR-010 dizia que todo o `financeiro` (caixa,
   crediário, contas a pagar/receber, conta corrente) ficava fora do v1, para a Fase 2. O dono
   do produto confirmou que quer **antecipar crediário + caixa** para agora — mesmo movimento
   já feito com `cfg_plano_contas` (V016). Vira **ADR-012** (revisa ADR-010).
   `contas_pagar`/`conta_corrente(_movimento)` **continuam fora**, sem migration.
2. **Análise do DDL colado (legado Firebird quase sem conversão)** — achados corrigidos antes
   de criar: faltava `id_tenant` em todas as 8 tabelas; FKs simples e apontando para nomes de
   tabela errados (`VENDAS`/`EMPRESAS`/`USUARIOS` em vez de `venda`/`empresa`/`usuario`, que já
   existem desde V014/V015/V018); `FLOAT` em dinheiro/percentual (P7); `VARCHAR(1)` S/N em vez
   de `boolean`; `TIMESTAMP` em vez de `TIMESTAMPTZ`; `GENERATED BY DEFAULT` em vez de
   `GENERATED ALWAYS AS IDENTITY`; `CONTAS_RECEBER_DETALHE` sem PK nenhuma (virou PK composta
   `(id_tenant, id_conta_receber)`, 1:1 confirmado com o dono do produto); `MOEDAS_DETALHE`
   como PK `(id_moeda, id_carteira)` sem tenant (virou `(id_tenant, id_moeda, id_carteira)`).
3. **`caixa_detalhe.tipo_operacao`** — os códigos do legado (`RV`/`RP`/`DC`/`CC`/`TR`) não
   diziam o significado; confirmado com o dono do produto e virou ENUM `tipo_operacao_caixa`
   por extenso: `RECEBIMENTO_VENDA`, `RECEBIMENTO_PARCELA_CREDIARIO`, `DEBITO_CAIXA`,
   `CREDITO_CAIXA`, `TROCO`. `credito_debito` (`C`/`D`) **reaproveita** o ENUM já criado em
   V013 para o ledger de estoque, em vez de criar um tipo novo.
4. **`caixa_detalhe` ganhou `criado_em`** (ausente no legado) — uma sessão de caixa pode durar
   o dia todo com vários lançamentos em horários diferentes; sem timestamp por linha não dá
   pra saber "quando" cada lançamento ocorreu (P3 exige quem/quando/origem).
5. **Seed de `moeda` é por tenant, não global:** o legado insere 7 linhas fixas (DINHEIRO,
   PIX, CARTAO DEBITO/CREDITO, CREDIARIO, VALE PRESENTE, VALE MERCADORIA) sem tenant — como
   `id_tenant` é obrigatório e não existe no momento da migration, o dono do produto confirmou
   que o seed deve ser **por tenant no signup**, mesmo padrão de `cfg_geral` no
   `SignupService`. ✅ **Implementado em seguida (mesmo dia):** `SignupService.assinar()`
   insere as 7 linhas de `moeda` logo após criar `cfg_geral`, dentro da mesma transação
   atômica do signup (§3.3.2). Nenhum teste foi rodado/banco recriado nesta rodada (convenção
   da sessão — só sob pedido explícito).
6. **Migration `V025__financeiro_caixa_crediario.sql` criada** (nome pedido pelo dono do
   produto) — as 7 tabelas + ENUM `tipo_operacao_caixa`, todas com `id_tenant`, FKs compostas
   `(id_tenant, id_x)` (P8) e RLS **próprio no arquivo** (o guarda-corpo de V024 já tinha
   rodado antes destas tabelas existirem, então não as alcançaria). Documentação sincronizada:
   `db/migration/README.md`, `spec-driven-erp-varejo.md` (§2.7 Q5, §3.3.7, §3.5.1, lista de
   ADRs) e este arquivo. **Banco não recriado/testado nesta rodada** (convenção da sessão).

### 2026-07-16 — Revisão do schema de domínio: tipos, cascade, imutabilidade e bug de bootstrap

Auditoria linha a linha de `db/migration/V001–V024` + `db/bootstrap/00_roles.sql` (nenhum código de
domínio em `api/` ainda depende dessas tabelas, então deu para corrigir sem quebrar nada em produção).

1. **Bug bloqueante corrigido: `niner_owner` sem `CREATE` no schema `public`.**
   `ALTER DATABASE niner_db OWNER TO niner_owner` (bootstrap) muda o dono do **banco**, não do
   schema `public` pré-existente (dono é o superusuário de bootstrap da imagem). Como as
   migrations de domínio (V013+) criam tipos/tabelas sem prefixo de schema (→ `public`), o
   Flyway rodando como `niner_owner` via `docker compose run --rm flyway` falharia em V013 com
   `permission denied for schema public`. Não aparecia nos testes porque
   `api/src/test/resources/bootstrap-test.sql` roda o Flyway como o superusuário do container
   Testcontainers, não como `niner_owner` — o caminho real nunca tinha sido exercitado. Fix:
   `GRANT CREATE ON SCHEMA public TO niner_owner;` no bootstrap.
2. **Regressão revertida em V017 (catálogo).** Uma edição anterior tinha substituído a coluna
   `sku` de `produto_barra` por `ean_fabricante plataforma.sim_nao DEFAULT 'SIML'` — valor
   inválido para o ENUM (`'SIM'`/`'NÃO'`), o que quebraria a migration — e tornado `ean`
   obrigatório, revertendo a decisão **Q7** (sku interno obrigatório/único, ean opcional).
   Restaurado ao design da Q7.
3. **Tipos padronizados em todo o schema:** `id_tenant` sempre `SMALLINT` (raiz do isolamento,
   `plataforma.tenant.id_tenant`); demais PKs surrogate sempre `INTEGER`. Decisão consciente
   (revê a orientação anterior da spec de `BIGINT` genérico) — teto de 32.767 tenants, considerado
   suficiente para o público-alvo; revisitar se o funil comercial aproximar do limite.
4. **Sem `ON DELETE CASCADE` em nenhuma FK do domínio** — decisão do produto: apagar um registro
   com dependentes deve falhar por violação de FK, nunca apagar em cascata silenciosamente.
5. **Ledger de estoque imutável ao nível de banco.** `produto_movimento_mestre`/
   `produto_movimento_detalhe` (P3) ganharam `REVOKE UPDATE, DELETE ... FROM niner_app` em V024
   — mesmo tratamento que `plataforma.impersonacao_log` já tinha (V011). Correção de um
   lançamento é sempre um novo movimento compensatório.
6. **E-mail de login case-insensitive.** `usuario.email` (V015) passou de
   `UNIQUE(id_tenant, email)` para `UNIQUE INDEX (id_tenant, lower(email))`, igual ao padrão já
   usado em `plataforma.staff` (V010) — evita duas contas do mesmo tenant diferindo só por
   maiúscula/minúscula.
7. **`empresa.codigo_empresa`** (número sequencial por tenant, só para exibição em relatório)
   ganhou `UNIQUE(id_tenant, codigo_empresa)` + comentário. **`produto_movimento_mestre.
   id_transferencia`** documentado como proposital sem FK (vem de um gerador externo).
8. **Documentação sincronizada:** `db/migration/README.md` e `spec-driven-erp-varejo.md`
   (§3.1.1, §3.3.1–§3.3.6) atualizados para refletir os tipos/convenções reais e os marcadores
   🔴 já resolvidos pelas migrations V013–V024 (reserva/disponível, ledger, canal/anúncio/pedido,
   outbox/webhook).
9. **`cliente.data_nascimento` + `cliente.genero`.** Campos do legado (§3.3.9: `nascimento`,
   `genero` M/F/O) que tinham ficado de fora quando `V016` foi criado. Como o banco ainda está
   em fase de construção (nenhuma migration foi aplicada em ambiente real até agora), os campos
   entraram **direto na V016** em vez de uma migration nova: `data_nascimento DATE` +
   `genero genero_cliente` (ENUM `MASCULINO`/`FEMININO`/`OUTROS`, tipo definido em V013).
   Primeira versão os deixou `NOT NULL` incondicional; **ajustado no mesmo dia** para
   obrigatório **só em pessoa física** — colunas nullable + `CONSTRAINT
   cliente_dados_pessoais_ck CHECK (NOT fisica_juridica OR (data_nascimento IS NOT NULL AND
   genero IS NOT NULL))`, já que cliente pessoa jurídica não tem data de nascimento nem gênero.
   Convenção do projeto enquanto o banco não roda em ambiente real: só criar migration
   incremental (`V025+`) depois que V001–V024 forem de fato aplicadas num ambiente que importa
   preservar.
10. **`cfg_categoria_cliente` (V016) + `cliente.id_categoria_cliente` (`NOT NULL`).** Tabela do
    legado (§3.3.8) que também tinha ficado de fora — criada com o padrão já usado em
    `cfg_categoria_produto` (V017): `id_categoria_cliente integer GENERATED ALWAYS AS IDENTITY
    PK`, `id_tenant` (P8), `nome_categoria text NOT NULL`, `UNIQUE(id_tenant, nome_categoria)`.
    Entra **antes** de `cliente` no mesmo arquivo (ordem de FK). `cliente` ganhou
    `id_categoria_cliente integer NOT NULL REFERENCES cfg_categoria_cliente` + índice
    `(id_tenant, id_categoria_cliente)`; sem categoria padrão pré-cadastrada, então todo cliente
    precisa de uma categoria já criada. `cfg_categoria_cliente` entrou no array de RLS do V024.
11. **Galeria de imagens do produto.** `produto.imagem` (uma imagem só) removida; criada
    `produto_imagem` (V017) para várias imagens por produto: `id_produto_imagem integer PK`,
    `id_tenant` (P8), `id_produto integer NOT NULL REFERENCES produto` (sem cascade),
    `indice smallint NOT NULL` (ordem de exibição), `imagem text NOT NULL`,
    `UNIQUE(id_tenant, id_produto, indice)`. Entrou no array de RLS do V024.
12. **Seis ajustes pontuais pedidos pelo dono do produto** (V014/V016/V018/V019/V023) — só
    script + documentação, banco **não** recriado/testado nesta rodada (nova convenção: só
    recriar quando pedido explicitamente):
    - `funcionario` (V016): +`telefone`; `funcionario_cpf_uk` virou `UNIQUE(id_tenant,
      id_funcionario)` — como `id_funcionario` já é PK, isso não impõe nada além da PK; **CPF
      deixou de ser único por tenant**.
    - `venda` (V018): removido `id_funcionario` — vendedor/comissão por item ficam só em
      `produto_movimento_detalhe.id_funcionario`.
    - `cfg_geral` (V023): removido `moeda_devolucao`; adicionados `cfg_usa_variante_linha` e
      `cfg_usa_variante_coluna` (`boolean NOT NULL DEFAULT true`).
    - `empresa` (V014): novo campo `cfg_nome_etiqueta text NOT NULL` (texto/modelo da etiqueta
      de produto).
    - `produto_balanco` (V019): removidos `qtd_sistema` e `observacao`; `id_balanco` virou
      `bigint` — **exceção deliberada** à convenção "PKs surrogate são `integer`" (volume de
      contagens de inventário esperado maior que o das demais tabelas).
    - `produto_movimento_detalhe` (V019): removido `saldo_apos` — o ledger continua imutável
      (`REVOKE UPDATE, DELETE`, V024), mas deixa de gravar o saldo resultante por linha; esse
      saldo passa a existir só materializado em `produto_estoque`.
13. **Direção do controle de saldo de estoque em revisão.** O dono do produto avisou que a
    remoção de `saldo_apos` (item 12) é compatível com um plano futuro: recriar
    `SP_ATUALIZA_QUANTIDADE_ESTOQUE` como **stored procedure acionada por trigger** em
    `produto_movimento_mestre`/`produto_movimento_detalhe` — o **oposto** do que está escrito
    hoje em `V019` e na spec (§3.3.1/§3.3.4: "domínio Java, não trigger"). Detalhes ainda **não
    definidos** ("vamos definir mais pra frente"); marcado 🔴 em `V019__estoque.sql` e na spec
    para não ficar como decisão esquecida. Quando fechar, vira ADR e a migration do
    trigger/procedure.
14. **Redes sociais em `cliente`.** `whatsapp`, `instagram`, `facebook`, `tiktok` — `text`
    nullable, sem validação de formato no banco (V016). `instagram`/`facebook` já existiam no
    legado (§3.3.9) e tinham ficado de fora; `whatsapp`/`tiktok` são novos, sem equivalente
    legado. Bloco de sequência de migrations (§3.5.1 da spec) reescrito nesta rodada para bater
    com os números reais V013–V024 (estava com placeholder `V013+`/`V0xx` desde antes da
    implementação).
15. **Item 13 fechado: trigger de estoque implementada.** `trg_produto_movimento_detalhe_estoque`
    (função `fn_atualiza_estoque_movimento()`, PL/pgSQL, fim do `V019__estoque.sql`) mantém
    `produto_estoque.qtd_estoque` em `INSERT`/`UPDATE`/`DELETE` de `produto_movimento_detalhe`:
    `credito_debito='C'` soma, `'D'` subtrai; `UPDATE` desfaz o efeito antigo e aplica o novo
    (cobre troca de empresa/variação/tipo/quantidade); `DELETE` desfaz o efeito. Faz **UPSERT**
    em `produto_estoque` — cria a linha na hora se não existir para o `(id_tenant, id_empresa,
    id_variacao)`. Não existe mais `SP_ATUALIZA_QUANTIDADE_ESTOQUE` como objeto separado — a
    lógica inteira está na função de trigger.
    - **Reverte a regra de imutabilidade do item 12/13:** `produto_movimento_detalhe` saiu do
      `REVOKE UPDATE, DELETE` do `V024` (só `produto_movimento_mestre` continua imutável) —
      sem isso `niner_app` nunca conseguiria disparar as branches de `UPDATE`/`DELETE` da
      trigger (o `REVOKE` bloquearia o comando antes de chegar nela).
    - Escopo confirmado só em `qtd_estoque` — `reservado` continua fora (fluxo de reserva do
      pedido, Q2/ADR-004, não muda).
    - Roda como `niner_app` (SECURITY INVOKER, padrão): RLS de `produto_estoque` continua
      valendo dentro da trigger, sem risco de vazar saldo entre tenants.
    - Único trigger de banco do domínio até agora — todo o resto continua "sem trigger,
      auditoria/saldo no domínio Java" (decisão original, ainda válida para as outras tabelas).
16. **Achado real ao testar a trigger: FK simples não valida o tenant do registro referenciado
    (P8).** Testando isolamento entre tenants (não pedido, verificação por conta própria antes
    de dar a trigger por pronta), consegui — como tenant 2 — inserir em
    `produto_movimento_detalhe` uma linha com `id_tenant=2` mas `id_empresa`/`id_variacao`/
    `id_movimento` **do tenant 1**, e o INSERT passou. A trigger, obediente, criou uma linha em
    `produto_estoque` com `id_tenant=2` apontando pra `id_empresa`/`id_variacao` de outro
    tenant (`qtd_estoque` fabricado). Causa: FK simples (`REFERENCES tabela (id_x)`) só checa
    se o ID existe em algum lugar — **RLS não é aplicado na checagem de integridade
    referencial**. Isso não era bug da trigger; é uma lacuna em **toda** tabela de domínio com
    FK pra outra tabela de domínio (~34 constraints em ~17 tabelas: `usuario`, `cliente`,
    `funcionario`, `produto_categoria`, `produto_barra`, `produto_imagem`, `venda`,
    `venda_devolucao`, `produto_estoque`, `produto_movimento_mestre`,
    `produto_movimento_detalhe`, `produto_balanco`, `anuncio`, `pedido`, `pedido_item`,
    `webhook_recebido` — todo o schema de fato).
    - **Fix (2026-07-16, V014–V022):** toda tabela referenciada ganhou `UNIQUE (id_tenant,
      id_<pk>)` (ex.: `empresa_id_empresa_uk`), e toda FK entre tabelas de domínio virou
      **composta**: `FOREIGN KEY (id_tenant, id_x) REFERENCES tabela (id_tenant, id_x)`. FKs
      nullable (`venda.id_cliente`, `produto_barra.id_variante_linha` etc.) continuam
      funcionando — `MATCH SIMPLE` (padrão do Postgres) não checa a FK se qualquer coluna
      envolvida for `NULL`.
    - Convenção registrada no checklist de aprovação de spec (fim do documento) e em
      `db/migration/README.md`/`spec-driven-erp-varejo.md` §3.1.1: toda FK nova entre tabelas
      de domínio nasce composta, nunca simples.
17. **`cfg_plano_contas` antecipada (V016) — preparação para relatórios/DRE.** O dono do
    produto pediu a tabela de plano de contas (§3.3.7 do legado, que fazia parte do módulo
    `financeiro` deferido pra Fase 2 — Q5/ADR-010) + `fornecedor.id_plano_contas NOT NULL`.
    Só o plano de contas em si entrou — `moeda`, `caixa_*`, `contas_receber*`,
    `contas_pagar`, `conta_corrente*` continuam fora, sem migration (Fase 2). DDL ajustado
    ao padrão do projeto a partir do que foi passado:
    - PK vira **composta `(id_tenant, id_plano_contas)`** em vez de `id_plano_contas` sozinho
      — a chave de negócio (código contábil `text`, ex.: `"3.1.001"`) precisa do tenant pra
      não colidir entre lojistas; é a primeira tabela do domínio sem PK surrogate `integer`.
    - `VARCHAR(13)`/`VARCHAR(100)` → `text`; `VARCHAR(1) CHECK IN ('S','N')`
      (`inclui_dre`/`inclui_fluxo_caixa`) → `boolean` (regra de conversão §3.3.1).
    - `tipo_movimento VARCHAR(1) CHECK IN ('C','D','N')` → novo ENUM `tipo_movimento_conta`
      (V013) — distinto de `credito_debito` (ledger de estoque). Valores por extenso
      (`'CRÉDITO'`/`'DÉBITO'`/`'NEUTRO'`, ajustado logo em seguida no mesmo dia — ver item 18),
      não os códigos de uma letra do legado.
    - `fornecedor.id_plano_contas` entra como FK **composta** desde já (não simples e depois
      corrigida) — já nasce seguindo a convenção do item 16. Sem linha padrão pré-cadastrada
      em `cfg_plano_contas`, então todo fornecedor novo precisa de um plano de contas já
      criado.
    - Constraint/índice nomeados no padrão do projeto (`cfg_plano_contas_pk`,
      `..._id_tenant_ix`, `..._descricao_ix`), não no estilo Firebird (`PK_...`, `IX_...`) do
      DDL original; PK definida inline no `CREATE TABLE`, não via `ALTER TABLE` depois.
    - `cfg_plano_contas` entrou no array de RLS do V024.
18. **Ajuste do ENUM `tipo_movimento_conta` + mais 3 arquivos legados removidos.**
    `tipo_movimento_conta` (item 17) trocou de códigos de uma letra
    (`'C'`/`'D'`/`'N'`) para valores por extenso: `CREATE TYPE tipo_movimento_conta AS ENUM
    ('CRÉDITO', 'DÉBITO', 'NEUTRO')` (V013). Nada dependia do valor anterior (tabela sem
    seed, sem código em `api/`), troca segura. Removidos também `db/005_CFG_PLANO_CONTAS.txt`,
    `db/101_PROCEDURES.txt` e `db/102_TRIGGERS.txt` — dumps do legado Firebird (procedures/
    triggers, incluindo a antiga `SP_ATUALIZA_QUANTIDADE_ESTOQUE`) que não fazem mais falta
    como referência: a lógica de estoque já está na trigger real (`V019`, item 15) e o plano
    de contas já foi migrado (item 17).
19. **`venda`/`venda_devolucao` (V018) perdem campos.** `venda` sem `valor_total`,
    `observacao` e `criado_em`; `venda_devolucao` sem `criado_em`. `venda`/`venda_devolucao`
    passam a ser as **únicas** tabelas do domínio sem `criado_em` (exceção deliberada à
    convenção de auditoria — registrado em `db/migration/README.md`). Motivo do
    `criado_em`, confirmado pelo dono do produto: `data_venda`/`data_devolucao` já cumprem
    esse papel — não há fluxo de "criar rascunho hoje, confirmar depois" nessas duas
    tabelas, então `criado_em` seria sempre igual e redundante. O total da venda passa a
    ser sempre derivado somando `produto_movimento_detalhe` (tipo `VENDA`) — não fica mais
    armazenado/duplicado em `venda.valor_total`.
20. **Mais 2 arquivos legados removidos.** `db/040_VENDAS.txt` e
    `db/041_VENDAS_DEVOLUCAO.txt` (dumps Firebird de `venda`/`venda_devolucao`) — schema já
    migrado em V018 (item 19), sem falta de referência.

### 2026-07-11 — Home institucional (concorrente do Bling) + trial 60 dias (ponta a ponta)

Reforço do topo do funil (§Fase 0, ADR-011) e revisão de D2.

1. **Trial 14 → 60 dias (D2 revisto).** Decisão do dono do produto para dar mais tempo de
   ativação/aha. Aplicado **ponta a ponta**: `application.yml` (`niner.trial.dias: 60`, prod e
   teste), comentário em `OnboardingController`, e todos os textos da spec (R12, §3.3.2, tabela de
   rotas, D2), do `PLANO-DE-NEGOCIO` (§6, intro, D2) e do site. `SignupService` já parametrizava
   via `NinerProperties.Trial.dias` — nenhuma lógica mudou; os 11 testes seguem válidos (nenhum
   depende do valor de dias).
2. **Home reconstruída (`site/index.astro`).** De landing "seca" para página longa e animada,
   posicionando o Niner como ERP multicanal moderno (sem citar o Bling nominalmente): hero com
   painel decorativo + **demo de sincronização estoque→canais**, faixa de prova com **contadores
   animados**, blocos problema→solução, "como funciona" em 3 passos com ilustrações SVG,
   6 recursos, pílulas de canais, planos (mantido o enhancement de preços da API), FAQ em
   `<details>` e CTA final. Toda CTA leva a `/assinar` ("60 dias grátis, sem cartão").
3. **Sistema visual + movimento.** Novo `site/src/styles/site.css` (portado do golden
   `docs/padroes/nainer_institucional`, rebrandeado Niner) e `Base.astro` com navbar sticky,
   toggle de tema persistido, menu mobile e um script de **scroll-reveal** (IntersectionObserver)
   + contadores, tudo desligado sob `prefers-reduced-motion`. Sem novas dependências (P6/ADR-011).
   `/assinar` adotou `form-card`/`field` e ganhou uma coluna de reforços; `/bem-vindo` alinhado.
4. **Verificação:** `npm run build` (SSG, 3 páginas) ok; Playwright confirmou hero animado,
   reveal disparando ao rolar (opacity 0→1) e contadores, em tema claro e escuro; caminho
   reduced-motion mostra tudo estático.

### 2026-07-10 — Esqueleto da API no ar (Fase 0): 3 superfícies + contexto de tenant

Scaffolding do backend (spec §Roadmap Fase 0), **independente** das decisões
bloqueantes Q2/Q5/Q7 — entrega um esqueleto que compila, sobe e prova a arquitetura,
sem as migrations de domínio (V013+).

1. **`api/` criado** via Spring Initializr — **Spring Boot 4.0.7 / Java 25**, Maven,
   deps: webmvc, security, oauth2-resource-server, validation, actuator, postgresql,
   flyway, **data-jdbc**, testcontainers. `groupId com.vetor.niner`.

2. **Decisão de persistência: Spring Data JDBC** (não JPA/Hibernate). Mais explícito e
   previsível para o padrão RLS + `SET LOCAL` por transação. A spec foi atualizada
   (§3.1.1, §3.2, §3.3.1): removidas as menções a Hibernate `@Filter` e JPA auditing;
   timestamps via `DEFAULT now()` + preenchimento no serviço de domínio.

3. **3 superfícies (ADR-007)** — `SegurancaConfig` com `SecurityFilterChain` separados
   por `securityMatcher`: `/api/publico/**` (+ actuator, permitAll), `/api/v1/**`
   (tenant), `/api/admin/**` (staff) e um chain default que **nega** o resto. JWT ainda
   não exigido (sem emissor nesta fase) — marcado `TODO(jwt)`; validação de `aud` entra
   na fase de auth.

4. **Infra de contexto de tenant (P8, §3.1.1)** — `TenantContext` com **`ScopedValue`**
   (Java 25); `TenantFilter` lê o claim `tid` do JWT e liga o contexto na cadeia de
   `/api/v1`; `TenantAwareTransactionManager` roda
   `select set_config('app.id_tenant', :tid, true)` no início da transação, casando com
   as políticas RLS (`plataforma.tenant_atual()`, V002). A app conecta como **`niner_app`**
   (sem BYPASSRLS); **Flyway roda separado** como `niner_owner` (serviço do compose) —
   `spring.flyway.enabled=false` na app.

5. **Pacotes-módulo do monólito** criados com `package-info.java`: `plataforma`,
   `identidade`, `catalogo`, `estoque`, `pedidos`, `precos`, `canais`, `integracao`,
   `comum/{config,tenant,web}`. Domínio entra com as migrations V013+.

6. **Serviço `api` no `docker-compose.yml`** + `Dockerfile` multi-stage (Maven+JDK 25 →
   JRE 25, usuário não-root). Ordem documentada: `db` → `flyway` → `api`.

7. **Verificação de ponta a ponta (tudo verde):**
   - `./mvnw test` → **7 testes** (context loads + 3 superfícies + propagação de tenant
     `tid=42 → id_tenant=42` + rota fora → 403), com **Testcontainers Postgres 18.4** e
     **Flyway aplicando V001–V012** (bootstrap de roles via `bootstrap-test.sql`).
   - App rodando ao vivo contra o `db` do compose (como `niner_app`):
     `/actuator/health` UP; `/api/publico|v1|admin/ping` → 200; rota fora → 403.
   - ⚠️ **Colima + Testcontainers:** exige `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
     (senão o Ryuk falha ao montar o docker.sock). Anotado no README da `api/`.

8. **Gate P8 (parcial neste momento):** fecha com as tabelas de domínio + RLS — feito no
   mesmo dia (ver entrada seguinte).

### 2026-07-10 — App `web/` (ERP do lojista): login + handoff + painel

1. **`web/` criado** (React 19 + Vite + TS; React Router 7 + TanStack Query 5 — §3.2).
2. **Autenticação:** login (`slug + email + senha` → `POST /api/publico/login`) e
   **handoff SSO** do site — o botão "Ir para o sistema" do `/bem-vindo` leva o token via
   `#token=...`; o app consome, guarda e limpa a URL. Guarda de rota + logout em 401.
3. **Shell do ERP:** cabeçalho (marca + Sair) + navegação lateral (Painel, Produtos,
   Estoque, Pedidos, Canais). **Painel** real via `GET /api/v1/eu` (loja, assinatura TRIAL,
   usuário/papel + próximos passos). Áreas de domínio como placeholders "em construção".
4. **Design system §3.7** (tokens CSS, tema claro/escuro) — mesma paleta do site.
5. **Backend:** CORS já cobre a origem do web (`niner.cors.origins`).
6. **Verificação (e2e, Playwright):** login → painel "Olá, Ana / Loja Web Teste / TRIAL";
   navegação para "Produtos"; **handoff** `#token=` → entra logado e limpa o hash. **Build ok.**
   *(Durante a verificação, uma instância antiga da API presa na 8080 foi derrubada e a API
   subiu do jar atual — sem mudança de código.)*
7. Serviço `web` adicionado ao `docker-compose.yml` (perfil `fronts`).

> O loop de aquisição está fechado: **site → assinar → tenant + trial → "Ir para o sistema" → web logado**.
> Falta: `admin/` (backoffice), endpoints de domínio reais em `/api/v1`, e R22 (ajuda) nos fronts.

### 2026-07-10 — Site público (Astro/SSG) + planos + CORS: aquisição self-service no ar

1. **ADR-011 decidido: Astro (SSG)** para o `site/` (SEO/Core Web Vitals). `web`/`admin`
   seguem React+Vite.
2. **`site/` criado** (Astro): landing SEO-forte (`<title>`/description/OG/canonical, h1,
   planos renderizados no HTML estático), `/assinar` (formulário → `POST /api/publico/assinar`
   → guarda token e vai para `/bem-vindo`), `/bem-vindo` (primeiro uso: `GET /api/v1/eu`).
   Design tokens §3.7 (tema claro/escuro); base-URL da API lida em runtime (`public/config.js`).
   **Build SSG ok** (3 páginas estáticas).
3. **Backend de apoio:** `GET /api/publico/planos` (R11, catálogo público) e **CORS**
   (`niner.cors.origins`) para os fronts chamarem a API. Preflight e planos validados com
   `Origin` do site (headers `Access-Control-Allow-*` corretos). **11 testes seguem verdes.**
4. Serviço `site` adicionado ao `docker-compose.yml` (perfil `fronts`).

> Pendente no site: **R22** (ajuda de tela + vídeo), páginas de conteúdo/sitemap, e o botão
> "Ir para o sistema" ligado ao app `web/` (a criar).

### 2026-07-10 — Motor do trial self-service (R12): signup → tenant + JWT + primeiro uso

Implementado o fluxo de **assinatura-teste** (14 dias, sem cartão — D2) na superfície pública,
com autenticação JWT real protegendo o ERP.

1. **Auth JWT (HS256)** — `JwtConfig` (encoder/decoder com segredo simétrico; decoder valida
   assinatura, expiração e **`aud=tenant`**), `TokenService` (emite token com `sub`/`tid`/`aud`/`roles`),
   `BCryptPasswordEncoder`. Config em `niner.jwt.*`. `/api/v1/**` agora **exige** JWT (era permitAll);
   o `TenantFilter` passa a ler o `tid` de um token real.

2. **`POST /api/publico/assinar`** (`SignupService`, atômico) — numa única transação: cria
   `plataforma.tenant` (TRIAL), estabelece `app.id_tenant` (para o RLS deixar inserir o domínio),
   cria `assinatura` TRIAL (plano-base **Profissional**, `trial_expira_em = now()+14d`), `uso_tenant`,
   `empresa`, `cfg_geral` (configurações padrão) e o primeiro **usuário ADMIN** (senha em BCrypt).
   Devolve o **token de primeiro acesso (auto-login)** + slug + validade do trial.

3. **`POST /api/publico/login`** (slug da loja + email + senha) e **`GET /api/v1/eu`** (primeiro uso:
   com o token, o cliente já enxerga a própria conta/assinatura via RLS).

4. **Verificação (tudo verde):**
   - **11 testes** (`OnboardingTrialTest`: signup cria tenant e libera 1º uso; login; 401 sem token).
   - **Ao vivo como `niner_app`** (RLS ativo): signup de "Loja do Ze" criou `empresa=1`, `cfg_geral=1`,
     `usuario_admin=1`, `assinatura=TRIAL`, `uso.qtd_usuarios=1`; `/api/v1/eu` com o token → conta TRIAL;
     sem token → 401; login → 200.

### 2026-07-10 — Decisões Q2/Q7/Q5 fechadas + domínio do lojista (V013–V024) + gate P8 verde

1. **Decisões bloqueantes de arquitetura fechadas** (todas):
   - **Q2 (ADR-004)** — reserva no **`recebido`** + expiração por canal (§3.3.5).
   - **Q7** — **separar** `sku` interno (obrigatório/único) de `ean` (GTIN, nullable/único), EAN
     exigido só na publicação (§3.3.3).
   - **Q5 (ADR-010)** — `financeiro` do lojista **fora do v1** (Fase 2, crediário priorizado; §3.3.7).
   - ADRs renumerados: **ADR-010** = financeiro fora do v1; **ADR-011** = framework do site (SEO, em aberto).

2. **Migrations de domínio V013–V024 criadas e validadas em banco real** (Postgres 18.4),
   convertendo o legado Firebird conforme §3.3.1 (minúsculo `snake_case`, `NUMERIC` para
   dinheiro, `BOOLEAN`, `TIMESTAMPTZ`, `BIGINT IDENTITY`) e com **`id_tenant` em toda tabela**:
   - identidade (`empresa`, `usuario`, `usuario_rotina`), cadastros (`cliente`/`fornecedor`/`funcionario`),
     catálogo (`produto`, `produto_barra` com `sku`+`ean`), estoque (`produto_estoque` com
     `reservado`/`disponivel` gerado, ledger imutável, balanço), vendas, canais (`canal`/`anuncio`),
     pedidos de canal (idempotência `(canal,id_externo)`), integração (`outbox_evento`/`webhook_recebido`),
     `cfg_geral`.
   - Convenção de domínio: surrogate `id_<x>` PK + `id_tenant` FK + chaves naturais únicas **por tenant**.

3. **RLS de domínio (V024)** — arquivo único e final: `ENABLE`+`FORCE ROW LEVEL SECURITY` +
   política `USING/WITH CHECK (id_tenant = plataforma.tenant_atual())` + grants de `niner_app` em
   **todas** as tabelas de tenant, mais um **guarda-corpo** que faz a migration falhar se alguma
   tabela com `id_tenant` ficar sem RLS (P8 auto-verificável).

4. **✅ Gate P8 verde:** teste automatizado (`RlsIsolamentoTest`, Testcontainers) conectando como
   **`niner_app`** (sem BYPASSRLS) prova: T1 não lê produto de T2; `WITH CHECK` bloqueia gravar para
   outro tenant; sem contexto não vê nada. **Suíte: 8 testes verdes.**

### 2026-07-09 — Infra local no ar: Postgres 18 + migrations validadas

1. **`docker-compose.yml` criado** (raiz, spec §3.5) com dois serviços por ora:
   - `db` — `postgres:18` (banco **`niner_db`**), volume `pgdata`, healthcheck, e `db/bootstrap/` montado em `/docker-entrypoint-initdb.d` (roles criadas no primeiro init).
   - `flyway` — `flyway/flyway:11` sob profile `migrate`; roda como **`niner_owner`** e aplica `db/migration/`. Uso: `docker compose up -d db` e `docker compose run --rm flyway`.
   - Nota técnica: a imagem `postgres:18` mudou o volume de dados para `/var/lib/postgresql` (o yaml de exemplo da spec §3.5 ainda mostra o caminho antigo `/var/lib/postgresql/data`).
   - `api`/`web`/`admin`/`site` entram no compose quando forem scaffolded.

2. **Migrations V001–V012 aplicadas com sucesso em banco real** (PostgreSQL 18.4) — antes só havia validação estática. Verificado:
   - Roles `niner_owner`/`niner_app` criadas, ambas **sem `BYPASSRLS`** (P8).
   - 9 tabelas no schema `plataforma` (dono `niner_owner`); seed dos 3 planos (Essencial R$ 99 / Profissional R$ 249 / Escala R$ 599 — 🔴 provisórios, D1).
   - `plataforma.tenant_atual()` lê `app.id_tenant` corretamente.
   - `niner_app` lê `plataforma.plano` (grant V011) e **não consegue** criar objetos no schema (`permission denied`) — como esperado.

### 2026-07-08 — Pivô para SaaS multi-tenant (spec v2.0)

1. **Padrão de telas incorporado** — `docs/padroes/TELA.rar` descompactado; mockup `cadastro_fornecedor_campos_cinza.html` mantido como *golden file* de UI; RAR e pasta avulsa removidos. Design system documentado na nova **§3.7** da spec (tokens de cor, tema claro/escuro, grid de 12 colunas, componentes de campo/botão, acessibilidade).

2. **Decisão de produto: virar SaaS multi-tenant.** A spec passou a **v2.0**. O que mudou:
   - **Constituição:** novos **P8** (isolamento de tenant inviolável via Postgres RLS) e **P9** (separação control-plane × tenant).
   - **PRD:** multi-tenant/site público/trial/cobrança deixaram de ser non-goal e viraram **CORE**; novas personas C–F; requisitos **R10–R21**; métricas de funil SaaS.
   - **Plano técnico:** topologia de **uma API com 3 superfícies** (`/api/publico`, `/api/v1`, `/api/admin`) + 3 apps React (`web`/`admin`/`site`); **§3.1.1** isolamento de tenant; **§3.3.11** módulo `plataforma` (control-plane); **§3.5.1** sequência de migrations V001–V091 para `niner_db`; ADR-006 a 009.
   - **Novo `docs/PLANO-DE-NEGOCIO.md`** com o plano comercial.

3. **Decisões tomadas nesta sessão:**
   - **Topologia:** Opção A (uma API, monólito modular) — mas **API stateless** e **base-URL configurável em runtime** nos fronts, para rodar 2 servidores/2 APIs e trocar o endereço em manutenção/failover.
   - **Isolamento:** banco único + `id_tenant` + **Postgres RLS** (`FORCE`).
   - **Gateway de cobrança:** **adiado** — adapter abstrato, cobrança manual no início (D3).
   - **Trial:** **14 dias, sem cartão** (D2).
   - **Q6 fechada:** manter `id_empresa` **e** adicionar `id_tenant` (`tenant 1:N empresa`, 1:1 no v1).

4. **Migrations do control-plane criadas** (`db/migration/V001–V012` + `db/bootstrap/00_roles.sql`) — o schema `plataforma` que controla o tenant: roles `niner_owner`/`niner_app` (esta **sem BYPASSRLS**), função de contexto `plataforma.tenant_atual()`, tipos ENUM, e tabelas `tenant`, `plano`, `assinatura`, `fatura`, `pagamento`, `webhook_gateway`, `uso_tenant`, `staff`, `impersonacao_log`, grants e seed de planos (🔴 preços provisórios). Numeração **renumerada em ordem contígua**; domínio do lojista + políticas RLS ficam em V013+ (§3.5.1). Validação apenas estática (Docker daemon desligado no momento).

5. **Novo requisito R22 (ajuda por tela):** toda tela (ERP/backoffice/site) deve ter **ajuda/manual de operação contextual + acesso a vídeo explicativo** (§3.7.1: componente `AjudaDaTela`, catálogo `ajuda_tela` servido pela API, `url_video` NULL ⇒ "em breve"). Adicionado ao template de Spec de Feature (§5) e ao gate de aprovação.

### 2026-07-07 — Fundação da documentação

1. **`CLAUDE.md` criado** — documenta que o repo é spec-driven, a Constituição (P1–P7), a arquitetura pretendida (monolito modular, adapters de canal, outbox no Postgres) e o alerta de que o `db/` é legado Firebird (não carregar padrões como `FLOAT` para dinheiro).

2. **Spec atualizado para v1.1** — modelo de dados (§3.3) reescrito a partir das tabelas reais do `db/`, adaptado de Firebird para PostgreSQL:
   - Nova **§3.3.1** — regras de conversão Firebird → PostgreSQL (`FLOAT`→`NUMERIC`, `S/N`→`BOOLEAN`, `GENERATOR`→`IDENTITY`, procedures/triggers→domínio Java, `TIMESTAMP`→`TIMESTAMPTZ`, etc.).
   - Modelo reorganizado por módulo (§3.3.2–§3.3.9): `identidade`, `catalogo`, `estoque`, `pedidos`, `integracao`, `financeiro`, config e cadastros.
   - Domínio de **marketplaces** (ausente no legado) integrado: `canal`, `anuncio`, `pedido`, `pedido_item`, `outbox_evento`, `webhook_recebido`.
   - Pendências marcadas em **vermelho** (🔴) — convenção definida no topo do documento.

3. **Commit `6c65765`** — CLAUDE.md + `db/` + spec v1.1 enviados para `origin/main`.

4. **GitHub CLI instalado e autenticado** — `gh` 2.96.0 em `C:\Program Files\GitHub CLI\gh.exe` (via winget), autenticado como `evirson` (device flow; escopos `gist`, `read:org`, `repo`).

5. **Coautor convidado** — `claudiocalixto` (`claudio@vetorsistemas.com.br`) convidado com acesso **Write** via `gh api PUT .../collaborators/claudiocalixto -f permission=push`. Convite **aguardando aceite**.

6. **Commit `76922ac`** — `docs/PROGRESSO.md` enviado para `origin/main` (após rebase sobre o commit remoto `9e4fa65` "inclusao de base").

---

## Decisões bloqueantes em aberto (ver §2.7 e §3.3 da spec)

**Todas as bloqueantes de arquitetura (Q2/Q5/Q6/Q7) estão fechadas** — o domínio (V013+) está destravado. Restam só decisões de **negócio**, que não travam o schema central:

- **Decisões de negócio do SaaS (D1–D10)** — ver `docs/PLANO-DE-NEGOCIO.md`. Abertas: D1 preços, D3 gateway (adiado), D5 nome "Niner", D6 NFS-e da assinatura, D8 dunning, D9 metas, D10 comportamento do estado `INADIMPLENTE`.
- **ADR-011 — framework do site público (SEO):** Astro × Next, "decidir depois" (não bloqueia o backend).

> ✅ **Q5 — módulo `financeiro` do lojista:** fechada em 2026-07-10 — **fora do v1**; **revisada em 2026-07-16, em duas rodadas (ADR-012)** — crediário (`tipo_carteira`/`moeda`/`contas_receber`) e caixa (`caixa_mestre`/`caixa_detalhe`) via **V025**, depois `contas_pagar` via **V026**. Só `conta_corrente(_movimento)` continua fora. R9 (venda manual) segue sem ligação automática ao financeiro (schema existe, domínio Java ainda não liga venda→recebível). Ver §3.3.7.
> ✅ **Q7 — SKU vs EAN:** fechada em 2026-07-10 — **separar** `sku` interno (obrigatório, único, chave do domínio; ex-`codigo_barra`) de `ean` (GTIN real, nullable, único quando preenchido). EAN exigido só na **publicação** em canal, não no cadastro. Nas migrations V013+, as FKs que apontavam para `codigo_barra` passam a referenciar `sku`. Ver §3.3.3.
> ✅ **Q2 — estratégia de reserva:** fechada em 2026-07-10 — reservar no **`recebido`** (pedido importado já incrementa `reservado`), com expiração configurável por canal que devolve reservas não pagas. Alinha R5 + P1. Vira **ADR-004**; adicionar colunas `reservado`/`minimo` a `produto_estoque` nas migrations de domínio. Ver §3.3.5.
> ✅ **Q6 — multi-empresa/tenant:** fechada em 2026-07-08 — manter `id_empresa` **e** adicionar `id_tenant` (banco único + RLS; `tenant 1:N empresa`, 1:1 no v1).

---

## Colaboração / acesso ao repositório

- **Claudio** — username GitHub **`claudiocalixto`** (`claudio@vetorsistemas.com.br`) — é **coautor** com acesso **Write** ao repo `evirson/niner-v2`.
- **Status:** ✅ **convite enviado (2026-07-07), aguardando aceite.** Ele aceita pelo e-mail do GitHub ou em https://github.com/evirson/niner-v2/invitations.
- Após aceitar, comita com a própria identidade Git (`user.name`/`user.email`) e aparece como autor nos commits.

---

## Próximos passos sugeridos

**Feito até 2026-07-10:** ✅ decisões de arquitetura (Q2/Q5/Q6/Q7 + ADRs) · ✅ esqueleto da API + 3 superfícies + contexto de tenant · ✅ domínio V013–V024 + RLS + **gate P8** · ✅ **trial self-service** (signup atômico + JWT + login + `/eu`) · ✅ **site** (Astro/SSG) · ✅ **web** (ERP: login + handoff SSO + painel). **Loop de aquisição fechado** (site → trial → web logado).

**Feito em 2026-07-20:** ✅ **primeira tela de domínio completa** — Clientes (`cadastros.cliente`): CRUD backend + frontend, categoria embutida, `AjudaDaTela` (R22), grid de 12 colunas (§3.7) finalmente em uso, convenções novas de projeto (maiúsculas sempre, foco automático no 1º campo).

**Feito em 2026-07-21:** ✅ **padrão de tela de cadastro consolidado** (paginação por página + ordenação por coluna + ícones de ação + modo somente-leitura + configuração de campos por tenant + shell de altura travada + campos informativos de auditoria) e ✅ **segunda, terceira e quarta telas de domínio** — Funcionários (`cadastros.funcionario`), Plano de Contas (`cadastros.planocontas`, com `criado_em`/`atualizado_em` adicionados à V016) e Fornecedores (`cadastros.fornecedor`, com criação rápida de plano de contas embutida), todas construídas sobre esse padrão; ✅ **Parâmetros do Sistema** (`configuracao.geral`), primeira tela deliberadamente fora do padrão de cadastro (singleton por tenant, ADMIN-only).

**Retomar — ordem sugerida:**

1. **⭐ Completar o vertical slice de Produtos.** O CRUD de `produto` foi entregue em
   2026-07-22 (`catalogo.produto`, ver linha do tempo); `docs/telas/produto.md` deixou
   **variação e imagens explicitamente fora de escopo**. Falta:
   - **Galeria de fotos (`produto_imagem`)** — object storage já **decidido e provisionado**
     (ADR-013: Firebase/GCS, buckets criados e testados), mas **nenhuma linha de Java escrita**.
     Ler `docs/infra/armazenamento-imagens.md` **antes de começar** — é o handoff, com TASK-A a
     TASK-D e critérios de aceitação. Atenção à seção de credenciais: **a chave não vem pelo
     git**, o próximo dev precisa de acesso concedido (o caminho recomendado dispensa arquivo
     de chave).
   - **Variação/SKU (`produto_barra`)** — schema pronto desde a V017, sem domínio nem tela.
     **⚠️ Evirson:** `sku` **não** é campo digitado — é sempre gerado por
     `gerar_ean13_interno()` (função SQL já pronta em V017, 2026-07-22, testada em
     `EanGeradorTest`); `ProdutoBarraService.criar()` deve chamá-la explicitamente antes do
     `INSERT`, sem criar gerador novo. Ver linha do tempo de 2026-07-22 e `CLAUDE.md` §Convenções.
   - `uso_tenant.qtd_produtos` (enforcement R19).
2. **Estoque:** `produto_estoque` (saldo/reserva) + movimentações (`POST /api/v1/estoque/movimentacoes`) → tela de estoque.
3. **`admin/`** — backoffice da plataforma (lista/ficha de tenants R17, suspender/impersonar R18/R21).
5. **Catálogo `ajuda_tela` na API** (R22) — hoje `AjudaDaTela` (`web/`) embute o conteúdo como fallback estático; falta o endpoint/tabela real (§3.3.10/§3.7.1 da spec).
6. Decisões de negócio em aberto: D1 (preços), D3 (gateway), D5/D6/D8/D9/D10.

**Como subir o ambiente:** `docker compose up -d db && docker compose run --rm flyway` · API: `cd api && ./mvnw spring-boot:run` (ou `java -jar target/*.jar`) · fronts: `cd site && npm run dev` / `cd web && npm run dev`. Testes da API: `cd api && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw test` (Colima). ⚠️ Se a API der 401 no `/api/publico/**`, há instância velha presa na 8080 → `lsof -ti tcp:8080 | xargs kill -9`.
