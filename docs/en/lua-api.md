# Lua API reference

Everything a script can reach is in the global table `mf` and in the libraries loaded with
`require("mf.…")`. The language is Lua 5.2 with `string`, `table`, `math`, `coroutine` and
`bit32`. Of `os` only `os.time()`, `os.date()` and `os.clock()` exist; there is no `io` and
no file system.

The same API in JavaScript is described in the [JavaScript API reference](js-api.md); short
ready-made examples are in the [cookbook](cookbook.md).

## Conventions

- Calls that need a permission or can fail for outside reasons return `nil` and a message
  instead of raising an error: `local value, err = mf.http{…}`.
- Calls with a programming mistake (wrong argument type, invalid JSON text) raise a Lua
  error. An uncaught error stops the module.
- Strings are byte strings. `mf.http` bodies and stored files may be binary.

## Module information

| Name | Value |
|---|---|
| `mf.id` | `id` from the manifest |
| `mf.name` | `name` from the manifest |
| `mf.version` | `version` from the manifest |

## Output and time

| Call | Description |
|---|---|
| `mf.log(...)` | Writes a line to the module output. Arguments are joined with tabs |
| `print(...)` | Same as `mf.log` |
| `mf.sleep(seconds)` | Pauses the script. Fractions are allowed |
| `mf.time()` | Seconds since 1970-01-01 UTC, with fractions |
| `mf.date([format [, time]])` | The moment `time` (default: now) as text |
| `os.time()`, `os.date(...)`, `os.clock()` | Whole seconds; the same as `mf.date`; seconds of a steady clock for measuring durations |

`mf.date` understands the usual `strftime` directives: `%Y %y %m %d %H %M %S %j %a %A %b %B
%p %Z %z %%`. The device's time zone is used; start the format with `!` for UTC. Without a
format the result looks like `2026-03-01 14:05:09`.

```lua
mf.date("%d.%m.%Y %H:%M")          -- 01.03.2026 14:05
mf.date("!%Y-%m-%dT%H:%M:%SZ")     -- 2026-03-01T11:05:09Z
local t = mf.date("*t")            -- { year, month, day, hour, min, sec, wday, yday }
if t.wday == 1 then mf.log("Sunday") end
```

## Permissions

The app asks the user for declared permissions before the module starts, so most scripts
never call these.

| Call | Returns |
|---|---|
| `mf.granted(permission [, target])` | `true` or `false`, without asking the user |
| `mf.request(permission, reason [, target])` | `true`, or `false` and a reason. Shows a dialog and waits when the permission is not held |

Reasons returned by `mf.request`:

| Reason | Meaning |
|---|---|
| `NOT_DECLARED` | The permission is not in the manifest. The user is not asked |
| `USER_DENIED` | The user refused. Asking again later is allowed |
| `MODULE_NOT_ENABLED` | The module is disabled |
| `TARGET_REQUIRED`, `TARGET_NOT_AUTHORIZED` | Only for permissions that act against a named target |
| `UNKNOWN_CAPABILITY` | No such permission name |

## Network — `NETWORK_OUTBOUND`

### `mf.http(request)`

```lua
local response, err = mf.http{
    url = "https://api.example.com/items",
    method = "POST",                                   -- default "GET"
    headers = { ["Content-Type"] = "application/json" },
    body = mf.json.encode({ name = "x" }),
    redirects = 5,                                     -- default 5; 0 returns the 3xx answer
}
if response then
    mf.log(response.status, #response.body, response.headers["content-type"])
end
```

| Field of the result | Meaning |
|---|---|
| `status` | HTTP status code |
| `body` | Response body as a string, at most 8 MB |
| `headers` | Table of response headers; names are lowercase |
| `url` | Address the answer came from, after redirects |

Instead of `body` a request can carry a form or files; the method defaults to `POST` then.

```lua
-- application/x-www-form-urlencoded
mf.http{ url = "https://example.com/login", form = { user = "me", code = "1234" } }

-- multipart/form-data: form fields plus files
mf.http{
    url = "https://example.com/upload",
    form = { caption = "Report" },
    files = {
        document = { filename = "report.txt", type = "text/plain", content = text },
    },
}
```

A file is a table with `content` and optionally `filename`, `type` and `field`; the key in
`files` is the field name unless `field` is given.

Things to know:

- Only `http://` and `https://` addresses on the public internet. The phone itself and the
  local network are refused: `destination is not a public internet address`.
- HTTPS certificates are verified by the host. A bad certificate is an error.
- Up to five redirects are followed (at most 10 with `redirects`). `Authorization` and
  `Cookie` headers are not sent on to a different host.
- One request per connection. A long-poll request simply waits for the server.
- Each request is recorded in the user's audit log with the host name and port.
- At most 16 connections may be open at once.

### `mf.connect(host, port [, options])` — a raw TCP connection

```lua
local conn = assert(mf.connect("irc.example.org", 6697, { tls = true }))
conn:write("NICK bot\r\n")
local line, err = conn:read_line(30)        -- nil, "timeout" when nothing came in 30 s
conn:close()
```

| Call | Returns |
|---|---|
| `conn:read([max [, timeout]])` | Whatever has arrived, at most `max` bytes (default 16384) |
| `conn:read_exactly(count [, timeout])` | Exactly `count` bytes |
| `conn:read_line([timeout])` | One line without its line break |
| `conn:write(data)` | `true` |
| `conn:close()` | |

Reads wait until data arrives. With a `timeout` in seconds they give `nil, "timeout"` when
nothing came in time; `nil, "closed"` means the other side ended the connection.

### `mf.websocket(url [, headers])`

```lua
local ws = assert(mf.websocket("wss://stream.example.com/feed"))
ws:send(mf.json.encode({ subscribe = "news" }))
while true do
    local message, info = ws:receive(60)
    if message then
        mf.log(message)
    elseif info == "timeout" then
        ws:ping()                            -- nothing for a minute: check the line
    else
        break                                -- closed
    end
end
```

| Call | Returns |
|---|---|
| `ws:send(text)` | `true`. Sends a text message |
| `ws:send_binary(data)` | `true`. Sends a binary message |
| `ws:receive([timeout])` | The message and `true` for text, `false` for binary; `nil, "timeout"`; `nil, "closed"` |
| `ws:ping()` | `true` |
| `ws:close()` | |

Pings from the server are answered for you and fragmented messages arrive whole. Both
`ws://` and `wss://` work. The same rules as for `mf.http` apply: public addresses only,
certificates verified, every connection in the audit log.

## Storage — `FILE_SANDBOXED`

Files of the module, encrypted on the device and removed when the module is uninstalled.

| Call | Returns |
|---|---|
| `mf.storage.read(path)` | The content, or `nil` when the file does not exist |
| `mf.storage.write(path, data)` | `true`. Creates or replaces the file |
| `mf.storage.delete(path)` | `true` when a file was removed, otherwise `false` |
| `mf.storage.list()` | Table of all paths, sorted |

Paths are relative, with `/` between parts, e.g. `cache/page.html`. Parts may contain
letters, digits and `_ . @ + -`; `..` is not allowed. One file holds at most 512 KB, a
module at most 64 MB in 2000 files.

Because a missing file and a refused call both give `nil`, check the second result when the
difference matters: `local data, err = mf.storage.read("x")`.

## Notifications — `NOTIFICATIONS`

`mf.notify(title [, text])` returns `true`, or `nil` and a message.

The notification carries the module's name, added by the app. A module has one
notification: a new one replaces the previous one. Title is cut to 80 characters, text to
1000.

## Questions to the user

`mf.ask(question [, secret])` shows a dialog and waits. Returns the typed text, or `nil`
when the user closed the dialog. Pass `true` as the second argument to hide the typed
text: `mf.ask("Token", true)`.

No permission is needed. The dialog names the module and tells the user where the answer
goes. The question is cut to 500 characters.

## JSON and URLs

| Call | Description |
|---|---|
| `mf.json.decode(text)` | Lua value from JSON text. Raises an error on invalid JSON — wrap in `pcall` for untrusted input |
| `mf.json.encode(value)` | JSON text from a Lua value |
| `mf.urlencode(text)` | Percent-encodes text for use in a URL; a space becomes `%20` |

Decoding: objects and arrays become tables (arrays start at 1), `null` becomes `nil`, so a
key with a `null` value is absent.

Encoding: a table whose keys are exactly `1..n` becomes an array, any other table an
object. An empty table becomes `{}`. Whole numbers are written without a fraction.
Functions and tables nested deeper than 64 levels cannot be encoded.

## Device services — apps, screen, camera

Scripts can use the phone itself: open apps, press buttons in them, take photos. This is the
part of ModuForge that needs the most care, so each service is a separate permission, shown
to the user in plain words before the module starts:

| Table | Permission | What it does |
|---|---|---|
| `mf.apps` | `LAUNCH_APPS` | List, open and link into the apps on the phone |
| `mf.screen` | `SCREEN_CONTROL` | Touch the screen and read it, in every app |
| `mf.camera` | `CAMERA` | Take photos with the cameras |

What the user gets in return:

- A notification — "*module* is controlling the screen" or "…is using the camera" — while a
  module uses these, and Android's own camera indicator during a photo.
- Every call that acts (a tap, a launch, a photo) in the audit log; reading calls once a
  minute. What a module types is never copied into the log.
- `mf.screen` works only while the user keeps the accessibility service of ModuForge turned
  on in the system settings, and stops when they turn it off.

Opening another app takes ModuForge off the screen, and the host stops a module that cannot
work in the background. So a script that uses `mf.apps` or `mf.screen` gets
`BACKGROUND_EXECUTION` declared automatically in the editor; in a packed module declare it
yourself.

Every call takes its arguments either **by position** or as **one table**:
`mf.screen.tap(100, 200)` and `mf.screen.tap{ x = 100, y = 200 }` are the same. A call that
fails returns `nil` and the reason, like every other service.

### `mf.apps`

| Call | Returns |
|---|---|
| `mf.apps.list()` | Table of `{ package, name }` for the apps that have an icon, sorted by name |
| `mf.apps.launch(app)` | `{ ok = true, package }`. `app` is a package name or the name as the launcher shows it (exact first, then part of the name) |
| `mf.apps.open(url)` | Opens a link in the app that handles it. `http`, `https`, `mailto`, `geo`; `tel` only prepares the call |
| `mf.apps.installed(package)` | `true` or `false` |

ModuForge itself cannot be launched this way.

### `mf.screen`

Coordinates are pixels of the screen as it is now (rotation counts). Positions of buttons are
best found with `mf.screen.find` rather than guessed.

| Call | Description |
|---|---|
| `mf.screen.info()` | `{ width, height, package, enabled }` — size, the app on top, whether the service is on |
| `mf.screen.tap(x, y [, ms])` | A tap; `ms` is how long the finger stays (default 50) |
| `mf.screen.press(x, y [, ms])` | A long press (default 700 ms) |
| `mf.screen.swipe(x1, y1, x2, y2 [, ms])` | A swipe (default 300 ms) |
| `mf.screen.back()`, `home()`, `recents()`, `notifications()` | The system buttons and the notification shade |
| `mf.screen.texts()` | Everything readable on the screen: a table of `{ text, desc, id, x, y, bounds, clickable }` |
| `mf.screen.find(text)` | The elements whose text, description or id contains `text`; exact matches first |
| `mf.screen.click(text)` | Presses the element that says `text` (or the pressable one around it) |
| `mf.screen.type(text)` | Types into the field that has the focus |
| `mf.screen.wait(text [, timeout])` | Waits (up to 25 s, default 10) until something says `text`; the element with `found = true`, or `{ found = false }` |
| `mf.screen.event([timeout])` | The next thing that happened on the screen, or `nil` |

`mf.screen.event` is how a module reacts to what the user does in other apps. It returns
`{ type = "click", package, text }` when a button was pressed, `{ type = "window", package, title }`
when a screen opened and `{ type = "notification", package, text }` when a notification
arrived. Text typed into fields is never reported.

Some screens are closed to modules whatever they ask: system settings, the permission and
installer dialogs, and ModuForge itself — a module must not approve its own permissions.
Password fields are never read or typed into. `back` and `home` always work.

### `mf.camera`

| Call | Description |
|---|---|
| `mf.camera.list()` | The lenses: a table of `{ lens = "back" or "front", id }` |
| `mf.camera.photo(path [, lens [, size [, quality [, flash]]]])` | Takes a photo and stores it as `path` in module storage. Returns `{ ok, path, width, height, bytes }` |

`lens` is `"back"` (default) or `"front"`; `size` is the longest side in pixels (default
1280, at most 2048); `quality` is the JPEG quality (default 80); `flash` is `"off"`
(default), `"on"` or `"auto"`. The photo is shrunk to fit a storage file (512 KB), so the
module needs `FILE_SANDBOXED`; read it back with `mf.storage.read`, or send it on with
`mf.http` — which needs `NETWORK_OUTBOUND`, so the user sees what the module can do with it.

Android lets an app use the camera only while it is visible or showing a notification; keep
that in mind for scenarios that photograph by timer. The user also allows the camera once in
**Settings → Device control**.

```lua
-- Opens the calculator, presses 7 + 8 = and reads the answer.
mf.apps.launch("Calculator")
mf.screen.wait("7", 8)
for _, key in ipairs({ "7", "+", "8", "=" }) do
    mf.screen.click(key)
    mf.sleep(0.4)
end
for _, item in ipairs(mf.screen.texts()) do
    if item.text:match("^%d+$") and item.y < 600 then mf.log("result: " .. item.text) end
end
mf.screen.back()
```

## Hashes and encodings

| Call | Returns |
|---|---|
| `mf.hash.sha256(data [, raw])` | Hex text of the digest; the bytes themselves when `raw` is `true`. Also `md5`, `sha1`, `sha512` |
| `mf.hmac.sha256(key, data [, raw])` | HMAC, the same way. Also `md5`, `sha1`, `sha512` |
| `mf.base64.encode(data [, url])` | Base64 text; `true` selects the URL-safe alphabet without padding |
| `mf.base64.decode(text)` | Bytes. Both alphabets are accepted |
| `mf.hex.encode(data)`, `mf.hex.decode(text)` | Hex text and back |
| `mf.random(count)` | `count` cryptographically random bytes, up to 1024 |

```lua
local signature = mf.hmac.sha256(secret, timestamp .. body)
local nonce = mf.hex.encode(mf.random(16))
```

## Interface

A script can show a simple interface on the module's screen and react to it. No permission
is needed. In a packed module put `"ui": "compose"` into the manifest; modules written in
the app get it automatically.

```lua
local count = 0
local function draw()
    mf.ui.show({
        { type = "text", text = "Counter", style = "title" },
        { type = "text", text = "Value: " .. count },
        { type = "row", children = {
            { type = "button", id = "minus", label = "−" },
            { type = "button", id = "plus", label = "+" },
        } },
        { type = "field", id = "note", label = "Note", value = "" },
    })
end

draw()
while true do
    local event = mf.ui.wait()               -- waits for a press or typing
    if event.type == "click" then
        count = count + (event.id == "plus" and 1 or -1)
        draw()
    elseif event.type == "text" then
        mf.log(event.id .. " = " .. event.value)
    end
end
```

| Call | Description |
|---|---|
| `mf.ui.show(tree)` | Replaces what is shown. A list is a column |
| `mf.ui.wait([timeout])` | Next event: `{ type = "click", id }` or `{ type = "text", id, value }`; `nil` after `timeout` seconds |
| `mf.ui.clear()` | Removes the interface |

| Element | Fields |
|---|---|
| `text` | `text`, `style`: `title`, `body` (default), `caption`, `code`. A plain string is a text element too |
| `button` | `id`, `label`, `enabled` (default `true`) |
| `field` | `id`, `label`, `value` |
| `row`, `column` | `children` |

## Library `mf.config`

Settings kept in module storage (`config.json`). Needs `FILE_SANDBOXED`.

```lua
local config = require("mf.config")
local city = config.get("city", { ask = "Your city" })
config.set("last_run", mf.time())
config.forget("city")
```

| Call | Description |
|---|---|
| `config.get(key [, options])` | Stored value of `key`. When nothing is stored, see the options |
| `config.set(key, value)` | Stores a value. Any JSON-encodable value |
| `config.forget(key)` | Removes a value |

Options of `get`:

| Option | Effect |
|---|---|
| `ask` | Question to put to the user. The answer is trimmed; an empty answer counts as none |
| `label` | Name of the value on the module's screen. Defaults to the question |
| `secret` | Hide what the user types |
| `default` | Value to use when nothing is stored and nothing was answered |
| `save = false` | Do not store the asked or default value |

**Settings the user can change.** Every value read with `ask` or `label` appears in the
**Settings** card on the module's screen. The user can change it there at any time; a
running module is restarted to pick the change up, and clearing a value makes the module
ask again or fall back to its default. So this is all a module needs for adjustable
settings:

```lua
local token    = config.get("token", { ask = "API token", secret = true })
local interval = config.get("interval", { label = "Seconds between checks", default = 300 })
```

Values written with `config.set` and never given a label (counters, the last seen id) are
the module's own and are not shown. A number or `true`/`false` stays one when the user
edits it; anything typed into a text value arrives as a string.

## Library `mf.schedule`

```lua
local schedule = require("mf.schedule")
schedule.every(300, check)                               -- now, then every 5 minutes
schedule.every(3600, report, { immediately = false })    -- first run in an hour
schedule.run()                                           -- never returns
```

| Call | Description |
|---|---|
| `schedule.every(seconds, task [, options])` | Registers a task |
| `schedule.run()` | Runs due tasks forever |
| `schedule.step()` | Runs due tasks once; returns seconds until the next one |

A task that raises an error is logged and tried again at its next turn. Tasks run one after
another on the script's thread; a slow task delays the others.

## Library `mf.telegram`

A bot framework over the Telegram Bot API. Needs `NETWORK_OUTBOUND` and `FILE_SANDBOXED`.

```lua
local telegram = require("mf.telegram")
local bot = assert(telegram.bot())

bot:command("start", function(message, arguments)
    bot:reply(message, "Hello, " .. message.from.first_name, {
        reply_markup = telegram.keyboard({ { { "Yes", "y" }, { "No", "n" } } }),
    })
end)

bot:on("text", function(message) bot:reply(message, message.text) end)

bot:on("callback", function(query)
    bot:call("answerCallbackQuery", { callback_query_id = query.id })
end)

local _, reason = bot:run()
mf.log("stopped: " .. tostring(reason))
```

| Call | Description |
|---|---|
| `telegram.bot([options])` | Creates a bot. Returns `nil` and a reason when there is no token |
| `telegram.keyboard(rows)` | Inline keyboard markup from rows of `{ text, callback_data }` |
| `bot:command(name, handler)` | `handler(message, arguments)` for `/name`. A leading `/` in `name` is optional |
| `bot:on(kind, handler)` | See the kinds below |
| `bot:reply(message, text [, extra])` | Sends text to the chat of `message` |
| `bot:send(chat_id, text [, extra])` | Sends text. `extra` adds any `sendMessage` fields |
| `bot:call(method [, parameters])` | Any Bot API method. Returns its result, or `nil` and Telegram's description |
| `bot:run()` | Checks the token, then serves updates until the module stops. Returns `nil` and a reason when Telegram rejects the token |
| `bot:poll()` | Fetches and handles updates once; returns how many, or `nil` and an error |

Options of `telegram.bot`: `token` (otherwise asked from the user once and stored), `ask`
(the question text), `timeout` (long-poll seconds, default 25).

Handler kinds for `bot:on`:

| Kind | Called with | When |
|---|---|---|
| `"text"` | message | A text message that matched no command |
| `"message"` | message | Any other message, and text when there is no `"text"` handler |
| `"callback"` | callback query | A press on an inline keyboard button |
| `"ready"` | bot info | Once, after the token was accepted |

Behaviour to rely on:

- A token Telegram rejects is forgotten, so the next start asks for it again.
- The position in the update queue is stored; a restarted bot does not repeat old updates.
- An error inside a handler is written to the module output and does not stop the bot.
- When a poll fails, the bot waits 2, 4, 8 … up to 60 seconds and tries again.

A package can ship its own `mf/telegram.lua`, `mf/config.lua` or `mf/schedule.lua`; the
package's file is used instead of the bundled one.
