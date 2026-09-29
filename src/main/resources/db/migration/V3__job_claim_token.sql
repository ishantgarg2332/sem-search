-- Fencing token for the job lease.
--
-- claimBatch() stamps a fresh random token on every claim. markDone() and markFailed()
-- only update the row if the caller still holds that token. If the lease expired and
-- LeaseReaper reset the job (clearing the token), or another worker re-claimed it
-- (a new token), a slow worker's late markDone/markFailed updates zero rows instead of
-- overwriting the new owner's status.
--
-- gen_random_uuid() is built into Postgres 13+ (we run 16), no extension needed.
ALTER TABLE ingest.job ADD COLUMN claim_token UUID;
