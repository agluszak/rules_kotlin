# Incremental Compilation Implementation Plan - Executive Summary

## Overview

This document summarizes the comprehensive plan for implementing Incremental Compilation (IC) in rules_kotlin using the Kotlin Build Tools API.

**Full Plan**: [IC_IMPLEMENTATION_PLAN.md](./IC_IMPLEMENTATION_PLAN.md)

---

## The Opportunity

### Current Situation

- ✅ Build Tools API integrated
- ✅ Persistent workers running
- ✅ Deterministic ProjectId implemented
- ❌ **Every file change = full recompilation (10-60+ seconds)**

### With Incremental Compilation

- ✅ **50-70% faster incremental builds**
- ✅ Only recompile affected files
- ✅ Track source and classpath changes
- ✅ Leverage existing infrastructure

---

## Key Design Decisions

### 1. IC State Storage: Bazel Output Tree

**Decision**: Store IC state in `bazel-out/.../target/_ic/` (not `~/.cache/`)

**Why**:
- ✅ Maintains Bazel hermeticity
- ✅ Works with sandboxing
- ✅ Compatible with RBE architecture (even if disabled for RBE)
- ✅ Explicit cache invalidation via `bazel clean`

### 2. Opt-In Strategy

**Decision**: Opt-in via toolchain flag (not enabled by default)

**Configuration**:
```python
define_kt_toolchain(
    name = "dev_toolchain",
    experimental_use_incremental_compilation = True,
)
```

**Why**:
- Safe rollout
- Gradual adoption
- Per-configuration control
- Clear migration path

### 3. Local-Only (No RBE Initially)

**Decision**: Disable IC for RBE, enable for persistent workers

**Why**:
- RBE prioritizes hermeticity over IC
- Local development benefits most from IC
- CI can use RBE + action cache (still fast)
- Future work: Remote IC cache

### 4. Smart Classpath Snapshots

**Decision**: Mixed granularity based on dependency type

```kotlin
// External dependencies (rarely change) → CLASS_LEVEL (smaller)
external/ → ClassSnapshotGranularity.CLASS_LEVEL

// Local code (changes frequently) → CLASS_MEMBER_LEVEL (finer)
src/ → ClassSnapshotGranularity.CLASS_MEMBER_LEVEL
```

**Why**:
- Optimizes snapshot size vs IC effectiveness
- External deps rarely change (coarse tracking OK)
- Local code changes often (fine tracking needed)

### 5. Compiler-Driven Change Detection

**Decision**: Use `SourcesChanges.ToBeCalculated`

**Why**:
- Compiler has best knowledge of changes
- Bazel already tracks input changes
- Simplifies Starlark implementation

---

## Architecture

```
┌─────────────────────────────────────┐
│  Bazel Rule (Starlark)              │
│  - Declares IC cache as input/out   │
│  - Passes IC config to worker       │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  Persistent Worker                  │
│  - Maintains CompilationService     │
│  - Preserves ProjectId per module   │
│  - Keeps IC state across actions    │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  BuildToolsAPICompiler              │
│  - Generate classpath snapshots     │
│  - Configure IC via BTAPI           │
│  - Pass to compiler                 │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  Kotlin Compiler (IC Runner)        │
│  - Detect changes                   │
│  - Recompile only affected files    │
│  - Update IC caches                 │
└─────────────────────────────────────┘
```

---

## Implementation Phases

### Phase 1: Foundation (3 days)

- Add IC configuration to proto schema
- Add toolchain configuration attributes
- Update toolchain provider

**Output**: IC can be enabled via toolchain flag

### Phase 2: Starlark Integration (3 days)

- Declare IC cache directory as input/output
- Pass IC configuration to builder
- Update action creation

**Output**: IC config flows from rules to worker

### Phase 3: Kotlin Implementation (4 days)

- Update BuildToolsAPICompiler signature
- Implement classpath snapshot generation
- Configure BTAPI IC
- Update task executor

**Output**: IC actually works!

### Phase 4: Testing (3 days)

- Unit tests (snapshot generation, IC config)
- Integration tests (compilation scenarios)
- E2E tests (real builds)
- Performance benchmarks

**Output**: Validated IC implementation

### Phase 5: Documentation (2 days)

- User documentation
- Migration guide
- Update README
- Blog post

**Output**: Users can adopt IC

**Total**: 15 days (~3 weeks)

---

## Performance Targets

| Scenario | Without IC | With IC | Improvement |
|----------|-----------|---------|-------------|
| **Cold build** | 30s | 35s | -17% (snapshot gen overhead) |
| **No changes** | 0.5s | 0.5s | 0% (action cache) |
| **1 file changed** | 30s | 5-10s | **67-83%** 🎯 |
| **10 files changed** | 30s | 10-15s | **50-67%** 🎯 |

**Memory**: <20% increase in worker RSS

---

## Risk Management

### Top Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| **IC state corruption** | High | File-by-file backup, automatic fallback, easy cleanup |
| **Memory pressure** | Medium | Keep caches in memory, GC scheduling, JVM tuning |
| **Snapshot overhead** | Low | Cache snapshots, mixed granularity, lazy generation |
| **RBE incompatibility** | Medium | Disable IC for RBE (acceptable trade-off) |

---

## Rollout Strategy

### Week 1: Internal Testing

- Enable for test modules
- Run benchmarks
- Fix critical bugs

**Success**: IC works correctly, performance improves

### Weeks 2-3: Opt-In Preview

- Announce to community
- Gather feedback
- Iterate

**Success**: 5+ users adopt, positive feedback

### Weeks 4-6: Recommended

- Update docs to recommend IC for dev
- Add to examples
- Blog post

**Success**: Widespread adoption, no major issues

### Future: Default

- Enable by default
- Requires: RBE solution, extensive validation

---

## Success Criteria

✅ **50-70% faster incremental builds**
✅ **<20% memory overhead**
✅ **<20% cold build overhead**
✅ **No correctness issues**
✅ **Positive community feedback**
✅ **Clear documentation**

---

## Why This Will Succeed

### 1. Foundation Already Exists

- ✅ BuildToolsAPICompiler integrated
- ✅ Persistent workers running
- ✅ Deterministic ProjectId implemented
- ✅ CompilationService cached

### 2. Leverages Bazel's Strengths

- IC state as action input/output (hermetic)
- Declared dependencies (Bazel tracks changes)
- Persistent workers (state preservation)
- Action caching (handles no-change case)

### 3. Follows Proven Patterns

- Maven & Gradle already use BTAPI IC
- Similar architecture
- Known performance characteristics
- Mature BTAPI implementation

### 4. Conservative Approach

- Opt-in (safe rollout)
- Local-only initially (no RBE complexity)
- Extensive testing (unit + integration + E2E)
- Clear rollback path (disable IC flag)

### 5. Significant Value

- **50-70% time savings** for incremental builds
- Local development velocity multiplier
- No action cache regression (IC orthogonal)
- Future-ready (RBE IC cache potential)

---

## Next Steps

1. **Review plan** with rules_kotlin maintainers
2. **Create tracking issue** on GitHub
3. **Implement Phase 1** (foundation)
4. **Internal testing** and iteration
5. **Community preview** for feedback
6. **Document and announce** stable release

---

## Key Differentiators vs Maven/Gradle

| Feature | Maven | Gradle | rules_kotlin (This Plan) |
|---------|-------|--------|--------------------------|
| **IC State Storage** | Build directory | Gradle user home | Bazel output tree (hermetic) |
| **RBE Compatible** | N/A | N/A | Architecture-ready (disabled initially) |
| **ProjectId** | Random UUID | Build UUID | **Deterministic** (module-based) |
| **Caching Strategy** | None | Build service | Lazy property + persistent worker |
| **Snapshot Granularity** | Fixed | Location-based | **Smart** (external vs local) |
| **Opt-In** | Always on | Opt-in flag | Toolchain attribute (explicit) |

**Rules_kotlin advantage**: Better hermeticity + deterministic caching + Bazel integration

---

## Questions?

See the full plan for details:
- [IC_IMPLEMENTATION_PLAN.md](./IC_IMPLEMENTATION_PLAN.md) - Complete technical plan
- [BTAPI_COMPARISON.md](./BTAPI_COMPARISON.md) - Comparison with Maven/Gradle
- [BTAPI_NEXT_STEPS.md](./BTAPI_NEXT_STEPS.md) - Overall roadmap

---

**Ready to implement?** Let's do it! 🚀

The foundation is solid, the plan is comprehensive, and the value is clear. Incremental Compilation will transform local development velocity for rules_kotlin users.
