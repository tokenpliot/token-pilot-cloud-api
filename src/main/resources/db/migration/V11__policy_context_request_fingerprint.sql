-- #15: body fingerprint of the decision request that first bound a policy request context.
-- A concurrent request with the same requestId but another body (e.g. another model) would otherwise be evaluated
-- against the first request's pricing snapshot before either decision row exists. Null for contexts bound without
-- a decision body (including rows created before this migration).
-- Kept separate from V8 so databases that already applied V8 from the feature branch keep a valid checksum.
alter table policy_request_contexts
    add column request_fingerprint char(64) null;
