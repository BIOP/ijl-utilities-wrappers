# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**ijl-utilities-wrappers** is a Fiji/ImageJ2 plugin suite (BIOP, EPFL) that wraps deep learning tools (Cellpose, StarDist, Spotiflow, Omnipose, DeepSlice) and image registration tools (Elastix, Transformix) for use within ImageJ. It also provides ROI/image conversion utilities.

## Build

Maven-based, Java 8+, parent POM `org.scijava:pom-scijava:43.0.0`.

```bash
mvn clean install
mvn clean install -DskipTests
```

No custom test runner — tests are demo classes under `src/test/java/` that launch ImageJ and require local conda environments/executables, so they are not standard unit tests.

## Architecture

### Task Pattern (core abstraction for all wrappers)

Each wrapper follows a four-layer pattern:
1. **Task** (abstract) — e.g. `CellposeTask` — defines the execution contract
2. **TaskSettings** — e.g. `CellposeTaskSettings` — holds all parameters
3. **DefaultTask** — e.g. `DefaultCellposeTask` — concrete implementation that builds CLI args, runs Python/executables, parses output
4. **IJ2 Command** (`@Plugin`) — e.g. `Cellpose.java` — ImageJ2 GUI entry point

### Python Execution Bridge

`ExecutePythonInConda` handles cross-platform conda/venv activation:
- **Windows**: `cmd.exe /c conda activate ... && python -m ...`
- **Mac/Linux**: bash with direct path resolution, sets `DYLD_LIBRARY_PATH` / `LD_LIBRARY_PATH`

DeepSlice is migrating to **Appose** (`DefaultDeepSliceTask`) for modern Python process communication instead of subprocess shelling.

### Convertible Object Framework

`ConvertibleObject` uses reflection and `@Converter` annotations for automatic format conversion chains:
- `ConvertibleRois` — ImageJ ROIs ↔ SVG ↔ Transformix format ↔ RealPoint lists
- `ConvertibleImage` — ImagePlus ↔ File ↔ Dataset

### Elastix/Transformix

Native executable wrappers (not Python). `Elastix.java` and `Transformix.java` manage executable paths stored in ImageJ preferences, with OS-specific library path setup.

## Key Packages

```
ch.epfl.biop.wrappers          — Conda, ExecutePythonInConda, BiopWrappersCheck
ch.epfl.biop.wrappers.cellpose — Cellpose (<=3.1.1.1) and CellposeSAM (>=4.0.0)
ch.epfl.biop.wrappers.stardist — StarDist 2D/3D (basic + advanced)
ch.epfl.biop.wrappers.spotiflow — Spotiflow (point detection → ROIs)
ch.epfl.biop.wrappers.omnipose — Omnipose
ch.epfl.biop.wrappers.deepslice — DeepSlice (Appose-based)
ch.epfl.biop.wrappers.elastix  — Elastix registration + parameter presets
ch.epfl.biop.wrappers.transformix — Transformix image/ROI transformation
ch.epfl.biop.java.utilities.roi — ROI conversion framework
ch.epfl.biop.java.utilities.image — Image conversion framework
```

## Cross-Platform Concerns

- Path handling must use forward slashes internally (even on Windows)
- Conda activation differs per OS — changes in `ExecutePythonInConda` must be tested on all three platforms
- Mac requires `DYLD_LIBRARY_PATH` for Elastix; Linux requires `LD_LIBRARY_PATH`
- Executable paths stored in ImageJ preferences (not hardcoded)

## CI

GitHub Actions (`.github/workflows/`): builds on push to master and on PRs, uses Java 8 Zulu, deploys to `maven.scijava.org`.
