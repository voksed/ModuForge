"""Telegram Bot API framework (needs NETWORK_OUTBOUND and FILE_SANDBOXED).

    from mf import telegram
    bot = telegram.bot()                    # asks for the token once and remembers it

    @bot.command("start")
    def start(message, args):
        bot.reply(message, "Hello, " + message["from"]["first_name"])

    @bot.on("text")
    def echo(message):
        bot.reply(message, message["text"])

    bot.run()                               # long polling; raises only on a fatal error

Handlers: command(name)(fn(message, args)), on("text" | "message" | "callback" | "ready")(fn).
An error inside a handler is logged and does not stop the bot.
"""
import json
import re
import mf
from mf import config

_TOKEN_KEY = "telegram_token"
_OFFSET_KEY = "telegram_offset"
_COMMAND = re.compile(r"^/([A-Za-z0-9_]+)(?:@[A-Za-z0-9_]*)?\s*(.*)$", re.S)


class Bot:
    def __init__(self, token, timeout):
        self.base = "https://api.telegram.org/bot" + token + "/"
        self.timeout = timeout
        self.commands = {}
        self.handlers = {}
        self.offset = None
        self.me = None

    def call(self, method, parameters=None):
        """Calls any Bot API method. Returns its result; raises mf.Error with Telegram's description."""
        response = mf.http(
            self.base + method,
            method="POST",
            headers={"Content-Type": "application/json"},
            body=json.dumps(parameters or {}),
        )
        try:
            data = json.loads(response.body)
        except ValueError:
            raise mf.Error("unexpected answer, HTTP " + str(response.status))
        if not data or not data.get("ok"):
            raise mf.Error((data or {}).get("description") or ("HTTP " + str(response.status)))
        return data.get("result")

    def send(self, chat_id, text, extra=None):
        """Sends a text message. `extra` adds any sendMessage fields (parse_mode, reply_markup, ...)."""
        parameters = {"chat_id": chat_id, "text": text}
        if extra:
            parameters.update(extra)
        return self.call("sendMessage", parameters)

    def reply(self, message, text, extra=None):
        return self.send(message["chat"]["id"], text, extra)

    def command(self, name, handler=None):
        """Registers a handler for /name, directly or as a decorator."""
        key = name.lstrip("/")

        def register(function):
            self.commands[key] = function
            return function

        return register(handler) if handler is not None else register

    def on(self, kind, handler=None):
        def register(function):
            self.handlers[kind] = function
            return function

        return register(handler) if handler is not None else register

    def dispatch(self, update):
        message = update.get("message")
        if message:
            text = message.get("text")
            if text:
                found = _COMMAND.match(text)
                if found and found.group(1) in self.commands:
                    return self.commands[found.group(1)](message, found.group(2))
                if "text" in self.handlers:
                    return self.handlers["text"](message)
            if "message" in self.handlers:
                return self.handlers["message"](message)
        elif update.get("callback_query") and "callback" in self.handlers:
            return self.handlers["callback"](update["callback_query"])

    def poll(self):
        """Fetches updates once and dispatches them. Returns the number handled."""
        if self.offset is None:
            self.offset = int(config.get(_OFFSET_KEY) or 0)
        updates = self.call("getUpdates", {"offset": self.offset, "timeout": self.timeout})
        for update in updates:
            self.offset = update["update_id"] + 1
            try:
                self.dispatch(update)
            except Exception as error:
                mf.log("handler failed: " + str(error))
        if updates:
            config.set(_OFFSET_KEY, self.offset)
        return len(updates)

    def run(self):
        """Verifies the token and serves updates until the module is stopped.

        Raises when Telegram rejects the token; a rejected token is forgotten so that the
        next start asks for it again.
        """
        try:
            me = self.call("getMe")
        except mf.Error as error:
            reason = str(error)
            if "Unauthorized" in reason or "Not Found" in reason:
                config.forget(_TOKEN_KEY)
            raise
        self.me = me
        mf.log("online as @" + str(me.get("username")))
        if "ready" in self.handlers:
            try:
                self.handlers["ready"](me)
            except Exception as error:
                mf.log("handler failed: " + str(error))
        failures = 0
        while True:
            try:
                self.poll()
                failures = 0
            except mf.Error as error:
                failures += 1
                mf.log("poll failed (" + str(failures) + "): " + str(error))
                mf.sleep(min(60, 2 ** failures))


def bot(token=None, ask=None, timeout=25):
    """Creates a bot. The token is asked from the user once and stored."""
    token = token or config.get(_TOKEN_KEY, ask=ask or "Bot token from @BotFather", secret=True)
    if not token:
        raise mf.Error("no bot token")
    return Bot(token, timeout)


def keyboard(rows):
    """Inline keyboard markup from rows of (text, callback_data) pairs."""
    return {"inline_keyboard": [[{"text": button[0], "callback_data": button[1]} for button in row] for row in rows]}
