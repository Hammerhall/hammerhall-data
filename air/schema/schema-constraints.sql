-- air-data-v1 — ключи, проверки, внешние ключи, индексы. Выполнять после загрузки данных.
-- Сгенерировано из описания схемы; руками не править.

-- первичные ключи
ALTER TABLE airports ADD CONSTRAINT airports_pkey PRIMARY KEY (airport_code);
ALTER TABLE airplanes ADD CONSTRAINT airplanes_pkey PRIMARY KEY (airplane_code);
ALTER TABLE seats ADD CONSTRAINT seats_pkey PRIMARY KEY (airplane_code, seat_no);
ALTER TABLE routes ADD CONSTRAINT routes_pkey PRIMARY KEY (route_no, valid_from);
ALTER TABLE flights ADD CONSTRAINT flights_pkey PRIMARY KEY (flight_id);
ALTER TABLE bookings ADD CONSTRAINT bookings_pkey PRIMARY KEY (book_ref);
ALTER TABLE tickets ADD CONSTRAINT tickets_pkey PRIMARY KEY (ticket_no);
ALTER TABLE segments ADD CONSTRAINT segments_pkey PRIMARY KEY (ticket_no, flight_id);
ALTER TABLE boarding_passes ADD CONSTRAINT boarding_passes_pkey PRIMARY KEY (ticket_no, flight_id);

-- уникальность
ALTER TABLE flights ADD CONSTRAINT flights_route_no_scheduled_departure_key UNIQUE (route_no, scheduled_departure);
ALTER TABLE boarding_passes ADD CONSTRAINT boarding_passes_flight_id_boarding_no_key UNIQUE (flight_id, boarding_no);
ALTER TABLE boarding_passes ADD CONSTRAINT boarding_passes_flight_id_seat_no_key UNIQUE (flight_id, seat_no);

-- допустимые значения и проверки
ALTER TABLE seats ADD CONSTRAINT seats_fare_conditions_check CHECK (fare_conditions IN ('Economy', 'Comfort', 'Business'));
ALTER TABLE routes ADD CONSTRAINT routes_valid_check CHECK (valid_from < valid_to);
ALTER TABLE flights ADD CONSTRAINT flights_status_check CHECK (status IN ('Scheduled', 'On Time', 'Delayed', 'Boarding', 'Departed', 'Arrived', 'Cancelled'));
ALTER TABLE flights ADD CONSTRAINT flights_schedule_check CHECK (scheduled_departure < scheduled_arrival);
ALTER TABLE segments ADD CONSTRAINT segments_fare_conditions_check CHECK (fare_conditions IN ('Economy', 'Comfort', 'Business'));

-- внешние ключи
ALTER TABLE seats ADD CONSTRAINT seats_airplane_code_fkey FOREIGN KEY (airplane_code) REFERENCES airplanes (airplane_code);
ALTER TABLE routes ADD CONSTRAINT routes_departure_airport_fkey FOREIGN KEY (departure_airport) REFERENCES airports (airport_code);
ALTER TABLE routes ADD CONSTRAINT routes_arrival_airport_fkey FOREIGN KEY (arrival_airport) REFERENCES airports (airport_code);
ALTER TABLE routes ADD CONSTRAINT routes_airplane_code_fkey FOREIGN KEY (airplane_code) REFERENCES airplanes (airplane_code);
ALTER TABLE tickets ADD CONSTRAINT tickets_book_ref_fkey FOREIGN KEY (book_ref) REFERENCES bookings (book_ref);
ALTER TABLE segments ADD CONSTRAINT segments_ticket_no_fkey FOREIGN KEY (ticket_no) REFERENCES tickets (ticket_no);
ALTER TABLE segments ADD CONSTRAINT segments_flight_id_fkey FOREIGN KEY (flight_id) REFERENCES flights (flight_id);
ALTER TABLE boarding_passes ADD CONSTRAINT boarding_passes_ticket_no_flight_id_fkey FOREIGN KEY (ticket_no, flight_id) REFERENCES segments (ticket_no, flight_id);

-- индексы (внешние ключи и поиск по времени)
CREATE INDEX routes_departure_airport_idx ON routes (departure_airport);
CREATE INDEX routes_arrival_airport_idx ON routes (arrival_airport);
CREATE INDEX routes_airplane_code_idx ON routes (airplane_code);
CREATE INDEX flights_scheduled_departure_idx ON flights (scheduled_departure);
CREATE INDEX bookings_book_date_idx ON bookings (book_date);
CREATE INDEX tickets_book_ref_idx ON tickets (book_ref);
CREATE INDEX segments_flight_id_idx ON segments (flight_id);
