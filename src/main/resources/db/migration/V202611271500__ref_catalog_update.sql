-- Расширение справочников: сценарий ПМИ 54, ФТ-22.

INSERT INTO ref.airport (iata_code, name, city, is_active) VALUES
       ('DME', 'Домодедово', 'Москва', true),
       ('VKO', 'Внуково', 'Москва', true),
       ('ZIA', 'Жуковский', 'Жуковский', true),
       ('OVB', 'Толмачёво', 'Новосибирск', true),
       ('SVX', 'Кольцово', 'Екатеринбург', true),
       ('KZN', 'Казань', 'Казань', true),
       ('AER', 'Сочи', 'Сочи', true),
       ('UFA', 'Уфа', 'Уфа', true),
       ('KUF', 'Курумоч', 'Самара', true),
       ('KJA', 'Красноярск', 'Красноярск', true),
       ('IKT', 'Иркутск', 'Иркутск', true),
       ('VVO', 'Владивосток', 'Владивосток', true),
       ('KHV', 'Хабаровск', 'Хабаровск', true),
       ('MRV', 'Минеральные Воды', 'Минеральные Воды', true),
       ('PEE', 'Большое Савино', 'Пермь', true),
       ('OMS', 'Омск-Центральный', 'Омск', true),
       ('TJM', 'Рощино', 'Тюмень', true),
       ('DXB', 'Дубай', 'Дубай', true),
       ('IST', 'Стамбул', 'Стамбул', true),
       ('MSQ', 'Минск', 'Минск', true)
ON CONFLICT (iata_code) DO NOTHING;

INSERT INTO ref.airline (iata_code, name, is_active) VALUES
     ('S7', 'S7 Airlines', true),
     ('U6', 'Уральские авиалинии', true),
     ('UT', 'ЮТэйр', true),
     ('N4', 'Nordwind Airlines', true),
     ('Y7', 'NordStar', true),
     ('WZ', 'Red Wings', true),
     ('5N', 'Smartavia', true),
     ('A4', 'Азимут', true),
     ('HZ', 'Аврора', true),
     ('R3', 'Якутия', true),
     ('EK', 'Emirates', true),
     ('TK', 'Turkish Airlines', true),
     ('QR', 'Qatar Airways', true),
     ('B2', 'Белавиа', true),
     ('HY', 'Uzbekistan Airways', true)
ON CONFLICT (iata_code) DO NOTHING;

INSERT INTO ref.aircraft_type (code, name, is_active) VALUES
      ('A319', 'Airbus A319', true),
      ('A321', 'Airbus A321', true),
      ('A20N', 'Airbus A320neo', true),
      ('A21N', 'Airbus A321neo', true),
      ('B738', 'Boeing 737-800', true),
      ('B739', 'Boeing 737-900', true),
      ('SU95', 'Sukhoi Superjet 100', true),
      ('E190', 'Embraer 190', true)
ON CONFLICT (code) DO NOTHING;
