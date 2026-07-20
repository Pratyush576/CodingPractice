# Bloom Filter — Design Document

**Use case:** Web crawler URL deduplication
**Implementation:** Pure Java, no external libraries, zero dependencies beyond the JDK

---

## 1. Problem Statement

A web crawler follows links and visits pages. Without deduplication it re-visits the
same pages infinitely. The naive solution — a `HashSet<String>` of seen URLs — works
for small crawlers but breaks at scale:

| URLs visited | Avg URL length | HashSet memory |
|---|---|---|
| 1 million  | 80 bytes | ~80 MB |
| 10 million | 80 bytes | ~800 MB |
| 1 billion  | 80 bytes | ~80 GB |

A Bloom filter holds a probabilistic fingerprint of each URL — not the URL string
itself — reducing memory by 20-40× at the cost of a small, configurable false-positive
rate. A 1% FPP means 1 in 100 new URLs is incorrectly skipped; this is acceptable
for most crawlers.

---

## 2. Bloom Filter Fundamentals

### 2.1 Core invariant

> A Bloom filter can answer "definitely not in set" with certainty,
> or "probably in set" with bounded probability.

- **False negative rate: 0%** — once a bit pattern is set for an element, it can never
  be unset, so `mightContain` never returns `false` for an inserted element.
- **False positive rate: p** — tunable at construction time via the bit-array size.

### 2.2 Data structure

```
  Bit array (m bits):
  ┌─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┐
  │0│1│0│0│1│0│0│1│0│0│1│0│0│0│0│1│  ← one long word (64 bits)
  └─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┴─┘
    ↑               ↑       ↑       ↑
    positions set by hash functions for element "example.com/page"
```

### 2.3 Operations

**put(element):**
1. Compute two base hashes h1, h2 from element
2. Derive k bit positions: `(h1 + i*h2) mod m` for i = 0 … k-1
3. Set all k bits to 1

**mightContain(element):**
1. Compute the same k bit positions
2. If ANY bit is 0 → return `false` (definitive absence)
3. If ALL bits are 1 → return `true` (probable presence)

### 2.4 Mathematical foundations

Given n expected insertions and target false-positive probability p:

```
Optimal bit count:    m = ⌈ −n · ln(p) / (ln 2)² ⌉
Optimal hash count:   k = round( (m/n) · ln 2 )
Achieved FPP:         p ≈ (1 − e^(−kn/m))^k
```

At n=1M insertions with p=1%: m=9,585,059 bits (~1.1 MB), k=7 hash functions.

**Memory comparison (1M URLs, 1% FPP):**

| Approach | Memory |
|---|---|
| `HashSet<String>` (avg 80 chars/URL) | ~80 MB |
| Bloom filter (1% FPP) | ~1.1 MB |
| **Savings** | **~72×** |

---

## 3. Design Decisions

### 3.1 Hash function: MurmurHash3 (x64, 128-bit)

**Requirements for a Bloom filter hash:**
1. Near-uniform distribution across the bit array
2. Avalanche property — small input changes produce large output changes
3. Speed — hashed on every put/contains call

**Candidates considered:**

| Hash function | Speed | Distribution | Cryptographic | Chosen? |
|---|---|---|---|---|
| `String.hashCode()` | Fast | Poor (only 32 bits) | No | No |
| SHA-256 | Slow | Excellent | Yes (unnecessary overhead) | No |
| FNV-1a | Fast | Good | No | No |
| **MurmurHash3** | Very fast | Excellent | No | **Yes** |
| xxHash | Very fast | Excellent | No | Alternative |

MurmurHash3 x64 processes 16 bytes per loop iteration using only shifts, multiplies,
and XOR — no memory allocations, no branching in the hot path.

**Seed:** Fixed at 0 for deterministic, JVM-independent output (important for
serialization — a filter written on one machine must give the same bit positions
when read on another).

### 3.2 Hash derivation: Kirsch-Mitzenmacher double hashing

Naively, k independent hash functions require k separate hash computations.
The **Kirsch-Mitzenmacher optimization** (ESA 2006) derives all k positions from
just two base hashes:

```
bitIndex_i = (h1 + i · h2) mod m,    i = 0, 1, …, k−1
```

MurmurHash3 naturally produces two independent 64-bit outputs (h1, h2) in a
single pass. This gives us k positions for the cost of one hash evaluation,
with the same asymptotic false-positive rate as k truly independent functions.

**Sign handling:** `(combinedHash & Long.MAX_VALUE) % m` strips the sign bit before
the modulo to guarantee a non-negative bit index regardless of the sum overflowing
into negative territory.

### 3.3 Bit array: `AtomicLongArray` + CAS

**Options:**

| Option | Thread safety | Overhead | Chosen? |
|---|---|---|---|
| `boolean[]` | None | Lowest | No |
| `BitSet` | Synchronized | Lock contention | No |
| `long[]` + `synchronized` | Yes, coarse | Lock per put | No |
| **`AtomicLongArray` + CAS** | Yes, fine | CAS per bit | **Yes** |
| `VarHandle` on `long[]` | Yes, fine | Similar to above | Alternative |

`AtomicLongArray` packs 64 bits per `long` word (8× denser than `boolean[]`)
and uses `compareAndSet` for lock-free thread safety. The CAS loop in `setBit`:

```java
do {
    prev = bits.get(longIdx);
    if ((prev & mask) != 0) return false;  // fast exit if already set
    next = prev | mask;
} while (!bits.compareAndSet(longIdx, prev, next));
```

Under low contention (bits are rarely contested — different elements hash to
different word indices) CAS succeeds on the first try. Under high contention on
the same word it spins, but this is rare given a 1 M-bit array.

### 3.4 Bit packing

Bits are packed into `long` words:
```
word index  = bitIndex / 64   (bitIndex >>> 6)
bit offset  = bitIndex % 64   (bitIndex &   63)
mask        = 1L << bitOffset
```

Right-shifting by 6 and masking by 63 replace division/modulo with bitwise ops —
important since these run inside the inner loop of every `put` and `mightContain`.

### 3.5 Configuration: immutable record with static factory

`BloomFilterConfig` is a Java record (implicitly immutable, auto-equals/hashCode/toString).
Construction is gated behind `BloomFilterConfig.of(n, p)`, a static factory that:
1. Validates inputs eagerly
2. Computes `m` and `k` using the optimal formulas
3. Returns a fully-validated, immutable config

**Design pattern: Factory Method + Value Object**

### 3.6 Serialization format

```
┌──────────────────────────────────────────────────────┐
│ Header (36 bytes)                                    │
│   [8B] expectedInsertions     long                   │
│   [8B] falsePositiveProbability  double (raw bits)   │
│   [8B] numBits                long                   │
│   [4B] numHashFunctions        int                   │
│   [8B] insertionCount          long                  │
├──────────────────────────────────────────────────────┤
│ Bit array (numBits/8 bytes, rounded up to 8)         │
│   [8B × arrayLength] raw long words                  │
└──────────────────────────────────────────────────────┘
```

Storing all four config fields (including `expectedInsertions` and `falsePositiveProbability`)
allows full reconstruction — the caller doesn't need to remember the original parameters
to restore a filter.

---

## 4. Class Design

```
┌────────────────────────────────────────────────────────────────────┐
│  Application / Use-case Layer                                      │
│                                                                    │
│  ┌─────────────────────────┐                                       │
│  │  UrlDeduplicator        │  Facade over BloomFilter.             │
│  │  - shouldCrawl(url)     │  Adds URL normalisation,              │
│  │  - hasBeenSeen(url)     │  domain stats, crawler semantics.     │
│  │  - printStats()         │  Pattern: Facade                      │
│  └──────────┬──────────────┘                                       │
└─────────────┼──────────────────────────────────────────────────────┘
              │ delegates to
┌─────────────┼──────────────────────────────────────────────────────┐
│  Core Layer │                                                      │
│             ▼                                                      │
│  ┌─────────────────────────┐   ┌──────────────────────────┐       │
│  │  BloomFilter            │   │  BloomFilterConfig       │       │
│  │  - put(String)          │◄──│  - expectedInsertions    │       │
│  │  - mightContain(String) │   │  - falsePositiveProb.    │       │
│  │  - mergeFrom(other)     │   │  - numBits               │       │
│  │  - writeTo / readFrom   │   │  - numHashFunctions      │       │
│  │  AtomicLongArray bits   │   │  - estimateFpp(n)        │       │
│  │  AtomicLong count       │   │  Pattern: Value Object   │       │
│  │  Pattern: no pattern —  │   │          Factory Method  │       │
│  │    raw domain object    │   └──────────────────────────┘       │
│  └──────────┬──────────────┘                                       │
│             │ delegates to                                          │
│             ▼                                                      │
│  ┌─────────────────────────┐                                       │
│  │  MurmurHash3            │  Stateless utility. Computes           │
│  │  - hash128(String)      │  long[]{h1, h2} via x64 128-bit       │
│  │  - hash128(byte[])      │  MurmurHash3 algorithm.               │
│  └─────────────────────────┘  Pattern: Utility / Strategy          │
└────────────────────────────────────────────────────────────────────┘
```

### Design patterns used

| Pattern | Where | Why |
|---|---|---|
| **Factory Method** | `BloomFilter.create()`, `BloomFilterConfig.of()` | Validates inputs, computes derived fields before construction |
| **Value Object** | `BloomFilterConfig` (record) | Immutable, equality by value not identity; safe to share |
| **Facade** | `UrlDeduplicator` | Hides probabilistic internals behind a crawler-friendly API |
| **Utility class** | `MurmurHash3` | No state, no inheritance, final class with private constructor |
| **Template Method (conceptual)** | `BloomFilter` | Core put/contains template that callers can extend by subclassing (not shown, but the structure supports it) |

---

## 5. Tradeoffs

### 5.1 Memory vs. FPP

These move in opposite directions — you can't improve both simultaneously:

```
p = 0.001 (0.1%) at 1M elements → m = 14,377,588 bits → 1.7 MB
p = 0.01  (1.0%) at 1M elements →  m =  9,585,059 bits → 1.1 MB
p = 0.1  (10.0%) at 1M elements →  m =  4,792,529 bits → 0.6 MB
```

Decreasing FPP by 10× costs roughly 1.5× more memory.

### 5.2 No deletion

Setting a bit to 1 is irreversible. Removing an element would require clearing its
k bits, but those bits might also be claimed by other elements — clearing them would
introduce false negatives (the fatal failure mode).

**When deletion is required:** Use a **Counting Bloom Filter** (each bit becomes a
counter; decrement on remove). Costs 4-8× more memory.

### 5.3 Upfront capacity planning

The filter must be sized before use. Inserting significantly more than
`expectedInsertions` elements inflates the FPP rapidly because the fill ratio
(setBitCount / numBits) approaches 1.

**When capacity is unknown:** Use a **Scalable Bloom Filter** — a chain of filters,
each 2× larger, added when the previous one exceeds a threshold fill ratio.

### 5.4 No enumeration

You cannot list the elements in a Bloom filter. Bits are mixed across elements;
individual elements cannot be recovered. This is a feature for privacy but a
limitation for debugging.

### 5.5 Thread-safety cost

CAS loops are cheaper than a `synchronized` block under low contention but add
overhead vs. a single-threaded `long[]`. If the Bloom filter is built sequentially
and then read concurrently, a plain `long[]` with a `volatile` publish (or
`VarHandle.storeFence`) is faster and still correct.

---

## 6. When NOT to Use a Bloom Filter

| Scenario | Problem | Alternative |
|---|---|---|
| Deletions required | Bits can't be cleared | Counting Bloom filter, Cuckoo filter |
| Zero false positives | Fundamental limitation | `HashSet`, sorted array + binary search |
| Tiny sets (< 1 000 elements) | HashSet overhead is negligible | `HashSet<String>` |
| Need to enumerate elements | Not recoverable from bit array | `HashSet`, persistent store |
| Adversarial inputs (DoS) | MurmurHash3 is not cryptographic | HMAC-based hash, SipHash |

---

## 7. False Positive Rate — Worked Example

Configuration: n=100 000, p=0.01 → m=958 505 bits, k=7

| Insertions | Fill ratio | Estimated FPP |
|---|---|---|
| 0 | 0% | 0.000% |
| 25 000 (25%) | ~17% | 0.006% |
| 50 000 (50%) | ~33% | 0.18% |
| 100 000 (100%) | ~50% | ~1.00% |
| 150 000 (150%) | ~62% | ~4.5% |
| 200 000 (200%) | ~73% | ~13% |

This shows why exceeding `expectedInsertions` is dangerous: FPP grows super-linearly
once the filter is overfull.

---

## 8. Memory Footprint Calculator

```
m  = ceil( -n * ln(p) / (ln2)² )
k  = round( m/n * ln2 )
memory = ceil(m / 8) bytes          ← bit array only
```

Quick estimates at 1% FPP (k=7):

| Scale | Bits (m) | Memory |
|---|---|---|
| 1 000 elements | 9 585 | 1.2 KB |
| 1 M elements | 9 585 059 | 1.1 MB |
| 10 M elements | 95 850 583 | 11 MB |
| 100 M elements | 958 505 841 | 114 MB |
| 1 B elements | 9 585 058 415 | 1.1 GB |

For a web-scale crawler visiting 1 billion pages with 0.1% FPP: ~1.7 GB — still
a fraction of storing the URL strings directly (~80 GB at 80 bytes/URL average).

---

## 9. Alternative Implementations

| Variant | vs. this implementation | Use when |
|---|---|---|
| **Counting Bloom Filter** | Counter per bit instead of single bit; ~4× more memory | Deletions needed |
| **Scalable Bloom Filter** | Chain of filters, each 2× bigger | n is unknown upfront |
| **Cuckoo Filter** | Stores fingerprints, supports deletion, slightly better FPP per bit | Deletions + better memory efficiency |
| **XOR Filter** | Static (build-once), 1.23 bits per element at 0% FPP | Immutable lookup tables (routing, CDN) |
| **Blocked Bloom Filter** | Cache-line-aware partitioning; better L1 hit rate | CPU cache-sensitive workloads |

---

## 10. Running

```bash
./gradlew :lib:run
```

---

## 11. Expected Output (abridged)

```
──────────────────────────────────────────────────────────────────────
  1. CONFIGURATION MATH
──────────────────────────────────────────────────────────────────────
  Expected (n)    FPP      Bits (m)        Hashes (k)  Memory
  ─────────────────────────────────────────────────────────────────────
  1,000           1.0%     9,585           7           1.2 KB
  100,000         1.0%     958,506         7           117.0 KB
  1,000,000       1.0%     9,585,059       7           1.1 MB
  10,000,000      1.0%     95,850,584      7           11.4 MB
  100,000,000     0.1%     1,437,758,880   10          171.5 MB

──────────────────────────────────────────────────────────────────────
  3. EMPIRICAL FALSE-POSITIVE RATE
──────────────────────────────────────────────────────────────────────
  Target FPP     : 1.00%
  Estimated FPP  : 1.00%
  Empirical FPP  : ~1.00%  (~1000 / 100,000 false positives)

──────────────────────────────────────────────────────────────────────
  4. URL DEDUPLICATOR — WEB CRAWLER SIMULATION
──────────────────────────────────────────────────────────────────────
  Crawl queue size : 50,000
  Unique pages     : 10,000
  URLs queued       : 50,000
  Duplicates blocked: 40,000
  Dedupe ratio      : 80.0%
  Estimated FPP     : 0.0001%
```
