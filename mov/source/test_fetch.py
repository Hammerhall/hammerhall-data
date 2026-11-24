"""Тесты mov/source/fetch.py без сети: локальный HTTP-сервер на 127.0.0.1 изображает SPARQL-эндпойнт Wikidata — по
тексту запроса узнаёт, какой это из запросов fetch.py, и отвечает из маленького поддельного мира (фильмы, жанры и
классы, съёмочная группа, студии, серии, деньги, награды, люди, имена, персонажи, места, страны, языки) в формате
results.bindings, со сбоями по дороге: тайм-ауты (500 и молчание дольше тайм-аута сокета), оборванный JSON и gzip при
HTTP 200, 429 и 503 с Retry-After.

Запуск (из корня репозитория):
  NO_PROXY=127.0.0.1,localhost no_proxy=127.0.0.1,localhost \\
    python3 -X utf8 -I -m unittest discover -s mov/source -p 'test_*.py' -v
"""
import collections
import contextlib
import gzip
import hashlib
import importlib.util
import io
import itertools
import json
import os
import pathlib
import re
import tempfile
import threading
import time
import unittest
import urllib.parse
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
SNAPSHOT = 'mov-source-20261123'
FETCHED_AT = '2026-11-23T10:00:00Z'
UA = 'HammerhallData/1.0 (https://github.com/Hammerhall/hammerhall-data)'
CLIENT_TIMEOUT = 1.0  # тайм-аут сокета клиента в тестах (с)
SLOW = 2.0            # столько молчит сервер на «тяжёлой» части — дольше тайм-аута клиента


def load_fetch():
    spec = importlib.util.spec_from_file_location('mov_source_fetch', HERE / 'fetch.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fetch = load_fetch()


# ---- локальный сервер ----

class Reply:
    """Ответ сервера. length — свой Content-Length (оборванный ответ)."""

    def __init__(self, body=b'', status=200, headers=None, length=None):
        self.body = body
        self.status = status
        self.headers = headers or {}
        self.length = length


class QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        pass  # клиент ушёл по своему тайм-ауту раньше ответа — так и задумано


class Server:
    """ThreadingHTTPServer на свободном порту. Маршрут (метод, путь) → ответы по очереди: первые — по разу, последний —
    сколько угодно; ответ может быть функцией (путь, заголовки, тело) → Reply."""

    def __init__(self):
        self.routes = {}
        self.requests = []
        self.lock = threading.Lock()
        self.httpd = QuietServer(('127.0.0.1', 0), self._handler())
        self.base = f'http://127.0.0.1:{self.httpd.server_address[1]}'
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def route(self, method, path, *replies):
        with self.lock:
            self.routes[(method, path)] = list(replies)

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def _pick(self, method, path):
        with self.lock:
            replies = self.routes.get((method, path))
            if not replies:
                return None
            return replies.pop(0) if len(replies) > 1 else replies[0]

    def _handler(self):
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.0'

            def log_message(self, *args):
                pass

            def do_POST(self):
                n = int(self.headers.get('Content-Length') or 0)
                body = self.rfile.read(n) if n else b''
                headers = dict(self.headers.items())
                with server.lock:
                    server.requests.append(('POST', self.path, headers, body))
                reply = server._pick('POST', self.path)
                if reply is None:
                    reply = Reply(b'not found', status=404)
                elif callable(reply):
                    reply = reply(self.path, headers, body)
                self.send_response(reply.status)
                for k, v in reply.headers.items():
                    self.send_header(k, v)
                self.send_header('Content-Length', str(len(reply.body) if reply.length is None else reply.length))
                self.end_headers()
                self.wfile.write(reply.body)

        return Handler


# ---- поддельный мир Wikidata ----

ENTITY = 'http://www.wikidata.org/entity/'
GENID = 'http://www.wikidata.org/.well-known/genid/'  # так WDQS отдаёт «неизвестное значение»
ONTOLOGY = 'http://wikiba.se/ontology#'
XSD = 'http://www.w3.org/2001/XMLSchema#'
ADULT = 'Q185529'
TIMEOUT_BODY = b'java.util.concurrent.ExecutionException: java.util.concurrent.TimeoutException'


def st(person, prop='P161', rank='normal', ordinal=(), character=(), name=()):
    """Утверждение состава с квалификаторами P1545, P453, P4633."""
    return {'prop': prop, 'person': person, 'rank': rank, 'P1545': list(ordinal), 'P453': list(character),
            'P4633': list(name)}


def aw(award, rank='normal', when=()):
    """Утверждение P166 или P1411 с квалификаторами P585: (дата, точность)."""
    return {'award': award, 'rank': rank, 'P585': list(when)}


DB = {
    # классы фильмов
    'Q11424': {'label': {'en': 'film'}, 'P279': ['Q1900']},
    'Q24869': {'label': {'en': 'feature film'}, 'P279': ['Q11424']},
    'Q24862': {'label': {'en': 'short film'}, 'P279': ['Q11424']},
    'Q17517379': {'label': {'en': 'animated short film'}, 'P279': ['Q24862', 'Q202866']},
    'Q202866': {'label': {'en': 'animated film'}, 'P279': ['Q11424']},
    'Q29168811': {'label': {'en': 'animated feature film'}, 'P279': ['Q202866', 'Q24869']},
    'Q20650540': {'label': {'en': 'anime film'}, 'P279': ['Q202866']},
    'Q18089587': {'label': {'en': 'stop-motion animated film'}, 'P279': ['Q202866']},
    'Q93204': {'label': {'en': 'documentary film'}, 'P279': ['Q11424']},
    'Q506240': {'label': {'en': 'television film'}, 'P279': ['Q11424']},
    'Q226730': {'label': {'en': 'silent film'}, 'P279': ['Q11424']},
    'Q193977': {'label': {'en': 'music video'}, 'P279': ['Q1900']},
    'Q622550': {'label': {'en': 'trailer'}},
    'Q18011172': {'label': {'en': 'film project'}},
    'Q24856': {'label': {'en': 'film series'}},
    'Q1900': {'label': {'en': 'visual work'}},
    # жанры
    'Q130232': {'label': {'en': 'drama film'}, 'P279': ['Q11424', 'Q1901']},
    'Q1901': {'label': {'en': 'drama'}},
    'Q157443': {'label': {'en': 'comedy film'}, 'P279': ['Q11424']},
    'Q1403': {'label': {'en': 'genre A'}, 'P279': ['Q1404']},
    'Q1404': {'label': {'en': 'genre B'}, 'P279': ['Q1403', GENID + 'g2']},  # цикл и «неизвестное значение»
    ADULT: {'label': {'en': 'genre X0'}, 'P279': ['Q11424']},
    'Q1400': {'label': {'en': 'genre X1'}, 'P279': [ADULT]},
    'Q1401': {'label': {'en': 'genre X2'}, 'P279': ['Q1402']},
    'Q1402': {'label': {'en': 'genre X3'}, 'P279': [ADULT]},
    # фильмы; P577 — (дата, точность, ранг[, места P291])
    'Q100': {'P31': ['Q11424', 'Q24869'], 'P136': ['Q130232'], 'P57': ['Q1001'], 'P495': ['Q142'], 'P364': ['Q150'],
             'label': {'en': 'Alpha', 'ru': 'Альфа', 'fr': 'Alpha', 'ja': 'アルファ'},
             'P1476': [('en', 'Alpha'), ('fr', 'Alpha, le film')],
             'P577': [('1999-03-31T00:00:00Z', 11, 'normal', ['Q142', 'Q183']),
                      ('1998-01-01T00:00:00Z', 9, 'deprecated', ['Q30']), ('1999-01-01T00:00:00Z', 9, 'preferred')],
             'P1191': [('1998-09-10T00:00:00Z', 11, 'normal')],
             'P571': [('1997-01-01T00:00:00Z', 9, 'preferred'), ('1996-01-01T00:00:00Z', 9, 'deprecated')],
             'P2047': [('5400.0', 'normal')], 'sitelinks': 25,
             'P166': [aw('Q7001', when=[('2000-03-26T00:00:00Z', 11), ('2000-01-01T00:00:00Z', 9)]), aw('Q7002'),
                      aw('Q7001', rank='deprecated')],
             'P1411': [aw('Q7001', rank='preferred')], 'P345': ['tt0000100'],
             'cast': [st('Q1001'), st('Q1002', character=['Q3001']), st('Q1003', name=['Bob, the "Builder"']),
                      st('Q1004', character=['Q3002'], name=['Jane']), st('Q1005', character=['Q3003', 'Q3004']),
                      st('Q1006', prop='P725', ordinal=['1'], name=['Narrator']), st(GENID + 'c1'),
                      st('Q1009', rank='deprecated'), st('Q1007', name=['A', 'B'])],
             'P58': ['Q1001', 'Q1010'], 'P86': ['Q1011'], 'P344': [GENID + 'w1'], 'P162': ['Q1010'],
             'P1040': ['Q1012'],
             'P272': ['Q5001'], 'P750': ['Q5002', 'Q5003'], 'P915': ['Q2001', 'Q90', GENID + 'l1'], 'P156': ['Q101'],
             'P179': [('Q6001', 'normal', ['1']), ('Q6002', 'deprecated', ['9'])],
             'P2130': [('+15000000', 'Q4917', 'normal'), ('1.2E+7', 'Q4917', 'preferred'),
                       ('9', 'Q4917', 'deprecated')],
             'P2142': [('100000000', 'Q4917', 'normal'), ('80000000.00', 'Q4916', 'normal')]},
    'Q101': {'P31': ['Q24869'], 'P136': ['Q157443'], 'P57': ['Q1002'], 'P495': ['Q183', 'Q142'], 'P364': ['Q1860'],
             'label': {'mul': 'Beta'}, 'P577': [('1995-06-01T00:00:00Z', 11, 'normal', ['Q30', 'Q90', GENID + 'r1'])],
             'sitelinks': 3, 'P345': ['tt0000102', 'tt0000101'], 'cast': [st('Q1001', character=[GENID + 'c2'])],
             'P86': ['Q1002'], 'P155': ['Q100'], 'P750': ['Q5002'], 'P179': [('Q6001', 'normal', ['2', '2a'])],
             'P2142': [('2500000.50', 'Q4917', 'normal')]},
    'Q102': {'P31': ['Q24869'], 'label': {'de': 'Gamma (Film)'}, 'P577': [('2012-01-01T00:00:00Z', 9, 'normal')],
             'sitelinks': 1, 'P2754': [('2010-06-01T00:00:00Z', 10, 'normal')], 'P179': [('Q6002', 'normal', [])]},
    'Q103': {'P31': ['Q24869'], 'label': {'en': 'Delta'}},  # без P577: по годам его не найти
    'Q104': {'P31': ['Q202866'], 'P136': ['Q1403'], 'P57': ['Q1008'], 'label': {'en': 'Epsilon'},
             'P577': [('-0050-01-01T00:00:00Z', 9, 'normal')], 'P2047': [('90.50', 'normal')]},
    'Q105': {'P31': ['Q11424', 'Q193977'], 'P136': [GENID + 'g1'], 'label': {'en': 'Zeta, "the clip"'},
             'P577': [('2001-05-05T00:00:00Z', 11, 'normal')]},
    'Q106': {'P31': ['Q93204'], 'P495': ['Q17'], 'P364': ['Q5287'], 'label': {'en': 'Eta', 'ru': 'Эта'},
             'P577': [('2005-04-01T00:00:00Z', 10, 'normal', ['Q17'])],
             'P2047': [('3600', 'normal'), ('3660', 'normal'), ('1', 'deprecated')], 'sitelinks': 2,
             'P1411': [aw('Q7002'), aw(GENID + 'a1', when=[('2006-01-01T00:00:00Z', 9)])], 'P915': ['Q2003']},
    'Q107': {'P31': ['Q24862'], 'P136': ['Q1500'], 'label': {'en': 'Theta'},
             'P577': [('2015-01-01T00:00:00Z', 9, 'normal')]},
    'Q108': {'P31': ['Q11424'], 'label': {'en': 'Iota'}, 'nositelinks': True,
             'cast': [st('Q1006', prop='P725', ordinal=['2']),
                      st('Q1003', character=['Q3001', 'Q3002'], name=['Mask', 'Shadow'])]},
    'Q109': {'P31': ['Q506240'], 'label': {'en': 'Kappa'}, 'P577': [('1988-01-01T00:00:00Z', 9, 'normal')]},
    'Q110': {'P31': ['Q11424'], 'P136': ['Q1400'], 'P57': ['Q2901'], 'label': {'en': 'X1'},
             'P577': [('2003-01-01T00:00:00Z', 9, 'normal', ['Q2903'])], 'cast': [st('Q2900')],
             'P1191': [('2002-01-01T00:00:00Z', 9, 'normal')], 'P58': ['Q2902'], 'P272': ['Q5009'],
             'P179': [('Q6009', 'normal', [])], 'P166': [aw('Q7009')], 'P2130': [('5', 'Q4917', 'normal')]},
    'Q111': {'P31': ['Q11424', ADULT], 'label': {'en': 'X2'}, 'cast': [st('Q2900')]},
    'Q112': {'P31': ['Q11424'], 'P136': ['Q1401'], 'label': {'en': 'X3'}},
    'Q113': {'P31': ['Q226730', 'Q11424'], 'P495': ['Q1300'], 'P364': ['Q1301'],
             'label': {'en': 'Lambda', 'sv': 'Lambda'}, 'P1476': [('en', 'Lambda')],
             'P577': [('1920-01-01T00:00:00Z', 9, 'normal')], 'sitelinks': 5},
    'Q114': {'P31': ['Q17517379'], 'label': {'pl': 'Mi'}, 'P577': [('2020-02-02T00:00:00Z', 11, 'normal')]},
    # без метки на языках LABEL_LANGS и без P1476 — все метки в film_labels_all; без P577 — запасная дата P1191
    'Q115': {'P31': ['Q11424'], 'label': {'ja': 'ガンマ', 'ko': 'Gamma, "K"', 'zh': '伽马'}, 'sitelinks': 1,
             'P1191': [('2003-04-05T00:00:00Z', 11, 'normal'), ('2003-01-01T00:00:00Z', 9, 'deprecated')],
             'P58': ['Q1013']},
    # метки только на «чужом» языке, но есть P1476 — в film_labels_all не идёт
    'Q116': {'P31': ['Q11424'], 'label': {'ja': 'デルタ'}, 'P1476': [('ja', 'デルタ')],
             'P2754': [('2009-01-01T00:00:00Z', 9, 'normal')]},
    # люди
    'Q1001': {'P31': ['Q5'], 'P21': ['Q6581097'], 'sitelinks': 10,
              'label': {'en': 'Jean Dupont', 'ru': 'Жан Дюпон', 'fr': 'Jean Dupont'},
              'P735': [('Q4001', 'normal', [])], 'P734': [('Q4002', 'normal', [])],
              'P569': [('1950-02-03T00:00:00Z', 11, 'normal'), ('1951-01-01T00:00:00Z', 9, 'deprecated')],
              'P570': [('2020-01-01T00:00:00Z', 9, 'preferred'), ('2019-12-31T00:00:00Z', 11, 'normal')],
              'P19': ['Q90'], 'P27': ['Q142']},
    'Q1002': {'P31': ['Q5'], 'P21': ['Q6581072', 'Q1052281'], 'sitelinks': 4, 'label': {'en': 'Mary Smith'},
              'P735': [('Q4003', 'normal', ['1']), ('Q4004', 'normal', ['2']), ('Q4005', 'deprecated', [])],
              'P734': [('Q4006', 'normal', [])], 'P569': [('1960-01-01T00:00:00Z', 9, 'normal')],
              'P19': ['Q2002'], 'P27': ['Q38', 'Q142']},
    'Q1003': {'P31': ['Q5'], 'label': {'mul': 'Kim Lee'}, 'P735': [('Q4009', 'normal', [])]},
    'Q1004': {'P31': ['Q144'], 'sitelinks': 2, 'label': {'en': 'Rex'}},
    'Q1005': {'P31': ['Q5'], 'P21': [GENID + 'p1'], 'sitelinks': 1, 'label': {'en': 'Pat Doe (actor)'}},
    'Q1006': {'P31': ['Q5'], 'P21': ['Q6581097'], 'sitelinks': 7, 'label': {'en': 'Robin Hale'},
              'P735': [('Q4007', 'normal', [])]},
    'Q1007': {'P31': ['Q5'], 'P21': ['Q6581097'], 'nositelinks': True, 'label': {'de': 'Karl Weber'},
              'P735': [('Q4008', 'normal', [])]},
    'Q1008': {'P31': ['Q1762059'], 'sitelinks': 3, 'label': {'en': 'Test Studio'}},
    'Q1009': {'P31': ['Q5'], 'label': {'en': 'Lost Person'}},
    'Q2900': {'P31': ['Q5'], 'label': {'en': 'Person X'}},
    'Q2901': {'P31': ['Q5'], 'label': {'en': 'Person Y'}},
    'Q2902': {'P31': ['Q5'], 'label': {'en': 'Person Z'}},
    # только в съёмочной группе
    'Q1010': {'P31': ['Q5'], 'P21': ['Q6581072'], 'sitelinks': 3, 'label': {'en': 'Lena Hart', 'ru': 'Лена Харт'},
              'P27': [GENID + 'n1'], 'P569': [('1970-01-01T00:00:00Z', 9, 'normal')]},
    'Q1011': {'P31': ['Q5'], 'label': {'en': 'Omar Tune'}, 'P735': [('Q4010', 'normal', [])]},
    'Q1012': {'P31': ['Q5'], 'label': {'ru': 'Ива Монтаж'}},
    'Q1013': {'P31': ['Q5'], 'P21': ['Q6581097'], 'label': {'en': 'Sam Quill'}, 'P19': ['Q2001'],
              'P569': [('1980-07-07T00:00:00Z', 11, 'normal')], 'P570': [('2024-01-01T00:00:00Z', 9, 'normal')]},
    # элементы имён
    'Q4001': {'label': {'en': 'Jean'}, 'P31': ['Q12308941']},
    'Q4002': {'label': {'en': 'Dupont', 'mul': 'Dupont'}, 'P31': ['Q101352']},
    'Q4003': {'label': {'mul': 'Mary'}, 'P31': ['Q11879590']},
    'Q4004': {'label': {'en': 'Ann'}, 'P31': ['Q12308941', 'Q101352']},
    'Q4006': {'label': {'en': 'Smith'}, 'P31': ['Q101352']},
    'Q4007': {'label': {'en': 'Robin'}, 'P31': ['Q3409032']},
    'Q4008': {'label': {'en': 'Kai'}, 'P31': ['Q202444']},
    'Q4009': {'label': {'en': 'Jo'}, 'P31': ['Q12308941', 'Q11879590']},
    'Q4010': {'label': {'en': 'Omar'}, 'P31': ['Q12308941']},
    # персонажи
    'Q3001': {'label': {'en': 'Hero'}},
    'Q3002': {'label': {'mul': 'Jane Doe'}},
    'Q3003': {},
    'Q3004': {'label': {'en': 'Villain', 'mul': 'Villain'}},
    # страны и языки
    'Q142': {'P297': ['FR'], 'P298': ['FRA'], 'label': {'en': 'France'}},
    'Q183': {'P297': ['DE'], 'P298': ['DEU']},
    'Q17': {'P297': ['JP'], 'P298': ['JPN']},
    'Q1300': {},
    'Q30': {'P297': ['US'], 'P298': ['USA']},   # место публикации (P291)
    'Q38': {'P297': ['IT'], 'P298': ['ITA']},   # гражданство (P27)
    'Q29': {'P297': ['ES'], 'P298': ['ESP']},   # страна места (P17)
    'Q31': {'P297': ['BE'], 'P298': ['BEL']},   # страна прокатчика (P17)
    'Q145': {'P297': ['GB'], 'P298': ['GBR']},  # страна студии (P17)
    # места (P19, P915; город Q90 — ещё и место публикации)
    'Q90': {'label': {'en': 'Rivertown'}, 'P1566': ['3000001'], 'P17': ['Q142']},
    'Q2001': {'label': {'en': 'Northport', 'ru': 'Нортпорт'}, 'P1566': ['900', '10000'], 'P17': ['Q183', 'Q29']},
    'Q2002': {'label': {'de': 'Südtal'}},
    'Q2003': {'label': {'en': 'Old Mill, "East"'}, 'P1566': ['x12'], 'P17': [GENID + 'p2']},
    # студии и прокатчики, серии, награды
    'Q5001': {'label': {'en': 'Northwind Pictures', 'ru': 'Нордвинд', 'de': 'Nordwind'}, 'P17': ['Q145']},
    'Q5002': {'label': {'mul': 'Lumen'}, 'P17': ['Q183', 'Q31']},
    'Q5003': {},
    'Q6001': {'label': {'en': 'Alpha saga', 'ru': 'Сага об Альфе'}},
    'Q6002': {'label': {'fr': 'Série Gamma'}},
    'Q7001': {'label': {'en': 'Golden Reel', 'mul': 'Golden Reel', 'de': 'Goldene Rolle'}},
    'Q7002': {'label': {'ru': 'Приз зрителей'}},
    'Q150': {'P218': ['fr'], 'P220': ['fra'], 'label': {'en': 'French'}},
    'Q1860': {'P218': ['en'], 'P220': ['eng'], 'label': {'en': 'English'}},
    'Q5287': {'P218': ['ja'], 'P220': ['jpn'], 'label': {'en': 'Japanese'}},
    'Q1301': {'P220': ['qaa']},
}
for _i in range(15):  # длинная цепочка P279 — глубже, чем GENRE_DEPTH
    DB[f'Q{1500 + _i}'] = {'label': {'en': f'level {_i}'}, 'P279': [f'Q{1501 + _i}']}

KEPT = ['Q100', 'Q101', 'Q102', 'Q104', 'Q105', 'Q106', 'Q107', 'Q108', 'Q109', 'Q113', 'Q114', 'Q115', 'Q116']
ADULT_FILMS = ('Q110', 'Q111', 'Q112')
# только у фильмов для взрослых: люди, место публикации, студия, серия, награда
ADULT_ONLY = ('Q2900', 'Q2901', 'Q2902', 'Q2903', 'Q5009', 'Q6009', 'Q7009')
CREW_ONLY = ['Q1010', 'Q1011', 'Q1012', 'Q1013']
PEOPLE = [f'Q{1001 + i}' for i in range(8)] + CREW_ONLY

VALUES_RE = re.compile(r'VALUES \?(\w+) \{([^}]*)\}')
# метка запроса → вид; первая подходящая (у film_awards есть и p:P166, у film_releases — и psv:P577)
MARKERS = {
    'film': (('pqv:P585', 'awards'), ('pq:P291', 'releases'), ('psv:P1191', 'dates_extra'), ('ps:P179', 'series'),
             ('psv:P2130', 'money'), ('wdt:P344', 'crew'), ('wdt:P272', 'links2'),
             ('ps:P161', 'cast'), ('p:P166', 'signals'), ('psn:P2047', 'durations'), ('psv:P577', 'dates'),
             ('wdt:P1476', 'titles'), ('wdt:P495', 'links'), ('rdfs:label', 'film_labels')),
    'person': (('psv:P569', 'people_dates'), ('wdt:P19 ', 'people_links'),
               ('ps:P735', 'names'), ('wdt:P21', 'people'), ('rdfs:label', 'people_labels')),
    'item': (('wdt:P279', 'genres'), ('wdt:P297', 'countries'), ('wdt:P218', 'languages'), ('wdt:P1566', 'places'),
             ('wdt:P17 ', 'companies'), ('wdt:P31', 'name_items'), ('rdfs:label ?label', 'item_labels'),
             ('rdfs:label', 'characters')),
}


def uri(v):
    return {'type': 'uri', 'value': v if v.startswith('http') else ENTITY + v}


def lit(v, lang=None, dtype=None):
    b = {'type': 'literal', 'value': str(v)}
    if lang:
        b['xml:lang'] = lang
    if dtype:
        b['datatype'] = XSD + dtype
    return b


def rank_uri(rank):
    return uri(ONTOLOGY + rank.capitalize() + 'Rank')


def code_of(query):
    """Текст запроса без строк-комментариев."""
    return '\n'.join(line for line in query.split('\n') if not line.startswith('#'))


def where_groups(code):
    """Содержимое групп { … } верхнего уровня внутри WHERE, по порядку (у запроса по VALUES первая — сам VALUES),
    одной строкой."""
    body = code[code.index('WHERE {') + len('WHERE {'):]
    depth, out, cur = 1, [], []
    for ch in body:
        if ch == '{':
            depth += 1
            if depth == 2:
                cur = []
                continue
        elif ch == '}':
            depth -= 1
            if depth == 0:
                break
            if depth == 1:
                out.append(' '.join(''.join(cur).split()))
                continue
        if depth >= 2:
            cur.append(ch)
    return out


def classify(code):
    """Какой это запрос: (вид, ключ, данные) — для VALUES ключ — кортеж Q-id части."""
    m = VALUES_RE.search(code)
    if m is None:
        cls = re.search(r'wdt:P31 wd:(Q[0-9]+)', code).group(1)
        if 'wdt:P577' in code:
            frm = re.search(r'>= "([^"]+)"', code).group(1)
            to = re.search(r'< "([^"]+)"', code).group(1)
            return 'years', (cls, frm[:4], to[:4]), (cls, frm, to)
        return 'class', cls, cls
    tokens = m.group(2).split()
    if not tokens or not all(re.fullmatch(r'wd:Q[1-9][0-9]*', t) for t in tokens):
        raise AssertionError(f'VALUES не из wd:Q…: {m.group(2)[:100]}')
    items = [t[3:] for t in tokens]
    for marker, kind in MARKERS[m.group(1)]:
        if marker in code:
            if kind == 'film_labels' and allowed_langs(code) is None:
                kind = 'labels_all'  # метки на любом языке — без FILTER(LANG(…) IN …)
            return kind, tuple(items), items
    raise AssertionError(f'неизвестный запрос: {code[:200]}')


def truthy_dates(d):
    stmts = d.get('P577', ())
    best = [s for s in stmts if s[2] == 'preferred']
    return best or [s for s in stmts if s[2] == 'normal']


def allowed_langs(code):
    m = re.search(r'LANG\(\?\w+\) IN \(([^)]*)\)', code)
    return re.findall(r'"([^"]+)"', m.group(1)) if m else None


class FakeWikidata:
    """Поддельный SPARQL. faults — сбои по дороге (см. fault); extra — свои сбои: (вид, ключ, номер вызова) → ответ
    или None. faulted — сколько ответов не годились клиенту."""

    def __init__(self, db=None, faults=True, extra=None):
        self.db = DB if db is None else db
        self.faults = faults
        self.extra = extra
        self.lock = threading.Lock()
        self.calls = collections.Counter()
        self.log = []
        self.faulted = 0

    def __call__(self, path, headers, body):
        query = urllib.parse.parse_qs(body.decode('ascii'))['query'][0]
        code = code_of(query)
        kind, key, data = classify(code)
        with self.lock:
            self.calls[(kind, key)] += 1
            n = self.calls[(kind, key)]
            self.log.append((kind, key, query))
        reply = self.extra(kind, key, n) if self.extra else None
        if reply is None and self.faults:
            reply = self.fault(kind, key, data, n, headers)
        if reply is not None:
            with self.lock:
                self.faulted += 1
            return reply
        return self.answer(kind, code, data, headers)

    def answer(self, kind, code, data, headers, cut=None):
        rows = getattr(self, 'q_' + kind)(code, data)
        body = json.dumps({'head': {'vars': []}, 'results': {'bindings': rows}}, ensure_ascii=False).encode()
        hdr = {'Content-Type': 'application/sparql-results+json;charset=utf-8'}
        if cut == 'json':
            body = body[:len(body) // 2]
        if 'gzip' in headers.get('Accept-Encoding', ''):
            body = gzip.compress(body, mtime=0)
            hdr['Content-Encoding'] = 'gzip'
        if cut == 'gzip':
            body = body[:len(body) // 2]
        return Reply(body, headers=hdr)

    def fault(self, kind, key, data, n, headers):
        if kind == 'class' and key == 'Q24869' and n <= 2:
            return Reply(TIMEOUT_BODY, status=500)
        if kind == 'years' and key == ('Q24869', '1990', '2000') and n <= 2:
            return Reply(TIMEOUT_BODY, status=500)
        if kind == 'class' and key == 'Q202866' and n == 1:
            return self.answer(kind, '', data, headers, cut='json')  # HTTP 200, JSON оборван
        if kind == 'dates' and 'Q100' in key and len(key) >= 2:
            return Reply(TIMEOUT_BODY, status=500)
        if kind == 'film_labels' and 'Q106' in key and len(key) >= 3 and n == 1:
            time.sleep(SLOW)  # клиент не дождётся
            return Reply(b'{}')
        if kind == 'titles' and 'Q104' in key and len(key) >= 2 and n == 1:
            return self.answer(kind, '', data, headers, cut='gzip')  # HTTP 200, gzip оборван
        if kind == 'people' and 'Q1001' in key and n == 1:
            return Reply(b'Too Many Requests', status=429, headers={'Retry-After': '0'})
        if kind == 'countries' and n == 1:
            return Reply(b'Service Unavailable', status=503, headers={'Retry-After': '0'})
        # новые шаги снимка v2: тайм-аут на шаге по фильмам и оборванный JSON на шаге по людям — делятся так же
        if kind == 'crew' and 'Q100' in key and len(key) >= 2:
            return Reply(TIMEOUT_BODY, status=500)
        if kind == 'people_dates' and 'Q1010' in key and len(key) >= 2 and n == 1:
            return self.answer(kind, '', data, headers, cut='json')
        return None

    def keep(self, code, rank):
        """Утверждение deprecated отбрасывается, только если фильтр есть в запросе."""
        return not (rank == 'deprecated' and 'FILTER(?rank != wikibase:DeprecatedRank)' in code)

    def item(self, q):
        return self.db.get(q, {})

    def q_class(self, code, cls):
        return [{'film': uri(q)} for q, d in self.db.items() if cls in d.get('P31', ())]

    def q_years(self, code, data):
        cls, frm, to = data
        return [{'film': uri(q)} for q, d in self.db.items()
                if cls in d.get('P31', ()) and any(frm <= s[0] < to for s in truthy_dates(d))]

    def links(self, xs, var, props, value):
        return [{var: uri(x), 'prop': lit(p), value: uri(v)}
                for x in xs for p in props for v in self.item(x).get(p, ())]

    def q_links(self, code, films):
        return self.links(films, 'film', ('P31', 'P136', 'P57', 'P495', 'P364'), 'value')

    def q_crew(self, code, films):
        return self.links(films, 'film', ('P58', 'P86', 'P344', 'P162', 'P1040'), 'person')

    def q_links2(self, code, films):
        return self.links(films, 'film', ('P272', 'P750', 'P915', 'P155', 'P156'), 'value')

    def q_people_links(self, code, people):
        return self.links(people, 'person', ('P19', 'P27'), 'value')

    def q_genres(self, code, items):
        out = []
        for q in items:
            d = self.item(q)
            if 'en' in d.get('label', {}):
                out.append({'item': uri(q), 'kind': lit('en'), 'value': lit(d['label']['en'], 'en')})
            out += [{'item': uri(q), 'kind': lit('P279'), 'value': uri(p)} for p in d.get('P279', ())]
        return out

    def q_dates(self, code, films):
        return [{'film': uri(f), 'date': lit(date, dtype='dateTime'), 'precision': lit(prec, dtype='integer'),
                 'rank': rank_uri(rank)}
                for f in films for date, prec, rank, *_ in self.item(f).get('P577', ()) if self.keep(code, rank)]

    def times(self, code, xs, var, props):
        """Даты вида ?var ?prop ?date ?precision ?rank — по утверждению на строку."""
        return [{var: uri(x), 'prop': lit(p), 'date': lit(date, dtype='dateTime'),
                 'precision': lit(prec, dtype='integer'), 'rank': rank_uri(rank)}
                for x in xs for p in props for date, prec, rank in self.item(x).get(p, ()) if self.keep(code, rank)]

    def q_dates_extra(self, code, films):
        return self.times(code, films, 'film', ('P1191', 'P571', 'P2754'))

    def q_people_dates(self, code, people):
        return self.times(code, people, 'person', ('P569', 'P570'))

    def q_releases(self, code, films):
        # решение SPARQL: утверждение P577 × каждое его P291; без P291 — ни одной строки
        return [{'film': uri(f), 'date': lit(s[0], dtype='dateTime'), 'precision': lit(s[1], dtype='integer'),
                 'place': uri(place), 'rank': rank_uri(s[2])}
                for f in films for s in self.item(f).get('P577', ()) if self.keep(code, s[2])
                for place in (s[3] if len(s) > 3 else ())]

    def q_series(self, code, films):
        out = []
        for f in films:
            for series, rank, ords in self.item(f).get('P179', ()):
                if not self.keep(code, rank):
                    continue
                for o in ords or [None]:
                    b = {'film': uri(f), 'series': uri(series)}
                    if o is not None:
                        b['ordinal'] = lit(o)
                    out.append(b)
        return out

    def q_money(self, code, films):
        return [{'film': uri(f), 'prop': lit(p), 'amount': lit(amount, dtype='decimal'), 'unit': uri(unit),
                 'rank': rank_uri(rank)}
                for f in films for p in ('P2130', 'P2142') for amount, unit, rank in self.item(f).get(p, ())
                if self.keep(code, rank)]

    def q_awards(self, code, films):
        out = []
        for f in films:
            for p in ('P166', 'P1411'):
                for s in self.item(f).get(p, ()):
                    if not self.keep(code, s['rank']):
                        continue
                    for when in s['P585'] or [None]:
                        b = {'film': uri(f), 'prop': lit(p), 'award': uri(s['award'])}
                        if when is not None:
                            b['date'] = lit(when[0], dtype='dateTime')
                            b['precision'] = lit(when[1], dtype='integer')
                        out.append(b)
        return out

    def labels(self, code, xs, var):
        langs = allowed_langs(code)
        return [{var: uri(x), 'lang': lit(lang), 'label': lit(text, lang)}
                for x in xs for lang, text in self.item(x).get('label', {}).items() if langs is None or lang in langs]

    def q_film_labels(self, code, films):
        return self.labels(code, films, 'film')

    def q_labels_all(self, code, films):
        return self.labels(code, films, 'film')

    def q_item_labels(self, code, items):
        return self.labels(code, items, 'item')

    def q_people_labels(self, code, people):
        return self.labels(code, people, 'person')

    def q_titles(self, code, films):
        return [{'film': uri(f), 'lang': lit(lang), 'title': lit(text, lang)}
                for f in films for lang, text in self.item(f).get('P1476', ())]

    def q_durations(self, code, films):
        return [{'film': uri(f), 'seconds': lit(amount, dtype='decimal')}
                for f in films for amount, rank in self.item(f).get('P2047', ()) if self.keep(code, rank)]

    def q_signals(self, code, films):
        out = []

        def group(f, kind, values):
            out.append({'film': uri(f), 'kind': lit(kind), 'count': lit(len(values), dtype='integer'),
                        'first': lit(min(values))})

        for f in films:
            d = self.item(f)
            if f in self.db and not d.get('nositelinks'):
                group(f, 'sitelinks', [str(d.get('sitelinks', 0))])
            for prop in ('P166', 'P1411'):
                stmts = [f'{ENTITY}statement/{f}-{prop}-{i}' for i, s in enumerate(d.get(prop, ()))
                         if self.keep(code, s['rank'])]
                if stmts:
                    group(f, prop, stmts)
            if d.get('P345'):
                group(f, 'P345', d['P345'])
        return out

    def q_cast(self, code, films):
        out = []
        for f in films:
            for s in self.item(f).get('cast', ()):
                if not self.keep(code, s['rank']):
                    continue
                for o, c, n in itertools.product(s['P1545'] or [None], s['P453'] or [None], s['P4633'] or [None]):
                    b = {'film': uri(f), 'prop': lit(s['prop']), 'person': uri(s['person'])}
                    if o is not None:
                        b['ordinal'] = lit(o)
                    if c is not None:
                        b['character'] = uri(c)
                    if n is not None:
                        b['name'] = lit(n)
                    out.append(b)
        return out

    def q_people(self, code, people):
        out = []
        for p in people:
            if p not in self.db:
                continue
            d = self.item(p)
            if not d.get('nositelinks'):
                out.append({'person': uri(p), 'kind': lit('sitelinks'),
                            'value': lit(d.get('sitelinks', 0), dtype='integer')})
            if 'Q5' in d.get('P31', ()):
                out.append({'person': uri(p), 'kind': lit('P31'), 'value': uri('Q5')})
            out += [{'person': uri(p), 'kind': lit('P21'), 'value': uri(g)} for g in d.get('P21', ())]
        return out

    def q_names(self, code, people):
        out = []
        for p in people:
            for kind, prop in (('given', 'P735'), ('family', 'P734')):
                for item, rank, ords in self.item(p).get(prop, ()):
                    if not self.keep(code, rank):
                        continue
                    for o in ords or [None]:
                        b = {'person': uri(p), 'kind': lit(kind), 'item': uri(item)}
                        if o is not None:
                            b['ordinal'] = lit(o)
                        out.append(b)
        return out

    def item_labels(self, code, q):
        langs = allowed_langs(code) or ['en', 'mul']
        return [{'item': uri(q), 'kind': lit(lang), 'value': lit(text, lang)}
                for lang, text in self.item(q).get('label', {}).items() if lang in langs]

    def q_name_items(self, code, items):
        return [b for q in items for b in self.item_labels(code, q)
                + [{'item': uri(q), 'kind': lit('P31'), 'value': uri(c)} for c in self.item(q).get('P31', ())]]

    def q_characters(self, code, items):
        return [b for q in items for b in self.item_labels(code, q)]

    def codes(self, items, props, en=False):
        out = []
        for q in items:
            d = self.item(q)
            out += [{'item': uri(q), 'kind': lit(p), 'value': lit(v)} for p in props for v in d.get(p, ())]
            if en and 'en' in d.get('label', {}):
                out.append({'item': uri(q), 'kind': lit('en'), 'value': lit(d['label']['en'], 'en')})
        return out

    def q_countries(self, code, items):
        return self.codes(items, ('P297', 'P298'))

    def q_languages(self, code, items):
        return self.codes(items, ('P218', 'P220'), en=True)

    def q_places(self, code, items):
        out = []
        for q in items:
            d = self.item(q)
            out += [{'item': uri(q), 'kind': lit('P1566'), 'value': lit(v)} for v in d.get('P1566', ())]
            out += [{'item': uri(q), 'kind': lit('P17'), 'value': uri(v)} for v in d.get('P17', ())]
            if 'en' in d.get('label', {}):
                out.append({'item': uri(q), 'kind': lit('en'), 'value': lit(d['label']['en'], 'en')})
        return out

    def q_companies(self, code, items):
        return [{'item': uri(q), 'country': uri(c)} for q in items for c in self.item(q).get('P17', ())]

    def requested(self, kind):
        """Части, которые спрашивали (ключ — кортеж Q-id), со счётом."""
        return collections.Counter(k for t, k, _ in self.log if t == kind)


def run_fetch(server, out, work=None, env=None, chunk='4', workers='3'):
    """fetch.main в этом процессе: адрес — локальный сервер, без пауз и нижних границ, части по 4, делятся до 1;
    итог — (код, stdout, stderr)."""
    full = {
        'MOV_WIKIDATA_SPARQL': server.base + '/sparql',
        'MOV_FETCH_BACKOFF': '0',
        'MOV_FETCH_MIN': '0',
        'MOV_FETCHED_AT': FETCHED_AT,
        'MOV_FETCH_WORKERS': workers,
        'MOV_FETCH_CHUNK': chunk,
        'NO_PROXY': '127.0.0.1,localhost',
        'no_proxy': '127.0.0.1,localhost',
    }
    full.update(env or {})
    argv = ['--snapshot', SNAPSHOT, '--out', str(out)] + (['--work', str(work)] if work else [])
    stdout, stderr = io.StringIO(), io.StringIO()
    with mock.patch.dict(os.environ, {k: v for k, v in full.items() if v is not None}), \
            mock.patch.object(fetch, 'MIN_CHUNK', 1), mock.patch.object(fetch, 'TIMEOUT', CLIENT_TIMEOUT), \
            contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        for k, v in full.items():
            if v is None:
                os.environ.pop(k, None)
        code = fetch.main(argv)
    return code, stdout.getvalue(), stderr.getvalue()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def lines(*rows):
    return ''.join(r + '\n' for r in rows)


EXPECTED = {
    'wikidata/classes.csv': lines(
        'class,label_en,included', 'Q11424,film,1', 'Q24856,film series,0', 'Q24862,short film,1',
        'Q24869,feature film,1', 'Q93204,documentary film,1', 'Q193977,music video,0', 'Q202866,animated film,1',
        'Q226730,silent film,1', 'Q506240,television film,1', 'Q622550,trailer,0', 'Q17517379,animated short film,1',
        'Q18011172,film project,0', 'Q18089587,stop-motion animated film,1', 'Q20650540,anime film,1',
        'Q29168811,animated feature film,1'),
    'wikidata/films.csv.gz': lines(
        'film,class', 'Q100,Q11424', 'Q100,Q24869', 'Q101,Q24869', 'Q102,Q24869', 'Q104,Q202866', 'Q105,Q11424',
        'Q105,Q193977', 'Q106,Q93204', 'Q107,Q24862', 'Q108,Q11424', 'Q109,Q506240', 'Q113,Q11424',
        'Q113,Q226730', 'Q114,Q17517379', 'Q115,Q11424', 'Q116,Q11424'),
    'wikidata/film_dates.csv.gz': lines(
        'film,date,precision,rank', 'Q100,1999-01-01T00:00:00Z,9,preferred', 'Q100,1999-03-31T00:00:00Z,11,normal',
        'Q101,1995-06-01T00:00:00Z,11,normal', 'Q102,2012-01-01T00:00:00Z,9,normal',
        'Q104,-0050-01-01T00:00:00Z,9,normal', 'Q105,2001-05-05T00:00:00Z,11,normal',
        'Q106,2005-04-01T00:00:00Z,10,normal', 'Q107,2015-01-01T00:00:00Z,9,normal',
        'Q109,1988-01-01T00:00:00Z,9,normal', 'Q113,1920-01-01T00:00:00Z,9,normal',
        'Q114,2020-02-02T00:00:00Z,11,normal'),
    'wikidata/film_labels.csv.gz': lines(
        'film,lang,label', 'Q100,en,Alpha', 'Q100,fr,Alpha', 'Q100,ru,Альфа', 'Q101,mul,Beta', 'Q102,de,Gamma (Film)',
        'Q104,en,Epsilon', 'Q105,en,"Zeta, ""the clip"""', 'Q106,en,Eta', 'Q106,ru,Эта', 'Q107,en,Theta',
        'Q108,en,Iota', 'Q109,en,Kappa', 'Q113,en,Lambda', 'Q113,sv,Lambda', 'Q114,pl,Mi'),
    'wikidata/film_titles.csv.gz': lines(
        'film,lang,title', 'Q100,en,Alpha', 'Q100,fr,"Alpha, le film"', 'Q113,en,Lambda', 'Q116,ja,デルタ'),
    'wikidata/film_links.csv.gz': lines(
        'film,prop,value', 'Q100,P136,Q130232', 'Q100,P364,Q150', 'Q100,P495,Q142', 'Q100,P57,Q1001',
        'Q101,P136,Q157443', 'Q101,P364,Q1860', 'Q101,P495,Q142', 'Q101,P495,Q183', 'Q101,P57,Q1002',
        'Q104,P136,Q1403', 'Q104,P57,Q1008', 'Q106,P364,Q5287', 'Q106,P495,Q17', 'Q107,P136,Q1500',
        'Q113,P364,Q1301', 'Q113,P495,Q1300'),
    'wikidata/film_durations.csv.gz': lines(
        'film,seconds', 'Q100,5400', 'Q104,90.5', 'Q106,3600', 'Q106,3660'),
    'wikidata/film_signals.csv.gz': lines(
        'film,sitelinks,awards,nominations,imdb', 'Q100,25,2,1,tt0000100', 'Q101,3,0,0,tt0000101', 'Q102,1,0,0,',
        'Q104,0,0,0,', 'Q105,0,0,0,', 'Q106,2,0,2,', 'Q107,0,0,0,', 'Q108,0,0,0,', 'Q109,0,0,0,', 'Q113,5,0,0,',
        'Q114,0,0,0,', 'Q115,1,0,0,', 'Q116,0,0,0,'),
    'wikidata/film_cast.csv.gz': lines(
        'film,prop,person,ordinal,character,character_name',
        'Q100,P161,Q1001,,,',                        # без квалификаторов — одна строка с пустыми
        'Q100,P161,Q1002,,Q3001,',                   # только P453
        'Q100,P161,Q1003,,,"Bob, the ""Builder"""',  # только P4633
        'Q100,P161,Q1004,,Q3002,Jane',               # оба — одной строкой
        'Q100,P161,Q1005,,Q3003,',                   # два P453 — две строки
        'Q100,P161,Q1005,,Q3004,',
        'Q100,P161,Q1007,,,A',                       # два P4633 — две строки
        'Q100,P161,Q1007,,,B',
        'Q100,P725,Q1006,1,,Narrator',
        'Q101,P161,Q1001,,,',                        # P453 — «неизвестное значение»: персонажа нет
        'Q108,P161,Q1003,,Q3001,Mask',               # два P453 и два P4633 — перекрёсток, четыре строки
        'Q108,P161,Q1003,,Q3001,Shadow',
        'Q108,P161,Q1003,,Q3002,Mask',
        'Q108,P161,Q1003,,Q3002,Shadow',
        'Q108,P725,Q1006,2,,'),
    'wikidata/people.csv.gz': lines(
        'person,human,gender,sitelinks', 'Q1001,1,Q6581097,10', 'Q1002,1,Q1052281,4', 'Q1003,1,,0', 'Q1004,0,,2',
        'Q1005,1,,1', 'Q1006,1,Q6581097,7', 'Q1007,1,Q6581097,0', 'Q1008,0,,3',
        'Q1010,1,Q6581072,3', 'Q1011,1,,0', 'Q1012,1,,0', 'Q1013,1,Q6581097,0'),  # из съёмочной группы
    'wikidata/people_labels.csv.gz': lines(
        'person,lang,label', 'Q1001,en,Jean Dupont', 'Q1001,fr,Jean Dupont', 'Q1001,ru,Жан Дюпон',
        'Q1002,en,Mary Smith', 'Q1003,mul,Kim Lee', 'Q1004,en,Rex', 'Q1005,en,Pat Doe (actor)',
        'Q1006,en,Robin Hale', 'Q1007,de,Karl Weber', 'Q1008,en,Test Studio', 'Q1010,en,Lena Hart',
        'Q1010,ru,Лена Харт', 'Q1011,en,Omar Tune', 'Q1012,ru,Ива Монтаж', 'Q1013,en,Sam Quill'),
    'wikidata/people_names.csv.gz': lines(
        'person,kind,item,ordinal', 'Q1001,family,Q4002,', 'Q1001,given,Q4001,', 'Q1002,family,Q4006,',
        'Q1002,given,Q4003,1', 'Q1002,given,Q4004,2', 'Q1003,given,Q4009,', 'Q1006,given,Q4007,',
        'Q1007,given,Q4008,', 'Q1011,given,Q4010,'),
    'wikidata/name_items.csv.gz': lines(
        'item,label_en,label_mul,kind', 'Q4001,Jean,,male', 'Q4002,Dupont,Dupont,family', 'Q4003,,Mary,female',
        'Q4004,Ann,,male', 'Q4006,Smith,,family', 'Q4007,Robin,,unisex', 'Q4008,Kai,,other', 'Q4009,Jo,,unisex',
        'Q4010,Omar,,male'),
    'wikidata/characters.csv.gz': lines(
        'item,label_en,label_mul', 'Q3001,Hero,', 'Q3002,,Jane Doe', 'Q3003,,', 'Q3004,Villain,Villain'),
    'wikidata/countries.csv': lines(
        'item,iso2,iso3', 'Q17,JP,JPN',
        'Q29,ES,ESP',                  # P17 места Q2001 (первая по номеру, не по алфавиту)
        'Q30,US,USA',                  # P291 фильма Q101 (у deprecated-утверждения Q100 — не в счёт)
        'Q31,BE,BEL',                  # P17 прокатчика Q5002 (так же)
        'Q38,IT,ITA',                  # P27
        'Q90,,',                       # P291 — город: строка с пустыми кодами
        'Q142,FR,FRA',
        'Q145,GB,GBR',                 # P17 студии
        'Q183,DE,DEU', 'Q1300,,'),
    'wikidata/languages.csv': lines(
        'item,iso1,iso3,label_en', 'Q150,fr,fra,French', 'Q1301,,qaa,', 'Q1860,en,eng,English',
        'Q5287,ja,jpn,Japanese'),
    'wikidata/genre_items.csv': lines(
        'item,label_en,parent', 'Q1403,genre A,Q1404', 'Q1404,genre B,Q1403',
        *[f'Q{1500 + i},level {i},Q{1501 + i}' for i in range(13)],
        'Q1900,visual work,', 'Q1901,drama,', 'Q11424,film,Q1900', 'Q24856,film series,',
        'Q24862,short film,Q11424', 'Q24869,feature film,Q11424', 'Q93204,documentary film,Q11424',
        'Q130232,drama film,Q1901', 'Q130232,drama film,Q11424', 'Q157443,comedy film,Q11424',
        'Q193977,music video,Q1900', 'Q202866,animated film,Q11424', 'Q226730,silent film,Q11424',
        'Q506240,television film,Q11424', 'Q622550,trailer,', 'Q17517379,animated short film,Q24862',
        'Q17517379,animated short film,Q202866', 'Q18011172,film project,',
        'Q18089587,stop-motion animated film,Q202866', 'Q20650540,anime film,Q202866',
        'Q29168811,animated feature film,Q24869', 'Q29168811,animated feature film,Q202866'),
    # ---- снимок v2 ----
    'wikidata/film_dates_extra.csv.gz': lines(
        'film,prop,date,precision,rank',
        'Q100,P1191,1998-09-10T00:00:00Z,11,normal',
        'Q100,P571,1997-01-01T00:00:00Z,9,preferred',    # deprecated P571 — нет
        'Q102,P2754,2010-06-01T00:00:00Z,10,normal',
        'Q115,P1191,2003-04-05T00:00:00Z,11,normal',     # фильм без P577
        'Q116,P2754,2009-01-01T00:00:00Z,9,normal'),
    'wikidata/film_releases.csv.gz': lines(
        'film,date,precision,place',
        'Q100,1999-03-31T00:00:00Z,11,Q142',             # два P291 одного утверждения — две строки;
        'Q100,1999-03-31T00:00:00Z,11,Q183',             # deprecated (с P291) и preferred без P291 — ни одной
        'Q101,1995-06-01T00:00:00Z,11,Q30',              # третий P291 — «неизвестное значение»: строки нет
        'Q101,1995-06-01T00:00:00Z,11,Q90',
        'Q106,2005-04-01T00:00:00Z,10,Q17'),
    'wikidata/film_labels_all.csv.gz': lines(
        'film,lang,label', 'Q115,ja,ガンマ', 'Q115,ko,"Gamma, ""K"""', 'Q115,zh,伽马'),  # Q116 (есть P1476) — нет
    'wikidata/film_crew.csv.gz': lines(
        'film,prop,person', 'Q100,P1040,Q1012', 'Q100,P162,Q1010', 'Q100,P58,Q1001', 'Q100,P58,Q1010',
        'Q100,P86,Q1011', 'Q101,P86,Q1002', 'Q115,P58,Q1013'),  # P344 Q100 — «неизвестное значение»: строки нет
    'wikidata/film_links2.csv.gz': lines(
        'film,prop,value', 'Q100,P156,Q101', 'Q100,P272,Q5001', 'Q100,P750,Q5002', 'Q100,P750,Q5003',
        'Q100,P915,Q90', 'Q100,P915,Q2001', 'Q101,P155,Q100', 'Q101,P750,Q5002', 'Q106,P915,Q2003'),
    'wikidata/film_series.csv.gz': lines(
        'film,series,ordinal',
        'Q100,Q6001,1',                                  # deprecated Q6002 — нет
        'Q101,Q6001,2',                                  # два P1545 одного утверждения — две строки
        'Q101,Q6001,2a',
        'Q102,Q6002,'),                                  # без P1545 — одна строка с пустым
    'wikidata/film_money.csv.gz': lines(
        'film,prop,amount,unit,rank',
        'Q100,P2130,12000000,Q4917,preferred',           # «1.2E+7» — точно, без показателя
        'Q100,P2130,15000000,Q4917,normal',              # «+15000000» — без знака; deprecated «9» — нет
        'Q100,P2142,80000000,Q4916,normal',              # «80000000.00» — без лишних нулей; другая валюта
        'Q100,P2142,100000000,Q4917,normal',             # сортировка сумм — как чисел
        'Q101,P2142,2500000.5,Q4917,normal'),
    'wikidata/film_awards.csv.gz': lines(
        'film,prop,award,date,precision',
        'Q100,P1411,Q7001,,',                            # номинация без P585 — одна строка с пустыми
        'Q100,P166,Q7001,2000-01-01T00:00:00Z,9',        # два P585 одного утверждения — две строки;
        'Q100,P166,Q7001,2000-03-26T00:00:00Z,11',       # deprecated P166 Q7001 — нет
        'Q100,P166,Q7002,,',
        'Q106,P1411,Q7002,,'),                           # номинация на «неизвестное значение» — нет
    'wikidata/people_dates.csv.gz': lines(
        'person,prop,date,precision,rank',
        'Q1001,P569,1950-02-03T00:00:00Z,11,normal',     # deprecated P569 — нет
        'Q1001,P570,2019-12-31T00:00:00Z,11,normal',
        'Q1001,P570,2020-01-01T00:00:00Z,9,preferred',
        'Q1002,P569,1960-01-01T00:00:00Z,9,normal',
        'Q1010,P569,1970-01-01T00:00:00Z,9,normal',      # люди из съёмочной группы — тоже
        'Q1013,P569,1980-07-07T00:00:00Z,11,normal',
        'Q1013,P570,2024-01-01T00:00:00Z,9,normal'),
    'wikidata/people_links.csv.gz': lines(
        'person,prop,value', 'Q1001,P19,Q90', 'Q1001,P27,Q142', 'Q1002,P19,Q2002', 'Q1002,P27,Q38',
        'Q1002,P27,Q142', 'Q1013,P19,Q2001'),            # P27 Q1010 — «неизвестное значение»: строки нет
    'wikidata/places.csv.gz': lines(
        'item,geonames,country,label_en',
        'Q90,3000001,Q142,Rivertown',
        'Q2001,900,Q29,Northport',                       # 900 и 10000 — наименьший по числу; Q183 и Q29 — по номеру
        'Q2002,,,',                                      # нет ни P1566, ни P17, ни метки en
        'Q2003,,,"Old Mill, ""East"""'),                 # P1566 не число, P17 — «неизвестное значение»
    'wikidata/item_labels.csv.gz': lines(
        'item,lang,label', 'Q5001,en,Northwind Pictures', 'Q5001,ru,Нордвинд', 'Q5002,mul,Lumen',
        'Q6001,en,Alpha saga', 'Q6001,ru,Сага об Альфе', 'Q7001,en,Golden Reel', 'Q7001,mul,Golden Reel',
        'Q7002,ru,Приз зрителей'),                       # de и fr — не на этих языках
    'wikidata/companies.csv': lines(
        'item,country', 'Q5001,Q145', 'Q5002,Q31', 'Q5003,'),  # Q183 и Q31 — первая по номеру
}
DATA_FILES = sorted(EXPECTED)
NEW_FILES = ('film_dates_extra', 'film_releases', 'film_labels_all', 'film_crew', 'film_links2', 'film_series',
             'film_money', 'film_awards', 'people_dates', 'people_links', 'places', 'item_labels', 'companies')


# ---- тесты ----

class FullRunTest(unittest.TestCase):
    """Удачный прогон (со сбоями по дороге) и второй — на таком же сервисе: тот же архив."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(cls.tmp.name)
        cls.server = Server()
        cls.fake = FakeWikidata()
        cls.server.route('POST', '/sparql', cls.fake)
        cls.out = root / 'out'
        cls.started = time.monotonic()
        cls.code, cls.stdout, cls.stderr = run_fetch(cls.server, cls.out, work=root / 'work')
        if cls.code != 0:
            raise AssertionError(f'прогон упал: {cls.stderr}\n{cls.stdout}')
        cls.requests_first = list(cls.server.requests)
        cls.zip_bytes = (cls.out / f'{SNAPSHOT}.zip').read_bytes()
        cls.zf = zipfile.ZipFile(io.BytesIO(cls.zip_bytes))
        cls.names = cls.zf.namelist()
        cls.fake2 = FakeWikidata()
        cls.server.route('POST', '/sparql', cls.fake2)
        cls.out2 = root / 'out2'
        cls.code2, cls.stdout2, cls.stderr2 = run_fetch(cls.server, cls.out2)

    @classmethod
    def tearDownClass(cls):
        cls.zf.close()
        cls.server.close()
        cls.tmp.cleanup()

    def read(self, name):
        return self.zf.read(name)

    def text(self, name):
        data = self.read(name)
        return (gzip.decompress(data) if name.endswith('.gz') else data).decode('utf-8')

    def test_outputs(self):
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), ['SHA256SUMS', 'SOURCE.md', f'{SNAPSHOT}.zip'])
        self.assertEqual((self.out / 'SOURCE.md').read_bytes(), self.read('SOURCE.md'))
        self.assertEqual((self.out / 'SHA256SUMS').read_bytes(), self.read('SHA256SUMS'))
        self.assertTrue((pathlib.Path(self.tmp.name) / 'work' / SNAPSHOT / 'fetch.json').is_file())

    def test_layout(self):
        expected = (['SHA256SUMS', 'SOURCE.md', 'fetch.json'] + DATA_FILES
                    + [f'wikidata/queries/{n}.rq' for n in fetch.QUERIES])
        self.assertEqual(sorted(self.names), sorted(expected))
        self.assertEqual(self.names, sorted(self.names, key=lambda s: s.encode('utf-8')), 'записи — по имени')
        self.assertEqual(len(fetch.QUERIES), 30)
        for stem in NEW_FILES:  # снимок v2: у каждого нового файла — свой запрос
            self.assertIn(stem, fetch.QUERIES)
            self.assertTrue(f'wikidata/{stem}.csv.gz' in self.names or f'wikidata/{stem}.csv' in self.names, stem)

    def test_zip_entries(self):
        for info in self.zf.infolist():
            self.assertEqual(info.date_time, (1980, 1, 1, 0, 0, 0), info.filename)
            self.assertEqual(info.external_attr >> 16, 0o100644, info.filename)
            self.assertFalse(info.is_dir())
            want = zipfile.ZIP_STORED if info.filename.endswith(('.zip', '.gz')) else zipfile.ZIP_DEFLATED
            self.assertEqual(info.compress_type, want, info.filename)
        self.assertIsNone(self.zf.testzip())

    def test_gzip_members(self):
        gz = [n for n in self.names if n.endswith('.gz')]
        self.assertEqual(len(gz), 25)
        for name in gz:
            data = self.read(name)
            self.assertEqual(data[:4], b'\x1f\x8b\x08\x00', f'{name}: без имени файла и флагов')
            self.assertEqual(data[4:8], b'\x00\x00\x00\x00', f'{name}: время 0')
            self.assertEqual(data[9], 255, f'{name}: ОС «неизвестна»')
            self.assertEqual(gzip.decompress(data).decode('utf-8'), EXPECTED[name], name)

    def test_determinism(self):
        self.assertEqual(self.code2, 0, self.stderr2)
        self.assertEqual(sha256((self.out2 / f'{SNAPSHOT}.zip').read_bytes()), sha256(self.zip_bytes))
        self.assertEqual((self.out2 / 'SOURCE.md').read_bytes(), (self.out / 'SOURCE.md').read_bytes())

    def test_sha256sums(self):
        sums = self.read('SHA256SUMS').decode('utf-8').splitlines()
        paths = [line.split('  ', 1)[1] for line in sums]
        self.assertEqual(paths, [n for n in self.names if n != 'SHA256SUMS'])
        for line in sums:
            digest, path = line.split('  ', 1)
            self.assertEqual(digest, sha256(self.read(path)), path)
        self.assertTrue(self.read('SHA256SUMS').endswith(b'\n'))

    def test_fetch_json(self):
        doc = json.loads(self.read('fetch.json'))
        self.assertEqual(list(doc), ['snapshot', 'fetched_at', 'files', 'skipped'])
        self.assertEqual((doc['snapshot'], doc['fetched_at'], doc['skipped']), (SNAPSHOT, FETCHED_AT, []))
        files = doc['files']
        self.assertEqual(sorted(files), sorted(n for n in self.names if n not in ('SOURCE.md', 'SHA256SUMS',
                                                                                    'fetch.json')))
        for path, e in files.items():
            self.assertEqual(list(e), ['url', 'bytes', 'sha256', 'rows'], path)
            data = self.read(path)
            self.assertEqual(e['bytes'], len(data), path)
            self.assertEqual(e['sha256'], sha256(data), path)
            if path.endswith('.rq'):
                self.assertIsNone(e['url'], path)
                self.assertIsNone(e['rows'], path)
            else:
                self.assertEqual(e['url'], self.server.base + '/sparql', path)
                self.assertEqual(e['rows'], self.text(path).count('\n') - 1, path)

    def test_csv_exact(self):
        for name in DATA_FILES:
            with self.subTest(name=name):
                self.assertEqual(self.text(name), EXPECTED[name])

    def test_queries_saved(self):
        for name, text in fetch.QUERIES.items():
            self.assertEqual(self.read(f'wikidata/queries/{name}.rq').decode('utf-8'), text, name)
        template = fetch.QUERIES['film_dates']
        self.assertEqual(template.count(fetch.VALUES), 1)
        sent = [q for kind, key, q in self.fake.log if kind == 'dates']
        self.assertIn(template.replace(fetch.VALUES, 'wd:Q100 wd:Q101 wd:Q102 wd:Q104'), sent)
        self.assertIn(fetch.QUERIES['films_by_class'].replace(fetch.CLASS, 'Q11424'),
                      [q for kind, _, q in self.fake.log if kind == 'class'])

    def test_portable_queries(self):
        for kind, key, query in self.fake.log:
            with self.subTest(kind=kind, key=key):
                for bad in ('SERVICE', 'wikibase:label', 'hint:', 'bd:', 'mwapi'):
                    self.assertNotIn(bad, query)
                declared = set(re.findall(r'^PREFIX (\w+): <[^>]+>$', query, re.M))
                body = re.sub(r'"[^"]*"', '""', re.sub(r'<[^>]*>', '<>', code_of(query)))
                body = '\n'.join(line for line in body.split('\n') if not line.startswith('PREFIX '))
                used = set(re.findall(r'(?<![\w:?])([a-z]+):[A-Za-z]', body))
                self.assertTrue(used, query)
                self.assertLessEqual(used, declared, query)

    def test_requests(self):
        posts = [r for r in self.requests_first if r[0] == 'POST']
        self.assertEqual(len(posts), len(self.fake.log))
        for _, path, headers, body in posts:
            self.assertEqual(path, '/sparql')
            self.assertEqual(headers.get('User-Agent'), UA)
            self.assertEqual(headers.get('Accept'), 'application/sparql-results+json')
            self.assertEqual(headers.get('Content-Type'), 'application/x-www-form-urlencoded')
            self.assertEqual(headers.get('Accept-Encoding'), 'gzip')
            self.assertEqual(list(urllib.parse.parse_qs(body.decode('ascii'))), ['query'])

    def test_class_queries(self):
        classes = self.fake.requested('class')
        want = {c: 1 for c in fetch.FILM_CLASSES}
        want.update({'Q24869': 2, 'Q202866': 2})  # два тайм-аута — по годам; оборванный JSON — повтор того же
        self.assertEqual(dict(classes), want)
        for c in fetch.EXCLUDED_CLASSES + (ADULT,):
            self.assertNotIn(c, classes, 'класс не из FILM_CLASSES не запрашивается')
        self.assertIn('попытка 1/6 не удалась', self.stdout)
        self.assertIn('films_by_class Q202866): не JSON', self.stdout)

    def test_year_fallback(self):
        years = self.fake.requested('years')
        decades = [('Q24869', str(y), str(min(y + 10, 2027))) for y in range(1870, 2027, 10)]
        singles = [('Q24869', str(y), str(y + 1)) for y in range(1990, 2000)]
        want = collections.Counter({k: 1 for k in decades + singles})
        want[('Q24869', '1990', '2000')] = 2
        self.assertEqual(years, want)
        self.assertEqual(len(decades), 16)
        self.assertIn('класс Q24869 целиком дважды не уложился', self.stdout)
        self.assertIn('Q24869, 1990–1999 — дважды не уложился', self.stdout)
        films = self.text('wikidata/films.csv.gz')
        self.assertNotIn('Q103', films, 'фильм без P577 по годам не находится')
        self.assertIn('Q101,Q24869', films)

    def test_halving(self):
        tail = {('Q109', 'Q113', 'Q114', 'Q115'): 1, ('Q116',): 1}
        self.assertEqual(self.fake.requested('dates'), collections.Counter({
            ('Q100', 'Q101', 'Q102', 'Q104'): 1, ('Q100', 'Q101'): 1, ('Q100',): 1, ('Q101',): 1, ('Q102', 'Q104'): 1,
            ('Q105', 'Q106', 'Q107', 'Q108'): 1, **tail}), '500 — пополам, пока не ответит')
        self.assertEqual(self.fake.requested('film_labels'), collections.Counter({
            ('Q100', 'Q101', 'Q102', 'Q104'): 1, ('Q105', 'Q106', 'Q107', 'Q108'): 1, ('Q105', 'Q106'): 1,
            ('Q107', 'Q108'): 1, **tail}), 'нет ответа дольше тайм-аута — пополам')
        self.assertEqual(self.fake.requested('titles'), collections.Counter({
            ('Q100', 'Q101', 'Q102', 'Q104'): 1, ('Q100', 'Q101'): 1, ('Q102', 'Q104'): 1, ('Q102',): 1, ('Q104',): 1,
            ('Q105', 'Q106', 'Q107', 'Q108'): 1, **tail}), 'оборванный gzip при 200 — пополам')
        self.assertIn('film_dates: часть из 4 (Q100 … Q104) не прошла — HTTP 500', self.stdout)
        self.assertIn(f'film_labels: часть из 4 (Q105 … Q108) не прошла — нет ответа за {CLIENT_TIMEOUT:g} с',
                      self.stdout)
        self.assertIn('film_titles: часть из 4 (Q100 … Q104) не прошла — битый gzip', self.stdout)

    def test_retry_after(self):
        self.assertEqual(self.fake.requested('people'), collections.Counter({
            ('Q1001', 'Q1002', 'Q1003', 'Q1004'): 2, ('Q1005', 'Q1006', 'Q1007', 'Q1008'): 1,
            ('Q1010', 'Q1011', 'Q1012', 'Q1013'): 1}), '429 — та же часть ещё раз, без деления')
        self.assertEqual(self.fake.requested('countries'), collections.Counter({
            ('Q17', 'Q29', 'Q30', 'Q31'): 2, ('Q38', 'Q90', 'Q142', 'Q145'): 2, ('Q183', 'Q1300'): 2}))
        self.assertIn('сервис просит подождать', self.stdout)
        self.assertIn('HTTP 429', self.stdout)
        self.assertIn('HTTP 503', self.stdout)

    def test_adult_filter(self):
        after = ('dates', 'film_labels', 'titles', 'durations', 'signals', 'cast', 'people', 'people_labels', 'names',
                 'dates_extra', 'releases', 'labels_all', 'crew', 'links2', 'series', 'money', 'awards',
                 'people_dates', 'people_links', 'places', 'item_labels', 'companies', 'countries')
        for kind, key, _ in self.fake.log:
            if kind in after:
                for q in ADULT_FILMS + ADULT_ONLY:
                    self.assertNotIn(q, key, f'{kind}: {q} — после отбора его не спрашивают')
        links = {q for k in self.fake.requested('links') for q in k}
        self.assertLessEqual(set(ADULT_FILMS), links, 'до отбора — спрашивают')
        for name in DATA_FILES:
            text = self.text(name)
            for q in ADULT_FILMS + ADULT_ONLY + ('Q1400', 'Q1401', 'Q1402', ADULT):
                self.assertIsNone(re.search(rf'\b{q}\b', text), f'{name}: {q}')
        self.assertIn(f'в замыкании P279 элемента {ADULT}) в снимок не вошли: 3.', self.read('SOURCE.md').decode())

    def test_source_md(self):
        text = self.read('SOURCE.md').decode('utf-8')
        sent = len(self.fake.log)
        for want in (f'# {SNAPSHOT} — снимок источника набора Movie', f'**{FETCHED_AT}** (UTC)', 'CC0 1.0',
                     'https://creativecommons.org/publicdomain/zero/1.0/', 'https://www.wikidata.org/',
                     self.server.base + '/sparql',
                     f'Запросов отправлено — {sent}, с ответом — {sent - self.fake.faulted} (остальные —',
                     '| `Q11424` | film | целиком | 9 |',
                     '| `Q24869` | feature film | по годам P577, 1870–2026, по 10 лет; по году — 1990–1999 | 3 |',
                     '| `Q202866` | animated film | целиком | 1 |',
                     'частями VALUES по\n4 элементов, до 3 запросов одновременно',
                     'из них делились — 9; ответила каждая.',
                     'В снимке фильмов — 13, людей — 12 (режиссёры, состав и съёмочная группа).',
                     'без P1476 — 1: все их метки — в\n`wikidata/film_labels_all.csv.gz`.',
                     '| `wikidata/film_cast.csv.gz` | `film,prop,person,ordinal,character,character_name` — ',
                     f'| 15 | {len(self.read("wikidata/film_cast.csv.gz"))} |',
                     '| `wikidata/film_awards.csv.gz` | `film,prop,award,date,precision` — ',
                     f'| 5 | {len(self.read("wikidata/film_awards.csv.gz"))} |',
                     '| `wikidata/genre_items.csv` | ',
                     'не записаны: film_awards — 1, film_cast — 1, film_cast: P453 — 1, film_crew — 1, film_links — 1, '
                     'film_links2 — 1, film_releases — 1, genre_items — 1, people: P21 — 1, people_links — 1, '
                     'places: P1566 — 1, places: P17 — 1.',
                     'только в `parent`: 1.'):
            self.assertIn(want, text)
        self.assertTrue(text.endswith('\n') and '\r' not in text)

    def test_progress(self):
        for stage in ('films_by_class', 'film_links', 'genre_items', 'film_dates', 'film_cast', 'people',
                      'people_names', 'name_items', 'characters', 'countries', 'languages') + NEW_FILES:
            self.assertRegex(self.stdout, rf'Wikidata: {stage} — [^\n]*готово за \d+ с')
        self.assertIn('Wikidata: film_cast — фильмов 13, частей 4 по 4, потоков 3', self.stdout)
        self.assertIn('Wikidata: film_labels_all — фильмов без метки и названия 1, частей 1 по 4', self.stdout)
        self.assertIn('из них для взрослых', self.stdout)
        self.assertIn('выше 12 уровня не поднимались (элементов там: 1)', self.stdout)
        self.assertIn('собран за', self.stdout)
        self.assertLess(time.monotonic() - self.started, 60)

    # ---- снимок v2 ----

    def asked(self, kind):
        """Элементы, о которых спрашивал шаг (по всем частям), — по номеру."""
        return sorted({q for key in self.fake.requested(kind) for q in key}, key=fetch.qnum)

    def test_step_order(self):
        """Новые шаги — после отбора фильмов для взрослых; film_labels_all — после меток и названий; люди — после
        состава и съёмочной группы; места, метки, компании, страны — по уже собранным значениям."""
        kinds = [k for k, _, _ in self.fake.log]
        first = {k: kinds.index(k) for k in kinds}
        last = {k: i for i, k in enumerate(kinds)}
        for before, then in (('genres', 'dates_extra'), ('cast', 'dates_extra'), ('film_labels', 'labels_all'),
                             ('titles', 'labels_all'), ('cast', 'people'), ('crew', 'people'),
                             ('awards', 'people'), ('names', 'people_dates'), ('people_links', 'places'),
                             ('links2', 'places'), ('series', 'item_labels'), ('awards', 'item_labels'),
                             ('links2', 'companies'), ('releases', 'countries'), ('people_links', 'countries'),
                             ('places', 'countries'), ('companies', 'countries'), ('countries', 'languages')):
            with self.subTest(before=before, then=then):
                self.assertLess(last[before], first[then])
        # прежние шаги — в прежнем порядке, новые по фильмам — следом за составом
        order = list(dict.fromkeys(k for k in kinds if k not in ('class', 'years', 'genres')))
        self.assertEqual(order, ['links', 'dates', 'film_labels', 'titles', 'durations', 'signals', 'cast',
                                 'dates_extra', 'releases', 'labels_all', 'crew', 'links2', 'series', 'money',
                                 'awards', 'people', 'people_labels', 'names', 'people_dates', 'people_links',
                                 'name_items', 'characters', 'places', 'item_labels', 'companies', 'countries',
                                 'languages'])

    def test_untitled_films(self):
        """film_labels_all — только по фильмам без метки на языках LABEL_LANGS и без P1476: Q115 — да; Q116 (только
        P1476), Q114 (метка pl), Q100 (есть и ja, но и en) — нет."""
        self.assertEqual(self.fake.requested('labels_all'), collections.Counter({('Q115',): 1}))
        sent = [q for kind, _, q in self.fake.log if kind == 'labels_all']
        self.assertNotIn('LANG(?label) IN', sent[0], 'на любом языке')
        text = self.text('wikidata/film_labels_all.csv.gz')
        for q in ('Q100', 'Q114', 'Q116'):
            self.assertNotIn(q, text)
        self.assertNotIn('Q115', self.text('wikidata/film_labels.csv.gz') + self.text('wikidata/film_titles.csv.gz'))

    def test_crew_people(self):
        """Люди из съёмочной группы (и только из неё) — в people и во всех шагах по людям; их имена — в name_items."""
        for kind in ('people', 'people_labels', 'names', 'people_dates', 'people_links'):
            self.assertEqual(self.asked(kind), PEOPLE, kind)
        people = self.text('wikidata/people.csv.gz')
        cast_and_directors = self.text('wikidata/film_cast.csv.gz') + self.text('wikidata/film_links.csv.gz')
        for q in CREW_ONLY:
            self.assertRegex(people, rf'(?m)^{q},1,', q)
            self.assertIsNone(re.search(rf'\b{q}\b', cast_and_directors), f'{q} — только в съёмочной группе')
        self.assertIn('Q4010', self.asked('name_items'), 'имя Q1011 (композитор) — тоже элемент имени')
        self.assertIn('Q1013', self.text('wikidata/film_crew.csv.gz'), 'группа фильма без названия — тоже')

    def test_value_sets(self):
        """Справочники — ровно по собранным множествам значений, частями VALUES."""
        self.assertEqual(self.asked('places'), ['Q90', 'Q2001', 'Q2002', 'Q2003'], 'P19 ∪ P915')
        self.assertEqual(self.asked('item_labels'), ['Q5001', 'Q5002', 'Q5003', 'Q6001', 'Q6002', 'Q7001', 'Q7002'],
                         'P272 ∪ P750 ∪ P179 ∪ P166 ∪ P1411')
        self.assertEqual(self.fake.requested('item_labels'), collections.Counter({
            ('Q5001', 'Q5002', 'Q5003', 'Q6001'): 1, ('Q6002', 'Q7001', 'Q7002'): 1}))
        self.assertEqual(self.asked('companies'), ['Q5001', 'Q5002', 'Q5003'], 'P272 ∪ P750')
        # у каждого источника — своя страна: P495 — Q17, Q142, Q183, Q1300; P291 — Q30 и город Q90; P27 — Q38;
        # P17 места — Q29; P17 студии и прокатчика — Q145, Q31 (записанные, первые по номеру)
        self.assertEqual(self.asked('countries'),
                         ['Q17', 'Q29', 'Q30', 'Q31', 'Q38', 'Q90', 'Q142', 'Q145', 'Q183', 'Q1300'])
        self.assertNotIn('Q2003', self.asked('countries'), 'P17 места — «неизвестное значение»')

    def test_qualifiers(self):
        """Строка — решение SPARQL: утверждение × каждый его квалификатор (P291 — обязателен; P585, P1545 —
        OPTIONAL, без них — одна строка с пустыми)."""
        code = code_of(fetch.QUERIES['film_releases'])
        self.assertIn('?st pq:P291 ?place .', code)
        self.assertNotIn('OPTIONAL', code, 'P577 без P291 — не нужен')
        for stage, qualifier in (('film_awards', 'pqv:P585'), ('film_series', 'pq:P1545')):
            code = code_of(fetch.QUERIES[stage])
            self.assertEqual(code.count(f'OPTIONAL {{ ?st {qualifier}'), code.count('?film p:P'), stage)
        rows = self.text('wikidata/film_releases.csv.gz').splitlines()
        self.assertEqual([r for r in rows if r.startswith('Q100,')],
                         ['Q100,1999-03-31T00:00:00Z,11,Q142', 'Q100,1999-03-31T00:00:00Z,11,Q183'])
        rows = self.text('wikidata/film_awards.csv.gz').splitlines()
        self.assertEqual([r for r in rows if r.startswith('Q100,P166,Q7001,')],
                         ['Q100,P166,Q7001,2000-01-01T00:00:00Z,9', 'Q100,P166,Q7001,2000-03-26T00:00:00Z,11'])
        self.assertIn('Q100,P1411,Q7001,,', rows, 'и награда, и номинация на одно — обе строки')
        rows = self.text('wikidata/film_series.csv.gz').splitlines()
        self.assertEqual([r for r in rows if r.startswith('Q101,')], ['Q101,Q6001,2', 'Q101,Q6001,2a'])

    def test_halving_new_steps(self):
        self.assertEqual(self.fake.requested('crew'), collections.Counter({
            ('Q100', 'Q101', 'Q102', 'Q104'): 1, ('Q100', 'Q101'): 1, ('Q100',): 1, ('Q101',): 1, ('Q102', 'Q104'): 1,
            ('Q105', 'Q106', 'Q107', 'Q108'): 1, ('Q109', 'Q113', 'Q114', 'Q115'): 1, ('Q116',): 1}),
            '500 на новом шаге по фильмам — пополам, пока не ответит')
        self.assertEqual(self.fake.requested('people_dates'), collections.Counter({
            ('Q1001', 'Q1002', 'Q1003', 'Q1004'): 1, ('Q1005', 'Q1006', 'Q1007', 'Q1008'): 1,
            ('Q1010', 'Q1011', 'Q1012', 'Q1013'): 1, ('Q1010', 'Q1011'): 1, ('Q1010',): 1, ('Q1011',): 1,
            ('Q1012', 'Q1013'): 1}), 'оборванный JSON при 200 на новом шаге по людям — пополам')
        self.assertIn('film_crew: часть из 4 (Q100 … Q104) не прошла — HTTP 500', self.stdout)
        self.assertIn('people_dates: часть из 4 (Q1010 … Q1013) не прошла — не JSON', self.stdout)
        self.assertIn('Wikidata: film_crew — фильмов 13: готово', self.stdout)

    def test_source_md_every_file(self):
        """SOURCE.md описывает каждый файл данных: колонки (как в заголовке CSV), строк, байт."""
        text = self.read('SOURCE.md').decode('utf-8')
        doc = json.loads(self.read('fetch.json'))['files']
        for name in DATA_FILES:
            with self.subTest(name=name):
                header = self.text(name).split('\n', 1)[0]
                rows, size = doc[name]['rows'], doc[name]['bytes']
                self.assertRegex(text, rf'(?m)^\| `{re.escape(name)}` \| `{re.escape(header)}` — [^|\n]+ '
                                       rf'\| {fetch.num(rows)} \| {fetch.num(size)} \|$')


class FailureTest(unittest.TestCase):
    """Сбои: после повторов или сразу — код 1 и причина; архива нет."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.server = Server()

    def tearDown(self):
        self.server.close()
        self.tmp.cleanup()

    def run_fetch(self, fake=None, **kw):
        self.fake = fake or FakeWikidata(faults=False)
        self.server.route('POST', '/sparql', self.fake)
        return run_fetch(self.server, self.root / 'out', **kw)

    def assert_failed(self, code, stderr, *parts):
        self.assertEqual(code, 1, stderr)
        self.assertIn('снимок не собран', stderr)
        for p in parts:
            self.assertIn(p, stderr)
        self.assertFalse((self.root / 'out' / f'{SNAPSHOT}.zip').exists())

    def test_minimum_films(self):
        code, _, err = self.run_fetch(env={'MOV_FETCH_MIN': None})
        # без сбоев Q24869 запрошен целиком — фильм без P577 (Q103) тоже здесь
        self.assert_failed(code, err, 'Wikidata: фильмов: 14 — меньше нижней границы 150 000')
        self.assertFalse(self.fake.requested('dates'), 'после отбора — сразу проверка')

    def test_minimum_cast(self):
        with mock.patch.object(fetch, 'MIN_FILMS', 0):
            code, _, err = self.run_fetch(env={'MOV_FETCH_MIN': None})
        self.assert_failed(code, err, 'строк состава (film_cast): 15 — меньше нижней границы 300 000')

    def test_no_sitelinks_at_all(self):
        db = {q: {k: v for k, v in d.items() if k != 'sitelinks'} for q, d in DB.items()}
        with mock.patch.object(fetch, 'MIN_FILMS', 0):
            code, _, err = self.run_fetch(FakeWikidata(db=db, faults=False), env={'MOV_FETCH_MIN': None})
        self.assert_failed(code, err, 'sitelinks у всех фильмов — 0')

    def test_minimum_people(self):
        with mock.patch.object(fetch, 'MIN_FILMS', 0), mock.patch.object(fetch, 'MIN_CAST', 0):
            code, _, err = self.run_fetch(env={'MOV_FETCH_MIN': None})
        self.assert_failed(code, err, 'людей (people): 12 — меньше нижней границы 150 000')

    def test_part_never_answers(self):
        fake = FakeWikidata(faults=False, extra=lambda kind, key, n:
                            Reply(TIMEOUT_BODY, status=500) if kind == 'cast' else None)
        code, out, err = self.run_fetch(fake)
        self.assert_failed(code, err, 'film_cast, 1 шт.', 'HTTP 500', 'попыток: 6')
        singles = [k for k, n in fake.requested('cast').items() if len(k) == 1 and n == 6]
        self.assertTrue(singles, 'до одной — делится, одна — шесть попыток')
        self.assertIn('делится пополам', out)
        self.assertFalse(fake.requested('people'), 'дальше не идёт')

    def test_new_step_never_answers(self):
        fake = FakeWikidata(faults=False, extra=lambda kind, key, n:
                            Reply(TIMEOUT_BODY, status=500) if kind == 'awards' else None)
        code, out, err = self.run_fetch(fake)
        self.assert_failed(code, err, 'film_awards, 1 шт.', 'HTTP 500', 'попыток: 6')
        self.assertIn('Wikidata film_awards: часть из 4', out)
        singles = [k for k, n in fake.requested('awards').items() if len(k) == 1 and n == 6]
        self.assertTrue(singles, 'и на новом шаге: до одной — делится, одна — шесть попыток')
        self.assertTrue(fake.requested('money'), 'шаги до него прошли')
        for kind in ('people', 'places', 'item_labels', 'companies', 'countries'):
            self.assertFalse(fake.requested(kind), f'{kind}: дальше не идёт')

    def test_bad_request_fails_at_once(self):
        fake = FakeWikidata(faults=False, extra=lambda kind, key, n:
                            Reply(b'MalformedQueryException', status=400) if kind == 'names' else None)
        code, _, err = self.run_fetch(fake)
        self.assert_failed(code, err, 'people_names', 'HTTP 400', 'повтор не поможет')
        # без повторов: не больше запроса на часть (людей 12 — три части по 4, все три могли уйти разом)
        self.assertTrue(all(n == 1 for n in fake.requested('names').values()))
        self.assertLessEqual(sum(fake.requested('names').values()), 3)

    def test_single_year_fails(self):
        fake = FakeWikidata(faults=False, extra=lambda kind, key, n:
                            Reply(TIMEOUT_BODY, status=500) if kind == 'class' and key == 'Q24869'
                            or kind == 'years' and key[1:] in (('1990', '2000'), ('1995', '1996')) else None)
        code, _, err = self.run_fetch(fake)
        self.assert_failed(code, err, 'films_by_class_years Q24869 1995', 'попыток: 6')

    def test_film_class_in_adult_closure(self):
        db = dict(DB)
        db['Q506240'] = {'label': {'en': 'television film'}, 'P279': ['Q1400']}
        code, _, err = self.run_fetch(FakeWikidata(db=db, faults=False))
        self.assert_failed(code, err, 'класс из FILM_CLASSES (Q506240)', 'P279')

    def test_bad_snapshot_name(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
            code = fetch.main(['--snapshot', 'mov-source-20261340', '--out', str(self.root / 'out')])
        self.assertEqual(code, 1)
        self.assertIn('нет такой даты', stderr.getvalue())
        with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
            code = fetch.main(['--snapshot', 'wld-source-20261123', '--out', str(self.root / 'out')])
        self.assertEqual(code, 1)
        self.assertIn('не mov-source-YYYYMMDD', stderr.getvalue())

    def test_bad_env(self):
        for env, want in (({'MOV_FETCH_WORKERS': '0'}, 'MOV_FETCH_WORKERS=«0» — нужно целое число от 1 до 5'),
                          ({'MOV_FETCH_WORKERS': '9'}, 'от 1 до 5'),
                          ({'MOV_FETCH_CHUNK': 'x'}, 'MOV_FETCH_CHUNK=«x» — нужно целое число не меньше 1'),
                          ({'MOV_FETCH_BACKOFF': '-1'}, 'MOV_FETCH_BACKOFF=«-1»'),
                          ({'MOV_FETCHED_AT': '2026-11-23'}, 'MOV_FETCHED_AT=«2026-11-23»')):
            with self.subTest(env=env):
                code, _, err = run_fetch(self.server, self.root / 'out', env=env)
                self.assert_failed(code, err, want)


class HttpTest(unittest.TestCase):
    """Http.post на локальном сервере: просьбы подождать, «тяжёлые» ответы, gzip, оборванные ответы."""

    def setUp(self):
        self.server = Server()
        self.url = self.server.base + '/sparql'
        self.ok = Reply(json.dumps({'head': {'vars': []}, 'results': {'bindings': [{'x': lit('1')}]}}).encode())
        patcher = mock.patch.dict(os.environ, {'NO_PROXY': '127.0.0.1,localhost', 'no_proxy': '127.0.0.1,localhost'})
        patcher.start()
        self.addCleanup(patcher.stop)

    def tearDown(self):
        self.server.close()

    def post(self, http=None, heavy=None):
        http = http or fetch.Http(0)
        with contextlib.redirect_stdout(io.StringIO()) as out:
            value = http.post(self.url, b'query=x', 'тест', fetch.check_sparql, heavy)
        return value, out.getvalue()

    def test_busy_is_not_an_attempt(self):
        busy = Reply(b'Too Many Requests', status=429, headers={'Retry-After': '0'})
        self.server.route('POST', '/sparql', *([busy] * 8), self.ok)
        value, out = self.post()
        self.assertEqual(value, [{'x': lit('1')}])
        self.assertEqual(len(self.server.requests), 9)
        self.assertEqual(out.count('сервис просит подождать'), 8)
        self.server.route('POST', '/sparql', busy)
        with mock.patch.object(fetch, 'THROTTLE_MAX', 3), self.assertRaisesRegex(fetch.FetchError, '3 раз подряд'):
            self.post()

    def test_hold_waits_for_everyone(self):
        self.server.route('POST', '/sparql', Reply(b'busy', status=503, headers={'Retry-After': '1'}), self.ok)
        http = fetch.Http(1)
        with mock.patch.object(fetch, 'BACKOFF', (0, 0, 0, 0, 0)):
            t0 = time.monotonic()
            self.post(http)
            self.assertGreaterEqual(time.monotonic() - t0, 0.9, 'пауза из Retry-After')
            http.hold(1.0)
            t0 = time.monotonic()
            done = []
            thread = threading.Thread(target=lambda: done.append(self.post(http)))
            thread.start()
            thread.join(5)
            self.assertTrue(done)
            self.assertGreaterEqual(time.monotonic() - t0, 0.9, 'общая пауза — и для другого потока')

    def test_heavy(self):
        self.server.route('POST', '/sparql', Reply(TIMEOUT_BODY, status=500))
        with self.assertRaises(fetch.Heavy):
            self.post(heavy=2)
        self.assertEqual(len(self.server.requests), 2)
        with self.assertRaisesRegex(fetch.FetchError, r'HTTP 500 — «java\.util.*попыток: 6'):
            self.post()
        self.assertEqual(len(self.server.requests), 8)

    def test_truncated_and_gzip(self):
        body = self.ok.body
        self.server.route('POST', '/sparql', Reply(body, length=len(body) + 50), Reply(body[:-3]),
                          Reply(gzip.compress(body), headers={'Content-Encoding': 'gzip'}))
        value, out = self.post()
        self.assertEqual(value, [{'x': lit('1')}])
        self.assertIn('ответ оборван', out)
        self.assertIn('не JSON', out)
        with self.assertRaises(fetch.Heavy):
            self.server.route('POST', '/sparql', Reply(b'{"head":{},"results":{"bindi'))
            self.post(heavy=1)

    def test_4xx_at_once(self):
        self.server.route('POST', '/sparql', Reply(b'bad query', status=400))
        with self.assertRaisesRegex(fetch.FetchError, 'HTTP 400 — «bad query» — повтор не поможет'):
            self.post()
        self.assertEqual(len(self.server.requests), 1)

    def test_stop(self):
        http = fetch.Http(0)
        http.stop.set()
        with self.assertRaises(fetch.Stopped):
            self.post(http)
        self.assertEqual(self.server.requests, [])


class PartsTest(unittest.TestCase):
    """Отдельные части: разбор значений, gzip, сортировка, проверка частей, zip."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_retry_after(self):
        self.assertEqual(fetch.retry_after({'Retry-After': '7'}), 7)
        self.assertEqual(fetch.retry_after({'Retry-After': '100000'}), fetch.RETRY_AFTER_MAX)
        self.assertEqual(fetch.retry_after({'Retry-After': 'Wed, 21 Oct 2015 07:28:00 GMT'}), 0)
        self.assertIsNone(fetch.retry_after({'Retry-After': 'soon'}))
        self.assertIsNone(fetch.retry_after({}))

    def test_check_sparql(self):
        self.assertEqual(fetch.check_sparql(b'{"head":{"vars":[]},"results":{"bindings":[]}}'), [])
        for bad in (b'{"head":{"vars":[]},"results":{"bindi', b'<html>Bad Gateway</html>', b'{"results":{}}',
                    b'{"results":{"bindings":[1]}}'):
            with self.subTest(bad=bad), self.assertRaises(fetch.Retry):
                fetch.check_sparql(bad)

    def test_union_top_level(self):
        # Запрос по VALUES с UNION: на верхнем уровне — только VALUES и ветки; общий для веток шаблон после UNION
        # (так было у film_cast и people_names: «?st wikibase:rank ?rank») Blazegraph начинает со всех утверждений
        # Wikidata — 504 на любой части, даже из 62 фильмов.
        def top_level(code):
            body = code[code.index('WHERE {') + len('WHERE {'):]
            depth, out = 1, []
            for ch in body:
                if ch in '{}':
                    depth += 1 if ch == '{' else -1
                    if depth == 0:
                        break
                elif depth == 1:
                    out.append(ch)
            return ' '.join(''.join(out).split())

        checked = 0
        for stage, query in fetch.QUERIES.items():
            code = code_of(query)
            if 'UNION' not in code:
                continue
            with self.subTest(stage=stage):
                self.assertIn('%VALUES%', code)
                self.assertRegex(top_level(code), r'^VALUES \?[a-z]+( UNION)+$')
                checked += 1
        self.assertGreaterEqual(checked, 17)  # 9 запросов снимка v1 и 8 новых

    def test_union_branches(self):
        # Каждая ветка UNION начинается с элемента из VALUES и сама несёт ранг и его фильтр (так прошли на настоящем
        # сервисе film_signals, film_links, film_cast); ранг нигде не берётся без фильтра deprecated.
        checked = 0
        for stage, query in fetch.QUERIES.items():
            code = code_of(query)
            with self.subTest(stage=stage):
                self.assertEqual(code.count('wikibase:rank ?rank'),
                                 code.count('FILTER(?rank != wikibase:DeprecatedRank)'))
                if 'UNION' not in code:
                    continue
                var = re.search(r'VALUES \?(\w+) \{', code).group(1)
                groups = where_groups(code)
                self.assertEqual(groups[0], '%VALUES%')
                for branch in groups[1:]:
                    self.assertTrue(branch.startswith(f'?{var} '), branch)
                    self.assertEqual(branch.count('wikibase:rank ?rank'),
                                     branch.count('FILTER(?rank != wikibase:DeprecatedRank)'), branch)
                checked += 1
        self.assertEqual(checked, 17)

    def test_ranks(self):
        """«Кроме deprecated» — через p:/ps:/psv:/pq: с фильтром ранга; «лучший ранг» — через wdt: без p:."""
        all_but_deprecated = ('film_dates', 'film_durations', 'film_cast', 'people_names', 'film_dates_extra',
                              'film_releases', 'film_series', 'film_money', 'film_awards', 'people_dates')
        best_rank = ('film_links', 'film_titles', 'film_crew', 'film_links2', 'people_links', 'places', 'companies',
                     'countries', 'languages')
        for stage in all_but_deprecated:
            code = code_of(fetch.QUERIES[stage])
            branches = max(1, code.count('UNION') + 1)
            self.assertEqual(code.count('FILTER(?rank != wikibase:DeprecatedRank)'), branches, stage)
            self.assertNotIn('wdt:', code.split('WHERE', 1)[1], stage)
        for stage in best_rank:
            code = code_of(fetch.QUERIES[stage])
            self.assertIn('wdt:', code, stage)
            self.assertIsNone(re.search(r'\bp:P', code), stage)
            self.assertNotIn('wikibase:rank', code, stage)
        for props, stage in ((fetch.EXTRA_DATE_PROPS, 'film_dates_extra'), (fetch.CREW_PROPS, 'film_crew'),
                             (fetch.LINK2_PROPS, 'film_links2'), (fetch.MONEY_PROPS, 'film_money'),
                             (fetch.AWARD_PROPS, 'film_awards'), (fetch.PERSON_DATE_PROPS, 'people_dates'),
                             (fetch.PERSON_LINK_PROPS, 'people_links')):
            code = code_of(fetch.QUERIES[stage])
            self.assertEqual(re.findall(r'BIND\("(P[0-9]+)" AS \?prop\)', code), list(props), stage)

    def test_amounts(self):
        self.assertEqual([fetch.decimal_text(s, 'film_money') for s in
                          ('+15000000', '1.2E+7', '80000000.00', '2500000.50', '-5', '+0.000',
                           '123456789012345678901234567890.10')],
                         ['15000000', '12000000', '80000000', '2500000.5', '-5', '0',
                          '123456789012345678901234567890.1'])
        with self.assertRaisesRegex(fetch.FetchError, 'Wikidata film_money: «x» — не число'):
            fetch.decimal_text('x', 'film_money')

    def test_values(self):
        self.assertEqual(fetch.qid('http://www.wikidata.org/entity/Q42'), 'Q42')
        self.assertIsNone(fetch.qid('http://www.wikidata.org/entity/L7'))
        self.assertIsNone(fetch.qid('http://www.wikidata.org/entity/Q042'))
        self.assertIsNone(fetch.qid(GENID + 'abc'))
        self.assertEqual([fetch.seconds_text(s) for s in ('5400', '+5400.0', '90.50', '0.0', '1E+3')],
                         ['5400', '5400', '90.5', '0', '1000'])
        with self.assertRaises(fetch.FetchError):
            fetch.seconds_text('NaN')
        self.assertEqual(fetch.name_kind({'Q12308941'}), 'male')
        self.assertEqual(fetch.name_kind({'Q11879590', 'Q101352'}), 'female')
        self.assertEqual(fetch.name_kind({'Q12308941', 'Q11879590'}), 'unisex')
        self.assertEqual(fetch.name_kind({'Q3409032'}), 'unisex')
        self.assertEqual(fetch.name_kind({'Q101352'}), 'family')
        self.assertEqual(fetch.name_kind(set()), 'other')

    def test_gzip_bytes(self):
        data = 'film,class\nQ1,Q11424\n'.encode() * 1000
        one, two = fetch.gzip_bytes(data), fetch.gzip_bytes(data)
        self.assertEqual(one, two)
        self.assertEqual(gzip.decompress(one), data)
        self.assertEqual(one[:10], b'\x1f\x8b\x08\x00\x00\x00\x00\x00\x02\xff')
        self.assertEqual(gzip.decompress(fetch.gzip_bytes(b'')), b'')

    def test_sort_key(self):
        rows = [('Q10', 'b', ''), ('Q9', 'b', 'Q2'), ('Q10', 'a', 'Q30'), ('Q10', 'a', ''), ('Q10', 'a', 'Q4')]
        self.assertEqual(sorted(rows, key=fetch.sort_key('qsq')),
                         [('Q9', 'b', 'Q2'), ('Q10', 'a', ''), ('Q10', 'a', 'Q4'), ('Q10', 'a', 'Q30'),
                          ('Q10', 'b', '')])
        self.assertEqual(sorted([('Q1', '90.5'), ('Q1', '5400'), ('Q1', '100')], key=fetch.sort_key('qd')),
                         [('Q1', '90.5'), ('Q1', '100'), ('Q1', '5400')])

    def test_every_part_answers(self):
        """Если часть пропала из итогов (так быть не должно), шаг не проходит."""
        server = Server()
        self.addCleanup(server.close)
        server.route('POST', '/sparql', Reply(b'{"head":{"vars":[]},"results":{"bindings":[]}}'))
        env = {'MOV_FETCH_BACKOFF': '0', 'MOV_WIKIDATA_SPARQL': server.base + '/sparql', 'MOV_FETCH_CHUNK': '2',
               'MOV_FETCH_WORKERS': '2', 'MOV_FETCHED_AT': FETCHED_AT}
        cfg = fetch.Config(env)
        snap = fetch.Snapshot(SNAPSHOT, 2026, cfg, self.root, fetch.Http(0))
        wd = fetch.Wikidata(snap)
        items = ['Q1', 'Q2', 'Q3', 'Q5', 'Q8']
        real = fetch.run_parallel

        def lossy(*args):
            results = real(*args)
            return results[:-1]

        with mock.patch.dict(os.environ, {'NO_PROXY': '127.0.0.1', 'no_proxy': '127.0.0.1'}), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(fetch.in_parts(wd, 'countries', items, fetch.parse_links, 'элементов'), [])
            with mock.patch.object(fetch, 'run_parallel', lossy), \
                    self.assertRaisesRegex(fetch.FetchError, 'ответили не все части — элементов с ответом 4 из 5'):
                fetch.in_parts(wd, 'countries', items, fetch.parse_links, 'элементов')
        self.assertEqual(len(server.requests), 6)

    def test_deterministic_zip(self):
        tree = self.root / 'tree'
        (tree / 'a').mkdir(parents=True)
        (tree / 'a' / 'x.csv.gz').write_bytes(fetch.gzip_bytes(b'x\n'))
        (tree / 'b.txt').write_bytes(b'b\n' * 100)
        names = fetch.tree_files(tree)
        self.assertEqual(names, ['a/x.csv.gz', 'b.txt'])
        fetch.write_zip(tree, names, self.root / 'one.zip')
        os.utime(tree / 'b.txt', (1, 1))
        os.chmod(tree / 'b.txt', 0o600)
        fetch.write_zip(tree, names, self.root / 'two.zip')
        self.assertEqual((self.root / 'one.zip').read_bytes(), (self.root / 'two.zip').read_bytes())
        with zipfile.ZipFile(self.root / 'one.zip') as zf:
            self.assertEqual(zf.getinfo('a/x.csv.gz').compress_type, zipfile.ZIP_STORED)


if __name__ == '__main__':
    unittest.main()
