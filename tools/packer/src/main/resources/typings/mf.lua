---@meta
-- Types of the `mf` table of ModuForge Lua modules (editor help for Lua language servers only).

---@class MfResponse
---@field status integer
---@field body string
---@field headers table<string, string>
---@field url string

---@class MfScreenElement
---@field text string
---@field desc string
---@field id string
---@field x integer
---@field y integer
---@field clickable boolean

---@class mf
---@field id string
---@field name string
---@field version string
mf = {}

---@param ... any
function mf.log(...) end
---@param seconds number
function mf.sleep(seconds) end
---@return number
function mf.time() end
---@param format? string
---@param time? number
---@return string
function mf.date(format, time) end
---@param permission string
---@param target? string
---@return boolean
function mf.granted(permission, target) end
---@param permission string
---@param reason? string
---@param target? string
---@return boolean, string?
function mf.request(permission, reason, target) end
---@param title string
---@param text? string
function mf.notify(title, text) end
---@param question string
---@param secret? boolean
---@return string?
function mf.ask(question, secret) end
---@param text string
---@return string
function mf.urlencode(text) end
---@param count integer
---@return string
function mf.random(count) end
---@param request string|table
---@return MfResponse?, string?
function mf.http(request) end
---@param host string
---@param port integer
---@param options? table
function mf.connect(host, port, options) end
---@param url string
---@param headers? table
function mf.websocket(url, headers) end

mf.storage = {}
---@param path string
---@return string?
function mf.storage.read(path) end
---@param path string
---@param data string
---@return boolean
function mf.storage.write(path, data) end
---@param path string
---@return boolean
function mf.storage.delete(path) end
---@return string[]
function mf.storage.list() end

mf.json = {}
---@param text string
---@return any
function mf.json.decode(text) end
---@param value any
---@return string
function mf.json.encode(value) end

mf.hash = {}
---@param data string
---@param raw? boolean
---@return string
function mf.hash.sha256(data, raw) end
mf.hmac = {}
---@param key string
---@param data string
---@param raw? boolean
---@return string
function mf.hmac.sha256(key, data, raw) end
mf.base64 = {}
---@param data string
---@param url? boolean
---@return string
function mf.base64.encode(data, url) end
---@param text string
---@return string
function mf.base64.decode(text) end
mf.hex = {}
---@param data string
---@return string
function mf.hex.encode(data) end
---@param text string
---@return string
function mf.hex.decode(text) end

mf.ui = {}
---@param tree table
function mf.ui.show(tree) end
---@param timeout? number
---@return table?
function mf.ui.wait(timeout) end
function mf.ui.clear() end

mf.apps = {}
---@return table[]
function mf.apps.list() end
---@param app string
function mf.apps.launch(app) end
---@param url string
function mf.apps.open(url) end
---@param package string
---@return boolean
function mf.apps.installed(package) end

mf.screen = {}
---@return table
function mf.screen.info() end
---@param x integer
---@param y integer
---@param ms? integer
function mf.screen.tap(x, y, ms) end
---@param x integer
---@param y integer
---@param ms? integer
function mf.screen.press(x, y, ms) end
---@param x1 integer
---@param y1 integer
---@param x2 integer
---@param y2 integer
---@param ms? integer
function mf.screen.swipe(x1, y1, x2, y2, ms) end
function mf.screen.back() end
function mf.screen.home() end
function mf.screen.recents() end
function mf.screen.notifications() end
---@return MfScreenElement[]
function mf.screen.texts() end
---@param text string
---@return MfScreenElement[]
function mf.screen.find(text) end
---@param text string
function mf.screen.click(text) end
---@param text string
function mf.screen.type(text) end
---@param text string
---@param timeout? number
---@return MfScreenElement
function mf.screen.wait(text, timeout) end
---@param timeout? number
---@return table?
function mf.screen.event(timeout) end

mf.camera = {}
---@return table[]
function mf.camera.list() end
---@param path string
---@param lens? string
---@param size? integer
---@param quality? integer
---@param flash? boolean
---@return table
function mf.camera.photo(path, lens, size, quality, flash) end
