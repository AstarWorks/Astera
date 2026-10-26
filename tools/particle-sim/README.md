# :tools:particle-sim

Runs the real vanilla particle classes (unobfuscated 26.3 client jar, read in place from the launcher cache or downloaded
from the official launcher manifest into `~/.cache/astera-particle-sim`; nothing is copied into the repo) for the particle
emissions of a skill.

```
make particle-sim SKILL=bastion-greatsword-s1 [REUSE=1] [SEED=n]
  -> build/skill-lint/<skill>/plan.json     (`particles[]`: tick, type, count, pos, delta, speed, data; caster-local, +x right)
  -> build/skill-preview/<skill>/particles.json  (ticks[].particles[]: type,pos,size(half extent),sprite,rgba,light,roll,age; atlas; coverage)
```

How: providers come from `ParticleResources` (registration only, no reload); `ParticleEngine` / `ParticleRenderType` are never
initialised; `ClientLevel` is a generated subclass created without a constructor whose few queried methods go to `StubHooks`
(empty world, sky light 15); sprite sets are the vanilla `MutableSpriteSet`s bound to placeholder sprites carrying texture ids,
resolved with our ADR-0274 skins first. `RandomSupport.generateUniqueSeed` is patched in memory (no `nanoTime`) so a seed
reproduces a run. Types the real classes cannot run (block / item / vibration options need game registries) fall back to a
20-tick dot and are listed under `coverage` with `mode: fallback`.
