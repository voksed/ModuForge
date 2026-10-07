import json

import mf
from mf import config

STATE_FILE = "state.json"


def load_state():
    try:
        return json.loads(mf.storage.read(STATE_FILE) or "{}")
    except ValueError:
        return {}


def check(url):
    """Returns (is_up, milliseconds, note) for one address."""
    started = mf.time()
    try:
        response = mf.http(url, redirects=3)
    except mf.Error as error:
        return False, None, str(error)
    except TimeoutError:
        return False, None, "timed out"
    millis = round((mf.time() - started) * 1000)
    if response.status >= 500:
        return False, millis, "HTTP " + str(response.status)
    return True, millis, "HTTP " + str(response.status)


def sites():
    raw = config.get("sites", label="Sites to watch (separated by spaces)",
                     ask="Addresses to watch, separated by spaces")
    result = []
    for item in raw.replace(",", " ").split():
        result.append(item if "://" in item else "https://" + item)
    return result


def screen(rows, interval):
    items = [{"type": "text", "text": "Site watch", "style": "title"}]
    for url, up, millis, note in rows:
        mark = "UP  " if up else "DOWN"
        speed = "" if millis is None else "  " + str(millis) + " ms"
        items.append({"type": "text", "text": mark + "  " + url + speed + "  (" + note + ")"})
    when = mf.date("%H:%M:%S")
    items.append({"type": "text", "text": "Checked at " + when + ". Next check in "
                  + str(interval // 60) + " min.", "style": "caption"})
    items.append({"type": "button", "id": "now", "label": "Check now"})
    mf.ui.show(items)


def main():
    state = load_state()
    while True:
        interval = max(60, int(config.get("interval", label="Seconds between checks", default=300)))
        rows = []
        for url in sites():
            up, millis, note = check(url)
            rows.append((url, up, millis, note))
            before = state.get(url)
            if before is not None and before != up:
                if up:
                    mf.notify("Back up", url)
                else:
                    mf.notify("Down", url + " - " + note)
            state[url] = up
            mf.log(("up " if up else "DOWN ") + url + " " + note)
        mf.storage.write(STATE_FILE, json.dumps(state))
        screen(rows, interval)
        # Wait for the next round; the button asks for one at once.
        mf.ui.wait(interval)


main()
