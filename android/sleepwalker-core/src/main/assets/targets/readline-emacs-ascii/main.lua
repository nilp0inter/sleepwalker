local diff = require("sleepwalker.diff")
local actions = require("sleepwalker.actions")
local state_mod = require("sleepwalker.state")
local plan = require("sleepwalker.plan")
local cost = require("sleepwalker.cost")

local M = {}

M.abi_version = 1

function M.initialize(current_rendered_text)
    return {
        buffer = current_rendered_text or "",
        point = #(current_rendered_text or ""),
        revision = 0,
    }
end

local function ctrl(key_char)
    return {
        actions.down("USB_KEY_LEFTCTRL"),
        actions.tap("USB_KEY_" .. string.upper(key_char)),
        actions.up("USB_KEY_LEFTCTRL")
    }
end

local function check_constraints(desired)
    if string.find(desired, "\n") or string.find(desired, "\r") then
        return false, "UnsupportedBehavior: multi-line content not supported by readline-emacs-ascii"
    end
    for i = 1, #desired do
        local b = string.byte(desired, i)
        if b < 32 or b > 126 then
            return false, "UnrepresentableContent: " .. string.sub(desired, i, i)
        end
    end
    return true
end

function M.plan(current, desired, state)
    local ok, err_msg = check_constraints(desired)
    if not ok then
        return { error = err_msg }
    end

    if current == desired then
        return {
            actions = {},
            next_state = state
        }
    end

    local lcp, lcs = diff.common_affixes(current, desired)
    local start_idx = lcp
    local end_idx = #current - lcs
    local old_len = end_idx - start_idx
    local new_text = string.sub(desired, lcp + 1, #desired - lcs)

    -- Candidate 1: Home-then-forward navigation + forward deletion (C-d)
    local function plan_home_forward_cd()
        local s = state_mod.copy(state)
        local acts = {}
        if #current > 0 or s.point ~= 0 then
            acts = actions.concat(acts, ctrl("A"))
            s.point = 0
        end
        for _ = 1, start_idx do
            acts = actions.concat(acts, ctrl("F"))
            s.point = s.point + 1
        end
        for _ = 1, old_len do
            acts = actions.concat(acts, ctrl("D"))
            s.buffer = string.sub(s.buffer, 1, s.point) .. string.sub(s.buffer, s.point + 2)
        end
        if #new_text > 0 then
            table.insert(acts, actions.text(new_text))
            s.buffer = string.sub(s.buffer, 1, s.point) .. new_text .. string.sub(s.buffer, s.point + 1)
            s.point = s.point + #new_text
        end
        s.revision = s.revision + 1
        if s.buffer ~= desired then
            return nil, "inconsistent prediction"
        end
        return {
            text = s.buffer,
            state = s,
            actions = acts,
            cost = cost.sum(acts)
        }
    end

    -- Candidate 2: Home-then-forward navigation + backward deletion (C-h)
    local function plan_home_forward_ch()
        local s = state_mod.copy(state)
        local acts = {}
        if #current > 0 or s.point ~= 0 then
            acts = actions.concat(acts, ctrl("A"))
            s.point = 0
        end
        for _ = 1, end_idx do
            acts = actions.concat(acts, ctrl("F"))
            s.point = s.point + 1
        end
        for _ = 1, old_len do
            acts = actions.concat(acts, ctrl("H"))
            s.buffer = string.sub(s.buffer, 1, s.point - 1) .. string.sub(s.buffer, s.point + 1)
            s.point = s.point - 1
        end
        if #new_text > 0 then
            table.insert(acts, actions.text(new_text))
            s.buffer = string.sub(s.buffer, 1, s.point) .. new_text .. string.sub(s.buffer, s.point + 1)
            s.point = s.point + #new_text
        end
        s.revision = s.revision + 1
        if s.buffer ~= desired then
            return nil, "inconsistent prediction"
        end
        return {
            text = s.buffer,
            state = s,
            actions = acts,
            cost = cost.sum(acts)
        }
    end

    -- Candidate 3: Relative navigation + forward deletion (C-d)
    local function plan_relative_cd()
        local s = state_mod.copy(state)
        local acts = {}
        if start_idx > s.point then
            for _ = 1, start_idx - s.point do
                acts = actions.concat(acts, ctrl("F"))
                s.point = s.point + 1
            end
        elseif start_idx < s.point then
            for _ = 1, s.point - start_idx do
                acts = actions.concat(acts, ctrl("B"))
                s.point = s.point - 1
            end
        end
        for _ = 1, old_len do
            acts = actions.concat(acts, ctrl("D"))
            s.buffer = string.sub(s.buffer, 1, s.point) .. string.sub(s.buffer, s.point + 2)
        end
        if #new_text > 0 then
            table.insert(acts, actions.text(new_text))
            s.buffer = string.sub(s.buffer, 1, s.point) .. new_text .. string.sub(s.buffer, s.point + 1)
            s.point = s.point + #new_text
        end
        s.revision = s.revision + 1
        if s.buffer ~= desired then
            return nil, "inconsistent prediction"
        end
        return {
            text = s.buffer,
            state = s,
            actions = acts,
            cost = cost.sum(acts)
        }
    end

    -- Candidate 4: Relative navigation + backward deletion (C-h)
    local function plan_relative_ch()
        local s = state_mod.copy(state)
        local acts = {}
        if end_idx > s.point then
            for _ = 1, end_idx - s.point do
                acts = actions.concat(acts, ctrl("F"))
                s.point = s.point + 1
            end
        elseif end_idx < s.point then
            for _ = 1, s.point - end_idx do
                acts = actions.concat(acts, ctrl("B"))
                s.point = s.point - 1
            end
        end
        for _ = 1, old_len do
            acts = actions.concat(acts, ctrl("H"))
            s.buffer = string.sub(s.buffer, 1, s.point - 1) .. string.sub(s.buffer, s.point + 1)
            s.point = s.point - 1
        end
        if #new_text > 0 then
            table.insert(acts, actions.text(new_text))
            s.buffer = string.sub(s.buffer, 1, s.point) .. new_text .. string.sub(s.buffer, s.point + 1)
            s.point = s.point + #new_text
        end
        s.revision = s.revision + 1
        if s.buffer ~= desired then
            return nil, "inconsistent prediction"
        end
        return {
            text = s.buffer,
            state = s,
            actions = acts,
            cost = cost.sum(acts)
        }
    end

    -- Candidate 5: End-backward navigation + forward deletion (C-d)
    local function plan_end_backward_cd()
        local s = state_mod.copy(state)
        local acts = {}
        acts = actions.concat(acts, ctrl("E"))
        s.point = #current
        for _ = 1, s.point - start_idx do
            acts = actions.concat(acts, ctrl("B"))
            s.point = s.point - 1
        end
        for _ = 1, old_len do
            acts = actions.concat(acts, ctrl("D"))
            s.buffer = string.sub(s.buffer, 1, s.point) .. string.sub(s.buffer, s.point + 2)
        end
        if #new_text > 0 then
            table.insert(acts, actions.text(new_text))
            s.buffer = string.sub(s.buffer, 1, s.point) .. new_text .. string.sub(s.buffer, s.point + 1)
            s.point = s.point + #new_text
        end
        s.revision = s.revision + 1
        if s.buffer ~= desired then
            return nil, "inconsistent prediction"
        end
        return {
            text = s.buffer,
            state = s,
            actions = acts,
            cost = cost.sum(acts)
        }
    end

    -- Candidate 6: End-backward navigation + backward deletion (C-h)
    local function plan_end_backward_ch()
        local s = state_mod.copy(state)
        local acts = {}
        acts = actions.concat(acts, ctrl("E"))
        s.point = #current
        for _ = 1, s.point - end_idx do
            acts = actions.concat(acts, ctrl("B"))
            s.point = s.point - 1
        end
        for _ = 1, old_len do
            acts = actions.concat(acts, ctrl("H"))
            s.buffer = string.sub(s.buffer, 1, s.point - 1) .. string.sub(s.buffer, s.point + 1)
            s.point = s.point - 1
        end
        if #new_text > 0 then
            table.insert(acts, actions.text(new_text))
            s.buffer = string.sub(s.buffer, 1, s.point) .. new_text .. string.sub(s.buffer, s.point + 1)
            s.point = s.point + #new_text
        end
        s.revision = s.revision + 1
        if s.buffer ~= desired then
            return nil, "inconsistent prediction"
        end
        return {
            text = s.buffer,
            state = s,
            actions = acts,
            cost = cost.sum(acts)
        }
    end

    local chooser = plan.choose({
        plan_home_forward_cd,
        plan_home_forward_ch,
        plan_relative_cd,
        plan_relative_ch,
        plan_end_backward_cd,
        plan_end_backward_ch
    })

    local best, err = chooser(current, desired, state)
    if not best then
        return { error = err or "planning failed" }
    end

    return {
        actions = best.actions,
        next_state = best.state
    }
end

return M
