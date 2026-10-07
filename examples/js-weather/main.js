// Weather notifier: a settings value, a schedule, an HTTP request and a notification.
var config = require("mf/config");
var schedule = require("mf/schedule");

// Asked once; afterwards it is on the module's screen under "Settings".
var city = config.get("city", { ask: "City to watch the weather in", label: "City" });
var minutes = config.get("minutes", { label: "Minutes between checks", default: 30 });

if (!city) {
    mf.log("no city given, nothing to do");
} else {
    schedule.every(Number(minutes) * 60, function () {
        var data = mf.http({ url: "https://wttr.in/" + mf.urlencode(city) + "?format=j1" }).json();
        var now = Number(data.current_condition[0].temp_C);
        var before = config.get("last");
        mf.log(city + ": " + now + " °C at " + mf.date("%H:%M"));
        if (before !== null && Math.abs(now - Number(before)) >= 1) {
            mf.notify(city + ": " + now + " °C", "was " + before + " °C");
        }
        config.set("last", now);
    });
    schedule.run();
}
