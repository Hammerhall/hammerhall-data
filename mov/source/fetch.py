"""Снимок источника набора Movie (mov-data-v1): фильмы, люди и справочники Wikidata — одним архивом
mov-source-YYYYMMDD.zip (выпуск mov-source-YYYYMMDD этого репозитория; вход source сборки набора).

Запуск:  python3 -X utf8 -I mov/source/fetch.py --snapshot mov-source-YYYYMMDD --out ПАПКА [--work ПАПКА]

В ПАПКЕ — mov-source-YYYYMMDD.zip, SOURCE.md и SHA256SUMS (копии тех же файлов из архива). Дерево архива собирается
в --work (в ней — папка с именем снимка, прежняя стирается); без --work — во временной папке, после сборки её нет.
Год снимка (из тега) — верхняя граница запасного пути по годам P577.

Раскладка архива — без общей верхней папки: SOURCE.md, SHA256SUMS, fetch.json, wikidata/…. Данные — CSV (UTF-8, LF,
заголовок, кавычки — где нужно; item — строкой Q…; строки — по столбцам слева направо, номера Q — как числа), большие —
.csv.gz (gzip без имени файла, время 0). Архив детерминированный: записи по имени (побайтно), время 1980-01-01
00:00:00, права 0644, .zip и .gz — без сжатия, остальное — deflate 9. Те же ответы сервиса и то же время снимка — тот
же архив побайтно.

Порядок (все запросы — к одному SPARQL-эндпойнту, POST, ответ JSON):
  1. фильмы — запрос на класс из FILM_CLASSES; класс, на котором запрос дважды не уложился во время, — по годам P577
     (по 10 лет, десятилетие, на котором и это не вышло, — по году);
  2. film_links: P31, P136, P57, P495, P364 найденных фильмов;
  3. genre_items: жанры и классы фильмов и их надклассы (P279) — вширь вверх, пока есть новые, не глубже GENRE_DEPTH;
  4. фильм, у которого класс или жанр — в замыкании P279 элемента ADULT_ROOT (фильмы для взрослых), дальше не идёт и
     из всех файлов снимка убирается;
  5. по оставшимся фильмам: даты, метки, названия, длительность, признаки популярности, состав; затем запасные даты
     (P1191, P571, P2754), выход по местам (P577 с квалификатором P291), все метки фильмов, у которых нет ни метки на
     языках LABEL_LANGS, ни P1476 (по меткам и названиям выше), съёмочная группа, студии, прокатчики, места съёмок и
     действия, соседние фильмы, серии, бюджет и сборы, награды и номинации;
  6. люди (P57, состав и съёмочная группа): P31, P21, sitelinks, метки, имена, даты рождения и смерти, место рождения
     и гражданство; затем элементы имён, персонажи, метки студий, прокатчиков, серий и наград, страны и
     штаб-квартиры студий и прокатчиков, места (P19, P915, P840, штаб-квартиры) с координатами, страны (P495, P291,
     P27, P17 мест и студий), языки.
Шаги 2–6 — частями VALUES по MOV_FETCH_CHUNK элементов, до MOV_FETCH_WORKERS запросов одновременно. Часть, на которую
сервис не ответил (тайм-аут, 5xx, оборванный или не-JSON ответ, в том числе HTTP 200), делится пополам, пока половины
не меньше MIN_CHUNK; меньшая — с обычными повторами. В конце шага — проверка, что ответила каждая часть.
Запрос по VALUES с UNION на верхнем уровне держит только VALUES и ветки: каждая ветка начинается с элемента из VALUES
и сама несёт свои фильтры и OPTIONAL. Шаблон, общий для веток (например «?st wikibase:rank ?rank» после UNION),
Blazegraph может начать раньше веток — со всех утверждений Wikidata — и не уложиться во время (тест это сторожит).

Повторы: 6 попыток, паузы 5, 15, 30, 60, 120 с, тайм-аут сокета 120 с. 429 и 503 с Retry-After — пауза из Retry-After
(не меньше обычной) для всех потоков сразу, попыткой не считается (до THROTTLE_MAX раз на запрос). Прочие 4xx, а также
последняя неудачная попытка — выход с кодом 1 и причиной (запрос и что не так).

Переменные среды (по умолчанию — настоящий сервис):
  MOV_WIKIDATA_SPARQL   https://query.wikidata.org/sparql
  MOV_FETCH_BACKOFF     множитель пауз между попытками и запросами (1; 0 — без пауз и без Retry-After)
  MOV_FETCH_MIN         0 — не проверять нижние границы числа строк (для тестов на маленьких ответах)
  MOV_FETCHED_AT        время снимка YYYY-MM-DDTHH:MM:SSZ вместо нынешнего (два прогона — один архив побайтно)
  MOV_FETCH_WORKERS     запросов одновременно (3; от 1 до 5)
  MOV_FETCH_CHUNK       элементов в части VALUES (1000)
Прокси — как у urllib: HTTPS_PROXY, HTTP_PROXY, NO_PROXY.

Только стандартная библиотека; Python 3.12 (CI) и новее.
"""
import argparse
import concurrent.futures
import csv
import datetime
import decimal
import email.utils
import hashlib
import http.client
import io
import json
import os
import pathlib
import re
import shutil
import stat
import struct
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib

USER_AGENT = 'HammerhallData/1.0 (https://github.com/Hammerhall/hammerhall-data)'
DEFAULT_SPARQL = 'https://query.wikidata.org/sparql'

SNAPSHOT_RE = re.compile(r'mov-source-([0-9]{4})([0-9]{2})([0-9]{2})')
FETCHED_AT_RE = re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z')

# повторы: попыток всего, паузы перед 2-й … 6-й (с), тайм-аут сокета (с), потолок паузы из Retry-After (с);
# сколько раз подряд сервис может попросить подождать (429, 503 с Retry-After), прежде чем запрос считается неудачным
ATTEMPTS = 6
BACKOFF = (5, 15, 30, 60, 120)
TIMEOUT = 120
RETRY_AFTER_MAX = 600
THROTTLE_MAX = 30
READ_CHUNK = 1 << 20

# части VALUES: запросов одновременно (по умолчанию и не больше — у WDQS до 5 с одного адреса), элементов в части,
# меньше скольких половина не бывает; пауза перед каждым запросом (с); шаг из стольких частей и больше — с строками
# хода через каждые 10 %
WORKERS = 3
WORKERS_MAX = 5
CHUNK = 1000
MIN_CHUNK = 50
PAUSE = 0.5
PROGRESS_PARTS = 20

# классы фильмов (P31): по ним фильмы запрашиваются (included = 1 в classes.csv)
FILM_CLASSES = (
    'Q11424',     # film
    'Q24869',     # feature film
    'Q24862',     # short film
    'Q17517379',  # animated short film
    'Q202866',    # animated film
    'Q29168811',  # animated feature film
    'Q20650540',  # anime film
    'Q18089587',  # stop-motion animated film
    'Q93204',     # documentary film
    'Q506240',    # television film
    'Q226730',    # silent film
)
# классы, по которым не запрашивается ничего: фильм с таким P31 сборка не берёт (included = 0 в classes.csv)
EXCLUDED_CLASSES = (
    'Q193977',    # music video
    'Q622550',    # trailer
    'Q18011172',  # film project
    'Q24856',     # film series
)
# фильм, у которого класс или жанр — этот элемент или его подкласс (P279, на любой глубине), в снимок не идёт
ADULT_ROOT = 'Q185529'
# жанры и классы — вверх по P279 не глубже стольких уровней; запасной путь по годам — с этого года
GENRE_DEPTH = 12
YEARS_FROM = 1870

LABEL_LANGS = ('en', 'mul', 'ru', 'de', 'fr', 'es', 'it', 'pt', 'nl', 'sv', 'pl')
LINK_PROPS = ('P136', 'P57', 'P495', 'P364')
# запасные даты фильма: первый показ, создание, дата производства
EXTRA_DATE_PROPS = ('P1191', 'P571', 'P2754')
# съёмочная группа: сценарист, композитор, оператор, продюсер, монтажёр
CREW_PROPS = ('P58', 'P86', 'P344', 'P162', 'P1040')
# ещё связи фильма: студия, прокатчик, место съёмок, место действия, предыдущий и следующий фильм; студия и
# прокатчик — компании; места съёмок и действия — места (places.csv.gz)
LINK2_PROPS = ('P272', 'P750', 'P915', 'P840', 'P155', 'P156')
COMPANY_PROPS = ('P272', 'P750')
PLACE_PROPS = ('P915', 'P840')
# координаты мест (P625) — только на этом глобусе (Земля)
EARTH = 'Q2'
# бюджет и сборы; награда и номинация
MONEY_PROPS = ('P2130', 'P2142')
AWARD_PROPS = ('P166', 'P1411')
# у людей: рождение и смерть; место рождения и гражданство
PERSON_DATE_PROPS = ('P569', 'P570')
PERSON_LINK_PROPS = ('P19', 'P27')
# метки студий, прокатчиков, серий и наград — на этих языках
ITEM_LANGS = ('en', 'mul', 'ru')
HUMAN = 'Q5'
# вид элемента имени по P31
NAME_MALE = 'Q12308941'
NAME_FEMALE = 'Q11879590'
NAME_UNISEX = 'Q3409032'
NAME_FAMILY = 'Q101352'

ENTITY_PREFIXES = ('http://www.wikidata.org/entity/', 'https://www.wikidata.org/entity/')
QID = re.compile(r'Q[1-9][0-9]*')
RANKS = {
    'http://wikiba.se/ontology#PreferredRank': 'preferred',
    'http://wikiba.se/ontology#NormalRank': 'normal',
    'http://wikiba.se/ontology#DeprecatedRank': 'deprecated',
}

# нижние границы (MOV_FETCH_MIN=0 — выключены): меньше — сервис отдал не всё
MIN_FILMS = 150000
MIN_CAST = 300000
MIN_PEOPLE = 150000

ZIP_TIME = (1980, 1, 1, 0, 0, 0)
ZIP_STORED_SUFFIXES = ('.zip', '.gz')

# Тексты запросов SPARQL — как есть в wikidata/queries/<имя>.rq. Переносимые: все PREFIX — явно, без SERVICE
# wikibase:label и подсказок движка; метки — rdfs:label с FILTER(LANG(…)).
VALUES = '%VALUES%'
CLASS = '%CLASS%'
DATE_FROM = '%FROM%'
DATE_TO = '%TO%'
LANGS_IN = ', '.join(f'"{lang}"' for lang in LABEL_LANGS)
ITEM_LANGS_IN = ', '.join(f'"{lang}"' for lang in ITEM_LANGS)
QUERIES = {
    'films_by_class': '''\
# Фильмы одного класса — item'ы, у которых P31 (instance of, лучший ранг) — %CLASS%: по запросу на каждый класс из
# FILM_CLASSES (wikidata/classes.csv, included = 1).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
SELECT ?film WHERE { ?film wdt:P31 wd:%CLASS% }
''',
    'films_by_class_years': '''\
# Запасной путь films_by_class — для класса, на котором запрос целиком дважды не уложился во время: фильмы класса
# с датой публикации (P577, лучший ранг) в [%FROM%, %TO%) — по 10 лет с 1870 года по год снимка; десятилетие, на
# котором и это дважды не вышло, — по году. Фильм без P577 так не находится (в набор он всё равно не идёт).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
SELECT DISTINCT ?film WHERE {
  ?film wdt:P31 wd:%CLASS% ;
        wdt:P577 ?date .
  FILTER(?date >= "%FROM%"^^xsd:dateTime && ?date < "%TO%"^^xsd:dateTime)
}
''',
    'film_links': '''\
# Связи фильмов (лучший ранг): P31 — классы (в films.csv), P136 — жанры, P57 — режиссёры, P495 — страны
# происхождения, P364 — языки оригинала (в film_links.csv). Частями: в VALUES — фильмы через пробел.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
SELECT ?film ?prop ?value WHERE {
  VALUES ?film { %VALUES% }
  { ?film wdt:P31 ?value . BIND("P31" AS ?prop) }
  UNION { ?film wdt:P136 ?value . BIND("P136" AS ?prop) }
  UNION { ?film wdt:P57 ?value . BIND("P57" AS ?prop) }
  UNION { ?film wdt:P495 ?value . BIND("P495" AS ?prop) }
  UNION { ?film wdt:P364 ?value . BIND("P364" AS ?prop) }
}
''',
    'genre_items': '''\
# Жанры (P136) и классы (P31) фильмов и их надклассы: метка en и P279 (subclass of, лучший ранг). Обход вширь вверх:
# в VALUES — элементы уровня, новые значения P279 — следующий уровень, пока они есть (не глубже 12).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?item ?kind ?value WHERE {
  VALUES ?item { %VALUES% }
  { ?item rdfs:label ?value . FILTER(LANG(?value) = "en") BIND("en" AS ?kind) }
  UNION { ?item wdt:P279 ?value . BIND("P279" AS ?kind) }
}
''',
    'film_dates': '''\
# Даты публикации фильмов (P577), кроме deprecated: значение, точность (9 — год, 10 — месяц, 11 — день) и ранг.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX psv: <http://www.wikidata.org/prop/statement/value/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?film ?date ?precision ?rank WHERE {
  VALUES ?film { %VALUES% }
  ?film p:P577 ?st .
  ?st wikibase:rank ?rank .
  FILTER(?rank != wikibase:DeprecatedRank)
  ?st psv:P577 ?v .
  ?v wikibase:timeValue ?date ;
     wikibase:timePrecision ?precision .
}
''',
    'film_labels': '''\
# Метки фильмов (rdfs:label) на языках %LANGS%.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?film ?lang ?label WHERE {
  VALUES ?film { %VALUES% }
  ?film rdfs:label ?label .
  FILTER(LANG(?label) IN (%LANGS%))
  BIND(LANG(?label) AS ?lang)
}
'''.replace('%LANGS%', LANGS_IN),
    'film_titles': '''\
# Названия фильмов (P1476 title, лучший ранг): монолингвальный текст и его язык.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
SELECT ?film ?lang ?title WHERE {
  VALUES ?film { %VALUES% }
  ?film wdt:P1476 ?title .
  BIND(LANG(?title) AS ?lang)
}
''',
    'film_durations': '''\
# Длительность фильмов (P2047 duration), кроме deprecated: нормализованное значение (psn:) — в секундах (Q11574).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX psn: <http://www.wikidata.org/prop/statement/value-normalized/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?film ?seconds WHERE {
  VALUES ?film { %VALUES% }
  ?film p:P2047 ?st .
  ?st wikibase:rank ?rank .
  FILTER(?rank != wikibase:DeprecatedRank)
  ?st psn:P2047 ?v .
  ?v wikibase:quantityAmount ?seconds ;
     wikibase:quantityUnit wd:Q11574 .
}
''',
    'film_signals': '''\
# Признаки популярности фильмов: число ссылок на вики-проекты (sitelinks), утверждений P166 (award received) и P1411
# (nominated for), кроме deprecated, ID IMDb (P345, лучший ранг) — по фильму и виду: число и первое по алфавиту.
# sitelinks — на самом item'е, как у WDQS (путь через узел данных schema:about убран: Blazegraph может начать с него
# и не уложиться во время).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX wikibase: <http://wikiba.se/ontology#>
PREFIX schema: <http://schema.org/>
SELECT ?film ?kind (COUNT(?value) AS ?count) (MIN(STR(?value)) AS ?first) WHERE {
  VALUES ?film { %VALUES% }
  { ?film wikibase:sitelinks ?value . BIND("sitelinks" AS ?kind) }
  UNION { ?film p:P166 ?value . ?value wikibase:rank ?rank .
          FILTER(?rank != wikibase:DeprecatedRank) BIND("P166" AS ?kind) }
  UNION { ?film p:P1411 ?value . ?value wikibase:rank ?rank .
          FILTER(?rank != wikibase:DeprecatedRank) BIND("P1411" AS ?kind) }
  UNION { ?film wdt:P345 ?value . BIND("P345" AS ?kind) }
}
GROUP BY ?film ?kind
''',
    'film_cast': '''\
# Состав фильмов: роли (P161 cast member) и озвучивание (P725 voice actor), кроме deprecated, с квалификаторами
# P1545 (порядок), P453 (персонаж — item) и P4633 (имя персонажа — строка), каждый — OPTIONAL. Строка ответа — решение
# SPARQL: у утверждения с несколькими значениями — перекрёсток P453 × P4633 (× P1545), без них — одна строка с пустыми.
# Ранг, фильтр и квалификаторы — внутри каждой ветки (см. «Запрос по VALUES с UNION» в начале файла).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX ps: <http://www.wikidata.org/prop/statement/>
PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?film ?prop ?person ?ordinal ?character ?name WHERE {
  VALUES ?film { %VALUES% }
  { ?film p:P161 ?st . ?st ps:P161 ?person ; wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank) BIND("P161" AS ?prop)
    OPTIONAL { ?st pq:P1545 ?ordinal }
    OPTIONAL { ?st pq:P453 ?character }
    OPTIONAL { ?st pq:P4633 ?name } }
  UNION
  { ?film p:P725 ?st . ?st ps:P725 ?person ; wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank) BIND("P725" AS ?prop)
    OPTIONAL { ?st pq:P1545 ?ordinal }
    OPTIONAL { ?st pq:P453 ?character }
    OPTIONAL { ?st pq:P4633 ?name } }
}
''',
    'film_dates_extra': '''\
# Запасные даты фильмов, кроме deprecated: P1191 (date of first performance — первый показ), P571 (inception —
# создание), P2754 (production date — дата производства); значение, точность и ранг — как в film_dates. Ранг, фильтр
# и BIND — внутри каждой ветки.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX psv: <http://www.wikidata.org/prop/statement/value/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?film ?prop ?date ?precision ?rank WHERE {
  VALUES ?film { %VALUES% }
  { ?film p:P1191 ?st . ?st wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank)
    ?st psv:P1191 ?v . ?v wikibase:timeValue ?date ; wikibase:timePrecision ?precision .
    BIND("P1191" AS ?prop) }
  UNION
  { ?film p:P571 ?st . ?st wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank)
    ?st psv:P571 ?v . ?v wikibase:timeValue ?date ; wikibase:timePrecision ?precision .
    BIND("P571" AS ?prop) }
  UNION
  { ?film p:P2754 ?st . ?st wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank)
    ?st psv:P2754 ?v . ?v wikibase:timeValue ?date ; wikibase:timePrecision ?precision .
    BIND("P2754" AS ?prop) }
}
''',
    'film_releases': '''\
# Выход фильмов по местам: даты публикации (P577), кроме deprecated, с квалификатором P291 (place of publication) —
# значение, точность и место; по строке на решение SPARQL, то есть на (утверждение, P291). Утверждение без P291 сюда
# не попадает. Ранг — только для отбора.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX psv: <http://www.wikidata.org/prop/statement/value/>
PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?film ?date ?precision ?place ?rank WHERE {
  VALUES ?film { %VALUES% }
  ?film p:P577 ?st .
  ?st wikibase:rank ?rank .
  FILTER(?rank != wikibase:DeprecatedRank)
  ?st pq:P291 ?place .
  ?st psv:P577 ?v .
  ?v wikibase:timeValue ?date ;
     wikibase:timePrecision ?precision .
}
''',
    'film_labels_all': '''\
# Все метки (rdfs:label, на любом языке) фильмов, у которых нет ни метки на языках film_labels, ни названия P1476
# (film_titles): в VALUES — только такие фильмы.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?film ?lang ?label WHERE {
  VALUES ?film { %VALUES% }
  ?film rdfs:label ?label .
  BIND(LANG(?label) AS ?lang)
}
''',
    'film_crew': '''\
# Съёмочная группа фильмов (лучший ранг): P58 — сценарист, P86 — композитор, P344 — оператор, P162 — продюсер,
# P1040 — монтажёр.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
SELECT ?film ?prop ?person WHERE {
  VALUES ?film { %VALUES% }
  { ?film wdt:P58 ?person . BIND("P58" AS ?prop) }
  UNION { ?film wdt:P86 ?person . BIND("P86" AS ?prop) }
  UNION { ?film wdt:P344 ?person . BIND("P344" AS ?prop) }
  UNION { ?film wdt:P162 ?person . BIND("P162" AS ?prop) }
  UNION { ?film wdt:P1040 ?person . BIND("P1040" AS ?prop) }
}
''',
    'film_links2': '''\
# Ещё связи фильмов (лучший ранг): P272 — студия, P750 — прокатчик, P915 — место съёмок, P840 — место действия
# (narrative location), P155 — предыдущий фильм (follows), P156 — следующий (followed by).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
SELECT ?film ?prop ?value WHERE {
  VALUES ?film { %VALUES% }
  { ?film wdt:P272 ?value . BIND("P272" AS ?prop) }
  UNION { ?film wdt:P750 ?value . BIND("P750" AS ?prop) }
  UNION { ?film wdt:P915 ?value . BIND("P915" AS ?prop) }
  UNION { ?film wdt:P840 ?value . BIND("P840" AS ?prop) }
  UNION { ?film wdt:P155 ?value . BIND("P155" AS ?prop) }
  UNION { ?film wdt:P156 ?value . BIND("P156" AS ?prop) }
}
''',
    'film_series': '''\
# Серии фильмов (P179 part of the series), кроме deprecated, с порядком в серии (квалификатор P1545, OPTIONAL): по
# строке на решение SPARQL — у утверждения с несколькими P1545 по строке на каждый, без него — одна с пустым.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX ps: <http://www.wikidata.org/prop/statement/>
PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?film ?series ?ordinal WHERE {
  VALUES ?film { %VALUES% }
  ?film p:P179 ?st .
  ?st ps:P179 ?series ;
      wikibase:rank ?rank .
  FILTER(?rank != wikibase:DeprecatedRank)
  OPTIONAL { ?st pq:P1545 ?ordinal }
}
''',
    'film_money': '''\
# Бюджет (P2130) и сборы (P2142) фильмов, кроме deprecated: сумма (psv: wikibase:quantityAmount — как в утверждении,
# без пересчёта), единица (wikibase:quantityUnit — item валюты) и ранг. Ранг, фильтр и BIND — внутри каждой ветки.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX psv: <http://www.wikidata.org/prop/statement/value/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?film ?prop ?amount ?unit ?rank WHERE {
  VALUES ?film { %VALUES% }
  { ?film p:P2130 ?st . ?st wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank)
    ?st psv:P2130 ?v . ?v wikibase:quantityAmount ?amount ; wikibase:quantityUnit ?unit .
    BIND("P2130" AS ?prop) }
  UNION
  { ?film p:P2142 ?st . ?st wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank)
    ?st psv:P2142 ?v . ?v wikibase:quantityAmount ?amount ; wikibase:quantityUnit ?unit .
    BIND("P2142" AS ?prop) }
}
''',
    'film_awards': '''\
# Награды (P166 award received) и номинации (P1411 nominated for) фильмов, кроме deprecated, с датой (квалификатор
# P585 point in time — значение и точность, OPTIONAL): по строке на решение SPARQL — у утверждения с несколькими P585
# по строке на каждый, без него — одна с пустыми. Ранг, фильтр, BIND и OPTIONAL — внутри каждой ветки.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX ps: <http://www.wikidata.org/prop/statement/>
PREFIX pqv: <http://www.wikidata.org/prop/qualifier/value/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?film ?prop ?award ?date ?precision WHERE {
  VALUES ?film { %VALUES% }
  { ?film p:P166 ?st . ?st ps:P166 ?award ; wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank) BIND("P166" AS ?prop)
    OPTIONAL { ?st pqv:P585 ?t . ?t wikibase:timeValue ?date ; wikibase:timePrecision ?precision } }
  UNION
  { ?film p:P1411 ?st . ?st ps:P1411 ?award ; wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank) BIND("P1411" AS ?prop)
    OPTIONAL { ?st pqv:P585 ?t . ?t wikibase:timeValue ?date ; wikibase:timePrecision ?precision } }
}
''',
    'people': '''\
# Люди — режиссёры (P57), состав (P161, P725) и съёмочная группа (P58, P86, P344, P162, P1040) оставшихся фильмов:
# человек ли (P31 = Q5), пол (P21, лучший ранг), число ссылок на вики-проекты (на самом item'е, как в film_signals).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX wikibase: <http://wikiba.se/ontology#>
PREFIX schema: <http://schema.org/>
SELECT ?person ?kind ?value WHERE {
  VALUES ?person { %VALUES% }
  { ?person wikibase:sitelinks ?value . BIND("sitelinks" AS ?kind) }
  UNION { ?person wdt:P31 ?value . FILTER(?value = wd:Q5) BIND("P31" AS ?kind) }
  UNION { ?person wdt:P21 ?value . BIND("P21" AS ?kind) }
}
''',
    'people_labels': '''\
# Метки людей (rdfs:label) на языках %LANGS%.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?person ?lang ?label WHERE {
  VALUES ?person { %VALUES% }
  ?person rdfs:label ?label .
  FILTER(LANG(?label) IN (%LANGS%))
  BIND(LANG(?label) AS ?lang)
}
'''.replace('%LANGS%', LANGS_IN),
    'people_names': '''\
# Имена (P735 given name) и фамилии (P734 family name) людей, кроме deprecated, с порядком (квалификатор P1545).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX ps: <http://www.wikidata.org/prop/statement/>
PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?person ?kind ?item ?ordinal WHERE {
  VALUES ?person { %VALUES% }
  { ?person p:P735 ?st . ?st ps:P735 ?item ; wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank) BIND("given" AS ?kind)
    OPTIONAL { ?st pq:P1545 ?ordinal } }
  UNION
  { ?person p:P734 ?st . ?st ps:P734 ?item ; wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank) BIND("family" AS ?kind)
    OPTIONAL { ?st pq:P1545 ?ordinal } }
}
''',
    'people_dates': '''\
# Даты рождения (P569) и смерти (P570) людей, кроме deprecated: значение, точность и ранг — как в film_dates. Ранг,
# фильтр и BIND — внутри каждой ветки.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX psv: <http://www.wikidata.org/prop/statement/value/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?person ?prop ?date ?precision ?rank WHERE {
  VALUES ?person { %VALUES% }
  { ?person p:P569 ?st . ?st wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank)
    ?st psv:P569 ?v . ?v wikibase:timeValue ?date ; wikibase:timePrecision ?precision .
    BIND("P569" AS ?prop) }
  UNION
  { ?person p:P570 ?st . ?st wikibase:rank ?rank .
    FILTER(?rank != wikibase:DeprecatedRank)
    ?st psv:P570 ?v . ?v wikibase:timeValue ?date ; wikibase:timePrecision ?precision .
    BIND("P570" AS ?prop) }
}
''',
    'people_links': '''\
# Место рождения (P19) и гражданство (P27) людей, лучший ранг.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
SELECT ?person ?prop ?value WHERE {
  VALUES ?person { %VALUES% }
  { ?person wdt:P19 ?value . BIND("P19" AS ?prop) }
  UNION { ?person wdt:P27 ?value . BIND("P27" AS ?prop) }
}
''',
    'name_items': '''\
# Элементы имён и фамилий (значения P735 и P734 людей): метки en и mul, P31 (по нему — вид имени).
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?item ?kind ?value WHERE {
  VALUES ?item { %VALUES% }
  { ?item rdfs:label ?value . FILTER(LANG(?value) IN ("en", "mul")) BIND(LANG(?value) AS ?kind) }
  UNION { ?item wdt:P31 ?value . BIND("P31" AS ?kind) }
}
''',
    'characters': '''\
# Персонажи (значения квалификатора P453 в составе): метки en и mul.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?item ?kind ?value WHERE {
  VALUES ?item { %VALUES% }
  ?item rdfs:label ?value .
  FILTER(LANG(?value) IN ("en", "mul"))
  BIND(LANG(?value) AS ?kind)
}
''',
    'places': '''\
# Места (значения P19 людей, P915 и P840 фильмов, штаб-квартиры P159 студий и прокатчиков): GeoNames ID (P1566),
# страна (P17) — лучший ранг; метки en, mul и ru; координаты (P625), кроме deprecated, — широта, долгота, глобус и ранг
# (psv: → wikibase:geoLatitude, wikibase:geoLongitude, wikibase:geoGlobe; глобус отбирает загрузчик). Ранг, фильтр
# и BIND — внутри ветки P625; ?lat ?lon ?globe ?rank есть только в её строках, ?value — только в строках остальных.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX psv: <http://www.wikidata.org/prop/statement/value/>
PREFIX wikibase: <http://wikiba.se/ontology#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?item ?kind ?value ?lat ?lon ?globe ?rank WHERE {
  VALUES ?item { %VALUES% }
  { ?item wdt:P1566 ?value . BIND("P1566" AS ?kind) }
  UNION { ?item wdt:P17 ?value . BIND("P17" AS ?kind) }
  UNION { ?item rdfs:label ?value . FILTER(LANG(?value) IN ("en", "mul", "ru")) BIND(LANG(?value) AS ?kind) }
  UNION { ?item p:P625 ?st . ?st wikibase:rank ?rank .
          FILTER(?rank != wikibase:DeprecatedRank)
          ?st psv:P625 ?v . ?v wikibase:geoLatitude ?lat ; wikibase:geoLongitude ?lon ; wikibase:geoGlobe ?globe .
          BIND("P625" AS ?kind) }
}
''',
    'item_labels': '''\
# Метки студий и прокатчиков (значения P272, P750), серий (P179) и наград (P166, P1411) на языках %LANGS%.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?item ?lang ?label WHERE {
  VALUES ?item { %VALUES% }
  ?item rdfs:label ?label .
  FILTER(LANG(?label) IN (%LANGS%))
  BIND(LANG(?label) AS ?lang)
}
'''.replace('%LANGS%', ITEM_LANGS_IN),
    'companies': '''\
# Студии и прокатчики (значения P272, P750): страна (P17 country) и штаб-квартира (P159 headquarters location),
# лучший ранг.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
SELECT ?item ?kind ?value WHERE {
  VALUES ?item { %VALUES% }
  { ?item wdt:P17 ?value . BIND("P17" AS ?kind) }
  UNION { ?item wdt:P159 ?value . BIND("P159" AS ?kind) }
}
''',
    'countries': '''\
# Страны — значения P495 фильмов, места публикации (P291 в film_releases), гражданство (P27) людей, страны (P17) мест
# и студий: коды ISO 3166-1 alpha-2 (P297) и alpha-3 (P298), лучший ранг.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
SELECT ?item ?kind ?value WHERE {
  VALUES ?item { %VALUES% }
  { ?item wdt:P297 ?value . BIND("P297" AS ?kind) }
  UNION { ?item wdt:P298 ?value . BIND("P298" AS ?kind) }
}
''',
    'languages': '''\
# Языки оригинала фильмов (значения P364): коды ISO 639-1 (P218) и ISO 639-3 (P220), лучший ранг, и метка en.
PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?item ?kind ?value WHERE {
  VALUES ?item { %VALUES% }
  { ?item wdt:P218 ?value . BIND("P218" AS ?kind) }
  UNION { ?item wdt:P220 ?value . BIND("P220" AS ?kind) }
  UNION { ?item rdfs:label ?value . FILTER(LANG(?value) = "en") BIND("en" AS ?kind) }
}
''',
}


class FetchError(Exception):
    """Снимок не собрать: в тексте — запрос (или файл) и причина. status — код HTTP, если причина в нём."""

    def __init__(self, text, status=None):
        super().__init__(text)
        self.status = status


class Retry(Exception):
    """Ответ пришёл, но не годится (оборван, не JSON, битый gzip) — повторить или поделить запрос."""


class Heavy(Exception):
    """Сервис не справился с запросом (тайм-аут, 5xx, негодный ответ) столько раз, сколько разрешено, — делить."""


class Stopped(Exception):
    """Другая часть шага не скачалась — эта прекращает работу."""


LOG_LOCK = threading.Lock()


def log(text):
    with LOG_LOCK:
        sys.stdout.write(text + '\n')
        sys.stdout.flush()


def num(n):
    """12345 → «12 345»."""
    return f'{n:,}'.replace(',', ' ')


def took(seconds):
    """Длительность для строк хода: «45 с», «3 мин 5 с», «1 ч 2 мин»."""
    s = int(seconds)
    if s < 60:
        return f'{s} с'
    if s < 3600:
        return f'{s // 60} мин {s % 60} с'
    return f'{s // 3600} ч {s % 3600 // 60} мин'


def content_length(headers):
    v = (headers.get('Content-Length') or '').strip()
    return int(v) if v.isascii() and v.isdigit() else None


def retry_after(headers):
    """Пауза из Retry-After (секунды или дата HTTP), не больше RETRY_AFTER_MAX; нет или не разобрать — None."""
    v = ((headers.get('Retry-After') if headers is not None else None) or '').strip()
    if not v:
        return None
    if v.isascii() and v.isdigit():
        return min(int(v), RETRY_AFTER_MAX)
    try:
        when = email.utils.parsedate_to_datetime(v)
    except (TypeError, ValueError, IndexError):
        return None
    if when.tzinfo is None:
        when = when.replace(tzinfo=datetime.timezone.utc)
    s = (when - datetime.datetime.now(datetime.timezone.utc)).total_seconds()
    return max(0, min(int(s) + 1, RETRY_AFTER_MAX))


def http_snippet(err):
    """Начало тела ответа-ошибки — в сообщение (одной строкой, до 200 знаков)."""
    try:
        raw = err.read(400) or b''
    except Exception:  # тело ошибки — только для сообщения, его сбой не важен
        raw = b''
    finally:
        err.close()
    text = ' '.join(raw.decode('utf-8', 'replace').split())[:200]
    return f' — «{text}»' if text else ''


class Http:
    """POST с повторами, общий для потоков. Пауза по просьбе сервиса (Retry-After) — одна на все потоки; stop —
    прекратить (другая часть шага не скачалась). sent — отправлено запросов, answered — из них с годным ответом."""

    def __init__(self, backoff):
        self.backoff = backoff
        self.opener = urllib.request.build_opener()  # прокси — из среды (HTTPS_PROXY, NO_PROXY)
        self.opener.addheaders = []
        self.lock = threading.Lock()
        self.hold_until = 0.0
        self.stop = threading.Event()
        self.sent = 0
        self.answered = 0

    def delay(self, seconds, exact=False):
        """Пауза с учётом MOV_FETCH_BACKOFF: обычная — умножается, из Retry-After (exact) — как есть; 0 — без пауз."""
        if self.backoff <= 0:
            return 0
        return seconds if exact else seconds * self.backoff

    def sleep(self, seconds):
        if seconds > 0 and self.stop.wait(seconds):
            raise Stopped()

    def pause(self, seconds):
        self.sleep(self.delay(seconds))

    def hold(self, seconds):
        """Все потоки ждут seconds с от этой минуты (просьба сервиса подождать)."""
        with self.lock:
            self.hold_until = max(self.hold_until, time.monotonic() + seconds)

    def wait_hold(self):
        while True:
            with self.lock:
                left = self.hold_until - time.monotonic()
            if left <= 0:
                return
            self.sleep(left)

    def post(self, url, data, label, check, heavy=None):
        """POST data с повторами; check(тело) — проверка ответа (Retry — ответ негодный), её итог — итог запроса.
        heavy — после стольких неудач вида «сервис не справился» (тайм-аут, 5xx, негодный ответ) — Heavy, а не новые
        попытки; None — такие неудачи — обычные попытки."""
        where = f'{url} ({label})'
        failed = heavy_seen = busy = 0
        while True:
            if self.stop.is_set():
                raise Stopped()
            self.wait_hold()
            with self.lock:
                self.sent += 1
            wait = None
            try:
                value = check(self._once(url, data))
                with self.lock:
                    self.answered += 1
                return value
            except Retry as e:
                kind, reason = 'heavy', str(e)
            except urllib.error.HTTPError as e:
                code = e.code
                wait = retry_after(e.headers)
                reason = f'HTTP {code}{http_snippet(e)}'
                if code == 429 or (code == 503 and wait is not None):
                    kind = 'busy'
                elif code == 408 or 500 <= code <= 599:
                    kind = 'heavy'
                elif code == 425:
                    kind = 'net'
                else:
                    raise FetchError(f'{where}: {reason} — повтор не поможет', status=code) from None
            except urllib.error.URLError as e:
                if isinstance(e.reason, TimeoutError):
                    kind, reason = 'heavy', f'нет ответа за {TIMEOUT:g} с'
                else:
                    kind, reason = 'net', f'сеть: {e.reason}'
            except TimeoutError:
                kind, reason = 'heavy', f'нет ответа за {TIMEOUT:g} с'
            except (http.client.HTTPException, OSError) as e:
                kind, reason = 'net', f'сеть: {type(e).__name__}: {e}'
            if kind == 'busy':
                busy += 1
                if busy > THROTTLE_MAX:
                    raise FetchError(f'не удалось скачать {where}: сервис просит подождать {THROTTLE_MAX} раз подряд '
                                     f'— {reason}')
                s = max(self.delay(BACKOFF[min(busy, len(BACKOFF)) - 1]),
                        self.delay(wait, exact=True) if wait is not None else 0)
                log(f'  сервис просит подождать — {where}: {reason}; пауза {s:g} с для всех запросов')
                self.hold(s)
                continue
            if kind == 'heavy':
                heavy_seen += 1
                if heavy is not None and heavy_seen >= heavy:
                    raise Heavy(reason)
            failed += 1
            if failed >= ATTEMPTS:
                raise FetchError(f'не удалось скачать {where}: {reason} (попыток: {ATTEMPTS})')
            s = self.delay(BACKOFF[failed - 1])
            log(f'  попытка {failed}/{ATTEMPTS} не удалась — {where}: {reason}; повтор через {s:g} с')
            self.sleep(s)

    def _once(self, url, data):
        req = urllib.request.Request(url, data=data, method='POST')
        req.add_header('User-Agent', USER_AGENT)
        req.add_header('Accept', 'application/sparql-results+json')
        req.add_header('Accept-Encoding', 'gzip')
        req.add_header('Content-Type', 'application/x-www-form-urlencoded')
        with self.opener.open(req, timeout=TIMEOUT) as resp:
            length = content_length(resp.headers)
            encoding = (resp.headers.get('Content-Encoding') or '').strip().lower()
            parts = []
            got = 0
            try:
                while True:
                    chunk = resp.read(READ_CHUNK)
                    if not chunk:
                        break
                    parts.append(chunk)
                    got += len(chunk)
            except TimeoutError:
                raise Retry(f'ответ оборван на {num(got)} байт: нет данных {TIMEOUT:g} с') from None
            except (http.client.HTTPException, OSError) as e:
                # обрыв посреди ответа — так у сервиса кончается время на запрос: делить, а не повторять тот же
                raise Retry(f'ответ оборван на {num(got)} байт: {type(e).__name__}: {e}') from None
        if length is not None and got != length:
            raise Retry(f'ответ оборван: {num(got)} байт из {num(length)} (Content-Length)')
        body = b''.join(parts)
        if encoding in ('gzip', 'x-gzip'):
            try:
                body = zlib.decompress(body, 16 + zlib.MAX_WBITS)
            except zlib.error as e:
                raise Retry(f'битый gzip ({e})') from None
        return body


# ---- ответы SPARQL ----

def check_sparql(body):
    """Ответ SPARQL JSON: results.bindings — список объектов; иначе (в том числе оборванный JSON при HTTP 200) —
    Retry."""
    try:
        obj = json.loads(body.decode('utf-8-sig'))
    except (UnicodeDecodeError, ValueError) as e:
        raise Retry(f'не JSON ({type(e).__name__}: {str(e)[:100]})') from None
    try:
        bindings = obj['results']['bindings']
    except (KeyError, TypeError):
        raise Retry('ответ SPARQL без results.bindings') from None
    if not isinstance(bindings, list) or not all(isinstance(b, dict) for b in bindings):
        raise Retry('results.bindings — не список объектов')
    return bindings


def value_of(b, name, required=True):
    v = b.get(name)
    if not isinstance(v, dict) or 'value' not in v:
        if required:
            raise FetchError(f'Wikidata: в строке ответа нет ?{name}: {json.dumps(b, ensure_ascii=False)[:200]}')
        return ''
    return str(v['value'])


def qid(uri):
    """http://www.wikidata.org/entity/Q42 → Q42; не item (неизвестное значение, лексема, свойство) — None."""
    for prefix in ENTITY_PREFIXES:
        if uri.startswith(prefix):
            q = uri[len(prefix):]
            return q if QID.fullmatch(q) else None
    return None


def qnum(q):
    return int(q[1:])


def own_qid(b, name, part, stage):
    """Item из VALUES запроса: ?name — Q-id, и он был в части; иначе ответ не по запросу — FetchError."""
    uri = value_of(b, name)
    q = qid(uri)
    if q is None or q not in part:
        raise FetchError(f'Wikidata {stage}: ?{name} = «{uri}» — этого item\'а в запросе не было')
    return q


def integer(text, what):
    if not re.fullmatch(r'[+-]?[0-9]+', text):
        raise FetchError(f'Wikidata {what}: «{text}» — не целое число')
    return int(text)


def decimal_text(text, stage):
    """Число из wikibase:quantityAmount — точно, без знака «+», показателя и лишних нулей («+5400.0» → «5400»,
    «90.50» → «90.5», «1.2E+7» → «12000000»)."""
    try:
        d = decimal.Decimal(text.strip())
    except decimal.InvalidOperation:
        raise FetchError(f'Wikidata {stage}: «{text}» — не число') from None
    if not d.is_finite():
        raise FetchError(f'Wikidata {stage}: «{text}» — не число')
    exact = decimal.Context(prec=max(decimal.getcontext().prec, len(d.as_tuple().digits)))
    s = format(d.normalize(exact), 'f')
    return '0' if s in ('-0', '0') else s


def seconds_text(text):
    """Число секунд из wikibase:quantityAmount (как decimal_text)."""
    return decimal_text(text, 'film_durations')


def name_kind(classes):
    """Вид элемента имени по его P31: male | female | unisex (оба или Q3409032) | family | other."""
    male, female = NAME_MALE in classes, NAME_FEMALE in classes
    if NAME_UNISEX in classes or (male and female):
        return 'unisex'
    if male:
        return 'male'
    if female:
        return 'female'
    if NAME_FAMILY in classes:
        return 'family'
    return 'other'


# ---- CSV, gzip ----

def csv_bytes(header, rows):
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator='\n', quoting=csv.QUOTE_MINIMAL)
    w.writerow(header)
    w.writerows(rows)
    return buf.getvalue().encode('utf-8', 'replace')


def gzip_bytes(data):
    """gzip (RFC 1952) без имени файла, время 0, ОС «неизвестна», deflate 9: те же байты — тот же .gz на любом
    Python."""
    co = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
    body = co.compress(data) + co.flush()
    return (b'\x1f\x8b\x08\x00' + struct.pack('<I', 0) + b'\x02\xff' + body
            + struct.pack('<II', zlib.crc32(data) & 0xffffffff, len(data) & 0xffffffff))


def sort_key(kinds):
    """Ключ сортировки строк по столбцам слева направо: q — Q-id по номеру (пусто — раньше всех), n — целое,
    d — десятичное, s — строка."""
    def q(v):
        return int(v[1:]) if v else -1

    def n(v):
        return int(v) if v != '' else -1

    def d(v):
        return decimal.Decimal(v) if v != '' else decimal.Decimal(-1)

    conv = [{'q': q, 'n': n, 'd': d, 's': str}[k] for k in kinds]
    return lambda row: tuple(f(v) for f, v in zip(conv, row))


# ---- снимок ----

class Config:
    """Настройки прогона — из переменных среды (см. описание модуля)."""

    def __init__(self, env):
        self.sparql = env.get('MOV_WIKIDATA_SPARQL') or DEFAULT_SPARQL
        raw = (env.get('MOV_FETCH_BACKOFF') or '1').strip()
        try:
            self.backoff = float(raw)
        except ValueError:
            raise FetchError(f'MOV_FETCH_BACKOFF=«{raw}» — нужно число (1 — обычные паузы, 0 — без пауз)') from None
        if not self.backoff >= 0:
            raise FetchError(f'MOV_FETCH_BACKOFF=«{raw}» — нужно число не меньше 0')
        self.minimums = (env.get('MOV_FETCH_MIN') or '1').strip() != '0'
        self.workers = env_int(env, 'MOV_FETCH_WORKERS', WORKERS, 1, WORKERS_MAX)
        self.chunk = env_int(env, 'MOV_FETCH_CHUNK', CHUNK, 1, None)
        at = (env.get('MOV_FETCHED_AT') or '').strip()
        if at:
            try:
                ok = FETCHED_AT_RE.fullmatch(at) and datetime.datetime.strptime(at, '%Y-%m-%dT%H:%M:%SZ')
            except ValueError:
                ok = False
            if not ok:
                raise FetchError(f'MOV_FETCHED_AT=«{at}» — нужно время UTC вида 2026-11-23T10:00:00Z')
            self.fetched_at = at
        else:
            self.fetched_at = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def env_int(env, key, default, low, high):
    raw = (env.get(key) or '').strip()
    if not raw:
        return default
    if not (raw.isascii() and raw.isdigit()) or int(raw) < low or (high is not None and int(raw) > high):
        bounds = f'от {low} до {high}' if high is not None else f'не меньше {low}'
        raise FetchError(f'{key}=«{raw}» — нужно целое число {bounds}')
    return int(raw)


class Snapshot:
    """Дерево архива и учёт его файлов: путь → url, bytes, sha256, rows (для fetch.json и SOURCE.md)."""

    def __init__(self, name, year, cfg, tree, http_client):
        self.name = name
        self.year = year
        self.cfg = cfg
        self.tree = tree
        self.http = http_client
        self.fetched_at = cfg.fetched_at
        self.files = {}
        self.info = {}

    def path(self, rel):
        p = self.tree.joinpath(*rel.split('/'))
        p.parent.mkdir(parents=True, exist_ok=True)
        return p

    def put(self, rel, data, url, rows=None):
        self.path(rel).write_bytes(data)
        self.files[rel] = {'url': url, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'rows': rows}

    def table(self, rel, header, rows, kinds):
        """CSV (.csv.gz — в gzip) из строк, отсортированных по столбцам слева направо (kinds — см. sort_key)."""
        rows = sorted(rows, key=sort_key(kinds))
        data = csv_bytes(header, rows)
        if rel.endswith('.gz'):
            data = gzip_bytes(data)
        self.put(rel, data, self.cfg.sparql, len(rows))
        f = self.files[rel]
        log(f'Wikidata: {rel} — {num(f["bytes"])} байт, строк {num(f["rows"])}')

    def at_least(self, what, have, need):
        if self.cfg.minimums and have < need:
            raise FetchError(f'{what}: {num(have)} — меньше нижней границы {num(need)}; сервис отдал не всё? '
                             f'(MOV_FETCH_MIN=0 — без этой проверки)')


class Wikidata:
    """Запросы к SPARQL-эндпойнту и их учёт: части, деления, значения не-item по шагам."""

    def __init__(self, snap):
        self.snap = snap
        self.cfg = snap.cfg
        self.http = snap.http
        self.lock = threading.Lock()
        self.parts = 0
        self.splits = 0
        self.not_items = {}

    def query(self, text, label, heavy=None):
        self.http.pause(PAUSE)
        data = urllib.parse.urlencode({'query': text}).encode('ascii')
        return self.http.post(self.cfg.sparql, data, f'Wikidata {label}', check_sparql, heavy)

    def not_item(self, stage, n=1):
        with self.lock:
            self.not_items[stage] = self.not_items.get(stage, 0) + n


def run_parallel(http_client, tasks, workers):
    """Задачи в workers потоках; итоги — в порядке задач. Первая ошибка останавливает остальные и выходит наружу."""
    results = [None] * len(tasks)
    error = None
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers, thread_name_prefix='mov-fetch') as pool:
        futures = {pool.submit(task): i for i, task in enumerate(tasks)}
        for f in concurrent.futures.as_completed(futures):
            if f.cancelled():
                continue
            try:
                results[futures[f]] = f.result()
            except BaseException as e:
                if error is None and not isinstance(e, Stopped):
                    error = e
                    http_client.stop.set()
                    for g in futures:
                        g.cancel()
    if error is not None:
        raise error
    if http_client.stop.is_set():
        raise FetchError('шаг остановлен, а причины нет — так не бывает')
    return results


def in_parts(wd, stage, items, parse, what, quiet=False):
    """Запрос stage частями VALUES по items (Q-id по возрастанию номера) в cfg.workers потоках; parse(wd, bindings,
    часть) → строки. Часть, на которую сервис не ответил, делится пополам, пока половины не меньше MIN_CHUNK. Итог —
    строки всех частей (в порядке частей) после проверки, что ответила каждая часть."""
    template = QUERIES[stage]
    chunk = wd.cfg.chunk
    parts = [items[i:i + chunk] for i in range(0, len(items), chunk)]
    t0 = time.monotonic()
    sent0 = wd.http.sent
    if not quiet:
        log(f'Wikidata: {stage} — {what} {num(len(items))}, частей {num(len(parts))} по {num(chunk)}, '
            f'потоков {wd.cfg.workers}')
    lock = threading.Lock()
    state = {'done': 0, 'rows': 0, 'splits': 0}

    def ask(part, rows, leaves):
        text = template.replace(VALUES, ' '.join(f'wd:{q}' for q in part))
        label = f'{stage}, {num(len(part))} шт.: {part[0]} … {part[-1]}'
        split = len(part) >= 2 * MIN_CHUNK
        try:
            bindings = wd.query(text, label, heavy=1 if split else None)
        except Heavy as e:
            with lock:
                state['splits'] += 1
            log(f'  Wikidata {stage}: часть из {num(len(part))} ({part[0]} … {part[-1]}) не прошла — {e}; '
                f'делится пополам')
            half = len(part) // 2
            ask(part[:half], rows, leaves)
            ask(part[half:], rows, leaves)
            return
        rows.extend(parse(wd, bindings, frozenset(part)))
        leaves.append(part)

    def task(part):
        rows, leaves = [], []
        ask(part, rows, leaves)
        with lock:
            state['done'] += 1
            state['rows'] += len(rows)
            done, total_rows = state['done'], state['rows']
        total = len(parts)
        if not quiet and total >= PROGRESS_PARTS and done < total and done * 10 // total > (done - 1) * 10 // total:
            log(f'Wikidata: {stage} — частей {num(done)}/{num(total)}, строк {num(total_rows)}, '
                f'{took(time.monotonic() - t0)}')
        return rows, leaves

    results = run_parallel(wd.http, [lambda p=p: task(p) for p in parts], wd.cfg.workers)
    rows, answered = [], []
    for part_rows, leaves in results:
        rows.extend(part_rows)
        for leaf in leaves:
            answered.extend(leaf)
    if answered != list(items):
        raise FetchError(f'Wikidata {stage}: ответили не все части — элементов с ответом {num(len(answered))} '
                         f'из {num(len(items))}')
    with wd.lock:
        wd.parts += len(parts)
        wd.splits += state['splits']
    log(f'Wikidata: {stage} — {what} {num(len(items))}: готово за {took(time.monotonic() - t0)}, строк '
        f'{num(len(rows))}, запросов {num(wd.http.sent - sent0)}'
        + (f', частей делилось {num(state["splits"])}' if state['splits'] else ''))
    return rows


def kinds_parser(stage, var, kinds):
    """Разбор ответов вида ?var ?kind ?value: строки (item, kind, value как есть)."""
    def parse(wd, bindings, part):
        out = []
        for b in bindings:
            x = own_qid(b, var, part, stage)
            kind = value_of(b, 'kind')
            if kind not in kinds:
                raise FetchError(f'Wikidata {stage}: ?kind = «{kind}» — ждали одно из {", ".join(kinds)}')
            out.append((x, kind, value_of(b, 'value')))
        return out
    return parse


def labels_parser(stage, var, langs=LABEL_LANGS):
    """Разбор меток ?var ?lang ?label: строки (item, язык, метка) — на языках langs (None — на любом)."""
    def parse(wd, bindings, part):
        out = []
        for b in bindings:
            x = own_qid(b, var, part, stage)
            lang = value_of(b, 'lang', required=False) or str(b.get('label', {}).get('xml:lang', ''))
            if (lang in langs) if langs is not None else lang:
                out.append((x, lang, value_of(b, 'label')))
        return out
    return parse


def prop_of(b, stage, props):
    """?prop строки ответа — одно из свойств запроса; иначе ответ не по запросу — FetchError."""
    prop = value_of(b, 'prop')
    if prop not in props:
        raise FetchError(f'Wikidata {stage}: ?prop = «{prop}» — такого свойства в запросе нет')
    return prop


def rank_of(b, stage):
    rank = RANKS.get(value_of(b, 'rank'))
    if rank is None:
        raise FetchError(f'Wikidata {stage}: ?rank = «{value_of(b, "rank")}» — неизвестный ранг')
    return rank


def dated(b, stage):
    """Дата утверждения: (значение как отдал сервис, точность, ранг); deprecated (если сервис его всё же отдал) —
    None."""
    rank = rank_of(b, stage)
    if rank == 'deprecated':
        return None
    precision = integer(value_of(b, 'precision'), f'{stage}: ?precision')
    return value_of(b, 'date'), str(precision), rank


def links_parser(stage, var, value, props):
    """Разбор ответов вида ?var ?prop ?value, где значение — item: строки (item, свойство, Q-id значения). Значение
    не item (неизвестное значение и т. п.) не пишется — считается в not_items шага."""
    def parse(wd, bindings, part):
        out = []
        for b in bindings:
            x = own_qid(b, var, part, stage)
            prop = prop_of(b, stage, props)
            v = qid(value_of(b, value))
            if v is None:
                wd.not_item(stage)
                continue
            out.append((x, prop, v))
        return out
    return parse


parse_links = links_parser('film_links', 'film', 'value', ('P31',) + LINK_PROPS)


def dates_parser(stage, var, props):
    """Разбор дат вида ?var ?prop ?date ?precision ?rank: строки (item, свойство, значение, точность, ранг)."""
    def parse(wd, bindings, part):
        out = []
        for b in bindings:
            x = own_qid(b, var, part, stage)
            prop = prop_of(b, stage, props)
            d = dated(b, stage)
            if d is not None:
                out.append((x, prop) + d)
        return out
    return parse


def parse_dates(wd, bindings, part):
    out = []
    for b in bindings:
        film = own_qid(b, 'film', part, 'film_dates')
        d = dated(b, 'film_dates')
        if d is not None:
            out.append((film,) + d)
    return out


def parse_releases(wd, bindings, part):
    """film_releases: строки (фильм, дата, точность, место P291)."""
    out = []
    for b in bindings:
        film = own_qid(b, 'film', part, 'film_releases')
        d = dated(b, 'film_releases')
        if d is None:
            continue
        place = qid(value_of(b, 'place'))
        if place is None:
            wd.not_item('film_releases')
            continue
        out.append((film, d[0], d[1], place))
    return out


def parse_series(wd, bindings, part):
    """film_series: строки (фильм, серия, P1545 как строка или пусто)."""
    out = []
    for b in bindings:
        film = own_qid(b, 'film', part, 'film_series')
        series = qid(value_of(b, 'series'))
        if series is None:
            wd.not_item('film_series')
            continue
        out.append((film, series, value_of(b, 'ordinal', required=False)))
    return out


def parse_money(wd, bindings, part):
    """film_money: строки (фильм, свойство, сумма, Q-id валюты, ранг); единица не item — пусто (и в not_items)."""
    out = []
    for b in bindings:
        film = own_qid(b, 'film', part, 'film_money')
        prop = prop_of(b, 'film_money', MONEY_PROPS)
        rank = rank_of(b, 'film_money')
        if rank == 'deprecated':
            continue
        unit = qid(value_of(b, 'unit'))
        if unit is None:
            wd.not_item('film_money: unit')
        out.append((film, prop, decimal_text(value_of(b, 'amount'), 'film_money'), unit or '', rank))
    return out


def parse_awards(wd, bindings, part):
    """film_awards: строки (фильм, свойство, награда, дата P585 и её точность — или пусто)."""
    out = []
    for b in bindings:
        film = own_qid(b, 'film', part, 'film_awards')
        prop = prop_of(b, 'film_awards', AWARD_PROPS)
        award = qid(value_of(b, 'award'))
        if award is None:
            wd.not_item('film_awards')
            continue
        date = value_of(b, 'date', required=False)
        precision = value_of(b, 'precision', required=False)
        if precision:
            precision = str(integer(precision, 'film_awards: ?precision'))
        out.append((film, prop, award, date, precision))
    return out


def parse_places(wd, bindings, part):
    """places: строки (место, вид, значение). У P625 значение — (ранг, широта, долгота) точной десятичной записью;
    координаты не на глобусе Земли (EARTH) не берутся (считаются в not_items)."""
    out = []
    for b in bindings:
        item = own_qid(b, 'item', part, 'places')
        kind = value_of(b, 'kind')
        if kind == 'P625':
            rank = rank_of(b, 'places')
            if rank == 'deprecated':
                continue
            if qid(value_of(b, 'globe')) != EARTH:
                wd.not_item('places: P625')
                continue
            out.append((item, kind, (rank, decimal_text(value_of(b, 'lat'), 'places: ?lat'),
                                     decimal_text(value_of(b, 'lon'), 'places: ?lon'))))
        elif kind in ('P1566', 'P17', 'en', 'mul', 'ru'):
            out.append((item, kind, value_of(b, 'value')))
        else:
            raise FetchError(f'Wikidata places: ?kind = «{kind}» — ждали одно из P1566, P17, en, mul, ru, P625')
    return out


def parse_titles(wd, bindings, part):
    out = []
    for b in bindings:
        film = own_qid(b, 'film', part, 'film_titles')
        lang = value_of(b, 'lang', required=False) or str(b.get('title', {}).get('xml:lang', ''))
        out.append((film, lang, value_of(b, 'title')))
    return out


def parse_durations(wd, bindings, part):
    return [(own_qid(b, 'film', part, 'film_durations'), seconds_text(value_of(b, 'seconds'))) for b in bindings]


def parse_signals(wd, bindings, part):
    out = []
    for b in bindings:
        film = own_qid(b, 'film', part, 'film_signals')
        kind = value_of(b, 'kind')
        if kind not in ('sitelinks', 'P166', 'P1411', 'P345'):
            raise FetchError(f'Wikidata film_signals: ?kind = «{kind}» — неизвестный')
        out.append((film, kind, integer(value_of(b, 'count'), 'film_signals: ?count'),
                    value_of(b, 'first', required=False)))
    return out


def parse_cast(wd, bindings, part):
    out = []
    for b in bindings:
        film = own_qid(b, 'film', part, 'film_cast')
        prop = value_of(b, 'prop')
        if prop not in ('P161', 'P725'):
            raise FetchError(f'Wikidata film_cast: ?prop = «{prop}» — такого свойства в запросе нет')
        person = qid(value_of(b, 'person'))
        if person is None:
            wd.not_item('film_cast')
            continue
        character = value_of(b, 'character', required=False)
        if character:
            q = qid(character)
            if q is None:
                wd.not_item('film_cast: P453')
            character = q or ''
        out.append((film, prop, person, value_of(b, 'ordinal', required=False), character,
                    value_of(b, 'name', required=False)))
    return out


def parse_names(wd, bindings, part):
    out = []
    for b in bindings:
        person = own_qid(b, 'person', part, 'people_names')
        kind = value_of(b, 'kind')
        if kind not in ('given', 'family'):
            raise FetchError(f'Wikidata people_names: ?kind = «{kind}» — ждали given или family')
        item = qid(value_of(b, 'item'))
        if item is None:
            wd.not_item('people_names')
            continue
        out.append((person, kind, item, value_of(b, 'ordinal', required=False)))
    return out


# ---- шаги ----

def date_literal(year):
    return f'{year:04d}-01-01T00:00:00Z'


def films_from(wd, bindings, stage):
    out = set()
    for b in bindings:
        q = qid(value_of(b, 'film'))
        if q is None:
            wd.not_item(stage)
            continue
        out.add(q)
    return out


def films_by_years(wd, cls, last_year):
    """Запасной путь: фильмы класса по годам P577 — по 10 лет, десятилетие, дважды не уложившееся, — по году."""
    found = set()
    single = []
    for start in range(YEARS_FROM, last_year + 1, 10):
        end = min(start + 10, last_year + 1)
        text = (QUERIES['films_by_class_years'].replace(CLASS, cls)
                .replace(DATE_FROM, date_literal(start)).replace(DATE_TO, date_literal(end)))
        try:
            found |= films_from(wd, wd.query(text, f'films_by_class_years {cls} {start}–{end - 1}', heavy=2),
                                'films_by_class')
            continue
        except Heavy as e:
            log(f'  Wikidata: класс {cls}, {start}–{end - 1} — дважды не уложился ({e}); по году')
        single.append((start, end - 1))
        for year in range(start, end):
            text = (QUERIES['films_by_class_years'].replace(CLASS, cls)
                    .replace(DATE_FROM, date_literal(year)).replace(DATE_TO, date_literal(year + 1)))
            found |= films_from(wd, wd.query(text, f'films_by_class_years {cls} {year}'), 'films_by_class')
    return found, single


def fetch_films(wd):
    """Фильмы классов FILM_CLASSES: пары (фильм, класс) и по классу — как запрошен и сколько найдено."""
    pairs = set()
    info = []
    t0 = time.monotonic()
    sent0 = wd.http.sent
    log(f'Wikidata: films_by_class — классов {len(FILM_CLASSES)}')
    for cls in FILM_CLASSES:
        text = QUERIES['films_by_class'].replace(CLASS, cls)
        try:
            found = films_from(wd, wd.query(text, f'films_by_class {cls}', heavy=2), 'films_by_class')
            mode = None
        except Heavy as e:
            log(f'  Wikidata: класс {cls} целиком дважды не уложился ({e}); по годам P577 с {YEARS_FROM}, по 10 лет')
            found, single = films_by_years(wd, cls, wd.snap.year)
            mode = single
        pairs |= {(f, cls) for f in found}
        info.append({'class': cls, 'found': len(found), 'years': mode})
        log(f'Wikidata: films_by_class {cls} — фильмов {num(len(found))}' + ('' if mode is None else ' (по годам)'))
    films = {f for f, _ in pairs}
    log(f'Wikidata: films_by_class — готово за {took(time.monotonic() - t0)}: фильмов {num(len(films))}, запросов '
        f'{num(wd.http.sent - sent0)}')
    return pairs, info


def fetch_genres(wd, start):
    """Жанры и классы и их надклассы — вширь вверх по P279, не глубже GENRE_DEPTH. Итог: метки en, надклассы
    (у каждого запрошенного элемента; у корня — пусто) и элементы следующего уровня, которые уже не запрашивались."""
    labels, parents, depth = {}, {}, {}
    level = sorted(start, key=qnum)
    for q in level:
        depth[q] = 0
    d = 0
    t0 = time.monotonic()
    sent0 = wd.http.sent
    log(f'Wikidata: genre_items — элементов уровня 0 (жанры и классы фильмов): {num(len(level))}; вверх по P279 '
        f'не глубже {GENRE_DEPTH}')
    parse = kinds_parser('genre_items', 'item', ('en', 'P279'))
    while level and d <= GENRE_DEPTH:
        rows = in_parts(wd, 'genre_items', level, parse, f'уровень {d}, элементов', quiet=True)
        for q in level:
            parents[q] = set()
        for item, kind, value in rows:
            if kind == 'en':
                labels[item] = min(labels.get(item, value), value)
                continue
            p = qid(value)
            if p is None:
                wd.not_item('genre_items')
                continue
            parents[item].add(p)
        d += 1
        level = sorted({p for q in level for p in parents[q] if p not in depth}, key=qnum)
        for p in level:
            depth[p] = d
    log(f'Wikidata: genre_items — готово за {took(time.monotonic() - t0)}: элементов {num(len(parents))}, '
        f'уровней {d}, запросов {num(wd.http.sent - sent0)}'
        + (f'; выше {GENRE_DEPTH} уровня не поднимались (элементов там: {num(len(level))})' if level else ''))
    return labels, parents


def below(parents, root):
    """root и все его подклассы среди запрошенных элементов (по их P279)."""
    children = {}
    for item, ps in parents.items():
        for p in ps:
            children.setdefault(p, []).append(item)
    out = {root}
    stack = [root]
    while stack:
        for c in children.get(stack.pop(), ()):
            if c not in out:
                out.add(c)
                stack.append(c)
    return out


def above(parents, start):
    """start и все их надклассы среди запрошенных элементов."""
    out = set()
    stack = [q for q in start if q in parents]
    while stack:
        q = stack.pop()
        if q in out:
            continue
        out.add(q)
        stack.extend(p for p in parents[q] if p in parents and p not in out)
    return out


def fetch_wikidata(snap):
    wd = Wikidata(snap)
    for name, text in QUERIES.items():
        snap.put(f'wikidata/queries/{name}.rq', text.encode('utf-8'), None)

    # 1–2. фильмы и их связи
    class_pairs, class_info = fetch_films(wd)
    films = sorted({f for f, _ in class_pairs}, key=qnum)
    links = in_parts(wd, 'film_links', films, parse_links, 'фильмов')

    # 3. жанры и классы, 4. фильмы для взрослых — дальше не идут
    start = set(FILM_CLASSES) | set(EXCLUDED_CLASSES) | {c for _, c in class_pairs}
    start |= {v for _, p, v in links if p in ('P31', 'P136')}
    labels, parents = fetch_genres(wd, start)
    adult_items = below(parents, ADULT_ROOT)
    wrong = [c for c in FILM_CLASSES if c in adult_items]
    if wrong:
        raise FetchError(f'Wikidata: класс из FILM_CLASSES ({", ".join(wrong)}) — в замыкании P279 элемента '
                         f'{ADULT_ROOT}; P279 в Wikidata надо проверить, прежде чем снимать снимок')
    adult = {f for f, p, v in links if p in ('P31', 'P136') and v in adult_items}
    adult |= {f for f, c in class_pairs if c in adult_items}
    kept = [f for f in films if f not in adult]
    keep = set(kept)
    log(f'Wikidata: фильмов {num(len(films))}, из них для взрослых (класс или жанр — в замыкании P279 элемента '
        f'{ADULT_ROOT}) {num(len(adult))} — дальше не идут; остаётся {num(len(kept))}')
    snap.at_least('Wikidata: фильмов', len(kept), MIN_FILMS)

    film_rows = {(f, c) for f, c in class_pairs if f in keep}
    film_rows |= {(f, v) for f, p, v in links if p == 'P31' and f in keep}
    snap.table('wikidata/films.csv.gz', ('film', 'class'), film_rows, 'qq')
    links = [r for r in links if r[0] in keep]
    snap.table('wikidata/film_links.csv.gz', ('film', 'prop', 'value'), [r for r in links if r[1] != 'P31'], 'qsq')
    reach = above(parents, set(FILM_CLASSES) | set(EXCLUDED_CLASSES) | {c for _, c in film_rows}
                  | {v for _, p, v in links if p == 'P136'})
    genre_rows = [(q, labels.get(q, ''), p) for q in reach for p in (sorted(parents[q], key=qnum) or [''])]
    snap.table('wikidata/genre_items.csv', ('item', 'label_en', 'parent'), genre_rows, 'qsq')
    top = {p for q in reach for p in parents[q] if p not in parents}
    snap.table('wikidata/classes.csv', ('class', 'label_en', 'included'),
               [(c, labels.get(c, ''), '1') for c in FILM_CLASSES]
               + [(c, labels.get(c, ''), '0') for c in EXCLUDED_CLASSES], 'qsn')

    # 5. свойства оставшихся фильмов
    rows = in_parts(wd, 'film_dates', kept, parse_dates, 'фильмов')
    snap.table('wikidata/film_dates.csv.gz', ('film', 'date', 'precision', 'rank'), rows, 'qsns')
    film_labels = in_parts(wd, 'film_labels', kept, labels_parser('film_labels', 'film'), 'фильмов')
    snap.table('wikidata/film_labels.csv.gz', ('film', 'lang', 'label'), film_labels, 'qss')
    titles = in_parts(wd, 'film_titles', kept, parse_titles, 'фильмов')
    snap.table('wikidata/film_titles.csv.gz', ('film', 'lang', 'title'), titles, 'qss')
    rows = in_parts(wd, 'film_durations', kept, parse_durations, 'фильмов')
    snap.table('wikidata/film_durations.csv.gz', ('film', 'seconds'), rows, 'qd')
    signals = {f: {'sitelinks': 0, 'P166': 0, 'P1411': 0, 'P345': ''} for f in kept}
    for film, kind, count, first in in_parts(wd, 'film_signals', kept, parse_signals, 'фильмов'):
        s = signals[film]
        if kind == 'sitelinks':
            s['sitelinks'] = max(s['sitelinks'], integer(first, 'film_signals: sitelinks'))
        elif kind == 'P345':
            s['P345'] = first
        else:
            s[kind] = count
    snap.table('wikidata/film_signals.csv.gz', ('film', 'sitelinks', 'awards', 'nominations', 'imdb'),
               [(f, str(s['sitelinks']), str(s['P166']), str(s['P1411']), s['P345']) for f, s in signals.items()],
               'qnnns')
    if snap.cfg.minimums and kept and not any(s['sitelinks'] for s in signals.values()):
        raise FetchError('Wikidata film_signals: sitelinks у всех фильмов — 0: сервис не отдал wikibase:sitelinks ни '
                         'на item\'е, ни на узле данных — запрос film_signals надо поправить под этот движок '
                         '(MOV_FETCH_MIN=0 — без этой проверки)')
    cast = in_parts(wd, 'film_cast', kept, parse_cast, 'фильмов')
    snap.table('wikidata/film_cast.csv.gz', ('film', 'prop', 'person', 'ordinal', 'character', 'character_name'),
               cast, 'qsqsqs')
    snap.at_least('Wikidata: строк состава (film_cast)', len(cast), MIN_CAST)

    # 5б. запасные даты, выход по местам, все метки фильмов без названия, съёмочная группа, студии и места, серии,
    # деньги, награды — тоже только по оставшимся фильмам
    rows = in_parts(wd, 'film_dates_extra', kept, dates_parser('film_dates_extra', 'film', EXTRA_DATE_PROPS),
                    'фильмов')
    snap.table('wikidata/film_dates_extra.csv.gz', ('film', 'prop', 'date', 'precision', 'rank'), rows, 'qssns')
    releases = in_parts(wd, 'film_releases', kept, parse_releases, 'фильмов')
    snap.table('wikidata/film_releases.csv.gz', ('film', 'date', 'precision', 'place'), releases, 'qsnq')
    named = {r[0] for r in film_labels} | {r[0] for r in titles}
    untitled = [f for f in kept if f not in named]
    rows = in_parts(wd, 'film_labels_all', untitled, labels_parser('film_labels_all', 'film', None),
                    'фильмов без метки и названия')
    snap.table('wikidata/film_labels_all.csv.gz', ('film', 'lang', 'label'), rows, 'qss')
    crew = in_parts(wd, 'film_crew', kept, links_parser('film_crew', 'film', 'person', CREW_PROPS), 'фильмов')
    snap.table('wikidata/film_crew.csv.gz', ('film', 'prop', 'person'), crew, 'qsq')
    links2 = in_parts(wd, 'film_links2', kept, links_parser('film_links2', 'film', 'value', LINK2_PROPS), 'фильмов')
    snap.table('wikidata/film_links2.csv.gz', ('film', 'prop', 'value'), links2, 'qsq')
    series = in_parts(wd, 'film_series', kept, parse_series, 'фильмов')
    snap.table('wikidata/film_series.csv.gz', ('film', 'series', 'ordinal'), series, 'qqs')
    rows = in_parts(wd, 'film_money', kept, parse_money, 'фильмов')
    snap.table('wikidata/film_money.csv.gz', ('film', 'prop', 'amount', 'unit', 'rank'), rows, 'qsdqs')
    awards = in_parts(wd, 'film_awards', kept, parse_awards, 'фильмов')
    snap.table('wikidata/film_awards.csv.gz', ('film', 'prop', 'award', 'date', 'precision'), awards, 'qsqsn')

    # 6. люди (режиссёры, состав, съёмочная группа) и справочники
    people = sorted({v for _, p, v in links if p == 'P57'} | {r[2] for r in cast} | {r[2] for r in crew}, key=qnum)
    facts = {q: {'human': '0', 'gender': [], 'sitelinks': 0} for q in people}
    for person, kind, value in in_parts(wd, 'people', people,
                                        kinds_parser('people', 'person', ('sitelinks', 'P31', 'P21')), 'людей'):
        f = facts[person]
        if kind == 'sitelinks':
            f['sitelinks'] = max(f['sitelinks'], integer(value, 'people: sitelinks'))
        elif kind == 'P31':
            if qid(value) == HUMAN:
                f['human'] = '1'
        else:
            g = qid(value)
            if g is None:
                wd.not_item('people: P21')
            else:
                f['gender'].append(g)
    snap.table('wikidata/people.csv.gz', ('person', 'human', 'gender', 'sitelinks'),
               [(q, f['human'], min(f['gender'], key=qnum) if f['gender'] else '', str(f['sitelinks']))
                for q, f in facts.items()], 'qnqn')
    snap.at_least('Wikidata: людей (people)', len(people), MIN_PEOPLE)
    rows = in_parts(wd, 'people_labels', people, labels_parser('people_labels', 'person'), 'людей')
    snap.table('wikidata/people_labels.csv.gz', ('person', 'lang', 'label'), rows, 'qss')
    names = in_parts(wd, 'people_names', people, parse_names, 'людей')
    snap.table('wikidata/people_names.csv.gz', ('person', 'kind', 'item', 'ordinal'), names, 'qsqs')
    rows = in_parts(wd, 'people_dates', people, dates_parser('people_dates', 'person', PERSON_DATE_PROPS), 'людей')
    snap.table('wikidata/people_dates.csv.gz', ('person', 'prop', 'date', 'precision', 'rank'), rows, 'qssns')
    person_links = in_parts(wd, 'people_links', people,
                            links_parser('people_links', 'person', 'value', PERSON_LINK_PROPS), 'людей')
    snap.table('wikidata/people_links.csv.gz', ('person', 'prop', 'value'), person_links, 'qsq')

    name_items = sorted({r[2] for r in names}, key=qnum)
    found = {q: {'en': [], 'mul': [], 'P31': set()} for q in name_items}
    for item, kind, value in in_parts(wd, 'name_items', name_items,
                                      kinds_parser('name_items', 'item', ('en', 'mul', 'P31')), 'элементов'):
        if kind == 'P31':
            c = qid(value)
            if c is not None:
                found[item]['P31'].add(c)
        else:
            found[item][kind].append(value)
    snap.table('wikidata/name_items.csv.gz', ('item', 'label_en', 'label_mul', 'kind'),
               [(q, min(f['en'], default=''), min(f['mul'], default=''), name_kind(f['P31']))
                for q, f in found.items()], 'qsss')

    characters = sorted({r[4] for r in cast if r[4]}, key=qnum)
    found = lookup(wd, 'characters', characters, ('en', 'mul'))
    snap.table('wikidata/characters.csv.gz', ('item', 'label_en', 'label_mul'),
               [(q, f['en'], f['mul']) for q, f in found.items()], 'qss')

    companies = sorted({v for _, p, v in links2 if p in COMPANY_PROPS}, key=qnum)
    labelled = sorted(set(companies) | {r[1] for r in series} | {r[2] for r in awards}, key=qnum)
    rows = in_parts(wd, 'item_labels', labelled, labels_parser('item_labels', 'item', ITEM_LANGS), 'элементов')
    snap.table('wikidata/item_labels.csv.gz', ('item', 'lang', 'label'), rows, 'qss')
    found = {q: {'P17': [], 'P159': []} for q in companies}
    for item, kind, value in in_parts(wd, 'companies', companies,
                                      kinds_parser('companies', 'item', ('P17', 'P159')), 'элементов'):
        v = qid(value)
        if v is None:
            wd.not_item(f'companies: {kind}')
        else:
            found[item][kind].append(v)
    company_rows = [(q, min(f['P17'], key=qnum, default=''), min(f['P159'], key=qnum, default=''))
                    for q, f in found.items()]
    snap.table('wikidata/companies.csv', ('item', 'country', 'hq'), company_rows, 'qqq')

    # места — P19 людей, P915 и P840 фильмов, штаб-квартиры (то, что записано в companies.hq)
    places = {v for _, p, v in person_links if p == 'P19'} | {v for _, p, v in links2 if p in PLACE_PROPS}
    places = sorted(places | {r[2] for r in company_rows if r[2]}, key=qnum)
    place_rows = fetch_places(wd, places)
    snap.table('wikidata/places.csv.gz', ('item', 'geonames', 'country', 'label_en', 'label_mul', 'label_ru', 'lat',
                                           'lon'), place_rows, 'qnqsssdd')

    # страны — всё, что сборщик может спросить как страну: P495, места публикации (P291), гражданство (P27), страна
    # места и студии (P17 — то, что записано в places и companies)
    countries = {v for _, p, v in links if p == 'P495'} | {r[3] for r in releases}
    countries |= {v for _, p, v in person_links if p == 'P27'}
    countries |= {r[2] for r in place_rows if r[2]} | {r[1] for r in company_rows if r[1]}
    countries = sorted(countries, key=qnum)
    found = lookup(wd, 'countries', countries, ('P297', 'P298'))
    snap.table('wikidata/countries.csv', ('item', 'iso2', 'iso3'),
               [(q, f['P297'], f['P298']) for q, f in found.items()], 'qss')
    languages = sorted({v for _, p, v in links if p == 'P364'}, key=qnum)
    found = lookup(wd, 'languages', languages, ('P218', 'P220', 'en'))
    snap.table('wikidata/languages.csv', ('item', 'iso1', 'iso3', 'label_en'),
               [(q, f['P218'], f['P220'], f['en']) for q, f in found.items()], 'qsss')

    snap.info['wikidata'] = {
        'classes': class_info, 'labels': labels, 'films': len(films), 'adult': len(adult), 'kept': len(kept),
        'untitled': len(untitled), 'people': len(people), 'top': len(top), 'parts': wd.parts, 'splits': wd.splits,
        'not_items': dict(wd.not_items),
    }


def lookup(wd, stage, items, kinds):
    """Справочник по items: у каждого — по виду первое по алфавиту значение (нет — пусто)."""
    found = {q: {k: [] for k in kinds} for q in items}
    for item, kind, value in in_parts(wd, stage, items, kinds_parser(stage, 'item', kinds), 'элементов'):
        found[item][kind].append(value)
    return {q: {k: min(v, default='') for k, v in f.items()} for q, f in found.items()}


def fetch_places(wd, places):
    """Места: строки (item, GeoNames ID — наименьший по числу, страна P17 — первая по номеру, метки en, mul и ru, широта и
    долгота); чего нет — пусто. Координаты — P625 на глобусе Земли: из набора preferred, иначе normal, — первые по
    (широта, долгота) как числам. GeoNames ID не из цифр и страна не item не пишутся (считаются в not_items)."""
    found = {q: {'P1566': [], 'P17': [], 'en': [], 'mul': [], 'ru': [], 'P625': []} for q in places}
    for item, kind, value in in_parts(wd, 'places', places, parse_places, 'элементов'):
        f = found[item]
        if kind == 'P1566':
            if re.fullmatch(r'[0-9]+', value):
                f['P1566'].append(int(value))
            else:
                wd.not_item('places: P1566')
        elif kind == 'P17':
            c = qid(value)
            if c is None:
                wd.not_item('places: P17')
            else:
                f['P17'].append(c)
        else:
            f[kind].append(value)
    rows = []
    for q, f in found.items():
        coords = [c for c in f['P625'] if c[0] == 'preferred'] or f['P625']
        lat, lon = min(((c[1], c[2]) for c in coords), key=lambda c: (decimal.Decimal(c[0]), decimal.Decimal(c[1])),
                       default=('', ''))
        rows.append((q, str(min(f['P1566'])) if f['P1566'] else '', min(f['P17'], key=qnum, default=''),
                     min(f['en'], default=''), min(f['mul'], default=''), min(f['ru'], default=''), lat,
                     lon))
    return rows


# ---- SOURCE.md, fetch.json, SHA256SUMS, архив ----

FILE_NOTES = (
    ('wikidata/classes.csv', '`class,label_en,included` — классы фильмов из `fetch.py`: `1` — фильмы запрошены по '
     'этому классу, `0` — фильм с таким P31 сборка не берёт; метка — из Wikidata'),
    ('wikidata/films.csv.gz', '`film,class` — фильмы классов с `included = 1` и все их P31 (лучший ранг)'),
    ('wikidata/film_dates.csv.gz', '`film,date,precision,rank` — дата публикации (P577), кроме deprecated: значение '
     'как отдал сервис, точность (9 — год, 10 — месяц, 11 — день), ранг'),
    ('wikidata/film_labels.csv.gz', '`film,lang,label` — метки на языках ' + ' '.join(LABEL_LANGS)),
    ('wikidata/film_titles.csv.gz', '`film,lang,title` — название (P1476, лучший ранг) и его язык'),
    ('wikidata/film_links.csv.gz', '`film,prop,value` — жанр (P136), режиссёр (P57), страна происхождения (P495), '
     'язык оригинала (P364), лучший ранг'),
    ('wikidata/film_durations.csv.gz', '`film,seconds` — длительность (P2047), кроме deprecated, нормализованная в '
     'секунды'),
    ('wikidata/film_signals.csv.gz', '`film,sitelinks,awards,nominations,imdb` — по строке на фильм: число ссылок на '
     'вики-проекты, утверждений P166 и P1411 (кроме deprecated), ID IMDb (P345; если их несколько — первый по '
     'алфавиту)'),
    ('wikidata/film_cast.csv.gz', '`film,prop,person,ordinal,character,character_name` — роли (P161) и озвучивание '
     '(P725), кроме deprecated: по строке на решение SPARQL — перекрёсток значений P453 × P4633 (× P1545) '
     'утверждения; без них — одна строка с пустыми'),
    ('wikidata/film_dates_extra.csv.gz', '`film,prop,date,precision,rank` — запасные даты, кроме deprecated: первый '
     'показ (P1191), создание (P571), дата производства (P2754); значение, точность и ранг — как в `film_dates`'),
    ('wikidata/film_releases.csv.gz', '`film,date,precision,place` — даты публикации (P577), кроме deprecated, с '
     'местом публикации (квалификатор P291): по строке на утверждение и место; утверждений без P291 здесь нет'),
    ('wikidata/film_labels_all.csv.gz', '`film,lang,label` — все метки (на любом языке) фильмов, у которых нет ни '
     'метки на языках `film_labels`, ни P1476'),
    ('wikidata/film_crew.csv.gz', '`film,prop,person` — съёмочная группа, лучший ранг: сценарист (P58), композитор '
     '(P86), оператор (P344), продюсер (P162), монтажёр (P1040)'),
    ('wikidata/film_links2.csv.gz', '`film,prop,value` — студия (P272), прокатчик (P750), место съёмок (P915), '
     'место действия (P840), предыдущий (P155) и следующий (P156) фильм, лучший ранг'),
    ('wikidata/film_series.csv.gz', '`film,series,ordinal` — серия (P179), кроме deprecated, и порядок в ней '
     '(квалификатор P1545 как строка): по строке на решение SPARQL — у нескольких P1545 по строке на каждый, без '
     'него — пусто'),
    ('wikidata/film_money.csv.gz', '`film,prop,amount,unit,rank` — бюджет (P2130) и сборы (P2142), кроме deprecated: '
     'сумма как в утверждении (без «+», показателя и лишних нулей), единица — item валюты, ранг'),
    ('wikidata/film_awards.csv.gz', '`film,prop,award,date,precision` — награды (P166) и номинации (P1411), кроме '
     'deprecated, с датой (квалификатор P585: значение и точность): по строке на решение SPARQL — у нескольких P585 '
     'по строке на каждый, без него — пусто'),
    ('wikidata/genre_items.csv', f'`item,label_en,parent` — жанры (P136) и классы (P31) фильмов и их надклассы '
     f'(P279, лучший ранг) вверх до корня, не глубже {GENRE_DEPTH} уровней: по строке на пару, у корня `parent` пуст'),
    ('wikidata/people.csv.gz', '`person,human,gender,sitelinks` — люди из P57, состава и съёмочной группы: `human` — '
     '1, если P31 = Q5; `gender` — P21 (лучший ранг; если их несколько — первый по номеру)'),
    ('wikidata/people_labels.csv.gz', '`person,lang,label` — метки людей на тех же языках'),
    ('wikidata/people_names.csv.gz', '`person,kind,item,ordinal` — имя (P735, `given`) и фамилия (P734, `family`), '
     'кроме deprecated, с порядком P1545'),
    ('wikidata/people_dates.csv.gz', '`person,prop,date,precision,rank` — даты рождения (P569) и смерти (P570), '
     'кроме deprecated; значение, точность и ранг — как в `film_dates`'),
    ('wikidata/people_links.csv.gz', '`person,prop,value` — место рождения (P19) и гражданство (P27), лучший ранг'),
    ('wikidata/name_items.csv.gz', f'`item,label_en,label_mul,kind` — элементы имён и фамилий: метки и вид по P31 '
     f'({NAME_MALE} — `male`, {NAME_FEMALE} — `female`, {NAME_UNISEX} или оба — `unisex`, {NAME_FAMILY} — `family`, '
     f'иначе `other`)'),
    ('wikidata/characters.csv.gz', '`item,label_en,label_mul` — персонажи (значения P453)'),
    ('wikidata/item_labels.csv.gz', '`item,lang,label` — метки ' + ', '.join(ITEM_LANGS) + ' студий и прокатчиков '
     '(значения P272, P750), серий (P179) и наград (P166, P1411)'),
    ('wikidata/companies.csv', '`item,country,hq` — студии и прокатчики (значения P272, P750): страна (P17) и '
     'штаб-квартира (P159), лучший ранг; несколько — первое по номеру; нет — пусто'),
    ('wikidata/places.csv.gz', '`item,geonames,country,label_en,label_mul,label_ru,lat,lon` — места (значения P19, P915, P840 и '
     '`companies.hq`): GeoNames ID (P1566; несколько — наименьший по числу), страна (P17; несколько — первая по '
     'номеру) — лучший ранг; метки en, mul и ru; координаты (P625, кроме deprecated, только глобус Земли; из preferred, '
     f'иначе normal, — первые по широте, затем долготе, как числам; глобус — {EARTH}); нет — пусто'),
    ('wikidata/countries.csv', '`item,iso2,iso3` — страны: значения P495, места публикации (P291 в `film_releases`), '
     'гражданство (P27), страны мест и компаний (`places.country`, `companies.country`): P297 и P298 (лучший ранг; '
     'несколько — первый по алфавиту; нет — пусто)'),
    ('wikidata/languages.csv', '`item,iso1,iso3,label_en` — значения P364: P218, P220 (так же) и метка en'),
)


def cell(v):
    if v is None or v == '':
        return '—'
    return str(v).replace('|', '\\|').replace('\n', ' ')


def source_md(snap):
    f = snap.files
    wd = snap.info['wikidata']
    cfg = snap.cfg
    http_client = snap.http
    out = [
        f'# {snap.name} — снимок источника набора Movie',
        '',
        'Снимок Wikidata, из которого собирается набор `mov-data-v1`: фильмы, их даты, названия, жанры, режиссёры,',
        'состав и съёмочная группа, студии, серии, бюджеты и сборы, награды, люди, места (съёмок, действия, рождения,',
        f'штаб-квартиры — с координатами) и справочники. Время снимка — **{snap.fetched_at}** (UTC).',
        '',
        f'Архив `{snap.name}.zip` — без общей папки. Ответы Wikidata — в CSV (UTF-8, LF, заголовок первой строкой,',
        'кавычки — где нужно; item — номером `Q…`; строки — по столбцам слева направо, номера `Q` — как числа),',
        'большие файлы — в `.csv.gz` (gzip). `SHA256SUMS` — суммы всех прочих файлов архива; `fetch.json` — у каждого',
        'файла адрес, байты, sha256 и число строк. Собран `mov/source/fetch.py` (workflow `source` по тегу снимка).',
        '',
        '## Wikidata',
        '',
        f'SPARQL — {cfg.sparql} (POST `query=…`, ответ `application/sparql-results+json`). Тексты запросов — в',
        '`wikidata/queries/`: на месте `%VALUES%` — часть элементов (`wd:Q…`), `%CLASS%` — класс, `%FROM%` и `%TO%` —',
        f'границы дат. Запросов отправлено — {num(http_client.sent)}, с ответом — {num(http_client.answered)}'
        + (' (остальные — повторы после сбоев и пауз по просьбе сервиса).' if http_client.sent != http_client.answered
           else '.'),
        '',
        'Фильмы — экземпляры (P31) классов с `included = 1` из `wikidata/classes.csv`, по запросу на класс:',
        '',
        '| класс | метка | запрос | найдено |',
        '|---|---|---|---:|',
    ]
    for c in wd['classes']:
        if c['years'] is None:
            how = 'целиком'
        else:
            how = f'по годам P577, {YEARS_FROM}–{snap.year}, по 10 лет'
            if c['years']:
                how += '; по году — ' + ', '.join(f'{a}–{b}' for a, b in c['years'])
        out.append(f'| `{c["class"]}` | {cell(wd["labels"].get(c["class"]))} | {how} | {num(c["found"])} |')
    out += [
        '',
        'Запрос класса по годам — если целиком он дважды не уложился во время; фильм без P577 так не находится.',
        '',
        'Связи, даты, метки, названия, длительность, признаки, состав, люди и справочники — частями VALUES по',
        f'{num(cfg.chunk)} элементов, до {cfg.workers} запросов одновременно; часть, на которую сервис не ответил',
        f'(тайм-аут, 5xx, оборванный ответ), делится пополам, пока половины не меньше {MIN_CHUNK}. Частей — '
        f'{num(wd["parts"])},',
        f'из них делились — {num(wd["splits"])}; ответила каждая.',
        '',
        f'Фильмы для взрослых (класс или жанр — в замыкании P279 элемента {ADULT_ROOT}) в снимок не вошли: '
        f'{num(wd["adult"])}.',
        '',
        f'В снимке фильмов — {num(wd["kept"])}, людей — {num(wd["people"])} (режиссёры, состав и съёмочная группа).',
        f'Фильмов без метки на языках {" ".join(LABEL_LANGS)} и без P1476 — {num(wd["untitled"])}: все их метки — в',
        '`wikidata/film_labels_all.csv.gz`.',
        '',
        '| файл | что | строк | байт |',
        '|---|---|---:|---:|',
    ]
    for rel, what in FILE_NOTES:
        out.append(f'| `{rel}` | {what} | {num(f[rel]["rows"])} | {num(f[rel]["bytes"])} |')
    out.append('')
    if wd['not_items']:
        out += ['Значения, которые не item (неизвестное значение и т. п.; у P1566 — не число, у P625 — не глобус '
                'Земли), в файлы не записаны: '
                + ', '.join(f'{k} — {num(v)}' for k, v in sorted(wd['not_items'].items())) + '.', '']
    if wd['top']:
        out += [f'Надклассы выше {GENRE_DEPTH}-го уровня не запрашивались — элементов, которые есть в '
                f'`genre_items.csv` только в `parent`: {num(wd["top"])}.', '']
    out += [
        'Лицензия данных — CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/). Источник — Wikidata,',
        'https://www.wikidata.org/.',
        '',
    ]
    return '\n'.join(out)


def fetch_json(snap):
    """fetch.json: снимок, время, файлы (по пути) и пропущенное; одна строка на файл — читать глазами."""
    lines = ['{',
             f'  "snapshot": {json.dumps(snap.name)},',
             f'  "fetched_at": {json.dumps(snap.fetched_at)},',
             '  "files": {']
    names = sorted(snap.files, key=lambda s: s.encode('utf-8'))
    for i, rel in enumerate(names):
        e = snap.files[rel]
        entry = {'url': e['url'], 'bytes': e['bytes'], 'sha256': e['sha256'], 'rows': e['rows']}
        lines.append(f'    {json.dumps(rel, ensure_ascii=False)}: {json.dumps(entry, ensure_ascii=False)}'
                     + (',' if i < len(names) - 1 else ''))
    lines.append('  },')
    lines.append('  "skipped": []')
    lines.append('}')
    return ('\n'.join(lines) + '\n').encode('utf-8')


def file_digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(READ_CHUNK), b''):
            h.update(chunk)
    return h.hexdigest()


def tree_files(tree):
    out = []
    for dirpath, _, filenames in os.walk(tree):
        for fn in filenames:
            out.append(pathlib.Path(dirpath, fn).relative_to(tree).as_posix())
    return sorted(out, key=lambda s: s.encode('utf-8'))


def write_zip(tree, names, dest):
    """Детерминированный zip: записи в порядке names, время 1980-01-01, права 0644; .zip/.gz — STORED."""
    part = dest.with_name(dest.name + '.part')
    with zipfile.ZipFile(part, 'w', allowZip64=True) as zf:
        for name in names:
            data = tree.joinpath(*name.split('/')).read_bytes()
            zi = zipfile.ZipInfo(name, date_time=ZIP_TIME)
            zi.create_system = 3
            zi.external_attr = (stat.S_IFREG | 0o644) << 16
            if name.endswith(ZIP_STORED_SUFFIXES):
                zi.compress_type = zipfile.ZIP_STORED
                zf.writestr(zi, data)
            else:
                zi.compress_type = zipfile.ZIP_DEFLATED
                zf.writestr(zi, data, compresslevel=9)
    os.replace(part, dest)


def finish(snap, out):
    snap.path('SOURCE.md').write_bytes(source_md(snap).encode('utf-8'))
    snap.path('fetch.json').write_bytes(fetch_json(snap))
    names = tree_files(snap.tree)
    expected = sorted(set(snap.files) | {'SOURCE.md', 'fetch.json'}, key=lambda s: s.encode('utf-8'))
    if names != expected:
        extra = sorted(set(names) ^ set(expected))
        raise FetchError(f'дерево снимка не совпало с учётом файлов: {", ".join(extra[:10])}')
    sums = ''.join(f'{file_digest(snap.tree.joinpath(*n.split("/")))}  {n}\n' for n in names)
    snap.path('SHA256SUMS').write_bytes(sums.encode('utf-8'))
    names = tree_files(snap.tree)
    out.mkdir(parents=True, exist_ok=True)
    dest = out / f'{snap.name}.zip'
    write_zip(snap.tree, names, dest)
    for name in ('SOURCE.md', 'SHA256SUMS'):
        (out / name).write_bytes((snap.tree / name).read_bytes())
    log(f'архив: {dest} — {num(dest.stat().st_size)} байт, файлов {num(len(names))}, sha256 {file_digest(dest)}')


def run(args, cfg):
    m = SNAPSHOT_RE.fullmatch(args.snapshot)
    if not m:
        raise FetchError(f'снимок «{args.snapshot}» — не mov-source-YYYYMMDD')
    try:
        datetime.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        raise FetchError(f'снимок «{args.snapshot}»: нет такой даты') from None
    year = int(m.group(1))
    out = pathlib.Path(args.out)
    tmp = None
    if args.work:
        work = pathlib.Path(args.work)
    else:
        tmp = tempfile.mkdtemp(prefix='mov-source-')
        work = pathlib.Path(tmp)
    tree = work / args.snapshot
    try:
        if tree.exists():
            shutil.rmtree(tree)
        tree.mkdir(parents=True)
        snap = Snapshot(args.snapshot, year, cfg, tree, Http(cfg.backoff))
        t0 = time.monotonic()
        log(f'снимок {snap.name}: время {snap.fetched_at}, {cfg.sparql}, частями по {num(cfg.chunk)}, потоков '
            f'{cfg.workers}, дерево {tree}')
        fetch_wikidata(snap)
        finish(snap, out)
        log(f'снимок {snap.name} собран за {took(time.monotonic() - t0)}: запросов {num(snap.http.sent)}, '
            f'с ответом {num(snap.http.answered)}')
    finally:
        if tmp is not None:
            shutil.rmtree(tmp, ignore_errors=True)
    return 0


def parse_args(argv):
    p = argparse.ArgumentParser(prog='fetch.py', description='Снимок источника набора Movie (mov-data-v1).')
    p.add_argument('--snapshot', required=True, help='имя снимка — тег выпуска: mov-source-YYYYMMDD')
    p.add_argument('--out', required=True, help='куда положить архив, SOURCE.md и SHA256SUMS')
    p.add_argument('--work', help='где собирать дерево архива (по умолчанию — временная папка)')
    return p.parse_args(argv)


def main(argv=None):
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    args = parse_args(argv)
    try:
        return run(args, Config(os.environ))
    except FetchError as e:
        prefix = '::error::' if os.environ.get('GITHUB_ACTIONS') == 'true' else ''
        print(f'{prefix}снимок не собран: {e}', file=sys.stderr, flush=True)
        return 1


if __name__ == '__main__':
    sys.exit(main())
