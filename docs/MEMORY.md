# Allocators (`vmath.mem`, experimental)

Five small allocators for sub-allocating buffers: GPU upload memory, vertex and index pools, per-frame data. They are plain arithmetic over a **range of bytes** and return **byte offsets**, so
they work the same for a heap segment, native memory or a persistently mapped GPU buffer, and none of them touches the managed memory (the bookkeeping is in ordinary arrays). Each can be built over a
`MemorySegment` (then `slice` gives a view of a result) or from a size alone. Running out returns `NONE` (-1), not an exception, because a full upload buffer is an ordinary event. Nothing allocates after
construction (`AllocationContractTest`). None is thread-safe.

| Class | Use it for | Allocate | Free |
|---|---|---|---|
| `ArenaAllocator` | one shared lifetime: a frame's transient data, a level | bump with alignment | `reset()`, or `rewind(mark)` |
| `SlabAllocator` | many blocks of one size, freed in any order | O(1) | O(1), double frees throw |
| `FreeListAllocator` | variable sizes, freed in any order (vertex and index pools) | first or best fit over a sorted block array, alignment without waste | merges with free neighbours |
| `RingAllocator` | per-frame data released in order | contiguous, wraps and skips the end | `endFrame()` then `retireOldestFrame()` when the GPU is done |
| `PersistentBufferRing<F>` | a mapped upload buffer shared by N frames in flight | bump in the frame's region | fence per region |

**`FreeListAllocator`** keeps a sorted array of blocks that tiles the range exactly; `validate()` checks that and the free total (the tests call it throughout 30 000 random operations with both strategies,
and check alignment, no overlaps, and that freeing everything leaves one block). A failed allocation means no free block is large enough; `largestFree()` tells whether the space exists in total (fragmentation).

**`RingAllocator`** reserves contiguous pieces; one that does not fit before the end of the range wraps and the end is skipped until its frame is retired. The tests simulate 50 000 random
steps (allocations, frame ends, retirements) and check that no live allocation overlaps another and that every offset is aligned and inside the range.

**`PersistentBufferRing`** cuts the mapped range into N equal regions (rounded down to a region alignment such as 256). `beginFrame()` moves to the next region, first waiting for the fence of its previous use if
it has not signalled (counted in `stalls()`); `allocate` bumps inside the region; `endFrame()` inserts the fence. The graphics API comes in through `FenceOps<F>` (`insert`, `isSignaled`, `await`, `release`), which maps to
`glFenceSync`/`glClientWaitSync`/`glDeleteSync` or a Vulkan fence or timeline semaphore; the library calls no graphics API, and the ring was tested only against a simulated GPU that completes frames some number
of frames behind: a 3-frame ring never waits when the GPU is at most 2 frames behind, waits in every frame when it is 3 behind, never reuses a region before its frame completed, and releases every fence.
Pair it with `FrameDirtyRanges` (see `docs/BULK.md`) to upload only the elements that changed since that region was last written. `highWaterMark()` reports how much of a region is actually used.

## Measured

`MemBench`, JDK 25, one machine, 1 000 operations per call:

| Operation | Per operation |
|---|---|
| `ArenaAllocator.allocate` | 0.9 ns (the error bar is as large as the value) |
| `RingAllocator.allocate` | 2.7 ns |
| `SlabAllocator` allocate and free (1 000 blocks, freed in random order) | 5.1 ns per call, 10.2 us for 1 000 of each |
| `FreeListAllocator` best fit, allocate and free (up to 1 000 live blocks, random free order) | 0.4 us per call, 805 us for 1 000 of each |
| `HandleRegistry` create, `denseIndex`, destroy | about 3 ns each |

The free list scans its blocks on every allocation and shifts arrays on every split and merge, so its cost grows with the number of live blocks; that is fine for hundreds of allocations (pools of meshes and
textures) and the wrong tool for hundreds of thousands (use `SlabAllocator`, or sub-allocate in two levels). A free-block index (size classes) would remove the scan and has not been built.

## Not covered

Thread safety, defragmentation or moving allocations, sparse (virtual) buffers, and GPU-side allocation. The ring has not been run against a real OpenGL or Vulkan binding.
