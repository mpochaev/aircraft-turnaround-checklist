package ru.miit.uvp411.checklist.ref.internal;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import ru.miit.uvp411.checklist.ref.RefApi;
import ru.miit.uvp411.checklist.shared.error.ApiException;
import ru.miit.uvp411.checklist.shared.jdbc.Rows;

@Service
public class RefService implements RefApi {

    private final RefRepository repository;
    private final JdbcClient jdbc;

    RefService(RefRepository repository, JdbcClient jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Override
    public List<String> dictionaries() {
        return List.copyOf(RefRepository.DICTIONARIES.keySet());
    }

    /** Записи справочника. Неизвестный справочник: 404 UNKNOWN_DICTIONARY (раздел 6.1 спецификации). */
    public List<Map<String, Object>> list(String dictionary, boolean includeInactive) {
        if (!RefRepository.DICTIONARIES.containsKey(dictionary)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_DICTIONARY", "Справочника " + dictionary + " нет");
        }
        return repository.list(dictionary, includeInactive);
    }

    @Override
    public boolean isActive(String dictionary, long id) {
        return repository.isActive(dictionary, id);
    }

    @Override
    public List<Map<String, Object>> activeNorms(String aircraftTypeCode) {
        return Rows.clean(jdbc.sql("""
                        select n.id, o.code as "operationCode", n.seq_no as "seqNo",
                               n.duration_min as "durationMin", n.is_mandatory as "isMandatory"
                        from ref.operation_norm n
                        join ref.aircraft_type t on t.id = n.aircraft_type_id
                        join ref.operation o on o.id = n.operation_id
                        where t.code = :type and n.is_active
                        order by n.seq_no
                        """)
                .param("type", aircraftTypeCode)
                .query().listOfRows());
    }
}
