package com.vetor.niner.comum.web;

import com.vetor.niner.fiscal.documento.MontagemNfceDtos.MontagemInvalidaException;
import com.vetor.niner.plataforma.onboarding.ContaJaExisteException;
import com.vetor.niner.plataforma.uso.LimiteVendasExcedidoException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/**
 * Tradução de exceções para Problem Details (RFC 9457), convenção de erro da API
 * (spec §3.4). O tratamento das exceções padrão de MVC já vem do Spring
 * ({@code spring.mvc.problemdetails.enabled=true}); aqui ficam os casos de domínio.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail tratarEstadoInvalido(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Estado inválido");
        pd.setType(URI.create("urn:niner:erro:estado-invalido"));
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail tratarArgumentoInvalido(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Dado inválido");
        pd.setType(URI.create("urn:niner:erro:validacao"));
        return pd;
    }

    /**
     * {@code ResponseStatusException(status, motivo)} — usado em vários serviços (login,
     * validações pontuais) — não ganha corpo Problem Details de graça: sem este handler ela
     * chega ao cliente como status certo mas corpo vazio, e o front (que lê {@code p.detail})
     * cai no genérico "Ocorreu um erro.", escondendo o motivo real que o backend já tinha
     * (bug real encontrado ao testar a rejeição de login por horário de acesso, 2026-08-11 —
     * afetava também "Credenciais inválidas." e todo outro uso já existente desta exceção).
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail tratarStatusException(ResponseStatusException ex) {
        return ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
    }

    @ExceptionHandler(ConflitoDadosException.class)
    public ProblemDetail tratarConflito(ConflitoDadosException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Conflito de dados");
        pd.setType(URI.create("urn:niner:erro:conflito"));
        return pd;
    }

    /**
     * Cota de vendas do mês esgotada (ADR-015). Vai além da mensagem: manda os números e a faixa
     * recomendada como propriedades do Problem Details, para a tela oferecer o upgrade na hora —
     * é o momento de conversão do produto, não só um erro.
     */
    /**
     * E-mail já tem conta no Nainer (2026-08-27). Tipo próprio para a tela de contratação
     * reconhecer a situação e oferecer a escolha — acrescentar a empresa ao grupo existente ou
     * abrir um grupo separado — em vez de mostrar um erro sem saída.
     *
     * <p>⚠️ Nenhuma propriedade extra aqui, de propósito: neste ponto ninguém provou ser dono do
     * e-mail, então a resposta não pode dizer o nome nem a quantidade de contas.
     */
    @ExceptionHandler(ContaJaExisteException.class)
    public ProblemDetail tratarContaJaExiste(ContaJaExisteException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Este e-mail já tem conta");
        pd.setType(URI.create("urn:niner:erro:conta-ja-existe"));
        return pd;
    }

    @ExceptionHandler(LimiteVendasExcedidoException.class)
    public ProblemDetail tratarLimiteDeVendas(LimiteVendasExcedidoException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Limite de vendas do plano atingido");
        pd.setType(URI.create("urn:niner:erro:limite-de-vendas"));
        pd.setProperty("usadas", ex.usadas());
        pd.setProperty("limite", ex.limite());
        pd.setProperty("tolerancia", ex.tolerancia());
        if (ex.faixaRecomendada() != null) {
            pd.setProperty("faixaRecomendada", ex.faixaRecomendada());
            pd.setProperty("precoMensalRecomendado", ex.precoMensalRecomendado());
        }
        return pd;
    }

    /**
     * Rede de segurança: se algum serviço deixar de checar manualmente uma FK antes de excluir
     * (a checagem de {@code ConflitoDadosException} é sempre uma pré-verificação enumerada à
     * mão — ver {@code PlanoContasService}), o próprio banco rejeita e o JDBC lança esta
     * exceção. Sem este handler ela caía no 500 genérico do Spring, sem {@code detail}/{@code
     * title} — o front então mostrava só "Ocorreu um erro.", indistinguível de uma falha real.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail tratarViolacaoDeIntegridade(DataIntegrityViolationException ex) {
        String estoque = mensagemDeEstoqueInsuficiente(ex);
        if (estoque != null) {
            // Não é violação de FK — é a regra de estoque negativo (V054), que a trigger
            // `fn_atualiza_estoque_movimento` levanta com ERRCODE 23514. Sem este desvio o
            // operador leria "registro em uso por outro cadastro" ao tentar vender, o que não
            // faz o menor sentido para ele.
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, estoque);
            pd.setTitle("Estoque insuficiente");
            pd.setType(URI.create("urn:niner:erro:estoque-insuficiente"));
            return pd;
        }
        LOG.warn("Violacao de integridade tratada como 409", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Registro em uso por outro cadastro — não pode ser excluído.");
        pd.setTitle("Conflito de dados");
        pd.setType(URI.create("urn:niner:erro:conflito"));
        return pd;
    }

    /**
     * Prefixo que a trigger de estoque (V054) põe na mensagem para se distinguir de uma violação
     * de FK de verdade. O texto depois do {@code |} já vem escrito para o operador — qual produto,
     * qual empresa, quanto há e quanto a operação precisa — e é devolvido como está.
     *
     * <p>Por que casar por texto e não por SQLSTATE: a regra usa {@code 23514} justamente para o
     * driver classificar como violação de integridade e cair neste handler; um SQLSTATE próprio
     * (classe {@code P0}) viraria {@code UncategorizedSQLException} e um 500 sem mensagem.
     */
    private static String mensagemDeEstoqueInsuficiente(Throwable ex) {
        for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
            String mensagem = causa.getMessage();
            if (mensagem != null) {
                int marcador = mensagem.indexOf(MARCADOR_ESTOQUE);
                if (marcador >= 0) {
                    String texto = mensagem.substring(marcador + MARCADOR_ESTOQUE.length());
                    // O Postgres acrescenta contexto depois da mensagem ("Where: PL/pgSQL…").
                    int fim = texto.indexOf('\n');
                    return (fim >= 0 ? texto.substring(0, fim) : texto).trim();
                }
            }
            if (causa.getCause() == causa) {
                break;
            }
        }
        return null;
    }

    private static final String MARCADOR_ESTOQUE = "ESTOQUE_NEGATIVO|";

    /**
     * Falha de montagem de XML fiscal (emissão, cancelamento, inutilização) — sem este handler
     * caía no 500 genérico do Spring (achado real: cancelar uma venda com NFC-e autorizada e
     * justificativa curta demais lançava {@code MontagemInvalidaException} de dentro de
     * {@code CancelamentoNfceService}, e o front só mostrava "Ocorreu um erro", escondendo a
     * regra real — SEFAZ exige justificativa com no mínimo 15 caracteres, MOC).
     */
    @ExceptionHandler(MontagemInvalidaException.class)
    public ProblemDetail tratarMontagemInvalida(MontagemInvalidaException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Documento fiscal inválido");
        pd.setType(URI.create("urn:niner:erro:fiscal-montagem-invalida"));
        return pd;
    }
}
