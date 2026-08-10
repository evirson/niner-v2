package com.vetor.niner.configuracao.importacao;

import java.util.ArrayList;
import java.util.List;

/** DTOs da Rotina de Importação de Dados (docs/telas/importacao-dados.md). */
public final class ImportacaoDtos {

    private ImportacaoDtos() {
    }

    public record TabelaImportavel(String chave, String titulo, String descricao) {
    }

    /** Resultado da detecção automática de tipo de arquivo (2026-08-09) — {@code tabela} vem
     *  {@code null} quando nenhuma tabela cadastrada bate com confiança suficiente com as
     *  colunas do arquivo (arquivo fora do padrão, ou de uma tabela não suportada); nesse caso
     *  a tela pede pro usuário escolher manualmente. {@code colunasArquivo} é devolvido mesmo
     *  sem detecção, para a tela poder mostrar o que foi lido do cabeçalho. */
    public record DeteccaoArquivo(String tabela, List<String> colunasArquivo) {
    }

    /** Progresso "ao vivo" de uma importação em andamento (2026-08-11), consultado por polling
     *  enquanto {@code /processar} está em voo — ver {@link ImportacaoProgressoRegistry}.
     *  {@code etapa} é {@code "leitura"}, {@code "validando"} ou {@code "importando"}. */
    public record ProgressoResponse(String etapa, int atual, int total) {
    }

    /** {@code campo}/{@code valor} vêm preenchidos quando o erro é de conversão de um campo
     *  específico (data/decimal/inteiro inválidos — {@link CampoInvalidoException}); ficam
     *  {@code null} pra erros de regra de negócio que não apontam pra um valor cru específico
     *  (ex.: "Gênero é obrigatório para pessoa física.") — a tela mostra "—" nesse caso. */
    public record LinhaErro(int linha, String motivo, String campo, String valor) {

        static LinhaErro de(int linha, RuntimeException e) {
            if (e instanceof CampoInvalidoException cie) {
                return new LinhaErro(linha, cie.getMessage(), cie.campo(), cie.valor());
            }
            String msg = e.getMessage();
            String motivo = (msg == null || msg.isBlank())
                    ? "Erro ao processar a linha (" + e.getClass().getSimpleName() + ")." : msg;
            return new LinhaErro(linha, motivo, null, null);
        }
    }

    /**
     * Resultado de uma execução — prévia (dry-run, {@code confirmado=false}) ou gravação de
     * verdade ({@code confirmado=true}). {@code pendencias} preenchido significa que o
     * importador precisa de mais alguma escolha do usuário antes de conseguir processar (ex.:
     * mapear coluna de estoque para empresa) — nesse caso nenhuma linha foi processada ainda;
     * os demais contadores vêm zerados e o front deve resolver as pendências e reenviar o
     * mesmo arquivo com as escolhas completas.
     */
    public record RelatorioImportacao(
            boolean confirmado,
            int totalLinhas,
            int linhasImportadas,
            int linhasIgnoradas,
            int linhasRejeitadas,
            List<LinhaErro> erros,
            List<String> avisos,
            List<String> pendencias) {

        public static RelatorioImportacao pendente(List<String> pendencias) {
            return new RelatorioImportacao(false, 0, 0, 0, 0, List.of(), List.of(), pendencias);
        }

        /**
         * Monta o relatório final de uma execução — "tudo ou nada" (2026-08-06, pedido do dono
         * do produto: revoga a ideia anterior de importar as linhas boas e só rejeitar as
         * ruins). Só marca {@code confirmado=true} (e o chamador só commita) quando
         * {@code confirmar=true} **e** não sobrou nenhuma linha com erro — um arquivo com
         * qualquer linha inválida não importa nada, mesmo as linhas que estariam certas.
         * {@code linhasImportadas}/{@code linhasIgnoradas} nunca mentem: são sempre a contagem
         * de "o que aconteceria" — o rótulo de tela é que muda conforme {@code confirmado}
         * (prévia = "prontas para importar"; confirmado de verdade = "importadas").
         */
        public static RelatorioImportacao concluir(boolean confirmar, int totalLinhas, int linhasImportadas,
                                                     int linhasIgnoradas, List<LinhaErro> erros, List<String> avisos) {
            boolean confirmado = confirmar && erros.isEmpty();
            List<String> avisosFinais = avisos;
            if (confirmar && !erros.isEmpty()) {
                avisosFinais = new ArrayList<>(avisos);
                avisosFinais.add("Nada foi importado: corrija a" + (erros.size() > 1 ? "s " + erros.size() + " linhas" : " linha")
                        + " com erro listada" + (erros.size() > 1 ? "s" : "") + " abaixo e envie o arquivo novamente.");
            }
            return new RelatorioImportacao(confirmado, totalLinhas, linhasImportadas, linhasIgnoradas, erros.size(),
                    erros, avisosFinais, List.of());
        }
    }
}
