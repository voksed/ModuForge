# Первый модуль за пять минут

Выберите язык. Каждый путь заканчивается работающим модулем на компьютере и на телефоне.

## Что нужно

- **Писать только на телефоне:** приложение ModuForge. Нажмите **Создать модуль**, выберите
  заготовку, правьте, нажмите **Запустить**. Больше ничего не нужно.
- **Писать на компьютере:** `mfrg`, утилита командной строки, из
  [релизов](https://github.com/voksed/ModuForge/releases) (`mfrg-windows.zip` с Java внутри;
  `mfrg.zip` требует Java 17). Распакуйте и добавьте папку `bin` в `PATH`.

Проверка: `mfrg` печатает справку.

## Python

```
mfrg new hello           # ответьте на вопросы; выберите Python и «Пустой скрипт»
cd hello
mfrg run . --watch       # запускает здесь; сохранили main.py — запустится снова
```

`main.py`:

```python
mf.log("hello from " + mf.name)
resp = mf.http("https://api.github.com/zen")
mf.log(resp.text)
```

Добавьте `NETWORK_OUTBOUND` в `permissions` файла `moduforge.json` (или положитесь на
`mfrg run`: одиночный скрипт без манифеста получает разрешения, которые использует его код).

## JavaScript

```
mfrg new hello           # выберите JavaScript
mfrg run hello --watch
```

```js
mf.log("hello from " + mf.name);
var resp = mf.http({ url: "https://api.github.com/zen" });
mf.log(resp.body);
```

В циклах пишите `var` или `let` (`for (let x of list)`); `class`, `async` и `f(...args)` не
поддерживаются, и сообщение об ошибке об этом говорит.

## Lua

```
mfrg new hello           # выберите Lua
mfrg run hello --watch
```

```lua
mf.log("hello from " .. mf.name)
local resp, err = mf.http{ url = "https://api.github.com/zen" }
mf.log(resp and resp.body or err)
```

## На телефоне

1. В приложении: **Настройки → Режим разработчика**, включите. Он покажет команду с токеном.
2. Подключите телефон с включённой отладкой по USB и в папке проекта выполните:

   ```
   mfrg push . --token XXXX-XXXX-XXXX-XXXX --watch
   ```

   Модуль установится и запустится, его вывод появится в терминале; сохранили файл — он
   отправится снова. Токен запоминается после первого раза.
3. При первом запуске появится запрос разрешений. Выдайте то, что просит модуль.

## Поделиться

- Один скрипт: допишите в начало заголовок и упакуйте.

  ```python
  # @name Zen
  # @version 1.0.0
  # @description Пишет строку из GitHub zen.
  ```

  `mfrg pack zen.py` создаёт подписанный файл `.mfrg`. Любой с ModuForge открывает его,
  видит разрешения и код и устанавливает.
- Папка проекта: `mfrg pack hello`.

## Дальше

- [Сборник рецептов](cookbook.md): готовые примеры на каждый день.
- API: [Lua](lua-api.md), [JavaScript](js-api.md), [Python](python-api.md).
- Что модуль может и чего не может, и почему: [безопасность](security.md).
