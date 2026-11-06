package ke.chama;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces ADR-006 module boundaries. The build fails if a module reaches into another
 * module's internals (anything except its base package, {@code api}, or {@code domain.event}).
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(ChamaApplication.class);

    @Test
    void detectsEveryBusinessModule() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder(
                        "shared", "auth", "audit", "chama", "member", "contribution", "loan", "penalty",
                        "expense", "investment", "transaction", "notification", "report");
    }

    @Test
    void verifiesModuleBoundaries() {
        modules.verify();
    }

    /** Writes PlantUML/AsciiDoc module diagrams to target/spring-modulith-docs. */
    @Test
    void writesDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
