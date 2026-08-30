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
            /**
             * ⚠️ {@code Boolean} com {@code @NotNull}, não {@code boolean} (auditoria 2026-08-29,
             * rodada 2). Com o primitivo, a chave <b>ausente</b> no JSON resolvia para
             * {@code false} — e aqui {@code TRUE} é pessoa <b>física</b>, então omitir o campo
             * virava o cliente em <b>jurídica</b> em silêncio. Num {@code PUT} vindo de qualquer
             * cliente da API que não seja o {@code web/} (integração, script de correção em
             * massa), o CPF passava a ser normalizado como CNPJ, a exigência de gênero deixava de
             * valer, e a venda a esse cliente passava a emitir <b>NF-e 55 em vez de NFC-e</b>.
             * Exigir explicitamente transforma o silêncio num 400 que diz qual campo falta.
             */
            @NotNull Boolean fisicaJuridica,
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
            /** Codigo IBGE do municipio (7 digitos) — obrigatorio para a NF-e 55 (enderDest),
             *  que sai em toda venda a pessoa juridica desde 2026-08-24. A NFC-e nao usa. */
            @Size(max = 7) String codigoMunicipioIbge,
            /** Como o destinatario se declara ao ICMS: 1 contribuinte · 2 isento · 9 nao
             *  contribuinte. Vai no indIEDest da NF-e, e e ELE que decide se a inscricao estadual
             *  entra na nota — o XSD recusa a tag IE quando nao e 1. Nulo mantem o padrao 9. */
            Integer indicadorIe,
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
            String codigoMunicipioIbge,
            Integer indicadorIe,
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

    /**
     * Listagem paginada por número de página, ordenada por {@code nome} (2026-07-21 —
     * substitui a paginação por cursor: a navegação numerada exige saber o total de páginas
     * e permitir pular direto para qualquer uma).
     */
    public record PaginaClientes(
            List<ClienteResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Resultado do DELETE: {@code acao} é {@code "excluido"} ou {@code "inativado"}. */
    public record ExclusaoClienteResponse(String acao, String motivo) {
    }
}
