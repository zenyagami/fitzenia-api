-- 006_coach_message_segments.sql
-- Per-segment token accounting for escalated coach turns.
--
-- An escalated turn runs TWO model passes: the Flash Lite first pass and the Pro retry.
-- Until now only the Pro-pass counts survived (the Lite pass was overwritten in ChatRoutes),
-- so the Lite segment of an escalated turn was never persisted. Cost-weighted budgeting
-- (007) needs both segments because they carry different per-token prices (Pro = 6x Lite).
--
-- Semantics:
--   input_tokens / output_tokens      = TOTAL across all passes (lite + pro), as before
--                                       for non-escalated turns.
--   pro_input_tokens / pro_output_tokens = the Pro-pass share. NULL = legacy row or
--                                       non-escalated turn. Lite share = total - pro.
--
-- Applied to: dev (tpslgveyjldykkkhnifs) and prod (anqvtpesmddllplyhkrc).

alter table public.coach_message
    add column if not exists pro_input_tokens integer check (pro_input_tokens >= 0),
    add column if not exists pro_output_tokens integer check (pro_output_tokens >= 0);

comment on column public.coach_message.pro_input_tokens is
    'Input tokens of the Pro (escalation) pass only. NULL = not escalated or legacy row. Lite share = input_tokens - pro_input_tokens.';
comment on column public.coach_message.pro_output_tokens is
    'Output tokens of the Pro (escalation) pass only. NULL = not escalated or legacy row. Lite share = output_tokens - pro_output_tokens.';
