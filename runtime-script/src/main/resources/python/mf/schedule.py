"""Periodic tasks.

    from mf import schedule
    schedule.every(300, check)                             # now, then every 5 minutes
    schedule.every(3600, report, immediately=False)        # first run in an hour
    schedule.run()                                         # never returns

A task that raises is logged and tried again at its next turn.
"""
import mf

_tasks = []


def every(seconds, task, immediately=True):
    if not isinstance(seconds, (int, float)) or not seconds > 0:
        raise TypeError("interval must be a positive number of seconds")
    if not callable(task):
        raise TypeError("task must be callable")
    _tasks.append({"interval": float(seconds), "task": task, "due": mf.time() + (0 if immediately else seconds)})


def step():
    """Runs the due tasks once and returns the seconds until the next one is due."""
    nearest = float("inf")
    for entry in _tasks:
        if entry["due"] <= mf.time():
            try:
                entry["task"]()
            except Exception as error:
                mf.log("scheduled task failed: " + str(error))
            entry["due"] = mf.time() + entry["interval"]
        nearest = min(nearest, entry["due"] - mf.time())
    return nearest


def run():
    if not _tasks:
        raise RuntimeError("no tasks were scheduled")
    while True:
        mf.sleep(max(step(), 0.05))
