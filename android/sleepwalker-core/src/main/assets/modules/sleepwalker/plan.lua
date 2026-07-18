local M = {}

local cost_mod = require("sleepwalker.cost")
local state_mod = require("sleepwalker.state")
local actions_mod = require("sleepwalker.actions")

-- Identity planner: returns a candidate representing no changes.
function M.identity(current, state)
    return {
        text = current,
        state = state,
        actions = {},
        cost = 0
    }
end

-- Rejection helper: returns nil and a reason.
function M.reject(reason)
    return nil, reason
end

-- Sequences multiple planners.
-- Runs them one after another. If any planner fails/rejects, returns nil and the reason.
function M.sequence(planners)
    return function(current, desired, state)
        local candidate = {
            text = current,
            state = state_mod.copy(state),
            actions = {},
            cost = 0
        }
        for _, p in ipairs(planners) do
            local next_cand, err = p(candidate.text, desired, candidate.state)
            if not next_cand then
                return nil, err
            end
            candidate.text = next_cand.text
            candidate.state = next_cand.state
            candidate.actions = actions_mod.concat(candidate.actions, next_cand.actions)
            candidate.cost = candidate.cost + (next_cand.cost or 0)
        end
        return candidate
    end
end

-- Choice combinator: runs all planners and picks the best one using cost comparison.
function M.choose(planners)
    return function(current, desired, state)
        local best_cand = nil
        local last_err = "no candidates"
        for _, p in ipairs(planners) do
            local cand, err = p(current, desired, state)
            if cand then
                if not best_cand or cost_mod.compare(cand, best_cand) then
                    best_cand = cand
                end
            else
                last_err = err
            end
        end
        if not best_cand then
            return nil, last_err
        end
        return best_cand
    end
end

-- Validation combinator: asserts that the generated candidate satisfies a predicate.
function M.validate(planner, predicate)
    return function(current, desired, state)
        local cand, err = planner(current, desired, state)
        if not cand then
            return nil, err
        end
        local ok, val_err = predicate(cand)
        if not ok then
            return nil, val_err or "validation failed"
        end
        return cand
    end
end

return M
