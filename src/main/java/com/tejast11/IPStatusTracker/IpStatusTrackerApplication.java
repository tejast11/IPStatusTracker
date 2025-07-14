package com.tejast11.IPStatusTracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IpStatusTrackerApplication {
	public static void main(String[] args) {
		SpringApplication.run(IpStatusTrackerApplication.class, args);
	}
}
