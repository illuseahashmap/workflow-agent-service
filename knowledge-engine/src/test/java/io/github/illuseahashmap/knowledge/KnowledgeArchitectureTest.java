package io.github.illuseahashmap.knowledge;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.github.illuseahashmap.knowledge")
class KnowledgeArchitectureTest {
    @ArchTest
    static final ArchRule knowledge_must_not_depend_on_runtime_modules = noClasses()
            .that().resideInAnyPackage("io.github.illuseahashmap.knowledge..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("io.github.illuseahashmap.agent..",
                    "io.github.illuseahashmap.workflow.process..",
                    "io.github.illuseahashmap.workflow.agent..")
            .because("knowledge-engine is an independent bounded context and exposes ports only");
}
