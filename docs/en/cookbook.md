# Cookbook

Short pieces that do one thing each, in Lua and in JavaScript. Copy one into the editor in
the app (**Create module → Empty script**) or into a file and run it on a computer with
`mfrg run file.lua`. Permissions are worked out from the calls a script makes.

Full descriptions of every call: [Lua API](lua-api.md), [JavaScript API](js-api.md).

## Get JSON from a web service

```lua
local response, err = mf.http{ url = "https://api.github.com/repos/voksed/ModuForge" }
if not response then
    mf.log("no answer: " .. err)
    return
end
local repo = mf.json.decode(response.body)
mf.log(repo.full_name .. ": " .. repo.stargazers_count .. " stars")
```

```js
var repo = mf.http({ url: "https://api.github.com/repos/voksed/ModuForge" }).json();
mf.log(repo.full_name + ": " + repo.stargazers_count + " stars");
```

## Send JSON, with a header

```lua
local response = mf.http{
    url = "https://httpbin.org/post",
    method = "POST",
    headers = { ["Content-Type"] = "application/json", Authorization = "Bearer " .. token },
    body = mf.json.encode({ text = "hello" }),
}
```

```js
var response = mf.http({
    url: "https://httpbin.org/post",
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: "Bearer " + token },
    body: JSON.stringify({ text: "hello" })
});
```

## Remember something between starts

```lua
local runs = tonumber(mf.storage.read("runs.txt") or "0") + 1
mf.storage.write("runs.txt", tostring(runs))
mf.log("start number " .. runs)
```

```js
var runs = Number(mf.storage.read("runs.txt") || 0) + 1;
mf.storage.write("runs.txt", String(runs));
mf.log("start number " + runs);
```

## Settings the user can change

The first start asks; later the values are on the module's screen under **Settings**.

```lua
local config = require("mf.config")
local city  = config.get("city", { ask = "Your city" })
local every = config.get("minutes", { label = "Minutes between checks", default = 30 })
local key   = config.get("key", { ask = "API key", secret = true })
```

```js
var config = require("mf/config");
var city  = config.get("city", { ask: "Your city" });
var every = config.get("minutes", { label: "Minutes between checks", default: 30 });
var key   = config.get("key", { ask: "API key", secret: true });
```

## Do something every few minutes

Switch on **Background work** for the module so it keeps going with the screen off.

```lua
local schedule = require("mf.schedule")
schedule.every(600, function()
    mf.log("tick at " .. mf.date("%H:%M"))
end)
schedule.run()
```

```js
var schedule = require("mf/schedule");
schedule.every(600, function () {
    mf.log("tick at " + mf.date("%H:%M"));
});
schedule.run();
```

## Do something every day at a fixed time

```lua
local function seconds_until(hour, minute)
    local t = mf.date("*t")
    return (hour * 3600 + minute * 60 - (t.hour * 3600 + t.min * 60 + t.sec)) % 86400
end

while true do
    mf.sleep(seconds_until(9, 0))
    mf.notify("Good morning", mf.date("%A, %d %B"))
    mf.sleep(60)                      -- step past 09:00 before waiting for the next one
end
```

```js
function secondsUntil(hour, minute) {
    var t = mf.dateFields();
    var left = hour * 3600 + minute * 60 - (t.hour * 3600 + t.min * 60 + t.sec);
    return (left % 86400 + 86400) % 86400;
}

while (true) {
    mf.sleep(secondsUntil(9, 0));
    mf.notify("Good morning", mf.date("%A, %d %B"));
    mf.sleep(60);
}
```

## Notify when a number crosses a line

```lua
local schedule = require("mf.schedule")
local limit = 100

schedule.every(300, function()
    local response = mf.http{ url = "https://api.example.com/price" }
    if not response then return end
    local price = mf.json.decode(response.body).price
    local was_above = mf.storage.read("above") == "1"
    if price > limit and not was_above then
        mf.notify("Price is above " .. limit, tostring(price))
    end
    mf.storage.write("above", price > limit and "1" or "0")
end)
schedule.run()
```

```js
var schedule = require("mf/schedule");
var limit = 100;

schedule.every(300, function () {
    var price = mf.http({ url: "https://api.example.com/price" }).json().price;
    var wasAbove = mf.storage.read("above") === "1";
    if (price > limit && !wasAbove) mf.notify("Price is above " + limit, String(price));
    mf.storage.write("above", price > limit ? "1" : "0");
});
schedule.run();
```

In JavaScript a failed request throws; `schedule` logs the error and tries again next time.

## Try again when the network fails

```lua
local function retry(times, action)
    local result, err
    for attempt = 1, times do
        result, err = action()
        if result then return result end
        mf.sleep(2 ^ attempt)         -- 2, 4, 8 … seconds
    end
    return nil, err
end

local response, err = retry(4, function() return mf.http{ url = "https://example.com/" } end)
```

```js
function retry(times, action) {
    for (var attempt = 1; ; attempt++) {
        try {
            return action();
        } catch (e) {
            if (attempt >= times) throw e;
            mf.sleep(Math.pow(2, attempt));
        }
    }
}

var response = retry(4, function () { return mf.http({ url: "https://example.com/" }); });
```

## Telegram bot with commands and buttons

```lua
local telegram = require("mf.telegram")
local bot = assert(telegram.bot())

bot:command("start", function(message)
    bot:reply(message, "Pick one", {
        reply_markup = telegram.keyboard({ { { "Time", "time" }, { "Dice", "dice" } } }),
    })
end)

bot:on("callback", function(query)
    local answer = query.data == "time" and mf.date("%H:%M:%S") or tostring(math.random(6))
    bot:call("answerCallbackQuery", { callback_query_id = query.id })
    bot:send(query.message.chat.id, answer)
end)

bot:run()
```

```js
var telegram = require("mf/telegram");
var bot = telegram.bot();

bot.command("start", function (message) {
    bot.reply(message, "Pick one", {
        reply_markup: telegram.keyboard([[["Time", "time"], ["Dice", "dice"]]])
    });
});

bot.on("callback", function (query) {
    var answer = query.data === "time" ? mf.date("%H:%M:%S") : String(1 + Math.floor(Math.random() * 6));
    bot.call("answerCallbackQuery", { callback_query_id: query.id });
    bot.send(query.message.chat.id, answer);
});

bot.run();
```

## Send yourself a Telegram message from any script

The chat id is the number the bot sees in `message.chat.id` when you write to it.

```lua
local telegram = require("mf.telegram")
local config = require("mf.config")
local bot = assert(telegram.bot())
local chat = config.get("chat", { ask = "Your chat id" })

bot:send(chat, "Backup finished at " .. mf.date("%H:%M"))
```

```js
var telegram = require("mf/telegram");
var config = require("mf/config");
var bot = telegram.bot();
var chat = config.get("chat", { ask: "Your chat id" });

bot.send(chat, "Backup finished at " + mf.date("%H:%M"));
```

## Send a file

`mf.http` builds the multipart request; here the receiver is the Telegram Bot API, with
`token` and `chat` taken from the settings as above.

```lua
local report = "line 1\nline 2\n"
mf.http{
    url = "https://api.telegram.org/bot" .. token .. "/sendDocument",
    form = { chat_id = chat },
    files = { document = { filename = "report.txt", type = "text/plain", content = report } },
}
```

```js
var report = "line 1\nline 2\n";
mf.http({
    url: "https://api.telegram.org/bot" + token + "/sendDocument",
    form: { chat_id: chat },
    files: [{ field: "document", filename: "report.txt", type: "text/plain", content: report }]
});
```

## Download a file and keep it

One file in module storage holds up to 512 KB.

```lua
local response = assert(mf.http{ url = "https://example.com/logo.png" })
assert(mf.storage.write("logo.png", response.body))
```

```js
var response = mf.http({ url: "https://example.com/logo.png" });
mf.storage.write("logo.png", response.bytes());
```

## Sign a request

```lua
local timestamp = tostring(math.floor(mf.time()))
local body = mf.json.encode({ amount = 5 })
local signature = mf.hmac.sha256(secret, timestamp .. body)

mf.http{
    url = "https://api.example.com/orders",
    method = "POST",
    headers = { ["X-Timestamp"] = timestamp, ["X-Signature"] = signature },
    body = body,
}
```

```js
var timestamp = String(Math.floor(mf.time()));
var body = JSON.stringify({ amount: 5 });
var signature = mf.hmac.sha256(secret, timestamp + body);

mf.http({
    url: "https://api.example.com/orders",
    method: "POST",
    headers: { "X-Timestamp": timestamp, "X-Signature": signature },
    body: body
});
```

## Listen to a WebSocket and reconnect

```lua
while true do
    local ws, err = mf.websocket("wss://stream.example.com/feed")
    if ws then
        ws:send(mf.json.encode({ subscribe = "news" }))
        while true do
            local message, info = ws:receive(60)
            if message then
                mf.log(message)
            elseif info == "timeout" then
                if not ws:ping() then break end
            else
                break
            end
        end
        ws:close()
    else
        mf.log("cannot connect: " .. err)
    end
    mf.sleep(5)
end
```

```js
while (true) {
    try {
        var ws = mf.websocket("wss://stream.example.com/feed");
        ws.send(JSON.stringify({ subscribe: "news" }));
        while (true) {
            var message;
            try {
                message = ws.receive(60);
            } catch (e) {
                if (e.message !== "timeout") throw e;
                ws.ping();
                continue;
            }
            if (message === null) break;
            mf.log(message);
        }
        ws.close();
    } catch (e) {
        mf.log("connection lost: " + e.message);
    }
    mf.sleep(5);
}
```

## A screen with buttons

```lua
local light = false
local function draw()
    mf.ui.show({
        { type = "text", text = "Lamp", style = "title" },
        light and "It is on" or "It is off",
        { type = "button", id = "toggle", label = light and "Switch off" or "Switch on" },
    })
end

draw()
while true do
    if mf.ui.wait().id == "toggle" then
        light = not light
        draw()
    end
end
```

```js
var light = false;
function draw() {
    mf.ui.show([
        { type: "text", text: "Lamp", style: "title" },
        light ? "It is on" : "It is off",
        { type: "button", id: "toggle", label: light ? "Switch off" : "Switch on" }
    ]);
}

draw();
while (true) {
    if (mf.ui.wait().id === "toggle") {
        light = !light;
        draw();
    }
}
```

## A form: type a value, press a button

```lua
local name = ""
mf.ui.show({
    { type = "field", id = "name", label = "Your name", value = "" },
    { type = "button", id = "ok", label = "Greet" },
})
while true do
    local event = mf.ui.wait()
    if event.type == "text" then
        name = event.value
    elseif event.id == "ok" then
        mf.ui.show({ "Hello, " .. name .. "!" })
        break
    end
end
mf.sleep(3600)                        -- keep the module, and its screen, alive
```

```js
var name = "";
mf.ui.show([
    { type: "field", id: "name", label: "Your name", value: "" },
    { type: "button", id: "ok", label: "Greet" }
]);
while (true) {
    var event = mf.ui.wait();
    if (event.type === "text") {
        name = event.value;
    } else if (event.id === "ok") {
        mf.ui.show(["Hello, " + name + "!"]);
        break;
    }
}
mf.sleep(3600);
```

## Split the code into files

In the editor press **Add file**; in a project just create the file.

```lua
-- lib/text.lua
local M = {}
function M.shout(s) return s:upper() .. "!" end
return M

-- main.lua
local text = require("lib.text")
mf.log(text.shout("hello"))
```

```js
// lib/text.js
exports.shout = function (s) { return s.toUpperCase() + "!"; };

// main.js
var text = require("./lib/text");
mf.log(text.shout("hello"));
```

## Work without a permission

A refused permission is not an error of the module: decide what to do without it.

```lua
local ok, err = mf.notify("Done")
if not ok then mf.log("Done (notifications are off: " .. err .. ")") end
```

```js
try {
    mf.notify("Done");
} catch (e) {
    mf.log("Done (notifications are off: " + e.message + ")");
}
```

## Try it on a computer, then on the phone

```
mfrg run main.lua               run a single script here, with real internet
mfrg run my-module              run a project folder
mfrg run my-module --deny NOTIFICATIONS     see how it behaves when the user refuses
mfrg push my-module             install on the phone, restart, print the output
```

Questions from `mf.ask` appear in the terminal, notifications are printed, storage lives in
`.mfrg-run/` next to the code. See [Writing modules](writing-modules.md).
