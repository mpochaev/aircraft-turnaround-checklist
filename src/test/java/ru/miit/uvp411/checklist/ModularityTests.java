package ru.miit.uvp411.checklist;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Проверка границ модулей. Тест падает, если модуль обращается к закрытым пакетам другого модуля
 * (internal, web) или модули зависят друг от друга по кругу.
 * Заодно рисует схему модулей в target/spring-modulith-docs.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(ChecklistApplication.class);

    @Test
    void modulesRespectBoundaries() {
        modules.verify();
    }

    @Test
    void writeModuleDiagrams() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
