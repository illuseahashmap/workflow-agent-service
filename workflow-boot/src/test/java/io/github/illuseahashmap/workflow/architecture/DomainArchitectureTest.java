package io.github.illuseahashmap.workflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DomainArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .importPackages("io.github.illuseahashmap.workflow");
    }

    @Test
    void domainLayerDoesNotDependOnFrameworkOrOuterLayers() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "..application..",
                        "..infrastructure..",
                        "..interfaces..")
                .because("domain code must stay framework-independent and point only inward")
                .check(productionClasses);
    }

    @Test
    void applicationLayerDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .because("application services must reach adapters through ports")
                .check(productionClasses);
    }

    @Test
    void workflowModuleDoesNotReachIntoAuthInfrastructure() {
        noClasses().that().resideOutsideOfPackage("..auth..")
                .should().dependOnClassesThat().resideInAPackage("..auth.infrastructure..")
                .because("auth infrastructure is private to the authentication bounded context")
                .check(productionClasses);
    }
}
