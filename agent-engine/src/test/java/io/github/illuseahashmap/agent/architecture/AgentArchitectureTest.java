package io.github.illuseahashmap.agent.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AgentArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.illuseahashmap.agent");
    }

    @Test
    void domainLayerDoesNotDependOnFrameworkOrOuterLayers() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "org.flowable..",
                        "jakarta..",
                        "java.sql..",
                        "..application..",
                        "..infrastructure..",
                        "..interfaces..")
                .because("Agent domain code must stay framework-independent and point only inward")
                .check(productionClasses);
    }

    @Test
    void agentEngineDoesNotDependOnWorkflowEngine() {
        noClasses().that().resideInAPackage("io.github.illuseahashmap.agent..")
                .should().dependOnClassesThat().resideInAPackage("io.github.illuseahashmap.workflow.process..")
                .because("agent-engine communicates with workflow-engine only through stable events and ports")
                .check(productionClasses);
    }
}
