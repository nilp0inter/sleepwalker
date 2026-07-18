local M = {}

M.text_cost = (sleepwalker and sleepwalker.cost and sleepwalker.cost.text_cost) or function(text)
    -- Fallback for tests if needed
    return #text, "layout", "metric"
end

local function compare_actions(a1, a2)
    if a1.kind ~= a2.kind then
        return a1.kind < a2.kind
    end
    if a1.kind == "text" then
        return (a1.text or "") < (a2.text or "")
    else
        return (a1.usage or "") < (a2.usage or "")
    end
end

local function compare_action_lists(list1, list2)
    local n1 = #list1
    local n2 = #list2
    local min_n = math.min(n1, n2)
    for i = 1, min_n do
        local a1 = list1[i]
        local a2 = list2[i]
        if compare_actions(a1, a2) then
            return true
        elseif compare_actions(a2, a1) then
            return false
        end
    end
    return n1 < n2
end

function M.sum(actions)
    local total = 0
    for _, action in ipairs(actions) do
        if action.kind == "text" then
            local cost = M.text_cost(action.text)
            if not cost then
                return nil
            end
            total = total + cost
        else
            total = total + 1
        end
    end
    return total
end

function M.compare(c1, c2)
    if not c1 then return false end
    if not c2 then return true end

    local cost1 = c1.cost
    local cost2 = c2.cost

    if cost1 == nil and cost2 == nil then
        return compare_action_lists(c1.actions, c2.actions)
    elseif cost1 == nil then
        return false
    elseif cost2 == nil then
        return true
    elseif cost1 ~= cost2 then
        return cost1 < cost2
    else
        return compare_action_lists(c1.actions, c2.actions)
    end
end

return M
