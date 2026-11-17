-- PDF §5.3 / §8 — sliding-window rate limit as a PN-Counter CRDT on Valkey (ADR-001).
-- Each region increments only its own P/N slots, so replicas in different regions merge conflict-free
-- (value = Σ P[r] − Σ N[r]). Window buckets expire automatically.
--
-- KEYS[1]  counter hash for (client, window-bucket), e.g. rl:{client}:1731830400
-- KEYS[2]  counter hash for the previous bucket (sliding window)
-- ARGV[1]  region id           ARGV[2] limit per window
-- ARGV[3]  window seconds      ARGV[4] elapsed fraction of the current window (0..1, from event_timestamp)
-- ARGV[5]  delta (+1 acquire, -1 refund on compensation)
-- Returns {allowed (1/0), current estimated count}

local function value(key)
  local fields = redis.call('HGETALL', key)
  local total = 0
  for i = 1, #fields, 2 do
    local f, v = fields[i], tonumber(fields[i + 1])
    if string.sub(f, 1, 2) == 'P:' then total = total + v else total = total - v end
  end
  return total
end

local region, limit, window = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3])
local elapsed, delta = tonumber(ARGV[4]), tonumber(ARGV[5])

local estimate = value(KEYS[1]) + value(KEYS[2]) * (1 - elapsed)
if delta > 0 and estimate + delta > limit then
  return {0, math.floor(estimate)}
end

if delta > 0 then
  redis.call('HINCRBY', KEYS[1], 'P:' .. region, delta)
else
  redis.call('HINCRBY', KEYS[1], 'N:' .. region, -delta)
end
redis.call('EXPIRE', KEYS[1], window * 2)
return {1, math.floor(estimate + delta)}
