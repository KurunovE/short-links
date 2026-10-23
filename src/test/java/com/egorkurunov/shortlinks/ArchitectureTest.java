package com.egorkurunov.shortlinks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    @Test
    void dependenciesPointInward() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.egorkurunov.shortlinks");
        classes().that().areRecords().should().beTopLevelClasses().check(classes);
        classes()
                .that()
                .haveSimpleNameEndingWith("Request")
                .or()
                .haveSimpleNameEndingWith("Response")
                .should()
                .resideInAPackage("com.egorkurunov.shortlinks.dto")
                .check(classes);
        classes()
                .that()
                .resideInAPackage("..dto")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "jakarta.validation..", "..domain..", "..dto")
                .check(classes);
        classes()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..domain..")
                .check(classes);
        classes()
                .that()
                .resideInAPackage("..application..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..domain..", "..application..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..adapter.in..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.out..")
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..adapter.out..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.in..")
                .check(classes);
        for (String adapter : new String[] {"persistence", "cache", "codegen"}) {
            noClasses()
                    .that()
                    .resideInAPackage("..adapter.out." + adapter + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            Arrays.stream(new String[] {"persistence", "cache", "codegen"})
                                    .filter(name -> !name.equals(adapter))
                                    .map(name -> "..adapter.out." + name + "..")
                                    .toArray(String[]::new))
                    .check(classes);
        }
    }
}
