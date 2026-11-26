package ru.miit.uvp411.checklist.ref.internal;

import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.miit.uvp411.checklist.log.AuditLog;
import ru.miit.uvp411.checklist.ref.RefApi;
import ru.miit.uvp411.checklist.shared.error.ApiError;
import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.web.Paging;

@Service
public class RefService implements RefApi {

    private final RefRepository repository;
    private final AuditLog auditLog;

    public RefService(RefRepository repository, AuditLog auditLog) {
        this.repository = repository;
        this.auditLog = auditLog;
    }

    @Override
    public List<String> dictionaries() {
        return List.copyOf(RefRepository.DICTIONARIES.keySet());
    }

    private void checkDictionary(String name) {
        if (!RefRepository.DICTIONARIES.containsKey(name)) {
            throw new ApiException(
                HttpStatus.NOT_FOUND,
                "UNKNOWN_DICTIONARY",
                "Справочника " + name + " нет"
            );
        }
    }

    public Map<String, Object> list(
        String name, String active, String search,
        Integer type, Integer airport, int page, int size, String sort, String direction
    ) {
        checkDictionary(name);

        if (!"true".equals(active)
            && !"false".equals(active)
            && !"all".equals(active)) {
            invalid("isActive", active, "Допустимо true, false или all");
        }

        search = search == null ? "" : search.strip();

        if (search.length() > 50) {
            invalid("search", search, "Не больше 50 символов");
        }

        if (type != null && (!name.equals("operation-norms") || type < 1)) {
            invalid("aircraftTypeId", type,
                "Положительный id типа ВС допустим только для нормативов");
        }

        if (airport != null && (!name.equals("stands") || airport < 1)) {
            invalid("airportId", airport,
                "Положительный id аэропорта допустим только для стоянок");
        }

        if (!sort.isEmpty() && !RefRepository.sortFields(name).contains(sort)) {
            invalid("sort", sort, "Недоступное поле сортировки");
        }
        if (!Set.of("asc", "desc").contains(direction)) {
            invalid("direction", direction, "Допустимо asc или desc");
        }
        return repository.page(
            name, active, search, type, airport, Paging.of(page, size), sort, direction
        );
    }

    @Override
    public boolean isActive(String name, long id) {
        return repository.isActive(name, id);
    }

    @Override
    public List<Map<String, Object>> activeNorms(String typeCode) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> norm : repository.list("operation-norms", false)) {
            if (!typeCode.equals(norm.get("aircraftType"))) {
                continue;
            }

            long typeId = ((Number) norm.get("aircraftTypeId")).longValue();
            long operationId = ((Number) norm.get("operationId")).longValue();

            if (!isActive("aircraft-types", typeId)
                || !isActive("operations", operationId)) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>(norm);
            String after = (String) norm.get("after");

            List<String> predecessors = List.of();

            if (after != null && !after.isBlank()) {
                predecessors = Arrays.asList(after.split(","));
            }

            item.put("after", predecessors);
            result.add(item);
        }

        return result;
    }

    @Transactional
    public Map<String, Object> create(String name, Map<String, Object> body) {
        return change(name, null, body);
    }

    @Transactional
    public Map<String, Object> update(
        String name, long id, Map<String, Object> body
    ) {
        return change(name, id, body);
    }

    private Map<String, Object> change(
        String name, Long id, Map<String, Object> body
    ) {
        checkDictionary(name);

        boolean isNew = id == null;
        boolean isNorm = name.equals("operation-norms");

        if (isNorm) {
            repository.lockNorms();
        }

        Map<String, Object> oldRecord = null;

        if (!isNew) {
            repository.lock(name, id);
            oldRecord = repository.one(name, id);
        }

        if (body == null || (!isNew && body.isEmpty())) {
            invalid("body", body, "Передайте изменяемые поля");
        }

        Map<String, Object> values = RefValidator.validate(name, body, isNew);

        Map<String, Object> merged = new LinkedHashMap<>();

        if (oldRecord != null) {
            merged.putAll(oldRecord);
        }

        merged.putAll(values);

        // Все ссылки проверяются при создании и повторной активации.
        boolean checkAllReferences =
            isNew || Boolean.TRUE.equals(values.get("isActive"));

        Map<String, String> references = Map.of(
            "airportId", "airports",
            "aircraftTypeId", "aircraft-types",
            "airlineId", "airlines",
            "operationId", "operations"
        );

        for (Map.Entry<String, String> reference : references.entrySet()) {
            String field = reference.getKey();
            String dictionary = reference.getValue();

            if (!merged.containsKey(field)) {
                continue;
            }

            if (!checkAllReferences && !values.containsKey(field)) {
                continue;
            }

            long targetId = ((Number) merged.get(field)).longValue();

            if (!repository.activeLocked(dictionary, targetId)) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INACTIVE_REFERENCE",
                    "Связанная запись отсутствует или неактивна",
                    List.of(new ApiError.Detail(
                        field, targetId, "Выберите активную запись"
                    ))
                );
            }
        }

        List<Integer> predecessors = null;

        boolean dependenciesChanged =
            values.containsKey("prevNormIds")
                || values.containsKey("aircraftTypeId");

        if (isNorm && (isNew || dependenciesChanged || checkAllReferences)) {
            predecessors = new ArrayList<>();

            List<?> ids = (List<?>) merged.getOrDefault("prevNormIds", List.of());

            for (Object value : ids) {
                predecessors.add(((Number) value).intValue());
            }

            int typeId = ((Number) merged.get("aircraftTypeId")).intValue();
            validateDependencies(id, typeId, predecessors);
        }

        // Зависимости хранятся отдельно от самой записи норматива.
        values.remove("prevNormIds");

        {
            long savedId = repository.save(name, id, values);

            if (predecessors != null) {
                repository.dependencies(savedId, predecessors);
            }

            Map<String, Object> result = repository.one(name, savedId);

            String action = isNew ? "ref_created" : "ref_changed";
            String entity = RefRepository.DICTIONARIES
                .get(name).table().substring(4);

            auditLog.record(new AuditLog.Entry(
                action, entity, savedId, null, oldRecord, result
            ));

            return result;

        }
    }

    private void validateDependencies(
        Long id, int typeId, List<Integer> predecessors
    ) {
        for (int predecessorId : predecessors) {
            if (id != null && id == predecessorId) {
                cycle();
            }

            if (!repository.activeLocked("operation-norms", predecessorId)) {
                throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INACTIVE_REFERENCE",
                    "Предшественник отсутствует или неактивен"
                );
            }

            Map<String, Object> predecessor =
                repository.one("operation-norms", predecessorId);

            int predecessorType =
                ((Number) predecessor.get("aircraftTypeId")).intValue();

            if (predecessorType != typeId) {
                invalid("prevNormIds", predecessors,
                    "Предшественник должен относиться к тому же типу ВС");
            }
        }

        if (id == null) {
            return;
        }

        Map<Long, List<Long>> graph = new HashMap<>();

        for (Map<String, Object> edge : repository.graph()) {
            long from = ((Number) edge.get("norm_id")).longValue();
            long to = ((Number) edge.get("prev_norm_id")).longValue();

            if (to == id) {
                Map<String, Object> dependent =
                    repository.one("operation-norms", from);

                int dependentType =
                    ((Number) dependent.get("aircraftTypeId")).intValue();

                if (dependentType != typeId) {
                    invalid("aircraftTypeId", typeId,
                        "Норматив используется зависимостями другого типа ВС");
                }
            }

            if (from != id) {
                if (!graph.containsKey(from)) {
                    graph.put(from, new ArrayList<>());
                }

                graph.get(from).add(to);
            }
        }

        // Ищем путь от каждого предшественника к текущему нормативу.
        for (int predecessorId : predecessors) {
            Deque<Long> pending = new ArrayDeque<>();
            Set<Long> visited = new HashSet<>();

            pending.push((long) predecessorId);

            while (!pending.isEmpty()) {
                long currentId = pending.pop();

                if (currentId == id) {
                    cycle();
                }

                if (visited.add(currentId)) {
                    List<Long> neighbours =
                        graph.getOrDefault(currentId, List.of());

                    pending.addAll(neighbours);
                }
            }
        }
    }

    private static void invalid(String field, Object value, String message) {
        throw ApiException.validation(field, value, message);
    }

    private static void cycle() {
        throw new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "DEPENDENCY_CYCLE",
            "Зависимости нормативов замыкаются в круг"
        );
    }
}
