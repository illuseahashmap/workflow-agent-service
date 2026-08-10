package io.github.illuseahashmap.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {
        "io.github.illuseahashmap.workflow",
        "io.github.illuseahashmap.agent"
})
@ConfigurationPropertiesScan(basePackages = {
        "io.github.illuseahashmap.workflow",
        "io.github.illuseahashmap.agent"
})
public class WorkflowAgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowAgentServiceApplication.class, args);
    }
}
