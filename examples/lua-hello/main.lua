-- Entry script: runs on its own thread from start until the module is stopped.
local greeting = require("lib.greeting")

mf.log(greeting.build(mf.name, mf.version))

-- Storage: count how many times the module was started.
if mf.request("FILE_SANDBOXED", "Remember how many times the module was started.") then
    local runs = (tonumber(mf.storage.read("runs.txt")) or 0) + 1
    mf.storage.write("runs.txt", tostring(runs))
    mf.log("start number " .. runs)
end

-- Network: one request through the host.
if mf.request("NETWORK_OUTBOUND", "Fetch example.com once to show networking.") then
    local response, err = mf.http{ url = "https://example.com/" }
    if response then
        mf.log("example.com answered " .. response.status .. ", " .. #response.body .. " bytes")
    else
        mf.log("request failed: " .. err)
    end
end

-- A capability missing from the manifest is refused without asking the user.
local clipboard, why = mf.request("CLIPBOARD", "Not declared in the manifest.")
mf.log("CLIPBOARD granted: " .. tostring(clipboard) .. " (" .. tostring(why) .. ")")

-- Keeps running while the host is on screen; with BACKGROUND_EXECUTION granted, also off screen.
mf.request("BACKGROUND_EXECUTION", "Keep ticking while the app is not on screen.")
local ticks = 0
while true do
    mf.sleep(5)
    ticks = ticks + 1
    mf.log("still running, tick " .. ticks)
end
