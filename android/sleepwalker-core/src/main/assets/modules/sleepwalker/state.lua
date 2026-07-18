local M = {}

local function deep_copy(val)
    if type(val) ~= "table" then
        return val
    end
    local res = {}
    for k, v in pairs(val) do
        res[deep_copy(k)] = deep_copy(v)
    end
    return res
end

function M.copy(state)
    return deep_copy(state)
end

-- Pure update function. Returns a new table with changes merged.
function M.update(state, changes)
    local new_state = deep_copy(state)
    if changes then
        for k, v in pairs(changes) do
            new_state[k] = deep_copy(v)
        end
    end
    return new_state
end

return M
