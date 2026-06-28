package dev.ayush.nexusq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NexusqApplication {

	public static void main(String[] args) {
		SpringApplication.run(NexusqApplication.class, args);
	}

}
