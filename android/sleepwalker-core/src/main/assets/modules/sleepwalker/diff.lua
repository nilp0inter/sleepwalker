local M = {}

-- Returns the length of the longest common prefix of two strings.
local function common_prefix(s1, s2)
    local min_len = math.min(#s1, #s2)
    for i = 1, min_len do
        if string.byte(s1, i) ~= string.byte(s2, i) then
            return i - 1
        end
    end
    return min_len
end

-- Returns lcp, lcs.
-- Zero-based offsets, half-open ranges.
function M.common_affixes(current, desired)
    local lcp = common_prefix(current, desired)
    local max_suffix = math.min(#current, #desired) - lcp
    local lcs = 0
    for i = max_suffix, 1, -1 do
        local match = true
        for j = 0, i - 1 do
            if string.byte(current, #current - j) ~= string.byte(desired, #desired - j) then
                match = false
                break
            end
        end
        if match then
            lcs = i
            break
        end
    end
    return lcp, lcs
end

-- Returns the replacement start index, end index (0-based, half-open), and replacement text.
function M.difference(current, desired)
    local lcp, lcs = M.common_affixes(current, desired)
    local start_idx = lcp
    local end_idx = #current - lcs
    local insert_text = string.sub(desired, lcp + 1, #desired - lcs)
    return start_idx, end_idx, insert_text
end

return M
