# Your first module in five minutes

Pick a language. Each path ends with your module running on a computer and on a phone.

## What you need

- **To write on the phone only:** the ModuForge app. Press **Create module**, pick a starting
  point, edit, press **Run**. Nothing else is needed.
- **To write on a computer:** `mfrg`, the command-line tool, from the
  [releases](https://github.com/voksed/ModuForge/releases) (`mfrg-windows.zip` has Java inside;
  `mfrg.zip` needs Java 17). Unzip it and put the `bin` folder on your `PATH`.

Check it: `mfrg` prints its help.

## Python

```
mfrg new hello           # answer the questions; choose Python and "Empty script"
cd hello
mfrg run . --watch       # runs here; saving main.py runs it again
```

`main.py`:

```python
mf.log("hello from " + mf.name)
resp = mf.http("https://api.github.com/zen")
mf.log(resp.text)
```

Add `NETWORK_OUTBOUND` to `permissions` in `moduforge.json` (or let `mfrg run` find it: a
single script without a manifest gets the permissions its code uses).

## JavaScript

```
mfrg new hello           # choose JavaScript
mfrg run hello --watch
```

```js
mf.log("hello from " + mf.name);
var resp = mf.http({ url: "https://api.github.com/zen" });
mf.log(resp.body);
```

Write `var` or `let` in loops (`for (let x of list)`); `class`, `async` and `?.` are not
supported, and the error message says so.

## Lua

```
mfrg new hello           # choose Lua
mfrg run hello --watch
```

```lua
mf.log("hello from " .. mf.name)
local resp, err = mf.http{ url = "https://api.github.com/zen" }
mf.log(resp and resp.body or err)
```

## On your phone

1. In the app: **Settings → Developer mode**, switch it on. It shows a command with a token.
2. Connect the phone with USB debugging on, then in the project folder:

   ```
   mfrg push . --token XXXX-XXXX-XXXX-XXXX --watch
   ```

   The module is installed, started, and its output appears in the terminal; saving a file
   pushes it again. The token is remembered after the first time.
3. The first start shows a permission dialog. Grant what the module asks for.

## Share it

- One script: put a header at its top and pack it.

  ```python
  # @name Zen
  # @version 1.0.0
  # @description Logs a line of GitHub zen.
  ```

  `mfrg pack zen.py` writes a signed `.mfrg` file. Anyone with ModuForge opens it, sees the
  permissions and the code, and installs it.
- A project folder: `mfrg pack hello`.

## Where next

- [Cookbook](cookbook.md): ready examples for everyday tasks.
- API: [Lua](lua-api.md), [JavaScript](js-api.md), [Python](python-api.md).
- What a module can and cannot do, and why: [security](security.md).
