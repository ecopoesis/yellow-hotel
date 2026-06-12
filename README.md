# yellow-hotel

A Game Boy / Game Boy Color emulator in functional Kotlin, built to play
Pokémon Yellow — in color, with sound, at full speed.

![Pokemon Yellow title screen](docs/pokemon-cgb-title.png)

## Design

- **Functional core** (`:core`): all register/control state lives in immutable
  data classes; stepping is `step(State) -> State`. Bulk byte buffers
  (VRAM/WRAM/framebuffer) are owned by the state and mutated only inside step
  functions. No nulls (Arrow `Option`), no exceptions on hot paths — typed
  errors via `Either`/`Raise` on cold paths (ROM parsing, saves), total
  functions everywhere the hardware is total.
- **Tick-on-access bus**: every CPU M-cycle advances the rest of the machine
  before the access lands, so peripherals see bus traffic at hardware-accurate
  times. The PPU runs a per-dot pixel FIFO (fetcher, sprite stalls, window).
- **Pluggable edges**: the core emits a 160×144 RGB framebuffer and 48 kHz
  stereo samples, and consumes an abstract 8-button input. `:app-desktop` is a
  Compose Multiplatform window + `javax.sound` sink (the blocking audio write
  is the pacing clock) + keyboard input.

## Accuracy

All suites run in CI (`./gradlew :core:accuracyTest`):

| Suite | Result |
|---|---|
| SingleStepTests SM83 (500 opcode files, per-cycle bus traces) | 500/500 |
| Blargg `cpu_instrs`, `instr_timing`, `mem_timing`, `mem_timing-2`, `halt_bug` | all pass |
| Blargg `dmg_sound` / `cgb_sound` | 12/12 + 12/12 |
| dmg-acid2 / cgb-acid2 | pixel-perfect |
| Mooneye acceptance sweep | 54/66, regression-gated by `testroms/mooneye-expected.txt` |
| Pokémon Yellow | boots in color (CGB), reaches the menu via scripted input; golden-frame hashes pinned |

The remaining Mooneye failures are dot-exact PPU interrupt timing, exact boot
register fingerprints, and `timer/rapid_toggle`.

`:core` is held at **100% line coverage** (Kover gate on `check`).
Headless throughput gate: ≥4x realtime (currently ~10x on an M-series laptop).

## Running

Put a ROM next to the build (game ROMs are not distributed; the repo's
`.gitignore` keeps them out) and:

```
./gradlew :app-desktop:run                      # picks up "Pokemon Yellow.gbc"
./gradlew :app-desktop:run --args="path/to.gbc" # any GB/GBC ROM (NoMBC/MBC1/MBC5)
```

Keys: arrows = d-pad, X = A, Z = B, Enter = Start, Shift = Select.
Battery saves land in a `.sav` beside the ROM.

## Developing

```
./gradlew check                  # unit tests + 100% coverage gate on :core
./gradlew :core:accuracyTest     # hardware test-ROM suites (downloads SM83 JSON once)
./gradlew :core:perfTest         # >=4x realtime headless gate
```

Test ROMs (Blargg, Mooneye mts-20240926, acid2) are vendored under
`testroms/`. The SM83 JSON suite is fetched by `:core:downloadSm83Tests`
(pinned commit, SHA-256 verified).
