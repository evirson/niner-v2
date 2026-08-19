package com.vetor.niner.plataforma.staff;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** DTOs da sessão do staff da plataforma. */
public final class StaffDtos {

    private StaffDtos() {
    }

    public record LoginStaffRequest(@NotBlank @Email String email, @NotBlank String senha) {
    }

    /** Nunca devolve hash, id interno de outra população nem nada além do necessário à tela. */
    public record SessaoStaffResponse(String token, String nome, String email, String papel) {
    }
}
