local M = {}

function M.build(name, version)
    return string.format("Hello from %s %s (%s)", name, version, _VERSION)
end

return M
