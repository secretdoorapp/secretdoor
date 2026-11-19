# secretdoor.fyi

The SecretDoor website: plain static HTML and CSS, served by Cloudflare.

- `build.py` regenerates the pages. The manual topics and the privacy policy come from `../docs/`,
  the home, manual overview and support pages are written in `build.py`. Run
  `python3 website/build.py` after changing either, and commit the generated files.
- `assets/` holds the stylesheet and images (`assets/img/shots` are the manual screenshots).
- Deploy: `cd website && npx wrangler deploy` (log in once with `npx wrangler login`).
  `wrangler.toml` attaches `secretdoor.fyi` and `www.secretdoor.fyi` as custom domains.
