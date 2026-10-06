// Settings of a module, kept in its encrypted storage (needs FILE_SANDBOXED).
//
//   var config = require("mf/config");
//   var token = config.get("token", { ask: "API token", secret: true });
//   config.set("lastSeen", 42);
//
// A value that is missing can be asked from the user once and is then remembered. Values
// that have a question or a label appear on the module's screen in the app, where the user
// can change them later; the module is restarted to pick the change up.
var FILE = "config.json";
var DESCRIPTIONS = "config.meta.json";
var cache = null;
var descriptions = null;

function readObject(file) {
    var raw = null;
    var parsed = null;
    try { raw = mf.storage.read(file); } catch (e) { /* storage not granted: values live until the module stops */ }
    try { parsed = JSON.parse(raw || "{}"); } catch (e) { parsed = {}; }
    return parsed !== null && typeof parsed === "object" ? parsed : {};
}

// Tells the app how to present `key` to the user. Written only when something changed.
function describe(key, label, secret) {
    if (!descriptions) descriptions = readObject(DESCRIPTIONS);
    var known = descriptions[key];
    if (known && known.label === label && (known.secret === true) === secret) return;
    descriptions[key] = { label: label, secret: secret };
    try { mf.storage.write(DESCRIPTIONS, JSON.stringify(descriptions)); } catch (e) { /* shown next time */ }
}

function load() {
    if (!cache) cache = readObject(FILE);
    return cache;
}

function save() {
    try {
        mf.storage.write(FILE, JSON.stringify(cache));
        return true;
    } catch (e) {
        return false;
    }
}

// Returns the stored value of `key`. Options:
//   ask      question to put to the user when nothing is stored
//   label    name of the value on the module's screen; defaults to the question
//   secret   hide what the user types
//   default  value to use when nothing is stored and nothing was answered
//   save     false keeps an asked or default value out of storage
exports.get = function (key, options) {
    options = options || {};
    var label = options.label || options.ask;
    if (label && options.save !== false) describe(key, String(label), options.secret === true);
    var values = load();
    if (values[key] !== undefined && values[key] !== null) return values[key];
    var value = null;
    if (options.ask) {
        value = mf.ask(options.ask, options.secret === true);
        if (value !== null) value = String(value).trim();
        if (value === "") value = null;
    }
    if (value === null && options["default"] !== undefined) value = options["default"];
    if (value !== null && options.save !== false) {
        values[key] = value;
        save();
    }
    return value;
};

exports.set = function (key, value) {
    load()[key] = value;
    return save();
};

exports.forget = function (key) {
    delete load()[key];
    return save();
};
