package com.vvttai.smart_link_shortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableJpaAuditing
public class SmartLinkShortenerApplication {
	public static void main(String[] args) {
		SpringApplication.run(SmartLinkShortenerApplication.class, args);
	}
}
