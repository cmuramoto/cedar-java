package com.nc.cedar;

import static com.nc.cedar.CedarTestSupport.randomAlpha;
import static com.nc.cedar.CedarTestSupport.toMap;
import static java.lang.System.nanoTime;
import static java.lang.System.out;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

import org.junit.Test;

public class CedarSerializationTests extends BaseCedarTests {

	static class State {
		int loops;
		int max;
		int length;
		boolean copy;
		boolean reduced;

		long store;
		long size;
		List<Long> beforeTrips;
		List<Long> times;
		List<Long> afterTrips;
		String[] dict;

		State(int loops, int max, int length, boolean copy, boolean reduced) {
			super();
			this.loops = loops;
			this.max = max;
			this.length = length;
			this.copy = copy;
			this.reduced = reduced;
		}

		@Override
		public String toString() {
			var stats = beforeTrips.stream().mapToLong(v -> v).summaryStatistics();

			var ab = 1000d * (dict.length / stats.getAverage());

			stats = afterTrips.stream().mapToLong(v -> v).summaryStatistics();

			var af = 1000d * (dict.length / stats.getAverage());

			return String.format("RoundTrip (mode=%s, size=%.2fMB, reduced:%s, keys:%d, load:%s, trips: {before: %s, after: %s}, avg: {before: %.2freads/μs, after: %.2freads/μs})", copy ? "copy" : "mmap", size / (1024 * 1024d), reduced, dict.length, times, beforeTrips, afterTrips, ab, af);
		}
	}

	private void dump(String[] dict) throws IOException {
		Files.write(Paths.get("dump.txt"), Arrays.asList(dict), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	// specialized by type to avoid virtual calls
	private void run(BaseCedar cedar, String[] dict) {
		if (cedar instanceof Cedar c) {
			run(c, dict);
		} else if (cedar instanceof ReducedCedar r) {
			run(r, dict);
		}
	}

	private void run(Cedar cedar, String[] dict) {
		for (var i = 0; i < dict.length; i++) {
			assertEquals(i, cedar.get(dict[i]).value());
		}
	}

	private void run(ReducedCedar cedar, String[] dict) {
		for (var i = 0; i < dict.length; i++) {
			assertEquals(i, cedar.find(dict[i]));
		}
	}

	private void run(State r) throws IOException {
		var max = r.max;
		var loops = r.loops;
		var length = r.length;
		var rng = ThreadLocalRandom.current();
		var seen = new HashSet<String>();
		var dict = new String[max];
		for (; seen.size() < max;) {
			var chars = randomAlpha(rng, length);
			if (!seen.add(chars)) {
				LockSupport.parkNanos(1);
			}
		}

		dict = seen.toArray(dict);
		seen = null;
		r.dict = dict;

		var cedar = instantiate();
		cedar.build(dict);
		var size = cedar.imageSize();

		var btrips = new ArrayList<Long>();
		var now = nanoTime();

		for (var i = 0; i < loops; i++) {
			now = nanoTime();
			run(cedar, dict);
			btrips.add(nanoTime() - now);
		}

		var tmp = Files.createTempFile("cedar", "bin");

		now = nanoTime();
		cedar.serialize(tmp);
		r.store = nanoTime() - now;
		cedar.close();

		var times = new ArrayList<Long>();
		var trips = new ArrayList<Long>();

		for (var i = 0; i < loops; i++) {
			now = nanoTime();
			cedar = deserialize(tmp, r.copy);
			times.add(nanoTime() - now);
			now = nanoTime();
			run(cedar, dict);
			trips.add(nanoTime() - now);
			cedar.close();
		}

		r.size = size;
		r.times = times;
		r.beforeTrips = btrips;
		r.afterTrips = trips;
	}

	void runGuarded(State r) throws IOException {
		try {
			run(r);
		} catch (Exception e) {
			e.printStackTrace();
			dump(r.dict);
		}
	}

	@Test
	public void test_base_serialization() throws IOException {
		int max = 1000;
		var rng = ThreadLocalRandom.current();
		var dict = new String[max];
		for (var i = 0; i < max; i++) {
			var chars = randomAlpha(rng, 30);

			dict[i] = chars;
		}

		var key_values = toMap(dict);
		var cedar = instantiate();
		cedar.build(key_values);

		run(cedar, dict);

		var tmp = Files.createTempFile("cedar", "bin");

		cedar.serialize(tmp);
		cedar.close();

		cedar = deserialize(tmp, true);
		run(cedar, dict);
		cedar.close();

		cedar = deserialize(tmp, false);
		run(cedar, dict);

		// re-serialize mmaped
		cedar.serialize(tmp);

		cedar = deserialize(tmp, true);
		run(cedar, dict);
		cedar.close();

		cedar = deserialize(tmp, false);
		run(cedar, dict);
	}

	@Test
	public void test_bench_serialization_copy() throws IOException {
		var res = new State(5, 100000, 30, true, reduced);
		run(res);

		out.println(res);
	}

	@Test
	public void test_bench_serialization_mmap() throws IOException {
		var res = new State(5, 100000, 30, false, reduced);

		run(res);

		out.println(res);
	}

	@Test
	public void test_bench_serialization_mmap_large() throws IOException {
		try {
			var res = new State(2, 2000000, 6, false, reduced);

			run(res);

			out.println(res);
		} catch (Throwable e) {
			e.printStackTrace();
			throw e;
		}
	}
}
