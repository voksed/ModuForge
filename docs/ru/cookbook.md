# Рецепты

Короткие куски кода, каждый делает одно дело, на Lua и на JavaScript. Скопируйте нужный в
редактор приложения (**Создать модуль → Пустой скрипт**) или в файл и запустите на
компьютере: `mfrg run file.lua`. Разрешения определяются по вызовам, которые делает скрипт.

Полное описание каждого вызова: [Lua API](lua-api.md), [JavaScript API](js-api.md).

## Получить JSON с веб-сервиса

```lua
local response, err = mf.http{ url = "https://api.github.com/repos/voksed/ModuForge" }
if not response then
    mf.log("нет ответа: " .. err)
    return
end
local repo = mf.json.decode(response.body)
mf.log(repo.full_name .. ": " .. repo.stargazers_count .. " stars")
```

```js
var repo = mf.http({ url: "https://api.github.com/repos/voksed/ModuForge" }).json();
mf.log(repo.full_name + ": " + repo.stargazers_count + " stars");
```

## Отправить JSON с заголовком

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

## Запомнить что-то между запусками

```lua
local runs = tonumber(mf.storage.read("runs.txt") or "0") + 1
mf.storage.write("runs.txt", tostring(runs))
mf.log("запуск номер " .. runs)
```

```js
var runs = Number(mf.storage.read("runs.txt") || 0) + 1;
mf.storage.write("runs.txt", String(runs));
mf.log("запуск номер " + runs);
```

## Настройки, которые пользователь может менять

При первом запуске модуль спросит; потом значения лежат на экране модуля в карточке
**Настройки**.

```lua
local config = require("mf.config")
local city  = config.get("city", { ask = "Ваш город" })
local every = config.get("minutes", { label = "Минут между проверками", default = 30 })
local key   = config.get("key", { ask = "Ключ API", secret = true })
```

```js
var config = require("mf/config");
var city  = config.get("city", { ask: "Ваш город" });
var every = config.get("minutes", { label: "Минут между проверками", default: 30 });
var key   = config.get("key", { ask: "Ключ API", secret: true });
```

## Делать что-то раз в несколько минут

Включите модулю **Работу в фоне**, чтобы он продолжал работать при выключенном экране.

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

## Делать что-то каждый день в одно время

```lua
local function seconds_until(hour, minute)
    local t = mf.date("*t")
    return (hour * 3600 + minute * 60 - (t.hour * 3600 + t.min * 60 + t.sec)) % 86400
end

while true do
    mf.sleep(seconds_until(9, 0))
    mf.notify("Доброе утро", mf.date("%A, %d %B"))
    mf.sleep(60)                      -- уйти с 09:00, прежде чем ждать следующего раза
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
    mf.notify("Доброе утро", mf.date("%A, %d %B"));
    mf.sleep(60);
}
```

## Уведомить, когда число перешло порог

```lua
local schedule = require("mf.schedule")
local limit = 100

schedule.every(300, function()
    local response = mf.http{ url = "https://api.example.com/price" }
    if not response then return end
    local price = mf.json.decode(response.body).price
    local was_above = mf.storage.read("above") == "1"
    if price > limit and not was_above then
        mf.notify("Цена выше " .. limit, tostring(price))
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
    if (price > limit && !wasAbove) mf.notify("Цена выше " + limit, String(price));
    mf.storage.write("above", price > limit ? "1" : "0");
});
schedule.run();
```

В JavaScript неудавшийся запрос бросает ошибку; `schedule` запишет её в вывод и повторит
попытку в следующий раз.

## Повторить, если сеть подвела

```lua
local function retry(times, action)
    local result, err
    for attempt = 1, times do
        result, err = action()
        if result then return result end
        mf.sleep(2 ^ attempt)         -- 2, 4, 8 … секунд
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

## Telegram-бот с командами и кнопками

```lua
local telegram = require("mf.telegram")
local bot = assert(telegram.bot())

bot:command("start", function(message)
    bot:reply(message, "Выберите", {
        reply_markup = telegram.keyboard({ { { "Время", "time" }, { "Кубик", "dice" } } }),
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
    bot.reply(message, "Выберите", {
        reply_markup: telegram.keyboard([[["Время", "time"], ["Кубик", "dice"]]])
    });
});

bot.on("callback", function (query) {
    var answer = query.data === "time" ? mf.date("%H:%M:%S") : String(1 + Math.floor(Math.random() * 6));
    bot.call("answerCallbackQuery", { callback_query_id: query.id });
    bot.send(query.message.chat.id, answer);
});

bot.run();
```

## Отправить себе сообщение в Telegram из любого скрипта

Идентификатор чата — число, которое бот видит в `message.chat.id`, когда вы ему пишете.

```lua
local telegram = require("mf.telegram")
local config = require("mf.config")
local bot = assert(telegram.bot())
local chat = config.get("chat", { ask = "Идентификатор вашего чата" })

bot:send(chat, "Копирование закончено в " .. mf.date("%H:%M"))
```

```js
var telegram = require("mf/telegram");
var config = require("mf/config");
var bot = telegram.bot();
var chat = config.get("chat", { ask: "Идентификатор вашего чата" });

bot.send(chat, "Копирование закончено в " + mf.date("%H:%M"));
```

## Отправить файл

`mf.http` сам собирает multipart-запрос; здесь получатель — Telegram Bot API, а `token` и
`chat` взяты из настроек, как выше.

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

## Скачать файл и сохранить

Один файл в хранилище модуля вмещает до 512 КБ.

```lua
local response = assert(mf.http{ url = "https://example.com/logo.png" })
assert(mf.storage.write("logo.png", response.body))
```

```js
var response = mf.http({ url: "https://example.com/logo.png" });
mf.storage.write("logo.png", response.bytes());
```

## Подписать запрос

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

## Слушать WebSocket и переподключаться

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
        mf.log("не удалось подключиться: " .. err)
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
        mf.log("связь потеряна: " + e.message);
    }
    mf.sleep(5);
}
```

## Экран с кнопками

```lua
local light = false
local function draw()
    mf.ui.show({
        { type = "text", text = "Лампа", style = "title" },
        light and "Включена" or "Выключена",
        { type = "button", id = "toggle", label = light and "Выключить" or "Включить" },
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
        { type: "text", text: "Лампа", style: "title" },
        light ? "Включена" : "Выключена",
        { type: "button", id: "toggle", label: light ? "Выключить" : "Включить" }
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

## Форма: ввести значение и нажать кнопку

```lua
local name = ""
mf.ui.show({
    { type = "field", id = "name", label = "Ваше имя", value = "" },
    { type = "button", id = "ok", label = "Поздороваться" },
})
while true do
    local event = mf.ui.wait()
    if event.type == "text" then
        name = event.value
    elseif event.id == "ok" then
        mf.ui.show({ "Привет, " .. name .. "!" })
        break
    end
end
mf.sleep(3600)                        -- чтобы модуль и его экран не закрылись
```

```js
var name = "";
mf.ui.show([
    { type: "field", id: "name", label: "Ваше имя", value: "" },
    { type: "button", id: "ok", label: "Поздороваться" }
]);
while (true) {
    var event = mf.ui.wait();
    if (event.type === "text") {
        name = event.value;
    } else if (event.id === "ok") {
        mf.ui.show(["Привет, " + name + "!"]);
        break;
    }
}
mf.sleep(3600);
```

## Разбить код на файлы

В редакторе нажмите **Добавить файл**; в проекте просто создайте файл.

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

## Работать без разрешения

Отказ в разрешении — не ошибка модуля: решите, что делать без него.

```lua
local ok, err = mf.notify("Готово")
if not ok then mf.log("Готово (уведомления выключены: " .. err .. ")") end
```

```js
try {
    mf.notify("Готово");
} catch (e) {
    mf.log("Готово (уведомления выключены: " + e.message + ")");
}
```

## Проверить на компьютере, потом на телефоне

```
mfrg run main.lua               запустить один скрипт здесь, с настоящим интернетом
mfrg run my-module              запустить папку проекта
mfrg run my-module --deny NOTIFICATIONS     посмотреть, что будет при отказе пользователя
mfrg push my-module             поставить на телефон, перезапустить, показать вывод
```

Вопросы из `mf.ask` задаются в терминале, уведомления печатаются, хранилище лежит в
`.mfrg-run/` рядом с кодом. Подробнее — [Как писать модули](writing-modules.md).
