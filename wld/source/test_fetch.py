"""Тесты wld/source/fetch.py без сети: локальный HTTP-сервер на 127.0.0.1 отдаёт маленькие ответы в форматах настоящих
источников — GeoNames (countryInfo.txt с шапкой «#», cities500.zip, admin1CodesASCII.txt, <CC>.zip), World Bank
(страницы [шапка, строки], ответы-ошибки), npm registry (метаданные версии и tarball с dist.integrity), Wikidata SPARQL
(results.bindings).

Запуск (из корня репозитория):
  NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \\
    python3 -X utf8 -I -m unittest discover -s wld/source -p 'test_*.py' -v
"""
import base64
import contextlib
import functools
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import tarfile
import tempfile
import threading
import unittest
import urllib.parse
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
SNAPSHOT = 'wld-source-20261123'
FETCHED_AT = '2026-11-23T10:00:00Z'
UA = 'HammerhallData/1.0 (https://github.com/Hammerhall/hammerhall-data)'


def load_fetch():
    spec = importlib.util.spec_from_file_location('wld_source_fetch', HERE / 'fetch.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fetch = load_fetch()


# ---- локальный сервер ----

class Reply:
    """Ответ сервера. length — свой Content-Length (оборванный ответ) или 'omit' — без него (до закрытия связи)."""

    def __init__(self, body=b'', status=200, headers=None, length=None):
        self.body = body
        self.status = status
        self.headers = headers or {}
        self.length = length


class Server:
    """ThreadingHTTPServer на свободном порту. Маршрут (метод, путь с запросом) → ответы по очереди: первые — по
    разу, последний — сколько угодно; ответ может быть функцией (путь, тело) → Reply. HEAD без своего маршрута — как
    GET, без тела и не тратя очередь."""

    def __init__(self):
        self.routes = {}
        self.requests = []
        self.lock = threading.Lock()
        self.httpd = ThreadingHTTPServer(('127.0.0.1', 0), self._handler())
        self.httpd.daemon_threads = True
        self.base = f'http://127.0.0.1:{self.httpd.server_address[1]}'
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def route(self, method, path, *replies):
        self.routes[(method, path)] = list(replies)

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def count(self, method, path):
        return sum(1 for r in self.requests if r[0] == method and r[1] == path)

    def _pick(self, method, path):
        with self.lock:
            replies = self.routes.get((method, path))
            if replies is None and method == 'HEAD':
                replies = self.routes.get(('GET', path))
                return replies[-1] if replies else None
            if not replies:
                return None
            return replies.pop(0) if len(replies) > 1 else replies[0]

    def _handler(self):
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.0'

            def log_message(self, *args):
                pass

            def serve(self, method):
                n = int(self.headers.get('Content-Length') or 0)
                body = self.rfile.read(n) if n else b''
                with server.lock:
                    server.requests.append((method, self.path, dict(self.headers.items()), body))
                reply = server._pick(method, self.path)
                if reply is None:
                    reply = Reply(b'not found', status=404)
                elif callable(reply):
                    reply = reply(self.path, body)
                self.send_response(reply.status)
                for k, v in reply.headers.items():
                    self.send_header(k, v)
                if reply.length != 'omit':
                    self.send_header('Content-Length', str(len(reply.body) if reply.length is None else reply.length))
                self.end_headers()
                if method != 'HEAD':
                    self.wfile.write(reply.body)

            def do_GET(self):
                self.serve('GET')

            def do_HEAD(self):
                self.serve('HEAD')

            def do_POST(self):
                self.serve('POST')

        return Handler


# ---- данные в форматах источников ----

COUNTRY_INFO_HEADER = (
    '# ================================\n'
    '# \n'
    '# \n'
    '# \n'
    '# CountryCodes:\n'
    '# ============\n'
    '# \n'
    '# The official ISO country code (ISO 3166-1 alpha-2) is used as country code in geonames.\n'
    '# \n'
    '#ISO\tISO3\tISO-Numeric\tfips\tCountry\tCapital\tArea(in sq km)\tPopulation\tContinent\ttld\tCurrencyCode\t'
    'CurrencyName\tPhone\tPostal Code Format\tPostal Code Regex\tLanguages\tgeonameid\tneighbours\tEquivalentFipsCode\n'
)
COUNTRIES = [
    ['AD', 'AND', '020', 'AN', 'Andorra', 'Andorra la Vella', '468', '77006', 'EU', '.ad', 'EUR', 'Euro', '376',
     'AD###', '^(?:AD)*(\\d{3})$', 'ca', '3041565', 'ES,FR', ''],
    ['AQ', 'ATA', '010', 'AY', 'Antarctica', '', '14000000', '0', 'AN', '.aq', '', '', '', '', '', '', '6697173', '',
     ''],
    ['BE', 'BEL', '056', 'BE', 'Belgium', 'Brussels', '30510', '11422068', 'EU', '.be', 'EUR', 'Euro', '32', '####',
     '^(\\d{4})$', 'nl-BE,fr-BE,de-BE', '2802361', 'DE,NL,LU,FR', ''],
    ['FR', 'FRA', '250', 'FR', 'France', 'Paris', '547030', '66987244', 'EU', '.fr', 'EUR', 'Euro', '33', '#####',
     '^(\\d{5})$', 'fr-FR,frp,br,co,ca,eu,oc', '3017382', 'CH,DE,BE,LU,IT,AD,MC,ES', ''],
    ['LU', 'LUX', '442', 'LU', 'Luxembourg', 'Luxembourg', '2586', '607728', 'EU', '.lu', 'EUR', 'Euro', '352',
     'L-####', '^(?:L-)?\\d{4}$', 'lb,de-LU,fr-LU', '2960313', 'DE,BE,FR', ''],
    ['MC', 'MCO', '492', 'MN', 'Monaco', 'Monaco', '1.95', '38682', 'EU', '.mc', 'EUR', 'Euro', '377', '#####',
     '^(\\d{5})$', 'fr-MC,en,it,mc', '2993457', 'FR', ''],
]


def geo(gid, name, alt, lat, lon, fclass, fcode, cc, admin1, population, tz):
    """Строка выгрузки GeoNames: 19 столбцов, как в cities500.txt и <CC>.txt."""
    return [str(gid), name, name, alt, lat, lon, fclass, fcode, cc, '', admin1, '', '', '', str(population), '',
            '100', tz, '2024-03-01']


CITIES500 = [
    geo(3041563, 'Andorra la Vella', 'ALV,Andorra la Vella,Andorre-la-Vieille', '42.50779', '1.52109', 'P', 'PPLC',
        'AD', '07', 20430, 'Europe/Andorra'),
    geo(2988507, 'Paris', 'Lutetia,Paname,Pariis', '48.85341', '2.3488', 'P', 'PPLC', 'FR', '11', 2138551,
        'Europe/Paris'),
    geo(702550, 'Nice', 'Nica,Nizza', '43.70313', '7.26608', 'P', 'PPLA2', 'FR', '93', 338620, 'Europe/Paris'),
    geo(2800866, 'Bruxelles', 'Brussel,Brussels,Bruxelas', '50.85045', '4.34878', 'P', 'PPL', 'BE', 'BRU', 1019022,
        'Europe/Brussels'),
    geo(2960316, 'Esch-sur-Alzette', 'Esch,Esch-Uelzecht', '49.49583', '5.98056', 'P', 'PPLA', 'LU', 'ES', 36228,
        'Europe/Luxembourg'),
    geo(3039163, 'Hotel Test', '', '42.5', '1.5', 'S', 'HTL', 'AD', '07', 0, 'Europe/Andorra'),
]
MC_ROWS = [
    geo(2993458, 'Monaco', 'Monako,Monaco-Ville', '43.73333', '7.41667', 'P', 'PPLC', 'MC', '00', 32965,
        'Europe/Monaco'),
    geo(2992741, 'Monte-Carlo', 'Monte Carlo', '43.73976', '7.42732', 'P', 'PPLX', 'MC', '00', 15500,
        'Europe/Monaco'),
    geo(2993457, 'Principality of Monaco', 'Monaco', '43.73141', '7.41903', 'A', 'PCLI', 'MC', '00', 38682,
        'Europe/Monaco'),
]
P_GIDS = {3041563, 2988507, 702550, 2800866, 2960316, 2993458, 2992741}
ADMIN1 = ('AD.07\tAndorra la Vella\tAndorra la Vella\t3041566\n'
          'BE.BRU\tBrussels Capital\tBrussels Capital\t2800867\n'
          'FR.11\tÎle-de-France\tIle-de-France\t3012874\n'
          'FR.93\tProvence-Alpes-Côte d\'Azur\tProvence-Alpes-Cote d\'Azur\t2985244\n'
          'LU.ES\tEsch-sur-Alzette\tEsch-sur-Alzette\t2960314\n'
          'MC.00\tMonaco\tMonaco\t2993457\n')
README = 'Readme for GeoNames Gazetteer extract files\n\n============================================\n'
BIG_ZIP = 40 * 1024 * 1024


def tsv(rows):
    return ''.join('\t'.join(r) + '\n' for r in rows).encode('utf-8')


def make_zip(members):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as zf:
        for name, data in members:
            zf.writestr(name, data)
    return buf.getvalue()


INDICATOR_NAMES = {
    'SP.POP.TOTL': 'Population, total',
    'NY.GNP.MKTP.CD': 'GNI (current US$)',
    'NY.GDP.MKTP.CD': 'GDP (current US$)',
    'SP.DYN.LE00.IN': 'Life expectancy at birth, total (years)',
    'AG.SRF.TOTL.K2': 'Surface area (sq. km)',
}
WB_COUNTRIES = [
    ('AND', 'AD', 'Andorra', 'Europe & Central Asia'),
    ('BEL', 'BE', 'Belgium', 'Europe & Central Asia'),
    ('FRA', 'FR', 'France', 'Europe & Central Asia'),
    ('LUX', 'LU', 'Luxembourg', 'Europe & Central Asia'),
    ('MCO', 'MC', 'Monaco', 'Europe & Central Asia'),
    ('WLD', '1W', 'World', 'Aggregates'),
]


def wb_country(iso3, iso2, name, region):
    agg = region == 'Aggregates'
    return {'id': iso3, 'iso2Code': iso2, 'name': name,
            'region': {'id': 'NA' if agg else 'ECS', 'iso2code': 'NA' if agg else 'Z7', 'value': region},
            'adminregion': {'id': '', 'iso2code': '', 'value': ''},
            'incomeLevel': {'id': 'NA' if agg else 'HIC', 'iso2code': 'NA' if agg else 'XD',
                            'value': 'Aggregates' if agg else 'High income'},
            'lendingType': {'id': '', 'iso2code': '', 'value': 'Aggregates' if agg else 'Not classified'},
            'capitalCity': '', 'longitude': '', 'latitude': ''}


def wb_row(ind, iso3, iso2, name, year, value):
    return {'indicator': {'id': ind, 'value': INDICATOR_NAMES[ind]}, 'country': {'id': iso2, 'value': name},
            'countryiso3code': iso3, 'date': str(year), 'value': value, 'unit': '', 'obs_status': '', 'decimal': 0}


WB_VALUES = {
    'SP.POP.TOTL': [('AND', 2024, 81938), ('AND', 2025, 82904), ('FRA', 2025, 68516699), ('WLD', 2025, 8142056446),
                    ('MCO', 2025, None)],
    'NY.GNP.MKTP.CD': [('FRA', 2024, 3.1625e12), ('FRA', 2025, 3.2371e12), ('BEL', 2025, 6.9e11)],
    'NY.GDP.MKTP.CD': [('FRA', 2025, 3.16e12), ('LUX', 2025, 9.2847e10)],
    'SP.DYN.LE00.IN': [('FRA', 2023, 83.3317073170732), ('AND', 2023, 84.04)],
    'AG.SRF.TOTL.K2': [('AND', 2022, 470), ('FRA', 2022, 549087), ('MCO', 2022, 2.08)],
}
WB_ISO2 = {c[0]: (c[1], c[2]) for c in WB_COUNTRIES}


def wb_pages(rows, per_page, extra=None):
    """Страницы ответа World Bank: [шапка, строки] по per_page строк."""
    pages = max(1, -(-len(rows) // per_page))
    out = []
    for p in range(1, pages + 1):
        header = {'page': p, 'pages': pages, 'per_page': per_page, 'total': len(rows)}
        header.update(extra or {})
        out.append([header, rows[(p - 1) * per_page:p * per_page]])
    return out


def wb_metadata(ind, with_license=True):
    meta = [{'id': 'IndicatorName', 'value': INDICATOR_NAMES[ind]},
            {'id': 'Source', 'value': 'Test source'}]
    if with_license:
        meta += [{'id': 'License_Type', 'value': 'CC BY-4.0'},
                 {'id': 'License_URL', 'value': 'https://datacatalog.worldbank.org/public-licenses#cc-by'}]
    return {'page': 1, 'pages': 1, 'per_page': 50, 'total': 1,
            'source': [{'id': '2', 'name': 'World Development Indicators', 'lastupdated': '2026-10-28',
                        'concept': [{'id': 'Series', 'variable': [{'id': ind, 'metatype': meta}]}]}]}


def make_tgz(members):
    """tarball npm: (имя, байты) — обычный файл; (имя, None, тип) — не обычный."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w:gz') as tf:
        for m in members:
            info = tarfile.TarInfo(m[0])
            info.mtime = 499162500
            if m[1] is None:
                info.type = m[2]
                info.linkname = 'LICENSE' if m[2] in (tarfile.SYMTYPE, tarfile.LNKTYPE) else ''
                tf.addfile(info)
            else:
                info.size = len(m[1])
                info.mode = 0o644
                tf.addfile(info, io.BytesIO(m[1]))
    return buf.getvalue()


LICENSE = ('UNICODE LICENSE V3\n\nCOPYRIGHT AND PERMISSION NOTICE\n\nCopyright © 2001–2026 Unicode, Inc.\n\n'
           'Permission is hereby granted, free of charge, …\n\nSPDX-License-Identifier: Unicode-3.0\n').encode('utf-8')


def jb(obj):
    return json.dumps(obj, ensure_ascii=False, indent=2).encode('utf-8')


@functools.lru_cache(maxsize=None)
def tarballs():
    core = make_tgz([
        ('package/LICENSE', LICENSE),
        ('package/package.json', jb({'name': 'cldr-core', 'version': '49.0.0', 'license': 'Unicode-3.0'})),
        ('package/availableLocales.json', jb({'availableLocales': {'full': ['en', 'ru']}})),
        ('package/supplemental/aliases.json', jb({'supplemental': {}})),
        ('package/supplemental/territoryInfo.json', jb({'supplemental': {'territoryInfo': {'FR': {
            '_population': '68000000', 'languagePopulation': {'fr': {'_populationPercent': '100',
                                                                     '_officialStatus': 'official'}}}}}})),
        ('package/supplemental/territoryContainment.json', jb({'supplemental': {'territoryContainment': {
            '155': {'_contains': ['AT', 'BE', 'CH', 'DE', 'FR', 'LI', 'LU', 'MC', 'NL']}}}})),
        ('package/supplemental/languageData.json', jb({'supplemental': {'languageData': {'fr': {}}}})),
        ('package/supplemental/likelySubtags.json', jb({'supplemental': {'likelySubtags': {'fr': 'fr-Latn-FR'}}})),
    ])
    names = []
    for locale, kinds in (('en', ('territories', 'languages', 'scripts')), ('ru', ('territories', 'languages',
                                                                                   'scripts')),
                          ('zh-Hant', ('territories',)), ('de-AT', ('languages',))):
        for kind in kinds:
            names.append((f'package/main/{locale}/{kind}.json',
                          jb({'main': {locale: {'localeDisplayNames': {kind: {'FR': 'France'}}}}})))
        names.append((f'package/main/{locale}/localeDisplayNames.json', jb({'main': {locale: {}}})))
    localenames = make_tgz([('package/LICENSE', LICENSE),
                            ('package/package.json', jb({'name': 'cldr-localenames-full', 'version': '49.0.0'})),
                            ('package/README.md', b'# cldr-localenames-full\n'),
                            ('package/bower.json', b'{}\n')] + names)
    return {'cldr-core': core, 'cldr-localenames-full': localenames}


def sri(data):
    return 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode('ascii')


# Wikidata: ответы SPARQL JSON
CITY_LABELS = {
    3041563: [('Q1863', 'Андорра-ла-Велья')],
    2988507: [('Q90', 'Париж')],
    702550: [('Q33959', 'Ницца')],
    2800866: [('Q240', 'Брюссель'), ('Q9', 'Брюссель, "город"')],
    2993458: [('Q1000', 'Монако')],
}
INCEPTION = [
    ('Q228', 'AND', '1278-09-08T00:00:00Z', '11', 'Normal'),
    ('Q142', 'FRA', '0843-08-10T00:00:00Z', '11', 'Preferred'),
    ('Q142', 'FRA', '-0050-01-01T00:00:00Z', '9', 'Deprecated'),
    ('Q1001', 'FRA', '1792-09-22T00:00:00Z', '11', 'Normal'),
    ('Q31', 'BEL', '1830-10-04T00:00:00Z', '11', 'Normal'),
    ('Q32', 'LUX', None, None, None),
    ('Q235', 'MCO', '1297-01-08T00:00:00Z', '11', 'Normal'),
]
TYPES = [
    ('Q142', 'FRA', 'Q3624078'), ('Q142', 'FRA', 'Q6256'), ('Q142', 'FRA', 'Q185441'), ('Q1001', 'FRA', 'Q3024240'),
    ('Q228', 'AND', 'Q3624078'), ('Q31', 'BEL', 'Q3624078'), ('Q32', 'LUX', 'Q3624078'), ('Q235', 'MCO', 'Q3624078'),
    ('Q235', 'MCO', 'http://www.wikidata.org/.well-known/genid/0123456789abcdef'),
]
ENTITY = 'http://www.wikidata.org/entity/'
XSD = 'http://www.w3.org/2001/XMLSchema#'

EXPECTED_CITIES_RU = (
    'geonameid,item,label_ru\n'
    '702550,Q33959,Ницца\n'
    '2800866,Q9,"Брюссель, ""город"""\n'
    '2800866,Q240,Брюссель\n'
    '2988507,Q90,Париж\n'
    '2993458,Q1000,Монако\n'
    '3041563,Q1863,Андорра-ла-Велья\n')
EXPECTED_INCEPTION = (
    'iso3,item,inception,precision,rank\n'
    'AND,Q228,1278-09-08T00:00:00Z,11,normal\n'
    'BEL,Q31,1830-10-04T00:00:00Z,11,normal\n'
    'FRA,Q142,-0050-01-01T00:00:00Z,9,deprecated\n'
    'FRA,Q142,0843-08-10T00:00:00Z,11,preferred\n'
    'FRA,Q1001,1792-09-22T00:00:00Z,11,normal\n'
    'LUX,Q32,,,\n'
    'MCO,Q235,1297-01-08T00:00:00Z,11,normal\n')
LANGUAGES = [
    ('Q150', 'fra', 'French', 'французский язык'),
    ('Q7737', 'rus', 'Russian', 'русский язык'),
    ('Q1860', 'eng', 'English', 'английский язык'),
    ('Q36236', 'mal', 'Malayalam', None),
    ('Q100', 'qaa', None, 'язык, тестовый'),
    ('Q20', 'qaa', 'Test "A"', None),
    ('Q9051', 'ltz', 'Luxembourgish', 'люксембургский язык'),
]
EXPECTED_LANGUAGES = (
    'code,item,label_en,label_ru\n'
    'eng,Q1860,English,английский язык\n'
    'fra,Q150,French,французский язык\n'
    'ltz,Q9051,Luxembourgish,люксембургский язык\n'
    'mal,Q36236,Malayalam,\n'
    'qaa,Q20,"Test ""A""",\n'
    'qaa,Q100,,"язык, тестовый"\n'
    'rus,Q7737,Russian,русский язык\n')
EXPECTED_TYPES = (
    'iso3,item,type\n'
    'AND,Q228,Q3624078\n'
    'BEL,Q31,Q3624078\n'
    'FRA,Q142,Q6256\n'
    'FRA,Q142,Q185441\n'
    'FRA,Q142,Q3624078\n'
    'FRA,Q1001,Q3024240\n'
    'LUX,Q32,Q3624078\n'
    'MCO,Q235,Q3624078\n')


def bindings_json(vars_, bindings):
    return json.dumps({'head': {'vars': vars_}, 'results': {'bindings': bindings}}, ensure_ascii=False).encode()


class Sparql:
    """Поддельный SPARQL: по тексту запроса — какой это из четырёх; первый ответ country_types — оборванный JSON,
    первый ответ languages — 503."""

    def __init__(self):
        self.queries = []
        self.types_broken = False
        self.languages_busy = False

    def __call__(self, path, body):
        query = urllib.parse.parse_qs(body.decode('ascii'))['query'][0]
        self.queries.append(query)
        if 'P1566' in query:
            values = query.split('VALUES ?gid {', 1)[1].split('}', 1)[0]
            gids = [int(v.strip('"')) for v in values.split()]
            out = []
            for g in gids:
                for item, label in CITY_LABELS.get(g, []):
                    out.append({'gid': {'type': 'literal', 'value': str(g)},
                                'item': {'type': 'uri', 'value': ENTITY + item},
                                'ru': {'xml:lang': 'ru', 'type': 'literal', 'value': label}})
            return Reply(bindings_json(['gid', 'item', 'ru'], out),
                         headers={'Content-Type': 'application/sparql-results+json;charset=utf-8'})
        if 'P571' in query:
            out = []
            for item, iso3, inception, precision, rank in INCEPTION:
                b = {'item': {'type': 'uri', 'value': ENTITY + item}, 'iso3': {'type': 'literal', 'value': iso3}}
                if inception:
                    b['inception'] = {'datatype': XSD + 'dateTime', 'type': 'literal', 'value': inception}
                    b['precision'] = {'datatype': XSD + 'integer', 'type': 'literal', 'value': precision}
                    b['rank'] = {'type': 'uri', 'value': 'http://wikiba.se/ontology#' + rank + 'Rank'}
                out.append(b)
            return Reply(bindings_json(['item', 'iso3', 'inception', 'precision', 'rank'], out))
        if 'P220' in query:
            if not self.languages_busy:
                self.languages_busy = True
                return Reply(b'Service Unavailable', status=503)
            out = []
            for item, code, en, ru in LANGUAGES:
                b = {'item': {'type': 'uri', 'value': ENTITY + item}, 'code': {'type': 'literal', 'value': code}}
                if en is not None:
                    b['en'] = {'xml:lang': 'en', 'type': 'literal', 'value': en}
                if ru is not None:
                    b['ru'] = {'xml:lang': 'ru', 'type': 'literal', 'value': ru}
                out.append(b)
            return Reply(bindings_json(['item', 'code', 'en', 'ru'], out))
        if 'P31' in query:
            out = [{'item': {'type': 'uri', 'value': ENTITY + item}, 'iso3': {'type': 'literal', 'value': iso3},
                    'type': {'type': 'uri', 'value': t if t.startswith('http') else ENTITY + t}}
                   for item, iso3, t in TYPES]
            body = bindings_json(['item', 'iso3', 'type'], out)
            if not self.types_broken:
                self.types_broken = True
                return Reply(body[:len(body) // 2])
            return Reply(body)
        return Reply(b'unknown query', status=400)


class World:
    """Все маршруты удачного прогона; served — отданные байты (для сверки «как есть»)."""

    def __init__(self, server, bad_integrity=False):
        self.server = server
        self.sparql = Sparql()
        b = server.base
        s = self.served = {}
        g = '/geonames/'
        s['readme.txt'] = README.encode('utf-8')
        s['countryInfo.txt'] = COUNTRY_INFO_HEADER.encode('utf-8') + tsv(COUNTRIES)
        s['admin1CodesASCII.txt'] = ADMIN1.encode('utf-8')
        s['cities500.zip'] = make_zip([('cities500.txt', tsv(CITIES500))])
        s['MC.zip'] = make_zip([('MC.txt', tsv(MC_ROWS)), ('readme.txt', README)])
        for name in ('readme.txt', 'countryInfo.txt', 'admin1CodesASCII.txt', 'cities500.zip', 'MC.zip'):
            server.route('GET', g + name, Reply(s[name]))
        server.route('HEAD', g + 'LU.zip', Reply(b'', length=BIG_ZIP))

        w = '/wb/'
        rows = [wb_country(*c) for c in WB_COUNTRIES]
        self.country_pages = wb_pages(rows, 4)
        base = w + 'country?format=json&per_page=1000'
        self.route_pages(base, self.country_pages, first=Reply(b'<html>Service Unavailable</html>', status=500))
        self.indicator_pages = {}
        for ind, values in WB_VALUES.items():
            data = [wb_row(ind, iso3, WB_ISO2[iso3][0], WB_ISO2[iso3][1], year, v) for iso3, year, v in values]
            pages = wb_pages(data, 3 if ind == 'SP.POP.TOTL' else 20000,
                             {'sourceid': '2', 'lastupdated': '2026-10-28'})
            self.indicator_pages[ind] = pages
            first = {
                'NY.GNP.MKTP.CD': Reply(json.dumps([{'message': [{'id': '120', 'key': 'Invalid value', 'value':
                                                                 'The provided parameter value is not valid'}]}])
                                        .encode()),
                'NY.GDP.MKTP.CD': Reply(b'Too Many Requests', status=429, headers={'Retry-After': '0'}),
                'SP.DYN.LE00.IN': 'truncated',
                'AG.SRF.TOTL.K2': Reply(b'<?xml version="1.0"?><error/>'),
            }.get(ind)
            self.route_pages(f'{w}country/all/indicator/{ind}?format=json&per_page=20000&date=1960:2026', pages,
                             first=first)
            desc = [{'page': 1, 'pages': 1, 'per_page': '50', 'total': 1},
                    [{'id': ind, 'name': INDICATOR_NAMES[ind], 'unit': '', 'source': {'id': '2', 'value':
                                                                                       'World Development Indicators'},
                      'sourceNote': 'Test note.', 'sourceOrganization': 'Test organization.', 'topics': []}]]
            body = json.dumps(desc).encode()
            if ind == 'SP.POP.TOTL':
                body = b'\xef\xbb\xbf' + body  # World Bank отдаёт JSON и с BOM
            s[f'indicators/{ind}.json'] = body
            server.route('GET', f'{w}indicator/{ind}?format=json', Reply(body))
            body = json.dumps(wb_metadata(ind, with_license=ind != 'AG.SRF.TOTL.K2')).encode()
            s[f'metadata/{ind}.json'] = body
            server.route('GET', f'{w}sources/2/series/{ind}/metadata?format=json', Reply(body))

        for package, data in tarballs().items():
            s[f'{package}.tgz'] = data
            tgz = f'/npm/{package}/-/{package}-49.0.0.tgz'
            integrity = sri(data + b'x') if bad_integrity and package == 'cldr-core' else sri(data)
            meta = {'name': package, 'version': '49.0.0',
                    'dist': {'shasum': hashlib.sha1(data).hexdigest(), 'tarball': b + tgz, 'integrity': integrity,
                             'fileCount': 10, 'unpackedSize': 12345}}
            server.route('GET', f'/npm/{package}/49.0.0', Reply(jb(meta)))
            server.route('GET', tgz, Reply(data))

        server.route('POST', '/sparql', Reply(b'java.util.concurrent.TimeoutException', status=500), self.sparql)

    def route_pages(self, base, pages, first=None):
        for i, page in enumerate(pages, 1):
            body = json.dumps(page).encode()
            replies = [Reply(body)]
            if i == 1 and first == 'truncated':
                replies.insert(0, Reply(body, length=len(body) + 100))
            elif i == 1 and first is not None:
                replies.insert(0, first)
            self.server.route('GET', f'{base}&page={i}', *replies)


def run_fetch(server, out, work=None, env=None, chunk=3):
    """fetch.main в этом процессе: адреса — локальный сервер, без пауз и нижних границ; итог — (код, stdout, stderr)."""
    full = {
        'WLD_GEONAMES_BASE': server.base + '/geonames/',
        'WLD_WORLDBANK_BASE': server.base + '/wb/',
        'WLD_NPM_BASE': server.base + '/npm/',
        'WLD_WIKIDATA_SPARQL': server.base + '/sparql',
        'WLD_FETCH_BACKOFF': '0',
        'WLD_FETCH_MIN': '0',
        'WLD_FETCHED_AT': FETCHED_AT,
        'NO_PROXY': '127.0.0.1,localhost',
        'no_proxy': '127.0.0.1,localhost',
    }
    full.update(env or {})
    argv = ['--snapshot', SNAPSHOT, '--out', str(out)] + (['--work', str(work)] if work else [])
    stdout, stderr = io.StringIO(), io.StringIO()
    with mock.patch.dict(os.environ, {k: v for k, v in full.items() if v is not None}), \
            mock.patch.object(fetch, 'WIKIDATA_CHUNK', chunk), \
            contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        for k, v in full.items():
            if v is None:
                os.environ.pop(k, None)
        code = fetch.main(argv)
    return code, stdout.getvalue(), stderr.getvalue()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


# ---- тесты ----

class FullRunTest(unittest.TestCase):
    """Удачный прогон на всех источниках (с повторами по дороге) и второй прогон — тот же архив."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(cls.tmp.name)
        cls.server = Server()
        cls.world = World(cls.server)
        cls.out = root / 'out'
        cls.code, cls.stdout, cls.stderr = run_fetch(cls.server, cls.out, work=root / 'work')
        if cls.code != 0:
            raise AssertionError(f'прогон упал: {cls.stderr}\n{cls.stdout}')
        cls.requests_first = list(cls.server.requests)
        cls.zip_bytes = (cls.out / f'{SNAPSHOT}.zip').read_bytes()
        cls.zf = zipfile.ZipFile(io.BytesIO(cls.zip_bytes))
        cls.names = cls.zf.namelist()
        cls.out2 = root / 'out2'
        cls.code2, _, cls.stderr2 = run_fetch(cls.server, cls.out2)

    @classmethod
    def tearDownClass(cls):
        cls.zf.close()
        cls.server.close()
        cls.tmp.cleanup()

    def read(self, name):
        return self.zf.read(name)

    def test_outputs(self):
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), ['SHA256SUMS', 'SOURCE.md', f'{SNAPSHOT}.zip'])
        self.assertEqual((self.out / 'SOURCE.md').read_bytes(), self.read('SOURCE.md'))
        self.assertEqual((self.out / 'SHA256SUMS').read_bytes(), self.read('SHA256SUMS'))
        self.assertTrue((pathlib.Path(self.tmp.name) / 'work' / SNAPSHOT / 'fetch.json').is_file())

    def test_layout(self):
        locales = [f'cldr/cldr-localenames-full/main/{loc}/{kind}.json'
                   for loc, kinds in (('de-AT', ('languages',)), ('en', ('languages', 'scripts', 'territories')),
                                      ('ru', ('languages', 'scripts', 'territories')), ('zh-Hant', ('territories',)))
                   for kind in kinds]
        expected = (['SHA256SUMS', 'SOURCE.md', 'fetch.json']
                    + [f'cldr/cldr-core/{n}' for n in fetch.CLDR_CORE_FILES]
                    + ['cldr/cldr-localenames-full/LICENSE', 'cldr/cldr-localenames-full/package.json'] + locales
                    + ['cldr/npm/cldr-core-49.0.0.tgz', 'cldr/npm/cldr-localenames-full-49.0.0.tgz',
                       'geonames/admin1CodesASCII.txt', 'geonames/capitals/MC.zip', 'geonames/cities500.zip',
                       'geonames/countryInfo.txt', 'geonames/readme.txt']
                    + [f'wikidata/{n}' for n in ('cities_ru.csv', 'country_inception.csv', 'country_types.csv',
                                                 'languages.csv', 'queries/languages.rq',
                                                 'queries/cities_ru.rq', 'queries/country_inception.rq',
                                                 'queries/country_types.rq')]
                    + [f'worldbank/{i}.jsonl' for i in fetch.WB_INDICATORS] + ['worldbank/countries.jsonl']
                    + [f'worldbank/indicators/{i}.json' for i in fetch.WB_INDICATORS]
                    + [f'worldbank/metadata/{i}.json' for i in fetch.WB_INDICATORS])
        self.assertEqual(sorted(self.names), sorted(expected))
        self.assertEqual(self.names, sorted(self.names, key=lambda s: s.encode('utf-8')), 'записи — по имени')

    def test_zip_entries(self):
        for info in self.zf.infolist():
            self.assertEqual(info.date_time, (1980, 1, 1, 0, 0, 0), info.filename)
            self.assertEqual(info.external_attr >> 16, 0o100644, info.filename)
            self.assertFalse(info.is_dir())
            want = zipfile.ZIP_STORED if info.filename.endswith(('.zip', '.tgz')) else zipfile.ZIP_DEFLATED
            self.assertEqual(info.compress_type, want, info.filename)
        self.assertIsNone(self.zf.testzip())

    def test_determinism(self):
        self.assertEqual(self.code2, 0, self.stderr2)
        self.assertEqual(sha256((self.out2 / f'{SNAPSHOT}.zip').read_bytes()), sha256(self.zip_bytes))
        self.assertEqual((self.out2 / 'SOURCE.md').read_bytes(), (self.out / 'SOURCE.md').read_bytes())

    def test_sha256sums(self):
        lines = self.read('SHA256SUMS').decode('utf-8').splitlines()
        paths = [line.split('  ', 1)[1] for line in lines]
        self.assertEqual(paths, [n for n in self.names if n != 'SHA256SUMS'])
        for line in lines:
            digest, path = line.split('  ', 1)
            self.assertEqual(digest, sha256(self.read(path)), path)
        self.assertTrue(self.read('SHA256SUMS').endswith(b'\n'))

    def test_fetch_json(self):
        doc = json.loads(self.read('fetch.json'))
        self.assertEqual(list(doc), ['snapshot', 'fetched_at', 'files', 'skipped'])
        self.assertEqual(doc['snapshot'], SNAPSHOT)
        self.assertEqual(doc['fetched_at'], FETCHED_AT)
        files = doc['files']
        self.assertEqual(sorted(files), sorted(n for n in self.names if n not in ('SOURCE.md', 'SHA256SUMS',
                                                                                    'fetch.json')))
        for path, e in files.items():
            self.assertEqual(list(e), ['url', 'bytes', 'sha256', 'rows'], path)
            data = self.read(path)
            self.assertEqual(e['bytes'], len(data), path)
            self.assertEqual(e['sha256'], sha256(data), path)
        b = self.server.base
        rows = {p: e['rows'] for p, e in files.items()}
        self.assertEqual(rows['geonames/countryInfo.txt'], len(COUNTRIES))
        self.assertEqual(rows['geonames/admin1CodesASCII.txt'], 6)
        self.assertEqual(rows['geonames/cities500.zip'], len(CITIES500))
        self.assertEqual(rows['geonames/capitals/MC.zip'], len(MC_ROWS))
        self.assertIsNone(rows['geonames/readme.txt'])
        self.assertEqual(rows['worldbank/countries.jsonl'], len(WB_COUNTRIES))
        for ind, values in WB_VALUES.items():
            self.assertEqual(rows[f'worldbank/{ind}.jsonl'], len(values))
            self.assertIsNone(rows[f'worldbank/indicators/{ind}.json'])
            self.assertIsNone(rows[f'worldbank/metadata/{ind}.json'])
        self.assertEqual(rows['wikidata/cities_ru.csv'], 6)
        self.assertEqual(rows['wikidata/country_inception.csv'], 7)
        self.assertEqual(rows['wikidata/country_types.csv'], 8)
        self.assertEqual(rows['wikidata/languages.csv'], len(LANGUAGES))
        self.assertEqual(files['wikidata/languages.csv']['url'], b + '/sparql')
        self.assertIsNone(rows['wikidata/queries/languages.rq'])
        self.assertIsNone(rows['cldr/npm/cldr-core-49.0.0.tgz'])
        self.assertIsNone(rows['wikidata/queries/cities_ru.rq'])
        self.assertEqual(files['geonames/countryInfo.txt']['url'], b + '/geonames/countryInfo.txt')
        self.assertEqual(files['worldbank/countries.jsonl']['url'], b + '/wb/country?format=json&per_page=1000')
        self.assertEqual(files['worldbank/SP.POP.TOTL.jsonl']['url'],
                         b + '/wb/country/all/indicator/SP.POP.TOTL?format=json&per_page=20000&date=1960:2026')
        self.assertEqual(files['cldr/npm/cldr-core-49.0.0.tgz']['url'], b + '/npm/cldr-core/-/cldr-core-49.0.0.tgz')
        self.assertEqual(files['cldr/cldr-core/LICENSE']['url'],
                         b + '/npm/cldr-core/-/cldr-core-49.0.0.tgz#package/LICENSE')
        self.assertEqual(files['wikidata/cities_ru.csv']['url'], b + '/sparql')
        self.assertIsNone(files['wikidata/queries/cities_ru.rq']['url'])
        self.assertEqual(doc['skipped'], [{'path': 'geonames/capitals/LU.zip', 'url': b + '/geonames/LU.zip',
                                           'bytes': BIG_ZIP, 'reason': f'больше 30 МБ (Content-Length {BIG_ZIP})'}])

    def test_geonames_as_is(self):
        for name in ('readme.txt', 'countryInfo.txt', 'admin1CodesASCII.txt', 'cities500.zip'):
            self.assertEqual(self.read('geonames/' + name), self.world.served[name], name)
        self.assertEqual(self.read('geonames/capitals/MC.zip'), self.world.served['MC.zip'])

    def test_capitals(self):
        heads = sorted(r[1] for r in self.requests_first if r[0] == 'HEAD')
        self.assertEqual(heads, ['/geonames/LU.zip', '/geonames/MC.zip'])
        gets = [r[1] for r in self.requests_first if r[0] == 'GET']
        self.assertIn('/geonames/MC.zip', gets)
        self.assertNotIn('/geonames/LU.zip', gets)
        self.assertIn('LU MC', self.stdout)

    def test_worldbank_pages(self):
        lines = self.read('worldbank/countries.jsonl').decode('utf-8').splitlines()
        self.assertEqual(len(lines), 2)
        self.assertEqual(lines, [json.dumps(p, ensure_ascii=False, separators=(',', ':'))
                                 for p in self.world.country_pages])
        self.assertTrue(self.read('worldbank/countries.jsonl').endswith(b'\n'))
        lines = self.read('worldbank/SP.POP.TOTL.jsonl').decode('utf-8').splitlines()
        self.assertEqual(len(lines), 2)
        self.assertEqual([json.loads(line) for line in lines], self.world.indicator_pages['SP.POP.TOTL'])
        first = json.loads(lines[0])
        self.assertEqual((first[0]['page'], first[0]['pages'], first[0]['total']), (1, 2, 5))
        base = '/wb/country/all/indicator/SP.POP.TOTL?format=json&per_page=20000&date=1960:2026'
        self.assertEqual(self.server.count('GET', base + '&page=1'), 2)  # второй прогон — ещё раз
        self.assertEqual(self.server.count('GET', base + '&page=2'), 2)
        self.assertEqual(self.read('worldbank/indicators/SP.POP.TOTL.json'),
                         self.world.served['indicators/SP.POP.TOTL.json'], 'как есть, с BOM')
        self.assertEqual(self.read('worldbank/metadata/NY.GDP.MKTP.CD.json'),
                         self.world.served['metadata/NY.GDP.MKTP.CD.json'])

    def test_retries(self):
        def count(path):
            return sum(1 for r in self.requests_first if r[0] == 'GET' and r[1] == path)

        wb = '/wb/country/all/indicator/{}?format=json&per_page=20000&date=1960:2026&page=1'
        self.assertEqual(count('/wb/country?format=json&per_page=1000&page=1'), 2, '500 → повтор')
        self.assertEqual(count(wb.format('NY.GNP.MKTP.CD')), 2, 'ответ-ошибка World Bank → повтор')
        self.assertEqual(count(wb.format('NY.GDP.MKTP.CD')), 2, '429 → повтор')
        self.assertEqual(count(wb.format('SP.DYN.LE00.IN')), 2, 'оборванный ответ → повтор')
        self.assertEqual(count(wb.format('AG.SRF.TOTL.K2')), 2, 'не JSON → повтор')
        self.assertEqual(count(wb.format('SP.POP.TOTL')), 1)
        self.assertIn('HTTP 429', self.stdout)
        self.assertIn('World Bank ответил ошибкой: 120 Invalid value', self.stdout)
        self.assertIn('ответ оборван', self.stdout)
        self.assertIn('попытка 1/6 не удалась', self.stdout)
        posts = [r for r in self.requests_first if r[0] == 'POST']
        self.assertEqual(len(posts), 3 + 1 + 1 + 2 + 2,
                         'cities ×3 и повтор после 500, inception, types и повтор, languages и повтор после 503')
        self.assertIn('(Wikidata languages): HTTP 503', self.stdout)

    def test_user_agent(self):
        self.assertTrue(self.server.requests)
        for method, path, headers, _ in self.server.requests:
            self.assertEqual(headers.get('User-Agent'), UA, f'{method} {path}')

    def test_sparql_requests(self):
        posts = [r for r in self.requests_first if r[0] == 'POST']
        for _, path, headers, body in posts:
            self.assertEqual(path, '/sparql')
            self.assertEqual(headers.get('Accept'), 'application/sparql-results+json')
            self.assertEqual(headers.get('Content-Type'), 'application/x-www-form-urlencoded')
            self.assertEqual(list(urllib.parse.parse_qs(body.decode('ascii'))), ['query'])
        city_queries = [q for q in self.world.sparql.queries if 'P1566' in q]
        gids = []
        for q in city_queries[:3]:
            values = q.split('VALUES ?gid {', 1)[1].split('}', 1)[0].split()
            self.assertLessEqual(len(values), 3)
            self.assertTrue(all(v.startswith('"') and v.endswith('"') for v in values))
            gids += [int(v.strip('"')) for v in values]
        self.assertEqual(gids, sorted(P_GIDS), 'все geonameid класса P (cities500 и MC.zip), по возрастанию')
        template = self.read('wikidata/queries/cities_ru.rq').decode('utf-8')
        self.assertEqual(template.count(fetch.GEONAMEIDS), 1)
        self.assertEqual(city_queries[0], template.replace(fetch.GEONAMEIDS, ' '.join(f'"{g}"' for g in gids[:3])))
        for name in ('country_inception', 'country_types', 'languages'):
            self.assertEqual(self.read(f'wikidata/queries/{name}.rq').decode('utf-8'), fetch.QUERIES[name])
            self.assertIn(fetch.QUERIES[name], self.world.sparql.queries)

    def test_wikidata_csv(self):
        self.assertEqual(self.read('wikidata/cities_ru.csv').decode('utf-8'), EXPECTED_CITIES_RU)
        self.assertEqual(self.read('wikidata/country_inception.csv').decode('utf-8'), EXPECTED_INCEPTION)
        self.assertEqual(self.read('wikidata/country_types.csv').decode('utf-8'), EXPECTED_TYPES)
        self.assertEqual(self.read('wikidata/languages.csv').decode('utf-8'), EXPECTED_LANGUAGES)

    def test_cldr(self):
        tgz = tarballs()
        self.assertEqual(self.read('cldr/npm/cldr-core-49.0.0.tgz'), tgz['cldr-core'])
        self.assertEqual(self.read('cldr/npm/cldr-localenames-full-49.0.0.tgz'), tgz['cldr-localenames-full'])
        with tarfile.open(fileobj=io.BytesIO(tgz['cldr-localenames-full'])) as tf:
            self.assertEqual(self.read('cldr/cldr-localenames-full/main/ru/territories.json'),
                             tf.extractfile('package/main/ru/territories.json').read())
        self.assertEqual(self.read('cldr/cldr-core/LICENSE'), LICENSE)

    def test_source_md(self):
        text = self.read('SOURCE.md').decode('utf-8')
        b = self.server.base
        for want in (f'# {SNAPSHOT}', FETCHED_AT, '(UTC)', 'CC BY 4.0', 'https://www.geonames.org/',
                     'https://data.worldbank.org/', 'Unicode License v3', '`cldr/cldr-core/LICENSE`',
                     'Copyright © 2001–2026 Unicode, Inc.', 'CC0 1.0', b + '/geonames/cities500.zip',
                     b + '/wb/country?format=json&per_page=1000', b + '/sparql',
                     b + '/npm/cldr-core/-/cldr-core-49.0.0.tgz', sri(tarballs()['cldr-core']),
                     '| `SP.POP.TOTL` | Population, total | 2024–2025 | 2026-10-28 | CC BY-4.0 '
                     '| https://datacatalog.worldbank.org/public-licenses#cc-by |',
                     '| `AG.SRF.TOTL.K2` | Surface area (sq. km) | 2022 | 2026-10-28 |',
                     'см. `worldbank/metadata/AG.SRF.TOTL.K2.json`', '`geonames/capitals/MC.zip`',
                     'Не скачан `geonames/capitals/LU.zip`', '| `wikidata/cities_ru.csv` |',
                     '| `wikidata/languages.csv` | `languages.rq` | `code,item,label_en,label_ru` — ',
                     f'метки нет — поле пустое | {len(LANGUAGES)} |',
                     '| `geonames/cities500.zip` | ' + b + '/geonames/cities500.zip | 6 |'):
            self.assertIn(want, text)
        self.assertTrue(text.endswith('\n') and '\r' not in text)


class FailureTest(unittest.TestCase):
    """Сбои источников: после повторов или сразу — код 1 и причина с адресом."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.server = Server()

    def tearDown(self):
        self.server.close()
        self.tmp.cleanup()

    def run_fetch(self, **kw):
        return run_fetch(self.server, self.root / 'out', **kw)

    def assert_failed(self, code, stderr, *parts):
        self.assertEqual(code, 1)
        self.assertIn('снимок не собран', stderr)
        for p in parts:
            self.assertIn(p, stderr)
        self.assertFalse((self.root / 'out' / f'{SNAPSHOT}.zip').exists())

    def test_npm_integrity_mismatch(self):
        World(self.server, bad_integrity=True)
        code, _, err = self.run_fetch()
        tgz = '/npm/cldr-core/-/cldr-core-49.0.0.tgz'
        self.assert_failed(code, err, self.server.base + tgz, 'sha512', 'dist.integrity', 'попыток: 6')
        self.assertEqual(self.server.count('GET', tgz), 6)

    def test_404_fails_at_once(self):
        World(self.server)
        self.server.route('GET', '/geonames/readme.txt', Reply(b'gone', status=404))
        code, _, err = self.run_fetch()
        self.assert_failed(code, err, self.server.base + '/geonames/readme.txt', 'HTTP 404')
        self.assertEqual(self.server.count('GET', '/geonames/readme.txt'), 1)

    def test_retries_exhausted(self):
        World(self.server)
        self.server.route('GET', '/geonames/countryInfo.txt', Reply(b'busy', status=503))
        code, out, err = self.run_fetch()
        self.assert_failed(code, err, self.server.base + '/geonames/countryInfo.txt', 'HTTP 503', 'попыток: 6')
        self.assertEqual(self.server.count('GET', '/geonames/countryInfo.txt'), 6)
        self.assertEqual(out.count('не удалась'), 5)

    def test_worldbank_pages_disagree(self):
        world = World(self.server)
        base = '/wb/country/all/indicator/SP.POP.TOTL?format=json&per_page=20000&date=1960:2026'
        page = world.indicator_pages['SP.POP.TOTL'][1]
        bad = [dict(page[0], total=page[0]['total'] + 1), page[1]]
        self.server.route('GET', base + '&page=2', Reply(json.dumps(bad).encode()))
        code, out, err = self.run_fetch()
        self.assert_failed(code, err, 'страницы не сходятся', 'SP.POP.TOTL')
        self.assertEqual(self.server.count('GET', base + '&page=1'), fetch.WB_PASSES)
        self.assertIn('заново с первой страницы', out)

    def test_worldbank_total_mismatch(self):
        world = World(self.server)
        base = '/wb/country/all/indicator/SP.DYN.LE00.IN?format=json&per_page=20000&date=1960:2026'
        page = world.indicator_pages['SP.DYN.LE00.IN'][0]
        bad = [dict(page[0], total=page[0]['total'] + 1), page[1]]
        self.server.route('GET', base + '&page=1', Reply(json.dumps(bad).encode()))
        code, _, err = self.run_fetch()
        self.assert_failed(code, err, 'в шапке total = 3')

    def test_minimums(self):
        World(self.server)
        code, _, err = self.run_fetch(env={'WLD_FETCH_MIN': None})
        self.assert_failed(code, err, 'countryInfo.txt', 'меньше нижней границы 240')

    def test_minimum_languages(self):
        World(self.server)
        others = [n for n in dir(fetch) if n.startswith('MIN_') and n != 'MIN_LANGUAGES']
        with contextlib.ExitStack() as stack:
            for n in others:
                stack.enter_context(mock.patch.object(fetch, n, 0))
            code, _, err = self.run_fetch(env={'WLD_FETCH_MIN': None})
        self.assert_failed(code, err, 'Wikidata languages: строк: 7', 'меньше нижней границы 5 000')

    def test_country_info_format(self):
        World(self.server)
        body = COUNTRY_INFO_HEADER.encode() + tsv([r[:18] for r in COUNTRIES])
        self.server.route('GET', '/geonames/countryInfo.txt', Reply(body))
        code, _, err = self.run_fetch()
        self.assert_failed(code, err, 'столбцов 18, ждали 19')

    def test_capital_size_unknown(self):
        """HEAD без Content-Length — размер узнаётся по ходу скачивания: больше предела — пропуск."""
        world = World(self.server)
        big = make_zip([('LU.txt', os.urandom(4000).hex())])
        self.server.route('HEAD', '/geonames/LU.zip', Reply(b'', length='omit'))
        self.server.route('GET', '/geonames/LU.zip', Reply(big, length='omit'))
        limit = len(world.served['MC.zip']) + 10
        self.assertGreater(len(big), limit)
        with mock.patch.object(fetch, 'CAPITAL_ZIP_MAX', limit):
            code, out, err = self.run_fetch(work=self.root / 'work')
        self.assertEqual(code, 0, err)
        doc = json.loads((self.root / 'work' / SNAPSHOT / 'fetch.json').read_text(encoding='utf-8'))
        self.assertIn('geonames/capitals/MC.zip', doc['files'])
        self.assertNotIn('geonames/capitals/LU.zip', doc['files'])
        self.assertEqual([(s['path'], s['bytes']) for s in doc['skipped']], [('geonames/capitals/LU.zip', None)])
        self.assertFalse(any(p.name.endswith('.part') for p in (self.root / 'work').rglob('*')))

    def test_bad_snapshot_name(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
            code = fetch.main(['--snapshot', 'wld-source-20261340', '--out', str(self.root / 'out')])
        self.assertEqual(code, 1)
        self.assertIn('нет такой даты', stderr.getvalue())
        with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
            code = fetch.main(['--snapshot', 'air-source-20250901', '--out', str(self.root / 'out')])
        self.assertEqual(code, 1)
        self.assertIn('не wld-source-YYYYMMDD', stderr.getvalue())


class PartsTest(unittest.TestCase):
    """Отдельные части: tarball npm, SRI, ответы World Bank и Wikidata, Retry-After."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def tgz(self, members):
        p = self.root / 'cldr-core-49.0.0.tgz'
        p.write_bytes(make_tgz(members))
        return p

    def core_members(self):
        with tarfile.open(fileobj=io.BytesIO(tarballs()['cldr-core'])) as tf:
            return [(m.name, tf.extractfile(m).read()) for m in tf.getmembers()]

    def test_extract_ok(self):
        files = fetch.extract_cldr(self.tgz(self.core_members()), 'cldr-core')
        self.assertEqual(sorted(files), sorted(fetch.CLDR_CORE_FILES))

    def test_extract_symlink(self):
        members = [m for m in self.core_members() if m[0] != 'package/LICENSE']
        members.append(('package/LICENSE', None, tarfile.SYMTYPE))
        with self.assertRaisesRegex(fetch.FetchError, 'не обычный файл'):
            fetch.extract_cldr(self.tgz(members), 'cldr-core')

    def test_extract_traversal(self):
        for bad in ('package/../../etc/passwd', '/package/LICENSE', 'package\\..\\x'):
            with self.subTest(bad=bad):
                members = self.core_members() + [(bad, b'x')]
                with self.assertRaisesRegex(fetch.FetchError, 'небезопасный путь'):
                    fetch.extract_cldr(self.tgz(members), 'cldr-core')

    def test_extract_missing_and_version(self):
        members = [m for m in self.core_members() if m[0] != 'package/supplemental/territoryInfo.json']
        with self.assertRaisesRegex(fetch.FetchError, 'territoryInfo.json'):
            fetch.extract_cldr(self.tgz(members), 'cldr-core')
        members = [m if m[0] != 'package/package.json' else (m[0], jb({'name': 'cldr-core', 'version': '48.0.0'}))
                   for m in self.core_members()]
        with self.assertRaisesRegex(fetch.FetchError, 'cldr-core@49.0.0'):
            fetch.extract_cldr(self.tgz(members), 'cldr-core')

    def test_sri(self):
        digest = hashlib.sha512(b'abc').digest()
        self.assertEqual(fetch.sri_sha512(sri(b'abc')), [digest])
        self.assertEqual(fetch.sri_sha512('sha1-AAAA ' + sri(b'abc') + '?opt'), [digest])
        with self.assertRaises(fetch.FetchError):
            fetch.sri_sha512('sha1-qZk+NkcGgWq6PiVxeFDCbJzQ2J0=')

    def test_wb_shapes(self):
        err = json.dumps([{'message': [{'id': '175', 'key': 'Invalid format',
                                        'value': 'The indicator was not found'}]}])
        with self.assertRaisesRegex(fetch.Retry, '175 Invalid format'):
            fetch.check_wb_page(err.encode())
        with self.assertRaises(fetch.Retry):
            fetch.check_wb_page(b'[{"page":1}]')
        ok = fetch.check_wb_page(b'\xef\xbb\xbf[{"page":1,"pages":"1","per_page":"50","total":0},null]')
        self.assertIsNone(ok[1])
        self.assertEqual(fetch.find_license(wb_metadata('SP.POP.TOTL')),
                         ('CC BY-4.0', 'https://datacatalog.worldbank.org/public-licenses#cc-by'))
        self.assertEqual(fetch.find_license({'x': [{'name': 'license type', 'value': ' CC0 '}]}), ('CC0', None))
        self.assertEqual(fetch.find_license(wb_metadata('SP.POP.TOTL', with_license=False)), (None, None))

    def test_wikidata_values(self):
        self.assertEqual(fetch.qid('http://www.wikidata.org/entity/Q42'), 'Q42')
        self.assertIsNone(fetch.qid('http://www.wikidata.org/entity/L7'))
        self.assertIsNone(fetch.qid('http://www.wikidata.org/.well-known/genid/abc'))
        with self.assertRaises(fetch.Retry):
            fetch.check_sparql(b'{"head":{"vars":[]},"results":{"bindi')

    def test_retry_after(self):
        self.assertEqual(fetch.retry_after({'Retry-After': '7'}), 7)
        self.assertEqual(fetch.retry_after({'Retry-After': '100000'}), fetch.RETRY_AFTER_MAX)
        self.assertEqual(fetch.retry_after({'Retry-After': 'Wed, 21 Oct 2015 07:28:00 GMT'}), 0)
        self.assertIsNone(fetch.retry_after({'Retry-After': 'soon'}))
        self.assertIsNone(fetch.retry_after({}))

    def test_deterministic_zip(self):
        tree = self.root / 'tree'
        (tree / 'a').mkdir(parents=True)
        (tree / 'a' / 'x.zip').write_bytes(b'PK-not-really')
        (tree / 'b.txt').write_bytes(b'b\n' * 100)
        names = fetch.tree_files(tree)
        self.assertEqual(names, ['a/x.zip', 'b.txt'])
        fetch.write_zip(tree, names, self.root / 'one.zip')
        os.utime(tree / 'b.txt', (1, 1))
        os.chmod(tree / 'b.txt', 0o600)
        fetch.write_zip(tree, names, self.root / 'two.zip')
        self.assertEqual((self.root / 'one.zip').read_bytes(), (self.root / 'two.zip').read_bytes())

    def test_capital_row_with_dropped_code(self):
        # строка с именем столицы, но с кодом, которого сборка не берёт (PPLX …), столицу не заменяет
        rows = [geo(1, 'Alpha', '', '1.0', '1.0', 'P', 'PPLX', 'AA', '01', 5000, 'Etc/UTC'),
                geo(2, 'Beta', '', '2.0', '2.0', 'P', 'PPL', 'BB', '01', 5000, 'Etc/UTC'),
                geo(3, 'Gamma', '', '3.0', '3.0', 'P', 'PPLH', 'CC', '01', 5000, 'Etc/UTC'),
                geo(4, 'Gamma', '', '3.1', '3.1', 'P', 'PPLA', 'CC', '01', 5000, 'Etc/UTC')]
        z = self.root / 'cities500.zip'
        with zipfile.ZipFile(z, 'w') as zf:
            zf.writestr('cities500.txt', ''.join('\t'.join(r) + '\n' for r in rows))
        found, gids = set(), set()
        n = fetch.scan_geonames(z, 'cities500.txt', {'AA': 'Alpha', 'BB': 'Beta', 'CC': 'Gamma'}, found, gids)
        self.assertEqual(n, 4)
        self.assertEqual(found, {'BB', 'CC'})
        self.assertEqual(gids, {1, 2, 3, 4})


if __name__ == '__main__':
    unittest.main()
