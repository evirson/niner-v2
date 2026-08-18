# Spec: Dados da Empresa                           Status: Aprovada
Autor: Claudio Calixto (dono do produto) · Data: 2026-08-19 · Módulo(s): `identidade` (empresa) · Fase: 1 — Núcleo do ERP

## Problema

`empresa` sempre teve as colunas de identificação/endereço e, desde `V014__identidade_empresa.sql`
(bloco fiscal adicionado em 2026-08-16), também `cnpj`/`inscricao_estadual`/`inscricao_municipal`/
`codigo_municipio_ibge`/`cnae` — mas a tabela era **só leitura**: `EmpresaController` tinha
apenas `GET /api/v1/empresas` e `.../permitidas`, zero `POST`/`PUT`, e nenhuma tela em `web/src`
editava esses campos.

Achado reportado ao vivo pelo dono do produto testando a Conformidade Fiscal: *"na conformidade
fiscal, na seção empresas, tem 6 pendências... mas quando mando corrigir não me aparece pra
cadastrar o CNPJ da empresa, onde a gente coloca isso??"*. Das 6 pendências de "Empresa", 4
(CNPJ/Inscrição Estadual/código de município IBGE/CNAE) nem mostravam o botão "Corrigir"
(`telaCorrecao = null`); a 5ª ("Nenhuma configuração fiscal") levava para `/fiscal/configuracao`,
que trata de CRT/emissão/séries/CSC — campos diferentes. Só "Nenhum certificado digital"
funcionava de verdade.

É a **terceira vez** no projeto que a mesma causa raiz aparece: campo e endpoint prontos, tela
nunca construída. Tipo de Carteira e Produto tiveram o mesmo problema em 2026-08-18 (ver
`docs/telas/fiscal-conformidade.md`).

## Solução proposta

Nova tela em `identidade/empresa` — **o primeiro CRUD do projeto sem criar/excluir**: a empresa
já existe (criada no signup ou incluída via SQL direto pela equipe, caminho que já era usado
antes desta tela para cadastrar filiais); esta tela só edita o que já existe. Sem paginação,
busca ou ordenação — `EmpresaService.listar()` já documenta "no máximo poucas dezenas" (mesmo
critério de sempre para as empresas de um tenant).

**Acesso: somente ADMIN**, leitura e escrita (mesmo padrão de `configuracao.geral` — dado
sensível o bastante para não seguir a regra geral de "OPERADOR também tem acesso total" do resto
de `cadastros`). `listar()`/`listarPermitidas()` (usados por outras telas, ex. Entrada de
Produtos por Compra) continuam abertos a qualquer papel, sem mudança.

**Nada é obrigatório para salvar.** Quem cobra o preenchimento antes de emitir nota é a
Conformidade Fiscal, não este formulário — mesmo princípio de `TipoCarteiraForm`/`FornecedorForm`.
Salvar com campos em branco é aceito; a tela existe para permitir preencher aos poucos.

**Razão Social, Código e Matriz/Filial ficam de fora do formulário** — são estruturais,
imutáveis por esta tela (aparecem só como texto, sem `<input>`).

## Campos do formulário

Tabela `empresa` (V014). Quatro seções, nesta ordem:

| Seção | Campo (banco) | Rótulo na tela | Componente | Regra |
|---|---|---|---|---|
| Identificação | `razao_social` | Razão Social | texto, somente leitura | — |
| Identificação | `codigo_empresa` | Código | texto, somente leitura | — |
| Identificação | `matriz` | Tipo | texto, somente leitura | "Matriz" / "Filial" |
| Identificação | `nome_fantasia` | Nome Fantasia | texto | Maiúsculas; foco automático ao abrir |
| Dados Fiscais | `cnpj` | CNPJ | texto mascarado | Alfanumérico (`somenteAlfanumerico`/`mascararCpfCnpj`, IN RFB 2.229/2024 — ver `[[project_cnpj_alfanumerico]]`); validado via `FornecedorService.cnpjValido` |
| Dados Fiscais | `inscricao_estadual` | Inscrição Estadual | texto | Maiúsculas |
| Dados Fiscais | `inscricao_municipal` | Inscrição Municipal | texto | Maiúsculas |
| Dados Fiscais | `codigo_municipio_ibge` | Código de Município (IBGE) | texto, só dígitos, 7 posições | Sem lookup/autocomplete — texto puro, link para ibge.gov.br no rodapé do campo |
| Dados Fiscais | `cnae` | CNAE | texto | — |
| Endereço | `cep` | CEP | texto mascarado | Autopreenche endereço/bairro/cidade/UF via ViaCEP (`buscarEnderecoPorCep`, mesmo padrão de Cliente/Fornecedor) |
| Endereço | `endereco`, `numero`, `complemento`, `bairro`, `cidade`, `estado` | Endereço, Número, Complemento, Bairro, Cidade, UF | texto | Maiúsculas |
| Contato | `telefone` | Telefone | texto mascarado | — |
| Contato | `email` | E-mail | texto (`type="email"`) | Validado via `FornecedorService.emailValido` quando informado |

`id_empresa`, `criado_em` e `atualizado_em` aparecem em `InfoRegistro` no fim do formulário
(convenção do projeto).

## Critérios de aceitação (viram testes)

- Dado um ADMIN, quando atualiza os dados de uma empresa do próprio tenant com CNPJ válido,
  então recebe 200 com os dados salvos e o `GET` seguinte reflete a mudança.
- Dado um ADMIN, quando salva com todos os campos em branco, então recebe 200 (nada é
  obrigatório aqui).
- Dado um CNPJ com dígito verificador inválido, quando salvo, então 400.
- Dado um e-mail em formato inválido, quando salvo, então 400.
- Dado que a empresa já tem um CNPJ, quando salva de novo com o **mesmo** CNPJ, então não
  dispara conflito de unicidade (só CNPJ de **outra** empresa do tenant deveria).
- Dado um OPERADOR, quando tenta ler ou gravar uma empresa, então 403 nos dois casos.
- Dado um `id_empresa` de outro tenant, quando um ADMIN busca, então 404 (isolamento — P8).
- Dado um ADMIN, quando atualiza uma empresa, então Razão Social/Código/Matriz continuam
  inalterados (não fazem parte do request).

Cobertos por `EmpresaCrudTest` (8 testes). `ConformidadeFiscalCrudTest` ganhou 1 teste
confirmando que as 4 pendências de cadastro de empresa apontam `telaCorrecao == "identidade.empresa"`.

## Impacto no contrato de API

```
GET  /api/v1/empresas                lista básica do tenant (qualquer papel, sem mudança)
GET  /api/v1/empresas/permitidas     empresas operáveis pelo usuário logado (qualquer papel, sem mudança)
GET  /api/v1/empresas/{id}           ficha completa — identificação/endereço/fiscal (ADMIN)
PUT  /api/v1/empresas/{id}           atualiza identificação/endereço/fiscal (ADMIN) — todos os campos opcionais
```

Sob `/api/v1/**` (JWT de tenant, RLS ativo — P8); 403 (Problem Details) para papel diferente de
ADMIN nos dois endpoints novos; 404 para `id_empresa` de outro tenant; 409 para CNPJ já usado por
outra empresa do mesmo tenant (`ConflitoDadosException`).

## Impacto na Conformidade Fiscal

`ConformidadeFiscalService.pendenciasEmpresa`: as 4 pendências "Empresa sem CNPJ" / "... sem
Inscrição Estadual" / "... sem código de município IBGE" / "... sem CNAE", antes com
`telaCorrecao = null` (botão "Corrigir" não aparecia), agora apontam `"identidade.empresa"`.
`ROTA_POR_TELA` (`web/src/lib/conformidadeFiscal.ts`) ganhou a entrada `'identidade.empresa':
'/empresas'` — `rotaDeCorrecao()` já sabia montar `/empresas/{idRegistro}` sozinho, sem mudança
no mecanismo existente.

## Verificado ao vivo no navegador

Rebuild da API, login, `/empresas` mostrando as 5 empresas do tenant dev. Editada a Loja Dev
Claudio: CNPJ `11.222.333/0001-81` (máscara aplicada ao digitar), IE, IM, código IBGE `4106902`,
CNAE `4781400`, CEP `80010-000` → autopreencheu Rua José Loureiro/Centro/Curitiba/PR sozinho.
Salvou, voltou para a Conformidade Fiscal — pendências de "Empresa" caíram de **6 para 2** (só
restaram "sem configuração fiscal" e "sem certificado", que são de outra tela).

## Non-goals desta feature

- **Criar/excluir empresa pela UI.** Continua não existindo — uma empresa nova segue sendo
  INSERT direto via SQL, mesmo caminho já usado para criar filiais antes desta tela.
- **Lookup/autocomplete para o código de município IBGE.** Campo de texto puro (7 dígitos); não
  existe tabela de referência de municípios no projeto (diferente do NCM, que tem
  `cfg_produto_ncm` com ~10.515 códigos reais).
- **`imagem_relatorio`/`cfg_nome_etiqueta`** (colunas existentes de `empresa`) — fora do
  formulário; não fazem parte do que a Conformidade Fiscal cobra e não foram pedidas.

## Ajuda da tela (manual de operação + vídeo) — obrigatório (R22 / §3.7.1)

- **`chave_tela`: `identidade.empresa.lista`** — grade simples das empresas do tenant, sem
  criar/excluir; erro comum: só ADMIN acessa. `url_video`: NULL.
- **`chave_tela`: `identidade.empresa.form`** — identificação (parcial, Razão Social/Código/
  Matriz somente leitura), dados fiscais (CNPJ alfanumérico, IE, IM, código IBGE, CNAE),
  endereço (com autopreenchimento por CEP) e contato; nada é obrigatório para salvar; erro comum:
  CNPJ já usado por outra empresa do tenant é recusado. `url_video`: NULL.

## Impacto no banco

Nenhum — reaproveita `empresa` (V014), sem coluna nova.

## Impacto nas integrações

Nenhum.

## Questões abertas

Nenhuma bloqueante.

## Métrica de sucesso

Preencher os dados fiscais de uma empresa e ver a Conformidade Fiscal refletir a mudança em
menos de 1 minuto.
