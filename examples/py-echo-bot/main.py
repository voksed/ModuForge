# Echo bot: repeats every text message back. Get a token from @BotFather in Telegram;
# the module asks for it on the first start and keeps it in its own storage.
import json

import mf
from mf import telegram

bot = telegram.bot(ask="Bot token from @BotFather")

COUNTER_FILE = "count.json"


def load_counts():
    try:
        return json.loads(mf.storage.read(COUNTER_FILE) or "{}")
    except ValueError:
        return {}


counts = load_counts()


def bump(chat_id):
    key = str(chat_id)
    counts[key] = counts.get(key, 0) + 1
    mf.storage.write(COUNTER_FILE, json.dumps(counts))
    return counts[key]


@bot.command("start")
def start(message, args):
    name = message["from"].get("first_name", "there")
    bot.reply(message, "Hello, " + name + "! Send me any text and I will repeat it. /help lists the commands.")


@bot.command("help")
def help_(message, args):
    bot.reply(message, "/start - greeting\n/help - this text\n/count - how many messages you sent me\nAnything else is echoed.")


@bot.command("count")
def count(message, args):
    bot.reply(message, "You have sent " + str(counts.get(str(message["chat"]["id"]), 0)) + " messages.")


@bot.on("text")
def echo(message):
    n = bump(message["chat"]["id"])
    bot.reply(message, message["text"] + "\n(" + str(n) + ")")


try:
    mf.log("echo bot is running")
    bot.run()
except mf.Error as error:
    mf.log("bot stopped: " + str(error))
