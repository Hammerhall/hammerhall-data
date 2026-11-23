-- mov-data-v1 — ключи, проверки, внешние ключи, индексы. Выполнять после загрузки данных.
-- Сгенерировано из описания схемы; руками не править.

-- первичные ключи
ALTER TABLE genres ADD CONSTRAINT genres_pkey PRIMARY KEY (id);
ALTER TABLE movies ADD CONSTRAINT movies_pkey PRIMARY KEY (id);
ALTER TABLE actors ADD CONSTRAINT actors_pkey PRIMARY KEY (id);
ALTER TABLE directors ADD CONSTRAINT directors_pkey PRIMARY KEY (id);
ALTER TABLE roles ADD CONSTRAINT roles_pkey PRIMARY KEY (actor_id, movie_id);
ALTER TABLE movies_directors ADD CONSTRAINT movies_directors_pkey PRIMARY KEY (director_id, movie_id);
ALTER TABLE movies_genres ADD CONSTRAINT movies_genres_pkey PRIMARY KEY (movie_id, genre_id);
ALTER TABLE directors_genres ADD CONSTRAINT directors_genres_pkey PRIMARY KEY (director_id, genre_id);
ALTER TABLE movies_countries ADD CONSTRAINT movies_countries_pkey PRIMARY KEY (movie_id, country_code);
ALTER TABLE movies_languages ADD CONSTRAINT movies_languages_pkey PRIMARY KEY (movie_id, language_code);

-- уникальность
ALTER TABLE genres ADD CONSTRAINT genres_name_key UNIQUE (name);

-- допустимые значения и проверки
ALTER TABLE movies ADD CONSTRAINT movies_year_check CHECK (year >= 1874);
ALTER TABLE movies ADD CONSTRAINT movies_runtime_check CHECK (runtime BETWEEN 1 AND 1500);
ALTER TABLE movies ADD CONSTRAINT movies_rank_check CHECK (rank BETWEEN 1.0 AND 9.9);
ALTER TABLE actors ADD CONSTRAINT actors_gender_check CHECK (gender IN ('M', 'F'));
ALTER TABLE directors_genres ADD CONSTRAINT directors_genres_prob_check CHECK (prob > 0 AND prob <= 1);

-- внешние ключи
ALTER TABLE roles ADD CONSTRAINT roles_actor_id_fkey FOREIGN KEY (actor_id) REFERENCES actors (id);
ALTER TABLE roles ADD CONSTRAINT roles_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES movies (id);
ALTER TABLE movies_directors ADD CONSTRAINT movies_directors_director_id_fkey FOREIGN KEY (director_id) REFERENCES directors (id);
ALTER TABLE movies_directors ADD CONSTRAINT movies_directors_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES movies (id);
ALTER TABLE movies_genres ADD CONSTRAINT movies_genres_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES movies (id);
ALTER TABLE movies_genres ADD CONSTRAINT movies_genres_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES genres (id);
ALTER TABLE directors_genres ADD CONSTRAINT directors_genres_director_id_fkey FOREIGN KEY (director_id) REFERENCES directors (id);
ALTER TABLE directors_genres ADD CONSTRAINT directors_genres_genre_id_fkey FOREIGN KEY (genre_id) REFERENCES genres (id);
ALTER TABLE movies_countries ADD CONSTRAINT movies_countries_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES movies (id);
ALTER TABLE movies_languages ADD CONSTRAINT movies_languages_movie_id_fkey FOREIGN KEY (movie_id) REFERENCES movies (id);

-- индексы (внешние ключи и поиск по времени)
CREATE INDEX roles_movie_id_idx ON roles (movie_id);
CREATE INDEX movies_directors_movie_id_idx ON movies_directors (movie_id);
CREATE INDEX movies_genres_genre_id_idx ON movies_genres (genre_id);
CREATE INDEX directors_genres_genre_id_idx ON directors_genres (genre_id);
