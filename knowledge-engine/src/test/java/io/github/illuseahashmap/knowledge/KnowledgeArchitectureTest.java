package io.github.illuseahashmap.knowledge;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class KnowledgeArchitectureTest {
    private static final ArchRule KNOWLEDGE_MUST_NOT_DEPEND_ON_RUNTIME_MODULES = noClasses()
            .that().resideInAnyPackage("io.github.illuseahashmap.knowledge..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("io.github.illuseahashmap.agent..",
                    "io.github.illuseahashmap.workflow.process..",
                    "io.github.illuseahashmap.workflow.agent..")
            .because("knowledge-engine is an independent bounded context and exposes ports only");

    @Test
    void knowledgeModuleDoesNotDependOnRuntimeModules() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("io.github.illuseahashmap.knowledge");
        KNOWLEDGE_MUST_NOT_DEPEND_ON_RUNTIME_MODULES.check(classes);
    }
}
