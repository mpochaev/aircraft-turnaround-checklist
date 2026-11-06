package ru.miit.uvp411.checklist.ops.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.miit.uvp411.checklist.shared.jdbc.Rows;

@Repository
public class FlightQueries {

    private static final String SELECT = """
            select f.id,
                   jsonb_build_object('id', f.airline_id, 'iataCode', al.iata_code) as airline,
                   f.flight_number as "flightNumber", f.letter, f.flight_date as "flightDate",
                   dep.iata_code as "depAirport", arr.iata_code as "arrAirport",
                   f.std, f.sta, f.atd, f.ata
            from ops.flight f
            join ref.airline al on al.id = f.airline_id
            join ref.airport dep on dep.id = f.dep_airport_id
            join ref.airport arr on arr.id = f.arr_airport_id
            """;

    private final JdbcClient jdbc;

    public FlightQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(LocalDate date, Integer airlineId, String number, Integer arrAirportId,
                                            Integer depAirportId, boolean free) {
        return Rows.clean(jdbc.sql(SELECT + """
                        where f.flight_date = :date
                          and (cast(:airlineId as integer) is null or f.airline_id = :airlineId)
                          and (cast(:number as varchar) is null or f.flight_number = :number)
                          and (cast(:arrAirportId as integer) is null or f.arr_airport_id = :arrAirportId)
                          and (cast(:depAirportId as integer) is null or f.dep_airport_id = :depAirportId)
                          and (not :free or not exists (
                              select 1 from ops.turnaround t
                              where t.status <> 'cancelled'
                                and (t.arr_flight_id = f.id or t.dep_flight_id = f.id)
                          ))
                        order by f.std, f.id
                        """)
                .param("date", date)
                .param("airlineId", airlineId)
                .param("number", number)
                .param("arrAirportId", arrAirportId)
                .param("depAirportId", depAirportId)
                .param("free", free)
                .query().listOfRows());
    }

    long insert(FlightRequest request) {
        return jdbc.sql("""
                        insert into ops.flight (airline_id, flight_number, letter, flight_date,
                                                dep_airport_id, arr_airport_id, std, sta)
                        values (:airlineId, :number, :letter, :date, :depAirportId, :arrAirportId, :std, :sta)
                        returning id
                        """)
                .param("airlineId", request.airlineId())
                .param("number", request.flightNumber())
                .param("letter", request.normalizedLetter())
                .param("date", request.flightDate())
                .param("depAirportId", request.depAirportId())
                .param("arrAirportId", request.arrAirportId())
                .param("std", request.std())
                .param("sta", request.sta())
                .query(Long.class).single();
    }

    Map<String, Object> byId(long id) {
        return Rows.clean(jdbc.sql(SELECT + " where f.id = :id")
                .param("id", id).query().singleRow());
    }
}
