// Periodic tasks.
//
//   var schedule = require("mf/schedule");
//   schedule.every(300, function () { ... });                 // now, then every 5 minutes
//   schedule.every(3600, hourly, { immediately: false });     // first run in an hour
//   schedule.run();                                           // never returns
//
// A task that throws is logged and tried again at its next turn.
var tasks = [];

exports.every = function (seconds, task, options) {
    if (typeof seconds !== "number" || !(seconds > 0)) throw new TypeError("interval must be a positive number of seconds");
    if (typeof task !== "function") throw new TypeError("task must be a function");
    var wait = options && options.immediately === false ? seconds : 0;
    tasks.push({ interval: seconds, task: task, due: mf.time() + wait });
};

// Runs the due tasks once and returns the seconds until the next one is due.
exports.step = function () {
    var nearest = Infinity;
    tasks.forEach(function (entry) {
        if (entry.due <= mf.time()) {
            try {
                entry.task();
            } catch (e) {
                mf.log("scheduled task failed: " + e);
            }
            entry.due = mf.time() + entry.interval;
        }
        nearest = Math.min(nearest, entry.due - mf.time());
    });
    return nearest;
};

exports.run = function () {
    if (tasks.length === 0) throw new Error("no tasks were scheduled");
    while (true) mf.sleep(Math.max(exports.step(), 0.05));
};
