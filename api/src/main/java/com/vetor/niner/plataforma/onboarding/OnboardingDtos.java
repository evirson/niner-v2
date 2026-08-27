package com.vetor.niner.plataforma.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** DTOs do onboarding público (signup do trial + login). */
public final class OnboardingDtos {

    private OnboardingDtos() {
    }

    /** Pedido de criação de conta (self-service, R12) — nasce no plano Gratuito (ADR-015). */
    public record AssinarRequest(
            @NotBlank @Size(max = 120) String nomeLoja,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 8, max = 100) String senha,
            @NotBlank @Size(max = 120) String nomeAdmin,
            /** UUID do cookie first-party do site (ADR-017). Opcional: sem ele o signup funciona
             *  igual, só não dá para atribuir a conta à campanha que a trouxe. */
            String visitanteId,
            /**
             * Ramo de atividade da empresa (V072, 2026-08-27) — perguntado direto no formulário,
             * porque o signup não pede CNPJ e portanto não há CNAE de onde deduzir.
             *
             * <p>Opcional de propósito: ramo serve à decisão de tenant e à segmentação, não ao
             * funcionamento do ERP. Um id inválido é tratado como não informado — nada aqui pode
             * impedir alguém de criar a conta.
             */
            Integer idRamo,
            /**
             * Contratação de um <b>grupo separado</b> para quem já tem conta com este e-mail
             * (2026-08-27). Sem esta bandeira, e-mail repetido é recusado — a trava de
             * 2026-08-19 existe porque repetir o cadastro por engano dividia os dados do mesmo
             * lojista entre duas contas sem ele perceber.
             *
             * <p>⚠️ Marcá-la é uma escolha <b>consciente</b> feita na tela de contratação, depois
             * de ler o que se perde: um grupo separado nunca terá relatório somando as empresas
             * do outro grupo, e são duas assinaturas. Não é um "force" técnico.
             *
             * <p>⚠️ E ela <b>não</b> exige a senha da conta que já existe — é uma conta nova, que
             * só por acaso usa o mesmo e-mail, e nada da conta antiga é tocado. Quem quer entrar
             * no grupo <b>existente</b> é que precisa autenticar.
             */
            Boolean criarGrupoSeparado) {
    }

    /**
     * Resposta do signup: já devolve o token de primeiro acesso (auto-login). {@code
     * limiteVendasMes} substituiu {@code trialExpiraEm} em 2026-08-18 (ADR-015) — não existe mais
     * data de expiração; o que a conta gratuita tem é cota de vendas por mês.
     */
    public record AssinarResponse(
            String token,
            long idTenant,
            String slug,
            String nomeLoja,
            String plano,
            Integer limiteVendasMes) {
    }

    /**
     * Login de usuário do tenant: <b>e-mail + senha</b>, sem identificador de conta.
     *
     * <p><b>O {@code slug} saiu em 2026-08-27</b> (decisão do dono do produto). Ele era derivado
     * pelo próprio sistema no signup ({@code slugUnico}) — o usuário nunca o escolheu, só o via
     * num canto do Painel —, e ainda assim era exigido para entrar. Quem descobre a conta agora é
     * {@code plataforma.diretorio_login} (V071).
     *
     * <p>Os dois campos opcionais são <b>voltas seguintes</b> do mesmo formulário, cada uma
     * respondendo a uma pergunta que a anterior deixou em aberto:
     * <ul>
     *   <li>{@code idTenant} — o mesmo e-mail existe em mais de uma conta e a senha casou em
     *       várias (resposta a {@code escolherConta=true});</li>
     *   <li>{@code idEmpresa} — o usuário tem acesso a mais de uma empresa da conta
     *       (resposta a {@code escolherEmpresa=true}, 2026-07-28).</li>
     * </ul>
     * As duas podem acontecer na mesma sessão, nesta ordem: conta primeiro, empresa depois.
     */
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String senha,
            Long idTenant,
            Long idEmpresa) {
    }

    /** Uma empresa que o usuário pode escolher ao logar (`usuario_empresa`, 2026-07-28). */
    public record EmpresaOpcaoLogin(long idEmpresa, String nomeEmpresa) {
    }

    /**
     * Uma conta (tenant) que o usuário pode escolher ao logar (2026-08-27).
     *
     * <p>⚠️ Esta lista <b>só é montada depois que a senha bate</b> — ver
     * {@code SignupService.login}. Devolvê-la antes transformaria o login numa consulta pública
     * de "este e-mail é cliente de vocês, e de quantas contas?", que é exatamente o que a
     * recuperação de senha evita respondendo 204 sempre.
     */
    public record ContaOpcaoLogin(long idTenant, String nomeConta) {
    }

    /**
     * {@code token} vem preenchido quando o login se completa. Quando falta uma escolha, ele vem
     * {@code null} e exatamente um dos dois sinalizadores vem ligado:
     * <ul>
     *   <li>{@code escolherConta} — a senha casou em mais de uma conta; o front reapresenta as
     *       credenciais com o {@code idTenant} escolhido em {@code contas};</li>
     *   <li>{@code escolherEmpresa} — a conta está definida, mas o usuário alcança mais de uma
     *       empresa; o front reapresenta com o {@code idEmpresa} escolhido em {@code empresas}.</li>
     * </ul>
     */
    public record TokenResponse(
            String token,
            long idTenant,
            String slug,
            boolean escolherConta,
            List<ContaOpcaoLogin> contas,
            boolean escolherEmpresa,
            List<EmpresaOpcaoLogin> empresas) {
    }
}
