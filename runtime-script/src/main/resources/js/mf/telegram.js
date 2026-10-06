// Telegram Bot API framework (needs NETWORK_OUTBOUND and FILE_SANDBOXED).
//
//   var telegram = require("mf/telegram");
//   var bot = telegram.bot();                     // asks for the token once and remembers it
//
//   bot.command("start", function (message, args) {
//       bot.reply(message, "Hello, " + message.from.first_name);
//   });
//   bot.on("text", function (message) { bot.reply(message, message.text); });
//
//   bot.run();                                     // long polling; throws only on a fatal error
//
// Handlers: command(name, fn(message, args)), on("text" | "message" | "callback" | "ready", fn).
// An error inside a handler is logged and does not stop the bot.
var config = require("mf/config");

var TOKEN_KEY = "telegram_token";
var OFFSET_KEY = "telegram_offset";

function Bot(token, timeout) {
    this.base = "https://api.telegram.org/bot" + token + "/";
    this.timeout = timeout;
    this.commands = {};
    this.handlers = {};
    this.offset = null;
}

// Calls any Bot API method. Returns its result; throws an Error with Telegram's description.
Bot.prototype.call = function (method, parameters) {
    var response = mf.http({
        url: this.base + method,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(parameters || {})
    });
    var data;
    try {
        data = JSON.parse(response.body);
    } catch (e) {
        throw new Error("unexpected answer, HTTP " + response.status);
    }
    if (!data || !data.ok) throw new Error((data && data.description) || ("HTTP " + response.status));
    return data.result;
};

// Sends a text message. `extra` adds any sendMessage fields (parse_mode, reply_markup, ...).
Bot.prototype.send = function (chatId, text, extra) {
    var parameters = { chat_id: chatId, text: text };
    Object.keys(extra || {}).forEach(function (key) { parameters[key] = extra[key]; });
    return this.call("sendMessage", parameters);
};

Bot.prototype.reply = function (message, text, extra) {
    return this.send(message.chat.id, text, extra);
};

Bot.prototype.command = function (name, handler) {
    this.commands[name.replace(/^\//, "")] = handler;
};

Bot.prototype.on = function (kind, handler) {
    this.handlers[kind] = handler;
};

// Routes one update to the matching handler.
Bot.prototype.dispatch = function (update) {
    var message = update.message;
    if (message) {
        if (message.text) {
            var match = /^\/([A-Za-z0-9_]+)(?:@[A-Za-z0-9_]*)?\s*([\s\S]*)$/.exec(message.text);
            if (match && this.commands[match[1]]) return this.commands[match[1]](message, match[2]);
            if (this.handlers.text) return this.handlers.text(message);
        }
        if (this.handlers.message) return this.handlers.message(message);
    } else if (update.callback_query && this.handlers.callback) {
        return this.handlers.callback(update.callback_query);
    }
};

// Fetches updates once and dispatches them. Returns the number handled.
Bot.prototype.poll = function () {
    if (this.offset === null) this.offset = Number(config.get(OFFSET_KEY)) || 0;
    var updates = this.call("getUpdates", { offset: this.offset, timeout: this.timeout });
    for (var i = 0; i < updates.length; i++) {
        this.offset = updates[i].update_id + 1;
        try {
            this.dispatch(updates[i]);
        } catch (e) {
            mf.log("handler failed: " + e);
        }
    }
    if (updates.length > 0) config.set(OFFSET_KEY, this.offset);
    return updates.length;
};

// Verifies the token and serves updates until the module is stopped. Throws when Telegram
// rejects the token; a rejected token is forgotten so that the next start asks for it again.
Bot.prototype.run = function () {
    var me;
    try {
        me = this.call("getMe");
    } catch (e) {
        var reason = String(e.message);
        if (reason.indexOf("Unauthorized") >= 0 || reason.indexOf("Not Found") >= 0) config.forget(TOKEN_KEY);
        throw e;
    }
    this.me = me;
    mf.log("online as @" + me.username);
    if (this.handlers.ready) {
        try { this.handlers.ready(me); } catch (e) { mf.log("handler failed: " + e); }
    }

    var failures = 0;
    while (true) {
        try {
            this.poll();
            failures = 0;
        } catch (e) {
            failures++;
            mf.log("poll failed (" + failures + "): " + e.message);
            mf.sleep(Math.min(60, Math.pow(2, failures)));
        }
    }
};

// Creates a bot. Options: token (otherwise asked from the user and stored), ask (the question),
// timeout (long-poll seconds, default 25). Throws when there is no token.
exports.bot = function (options) {
    options = options || {};
    var token = options.token || config.get(TOKEN_KEY, { ask: options.ask || "Bot token from @BotFather", secret: true });
    if (!token) throw new Error("no bot token");
    return new Bot(token, options.timeout || 25);
};

// Inline keyboard markup from rows of [text, callback_data] pairs:
//   reply_markup: telegram.keyboard([[["Yes", "y"], ["No", "n"]]])
exports.keyboard = function (rows) {
    return {
        inline_keyboard: rows.map(function (row) {
            return row.map(function (button) { return { text: button[0], callback_data: button[1] }; });
        })
    };
};
