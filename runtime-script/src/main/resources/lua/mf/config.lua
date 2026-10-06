-- Settings of a module, kept in its encrypted storage (needs FILE_SANDBOXED).
--
--   local config = require("mf.config")
--   local token = config.get("token", { ask = "API token", secret = true })
--   local every = config.get("interval", { label = "Seconds between checks", default = 300 })
--   config.set("last_seen", 42)
--
-- A value that is missing can be asked from the user once and is then remembered. Values
-- that have a question or a label appear on the module's screen in the app, where the user
-- can change them later; the module is restarted to pick the change up.
local M = {}

local FILE = "config.json"
local DESCRIPTIONS = "config.meta.json"
local cache
local descriptions

local function decode(raw)
    local ok, decoded = pcall(mf.json.decode, raw or "{}")
    return (ok and type(decoded) == "table") and decoded or {}
end

local function load()
    if not cache then cache = decode(mf.storage.read(FILE)) end
    return cache
end

local function save()
    return mf.storage.write(FILE, mf.json.encode(cache))
end

-- Tells the app how to present `key` to the user. Written only when something changed.
local function describe(key, label, secret)
    if not descriptions then descriptions = decode(mf.storage.read(DESCRIPTIONS)) end
    local known = descriptions[key]
    if type(known) == "table" and known.label == label and (known.secret == true) == secret then return end
    descriptions[key] = { label = label, secret = secret }
    -- The order of first use is the order on screen.
    local order = descriptions._order
    if type(order) ~= "table" then order = {} end
    local listed = false
    for _, name in ipairs(order) do
        if name == key then listed = true end
    end
    if not listed then order[#order + 1] = key end
    descriptions._order = order
    mf.storage.write(DESCRIPTIONS, mf.json.encode(descriptions))
end

-- Returns the stored value of `key`. Options:
--   ask     question to put to the user when nothing is stored
--   label   name of the value on the module's screen; defaults to the question
--   secret  hide what the user types
--   default value to use when nothing is stored and nothing was answered
--   save    false keeps an asked or default value out of storage
function M.get(key, options)
    options = options or {}
    local label = options.label or options.ask
    if label and options.save ~= false then describe(key, tostring(label), options.secret == true) end
    local values = load()
    local value = values[key]
    if value ~= nil then return value end
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
