package com.nc.cedar;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * http://web.archive.org/web/20120206015921/http://www.naskitis.com/distinct_1.bz2
 */

public class HugeDistinctPerfTests extends BaseHugeCedarTests {

	Path src = Paths.get("/assets/cedar/distinct_1");

	BaseCedar cedar;

	int ids;

	int seq;

	long start;

	long finish;

	private void doRun() throws IOException {
		try (var lines = Files.lines(src)) {
			read(lines);
			finish = System.currentTimeMillis();
		}

		teardown(false);
	}

	void error(long v, String found, String query) {
		throw new AssertionError(String.format("Expected distinct dataset. Found %d -> %s from %s", found, query));
	}

	// duplicat to devirtualize
	void load(Stream<String> lines) {
		if (cedar instanceof Cedar c) {
			lines.forEach(s -> {
				var prev = c.get(s);
				if ((prev & BaseCedar.ABSENT_OR_NO_VALUE) != 0) {
					double before = c.imageSize();
					store(c, s, ids++);
					double after = c.imageSize();

					if (before != after) {
						System.out.printf("%d\t%.2f\t%.2f\n", ids, before / 1024 / 1024, after / 1024 / 1024);
					}

				} else {
					var match = c.match(s);
					error(prev, c.suffix(match.from(), match.length()), s);
				}
			});

		} else if (cedar instanceof ReducedCedar c) {
			lines.forEach(s -> {
				var prev = c.get(s);
				if ((prev & BaseCedar.ABSENT_OR_NO_VALUE) != 0) {
					double before = c.imageSize();
					store(c, s, ids++);
					double after = c.imageSize();
					if (before != after) {
						System.out.printf("%d\t%.2f\t%.2f\n", ids, before / 1024 / 1024, after / 1024 / 1024);
					}
				} else {
					var match = c.match(s);
					error(prev, c.suffix(match.from(), match.length()), s);
				}
			});
		}
	}

	void read(Stream<String> lines) {
		query = 0;
		seq = 0;
		long sum = 0;

		if (cedar instanceof Cedar c) {
			sum = lines.mapToInt(s -> {
				assertEquals(seq++, findPreFetch(c, s));
				return 1;
			}).sum();
		} else if (cedar instanceof ReducedCedar c) {
			sum = lines.mapToInt(s -> {
				assertEquals(seq++, findPreFetch(c, s));
				return 1;
			}).sum();
		}

		Assert.assertEquals(ids, seq);
		Assert.assertEquals(ids, sum);
	}

	@Test
	public void run() throws IOException {
		assumeEnoughMemory();

		try (var lines = Files.lines(src)) {
			start = System.currentTimeMillis();
			load(lines);
		}

		for (var i = 0; i < 3; i++) {
			doRun();
		}
	}

	@Before
	public void setup() {
		resetCounters();
		cedar = instantiate();
		ids = 0;
		seq = 0;
	}

	@After
	public void teardown() {
		logStats(cedar, ids, finish - start);
		cedar.close();
	}

	public void teardown(boolean close) {
		if (close) {
			cedar.close();
		} else {
			logStats(cedar, ids, finish - start);
		}
	}
}
