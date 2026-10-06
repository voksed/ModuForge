-- Periodic tasks.
--
--   local schedule = require("mf.schedule")
--   schedule.every(300, function() ... end)                 -- now, then every 5 minutes
--   schedule.every(3600, hourly, { immediately = false })   -- first run in an hour
--   schedule.run()                                          -- never returns
--
-- A task that raises an error is logged and tried again at its next turn.
local M = {}

local tasks = {}

function M.every(seconds, task, options)
    assert(type(seconds) == "number" and seconds > 0, "interval must be a positive number of seconds")
    assert(type(task) == "function", "task must be a function")
    local wait = (options and options.immediately == false) and seconds or 0
    tasks[#tasks + 1] = { interval = seconds, task = task, due = mf.time() + wait }
end

-- Runs the due tasks once and returns the seconds until the next one is due.
function M.step()
    local nearest = math.huge
    for _, entry in ipairs(tasks) do
        if entry.due <= mf.time() then
            local ok, err = pcall(entry.task)
            if not ok then mf.log("scheduled task failed: " .. tostring(err)) end
            entry.due = mf.time() + entry.interval
        end
        nearest = math.min(nearest, entry.due - mf.time())
    end
    return nearest
end

function M.run()
    assert(#tasks > 0, "no tasks were scheduled")
    while true do
        mf.sleep(math.max(M.step(), 0.05))
    end
end

return M
