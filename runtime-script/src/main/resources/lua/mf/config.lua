-- Settings of a module, kept in its encrypted storage (needs FILE_SANDBOXED).
--
--   local config = require("mf.config")
--   local token = config.get("token", { ask = "API token", secret = true })
--   config.set("last_seen", 42)
--
-- A value that is missing can be asked from the user once and is then remembered.
local M = {}

local FILE = "config.json"
local cache

local function load()
    if cache then return cache end
    local raw = mf.storage.read(FILE)
    local ok, decoded = pcall(mf.json.decode, raw or "{}")
    cache = (ok and type(decoded) == "table") and decoded or {}
    return cache
end

local function save()
    return mf.storage.write(FILE, mf.json.encode(cache))
end

-- Returns the stored value of `key`. Options:
--   ask     question to put to the user when nothing is stored
--   secret  hide what the user types
--   default value to use when nothing is stored and nothing was answered
--   save    false keeps an asked or default value out of storage
function M.get(key, options)
    local values = load()
    local value = values[key]
    if value ~= nil then return value end
    options = options or {}
    if options.ask then
        value = mf.ask(options.ask, options.secret == true)
        if value then value = value:match("^%s*(.-)%s*$") end
        if value == "" then value = nil end
    end
    if value == nil then value = options.default end
    if value ~= nil and options.save ~= false then
        values[key] = value
        save()
    end
    return value
end

function M.set(key, value)
    load()[key] = value
    return save()
end

function M.forget(key)
    return M.set(key, nil)
end

return M
