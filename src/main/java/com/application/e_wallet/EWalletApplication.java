package com.application.e_wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class EWalletApplication {

	private static final Logger log = LoggerFactory.getLogger(EWalletApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(EWalletApplication.class, args);

		 log.info("Request destination");
	}

}
