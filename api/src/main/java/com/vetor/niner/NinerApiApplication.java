package com.vetor.niner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

// ⚠️ `@EnableScheduling` saiu daqui em 2026-08-29 e foi para `comum.AgendadoresConfig`, que o
// condiciona a uma propriedade — sem isso os seis jobs disparavam DENTRO da suíte e produziam
// o pior tipo de falha: teste que passa sozinho e reprova na suíte. Ver o javadoc de lá.
@SpringBootApplication
public class NinerApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(NinerApiApplication.class, args);
	}

	/** Relógio do sistema, injetável — permite testes futuros substituírem por um Clock fixo
	 *  sem mexer em quem consome (hoje: expurgo de {@code comum.arquivocompartilhado}). */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
