-- A dish can be vegan even when its provider serves a mixed catalogue.
alter type public.dietary_type add value if not exists 'VEGAN';
