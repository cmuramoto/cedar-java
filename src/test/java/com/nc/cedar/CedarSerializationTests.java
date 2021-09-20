package com.nc.cedar;

import static com.nc.cedar.CedarTestSupport.randomAlpha;
import static com.nc.cedar.CedarTestSupport.toMap;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.Test;

public class CedarSerializationTests {

	record DeserResult(long size, List<Long> times) {
	}

	private void run(Cedar cedar, String[] dict) {
		for (var i = 0; i < dict.length; i++) {
			assertEquals(i, cedar.get(dict[i]).value());
		}
	}

	private DeserResult run(int loops, int max, int length, boolean copy) throws IOException {
		var rng = ThreadLocalRandom.current();
		var dict = new String[max];
		for (var i = 0; i < max; i++) {
			var chars = randomAlpha(rng, length);

			dict[i] = chars;
		}

		var key_values = toMap(dict);
		var cedar = new Cedar();
		cedar.build(key_values);
		var size = cedar.byteSize();

		run(cedar, dict);

		var tmp = Files.createTempFile("cedar", "bin");

		cedar.serialize(tmp);
		cedar.close();

		var times = new ArrayList<Long>();

		for (var i = 0; i < loops; i++) {
			var now = System.currentTimeMillis();
			cedar = Cedar.deserialize(tmp, copy);
			var elapsed = System.currentTimeMillis() - now;
			run(cedar, dict);
			cedar.close();
			times.add(elapsed);
		}

		return new DeserResult(size, times);
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
		var cedar = new Cedar();
		cedar.build(key_values);

		run(cedar, dict);

		var tmp = Files.createTempFile("cedar", "bin");

		cedar.serialize(tmp);
		cedar.close();

		cedar = Cedar.deserialize(tmp, true);
		run(cedar, dict);
		cedar.close();

		cedar = Cedar.deserialize(tmp, false);
		run(cedar, dict);

		// re-serialize mmaped
		cedar.serialize(tmp);

		cedar = Cedar.deserialize(tmp, true);
		run(cedar, dict);
		cedar.close();

		cedar = Cedar.deserialize(tmp, false);
		run(cedar, dict);
	}

	@Test
	public void test_bench_serialization_copy() throws IOException {
		var res = run(10, 100000, 30, true);

		System.out.printf("Deserialization times (mode=%s,size=%d): %s\n", "copy", res.size, res.times);
	}

	@Test
	public void test_bench_serialization_mmap() throws IOException {
		var res = run(10, 100000, 30, false);

		System.out.printf("Deserialization times (mode=%s,size=%d): %s\n", "mmap", res.size, res.times);
	}

	@Test
	public void test_bench_serialization_mmap_large() throws IOException {
		var res = run(5, 1000000, 10, false);

		System.out.printf("Deserialization times (mode=%s,size=%d): %s\n", "mmap", res.size, res.times);
	}
}
