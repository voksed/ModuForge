-- Telegram echo bot built on the bundled mf.telegram library.
-- The token is asked for on the first start and kept in the module's encrypted storage.
local telegram = require("mf.telegram")

local bot = assert(telegram.bot())

bot:on("ready", function(me)
    mf.notify("Bot is online", "@" .. tostring(me.username))
end)

bot:command("start", function(message)
    bot:reply(message, "Hello, " .. tostring(message.from.first_name) .. "! Send me any text.", {
        reply_markup = telegram.keyboard({ { { "Say hi", "hi" } } }),
    })
end)

bot:on("text", function(message)
    bot:reply(message, message.text)
end)

bot:on("callback", function(query)
    bot:call("answerCallbackQuery", { callback_query_id = query.id })
    if query.message then bot:send(query.message.chat.id, "hi!") end
end)

local _, reason = bot:run()
mf.log("bot stopped: " .. tostring(reason))
mf.notify("Bot is not running", tostring(reason))
