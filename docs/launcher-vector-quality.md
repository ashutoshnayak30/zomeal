# Customer launcher quality

Replaced the raster-backed customer launcher and themed icon with native Android VectorDrawable paths. The geometry is traced deterministically from the user's original white-on-green logo, not generated again. Green background remains a native gradient. Internal leaf, handle and tiffin gaps are retained using even-odd fill.

Adaptive foreground is centered inside 62/108 units; legacy foreground uses a larger 1.25 scale. In-app logos, the full-colour notification image and partner app are untouched.

Reproduction: run `scripts/vectorize-customer-launcher.cjs` with the original PNG path. The PNG decoder accepts 8-bit RGB/RGBA non-interlaced PNG. Path simplification removes sub-icon-scale raster noise. The script enforces Android's resource-string size limit.

`scripts/check-vector-launcher.cjs` verifies vector resources and produces enlarged, circular-mask and 48px previews. Physical launcher rendering/cache still needs a phone check after installation.
