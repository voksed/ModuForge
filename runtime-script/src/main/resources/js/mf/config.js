// Settings of a module, kept in its encrypted storage (needs FILE_SANDBOXED).
//
//   var config = require("mf/config");
//   var token = config.get("token", { ask: "API token", secret: true });
//   config.set("lastSeen", 42);
//
// A value that is missing can be asked from the user once and is then remembered.
var FILE = "config.json";
var cache = null;

function load() {
    if (cache) return cache;
    var raw = null;
    try { raw = mf.storage.read(FILE); } catch (e) { /* storage not granted: values live until the module stops */ }
    try { cache = JSON.parse(raw || "{}"); } catch (e) { cache = {}; }
    if (cache === null || typeof cache !== "object") cache = {};
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
//   secret   hide what the user types
//   default  value to use when nothing is stored and nothing was answered
//   save     false keeps an asked or default value out of storage
exports.get = function (key, options) {
    var values = load();
    if (values[key] !== undefined && values[key] !== null) return values[key];
    options = options || {};
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
