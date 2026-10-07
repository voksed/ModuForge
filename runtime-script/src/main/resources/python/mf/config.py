"""Settings of a module, kept in its encrypted storage (needs FILE_SANDBOXED).

    from mf import config
    token = config.get("token", ask="API token", secret=True)
    every = config.get("interval", label="Seconds between checks", default=300)
    config.set("last_seen", 42)

A value that is missing can be asked from the user once and is then remembered. Values that
have a question or a label appear on the module's screen in the app, where the user can
change them later; the module is restarted to pick the change up.
"""
import json
import mf

_FILE = "config.json"
_DESCRIPTIONS = "config.meta.json"
_cache = None
_described = None


def _read(path):
    try:
        raw = mf.storage.read(path)
    except mf.Error:
        # Storage is not granted: values live until the module stops.
        raw = None
    try:
        data = json.loads(raw or "{}")
    except ValueError:
        data = {}
    return data if isinstance(data, dict) else {}


def _load():
    global _cache
    if _cache is None:
        _cache = _read(_FILE)
    return _cache


def _save():
    try:
        mf.storage.write(_FILE, json.dumps(_cache))
        return True
    except mf.Error:
        return False


def _describe(key, label, secret):
    """Tells the app how to present `key` to the user. Written only when something changed."""
    global _described
    if _described is None:
        _described = _read(_DESCRIPTIONS)
    known = _described.get(key)
    if isinstance(known, dict) and known.get("label") == label and bool(known.get("secret")) == secret:
        return
    _described[key] = {"label": label, "secret": secret}
    order = _described.get("_order")
    if not isinstance(order, list):
        order = []
    if key not in order:
        order.append(key)
    _described["_order"] = order
    try:
        mf.storage.write(_DESCRIPTIONS, json.dumps(_described))
    except mf.Error:
        pass


def get(key, ask=None, label=None, secret=False, default=None, save=True):
    """The stored value of `key`.

    ask      question to put to the user when nothing is stored
    label    name of the value on the module's screen; defaults to the question
    secret   hide what the user types
    default  value to use when nothing is stored and nothing was answered
    save     False keeps an asked or default value out of storage
    """
    text = label or ask
    if text and save:
        _describe(key, str(text), secret is True)
    values = _load()
    if values.get(key) is not None:
        return values[key]
    value = None
    if ask:
        answer = mf.ask(ask, secret)
        if answer is not None:
            answer = answer.strip()
        value = answer or None
    if value is None:
        value = default
    if value is not None and save:
        values[key] = value
        _save()
    return value


def set(key, value):
    _load()[key] = value
    return _save()


def forget(key):
    _load().pop(key, None)
    return _save()
