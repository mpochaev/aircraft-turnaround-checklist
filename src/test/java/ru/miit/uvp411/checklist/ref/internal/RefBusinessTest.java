package ru.miit.uvp411.checklist.ref.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.miit.uvp411.checklist.log.AuditLog;
import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.web.Paging;

class RefBusinessTest {
    RefRepository repository;
    AuditLog audit;
    RefService service;

    @BeforeEach
    void setup() {
        repository = mock(RefRepository.class);
        audit = mock(AuditLog.class);
        service = new RefService(repository, audit);
    }

    // Поиск, фильтры, страница и сортировка передаются в репозиторий.
    @Test
    void passesListOptions() {
        var response = Map.<String, Object>of("items", List.of(), "total", 0L);
        when(repository.page("stands", "all", "Gate", null, 2, Paging.of(2, 10), "code", "desc"))
            .thenReturn(response);
        assertEquals(response, service.list("stands", "all", " Gate ", null, 2, 2, 10, "code", "desc"));
    }

    // Запрос не может подставить произвольный SQL в сортировку.
    @ParameterizedTest
    @ValueSource(strings = {"name desc; drop table ref.airport", "unknown", "airportId"})
    void rejectsUnknownSort(String sort) {
        assertThrows(ApiException.class, () -> service.list("airports", "true", "", null, null, 1, 25, sort, "asc"));
        verifyNoInteractions(repository);
    }

    // Неправильные параметры списка дают понятную ошибку.
    @Test
    void rejectsInvalidFiltersAndPaging() {
        assertThrows(ApiException.class, () -> service.list("airports", "yes", "", null, null, 1, 25, "", "asc"));
        assertThrows(ApiException.class, () -> service.list("airports", "all", "x".repeat(51), null, null, 1, 25, "", "asc"));
        assertThrows(ApiException.class, () -> service.list("airports", "all", "", 1, null, 1, 25, "", "asc"));
        assertThrows(ApiException.class, () -> service.list("stands", "all", "", null, 0, 1, 25, "", "asc"));
        assertThrows(ApiException.class, () -> service.list("airports", "all", "", null, null, 0, 25, "", "asc"));
        assertThrows(ApiException.class, () -> service.list("airports", "all", "", null, null, 1, 101, "", "asc"));
        assertThrows(ApiException.class, () -> service.list("airports", "all", "", null, null, 1, 25, "name", "sideways"));
        verifyNoInteractions(repository);
    }

    // Нельзя сохранить ссылку на отсутствующую или неактивную запись.
    @Test
    void rejectsInactiveReference() {
        var error = assertThrows(ApiException.class,
            () -> service.create("stands", Map.of("airportId", 2, "code", "T1")));
        assertEquals("INACTIVE_REFERENCE", error.code());
        verify(repository, never()).save(anyString(), any(), anyMap());
        verifyNoInteractions(audit);
    }

    // При повторной активации связанные записи проверяются ещё раз.
    @Test
    void reactivationChecksReferences() {
        when(repository.one("stands", 1)).thenReturn(Map.of("id", 1, "airportId", 2, "isActive", false));
        assertEquals("INACTIVE_REFERENCE", assertThrows(ApiException.class,
            () -> service.update("stands", 1, Map.of("isActive", true))).code());
    }

    // Создание и изменение записывают в журнал значения до и после изменения.
    @Test
    void writesAuditForCreateAndUpdate() {
        var body = Map.<String, Object>of("iataCode", "ABC", "name", "Airport", "city", "City");
        var created = Map.<String, Object>of("id", 1, "name", "Airport");
        var updated = Map.<String, Object>of("id", 1, "name", "New");
        when(repository.save(eq("airports"), isNull(), anyMap())).thenReturn(1L);
        when(repository.one("airports", 1)).thenReturn(created);
        service.create("airports", body);
        verify(audit).record(new AuditLog.Entry("ref_created", "airport", 1L, null, null, created));
        when(repository.save(eq("airports"), eq(1L), anyMap())).thenReturn(1L);
        when(repository.one("airports", 1)).thenReturn(created, updated);
        service.update("airports", 1, Map.of("name", "New"));
        verify(audit).record(new AuditLog.Entry("ref_changed", "airport", 1L, null, created, updated));
    }

    void normSetup() {
        when(repository.one("operation-norms", 1)).thenReturn(Map.of("id", 1, "aircraftTypeId", 7, "prevNormIds", List.of()));
        when(repository.activeLocked("operation-norms", 2)).thenReturn(true);
        when(repository.one("operation-norms", 2)).thenReturn(Map.of("id", 2, "aircraftTypeId", 7));
    }

    // Норматив не может зависеть от самого себя.
    @Test
    void rejectsSelfDependency() {
        normSetup();
        assertEquals("DEPENDENCY_CYCLE", assertThrows(ApiException.class,
            () -> service.update("operation-norms", 1, Map.of("prevNormIds", List.of(1)))).code());
        verify(repository, never()).save(anyString(), any(), anyMap());
    }

    // Проверяется и цикл через цепочку других нормативов.
    @Test
    void rejectsIndirectCycle() {
        normSetup();
        when(repository.graph()).thenReturn(List.of(
            Map.of("norm_id", 2, "prev_norm_id", 3), Map.of("norm_id", 3, "prev_norm_id", 1)));
        when(repository.one("operation-norms", 3)).thenReturn(Map.of("aircraftTypeId", 7));
        assertEquals("DEPENDENCY_CYCLE", assertThrows(ApiException.class,
            () -> service.update("operation-norms", 1, Map.of("prevNormIds", List.of(2)))).code());
    }

    // Предшественник должен быть того же типа ВС.
    @Test
    void rejectsAnotherType() {
        normSetup();
        when(repository.one("operation-norms", 2)).thenReturn(Map.of("aircraftTypeId", 8));
        assertEquals("VALIDATION_ERROR", assertThrows(ApiException.class,
            () -> service.update("operation-norms", 1, Map.of("prevNormIds", List.of(2)))).code());
    }

    // Нельзя сменить тип, если от норматива зависят нормативы прежнего типа.
    @Test
    void rejectsTypeChangeWithDependents() {
        normSetup();
        when(repository.activeLocked("aircraft-types", 8)).thenReturn(true);
        when(repository.graph()).thenReturn(List.of(Map.of("norm_id", 2, "prev_norm_id", 1)));
        assertEquals("VALIDATION_ERROR", assertThrows(ApiException.class,
            () -> service.update("operation-norms", 1, Map.of("aircraftTypeId", 8))).code());
    }

    // Зависимости сохраняются отдельно; их можно полностью убрать.
    @Test
    void savesAndClearsDependencies() {
        normSetup();
        when(repository.save(eq("operation-norms"), eq(1L), anyMap())).thenReturn(1L);
        service.update("operation-norms", 1, Map.of("prevNormIds", List.of(2)));
        verify(repository).dependencies(1, List.of(2));
        verify(repository).save("operation-norms", 1L, Map.of());
        service.update("operation-norms", 1, Map.of("prevNormIds", List.of()));
        verify(repository).dependencies(1, List.of());
    }

    // Другой модуль получает зависимости как список кодов, а не строку.
    @Test
    void activeNormsContainDependencies() {
        when(repository.list("operation-norms", false)).thenReturn(List.of(
            Map.of("id", 1, "aircraftType", "A320", "aircraftTypeId", 7, "operationId", 8, "after", "STAIRS,BAGGAGE", "prevNormIds", List.of(2, 3)),
            Map.of("id", 4, "aircraftType", "B737", "aircraftTypeId", 9, "operationId", 8)));
        when(repository.isActive("aircraft-types", 7)).thenReturn(true);
        when(repository.isActive("operations", 8)).thenReturn(true);
        var norms = service.activeNorms("A320");
        assertEquals(1, norms.size());
        assertEquals(List.of("STAIRS", "BAGGAGE"), norms.getFirst().get("after"));
        assertEquals(List.of(2, 3), norms.getFirst().get("prevNormIds"));
        when(repository.isActive("operations", 8)).thenReturn(false);
        assertTrue(service.activeNorms("A320").isEmpty());
    }

    // Список зависимостей не принимает нули, повторы и текст.
    @Test
    void validatesDependencyIds() {
        for (Object ids : List.of(List.of(0), List.of(1, 1), List.of("1"), "1")) {
            assertThrows(ApiException.class, () -> RefValidator.validate("operation-norms", Map.of("prevNormIds", ids), false));
        }
    }
}
