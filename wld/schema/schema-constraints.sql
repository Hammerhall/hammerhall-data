-- wld-data-v1 — ключи, проверки, внешние ключи, индексы. Выполнять после загрузки данных.
-- Сгенерировано из описания схемы; руками не править.

-- первичные ключи
ALTER TABLE countries ADD CONSTRAINT countries_pkey PRIMARY KEY (code);
ALTER TABLE cities ADD CONSTRAINT cities_pkey PRIMARY KEY (id);
ALTER TABLE country_languages ADD CONSTRAINT country_languages_pkey PRIMARY KEY (country_code, language_code);
ALTER TABLE country_borders ADD CONSTRAINT country_borders_pkey PRIMARY KEY (country_code, neighbour_code);
ALTER TABLE country_years ADD CONSTRAINT country_years_pkey PRIMARY KEY (country_code, year);

-- уникальность
ALTER TABLE countries ADD CONSTRAINT countries_code2_key UNIQUE (code2);

-- допустимые значения и проверки
ALTER TABLE countries ADD CONSTRAINT countries_continent_check CHECK (continent IN ('Africa', 'Antarctica', 'Asia', 'Europe', 'North America', 'Oceania', 'South America'));
ALTER TABLE cities ADD CONSTRAINT cities_latitude_check CHECK (latitude BETWEEN -90 AND 90);
ALTER TABLE cities ADD CONSTRAINT cities_longitude_check CHECK (longitude BETWEEN -180 AND 180);
ALTER TABLE country_borders ADD CONSTRAINT country_borders_not_self_check CHECK (country_code <> neighbour_code);
ALTER TABLE country_years ADD CONSTRAINT country_years_any_value_check CHECK (population IS NOT NULL OR gnp IS NOT NULL OR gdp IS NOT NULL OR life_expectancy IS NOT NULL);

-- внешние ключи
ALTER TABLE countries ADD CONSTRAINT countries_capital_fkey FOREIGN KEY (capital) REFERENCES cities (id);
ALTER TABLE cities ADD CONSTRAINT cities_country_code_fkey FOREIGN KEY (country_code) REFERENCES countries (code);
ALTER TABLE country_languages ADD CONSTRAINT country_languages_country_code_fkey FOREIGN KEY (country_code) REFERENCES countries (code);
ALTER TABLE country_borders ADD CONSTRAINT country_borders_country_code_fkey FOREIGN KEY (country_code) REFERENCES countries (code);
ALTER TABLE country_borders ADD CONSTRAINT country_borders_neighbour_code_fkey FOREIGN KEY (neighbour_code) REFERENCES countries (code);
ALTER TABLE country_years ADD CONSTRAINT country_years_country_code_fkey FOREIGN KEY (country_code) REFERENCES countries (code);

-- индексы (внешние ключи и поиск по времени)
CREATE INDEX countries_capital_idx ON countries (capital);
CREATE INDEX cities_country_code_idx ON cities (country_code);
CREATE INDEX country_borders_neighbour_code_idx ON country_borders (neighbour_code);
