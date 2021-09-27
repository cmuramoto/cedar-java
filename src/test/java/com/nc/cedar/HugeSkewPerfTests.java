package com.nc.cedar;

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
 * http://web.archive.org/web/20120206015921/http://www.naskitis.com/skew1_1.bz2
 */

public class HugeSkewPerfTests extends BaseHugeCedarTests {

	Path src = Paths.get("/assets/cedar/skew1_1");

	BaseCedar cedar;

	int ids;

	int seq;

	int distinct;

	long start;

	long finish;

	private void doRun() throws IOException {
		try (var lines = Files.lines(src)) {
			read(lines);
		}
		teardown(false);
	}

	private void load(Stream<String> lines) {
		if (cedar instanceof Cedar c) {
			lines.forEach(s -> {
				var prev = c.get(s);
				if ((prev & BaseCedar.ABSENT_OR_NO_VALUE) != 0) {
					distinct++;
				}
				store(c, s, ids++);
			});
		} else if (cedar instanceof ReducedCedar c) {
			lines.forEach(s -> {
				var prev = c.get(s);
				if ((prev & BaseCedar.ABSENT_OR_NO_VALUE) != 0) {
					distinct++;
				}
				store(c, s, ids++);
			});
		}

	}

	private void read(Stream<String> lines) {
		seq = 0;
		query = 0;

		if (cedar instanceof Cedar c) {
			lines.forEach(s -> {
				ids++;
				var found = lookup(c, s);
				Assert.assertEquals(s, found);
			});

		} else if (cedar instanceof ReducedCedar c) {
			lines.forEach(s -> {
				ids++;
				var found = lookup(c, s);
				Assert.assertEquals(s, found);
			});
		}

		Assert.assertEquals(ids, seq);
	}

	@Test
	public void run() throws IOException {
		assumeEnoughMemory();

		try (var lines = Files.lines(src)) {
			load(lines);
		}

		for (var i = 0; i < 3; i++) {
			doRun();
		}
	}

	@Before
	public void setup() {
		cedar = instantiate();
		ids = 0;
		distinct = 0;
	}

	@After
	public void teardown() {
		teardown(true);
	}

	public void teardown(boolean close) {
		if (close) {
			cedar.close();
		} else {
			logStats(cedar, distinct, ids, finish - start);
		}
		cedar.close();
	}
}