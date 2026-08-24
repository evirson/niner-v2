package com.vetor.niner.cadastros.fornecedor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** DTOs do cadastro de fornecedor (docs/telas/fornecedor.md). */
public final class FornecedorDtos {

    private FornecedorDtos() {
    }

    /**
     * Corpo de criação/atualização. {@code razaoSocial} e {@code idPlanoContas} são os
     * únicos obrigatórios estruturais (NOT NULL no banco); os demais são opcionais, exceto
     * quando marcados obrigatórios pela configuração de tela do tenant
     * ({@code cfg_tela_campo}).
     */
    public record FornecedorRequest(
            @NotBlank @Size(max = 160) String razaoSocial,
            @NotBlank @Size(max = 20) String idPlanoContas,
            @Size(max = 160) String nomeFantasia,
            @Size(max = 20) String cnpj,
            @Size(max = 30) String inscricaoEstadual,
            @Email @Size(max = 160) String email,
            @Size(max = 30) String telefone,
            @Size(max = 9) String cep,
            /** Codigo IBGE do municipio (7 digitos) — preenchido pelo CEP na tela. Exigido pela
             *  NF-e 55 da DEVOLUCAO AO FORNECEDOR, onde o fornecedor e o destinatario. */
            @Size(max = 7) String codigoMunicipioIbge,
            /** 1 contribuinte · 2 isento · 9 nao contribuinte. Decide se a IE entra na nota.
             *  Nulo mantem o padrao 9. */
            Integer indicadorIe,
            @Size(max = 160) String endereco,
            @Size(max = 20) String numero,
            @Size(max = 80) String bairro,
            @Size(max = 80) String cidade,
            @Size(max = 2) String estado,
            Boolean ativo) {
    }

    public record FornecedorResponse(
            long idFornecedor,
            String razaoSocial,
            String idPlanoContas,
            String descricaoPlanoContas,
            String nomeFantasia,
            String cnpj,
            String inscricaoEstadual,
            String email,
            String telefone,
            String cep,
            String codigoMunicipioIbge,
            Integer indicadorIe,
            String endereco,
            String numero,
            String bairro,
            String cidade,
            String estado,
            boolean ativo,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
    }

    /** Listagem paginada por número de página — mesmo padrão de {@code cadastros.cliente}. */
    public record PaginaFornecedores(
            List<FornecedorResponse> itens, int pagina, int tamanhoPagina, long totalItens, int totalPaginas) {
    }

    /** Resultado do DELETE: {@code acao} é {@code "excluido"} ou {@code "inativado"}. */
    public record ExclusaoFornecedorResponse(String acao, String motivo) {
    }
}
