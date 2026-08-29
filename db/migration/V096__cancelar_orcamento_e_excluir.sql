-- V096 — cancelar orçamento passa a exigir EXCLUIR (auditoria de 2026-08-29, rodada 2).
--
-- ⛔ O QUE ESTAVA ERRADO
--
-- `POST /api/v1/orcamentos/{id}/cancelar` era o ÚNICO "desfazer" do sistema sem
-- `@Acao(EXCLUIR)`. Sem a anotação, o `PermissaoInterceptor` cai na regra por verbo — POST vira
-- INCLUIR —, então **quem podia emitir orçamento podia cancelar o de qualquer colega**. E não
-- havia como o administrador impedir: `cfg_tela` trazia `orcamentos` com `tem_excluir = false`,
-- ou seja a caixa "excluir" nem aparecia na grade de permissões.
--
-- Os outros oito caminhos de desfazer já seguiam a regra "desfazer é excluir" (V077): cancelar
-- venda, cancelar devolução, cancelar entrada, cancelar devolução ao fornecedor, cancelar OS,
-- reabrir caixa, estornar crediário e desfazer balanço.
--
-- ⚠️ Cancelar um orçamento não é operação pequena: ele é IMUTÁVEL (R1) e carrega **preço
-- congelado** que a loja se comprometeu a honrar. Cancelar é a única forma de destruí-lo.
--
-- ⚠️ POR QUE O TESTE-GUARDA NÃO PEGOU: `AcoesPorTelaConferemTest` compara o catálogo com o que o
-- código exige, e aqui os dois estavam **coerentes entre si** — ambos errados na mesma direção
-- (o código pedia INCLUIR, o catálogo oferecia INCLUIR). Guarda de coerência não substitui
-- decisão de produto; ele só impede que as duas listas divirjam.

UPDATE cfg_tela SET tem_excluir = true WHERE chave = 'orcamentos';

-- ⚠️ MIGRA as concessões, como fez a V091: quem podia cancelar continua podendo. Exigir a ação
-- nova sem migrar tiraria, EM SILÊNCIO, um acesso que o administrador havia dado — o vendedor
-- abriria a tela no dia seguinte e o botão responderia 403 citando uma permissão que ninguém
-- sabia que passou a existir. Retirar de quem não deveria ter é decisão do administrador, na
-- grade, com a caixa agora visível.
UPDATE usuario_permissao
   SET excluir = true
 WHERE chave_tela = 'orcamentos' AND incluir = true;
