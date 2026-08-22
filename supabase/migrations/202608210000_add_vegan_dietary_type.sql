-- Vegan is a first-class dish classification in provider menus.
alter type public.dietary_type add value if not exists 'VEGAN' after 'VEG';
