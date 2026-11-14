package io.github.bot.marketrates;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Criterion 14: provider classes are referenced only from their adapter package and config. */
class ProviderIsolationArchTest {

    private final com.tngtech.archunit.core.domain.JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.bot.marketrates");

    @Test
    void domain_application_and_web_adapter_do_not_reference_any_outbound_adapter() {
        noClasses()
                .that()
                .resideInAnyPackage(
                        "io.github.bot.marketrates.domain..",
                        "io.github.bot.marketrates.application..",
                        "io.github.bot.marketrates.adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.github.bot.marketrates.adapter.out..")
                .check(classes);
    }

    @Test
    void binance_classes_are_referenced_only_from_the_binance_package_and_config() {
        classes()
                .that()
                .resideInAPackage("io.github.bot.marketrates.adapter.out.binance..")
                .should()
                .onlyHaveDependentClassesThat()
                .resideInAnyPackage(
                        "io.github.bot.marketrates.adapter.out.binance..", "io.github.bot.marketrates.config..")
                .check(classes);
    }
}
