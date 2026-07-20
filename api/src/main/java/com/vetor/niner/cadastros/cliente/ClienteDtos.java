package com.vetor.niner.cadastros.cliente;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de cliente (docs/telas/cliente.md). */
public final class ClienteDtos {

    private ClienteDtos() {
    }

    public enum Genero {
        MASCULINO, FEMININO, OUTROS
    }

    /**
     * Corpo de criação/atualização. {@code dataNascimento}/{@code genero} são obrigatórios
     * só para pessoa física (validado no serviço, refletindo o CHECK do banco); demais
     * campos além de {@code nome}/{@code idCategoriaCliente} são opcionais.
     */
    public record ClienteRequest(
            boolean fisicaJuridica,
            @NotBlank @Size(max = 160) String nome,
            @NotNull Integer idCategoriaCliente,
            @Size(max = 20) String cpfCnpj,
            @Size(max = 20) String rgIe,
            LocalDate dataNascimento,
            Genero genero,
            @Email @Size(max = 160) String email,
            @Size(max = 30) String telefone,
            @Size(max = 30) String whatsapp,
            @Size(max = 60) String instagram,
            @Size(max = 60) String facebook,
            @Size(max = 60) String tiktok,
            @Size(max = 9) String cep,
            @Size(max = 160) String endereco,
            @Size(max = 20) String numero,
            @Size(max = 80) String complemento,
            @Size(max = 80) String bairro,
            @Size(max = 80) String cidade,
            @Size(max = 2) String estado,
            BigDecimal limiteCredito,
            Boolean ativo) {
    }

    public record ClienteResponse(
            long idCliente,
            boolean fisicaJuridica,
            String nome,
            int idCategoriaCliente,
            String nomeCategoria,
            String cpfCnpj,
            String rgIe,
            LocalDate dataNascimento,
            Genero genero,
            String email,
            String telefone,
            String whatsapp,
            String instagram,
            String facebook,
            String tiktok,
            String cep,
            String endereco,
            String numero,
            String complemento,
            String bairro,
            String cidade,
            String estado,
            BigDecimal limiteCredito,
            boolean ativo,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por cursor (§3.4): {@code proximoCursor} nulo = última página. */
    public record PaginaClientes(List<ClienteResponse> itens, Long proximoCursor) {
    }

    /** Resultado do DELETE: {@code acao} é {@code "excluido"} ou {@code "inativado"}. */
    public record ExclusaoClienteResponse(String acao, String motivo) {
    }
}
