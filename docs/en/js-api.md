# JavaScript API reference

Everything a script can reach is in the global object `mf` and in the libraries loaded with
`require("mf/…")`. The services are the same as in Lua — see the
[Lua API reference](lua-api.md) for the rules of each service (limits, what is logged, which
addresses are allowed). This page lists the JavaScript forms of the calls and where they
differ. Short ready-made examples are in the [cookbook](cookbook.md).

## The language

- JavaScript without a browser and without Node.js. Available: `let`/`const`, arrow
  functions, template strings, destructuring, `for…of`, rest parameters, generators,
  `Map`/`Set`, `Symbol`, typed arrays, `JSON`, the modern `String`, `Array` and `Object`
  methods.
- Not available: `class` declarations (use functions and prototypes), `async`/`await`,
  default parameter values, the spread operator `...` in calls and array literals, `?.` and
  `??`, `import`/`export`, `setTimeout`, `fetch`, `process`, `Buffer`, npm packages that
  need Node.js. Libraries written in plain ES5 work when you put their file into the module
  folder.
- Every call is synchronous: `mf.http(...)` returns the answer, `mf.sleep(1)` pauses. A bot
  is a plain loop.
- `console.log`, `console.info`, `console.warn`, `console.error` write to the module output.

## Conventions

- A call that is refused or fails for outside reasons **throws an `Error`** whose `message`
  says why: no permission, no connection, a timeout. Catch it where the script should go on.
- A programming mistake (a wrong argument) throws a `TypeError`. An uncaught error stops the
  module; the output shows the file and line.
- Text is a string, binary data is a `Uint8Array`. Wherever data is passed in, a string, a
  `Uint8Array` or an array of numbers is accepted.

```js
try {
    var page = mf.http({ url: "https://example.com/" });
    mf.log(page.status);
} catch (e) {
    mf.log("request failed: " + e.message);
}
```

## Files of a module

`require` follows the CommonJS rules:

```js
var util = require("./lib/util");        // lib/util.js next to the current file
var config = require("mf/config");       // a library shipped with the app

// lib/util.js
exports.double = function (n) { return n * 2; };
```

A path starting with `./` or `../` is relative to the requiring file; any other path is
taken from the module's root. `.js` and `/index.js` are added when needed. A file of yours
named `mf/config.js`, `mf/schedule.js` or `mf/telegram.js` replaces the bundled library.

## Module information, output and time

| Call | Description |
|---|---|
| `mf.id`, `mf.name`, `mf.version` | From the manifest |
| `mf.log(...)` | Writes a line to the module output. Arguments are joined with spaces |
| `mf.sleep(seconds)` | Pauses the script. Fractions are allowed |
| `mf.time()` | Seconds since 1970-01-01 UTC, with fractions |
| `mf.date([format [, time]])` | The moment as text; `strftime` directives, `!` at the start for UTC |
| `mf.dateFields([time [, utc]])` | `{ year, month, day, hour, min, sec, wday, yday }`; `wday` 1 is Sunday |

`Date` works too, but knows only UTC; use `mf.date` and `mf.dateFields` for the device's
time zone.

## Permissions

| Call | Returns |
|---|---|
| `mf.granted(permission [, target])` | `true` or `false`, without asking the user |
| `mf.request(permission, reason [, target])` | `true` or `false`. Shows a dialog and waits when the permission is not held |

## Network — `NETWORK_OUTBOUND`

### `mf.http(request)`

```js
var response = mf.http({
    url: "https://api.example.com/items",
    method: "POST",                                    // default "GET"
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: "x" }),
    redirects: 5                                       // default 5; 0 returns the 3xx answer
});
var data = response.json();
```

| Field of the result | Meaning |
|---|---|
| `status` | HTTP status code |
| `body` | Response body as text |
| `bytes()` | Response body as a `Uint8Array` |
| `json()` | Response body parsed as JSON; throws when it is not JSON |
| `headers` | Object of response headers; names are lowercase |
| `url` | Address the answer came from, after redirects |

Forms and files; the method defaults to `POST` then:

```js
mf.http({ url: "https://example.com/login", form: { user: "me", code: "1234" } });

mf.http({
    url: "https://example.com/upload",
    form: { caption: "Report" },
    files: [{ field: "document", filename: "report.txt", type: "text/plain", content: text }]
});
```

### `mf.connect(host, port [, options])`

```js
var conn = mf.connect("irc.example.org", 6697, { tls: true });
conn.write("NICK bot\r\n");
var line = conn.readLine(30);            // null when the other side closed
conn.close();
```

| Call | Returns |
|---|---|
| `conn.read([max [, timeout]])` | Text that has arrived, at most `max` bytes; `null` when closed |
| `conn.readBytes([max [, timeout]])` | The same as a `Uint8Array` |
| `conn.readExactly(count [, timeout])` | `Uint8Array` of exactly `count` bytes; `null` when closed |
| `conn.readLine([timeout])` | One line without its line break; `null` when closed |
| `conn.write(data)` | `true` |
| `conn.close()` | |

A read with a `timeout` in seconds throws an `Error` with the message `timeout` when
nothing came in time.

### `mf.websocket(url [, headers])`

```js
var ws = mf.websocket("wss://stream.example.com/feed");
ws.send(JSON.stringify({ subscribe: "news" }));
while (true) {
    var message;
    try {
        message = ws.receive(60);
    } catch (e) {                        // "timeout": nothing for a minute
        ws.ping();
        continue;
    }
    if (message === null) break;         // closed
    mf.log(message);
}
```

| Call | Returns |
|---|---|
| `ws.send(data)` | `true`. A string goes as a text message, bytes as a binary one |
| `ws.receive([timeout])` | A string for a text message, a `Uint8Array` for a binary one, `null` when closed |
| `ws.ping()` | `true` |
| `ws.close()` | |

## Storage — `FILE_SANDBOXED`

| Call | Returns |
|---|---|
| `mf.storage.read(path)` | The content as text, or `null` when the file does not exist |
| `mf.storage.readBytes(path)` | The content as a `Uint8Array`, or `null` |
| `mf.storage.write(path, data)` | `true`. Creates or replaces the file |
| `mf.storage.delete(path)` | `true` when a file was removed, otherwise `false` |
| `mf.storage.list()` | Array of all paths, sorted |

## Notifications, questions, interface

| Call | Returns |
|---|---|
| `mf.notify(title [, text])` | `true`. Needs `NOTIFICATIONS` |
| `mf.ask(question [, secret])` | The typed text, or `null` when the user closed the dialog |
| `mf.ui.show(tree)` | Shows an interface on the module's screen. An array is a column |
| `mf.ui.wait([timeout])` | Next event: `{ type: "click", id }` or `{ type: "text", id, value }`; `null` after `timeout` seconds |
| `mf.ui.clear()` | Removes the interface |

```js
var count = 0;
function draw() {
    mf.ui.show([
        { type: "text", text: "Counter", style: "title" },
        "Value: " + count,
        { type: "row", children: [
            { type: "button", id: "minus", label: "−" },
            { type: "button", id: "plus", label: "+" }
        ] }
    ]);
}
draw();
while (true) {
    var event = mf.ui.wait();
    if (event.type === "click") {
        count += event.id === "plus" ? 1 : -1;
        draw();
    }
}
```

Elements and their fields are listed in the [Lua reference](lua-api.md#interface).

## Device services — apps, screen, camera

The same services as in Lua, with the same permissions (`LAUNCH_APPS`, `SCREEN_CONTROL`,
`CAMERA`), the same indicators and the same closed screens; read the introduction in the
[Lua reference](lua-api.md#device-services--apps-screen-camera) first. Calls take their
arguments by position or as one object — `mf.screen.tap(100, 200)` is
`mf.screen.tap({ x: 100, y: 200 })` — and a call that fails throws an `Error`.

| Call | Returns |
|---|---|
| `mf.apps.list()` | Array of `{ package, name }` |
| `mf.apps.launch(app)` | `{ ok, package }`; `app` is a package name or a name as the launcher shows it |
| `mf.apps.open(url)` | Opens a link (`http`, `https`, `mailto`, `geo`, `tel`) |
| `mf.apps.installed(package)` | `true` or `false` |
| `mf.screen.info()` | `{ width, height, package, enabled }` |
| `mf.screen.tap(x, y, ms)`, `press(x, y, ms)`, `swipe(x1, y1, x2, y2, ms)` | Touches |
| `mf.screen.back()`, `home()`, `recents()`, `notifications()` | System buttons |
| `mf.screen.texts()` | Array of `{ text, desc, id, x, y, bounds, clickable }` for everything readable |
| `mf.screen.find(text)` | Elements whose text, description or id contains `text`, exact matches first |
| `mf.screen.click(text)` | Presses the element that says `text` |
| `mf.screen.type(text)` | Types into the focused field |
| `mf.screen.wait(text, timeout)` | The element with `found: true`, or `{ found: false }` |
| `mf.screen.event(timeout)` | `{ type: "click" \| "window" \| "notification", package, … }` or `null` |
| `mf.camera.list()` | Array of `{ lens, id }` |
| `mf.camera.photo(path, lens, size, quality, flash)` | `{ ok, path, width, height, bytes }`; the file is in module storage |

```js
// Reacts to what the user does in other apps.
while (true) {
    var event = mf.screen.event(30);
    if (event && event.type === "notification" && event.package === "org.telegram.messenger") {
        mf.log("telegram: " + event.text);
    }
}
```

## Hashes and encodings

| Call | Returns |
|---|---|
| `mf.hash.sha256(data [, raw])` | Hex text; a `Uint8Array` when `raw` is `true`. Also `md5`, `sha1`, `sha512` |
| `mf.hmac.sha256(key, data [, raw])` | HMAC, the same way |
| `mf.base64.encode(data [, url])` | Base64 text; `true` selects the URL-safe alphabet without padding |
| `mf.base64.decode(text)` | Text |
| `mf.base64.decodeBytes(text)` | `Uint8Array` |
| `mf.hex.encode(data)`, `mf.hex.decode(text)` | Hex text; `Uint8Array` |
| `mf.random(count)` | `Uint8Array` of cryptographically random bytes, up to 1024 |
| `mf.urlencode(text)` | Percent-encoded text for a URL |

JSON is the standard `JSON.parse` and `JSON.stringify`.

## Library `mf/config`

```js
var config = require("mf/config");
var token = config.get("token", { ask: "API token", secret: true });
var interval = config.get("interval", { label: "Seconds between checks", default: 300 });
config.set("lastRun", mf.time());
config.forget("token");
```

Options of `get`: `ask`, `label`, `secret`, `default`, `save: false` — with the same meaning
as in Lua. Values read with `ask` or `label` appear in the **Settings** card on the module's
screen, where the user can change them later; see
[Library `mf.config`](lua-api.md#library-mfconfig).

Without the storage permission `config` still works, but forgets everything when the
module stops.

## Library `mf/schedule`

```js
var schedule = require("mf/schedule");
schedule.every(300, check);                              // now, then every 5 minutes
schedule.every(3600, report, { immediately: false });    // first run in an hour
schedule.run();                                          // never returns
```

`schedule.step()` runs the due tasks once and returns the seconds until the next one. A
task that throws is logged and tried again at its next turn.

## Library `mf/telegram`

```js
var telegram = require("mf/telegram");
var bot = telegram.bot();                // asks for the token once and remembers it

bot.command("start", function (message, args) {
    bot.reply(message, "Hello, " + message.from.first_name, {
        reply_markup: telegram.keyboard([[["Yes", "y"], ["No", "n"]]])
    });
});

bot.on("text", function (message) { bot.reply(message, message.text); });

bot.on("callback", function (query) {
    bot.call("answerCallbackQuery", { callback_query_id: query.id });
});

bot.run();
```

| Call | Description |
|---|---|
| `telegram.bot([options])` | Creates a bot. Options: `token`, `ask`, `timeout`. Throws when there is no token |
| `telegram.keyboard(rows)` | Inline keyboard markup from rows of `[text, callback_data]` |
| `bot.command(name, handler)` | `handler(message, args)` for `/name` |
| `bot.on(kind, handler)` | `"text"`, `"message"`, `"callback"`, `"ready"` |
| `bot.reply(message, text [, extra])` | Sends text to the chat of `message` |
| `bot.send(chatId, text [, extra])` | Sends text. `extra` adds any `sendMessage` fields |
| `bot.call(method [, parameters])` | Any Bot API method. Returns its result; throws with Telegram's description |
| `bot.run()` | Serves updates until the module stops. Throws when Telegram rejects the token |
| `bot.poll()` | Fetches and handles updates once; returns how many |

The behaviour is the same as in [Lua](lua-api.md#library-mftelegram): a rejected token is
forgotten, the place in the update queue is stored, an error in a handler does not stop
the bot, a failed poll is retried with growing pauses.
