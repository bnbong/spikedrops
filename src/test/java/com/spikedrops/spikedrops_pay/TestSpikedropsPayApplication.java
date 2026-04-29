package com.spikedrops.spikedrops_pay;

import org.springframework.boot.SpringApplication;

public class TestSpikedropsPayApplication {

	public static void main(String[] args) {
		SpringApplication.from(SpikedropsPayApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
