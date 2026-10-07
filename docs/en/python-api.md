# Python API reference

Modules can be written in Python. The runtime is an interpreter of a large part of Python 3
built into ModuForge — **not CPython**. That choice keeps modules inside the same sandbox as
Lua and JavaScript, needs nothing to download and works on every phone the app runs on; the
price is what is listed under [What is not there](#what-is-not-there).

Everything a script can reach is in the module `mf` and in the libraries loaded with
`from mf import …`. The services are the same as in Lua and JavaScript: the rules of each one
(limits, what is logged, which addresses are allowed) are in the [Lua reference](lua-api.md);
this page lists the Python forms of the calls. Short examples are in the
[cookbook](cookbook.md).

## The language

Available:

- Statements and expressions of Python 3: `if`/`elif`/`else`, `while`, `for` (also with
  `else`), `break`/`continue`, `try`/`except`/`else`/`finally`, `raise` (also `raise … from`),
  `with`, `assert`, `del`, `global`, `nonlocal`, `import`, `from … import`, relative imports.
- Functions with defaults, `*args`, `**kwargs`, keyword-only parameters, `lambda`, closures,
  decorators, generators (`yield`, `yield from`, `.send()`), generator expressions.
- Classes: inheritance (also several bases), `super()`, `@property` (with `.setter`),
  `@staticmethod`, `@classmethod`, class attributes, and the special methods `__init__`,
  `__str__`, `__repr__`, `__eq__`, `__lt__` and its siblings, `__len__`, `__iter__`, `__next__`,
  `__getitem__`, `__setitem__`, `__contains__`, `__call__`, `__enter__`/`__exit__`, `__bool__`,
  `__add__` and the other arithmetic operators, `__getattr__`.
- Comprehensions for lists, sets and dicts, slices, unpacking (`a, *rest = …`), the walrus
  operator `:=`, f-strings with format specs (`f"{x:>8.2f}"`, `{x!r}`, `{x=}`), `%` formatting,
  `str.format`.
- Integers of any size, floats printed the way Python prints them, `True`/`False`/`None`.
- Built-in types and their methods: `str`, `bytes`, `list`, `tuple`, `dict`, `set`,
  `frozenset`, `range`, and the usual built-in functions (`print`, `len`, `sorted`, `min`,
  `max`, `sum`, `zip`, `map`, `filter`, `enumerate`, `isinstance`, `getattr`, `round`, …).
- Exceptions: the standard hierarchy (`Exception`, `ValueError`, `KeyError`, `OSError`, …)
  and your own classes.

Standard library modules: `json`, `math`, `time`, `random`, `re`, `string`, `hashlib`,
`base64`, `sys`, `urllib.parse`, `datetime`, `collections` (`defaultdict`, `OrderedDict`,
`deque`, `Counter`, `namedtuple`), `functools` (`reduce`, `partial`, `lru_cache`, `wraps`),
`itertools`.

`print` writes a line to the module output, like `mf.log`. `input(prompt)` asks the user, like
`mf.ask`. `sys.exit()` ends the script.

### What is not there

- Packages from PyPI and C extensions (`numpy`, `requests`, …). Use `mf.http` instead of
  `requests`.
- `async`/`await` and `asyncio`; `threading`, `subprocess`, `socket`, `os`, `open()`. Calls
  wait for their result, and files live in `mf.storage`. Importing them says so.
- Subclassing built-in types (`class MyList(list)`), metaclasses, `__slots__`, `match`,
  type checking of annotations (annotations are accepted and ignored), `eval`/`exec`.
- Speed: this is an interpreter written for scripts that wait for the network and the user.
  A busy loop of a few million steps takes seconds.

A script that fails stops the module; the output shows the error with the file and line, and
in the editor a button opens it:

```
script failed: ZeroDivisionError: division by zero (main.py:12)
```

## Conventions

- A call that is refused or fails for outside reasons **raises `mf.Error`**, a subclass of
  `OSError`, whose message says why: no permission, no connection, no device service. A read
  that waited too long raises `TimeoutError`.
- A mistake in the arguments raises `TypeError` or `ValueError`, as in Python.
- Text is `str`, binary data is `bytes`. Wherever data is passed in, `str` (UTF-8) or `bytes`
  is accepted.

```python
import mf

try:
    page = mf.http("https://example.com/")
    mf.log(page.status)
except mf.Error as error:
    mf.log("request failed: " + str(error))
```

## Files of a module

Other `.py` files of the module are imported by name, packages with an `__init__.py` too:

```python
import util                      # util.py
from lib import helpers          # lib/helpers.py (lib/__init__.py makes it a package)
from . import base               # relative: base.py next to this file
```

A file of yours named `mf/config.py`, `mf/schedule.py` or `mf/telegram.py` replaces the
bundled library.

## The module `mf`

| Name | Description |
|---|---|
| `mf.id`, `mf.name`, `mf.version` | From the manifest |
| `mf.log(*values)` | A line of module output; values are joined with spaces |
| `mf.sleep(seconds)` | Pauses the script |
| `mf.time()` | Seconds since 1970 UTC, with fractions |
| `mf.date(format=None, time=None)` | The moment as text, `strftime` directives; a leading `!` selects UTC |
| `mf.date_fields(time=None, utc=False)` | `{"year", "month", "day", "hour", "min", "sec", "wday", "yday"}`; `wday` 1 is Sunday |
| `mf.granted(permission, target=None)` | `True` or `False`, without asking the user |
| `mf.request(permission, reason="", target=None)` | `True` or `False`; shows a dialog when the permission is not held |
| `mf.notify(title, text="")` | A notification (needs `NOTIFICATIONS`) |
| `mf.ask(question, secret=False)` | The typed text, or `None` when the user closed the dialog |
| `mf.urlencode(text)` | Percent-encoded text for a URL |
| `mf.hash.sha256(data, raw=False)` | Hex text, or `bytes` with `raw=True`; also `md5`, `sha1`, `sha512` |
| `mf.hmac.sha256(key, data, raw=False)` | HMAC, the same way |
| `mf.base64.encode(data, url=False)`, `.decode(text)` | Base64 text; `bytes` |
| `mf.hex.encode(data)`, `.decode(text)` | Hex text; `bytes` |
| `mf.random(count)` | `count` cryptographically random bytes, up to 1024 |

### Network — `NETWORK_OUTBOUND`

`mf.http(url, method="GET", headers=None, body=None, form=None, files=None, redirects=5)`
returns a response; `mf.http({"url": …, …})` works too.

```python
response = mf.http(
    "https://api.example.com/items",
    method="POST",
    headers={"Content-Type": "application/json"},
    body=json.dumps({"name": "x"}),
)
data = response.json()
```

| Attribute of the response | Meaning |
|---|---|
| `status` | HTTP status code |
| `body`, `text` | The body as text |
| `bytes()` | The body as `bytes` |
| `json()` | The body parsed as JSON; raises `ValueError` when it is not |
| `headers` | `dict` of response headers; names are lowercase |
| `url` | Address the answer came from, after redirects |

`form={"user": "me"}` sends a form; `files=[{"field": "document", "filename": "a.txt",
"type": "text/plain", "content": data}]` sends a multipart request.

`mf.connect(host, port, tls=False)` opens a raw connection:

| Call | Returns |
|---|---|
| `conn.read(max=16384, timeout=None)` | `bytes`, or `None` when the other side closed |
| `conn.read_exactly(count, timeout=None)` | `bytes` of that length, or `None` |
| `conn.read_line(timeout=None)` | One line as `str`, or `None` |
| `conn.write(data)` | `True` |
| `conn.close()` | |

`mf.websocket(url, headers=None)` returns an object with `send(data)` (a `str` goes as a text
message, `bytes` as a binary one), `receive(timeout=None)` (`str`, `bytes` or `None` when
closed), `ping()` and `close()`.

### Storage — `FILE_SANDBOXED`

| Call | Returns |
|---|---|
| `mf.storage.read(path)` | The content as `str`, or `None` |
| `mf.storage.read_bytes(path)` | The content as `bytes`, or `None` |
| `mf.storage.write(path, data)` | `True` |
| `mf.storage.delete(path)` | `True` when a file was removed |
| `mf.storage.list()` | List of all paths |

### Interface

`mf.ui.show(tree)` shows an interface on the module's screen; a list is a column, a `str` is a
text. `mf.ui.wait(timeout=None)` returns the next event as a `dict` —
`{"type": "click", "id": …}` or `{"type": "text", "id": …, "value": …}` — or `None`.
`mf.ui.clear()` removes it. Elements are described in the [Lua reference](lua-api.md#interface).

```python
count = 0
while True:
    mf.ui.show([
        {"type": "text", "text": "Counter", "style": "title"},
        {"type": "text", "text": str(count)},
        {"type": "button", "id": "plus", "label": "+"},
    ])
    event = mf.ui.wait()
    if event["type"] == "click" and event["id"] == "plus":
        count += 1
```

### Device — `mf.apps`, `mf.screen`, `mf.camera`

The same calls, permissions and rules as in the
[Lua reference](lua-api.md#device-services--apps-screen-camera); arguments by position or by
name, results as `dict`s and lists:

```python
mf.apps.launch("Calculator")
mf.screen.wait("7", 8)
for key in ["7", "+", "8", "="]:
    mf.screen.click(key)
    mf.sleep(0.4)
shown = [item["text"] for item in mf.screen.texts() if item["y"] < 600 and item["text"]]

photo = mf.camera.photo("door.jpg", lens="back", size=1280)
mf.log(str(photo["width"]) + "x" + str(photo["height"]))
```

## Libraries

### `mf.config`

```python
from mf import config

token = config.get("token", ask="API token", secret=True)
interval = config.get("interval", label="Seconds between checks", default=300)
config.set("last_run", mf.time())
config.forget("token")
```

`get(key, ask=None, label=None, secret=False, default=None, save=True)`. Values read with
`ask` or `label` appear in the **Settings** card on the module's screen, where the user can
change them later; see [Library `mf.config`](lua-api.md#library-mfconfig).

### `mf.schedule`

```python
from mf import schedule

schedule.every(300, check)                        # now, then every 5 minutes
schedule.every(3600, report, immediately=False)   # first run in an hour
schedule.run()                                    # never returns
```

`schedule.step()` runs the due tasks once and returns the seconds until the next one. A task
that raises is logged and tried again at its next turn.

### `mf.telegram`

```python
from mf import telegram

bot = telegram.bot()                 # asks for the token once and remembers it


@bot.command("start")
def start(message, args):
    bot.reply(message, "Hello, " + message["from"]["first_name"], {
        "reply_markup": telegram.keyboard([[("Yes", "y"), ("No", "n")]]),
    })


@bot.on("text")
def echo(message):
    bot.reply(message, message["text"])


@bot.on("callback")
def pressed(query):
    bot.call("answerCallbackQuery", {"callback_query_id": query["id"]})


bot.run()
```

Messages and updates are `dict`s as Telegram sends them. `bot.command(name)` and `bot.on(kind)`
work as decorators or as plain calls (`bot.on("text", handler)`); the kinds are `"text"`,
`"message"`, `"callback"`, `"ready"`. `bot.reply(message, text, extra=None)`,
`bot.send(chat_id, text, extra=None)`, `bot.call(method, parameters=None)`, `bot.poll()`,
`bot.run()` behave as described for [Lua](lua-api.md#library-mftelegram): a rejected token is
forgotten, the place in the update queue is stored, an error in a handler does not stop the
bot, a failed poll is retried with growing pauses. `telegram.bot(token=None, ask=None,
timeout=25)` raises `mf.Error` when there is no token.
