package com.vetor.niner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
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
