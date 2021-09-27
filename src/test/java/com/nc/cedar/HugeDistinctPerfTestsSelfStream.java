package com.nc.cedar;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * http://web.archive.org/web/20120206015921/http://www.naskitis.com/distinct_1.bz2
 */

public class HugeDistinctPerfTestsSelfStream extends BaseHugeCedarTests {

	Path src = Paths.get("/assets/cedar/distinct_1");

	BaseCedar cedar;

	int ids;

	long start;

	long finish;

	private void doRun() throws IOException {
		start = System.currentTimeMillis();
		query = 0;
		if (cedar instanceof Cedar c) {
			run(c);
		} else if (cedar instanceof ReducedCedar c) {
			run(c);
		}

		finish = System.currentTimeMillis();
	}

	void error(long v, String found, String query) {
		throw new AssertionError(String.format("Expected distinct dataset. Found %d -> %s from %s", found, query));
	}

	private void load(Stream<String> lines) {
		var sink = new ArrayList<String>();

		if (cedar instanceof Cedar c) {
			lines.forEach(s -> {
				var prev = c.get(s);
				if ((prev & BaseCedar.ABSENT_OR_NO_VALUE) != 0) {
					store(c, s, ids++);
				} else {
					var match = c.match(s);
					error(prev, c.suffix(match.from(), match.length()), s);
				}
			});

		} else if (cedar instanceof ReducedCedar c) {
			lines.forEach(s -> {
				var prev = c.get(s);
				if ((prev & BaseCedar.ABSENT_OR_NO_VALUE) != 0) {
					store(c, s, ids++);
				} else {
					var match = c.match(s);
					error(prev, c.suffix(match.from(), match.length()), s);
				}
			});
		}

		sink.clear();
	}

	@Test
	public void run() throws IOException {
		assumeEnoughMemory();
		for (var i = 0; i < 3; i++) {
			try {
				doRun();
			} finally {
				teardown(false);
			}
		}
	}

	private void run(Cedar c) {
		var sum = c.keys().mapToInt(key -> {
			var v = findPreFetch(c, key);
			assertTrue((v & BaseCedar.ABSENT_OR_NO_VALUE) == 0);
			return 1;
		}).sum();

		Assert.assertEquals(ids, sum);
	}

	private void run(ReducedCedar c) {
		var sum = c.keys().mapToInt(key -> {
			var v = findPreFetch(c, key);

			assertTrue((v & BaseCedar.ABSENT_OR_NO_VALUE) == 0);
			return 1;
		}).sum();

		Assert.assertEquals(ids, sum);
	}

	@Before
	public void setup() throws IOException {
		cedar = instantiate();
		ids = 0;

		resetCounters();

		try (var lines = Files.lines(src)) {
			load(lines);
		}
	}

	@After
	public void teardown() {
		teardown(true);
	}

	public void teardown(boolean close) {
		if (close) {
			cedar.close();
		} else {
			logStats(cedar, ids, finish - start);
		}
	}
}