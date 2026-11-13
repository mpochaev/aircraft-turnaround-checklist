-- Поиск рейсов по дате с сортировкой по плановому времени вылета (GET /flights).
CREATE INDEX flight_date_std_idx ON ops.flight (flight_date, std);
