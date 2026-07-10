package com.vetor.niner;

import org.springframework.boot.SpringApplication;

public class TestNinerApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(NinerApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
