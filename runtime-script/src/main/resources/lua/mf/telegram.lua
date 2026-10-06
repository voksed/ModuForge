-- Telegram Bot API framework (needs NETWORK_OUTBOUND and FILE_SANDBOXED).
--
--   local telegram = require("mf.telegram")
--   local bot = assert(telegram.bot())            -- asks for the token once and remembers it
--
--   bot:command("start", function(message, arguments)
--       bot:reply(message, "Hello, " .. message.from.first_name)
--   end)
--   bot:on("text", function(message) bot:reply(message, message.text) end)
--
--   bot:run()                                      -- long polling; returns only on a fatal error
--
-- Handlers: command(name, fn(message, arguments)), on("text" | "message" | "callback" | "ready", fn).
-- An error inside a handler is logged and does not stop the bot.
local config = require("mf.config")

local M = {}
local Bot = {}
Bot.__index = Bot

local TOKEN_KEY = "telegram_token"
local OFFSET_KEY = "telegram_offset"

-- Creates a bot. Options: token (otherwise asked from the user and stored), ask (the question),
-- timeout (long-poll seconds, default 25). Returns nil and a reason when there is no token.
function M.bot(options)
    options = options or {}
    local token = options.token
        or config.get(TOKEN_KEY, { ask = options.ask or "Bot token from @BotFather", secret = true })
    if not token then return nil, "no bot token" end
    return setmetatable({
        base = "https://api.telegram.org/bot" .. token .. "/",
        timeout = options.timeout or 25,
        commands = {},
        handlers = {},
    }, Bot)
end

-- Inline keyboard markup from rows of { text, callback_data } pairs:
--   reply_markup = telegram.keyboard({ { {"Yes", "y"}, {"No", "n"} } })
function M.keyboard(rows)
    local markup = {}
    for r, row in ipairs(rows) do
        markup[r] = {}
        for b, button in ipairs(row) do
            markup[r][b] = { text = button[1], callback_data = button[2] }
        end
    end
    return { inline_keyboard = markup }
end

-- Calls any Bot API method. Returns its result, or nil and Telegram's description of the error.
function Bot:call(method, parameters)
    local response, err = mf.http{
        url = self.base .. method,
        method = "POST",
        headers = { ["Content-Type"] = "application/json" },
        body = mf.json.encode(parameters or {}),
    }
    if not response then return nil, err end
    local ok, data = pcall(mf.json.decode, response.body)
    if not ok or type(data) ~= "table" then return nil, "unexpected answer, HTTP " .. response.status end
    if not data.ok then return nil, data.description or ("HTTP " .. response.status) end
    return data.result
end

-- Sends a text message. `extra` adds any sendMessage fields (parse_mode, reply_markup, ...).
function Bot:send(chat_id, text, extra)
    local parameters = { chat_id = chat_id, text = text }
    for key, value in pairs(extra or {}) do parameters[key] = value end
    return self:call("sendMessage", parameters)
end

function Bot:reply(message, text, extra)
    return self:send(message.chat.id, text, extra)
end

function Bot:command(name, handler)
    self.commands[(name:gsub("^/", ""))] = handler
end

function Bot:on(kind, handler)
    self.handlers[kind] = handler
end

-- Routes one update to the matching handler.
function Bot:dispatch(update)
    local message = update.message
    if message then
        if message.text then
            local command, arguments = message.text:match("^/([%w_]+)@?[%w_]*%s*(.*)$")
            if command and self.commands[command] then return self.commands[command](message, arguments) end
            if self.handlers.text then return self.handlers.text(message) end
        end
        if self.handlers.message then return self.handlers.message(message) end
    elseif update.callback_query and self.handlers.callback then
        return self.handlers.callback(update.callback_query)
    end
end

-- Fetches updates once and dispatches them. Returns the number handled, or nil and an error.
function Bot:poll()
    if not self.offset then self.offset = tonumber(config.get(OFFSET_KEY)) or 0 end
    local updates, err = self:call("getUpdates", { offset = self.offset, timeout = self.timeout })
    if not updates then return nil, err end
    for _, update in ipairs(updates) do
        self.offset = update.update_id + 1
        local ok, failure = pcall(self.dispatch, self, update)
        if not ok then mf.log("handler failed: " .. tostring(failure)) end
    end
    if #updates > 0 then config.set(OFFSET_KEY, self.offset) end
    return #updates
end

-- Verifies the token and serves updates until the module is stopped.
-- Returns nil and a reason when Telegram rejects the token; a rejected token is forgotten
-- so that the next start asks for it again.
function Bot:run()
    local me, err = self:call("getMe")
    if not me then
        err = tostring(err)
        if err:find("Unauthorized", 1, true) or err:find("Not Found", 1, true) then config.forget(TOKEN_KEY) end
        return nil, err
    end
    self.me = me
    mf.log("online as @" .. tostring(me.username))
    if self.handlers.ready then pcall(self.handlers.ready, me) end

    local failures = 0
    while true do
        local handled, pollError = self:poll()
        if handled then
            failures = 0
        else
            failures = failures + 1
            mf.log("poll failed (" .. failures .. "): " .. tostring(pollError))
            mf.sleep(math.min(60, 2 ^ failures))
        end
    end
end

return M
