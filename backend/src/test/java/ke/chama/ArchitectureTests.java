package ke.chama;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Clean Architecture layer rules inside every module. ModularityTests covers the rules
 * between modules.
 */
@AnalyzeClasses(packages = "ke.chama", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule layersPointInward = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Presentation").definedBy("ke.chama.modules..presentation..")
            .layer("Application").definedBy("ke.chama.modules..application..")
            .layer("Domain").definedBy("ke.chama.modules..domain..", "ke.chama.shared.domain..")
            .layer("Infrastructure").definedBy("ke.chama.modules..infrastructure..")
            .whereLayer("Presentation").mayNotBeAccessedByAnyLayer()
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Presentation", "Infrastructure");

    /** Business rules must be plain Java (+ JPA mapping annotations), testable without Spring. */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAnyPackage("ke.chama.modules..domain..", "ke.chama.shared.domain..")
            .and().doNotHaveSimpleName("package-info")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "ke.chama..application..",
                    "ke.chama..infrastructure..",
                    "ke.chama..presentation..",
                    "ke.chama.shared.web..",
                    "ke.chama.shared.config..");

    /** Spring Data repositories are adapters; application code depends on port interfaces. */
    @ArchTest
    static final ArchRule repositoriesLiveInInfrastructure = classes()
            .that().areAssignableTo("org.springframework.data.repository.Repository")
            .should().resideInAPackage("ke.chama.modules..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersLiveInPresentation = classes()
            .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .should().resideInAPackage("ke.chama.modules..presentation..")
            .allowEmptyShould(true);

    /** Entities must not be serialized as API responses. */
    @ArchTest
    static final ArchRule controllersDoNotExposeEntities = noClasses()
            .that().resideInAPackage("ke.chama.modules..presentation.controller..")
            .should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
            .allowEmptyShould(true);
}
