# Theme sprite artwork

The 2.9 walking, riding, jumping and sleeping additions are documented in
[actions-prompts.md](actions-prompts.md).

Generated with the built-in imagegen tool. The new Moose uses an earlier Moose sprite (not included) as an identity reference; the originals are unchanged.

Runtime assets: `launcher/assets/themes/moose.png` and `launcher/assets/themes/bengal.png`. Each contains four right-facing gallop poses and four seated poses. `PetSprites` extracts connected silhouettes rather than relying on the nominal grid, then downsamples once without filtering for the TV's pixel grid. Only the small frames remain cached.

## Bengal

Use case: stylized-concept. Asset type: transparent pixel-art game animation sprite sheet for an Android TV theme. Create ONE golden brown Bengal domestic cat with dark brown rosette spots, striped ringed tail, pale muzzle/chest and green eyes. Match the detailed warm naturalistic pixel-art texture and crisp stepped edges of the reference German shepherd, but draw ONLY the cat. Sheet layout EXACTLY 4 equal columns by 2 equal rows, 1536x1024 total, each cell 384x512. Eight isolated full-body sprites centered horizontally in their own cells, feet baseline 440 px within each cell, 40 px safe padding, no overlap. Top row: four sequential side-on RUNNING TO THE RIGHT gallop poses, extended, gathering, tucked, pushing off. Same body size and identity across all frames. Bottom row: four SEATED front three-quarter cat poses, neutral eyes open, blink, relaxed eyes open with slightly curled tail, sleepy blink. Same seated body silhouette and scale in each bottom-row cell. Small domestic cat physique, not a leopard. Long graceful tail. Visible chunky pixels rather than smooth photography. Transparent background. No ground, beds, shadows, props, labels, grid lines, borders, watermark, or dogs. Reference image is for art style only.

### Layout refinement

Fix the sprite sheet layout while preserving this exact Bengal cat identity, markings, art style and the same 8 poses. Output a transparent 1536x1024 image in an EXACT 4-column 2-row grid of 384x512 cells. Every entire cat including tail and whiskers MUST stay within its own cell with at least 32px transparent margin on both sides. Top row: 4 different running-right gait poses. Scale ALL running cats down to fit, same anatomical body size across all 4, maximum width 300px, centered at x=192,576,960,1344. Feet baseline y=420. Bottom row: the four seated poses centered at x=192,576,960,1344 with baseline y=932; maximum width 280px and maximum height 350px. Keep all pixels outside cats fully transparent with NO glow or shadow or haze. No grid lines, text or additional subjects. This is a production sprite atlas sliced on exact grid boundaries.

## Moose

Use case: stylized-concept. Create a NEW game sprite sheet of Moose, the user's black-and-tan German shepherd, for a cozy pixel-art TV theme called Idiots. Reference 1 shows Moose's identity: upright ears, black face mask and saddle, golden tan chest/paws, friendly expression, black collar and tiny silver tag. Reference 2 shows the companion Bengal cat art style. Draw ONLY Moose. Match cat's warm limited palette, crisp stepped pixel-art edges, natural animal silhouette. No smoking, no laptop, no glasses, no props. Exact 1536x1024 transparent atlas, 4 equal columns and 2 equal rows. Top row: FOUR distinct running RIGHT gallop poses (extended / gathering / tucked / pushing off) with real changing leg positions. Bottom row: FOUR seated three-quarter front poses (relaxed eyes open / blink / slight head tilt / relaxed eyes open). Every full body and tail fits INSIDE its own 384x512 cell with transparent padding; maximum sprite width 300px. Same anatomical size across frames within each row. Entire dog centered at x=192/576/960/1344, paws baseline top y=430 and bottom y=940. Pixel game art, chunky individual pixels, not smooth realistic illustration. Truly transparent empty background, no ambient glow, no shadow, no grid lines, no text, no extra characters.

