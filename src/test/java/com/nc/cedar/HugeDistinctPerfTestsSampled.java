package com.nc.cedar;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * http://web.archive.org/web/20120206015921/http://www.naskitis.com/distinct_1.bz2
 */

public class HugeDistinctPerfTestsSampled extends BaseHugeCedarTests {

	static final int MAX_SAMPLES = 64;

	static final int MIN_LEN = 10;

	Path src = Paths.get("/assets/cedar/distinct_1");

	BaseCedar cedar;

	int ids;

	long sum;

	long start;

	long finish;

	// keep original refs to avoid weird gc behavior
	List<String> wrapped;

	byte[][] samples;

	private void doRun() throws IOException {
		start = System.currentTimeMillis();
		if (cedar instanceof Cedar c) {
			run(c);
		} else if (cedar instanceof ReducedCedar c) {
			run(c);
		}
		finish = System.currentTimeMillis();
		teardown(false);
	}

	void error(long v, String found, String query) {
		throw new AssertionError(String.format("Expected distinct dataset. Found %d -> %s from %s", found, query));
	}

	private void load(Stream<String> lines) {
		var sink = new ArrayList<String>();

		if (cedar instanceof Cedar c) {
			lines.forEach(s -> {
				var prev = c.find(s);
				if ((prev & BaseCedar.ABSENT_OR_NO_VALUE) != 0) {
					int v = ids++;
					if (sink.size() < MAX_SAMPLES && s.length() >= MIN_LEN) {
						sink.add(s);
					}
					sum += s.length();
					store(c, s, v);
				} else {
					var match = c.get(s);
					error(prev, c.suffix(match.from(), match.length()), s);
				}
			});

		} else if (cedar instanceof ReducedCedar c) {
			lines.forEach(s -> {
				var prev = c.find(s);
				if ((prev & BaseCedar.ABSENT_OR_NO_VALUE) != 0) {
					int v = ids++;

					if (sink.size() < MAX_SAMPLES && s.length() >= MIN_LEN) {
						sink.add(s);
					}
					sum += s.length();
					store(c, s, v);
				} else {
					var match = c.get(s);
					error(prev, c.suffix(match.from(), match.length()), s);
				}
			});
		}

		var vs = (double) sum;

		wrapped = sink;
		log("Keys: %d. Average key length: (samples: %.2f. universe: %.2f)", sink.size(), sink.stream().mapToInt(String::length).average().orElse(0), vs / ids);
		samples = sink.stream().map(Bits::utf8).toArray(byte[][]::new);
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

	private void run(Cedar c) {
		var samples = this.samples;
		var ops = ids;
		query = 0;
		for (var i = 0; i < ops; i++) {
			var now = System.nanoTime();
			for (var key : samples) {
				assertTrue((c.find(key) & BaseCedar.ABSENT_OR_NO_VALUE) == 0);
			}
			query += (System.nanoTime() - now);
			shuffle(samples);
		}
	}

	private void run(ReducedCedar c) {
		var samples = this.samples;
		var ops = ids;
		for (var i = 0; i < ops; i++) {
			var now = System.nanoTime();
			for (var key : samples) {
				assertTrue((c.find(key) & BaseCedar.ABSENT_OR_NO_VALUE) == 0);
			}
			this.query += (System.nanoTime() - now);
			shuffle(samples);
		}
	}

	@Before
	public void setup() {
		cedar = instantiate();
		ids = 0;
		resetCounters();
	}

	@After
	public void teardown() {
		teardown(true);
	}

	public void teardown(boolean close) {
		if (close) {
			cedar.close();
			samples = null;
			wrapped = null;
		} else {
			logStats(cedar, ids, ((long) ids) * samples.length, finish - start);
		}
	}
}
