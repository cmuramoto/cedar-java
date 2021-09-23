package com.nc.cedar;

import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import org.junit.Assert;

public abstract class BaseHugeCedarTests extends BaseCedarTests {

	static final ThreadLocal<byte[]> CHUNKS = ThreadLocal.withInitial(() -> new byte[9]);

	static byte[] pad(byte[] v, int n) {
		for (var ix = v.length - 1; ix >= 0; ix--) {
			v[ix] = (byte) ('0' + (n % 10));
			n /= 10;
		}
		return v;
	}

	final LongAdder alloc = new LongAdder();

	final LongAdder query = new LongAdder();

	void fill(BaseCedar cedar, int max) {
		fill(cedar, max, true);
	}

	void fill(BaseCedar cedar, int max, boolean logProgress) {
		IntStream.range(0, max).sorted().forEach(v -> {
			cedar.update(str(v), v);

			if (logProgress && v > 0 && v % 1000000 == 0) {
				log("keys: %d. stats: %s", v, cedar.allocation());
			}
		});
	}

	final long find(Cedar cedar, String key) {
		var now = System.nanoTime();
		var found = cedar.find(key);
		query.add(System.nanoTime() - now);
		return found;
	}

	void logStats(BaseCedar cedar, int keys, long elapsed) {
		var qt = toMillis(query);
		var avg = ((double) keys) / qt;
		log("Stats: (keys:%d, size: %dMB, reduced: %s, loopTime: %dms, queryTimeSum: %dms, alloc: %dms, ops/ms: %.2f)", keys, (cedar.imageSize() / 1024L / 1024L), cedar.isReduced(), elapsed, qt, toMillis(alloc), avg);
	}

	void loop(BaseCedar cedar, int max) {
		alloc.reset();
		query.reset();

		var elapsed = System.currentTimeMillis();

		var range = IntStream.range(0, max).parallel();

		// help JIT devirtualize
		if (cedar instanceof Cedar c) {
			range.forEach(v -> {
				var key = str(v);
				var now = System.nanoTime();
				var found = c.find(key);
				query.add(System.nanoTime() - now);
				Assert.assertEquals(v, found);
			});
		} else if (cedar instanceof ReducedCedar r) {
			range.forEach(v -> {
				var key = str(v);
				var now = System.nanoTime();
				var found = r.find(key);
				query.add(System.nanoTime() - now);
				Assert.assertEquals(v, found);
			});
		}

		elapsed = System.currentTimeMillis() - elapsed;
		logStats(cedar, max, elapsed);
	}

	final String str(int v) {
		var now = System.nanoTime();

		var rv = Bits.newAscii(pad(CHUNKS.get(), v));

		alloc.add(System.nanoTime() - now);

		return rv;
	}
}
