package com.nc.cedar;

import java.util.stream.IntStream;

import org.junit.Assert;
import org.junit.Assume;

public abstract class BaseHugeCedarTests extends BaseCedarTests {

	static final ThreadLocal<byte[]> CHUNKS = ThreadLocal.withInitial(() -> new byte[9]);

	static byte[] pad(byte[] v, int n) {
		for (var ix = v.length - 1; ix >= 0; ix--) {
			v[ix] = (byte) ('0' + (n % 10));
			n /= 10;
		}
		return v;
	}

	static byte[] preFetch(String s) {
		var utf8 = Bits.utf8(s);
		for (var b : utf8) {
			if (b == 0) {
				throw new UnsupportedOperationException();
			}
		}

		return utf8;
	}

	long alloc;

	long query;

	long store;

	void assumeEnoughMemory() {
		Assume.assumeTrue("This test requires -XX:MaxDirectMemorySize=4G", Bits.maxDirectMemory() >= 4L * 1024 * 1024 * 1024);
	}

	void fill(BaseCedar cedar, int max) {
		store = 0;
		fill(cedar, max, true);
	}

	void fill(BaseCedar cedar, int max, boolean logProgress) {
		if (cedar instanceof Cedar c) {
			fill(c, max, logProgress);
		} else if (cedar instanceof ReducedCedar c) {
			fill((ReducedCedar) cedar, max, logProgress);
		}
	}

	void fill(Cedar cedar, int max) {
		fill(cedar, max, true);
	}

	void fill(Cedar cedar, int max, boolean logProgress) {
		IntStream.range(0, max).sorted().forEach(v -> {
			store(cedar, str(v), v);

			if (logProgress && v > 0 && v % 1000000 == 0) {
				log("keys: %d. stats: %s", v, cedar.allocation());
			}
		});
	}

	void fill(ReducedCedar cedar, int max) {
		fill(cedar, max, true);
	}

	void fill(ReducedCedar cedar, int max, boolean logProgress) {
		IntStream.range(0, max).sorted().forEach(v -> {
			store(cedar, str(v), v);

			if (logProgress && v > 0 && v % 1000000 == 0) {
				log("keys: %d. stats: %s", v, cedar.allocation());
			}
		});
	}

	final long find(Cedar cedar, byte[] key) {
		var now = System.nanoTime();
		var found = cedar.find(key);
		query += (System.nanoTime() - now);
		return found;
	}

	final long find(Cedar cedar, String key) {
		var now = System.nanoTime();
		var found = cedar.find(key);
		query += (System.nanoTime() - now);
		return found;
	}

	final long find(ReducedCedar cedar, byte[] key) {
		var now = System.nanoTime();
		var found = cedar.find(key);
		query += (System.nanoTime() - now);
		return found;
	}

	final long find(ReducedCedar cedar, String key) {
		var now = System.nanoTime();
		var found = cedar.find(key);
		query += (System.nanoTime() - now);
		return found;
	}

	final long findPreFetch(Cedar cedar, String key) {
		var chunk = preFetch(key);
		var now = System.nanoTime();
		var found = cedar.find(chunk);
		query += (System.nanoTime() - now);
		return found;
	}

	final long findPreFetch(ReducedCedar cedar, String key) {
		var chunk = preFetch(key);
		var now = System.nanoTime();
		var found = cedar.find(chunk);
		query += (System.nanoTime() - now);
		return found;
	}

	void logStats(BaseCedar cedar, int keys, long elapsed) {
		logStats(cedar, keys, keys, elapsed);
	}

	void logStats(BaseCedar cedar, int keys, long ops, long elapsed) {
		var qt = toMillis(query);
		var wt = toMillis(store);
		var ravg = ((double) ops) / qt;
		var wavg = ((double) ops) / wt;

		var rnsavg = ((double) query) / ops;
		var wnsavg = ((double) store) / ops;

		log("Stats: (keys:%d, ops:%d, size: %dMB, reduced: %s, loopTime: %dms, query: %dms, store: %dms, alloc: %dms, ops/ms: {r: %.2f, w: %.2f}. ns/op: {r: %.2f, w: %.2f})", keys, ops, (cedar.imageSize() / 1024L / 1024L), cedar.isReduced(), elapsed, qt, wt, toMillis(alloc), ravg, wavg, rnsavg, wnsavg);
	}

	final String lookup(Cedar cedar, String key) {
		findPreFetch(cedar, key);

		var m = cedar.get(key);

		var now = System.nanoTime();
		var rv = cedar.suffix(m.from(), m.length());
		alloc += (System.nanoTime() - now);
		return rv;
	}

	final String lookup(ReducedCedar cedar, String key) {
		findPreFetch(cedar, key);
		var m = cedar.get(key);

		var now = System.nanoTime();
		var rv = cedar.suffix(m.from(), m.length());
		alloc += (System.nanoTime() - now);
		return rv;
	}

	final String lookupPreFetch(Cedar cedar, String key) {
		var chunk = preFetch(key);
		find(cedar, chunk);
		var m = cedar.get(key);

		var now = System.nanoTime();
		var rv = cedar.suffix(m.from(), m.length());
		alloc += (System.nanoTime() - now);
		return rv;
	}

	final String lookupPreFetch(ReducedCedar cedar, String key) {
		var chunk = preFetch(key);
		find(cedar, chunk);
		var m = cedar.get(key);

		var now = System.nanoTime();
		var rv = cedar.suffix(m.from(), m.length());
		alloc += (System.nanoTime() - now);
		return rv;
	}

	void loop(BaseCedar cedar, int max) {
		alloc = 0;
		query = 0;

		var elapsed = System.currentTimeMillis();

		var range = IntStream.range(0, max);

		// help JIT devirtualize
		if (cedar instanceof Cedar c) {
			range.forEach(v -> {
				var key = str(v);
				var now = System.nanoTime();
				var found = c.find(key);
				query += (System.nanoTime() - now);
				Assert.assertEquals(v, found);
			});
		} else if (cedar instanceof ReducedCedar r) {
			range.forEach(v -> {
				var key = str(v);
				var now = System.nanoTime();
				var found = r.find(key);
				query += (System.nanoTime() - now);
				Assert.assertEquals(v, found);
			});
		}

		elapsed = System.currentTimeMillis() - elapsed;
		logStats(cedar, max, elapsed);
	}

	void resetCounters() {
		alloc = 0;
		query = 0;
		store = 0;
	}

	final void store(Cedar cedar, String key, int value) {
		var now = System.nanoTime();
		cedar.update(key, value);
		store += (System.nanoTime() - now);
	}

	final void store(ReducedCedar cedar, String key, int value) {
		var now = System.nanoTime();
		cedar.update(key, value);
		store += (System.nanoTime() - now);
	}

	final String str(int v) {
		var now = System.nanoTime();

		var rv = Bits.newAscii(pad(CHUNKS.get(), v));

		alloc += (System.nanoTime() - now);

		return rv;
	}
}
