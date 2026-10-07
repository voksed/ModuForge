# Справочник Python API

Модули можно писать на Python. Среда выполнения — интерпретатор значительной части Python 3,
встроенный в ModuForge, — **не CPython**. Такой выбор оставляет модули в той же песочнице,
что и Lua и JavaScript, ничего не требует скачивать и работает на любом телефоне, где есть
приложение; цена — то, что перечислено в разделе [Чего нет](#чего-нет).

Всё, до чего может дотянуться скрипт, находится в модуле `mf` и в библиотеках, подключаемых
через `from mf import …`. Службы те же, что в Lua и JavaScript: правила каждой (ограничения,
что попадает в журнал, какие адреса разрешены) описаны в [справочнике Lua](lua-api.md); здесь
перечислены формы вызовов на Python. Короткие примеры — в [рецептах](cookbook.md).

## Язык

Есть:

- Операторы и выражения Python 3: `if`/`elif`/`else`, `while`, `for` (в том числе с `else`),
  `break`/`continue`, `try`/`except`/`else`/`finally`, `raise` (и `raise … from`), `with`,
  `assert`, `del`, `global`, `nonlocal`, `import`, `from … import`, относительные импорты.
- Функции с умолчаниями, `*args`, `**kwargs`, именованными параметрами, `lambda`, замыкания,
  декораторы, генераторы (`yield`, `yield from`, `.send()`), генераторные выражения.
- Классы: наследование (в том числе от нескольких), `super()`, `@property` (с `.setter`),
  `@staticmethod`, `@classmethod`, атрибуты класса и специальные методы `__init__`, `__str__`,
  `__repr__`, `__eq__`, `__lt__` и родственные, `__len__`, `__iter__`, `__next__`,
  `__getitem__`, `__setitem__`, `__contains__`, `__call__`, `__enter__`/`__exit__`, `__bool__`,
  `__add__` и прочие арифметические операторы, `__getattr__`.
- Включения для списков, множеств и словарей, срезы, распаковка (`a, *rest = …`), оператор
  `:=`, f-строки со спецификациями формата (`f"{x:>8.2f}"`, `{x!r}`, `{x=}`), форматирование
  `%`, `str.format`.
- Целые любого размера, числа с плавающей точкой в том виде, в каком их печатает Python,
  `True`/`False`/`None`.
- Встроенные типы и их методы: `str`, `bytes`, `list`, `tuple`, `dict`, `set`, `frozenset`,
  `range` и привычные встроенные функции (`print`, `len`, `sorted`, `min`, `max`, `sum`, `zip`,
  `map`, `filter`, `enumerate`, `isinstance`, `getattr`, `round`, …).
- Исключения: стандартная иерархия (`Exception`, `ValueError`, `KeyError`, `OSError`, …) и ваши
  собственные классы.

Модули стандартной библиотеки: `json`, `math`, `time`, `random`, `re`, `string`, `hashlib`,
`base64`, `sys`, `urllib.parse`, `datetime`, `collections` (`defaultdict`, `OrderedDict`,
`deque`, `Counter`, `namedtuple`), `functools` (`reduce`, `partial`, `lru_cache`, `wraps`),
`itertools`.

`print` пишет строку в вывод модуля, как `mf.log`. `input(prompt)` спрашивает пользователя,
как `mf.ask`. `sys.exit()` завершает скрипт.

### Чего нет

- Пакетов из PyPI и расширений на C (`numpy`, `requests`, …). Вместо `requests` используйте
  `mf.http`.
- `async`/`await` и `asyncio`; `threading`, `subprocess`, `socket`, `os`, `open()`. Вызовы
  ждут результата, а файлы живут в `mf.storage`. При попытке импорта об этом сказано.
- Наследования от встроенных типов (`class MyList(list)`), метаклассов, `__slots__`, `match`,
  проверки аннотаций (аннотации принимаются и игнорируются), `eval`/`exec`.
- Скорости: это интерпретатор для скриптов, которые ждут сеть и пользователя. Занятый цикл в
  несколько миллионов шагов занимает секунды.

Скрипт, который упал, останавливает модуль; в выводе есть ошибка с файлом и строкой, а в
редакторе кнопка открывает это место:

```
script failed: ZeroDivisionError: division by zero (main.py:12)
```

## Общие правила

- Вызов, которому отказали или который не удался по внешним причинам, **бросает `mf.Error`** —
  подкласс `OSError`, в сообщении которого сказано почему: нет разрешения, нет связи, нет такой
  службы устройства. Чтение, которое ждало слишком долго, бросает `TimeoutError`.
- Ошибка в аргументах бросает `TypeError` или `ValueError`, как в Python.
- Текст — это `str`, двоичные данные — `bytes`. Везде, где данные передаются внутрь, принимается
  `str` (UTF-8) или `bytes`.

```python
import mf

try:
    page = mf.http("https://example.com/")
    mf.log(page.status)
except mf.Error as error:
    mf.log("запрос не удался: " + str(error))
```

## Файлы модуля

Другие файлы `.py` модуля подключаются по имени, пакеты — с `__init__.py`:

```python
import util                      # util.py
from lib import helpers          # lib/helpers.py (lib/__init__.py делает папку пакетом)
from . import base               # относительный: base.py рядом с этим файлом
```

Ваш файл `mf/config.py`, `mf/schedule.py` или `mf/telegram.py` заменяет встроенную библиотеку.

## Модуль `mf`

| Имя | Описание |
|---|---|
| `mf.id`, `mf.name`, `mf.version` | Из манифеста |
| `mf.log(*values)` | Строка вывода модуля; значения соединяются пробелами |
| `mf.sleep(seconds)` | Приостанавливает скрипт |
| `mf.time()` | Секунды с 1970 UTC, с дробной частью |
| `mf.date(format=None, time=None)` | Момент текстом, директивы `strftime`; `!` в начале — UTC |
| `mf.date_fields(time=None, utc=False)` | `{"year", "month", "day", "hour", "min", "sec", "wday", "yday"}`; `wday` 1 — воскресенье |
| `mf.granted(permission, target=None)` | `True` или `False`, пользователя не спрашивает |
| `mf.request(permission, reason="", target=None)` | `True` или `False`; если разрешения нет, показывает диалог |
| `mf.notify(title, text="")` | Уведомление (нужно `NOTIFICATIONS`) |
| `mf.ask(question, secret=False)` | Введённый текст или `None`, если пользователь закрыл диалог |
| `mf.urlencode(text)` | Текст в процентной кодировке для URL |
| `mf.hash.sha256(data, raw=False)` | Шестнадцатеричный текст или `bytes` при `raw=True`; также `md5`, `sha1`, `sha512` |
| `mf.hmac.sha256(key, data, raw=False)` | HMAC, так же |
| `mf.base64.encode(data, url=False)`, `.decode(text)` | Текст Base64; `bytes` |
| `mf.hex.encode(data)`, `.decode(text)` | Шестнадцатеричный текст; `bytes` |
| `mf.random(count)` | `count` криптографически случайных байт, до 1024 |

### Сеть — `NETWORK_OUTBOUND`

`mf.http(url, method="GET", headers=None, body=None, form=None, files=None, redirects=5)`
возвращает ответ; `mf.http({"url": …, …})` тоже работает.

```python
response = mf.http(
    "https://api.example.com/items",
    method="POST",
    headers={"Content-Type": "application/json"},
    body=json.dumps({"name": "x"}),
)
data = response.json()
```

| Атрибут ответа | Смысл |
|---|---|
| `status` | Код состояния HTTP |
| `body`, `text` | Тело ответа текстом |
| `bytes()` | Тело как `bytes` |
| `json()` | Тело, разобранное как JSON; бросает `ValueError`, если это не JSON |
| `headers` | `dict` заголовков ответа; имена строчными буквами |
| `url` | Адрес, с которого пришёл ответ, после перенаправлений |

`form={"user": "me"}` отправляет форму; `files=[{"field": "document", "filename": "a.txt",
"type": "text/plain", "content": data}]` — multipart-запрос.

`mf.connect(host, port, tls=False)` открывает сырое соединение:

| Вызов | Возвращает |
|---|---|
| `conn.read(max=16384, timeout=None)` | `bytes` или `None`, если другая сторона закрыла |
| `conn.read_exactly(count, timeout=None)` | `bytes` такой длины или `None` |
| `conn.read_line(timeout=None)` | Одну строку как `str` или `None` |
| `conn.write(data)` | `True` |
| `conn.close()` | |

`mf.websocket(url, headers=None)` возвращает объект с `send(data)` (`str` уходит текстовым
сообщением, `bytes` — двоичным), `receive(timeout=None)` (`str`, `bytes` или `None`, если
закрыто), `ping()` и `close()`.

### Хранилище — `FILE_SANDBOXED`

| Вызов | Возвращает |
|---|---|
| `mf.storage.read(path)` | Содержимое как `str` или `None` |
| `mf.storage.read_bytes(path)` | Содержимое как `bytes` или `None` |
| `mf.storage.write(path, data)` | `True` |
| `mf.storage.delete(path)` | `True`, если файл удалён |
| `mf.storage.list()` | Список всех путей |

### Интерфейс

`mf.ui.show(tree)` показывает интерфейс на экране модуля; список — это колонка, строка —
текст. `mf.ui.wait(timeout=None)` возвращает следующее событие как `dict` —
`{"type": "click", "id": …}` или `{"type": "text", "id": …, "value": …}` — либо `None`.
`mf.ui.clear()` убирает интерфейс. Элементы описаны в [справочнике Lua](lua-api.md#интерфейс).

```python
count = 0
while True:
    mf.ui.show([
        {"type": "text", "text": "Счётчик", "style": "title"},
        {"type": "text", "text": str(count)},
        {"type": "button", "id": "plus", "label": "+"},
    ])
    event = mf.ui.wait()
    if event["type"] == "click" and event["id"] == "plus":
        count += 1
```

### Устройство — `mf.apps`, `mf.screen`, `mf.camera`

Те же вызовы, разрешения и правила, что в
[справочнике Lua](lua-api.md#службы-устройства--приложения-экран-камера); аргументы по порядку
или по именам, результаты — `dict` и списки:

```python
mf.apps.launch("Калькулятор")
mf.screen.wait("7", 8)
for key in ["7", "+", "8", "="]:
    mf.screen.click(key)
    mf.sleep(0.4)
shown = [item["text"] for item in mf.screen.texts() if item["y"] < 600 and item["text"]]

photo = mf.camera.photo("door.jpg", lens="back", size=1280)
mf.log(str(photo["width"]) + "x" + str(photo["height"]))
```

## Библиотеки

### `mf.config`

```python
from mf import config

token = config.get("token", ask="Токен API", secret=True)
interval = config.get("interval", label="Секунд между проверками", default=300)
config.set("last_run", mf.time())
config.forget("token")
```

`get(key, ask=None, label=None, secret=False, default=None, save=True)`. Значения, прочитанные с
`ask` или `label`, появляются в карточке **Настройки** на экране модуля, где пользователь может
поменять их позже; см. [Библиотека `mf.config`](lua-api.md#библиотека-mfconfig).

### `mf.schedule`

```python
from mf import schedule

schedule.every(300, check)                        # сейчас, потом каждые 5 минут
schedule.every(3600, report, immediately=False)   # первый запуск через час
schedule.run()                                    # не возвращается
```

`schedule.step()` один раз выполняет подошедшие задачи и возвращает секунды до следующей.
Задача, бросившая ошибку, попадает в вывод и повторяется в свой следующий черёд.

### `mf.telegram`

```python
from mf import telegram

bot = telegram.bot()                 # один раз спрашивает токен и запоминает его


@bot.command("start")
def start(message, args):
    bot.reply(message, "Привет, " + message["from"]["first_name"], {
        "reply_markup": telegram.keyboard([[("Да", "y"), ("Нет", "n")]]),
    })


@bot.on("text")
def echo(message):
    bot.reply(message, message["text"])


@bot.on("callback")
def pressed(query):
    bot.call("answerCallbackQuery", {"callback_query_id": query["id"]})


bot.run()
```

Сообщения и обновления — `dict`, такие, как их присылает Telegram. `bot.command(name)` и
`bot.on(kind)` работают и как декораторы, и как обычные вызовы (`bot.on("text", handler)`);
виды — `"text"`, `"message"`, `"callback"`, `"ready"`. `bot.reply(message, text, extra=None)`,
`bot.send(chat_id, text, extra=None)`, `bot.call(method, parameters=None)`, `bot.poll()`,
`bot.run()` ведут себя так же, как описано для [Lua](lua-api.md#библиотека-mftelegram):
отвергнутый токен забывается, место в очереди обновлений сохраняется, ошибка в обработчике не
останавливает бота, неудавшийся опрос повторяется с растущими паузами. `telegram.bot(token=None,
ask=None, timeout=25)` бросает `mf.Error`, если токена нет.
