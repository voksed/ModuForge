# Lua API reference

Everything a script can reach is in the global table `mf` and in the libraries loaded with
`require("mf.…")`. The language is Lua 5.2 with `string`, `table`, `math`, `coroutine` and
`bit32`; there is no `os`, no `io` and no file system.

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

Things to know:

- Only `http://` and `https://` addresses on the public internet. The phone itself and the
  local network are refused: `destination is not a public internet address`.
- HTTPS certificates are verified by the host. A bad certificate is an error.
- Redirects are returned to you as a 3xx status; they are not followed.
- One request per connection. A long-poll request simply waits for the server.
- Each request is recorded in the user's audit log with the host name and port.
- At most 16 connections may be open at once.

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
| `secret` | Hide what the user types |
| `default` | Value to use when nothing is stored and nothing was answered |
| `save = false` | Do not store the asked or default value |

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
