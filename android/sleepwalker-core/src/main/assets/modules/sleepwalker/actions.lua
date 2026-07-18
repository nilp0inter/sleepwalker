local M = {}

function M.tap(usage)
    return { kind = "tap", usage = usage }
end

function M.down(usage)
    return { kind = "down", usage = usage }
end

function M.up(usage)
    return { kind = "up", usage = usage }
end

function M.text(text)
    return { kind = "text", text = text }
end

-- Concatenates action lists. Returns a new flat list of actions.
function M.concat(...)
    local result = {}
    local args = {...}
    for _, list in ipairs(args) do
        if list then
            for _, act in ipairs(list) do
                table.insert(result, act)
            end
        end
    end
    return result
end

return M
