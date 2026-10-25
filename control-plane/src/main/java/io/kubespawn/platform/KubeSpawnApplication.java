package io.kubespawn.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.kubespawn.platform.worker.WorkerSettings;

@SpringBootApplication
@EnableConfigurationProperties(WorkerSettings.class)
public class KubeSpawnApplication {

	public static void main(String[] args) {
		SpringApplication.run(KubeSpawnApplication.class, args);
	}

}
