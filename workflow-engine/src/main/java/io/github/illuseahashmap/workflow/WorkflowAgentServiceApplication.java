package io.github.illuseahashmap.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkflowAgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowAgentServiceApplication.class, args);
    }
}
