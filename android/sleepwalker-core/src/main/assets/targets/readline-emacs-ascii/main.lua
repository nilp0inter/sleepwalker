-- readline-emacs-ascii: GNU Readline 8.2 Emacs-mode ASCII target program.
--
-- Composes plans from Emacs control chords (C-a, C-b, C-e, C-f, C-d,
-- C-h) and host.text_plan() for printable text, using a pinned
-- deterministic navigation strategy: C-a (home) then C-f forward.
--
-- Explicit program state (buffer, point, revision) is received as an
-- input and returned as an output; no mutable Lua VM state is retained
-- between invocations.
--
-- Out-of-scope behaviours (Vi mode, history, completion, submission,
-- Unicode, wordwise, active selections) are rejected with a structured
-- nil/error return.

function plan(current, desired, lcp, oldMid, newMid, state, predictedPoint)
    -- Deep-copy input state (caller owns the original)
    local s = {
        buffer   = state.buffer,
        point    = state.point,
        revision = state.revision,
    }

    -- Guard: out-of-scope behaviours
    if string.find(desired, "[^ -~]") then
        return nil, "UnsupportedBehavior: non-printable ASCII is out of scope"
    end

    -- Step 1: Navigate to replacement start. Empty neutral state needs no
    -- navigation, preserving an insert-only initial plan.
    if #current > 0 or s.point ~= 0 then
        host.ctrl("A")
        s.point = 0
    end

    for _ = 1, lcp do
        host.ctrl("F")
        s.point = math.min(s.point + 1, #s.buffer)
    end

    -- Step 2: Delete oldMid content (C-d forward)
    for _ = 1, #oldMid do
        host.ctrl("D")
        -- C-d removes the character at point; point stays
        if s.point < #s.buffer then
            s.buffer = string.sub(s.buffer, 1, s.point) ..
                       string.sub(s.buffer, s.point + 2)
        elseif s.point == #s.buffer and #s.buffer > 0 then
            -- C-d at end is a no-op in Readline; model matches
        end
    end

    -- Step 3: Type newMid via TextPlanner
    if #newMid > 0 then
        host.text_plan(newMid)
        -- Insert newMid at point and advance
        local before = string.sub(s.buffer, 1, s.point)
        local after  = string.sub(s.buffer, s.point + 1)
        s.buffer = before .. newMid .. after
        s.point = s.point + #newMid
    end

    -- Step 4: Bump revision
    s.revision = s.revision + 1

    -- Step 5: Verify target prediction matches both requested outputs.
    if s.buffer ~= desired then
        return nil, "InconsistentPrediction:" .. s.buffer
    end
    if s.point ~= predictedPoint then
        return nil, "InconsistentPrediction:" .. s.buffer
    end

    return s
end
