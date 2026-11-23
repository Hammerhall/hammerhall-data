"""Снимок источников набора World (wld-data-v1): GeoNames, World Bank Open Data, Unicode CLDR 49 и Wikidata — одним
архивом wld-source-YYYYMMDD.zip (выпуск wld-source-YYYYMMDD этого репозитория; вход source сборки набора).

Запуск:  python3 -X utf8 -I wld/source/fetch.py --snapshot wld-source-YYYYMMDD --out ПАПКА [--work ПАПКА]

В ПАПКЕ — wld-source-YYYYMMDD.zip, SOURCE.md и SHA256SUMS (копии тех же файлов из архива). Дерево архива собирается
в --work (в ней — папка с именем снимка, прежняя стирается); без --work — во временной папке, после сборки её нет.
Год снимка — конец рядов World Bank (date=1960:<год>).

Раскладка архива — без общей верхней папки: SOURCE.md, SHA256SUMS, fetch.json, geonames/…, worldbank/…, cldr/…,
wikidata/…. Архив детерминированный: записи по имени (побайтно), время 1980-01-01 00:00:00, права 0644, .zip и .tgz —
без сжатия, остальное — deflate 9. Те же ответы источников и то же время снимка — тот же архив побайтно.

Скачивание — с повторами: 6 попыток, паузы 5, 15, 30, 60, 120 с, тайм-аут сокета 120 с. Повторяются сбои сети, 5xx,
408, 429 (пауза — из Retry-After), оборванные ответы (меньше Content-Length), не-JSON, ответы-ошибки World Bank
([{"message": …}]), битые архивы и sha512 tarball, не совпавший с dist.integrity. После последней попытки, а также
на 4xx и на неожиданном формате — выход с кодом 1 и причиной (адрес и что не так).

Переменные среды (по умолчанию — настоящие источники):
  WLD_GEONAMES_BASE     https://download.geonames.org/export/dump/
  WLD_WORLDBANK_BASE    https://api.worldbank.org/v2/
  WLD_NPM_BASE          https://registry.npmjs.org/
  WLD_WIKIDATA_SPARQL   https://query.wikidata.org/sparql
  WLD_FETCH_BACKOFF     множитель пауз между попытками и запросами Wikidata (1; 0 — без пауз и без Retry-After)
  WLD_FETCH_MIN         0 — не проверять нижние границы числа строк (для тестов на маленьких ответах)
  WLD_FETCHED_AT        время снимка YYYY-MM-DDTHH:MM:SSZ вместо нынешнего (два прогона — один архив побайтно)
Прокси — как у urllib: HTTPS_PROXY, HTTP_PROXY, NO_PROXY.

Только стандартная библиотека; Python 3.12 (CI) и новее.
"""
import argparse
import base64
import binascii
import csv
import datetime
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
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib

USER_AGENT = 'HammerhallData/1.0 (https://github.com/Hammerhall/hammerhall-data)'

DEFAULT_GEONAMES = 'https://download.geonames.org/export/dump/'
DEFAULT_WORLDBANK = 'https://api.worldbank.org/v2/'
DEFAULT_NPM = 'https://registry.npmjs.org/'
DEFAULT_SPARQL = 'https://query.wikidata.org/sparql'

SNAPSHOT_RE = re.compile(r'wld-source-([0-9]{4})([0-9]{2})([0-9]{2})')
FETCHED_AT_RE = re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z')

# повторы: попыток всего, паузы перед 2-й … 6-й (с), тайм-аут сокета (с), потолок паузы из Retry-After (с)
ATTEMPTS = 6
BACKOFF = (5, 15, 30, 60, 120)
TIMEOUT = 120
RETRY_AFTER_MAX = 600
RETRY_STATUS = (408, 425, 429)
READ_CHUNK = 1 << 20

# GeoNames: выгрузка страны <CC>.zip (для столицы, которой нет в cities500) — не больше стольких байт
CAPITAL_ZIP_MAX = 30 * 1024 * 1024
COUNTRY_INFO_COLUMNS = 19
ADMIN1_COLUMNS = 4
GEONAME_COLUMNS = 19
# коды класса P, которых сборка набора не берёт (части населённых пунктов, исторические, заброшенные, разрушенные):
# такая строка столицу не заменяет — для страны скачивается её выгрузка целиком
CITY_CODES_DROPPED = frozenset(('PPLX', 'PPLH', 'PPLQ', 'PPLW', 'PPLCH'))

# World Bank: показатели, размер страницы, начало рядов; полных проходов по страницам, если шапки страниц разошлись
WB_INDICATORS = ('SP.POP.TOTL', 'NY.GNP.MKTP.CD', 'NY.GDP.MKTP.CD', 'SP.DYN.LE00.IN', 'AG.SRF.TOTL.K2')
WB_COUNTRIES_PER_PAGE = 1000
WB_DATA_PER_PAGE = 20000
WB_FIRST_YEAR = 1960
WB_PASSES = 3

# Unicode CLDR: пакеты npm и что из них извлекается (пути — без префикса package/)
CLDR_VERSION = '49.0.0'
CLDR_PACKAGES = ('cldr-core', 'cldr-localenames-full')
CLDR_CORE_FILES = ('LICENSE', 'package.json', 'supplemental/territoryInfo.json',
                   'supplemental/territoryContainment.json', 'supplemental/languageData.json',
                   'supplemental/likelySubtags.json')
CLDR_NAMES_FILES = ('LICENSE', 'package.json')
CLDR_NAMES_REQUIRED = ('main/en/territories.json', 'main/ru/territories.json')
CLDR_LOCALE_FILE = re.compile(r'main/([A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)/(territories|languages|scripts)\.json')

# Wikidata: geonameid в одном запросе, пауза между запросами (с), строка хода — через столько частей
WIKIDATA_CHUNK = 500
WIKIDATA_PAUSE = 0.5
WIKIDATA_PROGRESS = 50
ENTITY_PREFIXES = ('http://www.wikidata.org/entity/', 'https://www.wikidata.org/entity/')
QID = re.compile(r'Q[1-9][0-9]*')
RANKS = {
    'http://wikiba.se/ontology#PreferredRank': 'preferred',
    'http://wikiba.se/ontology#NormalRank': 'normal',
    'http://wikiba.se/ontology#DeprecatedRank': 'deprecated',
}

# нижние границы (WLD_FETCH_MIN=0 — выключены): меньше — источник отдал не всё
MIN_COUNTRY_INFO = 240
MIN_ADMIN1 = 3000
MIN_CITIES500 = 150000
MIN_WB_COUNTRIES = 200
MIN_WB_INDICATOR = 200
MIN_CITIES_RU = 20000
MIN_INCEPTION_ITEMS = 200
MIN_LANGUAGES = 5000

ZIP_TIME = (1980, 1, 1, 0, 0, 0)
ZIP_STORED_SUFFIXES = ('.zip', '.tgz')

# Тексты запросов SPARQL — как есть в wikidata/queries/<имя>.rq. В cities_ru на месте GEONAMEIDS — коды одной части.
GEONAMEIDS = '%GEONAMEIDS%'
QUERIES = {
    'cities_ru': '''\
# Русские названия (rdfs:label на ru) item'ов по коду GeoNames (P1566). Запрос идёт частями по 500 geonameid:
# в VALUES — коды строками через пробел ("524901" "703448" …), все geonameid класса P из cities500.txt
# и capitals/*.zip, по возрастанию.
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?gid ?item ?ru WHERE {
  VALUES ?gid { %GEONAMEIDS% }
  ?item wdt:P1566 ?gid .
  ?item rdfs:label ?ru . FILTER(LANG(?ru) = "ru")
}
''',
    'country_inception': '''\
# Все item'ы с кодом ISO 3166-1 alpha-3 (P298) и их утверждения «дата основания» (P571): значение, точность и ранг.
# Item без P571 — одна строка с пустыми ?inception, ?precision, ?rank.
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX p: <http://www.wikidata.org/prop/>
PREFIX psv: <http://www.wikidata.org/prop/statement/value/>
PREFIX wikibase: <http://wikiba.se/ontology#>
SELECT ?item ?iso3 ?inception ?precision ?rank WHERE {
  ?item wdt:P298 ?iso3 .
  OPTIONAL { ?item p:P571 ?st . ?st wikibase:rank ?rank . ?st psv:P571 ?v .
             ?v wikibase:timeValue ?inception ; wikibase:timePrecision ?precision }
}
''',
    'country_types': '''\
# Типы (instance of) item'ов с кодом ISO 3166-1 alpha-3 (P298).
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
SELECT ?item ?iso3 ?type WHERE {
  ?item wdt:P298 ?iso3 ;
        wdt:P31 ?type .
}
''',
    'languages': '''\
# Item'ы с кодом языка ISO 639-3 (P220) и их метки на английском и русском; метки нет — пусто.
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?item ?code ?en ?ru WHERE {
  ?item wdt:P220 ?code .
  OPTIONAL { ?item rdfs:label ?en . FILTER(LANG(?en) = "en") }
  OPTIONAL { ?item rdfs:label ?ru . FILTER(LANG(?ru) = "ru") }
}
''',
}


class FetchError(Exception):
    """Снимок не собрать: в тексте — адрес (или файл) и причина. status — код HTTP, если причина в нём."""

    def __init__(self, text, status=None):
        super().__init__(text)
        self.status = status


class Retry(Exception):
    """Ответ пришёл, но не годится — повторить запрос; wait — своя пауза (с), например из Retry-After."""

    def __init__(self, text, wait=None):
        super().__init__(text)
        self.wait = wait


class TooBig(Exception):
    """Файл больше допустимого (size — Content-Length, если он был)."""

    def __init__(self, size):
        super().__init__(size)
        self.size = size


class Response:
    __slots__ = ('url', 'body', 'length', 'value')

    def __init__(self, url, body, length, value):
        self.url = url
        self.body = body
        self.length = length
        self.value = value


def log(text):
    print(text, flush=True)


def num(n):
    """12345 → «12 345»."""
    return f'{n:,}'.replace(',', ' ')


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
    """HTTP с повторами. Один запрос — до ATTEMPTS попыток; что не вышло — FetchError с адресом и причиной."""

    def __init__(self, backoff):
        self.backoff = backoff
        self.opener = urllib.request.build_opener()  # прокси — из среды (HTTPS_PROXY, NO_PROXY)
        self.opener.addheaders = []

    def delay(self, seconds, exact=False):
        """Пауза с учётом WLD_FETCH_BACKOFF: обычная — умножается, из Retry-After (exact) — как есть; 0 — без пауз."""
        if self.backoff <= 0:
            return 0
        return seconds if exact else seconds * self.backoff

    def pause(self, seconds):
        s = self.delay(seconds)
        if s > 0:
            time.sleep(s)

    def fetch(self, url, method='GET', data=None, headers=None, check=None, dest=None, limit=None, label=None):
        """Запрос с повторами. check(тело или путь) — проверка ответа: Retry — повторить, иначе её итог — в value.
        dest — путь: тело пишется в файл (через .part), иначе — в память. limit — сверх стольких байт TooBig."""
        where = url if label is None else f'{url} ({label})'
        reason = ''
        for attempt in range(1, ATTEMPTS + 1):
            wait = None
            try:
                return self._once(url, method, data, headers, check, dest, limit)
            except Retry as e:
                reason, wait = str(e), e.wait
            except urllib.error.HTTPError as e:
                code = e.code
                text = http_snippet(e)
                if code in RETRY_STATUS or 500 <= code <= 599:
                    reason = f'HTTP {code}{text}'
                    if code in (429, 503):
                        wait = retry_after(e.headers)
                else:
                    raise FetchError(f'{where}: HTTP {code} {e.reason}{text} — повтор не поможет',
                                     status=code) from None
            except urllib.error.URLError as e:
                reason = f'сеть: {e.reason}'
            except http.client.IncompleteRead as e:
                reason = f'ответ оборван ({len(e.partial)} байт): {e}'
            except (http.client.HTTPException, OSError) as e:
                reason = f'сеть: {type(e).__name__}: {e}'
            if attempt == ATTEMPTS:
                break
            # Retry-After — не меньше обычной паузы этой попытки
            s = max(self.delay(BACKOFF[attempt - 1]), self.delay(wait, exact=True) if wait is not None else 0)
            log(f'  попытка {attempt}/{ATTEMPTS} не удалась — {where}: {reason}; повтор через {s:g} с')
            if s > 0:
                time.sleep(s)
        raise FetchError(f'не удалось скачать {where}: {reason} (попыток: {ATTEMPTS})')

    def _once(self, url, method, data, headers, check, dest, limit):
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header('User-Agent', USER_AGENT)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        part = None if dest is None else dest.with_name(dest.name + '.part')
        try:
            with self.opener.open(req, timeout=TIMEOUT) as resp:
                length = content_length(resp.headers)
                if method == 'HEAD':
                    return Response(url, b'', length, None)
                if limit is not None and length is not None and length > limit:
                    raise TooBig(length)
                encoding = (resp.headers.get('Content-Encoding') or '').strip().lower()
                got = 0
                body = None
                with (open(part, 'wb') if part is not None else io.BytesIO()) as sink:
                    while True:
                        chunk = resp.read(READ_CHUNK)
                        if not chunk:
                            break
                        got += len(chunk)
                        if limit is not None and got > limit:
                            raise TooBig(None)
                        sink.write(chunk)
                    if part is None:
                        body = sink.getvalue()
            if length is not None and got != length:
                raise Retry(f'ответ оборван: {num(got)} байт из {num(length)} (Content-Length)')
            if body is not None and encoding in ('gzip', 'x-gzip'):
                # gzip сами не просим (Accept-Encoding не шлём), но если сервер прислал — разжать
                try:
                    body = zlib.decompress(body, 16 + zlib.MAX_WBITS)
                except zlib.error as e:
                    raise Retry(f'битый gzip ({e})') from None
            value = check(part if part is not None else body) if check is not None else None
            if part is not None:
                os.replace(part, dest)
            return Response(url, body, got, value)
        finally:
            if part is not None and part.exists():
                part.unlink()


# ---- проверки ответов ----

def parse_json(body):
    """JSON (UTF-8, возможно с BOM); не разобрать — Retry."""
    try:
        return json.loads(body.decode('utf-8-sig'))
    except (UnicodeDecodeError, ValueError) as e:
        raise Retry(f'не JSON ({type(e).__name__}: {str(e)[:100]})') from None


def wb_error(obj):
    """Ответ-ошибка World Bank ([{"message": [...]}] или {"message": [...]}) — текст ошибки, иначе None."""
    head = obj[0] if isinstance(obj, list) and obj else obj
    if not isinstance(head, dict) or 'message' not in head:
        return None
    msg = head['message']
    parts = []
    for m in msg if isinstance(msg, list) else [msg]:
        if isinstance(m, dict):
            parts.append(' '.join(str(m.get(k)) for k in ('id', 'key', 'value') if m.get(k) not in (None, '')))
        else:
            parts.append(str(m))
    return '; '.join(parts) or 'без текста'


def check_wb_json(body):
    """JSON World Bank как есть (описание показателя, метаданные): не JSON или ответ-ошибка — Retry."""
    obj = parse_json(body)
    err = wb_error(obj)
    if err is not None:
        raise Retry(f'World Bank ответил ошибкой: {err[:200]}')
    return obj


def check_wb_page(body):
    """Страница World Bank: [шапка с page/pages/total, строки или null]; иначе — Retry."""
    obj = check_wb_json(body)
    if not (isinstance(obj, list) and len(obj) == 2 and isinstance(obj[0], dict)):
        raise Retry('ответ World Bank не вида [шапка, строки]')
    try:
        for k in ('page', 'pages', 'total'):
            int(obj[0][k])
    except (KeyError, TypeError, ValueError):
        raise Retry('в шапке World Bank нет целых page/pages/total') from None
    rows = obj[1]
    if rows is not None and not (isinstance(rows, list) and all(isinstance(r, dict) for r in rows)):
        raise Retry('строки World Bank — не список объектов')
    return obj


def check_zip(path, member):
    """Архив читается, CRC всех записей сходятся, нужная запись есть."""
    try:
        with zipfile.ZipFile(path) as zf:
            names = zf.namelist()
            bad = zf.testzip()
    except (zipfile.BadZipFile, zlib.error, EOFError, OSError) as e:
        raise Retry(f'не zip-архив или битый ({type(e).__name__}: {e})') from None
    if bad is not None:
        raise Retry(f'архив повреждён: CRC записи {bad} не сходится')
    if member not in names:
        raise FetchError(f'{path.name.removesuffix(".part")}: в архиве нет {member} (есть: {", ".join(names[:5])})')


def sri_sha512(integrity):
    """Сводки sha512 из SRI («sha512-<base64>», через пробел может быть несколько)."""
    out = []
    for token in (integrity or '').split():
        alg, _, value = token.partition('-')
        if alg != 'sha512':
            continue
        try:
            digest = base64.b64decode(value.split('?', 1)[0], validate=True)
        except (binascii.Error, ValueError):
            continue
        if len(digest) == 64:
            out.append(digest)
    if not out:
        raise FetchError(f'dist.integrity без sha512: «{integrity}»')
    return out


def file_digest(path, algorithm):
    h = hashlib.new(algorithm)
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(READ_CHUNK), b''):
            h.update(chunk)
    return h


def check_sparql(body):
    """Ответ SPARQL JSON: results.bindings — список объектов; иначе — Retry."""
    obj = parse_json(body)
    try:
        bindings = obj['results']['bindings']
    except (KeyError, TypeError):
        raise Retry('ответ SPARQL без results.bindings') from None
    if not isinstance(bindings, list) or not all(isinstance(b, dict) for b in bindings):
        raise Retry('results.bindings — не список объектов')
    return bindings


# ---- разбор GeoNames ----

def tsv_rows(body, name, columns):
    """Строки данных текста GeoNames (без «#» и пустых), разбитые по табуляции; столбцов не columns — FetchError."""
    try:
        text = body.decode('utf-8')
    except UnicodeDecodeError as e:
        raise FetchError(f'{name}: не UTF-8 ({e})') from None
    rows = []
    for n, line in enumerate(text.split('\n'), 1):
        line = line.rstrip('\r')
        if not line or line.startswith('#'):
            continue
        cols = line.split('\t')
        if len(cols) != columns:
            raise FetchError(f'{name}, строка {n}: столбцов {len(cols)}, ждали {columns} — формат источника изменился')
        rows.append(cols)
    return rows


def is_digits(s):
    return s.isascii() and s.isdigit()


def scan_geonames(path, member, capitals, found, gids):
    """Строки выгрузки GeoNames (cities500.txt, <CC>.txt) в архиве path: их число; geonameid класса P — в gids;
    страны, у которых нашлась строка столицы (класс P, код не из CITY_CODES_DROPPED, и код PPLC или имя =
    Capital из countryInfo), — в found."""
    rows = 0
    try:
        with zipfile.ZipFile(path) as zf, zf.open(member) as raw:
            for n, line in enumerate(io.TextIOWrapper(raw, encoding='utf-8', newline='\n'), 1):
                line = line.rstrip('\n').rstrip('\r')
                if not line:
                    continue
                cols = line.split('\t')
                if len(cols) != GEONAME_COLUMNS or not is_digits(cols[0]):
                    raise FetchError(f'{path.name}/{member}, строка {n}: столбцов {len(cols)}, ждали '
                                     f'{GEONAME_COLUMNS} и geonameid числом — формат источника изменился')
                rows += 1
                if cols[6] != 'P':
                    continue
                gids.add(int(cols[0]))
                cc = cols[8]
                capital = capitals.get(cc)
                if capital and cc not in found and cols[7] not in CITY_CODES_DROPPED and (
                        cols[7] == 'PPLC' or capital == cols[1] or capital == cols[2] or capital in cols[3].split(',')):
                    found.add(cc)
    except UnicodeDecodeError as e:
        raise FetchError(f'{path.name}/{member}: не UTF-8 ({e})') from None
    return rows


# ---- разбор Wikidata ----

def value_of(b, name, required=True):
    v = b.get(name)
    if not isinstance(v, dict) or 'value' not in v:
        if required:
            raise FetchError(f'Wikidata: в строке ответа нет ?{name}: {json.dumps(b, ensure_ascii=False)[:200]}')
        return ''
    return str(v['value'])


def qid(uri):
    """http://www.wikidata.org/entity/Q42 → Q42; не item — None."""
    for prefix in ENTITY_PREFIXES:
        if uri.startswith(prefix):
            q = uri[len(prefix):]
            return q if QID.fullmatch(q) else None
    return None


def need_qid(b, name):
    uri = value_of(b, name)
    q = qid(uri)
    if q is None:
        raise FetchError(f'Wikidata: ?{name} = «{uri}» — не item Q…')
    return q


def qnum(q):
    return int(q[1:])


def csv_bytes(header, rows):
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator='\n', quoting=csv.QUOTE_MINIMAL)
    w.writerow(header)
    w.writerows(rows)
    return buf.getvalue().encode('utf-8')


# ---- CLDR ----

def cldr_wanted(package, rel):
    if package == 'cldr-core':
        return rel in CLDR_CORE_FILES
    return rel in CLDR_NAMES_FILES or CLDR_LOCALE_FILE.fullmatch(rel) is not None


def extract_cldr(tgz, package):
    """Нужные файлы tarball npm: {путь без package/: байты}. Путь с «..», абсолютный или с «\\» — FetchError;
    нужный файл — не обычный (ссылка, папка, устройство) или дважды — FetchError."""
    out = {}
    name = tgz.name
    try:
        with tarfile.open(tgz, mode='r:gz') as tf:
            for m in tf:
                parts = m.name.split('/')
                if m.name.startswith('/') or '\\' in m.name or '..' in parts or ':' in parts[0]:
                    raise FetchError(f'{name}: небезопасный путь в архиве: «{m.name}»')
                if not m.name.startswith('package/'):
                    continue
                rel = m.name[len('package/'):]
                if not cldr_wanted(package, rel):
                    continue
                if not m.isreg():
                    raise FetchError(f'{name}: {m.name} — не обычный файл')
                if rel in out:
                    raise FetchError(f'{name}: {m.name} — в архиве дважды')
                f = tf.extractfile(m)
                if f is None:
                    raise FetchError(f'{name}: {m.name} не читается')
                out[rel] = f.read()
    except (tarfile.TarError, EOFError, zlib.error, OSError) as e:
        raise FetchError(f'{name}: tarball не читается ({type(e).__name__}: {e})') from None
    required = CLDR_CORE_FILES if package == 'cldr-core' else CLDR_NAMES_FILES + CLDR_NAMES_REQUIRED
    missing = [r for r in required if r not in out]
    if missing:
        raise FetchError(f'{name}: в архиве нет {", ".join("package/" + r for r in missing)}')
    try:
        pkg = json.loads(out['package.json'].decode('utf-8'))
    except (UnicodeDecodeError, ValueError) as e:
        raise FetchError(f'{name}: package/package.json не JSON ({e})') from None
    if not isinstance(pkg, dict) or pkg.get('name') != package or pkg.get('version') != CLDR_VERSION:
        raise FetchError(f'{name}: в package.json не {package}@{CLDR_VERSION}')
    return out


# ---- снимок ----

class Config:
    """Настройки прогона — из переменных среды (см. описание модуля)."""

    def __init__(self, env):
        def base(key, default):
            v = env.get(key) or default
            return v if v.endswith('/') else v + '/'

        self.geonames = base('WLD_GEONAMES_BASE', DEFAULT_GEONAMES)
        self.worldbank = base('WLD_WORLDBANK_BASE', DEFAULT_WORLDBANK)
        self.npm = base('WLD_NPM_BASE', DEFAULT_NPM)
        self.sparql = env.get('WLD_WIKIDATA_SPARQL') or DEFAULT_SPARQL
        raw = (env.get('WLD_FETCH_BACKOFF') or '1').strip()
        try:
            self.backoff = float(raw)
        except ValueError:
            raise FetchError(f'WLD_FETCH_BACKOFF=«{raw}» — нужно число (1 — обычные паузы, 0 — без пауз)') from None
        if not self.backoff >= 0:
            raise FetchError(f'WLD_FETCH_BACKOFF=«{raw}» — нужно число не меньше 0')
        self.minimums = (env.get('WLD_FETCH_MIN') or '1').strip() != '0'
        at = (env.get('WLD_FETCHED_AT') or '').strip()
        if at:
            try:
                ok = FETCHED_AT_RE.fullmatch(at) and datetime.datetime.strptime(at, '%Y-%m-%dT%H:%M:%SZ')
            except ValueError:
                ok = False
            if not ok:
                raise FetchError(f'WLD_FETCHED_AT=«{at}» — нужно время UTC вида 2026-11-23T10:00:00Z')
            self.fetched_at = at
        else:
            self.fetched_at = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


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
        self.skipped = []
        self.info = {}

    def path(self, rel):
        p = self.tree.joinpath(*rel.split('/'))
        p.parent.mkdir(parents=True, exist_ok=True)
        return p

    def put(self, rel, data, url, rows=None):
        self.path(rel).write_bytes(data)
        self.files[rel] = {'url': url, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'rows': rows}

    def record(self, rel, url, rows=None):
        p = self.path(rel)
        self.files[rel] = {'url': url, 'bytes': p.stat().st_size, 'sha256': file_digest(p, 'sha256').hexdigest(),
                           'rows': rows}

    def at_least(self, what, have, need):
        if self.cfg.minimums and have < need:
            raise FetchError(f'{what}: {num(have)} — меньше нижней границы {num(need)}; источник отдал не всё? '
                             f'(WLD_FETCH_MIN=0 — без этой проверки)')

    def logged(self, source, rel, extra=''):
        f = self.files[rel]
        rows = '' if f['rows'] is None else f', строк {num(f["rows"])}'
        log(f'{source}: {rel} — {num(f["bytes"])} байт{rows}{extra}')


def fetch_geonames(snap):
    """readme, countryInfo, admin1, cities500; для стран без строки столицы в cities500 — <CC>.zip.
    Итог — geonameid класса P (для Wikidata)."""
    base = snap.cfg.geonames
    url = base + 'readme.txt'
    snap.put('geonames/readme.txt', snap.http.fetch(url).body, url)
    snap.logged('GeoNames', 'geonames/readme.txt')

    url = base + 'countryInfo.txt'
    body = snap.http.fetch(url).body
    countries = tsv_rows(body, 'countryInfo.txt', COUNTRY_INFO_COLUMNS)
    snap.put('geonames/countryInfo.txt', body, url, len(countries))
    snap.logged('GeoNames', 'geonames/countryInfo.txt')
    snap.at_least('GeoNames countryInfo.txt: строк данных', len(countries), MIN_COUNTRY_INFO)
    capitals = {}
    for cols in countries:
        if not re.fullmatch(r'[A-Z]{2}', cols[0]):
            raise FetchError(f'countryInfo.txt: код страны «{cols[0]}» — не две латинские буквы')
        capitals[cols[0]] = cols[5]  # сравнение — с Capital как есть, как в правилах сборки

    url = base + 'admin1CodesASCII.txt'
    body = snap.http.fetch(url).body
    admin1 = tsv_rows(body, 'admin1CodesASCII.txt', ADMIN1_COLUMNS)
    snap.put('geonames/admin1CodesASCII.txt', body, url, len(admin1))
    snap.logged('GeoNames', 'geonames/admin1CodesASCII.txt')
    snap.at_least('GeoNames admin1CodesASCII.txt: строк', len(admin1), MIN_ADMIN1)

    url = base + 'cities500.zip'
    rel = 'geonames/cities500.zip'
    snap.http.fetch(url, dest=snap.path(rel), check=lambda p: check_zip(p, 'cities500.txt'))
    found, gids = set(), set()
    rows = scan_geonames(snap.path(rel), 'cities500.txt', capitals, found, gids)
    snap.record(rel, url, rows)
    snap.logged('GeoNames', rel, f' (класс P — {num(len(gids))})')
    snap.at_least('GeoNames cities500.txt: строк', rows, MIN_CITIES500)

    missing = sorted(cc for cc, capital in capitals.items() if capital and cc not in found)
    log(f'GeoNames: стран, у которых строка столицы из countryInfo есть в cities500, — {num(len(found))}; нет — '
        + (f'{num(len(missing))}: {" ".join(missing)}' if missing else '0'))
    loaded = []
    for cc in missing:
        url = f'{base}{cc}.zip'
        rel = f'geonames/capitals/{cc}.zip'
        size = None
        try:
            size = snap.http.fetch(url, method='HEAD').length
        except FetchError as e:
            if e.status not in (405, 501):  # HEAD не поддержан — тогда размер узнаем по ходу скачивания
                raise
        if size is not None and size > CAPITAL_ZIP_MAX:
            skip(snap, rel, url, size)
            continue
        try:
            snap.http.fetch(url, dest=snap.path(rel), limit=CAPITAL_ZIP_MAX,
                            check=lambda p, m=f'{cc}.txt': check_zip(p, m))
        except TooBig as e:
            skip(snap, rel, url, e.size)
            continue
        before = len(gids)
        rows = scan_geonames(snap.path(rel), f'{cc}.txt', capitals, found, gids)
        snap.record(rel, url, rows)
        loaded.append(cc)
        snap.logged('GeoNames', rel, f'; новых geonameid класса P — {num(len(gids) - before)}; строка столицы '
                    + ('есть' if cc in found else 'не нашлась и здесь'))
    capitals_dir = snap.tree / 'geonames' / 'capitals'
    if capitals_dir.is_dir() and not any(capitals_dir.iterdir()):
        capitals_dir.rmdir()
    snap.info['geonames'] = {'missing': missing, 'loaded': loaded, 'gids': len(gids)}
    return gids


def skip(snap, rel, url, size):
    mb = CAPITAL_ZIP_MAX // (1024 * 1024)
    reason = f'больше {mb} МБ' + (f' (Content-Length {size})' if size is not None else ' (оборвано на скачивании)')
    snap.skipped.append({'path': rel, 'url': url, 'bytes': size, 'reason': reason})
    log(f'GeoNames: {rel} — не скачан: {reason}')


class Inconsistent(Exception):
    """Страницы одного ряда World Bank не сходятся между собой."""


def wb_pages(http_client, url):
    """Все страницы ряда: (ответы страниц, все строки). Шапки страниц и сумма строк сверяются с первой страницей."""
    first = http_client.fetch(f'{url}&page=1', check=check_wb_page).value
    h = first[0]
    page, pages, total = int(h['page']), int(h['pages']), int(h['total'])
    if page != 1:
        raise Inconsistent(f'на запрос page=1 пришла страница {page}')
    objs = [first]
    for p in range(2, pages + 1):
        obj = http_client.fetch(f'{url}&page={p}', check=check_wb_page).value
        hp = obj[0]
        got = (int(hp['page']), int(hp['pages']), int(hp['total']))
        if got != (p, pages, total) or hp.get('lastupdated') != h.get('lastupdated'):
            raise Inconsistent(f'страница {p}: page/pages/total = {got[0]}/{got[1]}/{got[2]}, lastupdated '
                               f'{hp.get("lastupdated")}; на первой — 1/{pages}/{total}, {h.get("lastupdated")}')
        objs.append(obj)
    rows = [r for o in objs for r in (o[1] or [])]
    if len(rows) != total:
        raise Inconsistent(f'сумма строк по страницам (pages = {pages}) — {len(rows)}, в шапке total = {total}')
    return objs, rows


def wb_series(snap, url, rel):
    """Ряд World Bank постранично → .jsonl (одна строка — один ответ страницы, записанный заново)."""
    for attempt in range(1, WB_PASSES + 1):
        try:
            objs, rows = wb_pages(snap.http, url)
            break
        except Inconsistent as e:
            if attempt == WB_PASSES:
                raise FetchError(f'{url}: страницы не сходятся (проходов подряд: {WB_PASSES}) — {e}') from None
            log(f'  World Bank: {url}: {e} — заново с первой страницы')
            snap.http.pause(BACKOFF[attempt - 1])
    lines = [json.dumps(o, ensure_ascii=False, separators=(',', ':')) for o in objs]
    snap.put(rel, ''.join(line + '\n' for line in lines).encode('utf-8'), url, len(rows))
    return objs, rows


def find_license(obj):
    """Поля лицензии в метаданных World Bank: объекты, у которых id (или name) — License_Type / License_URL
    (без учёта регистра и знаков), их value. Итог — (тип, адрес), ненайденное — None."""
    found = {}

    def walk(o):
        if isinstance(o, dict):
            key = o.get('id') if isinstance(o.get('id'), str) else o.get('name')
            if isinstance(key, str):
                k = re.sub(r'[^a-z]', '', key.lower())
                v = o.get('value')
                if k in ('licensetype', 'licenseurl') and k not in found and isinstance(v, str) and v.strip():
                    found[k] = ' '.join(v.split())
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    walk(obj)
    return found.get('licensetype'), found.get('licenseurl')


def indicator_name(obj):
    try:
        name = obj[1][0]['name']
    except (KeyError, IndexError, TypeError):
        return None
    return name if isinstance(name, str) else None


def fetch_worldbank(snap):
    base = snap.cfg.worldbank
    url = f'{base}country?format=json&per_page={WB_COUNTRIES_PER_PAGE}'
    objs, rows = wb_series(snap, url, 'worldbank/countries.jsonl')
    economies = sum(1 for r in rows
                    if not (isinstance(r.get('region'), dict) and r['region'].get('value') == 'Aggregates'))
    snap.logged('World Bank', 'worldbank/countries.jsonl', f', страниц {len(objs)}, не агрегатов {num(economies)}')
    snap.at_least('World Bank countries: стран (не агрегатов)', economies, MIN_WB_COUNTRIES)
    info = {'countries': {'url': url, 'pages': len(objs), 'rows': len(rows), 'economies': economies},
            'indicators': []}
    for ind in WB_INDICATORS:
        url = (f'{base}country/all/indicator/{ind}?format=json&per_page={WB_DATA_PER_PAGE}'
               f'&date={WB_FIRST_YEAR}:{snap.year}')
        rel = f'worldbank/{ind}.jsonl'
        objs, rows = wb_series(snap, url, rel)
        with_value = len({r.get('countryiso3code') for r in rows
                          if r.get('value') is not None and r.get('countryiso3code')})
        years = sorted({str(r.get('date')) for r in rows if r.get('value') is not None})
        snap.logged('World Bank', rel, f', страниц {len(objs)}, кодов со значением {num(with_value)}'
                    + (f', годы {span(years[0], years[-1])}' if years else ''))
        snap.at_least(f'World Bank {ind}: countryiso3code со значением', with_value, MIN_WB_INDICATOR)
        item = {'id': ind, 'url': url, 'pages': len(objs), 'rows': len(rows),
                'lastupdated': objs[0][0].get('lastupdated'), 'years': (years[0], years[-1]) if years else None}

        url = f'{base}indicator/{ind}?format=json'
        rel = f'worldbank/indicators/{ind}.json'
        r = snap.http.fetch(url, check=check_wb_json)
        snap.put(rel, r.body, url)
        snap.logged('World Bank', rel)
        item['name'] = indicator_name(r.value)

        url = f'{base}sources/2/series/{ind}/metadata?format=json'
        rel = f'worldbank/metadata/{ind}.json'
        r = snap.http.fetch(url, check=check_wb_json)
        snap.put(rel, r.body, url)
        item['license_type'], item['license_url'] = find_license(r.value)
        snap.logged('World Bank', rel, f'; License_Type: {item["license_type"] or "—"}, '
                    f'License_URL: {item["license_url"] or "—"}')
        info['indicators'].append(item)
    snap.info['worldbank'] = info


def fetch_cldr(snap):
    info = []
    for package in CLDR_PACKAGES:
        url = f'{snap.cfg.npm}{package}/{CLDR_VERSION}'
        meta = snap.http.fetch(url, headers={'Accept': 'application/json'}, check=parse_json).value
        if not isinstance(meta, dict) or meta.get('name') != package or meta.get('version') != CLDR_VERSION:
            raise FetchError(f'{url}: ответ registry — не {package}@{CLDR_VERSION}')
        dist = meta.get('dist') if isinstance(meta.get('dist'), dict) else {}
        tarball, integrity = dist.get('tarball'), dist.get('integrity')
        if not isinstance(tarball, str) or urllib.parse.urlsplit(tarball).scheme not in ('http', 'https'):
            raise FetchError(f'{url}: в dist нет адреса tarball')
        digests = sri_sha512(integrity if isinstance(integrity, str) else '')

        def check_tgz(p, digests=digests):
            if file_digest(p, 'sha512').digest() not in digests:
                raise Retry('sha512 tarball не совпал с dist.integrity из registry')

        rel = f'cldr/npm/{package}-{CLDR_VERSION}.tgz'
        snap.http.fetch(tarball, dest=snap.path(rel), check=check_tgz)
        snap.record(rel, tarball)
        snap.logged('CLDR', rel, ', sha512 сошёлся с dist.integrity')
        files = extract_cldr(snap.path(rel), package)
        for name in sorted(files):
            snap.put(f'cldr/{package}/{name}', files[name], f'{tarball}#package/{name}')
        locales = {}
        for name in files:
            m = CLDR_LOCALE_FILE.fullmatch(name)
            if m:
                locales.setdefault(m.group(2), set()).add(m.group(1))
        counts = {kind: len(v) for kind, v in sorted(locales.items())}
        log(f'CLDR: cldr/{package}/ — извлечено файлов {num(len(files))}'
            + ''.join(f', локалей с {kind}.json — {num(n)}' for kind, n in counts.items()))
        info.append({'package': package, 'tarball': tarball, 'integrity': integrity,
                     'files': sorted(files), 'locales': counts})
    copyright_line = None
    license_text = (snap.tree / 'cldr' / 'cldr-core' / 'LICENSE').read_text(encoding='utf-8', errors='replace')
    for line in license_text.splitlines():
        if line.strip().startswith('Copyright'):
            copyright_line = line.strip()
            break
    snap.info['cldr'] = {'packages': info, 'copyright': copyright_line}


def sparql(snap, query, label):
    data = urllib.parse.urlencode({'query': query}).encode('ascii')
    return snap.http.fetch(snap.cfg.sparql, method='POST', data=data, label=f'Wikidata {label}', check=check_sparql,
                           headers={'Accept': 'application/sparql-results+json',
                                    'Content-Type': 'application/x-www-form-urlencoded'}).value


def fetch_wikidata(snap, gids):
    endpoint = snap.cfg.sparql
    for name, text in QUERIES.items():
        snap.put(f'wikidata/queries/{name}.rq', text.encode('utf-8'), None)

    ordered = sorted(gids)
    chunks = [ordered[i:i + WIKIDATA_CHUNK] for i in range(0, len(ordered), WIKIDATA_CHUNK)]
    log(f'Wikidata: cities_ru — {num(len(ordered))} geonameid, частей {num(len(chunks))} по {WIKIDATA_CHUNK}')
    rows = []
    for i, chunk in enumerate(chunks, 1):
        if i > 1:
            snap.http.pause(WIKIDATA_PAUSE)
        query = QUERIES['cities_ru'].replace(GEONAMEIDS, ' '.join(f'"{g}"' for g in chunk))
        for b in sparql(snap, query, f'cities_ru, часть {i}/{len(chunks)}'):
            gid = value_of(b, 'gid')
            if not is_digits(gid):
                raise FetchError(f'Wikidata cities_ru: ?gid = «{gid}» — не число')
            item = need_qid(b, 'item')
            rows.append((int(gid), qnum(item), item, value_of(b, 'ru')))
        if i % WIKIDATA_PROGRESS == 0 or i == len(chunks):
            log(f'Wikidata: cities_ru — частей {num(i)}/{num(len(chunks))}, строк {num(len(rows))}')
    rows.sort()
    snap.put('wikidata/cities_ru.csv', csv_bytes(('geonameid', 'item', 'label_ru'),
                                                 [(g, item, ru) for g, _, item, ru in rows]), endpoint, len(rows))
    snap.logged('Wikidata', 'wikidata/cities_ru.csv', f', geonameid с названием {num(len({r[0] for r in rows}))}')
    snap.at_least('Wikidata cities_ru: строк', len(rows), MIN_CITIES_RU)

    snap.http.pause(WIKIDATA_PAUSE)
    rows = []
    for b in sparql(snap, QUERIES['country_inception'], 'country_inception'):
        item = need_qid(b, 'item')
        iso3 = value_of(b, 'iso3')
        inception = value_of(b, 'inception', required=False)
        precision = value_of(b, 'precision', required=False)
        rank = value_of(b, 'rank', required=False)
        if precision:
            if not re.fullmatch(r'-?[0-9]+', precision):
                raise FetchError(f'Wikidata country_inception: ?precision = «{precision}» — не число')
            precision = str(int(precision))
        if rank:
            if rank not in RANKS:
                raise FetchError(f'Wikidata country_inception: ?rank = «{rank}» — неизвестный ранг')
            rank = RANKS[rank]
        rows.append((iso3, qnum(item), inception, int(precision) if precision else -1, rank, item, precision))
    rows.sort()
    items = len({r[5] for r in rows})
    snap.put('wikidata/country_inception.csv',
             csv_bytes(('iso3', 'item', 'inception', 'precision', 'rank'),
                       [(r[0], r[5], r[2], r[6], r[4]) for r in rows]), endpoint, len(rows))
    snap.logged('Wikidata', 'wikidata/country_inception.csv', f', item {num(items)}')
    snap.at_least('Wikidata country_inception: item', items, MIN_INCEPTION_ITEMS)
    inception_items = items

    snap.http.pause(WIKIDATA_PAUSE)
    rows = []
    other = 0
    for b in sparql(snap, QUERIES['country_types'], 'country_types'):
        item = need_qid(b, 'item')
        kind = qid(value_of(b, 'type'))
        if kind is None:  # «неизвестное значение» и прочее не-item: у него нет номера Q
            other += 1
            continue
        rows.append((value_of(b, 'iso3'), qnum(item), qnum(kind), item, kind))
    rows.sort()
    snap.put('wikidata/country_types.csv', csv_bytes(('iso3', 'item', 'type'), [(r[0], r[3], r[4]) for r in rows]),
             endpoint, len(rows))
    snap.logged('Wikidata', 'wikidata/country_types.csv', f', значений P31 не item: {other}' if other else '')

    snap.http.pause(WIKIDATA_PAUSE)
    rows = []
    for b in sparql(snap, QUERIES['languages'], 'languages'):
        item = need_qid(b, 'item')
        rows.append((value_of(b, 'code'), qnum(item), item, value_of(b, 'en', required=False),
                     value_of(b, 'ru', required=False)))
    rows.sort()
    snap.put('wikidata/languages.csv', csv_bytes(('code', 'item', 'label_en', 'label_ru'),
                                                 [(r[0], r[2], r[3], r[4]) for r in rows]), endpoint, len(rows))
    snap.logged('Wikidata', 'wikidata/languages.csv', f', кодов {num(len({r[0] for r in rows}))}')
    snap.at_least('Wikidata languages: строк', len(rows), MIN_LANGUAGES)
    snap.info['wikidata'] = {'gids': len(ordered), 'chunks': len(chunks), 'inception_items': inception_items}


# ---- SOURCE.md, fetch.json, SHA256SUMS, архив ----

def cell(v):
    if v is None or v == '':
        return '—'
    return str(v).replace('|', '\\|').replace('\n', ' ')


def span(first, last):
    """Годы «2001–2025», один год — «2025»."""
    return first if first == last else f'{first}–{last}'


def rows_cell(snap, rel):
    rows = snap.files[rel]['rows']
    return '—' if rows is None else num(rows)


def source_md(snap):
    f = snap.files
    g = snap.info['geonames']
    wb = snap.info['worldbank']
    cl = snap.info['cldr']
    wd = snap.info['wikidata']
    cfg = snap.cfg
    out = [
        f'# {snap.name} — снимок источников набора World',
        '',
        f'Снимок открытых источников, из которых собирается набор `wld-data-v1`. Время снимка — **{snap.fetched_at}** (UTC).',
        '',
        f'Архив `{snap.name}.zip` — без общей папки. Файлы GeoNames, описания и метаданные World Bank, tarball\'ы CLDR —',
        'как их отдали источники; ряды World Bank — по страницам в `.jsonl`; ответы Wikidata — в CSV. `SHA256SUMS` — суммы',
        'всех прочих файлов архива; `fetch.json` — у каждого файла адрес, байты, sha256 и число строк. Собран',
        '`wld/source/fetch.py` (workflow `source` по тегу снимка).',
        '',
        '## GeoNames',
        '',
        f'Выгрузки справочника GeoNames — {cfg.geonames}.',
        '',
        '| файл | адрес | строк | байт |',
        '|---|---|---:|---:|',
    ]
    geonames_files = ['geonames/readme.txt', 'geonames/countryInfo.txt', 'geonames/admin1CodesASCII.txt',
                      'geonames/cities500.zip'] + [f'geonames/capitals/{cc}.zip' for cc in g['loaded']]
    for rel in geonames_files:
        out.append(f'| `{rel}` | {f[rel]["url"]} | {rows_cell(snap, rel)} | {num(f[rel]["bytes"])} |')
    out += [
        '',
        'Строки — строки данных (без строк-комментариев «#»); у `cities500.zip` — строки `cities500.txt` внутри, у',
        '`capitals/<CC>.zip` — строки `<CC>.txt`.',
        '',
    ]
    if g['missing']:
        out += ['`geonames/capitals/<CC>.zip` — выгрузки стран целиком: у этих стран в `cities500.txt` нет строки',
                'столицы из `countryInfo.txt` (класс P, кроме кодов PPLX, PPLH, PPLQ, PPLW, PPLCH, и код PPLC или имя,',
                'равное столице).']
        for s in snap.skipped:
            out.append(f'Не скачан `{s["path"]}` ({s["url"]}): {s["reason"]}.')
    else:
        out.append('Выгрузок стран (`geonames/capitals/`) нет: строка столицы каждой страны есть в `cities500.txt`.')
    out += [
        '',
        'Лицензия — CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Указание авторства: «GeoNames,',
        'https://www.geonames.org/».',
        '',
        '## World Bank Open Data',
        '',
        f'API v2 — {cfg.worldbank}. Страны — `country?format=json&per_page={WB_COUNTRIES_PER_PAGE}`, ряды показателей —',
        f'`country/all/indicator/<ID>?format=json&per_page={WB_DATA_PER_PAGE}&date={WB_FIRST_YEAR}:{snap.year}`, по',
        'страницам `&page=1` … `&page=<pages>`. В `.jsonl` одна строка — ответ одной страницы, `[шапка, строки]`,',
        'разобранный и записанный заново (JSON без пробелов, UTF-8); строки — сумма строк данных по страницам, она равна',
        '`total` из шапки. Описание показателя — `indicator/<ID>?format=json` (`worldbank/indicators/<ID>.json`),',
        'метаданные — `sources/2/series/<ID>/metadata?format=json` (`worldbank/metadata/<ID>.json`), оба как есть.',
        '',
        '| файл | адрес | страниц | строк |',
        '|---|---|---:|---:|',
        f'| `worldbank/countries.jsonl` | {wb["countries"]["url"]} | {wb["countries"]["pages"]} '
        f'| {num(wb["countries"]["rows"])} |',
    ]
    for it in wb['indicators']:
        out.append(f'| `worldbank/{it["id"]}.jsonl` | {it["url"]} | {it["pages"]} | {num(it["rows"])} |')
    out += [
        '',
        f'В `countries.jsonl` — {num(wb["countries"]["rows"])} записей, из них не агрегатов (`region.value` ≠ '
        f'`Aggregates`) — {num(wb["countries"]["economies"])}.',
        '',
        '| показатель | название | годы со значениями | обновлён | License_Type | License_URL |',
        '|---|---|---|---|---|---|',
    ]
    for it in wb['indicators']:
        see = f'см. `worldbank/metadata/{it["id"]}.json`'
        years = span(*it['years']) if it['years'] else '—'
        out.append(f'| `{it["id"]}` | {cell(it["name"])} | {years} | {cell(it["lastupdated"])} '
                   f'| {cell(it["license_type"]) if it["license_type"] else see} '
                   f'| {cell(it["license_url"]) if it["license_url"] else see} |')
    out += [
        '',
        'Лицензия — CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/); поля лицензии каждого показателя — в',
        'таблице выше, из его метаданных. Указание авторства: «World Bank Open Data, https://data.worldbank.org/».',
        '',
        f'## Unicode CLDR {CLDR_VERSION.split(".")[0]}',
        '',
        f'Пакеты npm {", ".join(f"`{p}@{CLDR_VERSION}`" for p in CLDR_PACKAGES)} из registry {cfg.npm}. Tarball — в',
        '`cldr/npm/` как есть, его sha512 сверен с `dist.integrity` из registry; нужные файлы извлечены рядом, без',
        'префикса `package/`.',
        '',
        '| пакет | tarball | dist.integrity | извлечено |',
        '|---|---|---|---|',
    ]
    for p in cl['packages']:
        if p['package'] == 'cldr-core':
            what = ', '.join(f'`{name}`' for name in p['files'])
        else:
            fixed = [name for name in p['files'] if not CLDR_LOCALE_FILE.fullmatch(name)]
            what = ', '.join(f'`{name}`' for name in fixed) + ', ' + ', '.join(
                f'`main/<локаль>/{kind}.json` (локалей: {num(n)})' for kind, n in p['locales'].items())
        out.append(f'| `{p["package"]}` | {p["tarball"]} | `{cell(p["integrity"])}` | {what} |')
    out += [
        '',
        'Лицензия — Unicode License v3 (SPDX `Unicode-3.0`), полный текст — `cldr/cldr-core/LICENSE` (тот же — в',
        '`cldr/cldr-localenames-full/LICENSE`).' + (f' {cl["copyright"].rstrip(".")}.' if cl['copyright'] else ''),
        '',
        '## Wikidata',
        '',
        f'SPARQL — {cfg.sparql} (POST `query=…`, ответ `application/sparql-results+json`). Тексты запросов — в',
        '`wikidata/queries/`; ответы — в CSV (UTF-8, заголовок первой строкой; item и тип — номером `Q…`).',
        '',
        '| файл | запрос | что | строк |',
        '|---|---|---|---:|',
        f'| `wikidata/cities_ru.csv` | `cities_ru.rq` | `geonameid,item,label_ru` — русские названия item\'ов по коду '
        f'GeoNames (P1566): все geonameid класса P из `cities500.txt` и `capitals/` ({num(wd["gids"])}), частями по '
        f'{WIKIDATA_CHUNK} (запросов: {num(wd["chunks"])}) | {rows_cell(snap, "wikidata/cities_ru.csv")} |',
        f'| `wikidata/country_inception.csv` | `country_inception.rq` | `iso3,item,inception,precision,rank` — '
        f'утверждения P571 у item\'ов с кодом ISO 3166-1 alpha-3 (P298) (item\'ов: {num(wd["inception_items"])}); item '
        f'без P571 — строка с пустыми полями | {rows_cell(snap, "wikidata/country_inception.csv")} |',
        f'| `wikidata/country_types.csv` | `country_types.rq` | `iso3,item,type` — P31 тех же item\'ов '
        f'| {rows_cell(snap, "wikidata/country_types.csv")} |',
        f'| `wikidata/languages.csv` | `languages.rq` | `code,item,label_en,label_ru` — item\'ы с кодом языка ISO 639-3 '
        f'(P220), их метки на английском и русском; метки нет — поле пустое '
        f'| {rows_cell(snap, "wikidata/languages.csv")} |',
        '',
        'Лицензия данных — CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/).',
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
    if snap.skipped:
        lines.append('  "skipped": [')
        for i, s in enumerate(snap.skipped):
            lines.append(f'    {json.dumps(s, ensure_ascii=False)}' + (',' if i < len(snap.skipped) - 1 else ''))
        lines.append('  ]')
    else:
        lines.append('  "skipped": []')
    lines.append('}')
    return ('\n'.join(lines) + '\n').encode('utf-8')


def tree_files(tree):
    out = []
    for dirpath, _, filenames in os.walk(tree):
        for fn in filenames:
            out.append(pathlib.Path(dirpath, fn).relative_to(tree).as_posix())
    return sorted(out, key=lambda s: s.encode('utf-8'))


def write_zip(tree, names, dest):
    """Детерминированный zip: записи в порядке names, время 1980-01-01, права 0644; .zip/.tgz — STORED."""
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
    sums = ''.join(f'{file_digest(snap.tree.joinpath(*n.split("/")), "sha256").hexdigest()}  {n}\n' for n in names)
    snap.path('SHA256SUMS').write_bytes(sums.encode('utf-8'))
    names = tree_files(snap.tree)
    out.mkdir(parents=True, exist_ok=True)
    dest = out / f'{snap.name}.zip'
    write_zip(snap.tree, names, dest)
    for name in ('SOURCE.md', 'SHA256SUMS'):
        (out / name).write_bytes((snap.tree / name).read_bytes())
    log(f'архив: {dest} — {num(dest.stat().st_size)} байт, файлов {num(len(names))}, '
        f'sha256 {file_digest(dest, "sha256").hexdigest()}')


def run(args, cfg):
    m = SNAPSHOT_RE.fullmatch(args.snapshot)
    if not m:
        raise FetchError(f'снимок «{args.snapshot}» — не wld-source-YYYYMMDD')
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
        tmp = tempfile.mkdtemp(prefix='wld-source-')
        work = pathlib.Path(tmp)
    tree = work / args.snapshot
    try:
        if tree.exists():
            shutil.rmtree(tree)
        tree.mkdir(parents=True)
        snap = Snapshot(args.snapshot, year, cfg, tree, Http(cfg.backoff))
        log(f'снимок {snap.name}: время {snap.fetched_at}, ряды World Bank {WB_FIRST_YEAR}–{year}, дерево {tree}')
        gids = fetch_geonames(snap)
        fetch_worldbank(snap)
        fetch_cldr(snap)
        fetch_wikidata(snap, gids)
        finish(snap, out)
    finally:
        if tmp is not None:
            shutil.rmtree(tmp, ignore_errors=True)
    return 0


def parse_args(argv):
    p = argparse.ArgumentParser(prog='fetch.py', description='Снимок источников набора World (wld-data-v1).')
    p.add_argument('--snapshot', required=True, help='имя снимка — тег выпуска: wld-source-YYYYMMDD')
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
