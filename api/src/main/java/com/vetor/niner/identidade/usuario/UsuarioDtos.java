package com.vetor.niner.identidade.usuario;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de usuário (docs/telas/usuario.md). */
public final class UsuarioDtos {

    private UsuarioDtos() {
    }

    /**
     * Corpo de criação/atualização. {@code senha} é obrigatória ao criar e opcional ao
     * atualizar (em branco = mantém a senha atual) — checado em {@code UsuarioService.validar},
     * não dá pra expressar isso só com anotação porque a regra muda conforme a operação.
     *
     * <p>Sem campo {@code administrador} de propósito (2026-07-28) — só existe um ADMIN por
     * tenant, criado no signup, e esta tela nunca cria nem edita esse papel (ver
     * {@code UsuarioService}).
     *
     * <p>{@code horarios} (2026-08-14) só é exigido/gravado quando {@code controlaHorarioAcesso}
     * é {@code true} — ver validação em {@code UsuarioService.validar}. Nunca se aplica ao
     * administrador (a tela nem mostra o campo pra ele).
     */
    public record UsuarioRequest(
            @NotBlank @Size(max = 160) String nome,
            @NotBlank @Email @Size(max = 160) String email,
            @Size(max = 100) String senha,
            Boolean ativo,
            @NotEmpty List<Long> idsEmpresa,
            Boolean controlaHorarioAcesso,
            List<@Valid HorarioAcessoRequest> horarios) {
    }

    /** Uma linha de horário permitido por dia da semana (1=segunda..7=domingo, ISO). Os dois
     *  campos de hora nulos juntos = sem acesso naquele dia (ex.: domingo). */
    public record HorarioAcessoRequest(int diaSemana, LocalTime horaInicio, LocalTime horaFim) {
    }

    public record EmpresaAcesso(long idEmpresa, String nomeEmpresa) {
    }

    public record HorarioAcessoResponse(int diaSemana, LocalTime horaInicio, LocalTime horaFim) {
    }

    public record UsuarioResponse(
            long idUsuario,
            String nome,
            String email,
            boolean ativo,
            boolean administrador,
            List<EmpresaAcesso> empresas,
            boolean controlaHorarioAcesso,
            List<HorarioAcessoResponse> horarios,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página — mesmo padrão de {@code cadastros.funcionario}. */
    public record PaginaUsuarios(
            List<UsuarioResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Resultado do DELETE: {@code acao} é {@code "excluido"} ou {@code "inativado"}. */
    public record ExclusaoUsuarioResponse(String acao, String motivo) {
    }
}
