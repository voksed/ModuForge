# Справочник JavaScript API

Всё, до чего может дотянуться скрипт, находится в глобальном объекте `mf` и в библиотеках,
подключаемых через `require("mf/…")`. Сервисы те же, что в Lua — правила каждого сервиса
(ограничения, что попадает в журнал, какие адреса разрешены) описаны в
[справочнике Lua API](lua-api.md). Здесь перечислены формы вызовов на JavaScript и отличия.
Короткие готовые примеры — в [рецептах](cookbook.md).

## Язык

- JavaScript без браузера и без Node.js. Доступны: `let`/`const`, стрелочные функции,
  шаблонные строки, деструктуризация, `for…of`, остаточные параметры, генераторы,
  `Map`/`Set`, `Symbol`, типизированные массивы, `JSON`, современные методы `String`,
  `Array` и `Object`.
- Недоступны: объявления `class` (используйте функции и прототипы), `async`/`await`,
  значения параметров по умолчанию, оператор `...` в вызовах и литералах массивов, `?.` и
  `??`, `import`/`export`, `setTimeout`, `fetch`, `process`, `Buffer`, пакеты npm, которым
  нужен Node.js. Библиотеки на простом ES5 работают, если положить их файл в папку модуля.
- Все вызовы синхронные: `mf.http(...)` возвращает ответ, `mf.sleep(1)` делает паузу. Бот —
  это обычный цикл.
- `console.log`, `console.info`, `console.warn`, `console.error` пишут в вывод модуля.

## Общие правила

- Вызов, которому отказали или который не удался по внешним причинам, **бросает `Error`**,
  в `message` которого сказано почему: нет разрешения, нет связи, истекло время. Ловите
  его там, где скрипт должен продолжать работу.
- Ошибка программиста (не тот аргумент) бросает `TypeError`. Неперехваченная ошибка
  останавливает модуль; в выводе будут файл и строка.
- Текст — это строка, двоичные данные — `Uint8Array`. Везде, где данные передаются внутрь,
  принимается строка, `Uint8Array` или массив чисел.

```js
try {
    var page = mf.http({ url: "https://example.com/" });
    mf.log(page.status);
} catch (e) {
    mf.log("запрос не удался: " + e.message);
}
```

## Файлы модуля

`require` работает по правилам CommonJS:

```js
var util = require("./lib/util");        // lib/util.js рядом с текущим файлом
var config = require("mf/config");       // библиотека из приложения

// lib/util.js
exports.double = function (n) { return n * 2; };
```

Путь, начинающийся с `./` или `../`, считается от файла, который подключает; любой другой —
от корня модуля. `.js` и `/index.js` подставляются при необходимости. Ваш файл с именем
`mf/config.js`, `mf/schedule.js` или `mf/telegram.js` заменяет встроенную библиотеку.

## Сведения о модуле, вывод и время

| Вызов | Описание |
|---|---|
| `mf.id`, `mf.name`, `mf.version` | Из манифеста |
| `mf.log(...)` | Пишет строку в вывод модуля. Аргументы соединяются пробелами |
| `mf.sleep(секунды)` | Приостанавливает скрипт. Допустимы дробные значения |
| `mf.time()` | Секунды с 1970-01-01 UTC, с дробной частью |
| `mf.date([формат [, время]])` | Момент текстом; директивы `strftime`, `!` в начале — UTC |
| `mf.dateFields([время [, utc]])` | `{ year, month, day, hour, min, sec, wday, yday }`; `wday` 1 — воскресенье |

`Date` тоже работает, но знает только UTC; для часового пояса устройства используйте
`mf.date` и `mf.dateFields`.

## Разрешения

| Вызов | Возвращает |
|---|---|
| `mf.granted(разрешение [, цель])` | `true` или `false`, пользователя не спрашивает |
| `mf.request(разрешение, причина [, цель])` | `true` или `false`. Если разрешения нет, показывает диалог и ждёт |

## Сеть — `NETWORK_OUTBOUND`

### `mf.http(запрос)`

```js
var response = mf.http({
    url: "https://api.example.com/items",
    method: "POST",                                    // по умолчанию "GET"
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: "x" }),
    redirects: 5                                       // по умолчанию 5; 0 вернёт ответ 3xx
});
var data = response.json();
```

| Поле результата | Смысл |
|---|---|
| `status` | Код состояния HTTP |
| `body` | Тело ответа текстом |
| `bytes()` | Тело ответа как `Uint8Array` |
| `json()` | Тело ответа, разобранное как JSON; бросает ошибку, если это не JSON |
| `headers` | Объект заголовков ответа; имена строчными буквами |
| `url` | Адрес, с которого пришёл ответ, после перенаправлений |

Формы и файлы; метод тогда по умолчанию `POST`:

```js
mf.http({ url: "https://example.com/login", form: { user: "me", code: "1234" } });

mf.http({
    url: "https://example.com/upload",
    form: { caption: "Отчёт" },
    files: [{ field: "document", filename: "report.txt", type: "text/plain", content: text }]
});
```

### `mf.connect(хост, порт [, параметры])`

```js
var conn = mf.connect("irc.example.org", 6697, { tls: true });
conn.write("NICK bot\r\n");
var line = conn.readLine(30);            // null, если другая сторона закрыла соединение
conn.close();
```

| Вызов | Возвращает |
|---|---|
| `conn.read([макс [, таймаут]])` | Пришедший текст, не больше `макс` байт; `null`, если закрыто |
| `conn.readBytes([макс [, таймаут]])` | То же как `Uint8Array` |
| `conn.readExactly(сколько [, таймаут])` | `Uint8Array` ровно из `сколько` байт; `null`, если закрыто |
| `conn.readLine([таймаут])` | Одну строку без перевода строки; `null`, если закрыто |
| `conn.write(данные)` | `true` |
| `conn.close()` | |

Чтение с `таймаутом` в секундах бросает `Error` с сообщением `timeout`, если вовремя ничего
не пришло.

### `mf.websocket(адрес [, заголовки])`

```js
var ws = mf.websocket("wss://stream.example.com/feed");
ws.send(JSON.stringify({ subscribe: "news" }));
while (true) {
    var message;
    try {
        message = ws.receive(60);
    } catch (e) {                        // "timeout": минуту тишина
        ws.ping();
        continue;
    }
    if (message === null) break;         // закрыто
    mf.log(message);
}
```

| Вызов | Возвращает |
|---|---|
| `ws.send(данные)` | `true`. Строка уходит текстовым сообщением, байты — двоичным |
| `ws.receive([таймаут])` | Строку для текстового сообщения, `Uint8Array` для двоичного, `null`, если закрыто |
| `ws.ping()` | `true` |
| `ws.close()` | |

## Хранилище — `FILE_SANDBOXED`

| Вызов | Возвращает |
|---|---|
| `mf.storage.read(путь)` | Содержимое текстом или `null`, если файла нет |
| `mf.storage.readBytes(путь)` | Содержимое как `Uint8Array` или `null` |
| `mf.storage.write(путь, данные)` | `true`. Создаёт или заменяет файл |
| `mf.storage.delete(путь)` | `true`, если файл был удалён, иначе `false` |
| `mf.storage.list()` | Массив всех путей по алфавиту |

## Уведомления, вопросы, интерфейс

| Вызов | Возвращает |
|---|---|
| `mf.notify(заголовок [, текст])` | `true`. Нужно `NOTIFICATIONS` |
| `mf.ask(вопрос [, secret])` | Введённый текст или `null`, если пользователь закрыл диалог |
| `mf.ui.show(дерево)` | Показывает интерфейс на экране модуля. Массив — это колонка |
| `mf.ui.wait([таймаут])` | Следующее событие: `{ type: "click", id }` или `{ type: "text", id, value }`; `null` через `таймаут` секунд |
| `mf.ui.clear()` | Убирает интерфейс |

```js
var count = 0;
function draw() {
    mf.ui.show([
        { type: "text", text: "Счётчик", style: "title" },
        "Значение: " + count,
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

Элементы и их поля перечислены в [справочнике Lua](lua-api.md#интерфейс).

## Хеши и кодировки

| Вызов | Возвращает |
|---|---|
| `mf.hash.sha256(данные [, raw])` | Шестнадцатеричный текст; `Uint8Array`, если `raw` равно `true`. Также `md5`, `sha1`, `sha512` |
| `mf.hmac.sha256(ключ, данные [, raw])` | HMAC, так же |
| `mf.base64.encode(данные [, url])` | Текст Base64; `true` выбирает алфавит для URL без дополнения |
| `mf.base64.decode(текст)` | Текст |
| `mf.base64.decodeBytes(текст)` | `Uint8Array` |
| `mf.hex.encode(данные)`, `mf.hex.decode(текст)` | Шестнадцатеричный текст; `Uint8Array` |
| `mf.random(сколько)` | `Uint8Array` из криптографически случайных байт, до 1024 |
| `mf.urlencode(текст)` | Текст в процентной кодировке для URL |

JSON — это стандартные `JSON.parse` и `JSON.stringify`.

## Библиотека `mf/config`

```js
var config = require("mf/config");
var token = config.get("token", { ask: "Токен API", secret: true });
var interval = config.get("interval", { label: "Секунд между проверками", default: 300 });
config.set("lastRun", mf.time());
config.forget("token");
```

Параметры `get`: `ask`, `label`, `secret`, `default`, `save: false` — смысл тот же, что в
Lua. Значения, прочитанные с `ask` или `label`, появляются в карточке **Настройки** на
экране модуля, где пользователь может поменять их позже; см.
[Библиотека `mf.config`](lua-api.md#библиотека-mfconfig).

Без разрешения на хранилище `config` работает, но всё забывает, когда модуль
останавливается.

## Библиотека `mf/schedule`

```js
var schedule = require("mf/schedule");
schedule.every(300, check);                              // сейчас, потом каждые 5 минут
schedule.every(3600, report, { immediately: false });    // первый запуск через час
schedule.run();                                          // не возвращается
```

`schedule.step()` один раз выполняет подошедшие задачи и возвращает секунды до следующей.
Задача, бросившая ошибку, попадает в вывод и повторяется в свой следующий черёд.

## Библиотека `mf/telegram`

```js
var telegram = require("mf/telegram");
var bot = telegram.bot();                // один раз спрашивает токен и запоминает его

bot.command("start", function (message, args) {
    bot.reply(message, "Привет, " + message.from.first_name, {
        reply_markup: telegram.keyboard([[["Да", "y"], ["Нет", "n"]]])
    });
});

bot.on("text", function (message) { bot.reply(message, message.text); });

bot.on("callback", function (query) {
    bot.call("answerCallbackQuery", { callback_query_id: query.id });
});

bot.run();
```

| Вызов | Описание |
|---|---|
| `telegram.bot([параметры])` | Создаёт бота. Параметры: `token`, `ask`, `timeout`. Бросает ошибку, если токена нет |
| `telegram.keyboard(ряды)` | Разметка инлайн-клавиатуры из рядов `[текст, callback_data]` |
| `bot.command(имя, обработчик)` | `обработчик(message, args)` для `/имя` |
| `bot.on(вид, обработчик)` | `"text"`, `"message"`, `"callback"`, `"ready"` |
| `bot.reply(message, текст [, extra])` | Отправляет текст в чат сообщения `message` |
| `bot.send(chatId, текст [, extra])` | Отправляет текст. `extra` добавляет любые поля `sendMessage` |
| `bot.call(метод [, параметры])` | Любой метод Bot API. Возвращает его результат; бросает ошибку с описанием от Telegram |
| `bot.run()` | Обрабатывает обновления, пока модуль не остановят. Бросает ошибку, если Telegram отверг токен |
| `bot.poll()` | Один раз получает и обрабатывает обновления; возвращает их число |

Поведение то же, что в [Lua](lua-api.md#библиотека-mftelegram): отвергнутый токен
забывается, место в очереди обновлений сохраняется, ошибка в обработчике не останавливает
бота, неудавшийся опрос повторяется с растущими паузами.
